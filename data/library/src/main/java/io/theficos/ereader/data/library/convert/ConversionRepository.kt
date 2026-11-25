package io.theficos.ereader.data.library.convert

import android.util.Log
import io.theficos.ereader.data.local.db.ConversionJobDao
import io.theficos.ereader.data.local.db.ConversionJobEntity
import io.theficos.ereader.data.local.db.DocumentDao
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where a conversion stands, as the reader sees it. */
enum class ConversionStatus { UPLOADING, QUEUED, CONVERTING, IMPORTING, READY, REVIEW, FAILED, CANCELLED }

data class Conversion(
    val id: String,
    val fileName: String,
    val bytes: Long,
    val status: ConversionStatus,
    /** Why it failed, or what it waits for: a server code or one of [ConversionRepository]'s own. */
    val problem: String?,
    /** The converter's own words about a failure. Untrusted: plain text only. */
    val detail: String?,
    /** Which pages need a look, in [ConversionStatus.REVIEW]. Untrusted: plain text only. */
    val review: ConvertReview?,
    /** When the app asks the server again, while it waits on [problem]. */
    val waitingUntil: Long?,
    val documentId: Long?,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canRemove: Boolean,
    val updatedAt: Long,
)

/** Whether the connected server can convert a PDF right now. */
sealed interface ConversionOffer {
    data class Available(val maxBytes: Long) : ConversionOffer
    data object NoServer : ConversionOffer
    data object NotOffered : ConversionOffer
    data object Unavailable : ConversionOffer
    data object SignedOut : ConversionOffer
    data object Unreachable : ConversionOffer
}

sealed interface ConversionStart {
    data class Started(val id: String) : ConversionStart
    data class AlreadyConverting(val id: String) : ConversionStart
    data class AlreadyInLibrary(val documentId: Long) : ConversionStart
    data class TooLarge(val maxBytes: Long) : ConversionStart
    data object Unreadable : ConversionStart
    data object NoServer : ConversionStart
}

/** What saving a converted EPUB into the library came to. */
sealed interface ImportOutcome {
    /** Added, or already there: either way the book is in the library. */
    data class Saved(val documentId: Long) : ImportOutcome
    data object Invalid : ImportOutcome
    data object Failed : ImportOutcome
}

/**
 * PDF to EPUB on the reader's Quire server, following the `/convert/v1` contract.
 *
 * Each conversion is a row saved before anything is sent: an idempotency key, the PDF's checksum
 * and size, and the app's own copy of the PDF (a shared file's permission ends with the screen
 * that received it). [step] moves every active row one stage on and is what the background worker
 * runs, so after process death the next run picks up exactly where the row says: an upload asks
 * the server again with the same key and gets the job in whatever state it reached.
 *
 * Stages: upload (create the job, send the PDF), convert (poll), import (download the EPUB and
 * save it through the sideload importer), acknowledge (only once the book is saved, or to clear a
 * failure), cancel (tell the server to stop), then done, failed or cancelled.
 */
class ConversionRepository(
    private val client: ConvertClient,
    private val dao: ConversionJobDao,
    private val documents: DocumentDao,
    /** The current account on its Quire server, or null without one. */
    private val owner: () -> String?,
    ownerFlow: Flow<String?>,
    private val sourceDir: File,
    private val downloadDir: File,
    private val importEpub: suspend (epub: File, fallbackTitle: String) -> ImportOutcome,
    /** Asks for a [step] soon; the app wires the background worker here. */
    private val wake: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val transfers = ConcurrentHashMap<String, Job>()

    /** No poll of a job before this time; in memory, because a restart may as well ask at once. */
    private val nextPoll = ConcurrentHashMap<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversions: Flow<List<Conversion>> = ownerFlow.distinctUntilChanged().flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else dao.observe(owner).map { rows -> rows.map { it.toConversion() } }
    }.flowOn(Dispatchers.IO)

    suspend fun offer(): ConversionOffer {
        if (owner() == null) return ConversionOffer.NoServer
        return try {
            val caps = client.capabilities()
            if (caps.available) ConversionOffer.Available(caps.maxBytes) else ConversionOffer.Unavailable
        } catch (e: ConvertHttpException) {
            when (e.code) {
                0 -> ConversionOffer.NoServer
                401 -> ConversionOffer.SignedOut
                404 -> ConversionOffer.NotOffered
                else -> ConversionOffer.Unreachable
            }
        } catch (e: IOException) {
            ConversionOffer.Unreachable
        } catch (e: SerializationException) {
            ConversionOffer.Unreachable
        }
    }

    /**
     * Copies the PDF [open] reads into the app's own storage, hashing it on the way, and saves the
     * conversion. The same PDF again gives the conversion already under way, or the book it
     * already became, instead of a second copy.
     */
    suspend fun start(fileName: String, maxBytes: Long, open: () -> InputStream?): ConversionStart =
        withContext(Dispatchers.IO) {
            val owner = owner() ?: return@withContext ConversionStart.NoServer
            sourceDir.mkdirs()
            val id = newId()
            val pdf = File(sourceDir, "$id.pdf")
            val copied = try {
                open()?.use { copyHashing(it, pdf, maxBytes) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null // the provider took its permission back
            }
            if (copied == null || copied.bytes == 0L) {
                pdf.delete()
                return@withContext ConversionStart.Unreadable
            }
            if (copied.bytes > maxBytes) {
                pdf.delete()
                return@withContext ConversionStart.TooLarge(maxBytes)
            }
            dao.latestFor(owner, copied.sha256)?.let { prior ->
                if (prior.stage in Stage.ACTIVE) {
                    pdf.delete()
                    return@withContext ConversionStart.AlreadyConverting(prior.id)
                }
                val book = prior.documentId
                if (prior.stage == Stage.DONE && book != null && documents.findById(book) != null) {
                    pdf.delete()
                    return@withContext ConversionStart.AlreadyInLibrary(book)
                }
            }
            val at = now()
            dao.insert(
                ConversionJobEntity(
                    id = id, owner = owner, idempotencyKey = newId(), fileName = fileName,
                    sha256 = copied.sha256, bytes = copied.bytes, sourcePath = pdf.absolutePath,
                    jobId = null, stage = Stage.UPLOAD, problem = null, jobJson = null, retryAt = null,
                    failures = 0, documentId = null, createdAt = at, updatedAt = at,
                ),
            )
            Log.i(TAG, "conversion=${id.take(8)} started bytes=${copied.bytes}")
            wake()
            ConversionStart.Started(id)
        }

    /** Stops a conversion that is uploading or on the server; an upload under way is cut off. */
    suspend fun cancel(id: String) {
        while (true) {
            val row = dao.find(id) ?: return
            if (row.stage !in Stage.CANCELLABLE) return
            if (dao.replaceIf(row.copy(stage = Stage.CANCEL, problem = null, retryAt = null, updatedAt = now()), row.stage)) break
        }
        transfers[id]?.cancel()
        wake()
    }

    /** Tries a failed conversion again, from where it broke or as a new job for the same PDF. */
    suspend fun retry(id: String) {
        val row = dao.find(id) ?: return
        if (!row.canRetry()) return
        val again = when (row.problem) {
            in RETRY_DOWNLOAD -> row.copy(stage = Stage.IMPORT)
            in RETRY_UPLOAD -> row.copy(stage = Stage.UPLOAD)
            // The job itself is over: a new key asks for a new one.
            else -> row.copy(stage = Stage.UPLOAD, idempotencyKey = newId(), jobId = null, jobJson = null)
        }
        val saved = dao.replaceIf(
            again.copy(problem = null, retryAt = null, failures = 0, updatedAt = now()),
            Stage.FAILED,
        )
        if (saved) wake()
    }

    /** Forgets a finished conversion and the app's copy of its PDF. */
    suspend fun remove(id: String) {
        val row = dao.find(id) ?: return
        if (row.stage !in Stage.SETTLED) return
        forget(row)
    }

    /** Wakes the worker when a conversion is under way, after an app start or an update say. */
    suspend fun resume() {
        val owner = owner() ?: return
        if (dao.inStages(owner, Stage.ACTIVE).isNotEmpty()) wake()
    }

    /**
     * Moves every active conversion one stage on. Answers how long until the next step is
     * worth running, or null when nothing is left to do. [beforeTransfer] runs before an upload
     * or download, so the worker can show itself while it moves the bytes.
     */
    suspend fun step(beforeTransfer: suspend () -> Unit = {}): Long? = mutex.withLock {
        prune()
        val owner = owner() ?: return@withLock null
        var next: Long? = null
        for (row in dao.inStages(owner, Stage.ACTIVE)) {
            val wait = try {
                advance(row, beforeTransfer)
            } catch (e: IOException) {
                Log.i(TAG, "conversion=${row.id.take(8)} stage=${row.stage} network failure")
                NETWORK_RETRY_MS
            } catch (e: SerializationException) {
                Log.w(TAG, "conversion=${row.id.take(8)} stage=${row.stage} unreadable answer")
                NETWORK_RETRY_MS
            } catch (e: ConvertHttpException) {
                Log.i(TAG, "conversion=${row.id.take(8)} stage=${row.stage} answer=${e.code}")
                e.retryAfterMs(NETWORK_RETRY_MS)
            }
            if (wait != null) next = minOf(next ?: wait, wait)
        }
        next
    }

    private suspend fun advance(row: ConversionJobEntity, beforeTransfer: suspend () -> Unit): Long? {
        val at = now()
        row.retryAt?.let { if (it > at) return it - at }
        nextPoll[row.id]?.let { if (it > at) return it - at }
        return when (row.stage) {
            Stage.UPLOAD -> upload(row, beforeTransfer)
            Stage.CONVERT -> poll(row)
            Stage.IMPORT -> fetch(row, beforeTransfer)
            Stage.ACKNOWLEDGE -> acknowledge(row)
            Stage.CANCEL -> stopOnServer(row)
            else -> null
        }
    }

    private suspend fun upload(row: ConversionJobEntity, beforeTransfer: suspend () -> Unit): Long? {
        val pdf = row.sourcePath?.let(::File)?.takeIf { it.isFile } ?: return fail(row, SOURCE_MISSING)
        // The same key with the same values answers the job in whatever state it reached, which
        // is how an upload interrupted by process death learns where it stands.
        val job = try {
            client.create(row.idempotencyKey, row.sha256, row.bytes)
        } catch (e: ConvertHttpException) {
            return when (e.code) {
                409 -> fail(row, IDEMPOTENCY_CONFLICT)
                413 -> fail(row, SOURCE_TOO_LARGE)
                422 -> fail(row, INVALID_REQUEST)
                429 -> wait(row, e.detail ?: TOO_MANY_ACTIVE_JOBS, e.retryAfterMs(TOO_MANY_WAIT_MS))
                503 -> fail(row, CONVERTER_UNAVAILABLE)
                else -> unexpected(row, e)
            }
        }
        val known = row.copy(jobId = job.id, jobJson = encode(job))
        if (job.state != "created") return follow(known, job)
        if (!save(known)) return 0 // the reader cancelled meanwhile
        beforeTransfer()
        val queued = try {
            transfer(row.id) { client.upload(job.id, pdf) } ?: return 0
        } catch (e: ConvertHttpException) {
            return when {
                e.code == 409 && e.detail == UPLOAD_IN_PROGRESS -> wait(known, UPLOAD_IN_PROGRESS, UPLOAD_IN_PROGRESS_WAIT_MS)
                // not_accepting_source: an earlier upload arrived and only its answer was lost.
                e.code == 409 -> follow(known, client.job(job.id))
                e.code == 408 || e.code >= 500 || (e.code == 422 && e.detail in RESEND) ->
                    broke(known, UPLOAD_FAILED, MAX_UPLOAD_TRIES, UPLOAD_RETRY_MS)
                e.code == 413 -> fail(known, SOURCE_TOO_LARGE)
                e.code == 415 -> fail(known, NOT_A_PDF)
                e.code == 422 -> fail(known, e.detail ?: INVALID_REQUEST)
                else -> unexpected(known, e)
            }
        } catch (e: IOException) {
            return broke(known, UPLOAD_FAILED, MAX_UPLOAD_TRIES, UPLOAD_RETRY_MS)
        }
        Log.i(TAG, "conversion=${row.id.take(8)} uploaded")
        return follow(known.copy(failures = 0), queued)
    }

    private suspend fun poll(row: ConversionJobEntity): Long? {
        val jobId = row.jobId ?: return move(row, Stage.UPLOAD, 0)
        val job = try {
            client.job(jobId)
        } catch (e: ConvertHttpException) {
            return unexpected(row, e)
        }
        return follow(row, job)
    }

    private suspend fun fetch(row: ConversionJobEntity, beforeTransfer: suspend () -> Unit): Long? {
        val jobId = row.jobId ?: return move(row, Stage.UPLOAD, 0)
        val expected = row.job()?.result ?: return poll(row)
        downloadDir.mkdirs()
        val epub = File(downloadDir, "${row.id}.epub")
        try {
            beforeTransfer()
            val got = try {
                client.download(jobId, epub)
            } catch (e: ConvertHttpException) {
                return when {
                    e.code == 410 -> fail(row, RESULT_GONE)
                    e.code == 409 -> poll(row)
                    e.code >= 500 -> broke(row, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
                    else -> unexpected(row, e)
                }
            } catch (e: IOException) {
                return broke(row, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
            }
            if (got.bytes != expected.bytes || got.sha256 != expected.sha256) {
                return broke(row, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
            }
            // The importer treats the EPUB like any sideloaded file and spots a book already there.
            return when (val outcome = importEpub(epub, row.fileName.substringBeforeLast('.'))) {
                is ImportOutcome.Saved -> move(row.copy(documentId = outcome.documentId, failures = 0), Stage.ACKNOWLEDGE, 0)
                ImportOutcome.Invalid -> fail(row, INVALID_EPUB)
                ImportOutcome.Failed -> fail(row, IMPORT_FAILED)
            }
        } finally {
            epub.delete()
        }
    }

    /** Only after the book is saved (or to clear a failure): this deletes the server's files. */
    private suspend fun acknowledge(row: ConversionJobEntity): Long? {
        val jobId = row.jobId
        if (jobId != null) {
            try {
                client.acknowledge(jobId)
            } catch (e: ConvertHttpException) {
                when {
                    e.code == 404 && e.detail == JOB_NOT_FOUND -> Unit // nothing left to clear
                    e.code == 409 -> return poll(row)
                    else -> return unexpected(row, e)
                }
            }
        }
        return if (row.documentId != null) settle(row, Stage.DONE) else fail(row, row.problem ?: FAILED)
    }

    private suspend fun stopOnServer(row: ConversionJobEntity): Long? {
        val jobId = row.jobId ?: return settle(row, Stage.CANCELLED)
        try {
            client.cancel(jobId)
        } catch (e: ConvertHttpException) {
            when {
                e.code == 401 || e.code == 429 || e.code >= 500 -> return wait(row, null, e.retryAfterMs(NETWORK_RETRY_MS))
                // Finished before the cancel arrived: clear what it left instead of importing it.
                e.code == 409 -> try {
                    if (client.job(jobId).state in FINISHED) client.acknowledge(jobId)
                } catch (_: ConvertHttpException) {
                    // Gone or already cleared: the server forgets it within 48 hours either way.
                }
                else -> Unit // 404: nothing left to stop
            }
        }
        return settle(row, Stage.CANCELLED)
    }

    /** Moves [row] to wherever the server's [job] says it is. */
    private suspend fun follow(row: ConversionJobEntity, job: ConvertJob): Long? {
        val r = row.copy(jobId = job.id, jobJson = encode(job), retryAt = null)
        return when (job.state) {
            "created" -> move(r.copy(problem = null), Stage.UPLOAD, 0)
            "queued", "running" -> {
                nextPoll[row.id] = now() + POLL_MS
                move(r.copy(problem = null), Stage.CONVERT, POLL_MS)
            }
            "ready", "review" ->
                move(r.copy(problem = null), if (r.documentId != null) Stage.ACKNOWLEDGE else Stage.IMPORT, 0)
            "failed" -> move(r.copy(problem = job.failure?.code ?: FAILED), Stage.ACKNOWLEDGE, 0)
            "cancelled" -> settle(r, Stage.CANCELLED)
            "acknowledged", "expired" ->
                if (r.documentId != null) settle(r, Stage.DONE) else fail(r, row.problem ?: RESULT_GONE)
            // A state this app does not know yet: keep asking.
            else -> {
                nextPoll[row.id] = now() + POLL_MS
                move(r, Stage.CONVERT, POLL_MS)
            }
        }
    }

    private suspend fun unexpected(row: ConversionJobEntity, e: ConvertHttpException): Long? = when {
        e.code == 401 -> wait(row, SIGNED_OUT, SIGNED_OUT_WAIT_MS)
        e.code == 404 && e.detail == JOB_NOT_FOUND ->
            if (row.documentId != null) settle(row, Stage.DONE) else fail(row, JOB_GONE)
        // `Not Found`: conversion is off on this server now.
        e.code == 404 -> fail(row, CONVERSION_OFF)
        e.code == 429 || e.code >= 500 -> wait(row, null, e.retryAfterMs(NETWORK_RETRY_MS))
        else -> fail(row, e.detail ?: INVALID_REQUEST)
    }

    /** A transfer broke: tried again a few times, never in an endless loop, then the reader decides. */
    private suspend fun broke(row: ConversionJobEntity, problem: String, tries: Int, retryMs: Long): Long? {
        val failures = row.failures + 1
        Log.i(TAG, "conversion=${row.id.take(8)} $problem attempt=$failures")
        return if (failures >= tries) fail(row.copy(failures = failures), problem)
        else wait(row.copy(failures = failures), null, retryMs)
    }

    private suspend fun wait(row: ConversionJobEntity, problem: String?, ms: Long): Long {
        save(row.copy(problem = problem, retryAt = now() + ms))
        return ms
    }

    private suspend fun move(row: ConversionJobEntity, stage: String, wait: Long): Long =
        if (save(row, stage)) wait else 0

    private suspend fun fail(row: ConversionJobEntity, problem: String): Long? {
        val failed = row.copy(problem = problem, retryAt = null)
        if (!failed.copy(stage = Stage.FAILED).canRetry()) return settle(failed, Stage.FAILED)
        save(failed, Stage.FAILED)
        return null
    }

    /** Over for good: the app's copy of the PDF goes. */
    private suspend fun settle(row: ConversionJobEntity, stage: String): Long? {
        if (save(row.copy(sourcePath = null, retryAt = null), stage)) {
            row.sourcePath?.let { File(it).delete() }
        }
        nextPoll.remove(row.id)
        return null
    }

    /** Saves [row] at [stage], unless the reader changed it meanwhile (a cancel, say). */
    private suspend fun save(row: ConversionJobEntity, stage: String = row.stage): Boolean {
        val saved = dao.replaceIf(row.copy(stage = stage, updatedAt = now()), row.stage)
        if (saved && stage != row.stage) {
            Log.i(TAG, "conversion=${row.id.take(8)} stage=$stage problem=${row.problem}")
        }
        return saved
    }

    private suspend fun <T> transfer(id: String, block: suspend () -> T): T? = coroutineScope {
        val running = async { block() }
        transfers[id] = running
        try {
            running.await()
        } catch (e: CancellationException) {
            ensureActive() // the worker itself stopping is not the reader's cancel
            null
        } finally {
            transfers.remove(id)
        }
    }

    private suspend fun prune() {
        for (row in dao.settledBefore(Stage.SETTLED, now() - KEEP_SETTLED_MS)) forget(row)
    }

    private suspend fun forget(row: ConversionJobEntity) {
        row.sourcePath?.let { File(it).delete() }
        dao.delete(row.id)
    }

    private fun ConversionJobEntity.job(): ConvertJob? =
        jobJson?.let { runCatching { json.decodeFromString(ConvertJob.serializer(), it) }.getOrNull() }

    private fun encode(job: ConvertJob): String = json.encodeToString(ConvertJob.serializer(), job)

    private fun ConversionJobEntity.canRetry(): Boolean {
        if (stage != Stage.FAILED) return false
        val havePdf = sourcePath?.let { File(it).isFile } == true
        return when (problem) {
            in RETRY_DOWNLOAD -> true
            in RETRY_UPLOAD, in RETRY_NEW_JOB -> havePdf
            // The server's own verdict on its failure: retryable means a new job may succeed.
            else -> havePdf && job()?.failure?.let { it.code == problem && it.retryable } == true
        }
    }

    private fun ConversionJobEntity.toConversion(): Conversion {
        val job = job()
        val finished = if (job?.state == "review") ConversionStatus.REVIEW else ConversionStatus.READY
        val status = when (stage) {
            Stage.UPLOAD -> ConversionStatus.UPLOADING
            Stage.CONVERT -> if (job?.state == "running") ConversionStatus.CONVERTING else ConversionStatus.QUEUED
            Stage.IMPORT -> ConversionStatus.IMPORTING
            Stage.ACKNOWLEDGE -> if (documentId == null) ConversionStatus.FAILED else finished
            Stage.DONE -> finished
            Stage.FAILED -> ConversionStatus.FAILED
            else -> ConversionStatus.CANCELLED
        }
        return Conversion(
            id = id,
            fileName = fileName,
            bytes = bytes,
            status = status,
            problem = problem,
            detail = job?.failure?.detail?.takeIf { status == ConversionStatus.FAILED },
            review = job?.review?.takeIf { status == ConversionStatus.REVIEW },
            waitingUntil = retryAt?.takeIf { stage in Stage.ACTIVE },
            documentId = documentId,
            canCancel = stage in Stage.CANCELLABLE,
            canRetry = canRetry(),
            canRemove = stage in Stage.SETTLED,
            updatedAt = updatedAt,
        )
    }

    private class Copied(val bytes: Long, val sha256: String)

    /** Stops reading one byte past [maxBytes]: a file that large is refused anyway. */
    private fun copyHashing(input: InputStream, dest: File, maxBytes: Long): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        dest.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (total <= maxBytes) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                total += n
            }
        }
        return Copied(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun ConvertHttpException.retryAfterMs(default: Long): Long =
        retryAfterSeconds?.takeIf { it > 0 }?.times(1000) ?: default

    internal object Stage {
        const val UPLOAD = "upload"
        const val CONVERT = "convert"
        const val IMPORT = "import"
        const val ACKNOWLEDGE = "acknowledge"
        const val CANCEL = "cancel"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        val ACTIVE = listOf(UPLOAD, CONVERT, IMPORT, ACKNOWLEDGE, CANCEL)
        val CANCELLABLE = listOf(UPLOAD, CONVERT)
        val SETTLED = listOf(DONE, FAILED, CANCELLED)
    }

    companion object {
        private const val TAG = "Conversion"

        // The app's own problem codes; the server's come through as they are.
        const val SOURCE_MISSING = "source_missing"
        const val UPLOAD_FAILED = "upload_failed"
        const val DOWNLOAD_FAILED = "download_failed"
        const val IMPORT_FAILED = "import_failed"
        const val INVALID_EPUB = "invalid_epub"
        const val RESULT_GONE = "result_gone"
        const val JOB_GONE = "job_gone"
        const val CONVERSION_OFF = "conversion_off"
        const val SIGNED_OUT = "signed_out"
        const val INVALID_REQUEST = "invalid_request"
        const val FAILED = "failed"

        // Server codes the app acts on.
        const val CONVERTER_UNAVAILABLE = "converter_unavailable"
        const val IDEMPOTENCY_CONFLICT = "idempotency_conflict"
        const val SOURCE_TOO_LARGE = "source_too_large"
        const val TOO_MANY_ACTIVE_JOBS = "too_many_active_jobs"
        const val DAILY_LIMIT_REACHED = "daily_limit_reached"
        const val UPLOAD_IN_PROGRESS = "upload_in_progress"
        const val NOT_A_PDF = "not_a_pdf"
        const val HOSTING_SUBSCRIPTION_REQUIRED = "hosting_subscription_required"
        private const val JOB_NOT_FOUND = "job_not_found"
        private val RESEND = setOf("size_mismatch", "checksum_mismatch")
        private val FINISHED = setOf("ready", "review", "failed")

        private val RETRY_DOWNLOAD = setOf(DOWNLOAD_FAILED, IMPORT_FAILED)
        private val RETRY_UPLOAD = setOf(UPLOAD_FAILED, CONVERTER_UNAVAILABLE)
        private val RETRY_NEW_JOB = setOf(RESULT_GONE, JOB_GONE, IDEMPOTENCY_CONFLICT)

        /** The contract asks for at most one poll every 5 s. */
        const val POLL_MS = 5_000L
        const val NETWORK_RETRY_MS = 30_000L
        const val UPLOAD_RETRY_MS = 10_000L
        const val UPLOAD_IN_PROGRESS_WAIT_MS = 30_000L
        const val TOO_MANY_WAIT_MS = 60_000L
        const val SIGNED_OUT_WAIT_MS = 5 * 60_000L
        /** One automatic retry of an upload; after that the reader decides, metered or not. */
        const val MAX_UPLOAD_TRIES = 2
        const val MAX_DOWNLOAD_TRIES = 3
        const val KEEP_SETTLED_MS = 7 * 24 * 60 * 60_000L
    }
}
