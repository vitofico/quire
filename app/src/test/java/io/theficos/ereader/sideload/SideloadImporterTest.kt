package io.theficos.ereader.sideload

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.CURRENT_IDENTITY_HASH_VERSION
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideloadImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: EReaderDatabase
    private lateinit var documentRepository: DocumentRepository
    private lateinit var libraryUploader: LibraryUploader
    private lateinit var importer: SideloadImporter
    private lateinit var booksDir: File
    private lateinit var uploaderScope: CoroutineScope

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Open now, on the test thread. Left to a Room worker, the open can deadlock
        // with tearDown's close() when the test ends first.
        db.openHelper.writableDatabase
        documentRepository = DocumentRepository(db.documentDao())
        booksDir = tmp.newFolder("books")
        uploaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Real LibraryUploader is constructed with a no-op base URL, so its
        // enqueueOne fires a launch into uploaderScope that 404s harmlessly.
        // The importer only needs `enqueueOne` to not throw synchronously.
        libraryUploader = LibraryUploader(
            client = LibraryClient(
                baseUrlProvider = { null },
                http = OkHttpClient(),
            ),
            dao = db.documentDao(),
            scope = uploaderScope,
        )
        importer = SideloadImporter(
            documentRepository = documentRepository,
            libraryUploader = libraryUploader,
            booksDir = booksDir,
            contentResolver = ctx.contentResolver,
            uploaderScope = uploaderScope,
            nowMillis = { 1_700_000_000_000L },
        )
    }

    @After fun tearDown() {
        db.close()
        uploaderScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `imports a valid epub and persists a document row`() = runTest {
        val epub = makeValidEpub(name = "happy", id = "urn:isbn:9780000000001", title = "Happy Path")

        val result = importer.import(Uri.fromFile(epub), displayNameHint = "Happy.epub")

        assertThat(result).isInstanceOf(SideloadResult.Imported::class.java)
        val imported = result as SideloadResult.Imported
        val rows = documentRepository.observeLibrary().first()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.id).isEqualTo(imported.documentId)
        // F-2 regression: every fresh sideloaded row is stamped with the
        // current hash version, not a hard-coded `1`.
        assertThat(row.identityHashVersion).isEqualTo(CURRENT_IDENTITY_HASH_VERSION)
        // The synthetic download URL is keyed by the content hash so duplicate
        // imports of the same bytes are distinguishable from real OPDS hrefs.
        assertThat(row.downloadUrl).startsWith("sideload://v1/")
        assertThat(row.downloadUrl).endsWith(row.identity.contentHash!!)
        // This EPUB declares no cover, so the library shows the placeholder.
        assertThat(row.coverPath).isNull()
        assertThat(booksDir.listFiles()!!.map { it.extension }).containsExactly("epub")
    }

    @Test fun `stores the epub's cover next to the book, like a catalog download`() = runTest {
        val coverBytes = testImage(300, 450)
        val epub = makeValidEpub(
            name = "covered", id = "urn:isbn:9780000000005", title = "Covered",
            cover = TestCover("image/jpeg", coverBytes),
        )

        val result = importer.import(Uri.fromFile(epub), displayNameHint = null)

        assertThat(result).isInstanceOf(SideloadResult.Imported::class.java)
        val row = documentRepository.observeLibrary().first().single()
        val cover = File(row.coverPath!!)
        // `<uuid>.cover` beside `<uuid>.epub`, the catalog path's naming.
        assertThat(cover.parentFile).isEqualTo(booksDir)
        assertThat(cover.name).isEqualTo(File(row.localPath).nameWithoutExtension + ".cover")
        assertThat(cover.readBytes()).isEqualTo(coverBytes)
    }

    @Test fun `a cover that does not decode still imports the book, without a cover`() = runTest {
        val epub = makeValidEpub(
            name = "brokencover", id = "urn:isbn:9780000000006", title = "Broken cover",
            cover = TestCover("image/jpeg", "not a jpeg".toByteArray()),
        )

        val result = importer.import(Uri.fromFile(epub), displayNameHint = null)

        assertThat(result).isInstanceOf(SideloadResult.Imported::class.java)
        assertThat(documentRepository.observeLibrary().first().single().coverPath).isNull()
        assertThat(booksDir.listFiles()!!.map { it.extension }).containsExactly("epub")
    }

    @Test fun `cover backfill covers old sideloaded books once and leaves catalog books alone`() = runTest {
        val cover = TestCover("image/jpeg", testImage(300, 450))
        val oldSideload = insertRow("old", "sideload://v1/h-old", cover)
        val noCoverSideload = insertRow("plain", "sideload://v1/h-plain", cover = null)
        val catalog = insertRow("catalog", "https://opds.example/book/1.epub", cover)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("backfill-test", Context.MODE_PRIVATE)

        assertThat(importer.backfillCoversOnce(prefs)).isEqualTo(1)

        val backfilled = documentRepository.findById(oldSideload)!!.coverPath
        assertThat(backfilled).isEqualTo(File(booksDir, "old.cover").absolutePath)
        assertThat(File(backfilled!!).readBytes()).isEqualTo(cover.bytes)
        assertThat(documentRepository.findById(noCoverSideload)!!.coverPath).isNull()
        assertThat(documentRepository.findById(catalog)!!.coverPath).isNull()

        // The pass is remembered: a later sideloaded row without a cover is not revisited,
        // and books with no cover are not reopened on every start.
        val later = insertRow("later", "sideload://v1/h-later", cover)
        assertThat(importer.backfillCoversOnce(prefs)).isEqualTo(0)
        assertThat(documentRepository.findById(later)!!.coverPath).isNull()
    }

    @Test fun `duplicate import of the same epub is reported as AlreadyImported`() = runTest {
        val epub1 = makeValidEpub(name = "dup1", id = "urn:isbn:9780000000002", title = "Dup")
        // Same bytes a second time would not be allowed (same Uri once consumed).
        // Build a second copy with identical OPF so the metadataId match short-circuits findByIdentity.
        val epub2 = makeValidEpub(name = "dup2", id = "urn:isbn:9780000000002", title = "Dup")

        val first = importer.import(Uri.fromFile(epub1), displayNameHint = null)
        val second = importer.import(Uri.fromFile(epub2), displayNameHint = null)

        assertThat(first).isInstanceOf(SideloadResult.Imported::class.java)
        assertThat(second).isInstanceOf(SideloadResult.AlreadyImported::class.java)
        val rows = documentRepository.observeLibrary().first()
        assertThat(rows).hasSize(1)
    }

    @Test fun `invalid epub (no container) is rejected and leaves no file or row`() = runTest {
        val bogus = tmp.newFile("bogus.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("not an epub".toByteArray())
                zip.closeEntry()
            }
        }

        val result = importer.import(Uri.fromFile(bogus), displayNameHint = "bogus.epub")

        assertThat(result).isEqualTo(SideloadResult.Failed(SideloadFailure.InvalidEpub))
        assertThat(documentRepository.observeLibrary().first()).isEmpty()
        // No `.epub` or `.epub.part` should be left behind in booksDir.
        assertThat(booksDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test fun `non-zip bytes are rejected as InvalidEpub`() = runTest {
        val notZip = tmp.newFile("plain.epub").apply { writeText("not even a zip") }

        val result = importer.import(Uri.fromFile(notZip), displayNameHint = null)

        assertThat(result).isEqualTo(SideloadResult.Failed(SideloadFailure.InvalidEpub))
        assertThat(booksDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test fun `sweepStaleParts removes leaked epub_part and cover_part files`() = runTest {
        val leaked = File(booksDir, "leaked.epub.part").apply { writeText("partial") }
        val leakedCover = File(booksDir, "leaked.cover.part").apply { writeText("partial") }
        val realBook = File(booksDir, "ok.epub").apply { writeText("not touched") }
        val realCover = File(booksDir, "ok.cover").apply { writeText("not touched") }

        importer.sweepStaleParts()

        assertThat(leaked.exists()).isFalse()
        assertThat(leakedCover.exists()).isFalse()
        assertThat(realBook.exists()).isTrue()
        assertThat(realCover.exists()).isTrue()
    }

    /** Inserts a row whose EPUB sits in [booksDir], as an import from before covers would have. */
    private suspend fun insertRow(name: String, downloadUrl: String, cover: TestCover?): Long {
        val epub = writeTestEpub(File(booksDir, "$name.epub"), id = "urn:uuid:$name", title = name, cover = cover)
        return documentRepository.insert(
            identity = DocumentIdentity(
                metadataId = "urn:uuid:$name",
                contentHash = "hash-$name",
                identityHashVersion = CURRENT_IDENTITY_HASH_VERSION,
            ),
            title = name,
            author = null,
            downloadUrl = downloadUrl,
            localPath = epub.absolutePath,
            coverPath = null,
            downloadedAt = 0,
        )
    }

    /** Builds a minimal but structurally valid EPUB zip on disk. */
    private fun makeValidEpub(name: String, id: String, title: String, cover: TestCover? = null): File =
        writeTestEpub(tmp.newFile("$name.epub"), id = id, title = title, cover = cover)
}
