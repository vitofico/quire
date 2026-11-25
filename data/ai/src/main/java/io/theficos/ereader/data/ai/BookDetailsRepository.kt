package io.theficos.ereader.data.ai

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * ([showTidied]) is on.
 */
class BookDetailsRepository(
    private val client: AiClient,
    private val ai: AiRepository,
    private val documents: DocumentRepository,
    private val dao: BookDetailsDao,
    private val coversDir: File,
    /** For Open Library covers: no account login may reach a third-party host. */
    private val openLibraryHttp: OkHttpClient,
    private val showTidied: StateFlow<Boolean>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val passMutex = Mutex()
    /** Orders the background pass's writes against an explicit tidy's. */
    private val storeMutex = Mutex()

    /** Until when the automatic pass waits, after the server said "not now". In memory: a new start tries again. */
    @Volatile private var pausedUntil = 0L

    /** True while the server offers book details, the reader has opted in, and the setting is on. */
    val available: Flow<Boolean> = combine(ai.config, ai.preferences, showTidied) { config, prefs, on ->
        config?.configured == true && config.detailsSupported && prefs?.aiEnabled == true && on
    }.distinctUntilChanged()

    private fun availableNow(): Boolean {
        val config = ai.config.value
        return config?.configured == true && config.detailsSupported &&
            ai.preferences.value?.aiEnabled == true && showTidied.value
    }

    /** One book's stored answer, whether or not the reader chose the original; null while the setting is off. */
    fun observe(documentId: Long): Flow<BookDetails?> =
        combine(dao.observe(documentId), showTidied) { row, on -> if (on) row?.toDetails() else null }

    /** The answers the shelves show, by book id: the setting is on and the reader kept the found details. */
    fun observeShown(): Flow<Map<Long, BookDetails>> =
        combine(dao.observeAll(), showTidied) { rows, on ->
            if (!on) emptyMap()
            else rows.filterNot { it.useOriginal }.mapNotNull { row -> row.toDetails()?.let { row.documentId to it } }.toMap()
        }

    suspend fun setUseOriginal(documentId: Long, useOriginal: Boolean) = dao.setUseOriginal(documentId, useOriginal)

    /**
     * "Tidy details": always tidies, at the cost of one generation unless the shared record
     * serves it, and shows the result again if the reader had chosen the original. Its answer
     * wins over a background lookup of the same book still under way. Throws what
     * [AiClient.tidyDetails] throws, for the screen to explain; a 429 also pauses the
     * background pass.
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

    /** Runs [runAutoPass] when the feature becomes available and whenever a book joins the library. Never returns. */
    suspend fun followLibrary() {
        combine(available, documents.observeLibrary().map { books -> books.map { it.id }.toSet() }, ::Pair)
            .distinctUntilChanged()
            .conflate()
            .collect { (on, _) ->
                if (!on) return@collect
                try {
                    runAutoPass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "book details pass failed", e)
                }
            }
    }

    /**
     * The automatic pass: quietly, once per book, for a book that lacks a cover, an author or a
     * description, or whose title looks like a file name. A book is asked about again only
     * after the allowance was spent or the tidy failed, a day later. The pass stops at the first
     * sign the server cannot answer now (offline, rate limited, off, opted out, allowance spent)
     * and waits before asking again: as long as a 429's Retry-After says, a day without one, half
     * an hour otherwise. So a patchy library never turns into a burst of retries.
     */
    suspend fun runAutoPass() = passMutex.withLock {
        if (!availableNow() || clock() < pausedUntil) return@withLock
        sweepCovers(dao.all().mapNotNullTo(HashSet()) { it.coverPath })
        for (doc in documents.observeLibrary().first()) {
            val seen = dao.find(doc.id)
            if (seen != null && (seen.retryAt ?: Long.MAX_VALUE) > clock()) continue
            val request = requestFor(doc)
            if (seen == null && !needsDetails(doc, request.book)) {
                saveUnlessChanged(seen, BookDetailsEntity(doc.id, null, null, useOriginal = false, retryAt = null))
                continue
            }
            val response = try {
                client.lookupDetails(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiQuotaException) {
                return@withLock pause(e.retryAfterSeconds ?: DAY_S)
            } catch (e: AiHttpException) {
                if (e.code == 429) return@withLock pause(e.retryAfterSeconds ?: DAY_S)
                if (e.code !in BOOK_REFUSED) return@withLock pause(PAUSE_S)
                // This book's request is refused as it stands; asking again would change nothing.
                saveUnlessChanged(seen, seen?.copy(retryAt = null) ?: BookDetailsEntity(doc.id, null, null, false, null))
                continue
            } catch (e: Exception) {
                return@withLock pause(PAUSE_S)
            }
            store(doc, response, seen, useOriginal = seen?.useOriginal ?: false, background = true)
            when (response.tidy) {
                TIDY_QUOTA_EXCEEDED -> return@withLock pause(DAY_S)
                TIDY_FAILED -> return@withLock pause(PAUSE_S)
            }
        }
    }

    private fun pause(seconds: Long) {
        pausedUntil = clock() + seconds * 1000L
    }

    private suspend fun requestFor(doc: Document): DetailsLookupBody =
        withContext(Dispatchers.IO) { detailsRequest(doc, readOpfBundle(File(doc.localPath), fallbackTitle = doc.title)) }

    /**
     * Keeps [response] for [doc], whose row read [seen] before asking. The server may answer
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
    ) {
        val (coverPath, coverMissing) = coverFor(doc, response, seen?.coverPath)
        val retryAt = when {
            response.tidy == TIDY_QUOTA_EXCEEDED || response.tidy == TIDY_FAILED -> clock() + DAY_S * 1000L
            coverMissing -> clock() + PAUSE_S * 1000L
            else -> null
        }
        val row = BookDetailsEntity(
            documentId = doc.id,
            responseJson = json.encodeToString(DetailsResponse.serializer(), response),
            coverPath = coverPath,
            useOriginal = useOriginal,
            retryAt = retryAt,
        )
        storeMutex.withLock {
            val current = dao.find(doc.id)
            if (background && current != seen) {
                if (coverPath != null && coverPath != current?.coverPath) File(coverPath).delete()
                return
            }
            save(row)
            current?.coverPath?.takeIf { it != coverPath }?.let { File(it).delete() }
        }
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

    private suspend fun openLibraryCover(url: String): ByteArray = withContext(Dispatchers.IO) {
        openLibraryHttp.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val type = resp.body?.contentType()
            if (!resp.isSuccessful || type?.type != "image") throw IOException("no cover (${resp.code})")
            resp.body!!.bytes()
        }
    }

    /** Removes cover copies no row points at any more, such as those of deleted books. */
    private suspend fun sweepCovers(live: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = clock() - PAUSE_S * 1000L
        coversDir.listFiles { file -> file.name.startsWith(COVER_PREFIX) }.orEmpty()
            .filter { it.absolutePath !in live && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    private fun BookDetailsEntity.toDetails(): BookDetails? {
        val raw = responseJson ?: return null
        val response = runCatching { json.decodeFromString(DetailsResponse.serializer(), raw) }.getOrNull() ?: return null
        return BookDetails(response, coverPath, useOriginal)
    }

    private companion object {
        const val TAG = "BookDetails"
        const val COVER_PREFIX = "details-"
        const val PAUSE_S = 30 * 60L
        const val DAY_S = 24 * 60 * 60L
        /** Statuses that refuse this book's request rather than say the server cannot answer now. */
        val BOOK_REFUSED = setOf(400, 413, 422)
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
