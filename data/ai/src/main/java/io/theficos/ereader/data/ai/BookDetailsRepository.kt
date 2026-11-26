package io.theficos.ereader.data.ai

import android.content.SharedPreferences
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.SIDELOAD_URL_PREFIX
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.BookDetailsDao
import io.theficos.ereader.data.local.db.BookDetailsEntity
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A library book's stored details answer, as the app shows it. */
data class BookDetails(
    val response: DetailsResponse,
    /** Local copy of the found or generated cover; null when the book shows its own, or none was fetched. */
    val coverPath: String?,
    val useOriginal: Boolean,
)

/**
 * A tidy waiting for the reader to accept or reject what it proposes, ended by
 * [BookDetailsRepository.apply], [BookDetailsRepository.close] or
 * [BookDetailsRepository.dismiss].
 */
class DetailsProposal internal constructor(
    /** The book as it was when the tidy began. */
    val document: Document,
    val answer: DetailsResponse,
    internal val request: DetailsLookupBody,
    /** The answer stored for the book when the tidy began, if any. */
    val previous: BookDetails?,
    internal val turn: Int,
    /** The book's row before the tidy's decline was held in it, put back by [BookDetailsRepository.dismiss]. */
    internal val held: BookDetailsEntity?,
) {
    internal val open = AtomicBoolean(true)
}

/** The cover a choice gives: the answer that names it and the local copy of its image, none for the book's own or no cover. */
data class CoverPreview(val response: DetailsResponse, val coverPath: String?)

/**
 * Book details and covers (`/details/v1`). Asks the server to fill and tidy a library book's
 * details and keeps the answer beside the book, never over it, so it shows offline. A found
 * or generated cover is copied next to the other covers, for a book with no cover of its own
 * or whose reader chose another. The reader's choices (the fields kept, the cover, a rejected
 * tidy) are kept with the answer.
 *
 * Nothing is sent unless the server offers the feature, the reader has opted in to AI (the
 * book's details reach the model provider), and the "Tidy up book details" setting
 * ([showTidied]) is on. Stored answers belong to one account on one server ([owner]) and are
 * dropped when that changes.
 */
class BookDetailsRepository(
    private val client: AiClient,
    private val ai: AiRepository,
    private val documents: DocumentRepository,
    private val dao: BookDetailsDao,
    private val coversDir: File,
    /** For Open Library covers: no account login may reach a third-party host. */
    openLibraryHttp: OkHttpClient,
    private val showTidied: StateFlow<Boolean>,
    /** The account and server the answers are kept for; null when there is none. */
    private val owner: Flow<String?>,
    /** True on a network that is not metered, such as Wi-Fi: the automatic pass waits for one. */
    private val unmetered: StateFlow<Boolean>,
    /** Holds the automatic pass's count for the day and the owner of the stored answers. */
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Redirects are followed by hand, so every hop is checked against the cover hosts. */
    private val coverHttp = openLibraryHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Orders the background pass's writes against the reader's apply and reject. */
    private val storeMutex = Mutex()

    /** Per book, how many tidies the reader started; a background lookup begun before the latest is dropped. */
    private val tidies = ConcurrentHashMap<Long, Int>()

    /** Per book, the previews open: the automatic pass leaves those books alone meanwhile. */
    private val previewing = ConcurrentHashMap<Long, Int>()

    /** Until when the automatic pass waits, after the server said "not now". In memory: a new start tries again. */
    @Volatile private var pausedUntil = 0L

    /** True while the server offers book details, the reader has opted in, and the setting is on. */
    val available: Flow<Boolean> = combine(ai.config, ai.preferences, showTidied, ::isAvailable).distinctUntilChanged()

    private fun isAvailable(config: AiConfig?, preferences: AiPreferences?, on: Boolean) =
        config?.configured == true && config.detailsSupported && preferences?.aiEnabled == true && on

    private fun mayRunAutomatically() =
        isAvailable(ai.config.value, ai.preferences.value, showTidied.value) && unmetered.value

    /** Every stored answer by book id, decoded once for all the screens that show them. */
    private val stored: StateFlow<Map<Long, BookDetails>> = dao.observeAll()
        .map { rows -> rows.mapNotNull { row -> row.toDetails()?.let { row.documentId to it } }.toMap() }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The stored answers, while the setting is on and the reader has not opted out of AI. An
     * opt-in not known yet (offline, say) keeps them showing.
     */
    private val showable: Flow<Map<Long, BookDetails>> =
        combine(stored, showTidied, ai.preferences) { all, on, preferences ->
            if (on && preferences?.aiEnabled != false) all else emptyMap()
        }

    /** The answers the shelves show, by book id: [showable], less those whose reader chose the original. */
    val shown: StateFlow<Map<Long, BookDetails>> = showable
        .map { all -> all.filterValues { !it.useOriginal } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** True while any answer is stored, so its setting stays in reach even with AI off. */
    val hasStored: Flow<Boolean> = stored.map { it.isNotEmpty() }.distinctUntilChanged()

    /** One book's stored answer, whether or not the reader chose the original; null while it may not show. */
    fun observe(documentId: Long): Flow<BookDetails?> = showable.map { it[documentId] }.distinctUntilChanged()

    /** Ordered against the other writes, which copy the row whole, so none of them puts back the old choice. */
    suspend fun setUseOriginal(documentId: Long, useOriginal: Boolean) =
        storeMutex.withLock { dao.setUseOriginal(documentId, useOriginal) }

    /**
     * "Improve details": always tidies, at the cost of one generation unless the shared record
     * serves it, and holds the answer for the reader to preview; none of it shows until
     * [apply]. The tidy already moved the reader's pointer on the server to that record, so
     * its key is kept as declined right away, and a preview the app never got to end (the
     * process died) still counts as rejected. While the preview is open the automatic pass
     * leaves the book alone, and a lookup of it already under way is dropped, so it never
     * overwrites what the reader applies or rejects. Throws what [AiClient.tidyDetails] throws, for the screen to explain;
     * a 429 also pauses the background pass. Not counted against the automatic pass's daily
     * lookups.
     */
    suspend fun propose(documentId: Long): DetailsProposal {
        val turn = tidies.merge(documentId, 1, Int::plus)!!
        previewing.merge(documentId, 1, Int::plus)
        try {
            val doc = documents.findById(documentId) ?: throw IllegalStateException("book $documentId is gone")
            val previous = dao.find(documentId)?.toDetails()
            val request = requestFor(doc, choice = null)
            val answer = try {
                client.tidyDetails(request)
            } catch (e: AiQuotaException) {
                pause(e.retryAfterSeconds ?: DAY_S)
                throw e
            } catch (e: AiHttpException) {
                if (e.code == 429) pause(e.retryAfterSeconds ?: DAY_S)
                throw e
            }
            return DetailsProposal(doc, answer, request, previous, turn, holdDecline(doc, answer, request, turn))
        } catch (e: Throwable) {
            previewing.computeIfPresent(documentId) { _, open -> (open - 1).takeIf { it > 0 } }
            throw e
        }
    }

    /**
     * The cover [choice] gives, from the record the tidy stored, so trying it costs no
     * generation. A painted cover shows as the drawn cover it will be painted on: a painting is
     * asked for only by [apply]. Throws when the lookup or the image download fails.
     */
    suspend fun coverPreview(proposal: DetailsProposal, choice: String): CoverPreview {
        val response = if (choice == COVER_KEEP) {
            proposal.answer
        } else {
            client.lookupDetails(proposal.request.copy(coverChoice = if (choice == COVER_PAINT) COVER_DRAW else choice))
        }
        return CoverPreview(response, coverImage(proposal.document, response, chosen = choice != COVER_KEEP))
    }

    /**
     * Keeps the [accepted] field groups of [proposal]'s answer and the cover of [choice], as
     * [cover] previewed it; a null [cover] keeps the cover shown now, as when its preview
     * failed, so the text changes still apply. A painted cover is asked for here, and checked
     * again an hour later; a server that can no longer paint keeps the drawn cover. Returns
     * what is now stored, or null when a later tidy of the book overtook this one. A failure
     * leaves the preview open, to try again or close. Runs to the end in the repository's
     * scope, so leaving the screen meanwhile neither loses the reader's choice nor rejects it.
     */
    suspend fun apply(proposal: DetailsProposal, accepted: List<String>, choice: String, cover: CoverPreview?): BookDetails? =
        scope.async { applyNow(proposal, accepted, choice, cover) }.await()

    private suspend fun applyNow(proposal: DetailsProposal, accepted: List<String>, choice: String, cover: CoverPreview?): BookDetails? {
        val id = proposal.document.id
        if (!proposal.open.get() || tidiesOf(id) != proposal.turn) return null.also { endPreview(proposal) }
        val originalBefore = dao.find(id)?.useOriginal == true
        val doc = proposal.document
        val previous = proposal.previous
        var chosen = cover ?: previous?.let { CoverPreview(it.response, it.coverPath) }
            ?: CoverPreview(proposal.answer.copy(cover = DetailsCover()), null)
        var coverChoice = choice.takeUnless { it == COVER_KEEP }
        if (choice == COVER_PAINT && cover != null) {
            val painted = try {
                client.lookupDetails(proposal.request.copy(coverChoice = COVER_PAINT))
            } catch (e: AiHttpException) {
                if (e.code != 409 || e.detail != COVER_ART_UNAVAILABLE) throw e
                null
            }
            if (painted == null) {
                // The server stopped painting: keep the drawn cover the preview showed, and ask for that from now on.
                coverChoice = COVER_DRAW
            } else {
                chosen = if (painted.cover.url == chosen.response.cover.url) {
                    chosen.copy(response = painted)
                } else {
                    // Until the painted image downloads, the drawn one shows.
                    CoverPreview(painted, coverFor(doc, painted, chosen.coverPath, chosen = true).first)
                }
            }
        }
        val coverPath = chosen.coverPath?.takeIf { File(it).isFile }
        val response = proposal.answer.copy(
            cover = chosen.response.cover,
            origins = proposal.answer.origins - "cover" + chosen.response.origins.filterKeys { it == "cover" },
            accepted = accepted,
            coverChoice = coverChoice,
            basis = proposal.request.book,
        )
        val coverDue = chosen.response.cover.url != null && coverPath == null && (doc.coverPath == null || coverChoice != null)
        val retryAt = when {
            response.cover.painting -> clock() + PAINTING_WAIT_S * 1000L
            coverDue -> clock() + PAUSE_S * 1000L
            else -> null
        }
        val useOriginal = storeMutex.withLock {
            if (tidiesOf(id) != proposal.turn) return null.also { endPreview(proposal) }
            val current = dao.find(id)
            // Applying shows the found details, unless the reader chose the original while Apply ran: that came later.
            val useOriginal = current?.useOriginal == true && !originalBefore
            save(BookDetailsEntity(id, encode(response), coverPath, useOriginal, retryAt))
            // A replaced cover is left for the sweep, so a shelf still holding its path can draw it meanwhile.
            current?.coverPath?.takeIf { it != coverPath }?.let { File(it).setLastModified(clock()) }
            useOriginal
        }
        endPreview(proposal)
        return BookDetails(response, coverPath, useOriginal)
    }

    /**
     * Ends [proposal]'s preview, rejecting its answer: [propose] already kept its key as
     * declined, so a later lookup served that record shows none of its changes, and the book
     * keeps what it showed.
     */
    fun close(proposal: DetailsProposal) {
        endPreview(proposal)
    }

    /**
     * Ends [proposal]'s preview with nothing to decide, as when the tidy changes nothing: the
     * book's row goes back to what it was before [propose], declining nothing.
     */
    suspend fun dismiss(proposal: DetailsProposal) {
        if (!endPreview(proposal)) return
        val id = proposal.document.id
        storeMutex.withLock {
            if (tidiesOf(id) != proposal.turn) return
            val held = proposal.held
            if (held != null) save(held) else dao.delete(id)
        }
    }

    /**
     * Keeps [answer]'s key as declined in the book's row, so the tidy counts as rejected until
     * the reader applies it, and returns the row as it was. A book with no answer yet gets one
     * that shows none of the record's changes and no cover; it holds the book's own details,
     * so an older version of the app, which knows nothing of declined records, shows those.
     * A book without a cover is looked up again for one.
     */
    private suspend fun holdDecline(doc: Document, answer: DetailsResponse, request: DetailsLookupBody, turn: Int): BookDetailsEntity? =
        storeMutex.withLock {
            val current = dao.find(doc.id)
            if (tidiesOf(doc.id) != turn) return@withLock current
            val stored = current?.toDetails()?.response
            save(
                if (current != null && stored != null) {
                    current.copy(responseJson = encode(stored.copy(declined = answer.key)))
                } else {
                    val rejected = DetailsResponse(
                        key = answer.key, book = request.book, tidy = answer.tidy,
                        accepted = emptyList(), declined = answer.key, basis = request.book,
                    )
                    BookDetailsEntity(doc.id, encode(rejected), null, useOriginal = false, retryAt = if (doc.coverPath == null) clock() else null)
                },
            )
            current
        }

    private fun tidiesOf(id: Long) = tidies[id] ?: 0

    /** Ends [proposal]'s preview once; false when it had already ended. */
    private fun endPreview(proposal: DetailsProposal): Boolean {
        if (!proposal.open.compareAndSet(true, false)) return false
        previewing.computeIfPresent(proposal.document.id) { _, open -> (open - 1).takeIf { it > 0 } }
        return true
    }

    /**
     * Keeps the automatic pass going: at start, when the feature becomes available or the
     * device reaches an unmetered network, and whenever a book joins the library. Any change
     * cancels a pass under way. After a pass that had to stop, it waits until it may go on and
     * runs again. First drops the stored answers when the account or server changed, so one
     * account's details never show under another. Never returns.
     */
    suspend fun followLibrary() {
        val library = documents.observeLibrary().map { books -> books.mapTo(HashSet()) { it.id } }.distinctUntilChanged()
        combine(owner, available, unmetered, library) { who, on, free, ids -> Triple(who, on && free, ids) }
            .distinctUntilChanged()
            .collectLatest { (who, go, _) ->
                forgetOtherOwners(who)
                if (!go) return@collectLatest
                while (true) {
                    val next = try {
                        runAutoPass()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "book details pass failed", e)
                        clock() + PAUSE_S * 1000L
                    } ?: return@collectLatest
                    sleep((next - clock()).coerceAtLeast(0L))
                }
            }
    }

    /**
     * The automatic pass: quietly, once per book, for a book that lacks a cover, an author or a
     * description, or whose title looks like a file name. At most [DAILY_LOOKUPS] lookups a
     * day, since a lookup may spend a generation of the reader's allowance. A book whose tidy
     * could not run (allowance spent, or failed) is asked about again a day later. A book whose
     * cover artwork is still being painted is asked about again an hour later, up to
     * [PAINTING_CHECKS] times; those lookups cost nothing, so they go first and are not counted,
     * and never use up the day's lookups for new books. The pass
     * stops at the first sign the server cannot answer now (offline, rate limited, off, opted
     * out, allowance spent), so a patchy library never turns into a burst of retries.
     *
     * Returns when it may run again: when a pause ends (as long as a 429's Retry-After says, a
     * day without one, half an hour for other failures), the next day once the day's lookups
     * are spent, or when a book is next due; null when nothing is waiting.
     */
    suspend fun runAutoPass(): Long? {
        if (!mayRunAutomatically()) return null
        if (clock() < pausedUntil) return pausedUntil
        val rows = dao.all().associateBy { it.documentId }
        sweepCovers(rows.values.mapNotNullTo(HashSet()) { it.coverPath })
        var due: Long? = null
        val painting = rows.values.filter { it.awaitsPainting() }.mapTo(HashSet()) { it.documentId }
        val library = documents.observeLibrary().first().sortedByDescending { it.id in painting }
        for (doc in library) {
            if (!mayRunAutomatically()) return null
            val seen = rows[doc.id]
            if (seen != null) {
                val retryAt = seen.retryAt ?: continue
                if (retryAt > clock()) {
                    due = minOf(due ?: retryAt, retryAt)
                    continue
                }
            }
            val since = tidiesOf(doc.id)
            if (previewing.containsKey(doc.id)) {
                // The reader is looking at a tidy of this book: ask once they are done.
                val soon = clock() + PAUSE_S * 1000L
                due = minOf(due ?: soon, soon)
                continue
            }
            val request = requestFor(doc, seen?.toDetails()?.response?.coverChoice)
            if (seen == null && !needsDetails(doc, request.book)) {
                saveUnlessChanged(null, BookDetailsEntity(doc.id, null, null, useOriginal = false, retryAt = null))
                continue
            }
            if (doc.id !in painting) {
                val used = lookupsToday()
                if (used >= DAILY_LOOKUPS) return startOfTomorrow()
                countLookups(used + 1)
            }
            val response = try {
                client.lookupDetails(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiQuotaException) {
                return pause(e.retryAfterSeconds ?: DAY_S)
            } catch (e: AiHttpException) {
                if (e.code == 429) return pause(e.retryAfterSeconds ?: DAY_S)
                if (seen != null && request.coverChoice != null &&
                    (e.code == 422 || (e.code == 409 && e.detail == COVER_ART_UNAVAILABLE))
                ) {
                    // The server stopped painting, or takes no cover choice: keep what shows, ask for no more.
                    saveUnlessChanged(seen, seen.withCoverChoice(if (e.code == 409) COVER_DRAW else null))
                    continue
                }
                if (e.code !in BOOK_REFUSED) return pause(PAUSE_S)
                // This book's request is refused as it stands; asking again would change nothing.
                saveUnlessChanged(seen, seen?.copy(retryAt = null) ?: BookDetailsEntity(doc.id, null, null, false, null))
                continue
            } catch (e: Exception) {
                return pause(PAUSE_S)
            }
            val retryAt = store(doc, response, seen, request.book, since)
            when (response.tidy) {
                TIDY_QUOTA_EXCEEDED -> {
                    // The allowance is spent: no more lookups today.
                    countLookups(DAILY_LOOKUPS)
                    return startOfTomorrow()
                }
                TIDY_FAILED -> return pause(PAUSE_S)
            }
            if (retryAt != null) due = minOf(due ?: retryAt, retryAt)
        }
        return due
    }

    private fun pause(seconds: Long): Long {
        pausedUntil = clock() + seconds * 1000L
        return pausedUntil
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun startOfTomorrow(): Long = today().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun lookupsToday(): Int =
        if (prefs.getLong(KEY_DAY, -1L) == today().toEpochDay()) prefs.getInt(KEY_LOOKUPS, 0) else 0

    private fun countLookups(count: Int) {
        prefs.edit().putLong(KEY_DAY, today().toEpochDay()).putInt(KEY_LOOKUPS, count).apply()
    }

    /** Drops every stored answer and its cover when they were kept for another account or server. */
    private suspend fun forgetOtherOwners(who: String?) {
        if (prefs.getString(KEY_OWNER, null) == who) return
        storeMutex.withLock {
            dao.clear()
            withContext(Dispatchers.IO) { coverFiles().forEach { it.delete() } }
        }
        pausedUntil = 0L
        prefs.edit().putString(KEY_OWNER, who).apply()
    }

    /** The request for [doc], with the reader's cover [choice] while this server takes one. */
    private suspend fun requestFor(doc: Document, choice: String?): DetailsLookupBody {
        val body = withContext(Dispatchers.IO) { detailsRequest(doc, readOpfBundle(File(doc.localPath), fallbackTitle = doc.title)) }
        return if (ai.config.value?.coverChoiceSupported == true) body.copy(coverChoice = choice) else body
    }

    /**
     * Keeps [response] for [doc], whose row read [seen] before asking with the fields [sent],
     * and returns when the automatic pass should ask about the book again, if it should. The
     * server may answer with details other than those sent (the reader's own record of the
     * book); they are kept as they come, with the reader's choices so far. The answer is
     * dropped when the row changed meanwhile, or the reader started a tidy of the book since
     * [turn], so what they applied or rejected is never overwritten.
     */
    private suspend fun store(doc: Document, response: DetailsResponse, seen: BookDetailsEntity?, sent: DetailsBook, turn: Int): Long? {
        val before = seen?.toDetails()?.response
        val (coverPath, coverMissing) = coverFor(doc, response, seen?.coverPath, chosen = before?.coverChoice != null)
        val checks = if (response.cover.painting && before?.cover?.painting == true) before.paintingChecks + 1 else 0
        val retryAt = when {
            response.tidy == TIDY_QUOTA_EXCEEDED || response.tidy == TIDY_FAILED -> clock() + DAY_S * 1000L
            coverMissing -> clock() + PAUSE_S * 1000L
            response.cover.painting && checks < PAINTING_CHECKS -> clock() + PAINTING_WAIT_S * 1000L
            else -> null
        }
        val kept = if (before != null && response.key == before.declined) {
            // A record the reader rejected brings none of its changes: the book keeps what it showed, with the new cover.
            before.copy(
                cover = response.cover,
                origins = before.origins - "cover" + response.origins.filterKeys { it == "cover" },
                paintingChecks = checks,
            )
        } else {
            response.copy(
                paintingChecks = checks,
                // The fields the reader kept from this record; a new record shows all of its own.
                accepted = before?.accepted?.takeIf { response.key == before.key },
                coverChoice = before?.coverChoice,
                declined = before?.declined,
                // The same record again keeps the fields it was asked for, so a field the catalog changed since still shows.
                basis = if (response.cached && response.key == before?.key) before.basis else sent,
            )
        }
        val row = BookDetailsEntity(doc.id, encode(kept), coverPath, useOriginal = seen?.useOriginal ?: false, retryAt = retryAt)
        storeMutex.withLock {
            val current = dao.find(doc.id)
            if (current != seen || tidiesOf(doc.id) != turn) {
                if (coverPath != null && coverPath != current?.coverPath) File(coverPath).delete()
                return null
            }
            save(row)
            // A replaced cover is left for the sweep, so a shelf still holding its path can draw it meanwhile.
            current?.coverPath?.takeIf { it != coverPath }?.let { File(it).setLastModified(clock()) }
        }
        return retryAt
    }

    private fun encode(response: DetailsResponse) = json.encodeToString(DetailsResponse.serializer(), response)

    /** This row with the reader's cover choice replaced, waiting for nothing. */
    private fun BookDetailsEntity.withCoverChoice(choice: String?): BookDetailsEntity {
        val response = toDetails()?.response ?: return copy(retryAt = null)
        return copy(responseJson = encode(response.copy(coverChoice = choice)), retryAt = null)
    }

    private suspend fun saveUnlessChanged(seen: BookDetailsEntity?, row: BookDetailsEntity) = storeMutex.withLock {
        if (dao.find(row.documentId) == seen) save(row)
    }

    /** The book may have been deleted while the server answered; its answer then has nowhere to go. */
    private suspend fun save(row: BookDetailsEntity) {
        try {
            dao.save(row)
        } catch (_: SQLiteConstraintException) {
            row.coverPath?.let { File(it).delete() }
        }
    }

    /** [coverImage], or [previous] and true when one was due but could not be fetched. */
    private suspend fun coverFor(doc: Document, response: DetailsResponse, previous: String?, chosen: Boolean): Pair<String?, Boolean> =
        try {
            coverImage(doc, response, chosen) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previous to true
        }

    /**
     * The local copy of [response]'s found or generated cover, fetched unless already kept; null
     * when it names none, or when the book has its own and the reader [chosen] no other. The
     * file name carries the cover's address, whose `?v=` changes with the image, so an
     * unchanged address reuses the file. Throws when the image cannot be fetched or kept.
     */
    private suspend fun coverImage(doc: Document, response: DetailsResponse, chosen: Boolean): String? {
        val url = response.cover.url
        if ((doc.coverPath != null && !chosen) || url == null) return null
        if (!response.cover.generated && response.cover.source != ORIGIN_OPEN_LIBRARY) return null
        val target = File(coversDir, "$COVER_PREFIX${doc.id}-${sha256(url).take(16)}.cover")
        if (target.isFile) return target.absolutePath
        val bytes = if (response.cover.generated) client.detailsCover(url) else openLibraryCover(url)
        return withContext(Dispatchers.IO) {
            val part = File(coversDir, "${target.name}.part")
            try {
                coversDir.mkdirs()
                part.writeBytes(bytes)
                if (!part.renameTo(target)) throw IOException("couldn't keep the cover")
                target.absolutePath
            } finally {
                part.delete()
            }
        }
    }

    /**
     * An Open Library cover, over https from Open Library's cover host and the Internet Archive
     * hosts it redirects to, and no larger than [MAX_COVER_BYTES]. Cancelling the caller
     * cancels the download.
     */
    private suspend fun openLibraryCover(address: String): ByteArray = withContext(Dispatchers.IO) {
        var url = address.toHttpUrlOrNull()
        repeat(MAX_HOPS) {
            val hop = url?.takeIf { it.isCoverHost() } ?: throw IOException("not an Open Library cover address")
            val bytes = coverHttp.newCall(Request.Builder().url(hop).get().build()).await().use { resp ->
                if (resp.isRedirect) {
                    url = resp.header("Location")?.let(hop::resolve)
                    return@use null
                }
                val body = resp.body
                if (!resp.isSuccessful || body == null || body.contentType()?.type != "image") {
                    throw IOException("no cover (${resp.code})")
                }
                val source = body.source()
                if (body.contentLength() > MAX_COVER_BYTES || source.request(MAX_COVER_BYTES + 1)) {
                    throw IOException("cover too large")
                }
                source.buffer.readByteArray()
            }
            if (bytes != null) return@withContext bytes
        }
        throw IOException("too many redirects")
    }

    private fun HttpUrl.isCoverHost() =
        isHttps && (host == "covers.openlibrary.org" || host == "archive.org" || host.endsWith(".archive.org"))

    private fun coverFiles(): List<File> =
        coversDir.listFiles { file -> file.name.startsWith(COVER_PREFIX) }.orEmpty().toList()

    /** Removes cover copies no row has pointed at for half an hour, such as those of deleted books or replaced covers. */
    private suspend fun sweepCovers(live: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = clock() - PAUSE_S * 1000L
        coverFiles().filter { it.absolutePath !in live && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    /** Waiting to ask again for cover artwork being painted, a free lookup. */
    private fun BookDetailsEntity.awaitsPainting(): Boolean {
        if (retryAt == null) return false
        val response = toDetails()?.response ?: return false
        return response.cover.painting && response.tidy != TIDY_QUOTA_EXCEEDED && response.tidy != TIDY_FAILED
    }

    private fun BookDetailsEntity.toDetails(): BookDetails? {
        val raw = responseJson ?: return null
        val response = runCatching { json.decodeFromString(DetailsResponse.serializer(), raw) }.getOrNull() ?: return null
        return BookDetails(response, coverPath, useOriginal)
    }

    companion object {
        /** The automatic pass's lookups a local day. "Tidy details" is not counted. */
        const val DAILY_LOOKUPS = 25
        private const val TAG = "BookDetails"
        private const val COVER_PREFIX = "details-"
        private const val PAUSE_S = 30 * 60L
        private const val DAY_S = 24 * 60 * 60L
        private const val PAINTING_WAIT_S = 60 * 60L
        /** How often the pass asks again for artwork still being painted before keeping the drawn cover. */
        private const val PAINTING_CHECKS = 3
        private const val MAX_HOPS = 5
        private const val MAX_COVER_BYTES = 10L * 1024 * 1024
        private const val KEY_DAY = "auto_lookup_day"
        private const val KEY_LOOKUPS = "auto_lookups"
        private const val KEY_OWNER = "owner"
        private const val COVER_ART_UNAVAILABLE = "cover_art_unavailable"
        /** Statuses that refuse this book's request rather than say the server cannot answer now. */
        private val BOOK_REFUSED = setOf(400, 413, 422)
    }
}

/**
 * What the app sends for [doc]: the details the reader sees, the library row's where it has
 * them (they may come from the catalog), the book file's for the rest, cut to the server's
 * limits. The identity is the one insights send.
 */
internal fun detailsRequest(doc: Document, opf: MetadataBundle): DetailsLookupBody {
    val source = if (doc.downloadUrl.startsWith(SIDELOAD_URL_PREFIX)) ORIGIN_FILE else ORIGIN_CATALOG
    return DetailsLookupBody(
        identity = doc.aiIdentity(),
        book = DetailsBook(
            title = doc.title.clean(512) ?: opf.title.clean(512) ?: doc.title,
            authors = listOfNotNull(doc.author.clean(256) ?: opf.author.clean(256)),
            seriesName = doc.seriesName.clean(256) ?: opf.seriesName.clean(256),
            seriesIndex = (doc.seriesIndex ?: opf.seriesPosition?.toDouble())?.takeIf { it in 0.0..10_000.0 },
            description = opf.description.clean(8192),
            subjects = opf.subjects.mapNotNull { it.clean(80) }.distinct().take(64),
            language = opf.language.clean(32),
            isbn = opf.isbn.clean(64),
            year = opf.publishDate?.trim()?.let { Regex("^[0-9]{4}").find(it)?.value?.toInt() },
            publisher = opf.publisher.clean(256),
        ),
        source = source,
        cover = if (doc.coverPath != null) source else null,
    )
}

private fun String?.clean(max: Int): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

/** Whether the automatic pass should ask about a book: no cover, no author, no description, or a file name for a title. */
internal fun needsDetails(doc: Document, book: DetailsBook): Boolean =
    doc.coverPath == null || book.authors.isEmpty() || book.description == null || looksLikeFileName(book.title)

private val ebookExtension = Regex("""\.(epub|kepub|pdf|mobi|azw3?|fb2|txt)$""", RegexOption.IGNORE_CASE)
private val releaseTag = Regex("""[\[(]\s*(retail|epub|mobi|pdf|ebook|scan|v\d+)\s*[\])]""", RegexOption.IGNORE_CASE)
private val joinedWords = Regex("""^\S+[.-]\S+[.-]\S+$""")

/** "dune_frank_herbert_(retail).epub" and the like: a title that is really a file name. */
internal fun looksLikeFileName(title: String): Boolean {
    val t = title.trim()
    return '_' in t || ebookExtension.containsMatchIn(t) || releaseTag.containsMatchIn(t) ||
        joinedWords.matches(t) ||
        // No capital letter at all, in a script that has them.
        (t.any { it.isLowerCase() } && t.none { it.isUpperCase() })
}

/**
 * The book as the shelves show it: the found title, authors and series the reader kept, and
 * the found cover when it has none of its own or the reader chose it.
 */
fun Document.shownWith(details: BookDetails?): Document {
    val book = details?.response?.book ?: return this
    val series = details.shows(FIELD_SERIES, this) && book.seriesName != null
    return copy(
        title = if (details.shows(FIELD_TITLE, this)) book.title else title,
        author = book.authors.joinToString(", ").ifEmpty { null }?.takeIf { details.shows(FIELD_AUTHORS, this) } ?: author,
        seriesName = if (series) book.seriesName else seriesName,
        seriesIndex = if (series) book.seriesIndex else seriesIndex,
        coverPath = if (details.showsCover(this)) details.coverPath else coverPath,
    )
}

/**
 * Whether the answer's value of a field group ([DETAILS_FIELDS]) shows for [doc]: the reader
 * kept it, and the library row's value has not changed since it was sent. A value the row
 * lacks came from the book file, which does not change under it. The description comes only
 * from the file, so only the reader's choice decides it.
 */
fun BookDetails.shows(group: String, doc: Document): Boolean {
    if (response.accepted?.contains(group) == false) return false
    val basis = response.basis ?: return true
    return when (group) {
        FIELD_TITLE -> doc.title.clean(512)?.let { it == basis.title } ?: true
        FIELD_AUTHORS -> doc.author.clean(256)?.let { listOf(it) == basis.authors } ?: true
        FIELD_SERIES -> doc.seriesName.clean(256)?.let { it == basis.seriesName && (doc.seriesIndex ?: basis.seriesIndex) == basis.seriesIndex } ?: true
        else -> true
    }
}

/** Whether the found or drawn cover shows for [doc]: it has none of its own, or the reader chose another. */
fun BookDetails.showsCover(doc: Document): Boolean =
    coverPath != null && (doc.coverPath == null || response.coverChoice != null)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
