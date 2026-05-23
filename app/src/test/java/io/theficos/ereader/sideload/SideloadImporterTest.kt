package io.theficos.ereader.sideload

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.CURRENT_IDENTITY_HASH_VERSION
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
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
        // Sideload doesn't extract covers in v1 — fallback rendering is fine.
        assertThat(row.coverPath).isNull()
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

    @Test fun `sweepStaleParts removes leaked epub_part files`() = runTest {
        val leaked = File(booksDir, "leaked.epub.part").apply { writeText("partial") }
        val realBook = File(booksDir, "ok.epub").apply { writeText("not touched") }

        importer.sweepStaleParts()

        assertThat(leaked.exists()).isFalse()
        assertThat(realBook.exists()).isTrue()
    }

    /** Builds a minimal but structurally valid EPUB zip on disk. */
    private fun makeValidEpub(name: String, id: String, title: String): File {
        val epub = tmp.newFile("$name.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bid">$id</dc:identifier>
                    <dc:title>$title</dc:title>
                    <dc:creator>Author</dc:creator>
                  </metadata>
                </package>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return epub
    }
}
