package io.theficos.ereader.sideload

import android.content.ContentResolver
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.core.model.CURRENT_IDENTITY_HASH_VERSION
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.local.DocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

/**
 * Phase-0 / A-3: ingest path for sideloaded EPUBs (share-sheet + `+ Import`).
 *
 * The pipeline is intentionally minimal per spec ("no curation /
 * library-management flourishes in v1"):
 *   1. Stream-copy the [Uri] into [booksDir] under a UUID filename so
 *      everything downstream operates on a real seekable [File] (required by
 *      [extractIdentity] / [contentHash], which use [java.io.RandomAccessFile]).
 *   2. Validate the bytes are an EPUB container (META-INF/container.xml +
 *      rootfile OPF present) before touching Room. Without this gate, any zip
 *      or even arbitrary file would get hashed and inserted.
 *   3. Compute the identity hash via [extractIdentity] — DO NOT inline copy
 *      that logic; spec/F-2 contract demand the shared module is the only
 *      source of truth.
 *   4. Dedup against existing rows by identity (uses the same `findByIdentity`
 *      the catalog path uses) and as a last line of defense catch the
 *      `SQLiteConstraintException` from the unique index on `contentHash`
 *      (the schema-level uniqueness is the actual correctness boundary).
 *   5. Stamp [CURRENT_IDENTITY_HASH_VERSION] on insert — F-2 wiring.
 *
 * Failures clean up the staged `.part` file and any renamed `.epub` file we
 * created but failed to commit, so process-death recovery only has to sweep
 * leaked `.part` artifacts ([sweepStaleParts]).
 */
class SideloadImporter(
    private val documentRepository: DocumentRepository,
    private val libraryUploader: LibraryUploader,
    private val booksDir: File,
    private val contentResolver: ContentResolver,
    private val uploaderScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Best-effort hook fired ONLY after a successful (non-duplicate) import,
     * to mirror the catalog download path's
     * `syncStateDao.clearAll() + SyncEnqueuer.enqueue(...)` parity step.
     * A returning user may have server-side progress for the newly
     * sideloaded book's identity that an earlier pull silently dropped (no
     * local doc to attach to); clearing the cursor + re-enqueuing lets the
     * next pull re-fetch from epoch 0 and attach. AppContainer wires this
     * up; tests can leave it as the default no-op.
     */
    private val onSuccessfulImport: suspend () -> Unit = {},
) {
    /**
     * Serializes concurrent imports against each other. The schema-level
     * unique index on `contentHash` is the correctness boundary, but this
     * mutex avoids two parallel imports both copying + hashing the same
     * 200MB EPUB just to have the second one rejected. Strictly an
     * optimization / UX nicety.
     */
    private val importMutex = Mutex()

    init { booksDir.mkdirs() }

    suspend fun import(uri: Uri, displayNameHint: String? = null): SideloadResult =
        importMutex.withLock { importLocked(uri, displayNameHint) }

    private suspend fun importLocked(uri: Uri, displayNameHint: String?): SideloadResult =
        withContext(Dispatchers.IO) {
            val baseName = UUID.randomUUID().toString()
            val partFile = File(booksDir, "$baseName.epub.part")
            val finalFile = File(booksDir, "$baseName.epub")

            val copyOk = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    partFile.outputStream().use { out -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrElse { t ->
                Log.w(TAG, "openInputStream failed for $uri", t)
                false
            }
            if (!copyOk) {
                partFile.delete()
                return@withContext SideloadResult.Failed(SideloadFailure.UriUnreadable)
            }

            if (!validateEpubContainer(partFile)) {
                partFile.delete()
                return@withContext SideloadResult.Failed(SideloadFailure.InvalidEpub)
            }

            if (!partFile.renameTo(finalFile)) {
                partFile.delete()
                return@withContext SideloadResult.Failed(SideloadFailure.IoError)
            }

            val identity = try {
                extractIdentity(finalFile)
            } catch (t: Throwable) {
                Log.w(TAG, "extractIdentity threw for $finalFile", t)
                finalFile.delete()
                return@withContext SideloadResult.Failed(SideloadFailure.InvalidEpub)
            }
            if (identity.contentHash.isNullOrBlank()) {
                finalFile.delete()
                return@withContext SideloadResult.Failed(SideloadFailure.InvalidEpub)
            }

            documentRepository.findByIdentity(identity)?.let { existing ->
                finalFile.delete()
                return@withContext SideloadResult.AlreadyImported(existing.id, existing.title)
            }

            val bundle = readOpfBundle(finalFile, fallbackTitle = displayNameHint?.takeIf { it.isNotBlank() }
                ?: finalFile.nameWithoutExtension)

            val insertedId = try {
                documentRepository.insert(
                    identity = DocumentIdentity(
                        metadataId = identity.metadataId,
                        contentHash = identity.contentHash,
                        identityHashVersion = CURRENT_IDENTITY_HASH_VERSION,
                    ),
                    title = bundle.title,
                    author = bundle.author,
                    // Sideloaded books have no source URL. Use a synthetic
                    // `sideload://` URI keyed by content hash so the NOT NULL
                    // `downloadUrl` column stays satisfied and the value is
                    // distinguishable from real OPDS hrefs. Stable across
                    // re-imports of the same bytes — useful if we ever want
                    // a "this is a sideload" filter without a schema column.
                    downloadUrl = "sideload://v1/${identity.contentHash}",
                    localPath = finalFile.absolutePath,
                    coverPath = null,
                    downloadedAt = nowMillis(),
                    seriesName = bundle.seriesName,
                    seriesIndex = bundle.seriesPosition?.toDouble(),
                    identityHashVersion = CURRENT_IDENTITY_HASH_VERSION,
                )
            } catch (e: SQLiteConstraintException) {
                // Race: a parallel import won the insert. Treat as duplicate.
                Log.d(TAG, "constraint hit on sideload insert (race against parallel import)", e)
                finalFile.delete()
                val existing = documentRepository.findByIdentity(identity)
                return@withContext if (existing != null) {
                    SideloadResult.AlreadyImported(existing.id, existing.title)
                } else {
                    SideloadResult.Failed(SideloadFailure.IoError)
                }
            }

            // Fire-and-forget library upload so the server learns about the
            // new identity. Mirrors the catalog path; failures are logged
            // inside the uploader and the next app-start backfill retries.
            uploaderScope.launch {
                runCatching { libraryUploader.enqueueOne(insertedId).join() }
                    .onFailure { Log.w(TAG, "enqueueOne failed for sideload id=$insertedId", it) }
            }

            runCatching { onSuccessfulImport() }
                .onFailure { Log.w(TAG, "onSuccessfulImport hook failed", it) }

            SideloadResult.Imported(insertedId, bundle.title)
        }

    /**
     * Removes any leaked `*.epub.part` files in [booksDir]. Called at app
     * startup so process death mid-import doesn't leak storage. Best-effort;
     * failures are silent (the next sweep will retry).
     */
    suspend fun sweepStaleParts() = withContext(Dispatchers.IO) {
        runCatching {
            booksDir.listFiles { f -> f.isFile && f.name.endsWith(".epub.part") }
                ?.forEach { it.delete() }
        }
    }

    /**
     * EPUB structural validation gate. Required because MIME-typed senders
     * (and bare ACTION_VIEW filters) don't actually prove the bytes are an
     * EPUB — a sender can claim `application/epub+zip` for any zip, or even
     * for non-zip content. Without this gate, [extractIdentity] would still
     * happily hash arbitrary bytes and we'd write garbage rows.
     *
     * Checks: opens as ZIP, finds `META-INF/container.xml`, parses out the
     * rootfile `full-path`, and confirms that OPF entry exists. XML parsing
     * uses the same XXE-hardened factory pattern as
     * [io.theficos.ereader.core.identity.extractMetadataId] — sideloaded
     * EPUBs are user-trusted but not verified, and an attacker-controlled
     * `container.xml` could otherwise hang the parser or trigger network IO.
     */
    private fun validateEpubContainer(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml") ?: return@use false
            val opfPath = zip.getInputStream(containerEntry).use { input ->
                val doc = newSafeDocumentBuilder().parse(input)
                val rootfile = doc.getElementsByTagNameNS(CONTAINER_NS, "rootfile").item(0) as? Element
                    ?: return@use null
                rootfile.getAttribute("full-path").ifEmpty { return@use null }
            } ?: return@use false
            zip.getEntry(opfPath) != null
        }
    } catch (_: ZipException) { false
    } catch (_: IOException) { false
    } catch (_: SAXException) { false
    } catch (t: Throwable) {
        Log.w(TAG, "validateEpubContainer threw", t)
        false
    }

    private fun newSafeDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        return factory.newDocumentBuilder()
    }

    companion object {
        private const val TAG = "SideloadImporter"
        private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

        /**
         * Best-effort lookup of a human-readable display name for a content URI,
         * used as the fallback EPUB title when OPF metadata is missing.
         */
        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryDisplayName failed for $uri", t)
            null
        }
    }
}

sealed interface SideloadResult {
    data class Imported(val documentId: Long, val title: String) : SideloadResult
    data class AlreadyImported(val documentId: Long, val title: String) : SideloadResult
    data class Failed(val reason: SideloadFailure) : SideloadResult
}

enum class SideloadFailure {
    /** The content URI couldn't be opened or read at all. */
    UriUnreadable,
    /** The file isn't a structurally valid EPUB (no container, no OPF, etc.). */
    InvalidEpub,
    /** Local I/O failure: rename failed, disk full, etc. */
    IoError,
}
