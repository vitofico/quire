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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** Local copy of the found or generated cover; null when the book has its own, or none was fetched. */
    val coverPath: String?,
    val useOriginal: Boolean,
)

/**
 * Book details and covers (`/details/v1`). Asks the server to fill and tidy a library book's
 * details and keeps the answer beside the book, never over it, so it shows offline. A found
 * or generated cover is copied next to the other covers, and only for a book with no cover of
 * its own.
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
    scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Redirects are followed by hand, so every hop is checked against the cover hosts. */
    private val coverHttp = openLibraryHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Orders the background pass's writes against an explicit tidy's. */
    private val storeMutex = Mutex()

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

    suspend fun setUseOriginal(documentId: Long, useOriginal: Boolean) = dao.setUseOriginal(documentId, useOriginal)

    /**
     * "Tidy details": always tidies, at the cost of one generation unless the shared record
     * serves it, and shows the result again if the reader had chosen the original. Its answer
     * wins over a background lookup of the same book still under way. Throws what
     * [AiClient.tidyDetails] throws, for the screen to explain; a 429 also pauses the
     * background pass. Not counted against the automatic pass's daily lookups.
     */
    suspend fun tidy(documentId: Long): DetailsResponse {
        val doc = documents.findById(documentId) ?: throw IllegalStateException("book $documentId is gone")
        val seen = dao.find(documentId)
        val response = try {
            client.tidyDetails(requestFor(doc))
        } catch (e: AiQuotaException) {
            pause(e.retryAfterSeconds ?: DAY_S)
            throw e
        } catch (e: AiHttpException) {
            if (e.code == 429) pause(e.retryAfterSeconds ?: DAY_S)
            throw e
        }
        store(doc, response, seen, useOriginal = false, background = false)
        return response
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
            val request = requestFor(doc)
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
                if (e.code !in BOOK_REFUSED) return pause(PAUSE_S)
                // This book's request is refused as it stands; asking again would change nothing.
                saveUnlessChanged(seen, seen?.copy(retryAt = null) ?: BookDetailsEntity(doc.id, null, null, false, null))
                continue
            } catch (e: Exception) {
                return pause(PAUSE_S)
            }
            val retryAt = store(doc, response, seen, useOriginal = seen?.useOriginal ?: false, background = true)
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

    private suspend fun requestFor(doc: Document): DetailsLookupBody =
        withContext(Dispatchers.IO) { detailsRequest(doc, readOpfBundle(File(doc.localPath), fallbackTitle = doc.title)) }

    /**
     * Keeps [response] for [doc], whose row read [seen] before asking, and returns when the
     * automatic pass should ask about the book again, if it should. The server may answer
     * with details other than those sent (the reader's own record of the book); they are kept
     * as they come. A [background] answer is dropped when the row changed meanwhile, so an
     * explicit tidy that landed first is never overwritten.
     */
    private suspend fun store(
        doc: Document,
        response: DetailsResponse,
        seen: BookDetailsEntity?,
        useOriginal: Boolean,
        background: Boolean,
    ): Long? {
        val (coverPath, coverMissing) = coverFor(doc, response, seen?.coverPath)
        val asked = seen?.takeIf { background }?.toDetails()?.response?.takeIf { it.cover.painting }
        val checks = if (response.cover.painting && asked != null) asked.paintingChecks + 1 else 0
        val retryAt = when {
            response.tidy == TIDY_QUOTA_EXCEEDED || response.tidy == TIDY_FAILED -> clock() + DAY_S * 1000L
            coverMissing -> clock() + PAUSE_S * 1000L
            response.cover.painting && checks < PAINTING_CHECKS -> clock() + PAINTING_WAIT_S * 1000L
            else -> null
        }
        val row = BookDetailsEntity(
            documentId = doc.id,
            responseJson = json.encodeToString(DetailsResponse.serializer(), response.copy(paintingChecks = checks)),
            coverPath = coverPath,
            useOriginal = useOriginal,
            retryAt = retryAt,
        )
        storeMutex.withLock {
            val current = dao.find(doc.id)
            if (background && current != seen) {
                if (coverPath != null && coverPath != current?.coverPath) File(coverPath).delete()
                return null
            }
            save(row)
            current?.coverPath?.takeIf { it != coverPath }?.let { File(it).delete() }
        }
        return retryAt
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

    /**
     * The local cover for [response], and whether one was due but could not be fetched. A book
     * with its own cover never gets one. The file name carries the cover's address, whose
     * `?v=` changes with the image, so an unchanged address reuses the file.
     */
    private suspend fun coverFor(doc: Document, response: DetailsResponse, previous: String?): Pair<String?, Boolean> {
        val url = response.cover.url
        if (doc.coverPath != null || url == null) return null to false
        if (!response.cover.generated && response.cover.source != ORIGIN_OPEN_LIBRARY) return null to false
        val target = File(coversDir, "$COVER_PREFIX${doc.id}-${sha256(url).take(16)}.cover")
        if (target.isFile) return target.absolutePath to false
        val bytes = try {
            if (response.cover.generated) client.detailsCover(url) else openLibraryCover(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return previous to true
        }
        return withContext(Dispatchers.IO) {
            val part = File(coversDir, "${target.name}.part")
            try {
                coversDir.mkdirs()
                part.writeBytes(bytes)
                if (part.renameTo(target)) target.absolutePath to false else previous to true
            } catch (_: IOException) {
                previous to true
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

    /** Removes cover copies no row points at any more, such as those of deleted books. */
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
 * The book as the shelves show it: the found title, authors and series, and the found cover
 * when it has none of its own.
 */
fun Document.shownWith(details: BookDetails?): Document {
    val book = details?.response?.book ?: return this
    return copy(
        title = book.title,
        author = book.authors.joinToString(", ").ifEmpty { null } ?: author,
        seriesName = book.seriesName ?: seriesName,
        seriesIndex = if (book.seriesName != null) book.seriesIndex else seriesIndex,
        coverPath = coverPath ?: details.coverPath,
    )
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
