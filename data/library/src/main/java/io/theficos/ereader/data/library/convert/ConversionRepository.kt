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
import kotlinx.coroutines.CoroutineScope
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
    data object NoSpace : ConversionStart
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
 *
 * Every upload and download is counted before it starts, so one cut off with the process counts
 * toward the few tries a transfer gets, and a run moves at most one of them.
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
    /** True on a metered network: an upload waits for another unless the reader agreed to it. */
    private val metered: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Two quick shares of the same PDF make one conversion. */
    private val startMutex = Mutex()
    private val transfers = ConcurrentHashMap<String, Job>()

    // In memory, because a restart may as well ask at once.
    /** Nothing asked about a conversion before this time. */
    private val notBefore = ConcurrentHashMap<String, Long>()

    /** Since when a job is polled, which spaces the polls out. */
    private val pollingSince = ConcurrentHashMap<String, Long>()

    /** Network failures in a row, which space the retries out. */
    private val networkErrors = ConcurrentHashMap<String, Int>()

    /** Since when the server says its converter isn't running. */
    private val unavailableSince = ConcurrentHashMap<String, Long>()

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
     * already became, instead of a second copy; one that failed or was cancelled makes way.
     * [allowMetered] is whether the reader agreed to send it over a metered network.
     */
    suspend fun start(
        fileName: String,
        sizeBytes: Long?,
        maxBytes: Long,
        allowMetered: Boolean,
        open: () -> InputStream?,
    ): ConversionStart = startMutex.withLock {
        withContext(Dispatchers.IO) {
            val owner = owner() ?: return@withContext ConversionStart.NoServer
            sourceDir.mkdirs()
            if (!roomFor(sizeBytes ?: 0)) return@withContext ConversionStart.NoSpace
            val id = newId()
            val pdf = File(sourceDir, "$id.pdf")
            var saved = false
            try {
                val copied = try {
                    open()?.use { copyHashing(it, pdf, maxBytes) }
                } catch (e: IOException) {
                    // A full disk is an I/O error too.
                    if (!roomFor(0)) return@withContext ConversionStart.NoSpace
                    null
                } catch (e: SecurityException) {
                    null // the provider took its permission back
                }
                if (copied == null || copied.bytes == 0L) return@withContext ConversionStart.Unreadable
                if (copied.bytes > maxBytes) return@withContext ConversionStart.TooLarge(maxBytes)
                dao.latestFor(owner, copied.sha256)?.let { prior ->
                    if (prior.stage in Stage.ACTIVE) return@withContext ConversionStart.AlreadyConverting(prior.id)
                    val book = prior.documentId
                    if (prior.stage == Stage.DONE && book != null && documents.findById(book) != null) {
                        return@withContext ConversionStart.AlreadyInLibrary(book)
                    }
                    forget(prior)
                }
                val at = now()
                dao.insert(
                    ConversionJobEntity(
                        id = id, owner = owner, idempotencyKey = newId(), fileName = fileName,
                        sha256 = copied.sha256, bytes = copied.bytes, sourcePath = pdf.absolutePath,
                        jobId = null, stage = Stage.UPLOAD, problem = null, jobJson = null, retryAt = null,
                        failures = 0, documentId = null, createdAt = at, updatedAt = at, allowMetered = allowMetered,
                    ),
                )
                saved = true
            } finally {
                // Cancelled half way, refused or a duplicate: no copy stays behind.
                if (!saved) pdf.delete()
            }
            Log.i(TAG, "conversion=${id.take(8)} started")
            wake()
            ConversionStart.Started(id)
        }
    }

    /** Stops a conversion that is uploading or on the server; an upload under way is cut off. */
    suspend fun cancel(id: String) {
        if (dao.cancel(id, now()) == 0) return
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
            else -> row.copy(stage = Stage.UPLOAD, idempotencyKey = newId(), jobId = null, jobJson = null, createdAt = now())
        }
        val saved = dao.replaceIf(
            // Asking again on a metered network is agreeing to use it.
            again.copy(problem = null, retryAt = null, failures = 0, allowMetered = row.allowMetered || metered(), updatedAt = now()),
            Stage.FAILED,
        )
        if (saved) wake()
    }

    /** Forgets a finished conversion and the app's copy of its PDF. */
    suspend fun remove(id: String) {
        val row = dao.find(id) ?: return
        if (row.stage !in Stage.SETTLED) return
        forget(row)
        releaseOnServer(row)
    }

    /**
     * After signing out: that account's conversions and their PDFs go. The server is not told,
     * since the account's credential is gone; its jobs expire there on their own.
     */
    suspend fun forgetOwner(owner: String) {
        dao.ownedBy(owner).forEach { transfers[it.id]?.cancel() }
        mutex.withLock { dao.ownedBy(owner).forEach { forget(it) } }
    }

    /** The account's server moved to another address: its conversions follow. */
    suspend fun moveOwner(from: String, to: String) = dao.moveOwner(from, to)

    /**
     * Wakes the worker when a conversion is under way, after an app start say, or one waits for
     * the app to be open.
     */
    suspend fun resume() {
        // Not behind a step moving a PDF: tidying up can wait for the next one.
        if (mutex.tryLock()) {
            try {
                prune()
            } finally {
                mutex.unlock()
            }
        }
        val owner = owner() ?: return
        if (dao.inStages(owner, Stage.ACTIVE).isNotEmpty()) wake()
    }

    /**
     * Moves every active conversion one stage on. Answers how long until the next step is
     * worth running, or null when nothing is left to do. [beforeTransfer] runs before an upload
     * ([upload] true) or a download, so the worker can show itself while it moves the bytes; false
     * leaves the upload waiting for the app to be open. After one transfer the step ends: the next
     * run carries on.
     */
    suspend fun step(beforeTransfer: suspend (upload: Boolean) -> Boolean = { true }): Long? = mutex.withLock {
        prune()
        val owner = owner() ?: return@withLock null
        var next: Long? = null
        var moved = false
        val gate: suspend (Boolean) -> Boolean = { upload -> beforeTransfer(upload).also { if (it) moved = true } }
        val rows = dao.inStages(owner, Stage.ACTIVE)
        for ((i, row) in rows.withIndex()) {
            // Signed out or into another account meanwhile: these rows wait for theirs.
            if (owner() != row.owner) break
            val wait = try {
                advance(row, gate)
            } catch (e: IOException) {
                Log.i(TAG, "conversion=${row.id.take(8)} stage=${row.stage} network failure")
                offline(row)
            } catch (e: SerializationException) {
                Log.w(TAG, "conversion=${row.id.take(8)} stage=${row.stage} unreadable answer")
                offline(row)
            } catch (e: ConvertHttpException) {
                Log.i(TAG, "conversion=${row.id.take(8)} stage=${row.stage} answer=${e.code}")
                e.retryAfterSeconds?.takeIf { it > 0 }?.let { later(row, it * 1000) } ?: offline(row)
            }
            if (wait != null) next = minOf(next ?: wait, wait)
            // One transfer a run: any rows left go in the next.
            if (moved) return@withLock if (i < rows.lastIndex) 0L else next
        }
        next
    }

    private suspend fun advance(row: ConversionJobEntity, gate: suspend (Boolean) -> Boolean): Long? {
        val at = now()
        if (at - row.createdAt > MAX_AGE_MS) return expired(row)
        row.retryAt?.let { if (it > at) return it - at }
        notBefore[row.id]?.let { if (it > at) return it - at }
        val wait = when (row.stage) {
            Stage.UPLOAD -> upload(row, gate)
            Stage.CONVERT -> poll(row)
            Stage.IMPORT -> fetch(row, gate)
            Stage.ACKNOWLEDGE -> acknowledge(row)
            Stage.CANCEL -> stopOnServer(row)
            else -> null
        }
        networkErrors.remove(row.id) // the server answered
        return wait
    }

    /** The server forgets a job a day after it was asked for: nothing more to wait for. */
    private suspend fun expired(row: ConversionJobEntity): Long? = when {
        row.documentId != null -> settle(row, Stage.DONE)
        row.stage == Stage.CANCEL -> settle(row, Stage.CANCELLED)
        row.stage == Stage.ACKNOWLEDGE -> fail(row, row.problem ?: FAILED)
        else -> fail(row, JOB_GONE)
    }

    private suspend fun upload(row: ConversionJobEntity, gate: suspend (Boolean) -> Boolean): Long? {
        val pdf = row.sourcePath?.let(::File)?.takeIf { it.isFile } ?: return fail(row, SOURCE_MISSING)
        if (row.failures >= MAX_UPLOAD_TRIES) return fail(row, UPLOAD_FAILED)
        if (!row.allowMetered && metered()) return wait(row, WAITING_UNMETERED, UNMETERED_WAIT_MS)
        // Background work is stopped after ten minutes, and a stopped upload starts again from
        // its first byte: a PDF only goes while the worker may show itself.
        if (!gate(true)) return park(row, NEEDS_APP)
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
                503 -> if (e.detail == CONVERTER_UNAVAILABLE) converterDown(row, e) else unexpected(row, e)
                else -> unexpected(row, e)
            }
        }
        unavailableSince.remove(row.id)
        val known = row.copy(jobId = job.id, jobJson = encode(job), problem = null)
        if (job.state != "created") return follow(known, job)
        val attempt = known.copy(failures = known.failures + 1)
        if (!save(attempt)) {
            // The reader cancelled meanwhile: the cancel still needs the job, to free its slot.
            dao.setJob(row.id, row.idempotencyKey, job.id, encode(job))
            return 0
        }
        if (owner() != row.owner) return 0
        val queued = try {
            transfer(row.id) { client.upload(job.id, pdf) } ?: return 0
        } catch (e: ConvertHttpException) {
            return when {
                // An earlier upload still holds the job; refused at once, so not an attempt.
                e.code == 409 && e.detail == UPLOAD_IN_PROGRESS -> wait(known, UPLOAD_IN_PROGRESS, UPLOAD_IN_PROGRESS_WAIT_MS)
                // not_accepting_source: an earlier upload arrived and only its answer was lost.
                e.code == 409 -> follow(attempt, client.job(job.id))
                e.code == 408 || e.code >= 500 || (e.code == 422 && e.detail in RESEND) ->
                    broke(attempt, UPLOAD_FAILED, MAX_UPLOAD_TRIES, UPLOAD_RETRY_MS)
                e.code == 413 -> fail(attempt, SOURCE_TOO_LARGE)
                e.code == 415 -> fail(attempt, NOT_A_PDF)
                e.code == 422 -> fail(attempt, e.detail ?: INVALID_REQUEST)
                else -> unexpected(attempt, e)
            }
        } catch (e: IOException) {
            return broke(attempt, UPLOAD_FAILED, MAX_UPLOAD_TRIES, UPLOAD_RETRY_MS)
        }
        Log.i(TAG, "conversion=${row.id.take(8)} uploaded")
        return follow(attempt, queued)
    }

    /** `converter_unavailable` comes with a Retry-After: worth a few minutes before giving up. */
    private suspend fun converterDown(row: ConversionJobEntity, e: ConvertHttpException): Long? {
        val since = unavailableSince.getOrPut(row.id) { now() }
        if (now() - since < CONVERTER_WAIT_MS) return wait(row, CONVERTER_UNAVAILABLE, e.retryAfterMs(TOO_MANY_WAIT_MS))
        unavailableSince.remove(row.id)
        return fail(row, CONVERTER_UNAVAILABLE)
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

    private suspend fun fetch(row: ConversionJobEntity, gate: suspend (Boolean) -> Boolean): Long? {
        val jobId = row.jobId ?: return move(row, Stage.UPLOAD, 0)
        val expected = row.job()?.result ?: return poll(row)
        if (row.failures >= MAX_DOWNLOAD_TRIES) return fail(row, DOWNLOAD_FAILED)
        // An EPUB is small enough to fetch without the notification, when the worker can't show one.
        gate(false)
        val attempt = row.copy(failures = row.failures + 1)
        if (!save(attempt)) return 0
        if (owner() != row.owner) return 0
        downloadDir.mkdirs()
        val epub = File(downloadDir, "${row.id}.epub")
        try {
            val got = try {
                client.download(jobId, epub, expected.bytes)
            } catch (e: ConvertHttpException) {
                return when {
                    e.code == 410 -> fail(attempt, RESULT_GONE)
                    e.code == 409 -> poll(attempt)
                    e.code >= 500 -> broke(attempt, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
                    else -> unexpected(attempt, e)
                }
            } catch (e: IOException) {
                return broke(attempt, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
            }
            if (got.bytes != expected.bytes || got.sha256 != expected.sha256) {
                return broke(attempt, DOWNLOAD_FAILED, MAX_DOWNLOAD_TRIES, NETWORK_RETRY_MS)
            }
            // The importer treats the EPUB like any sideloaded file and spots a book already there.
            return when (val outcome = importEpub(epub, row.fileName.substringBeforeLast('.'))) {
                is ImportOutcome.Saved -> move(attempt.copy(documentId = outcome.documentId, failures = 0), Stage.ACKNOWLEDGE, 0)
                ImportOutcome.Invalid -> fail(attempt, INVALID_EPUB)
                ImportOutcome.Failed -> fail(attempt, IMPORT_FAILED)
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
                    // The book is saved: whatever else the server says, the conversion is done.
                    row.documentId != null && !e.transient() -> Unit
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
                e.transient() -> return wait(row, null, e.retryAfterMs(NETWORK_RETRY_MS))
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

    /** Moves [row] to wherever the server's [job] says it is. Past the upload, its tries start over. */
    private suspend fun follow(row: ConversionJobEntity, job: ConvertJob): Long? {
        val r = row.copy(
            jobId = job.id, jobJson = encode(job), retryAt = null,
            failures = if (job.state == "created") row.failures else 0,
        )
        return when (job.state) {
            "created" -> move(r.copy(problem = null), Stage.UPLOAD, 0)
            "queued", "running" -> {
                val ms = pollDelay(row.id)
                notBefore[row.id] = now() + ms
                move(r.copy(problem = null), Stage.CONVERT, ms)
            }
            "ready", "review" ->
                move(r.copy(problem = null), if (r.documentId != null) Stage.ACKNOWLEDGE else Stage.IMPORT, 0)
            "failed" -> move(r.copy(problem = job.failure?.code ?: FAILED), Stage.ACKNOWLEDGE, 0)
            "cancelled" -> settle(r, Stage.CANCELLED)
            "acknowledged", "expired" ->
                if (r.documentId != null) settle(r, Stage.DONE) else fail(r, row.problem ?: RESULT_GONE)
            // A state this app does not know yet: keep asking.
            else -> {
                val ms = pollDelay(row.id)
                notBefore[row.id] = now() + ms
                move(r, Stage.CONVERT, ms)
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

    /**
     * A transfer broke: tried again a few times, never in an endless loop, then the reader
     * decides. [row] has the attempt counted already.
     */
    private suspend fun broke(row: ConversionJobEntity, problem: String, tries: Int, retryMs: Long): Long? {
        Log.i(TAG, "conversion=${row.id.take(8)} $problem attempt=${row.failures}")
        return if (row.failures >= tries) fail(row, problem) else wait(row, null, retryMs)
    }

    /** Every 5 s for the first minutes, then less often the longer a job takes, up to every 5 min. */
    private fun pollDelay(id: String): Long {
        val age = now() - pollingSince.getOrPut(id) { now() }
        return if (age < FAST_POLL_FOR_MS) POLL_MS else (age / 10).coerceIn(POLL_MS, MAX_POLL_MS)
    }

    /** The server out of reach: asked again later each time, up to every 15 min. */
    private fun offline(row: ConversionJobEntity): Long {
        val failures = networkErrors.merge(row.id, 1, Int::plus) ?: 1
        return later(row, minOf(NETWORK_RETRY_MS shl minOf(failures - 1, 5), MAX_RETRY_MS))
    }

    private fun later(row: ConversionJobEntity, ms: Long): Long {
        notBefore[row.id] = now() + ms
        return ms
    }

    /** Waits with no time set, for something only the reader brings: the app open, say. */
    private suspend fun park(row: ConversionJobEntity, problem: String): Long? {
        save(row.copy(problem = problem, retryAt = null))
        return null
    }

    private suspend fun wait(row: ConversionJobEntity, problem: String?, ms: Long): Long {
        save(row.copy(problem = problem, retryAt = now() + ms))
        return ms
    }

    private suspend fun move(row: ConversionJobEntity, stage: String, wait: Long): Long =
        if (save(row, stage)) wait else 0

    private suspend fun fail(row: ConversionJobEntity, problem: String): Long? {
        if (row.stage == Stage.UPLOAD) releaseOnServer(row)
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
        clearMemory(row.id)
        return null
    }

    /**
     * Best effort: a job still waiting for its PDF holds one of the account's few active jobs
     * until it expires an hour on, so one the app gives up on is cancelled.
     */
    private suspend fun releaseOnServer(row: ConversionJobEntity) {
        val jobId = row.jobId ?: return
        if (row.job()?.state != "created") return
        try {
            client.cancel(jobId)
        } catch (e: IOException) {
            Log.i(TAG, "conversion=${row.id.take(8)} job not released")
        } catch (e: ConvertHttpException) {
            Log.i(TAG, "conversion=${row.id.take(8)} job not released: ${e.code}")
        } catch (e: SerializationException) {
            Log.i(TAG, "conversion=${row.id.take(8)} job release unreadable")
        }
    }

    private fun clearMemory(id: String) {
        notBefore.remove(id)
        pollingSince.remove(id)
        networkErrors.remove(id)
        unavailableSince.remove(id)
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

    /** Runs with [mutex] held, so no transfer is under way. */
    private suspend fun prune() {
        val at = now()
        for (row in dao.settledBefore(Stage.SETTLED, at - KEEP_SETTLED_MS)) forget(row)
        // Another account's, or left from before a sign-out: a few days in case it comes back.
        for (row in dao.othersBefore(owner(), at - KEEP_OTHERS_MS)) forget(row)
        // Copies no row knows, from a start cut off before it saved its row, say. File times are
        // the file system's clock.
        val known = dao.sourcePaths().toSet()
        val stale = System.currentTimeMillis() - STRAY_FILE_MS
        sourceDir.listFiles()?.forEach { if (it.absolutePath !in known && it.lastModified() < stale) it.delete() }
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun forget(row: ConversionJobEntity) {
        row.sourcePath?.let { File(it).delete() }
        dao.delete(row.id)
        clearMemory(row.id)
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

    private fun roomFor(bytes: Long): Boolean = sourceDir.usableSpace >= bytes + SPACE_MARGIN

    /** Stops reading one byte past [maxBytes]: a file that large is refused anyway. */
    private fun CoroutineScope.copyHashing(input: InputStream, dest: File, maxBytes: Long): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        dest.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (total <= maxBytes) {
                ensureActive()
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

    /** Worth asking again as it is: signed out for now, too busy or the server unwell. */
    private fun ConvertHttpException.transient(): Boolean = code == 401 || code == 429 || code >= 500

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
        /** Waits for the app to be open, so the upload can show itself and isn't cut off. */
        const val NEEDS_APP = "needs_app"
        /** Waits for a network without data limits: the reader didn't agree to a metered one. */
        const val WAITING_UNMETERED = "waiting_unmetered"

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
        private val RETRY_UPLOAD = setOf(CONVERTER_UNAVAILABLE)
        // A failed upload's job was cancelled on the server, to free its slot.
        private val RETRY_NEW_JOB = setOf(UPLOAD_FAILED, RESULT_GONE, JOB_GONE, IDEMPOTENCY_CONFLICT)

        /** The contract asks for at most one poll every 5 s. */
        const val POLL_MS = 5_000L
        const val FAST_POLL_FOR_MS = 2 * 60_000L
        const val MAX_POLL_MS = 5 * 60_000L
        const val NETWORK_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 15 * 60_000L
        const val UNMETERED_WAIT_MS = 5 * 60_000L
        const val CONVERTER_WAIT_MS = 5 * 60_000L
        const val UPLOAD_RETRY_MS = 10_000L
        const val UPLOAD_IN_PROGRESS_WAIT_MS = 30_000L
        const val TOO_MANY_WAIT_MS = 60_000L
        const val SIGNED_OUT_WAIT_MS = 5 * 60_000L
        /** One automatic retry of an upload; after that the reader decides, metered or not. */
        const val MAX_UPLOAD_TRIES = 2
        const val MAX_DOWNLOAD_TRIES = 3
        const val KEEP_SETTLED_MS = 7 * 24 * 60 * 60_000L
        const val KEEP_OTHERS_MS = 3 * 24 * 60 * 60_000L

        /** Jobs expire on the server a day after they were asked for. */
        const val MAX_AGE_MS = 25 * 60 * 60_000L
        private const val STRAY_FILE_MS = 60 * 60_000L

        /** Left free beyond the PDF itself, for the EPUB and the rest of the phone. */
        private const val SPACE_MARGIN = 32L * 1024 * 1024
    }
}
