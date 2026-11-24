package io.theficos.ereader.sideload

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class EpubExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun name(title: String?, author: String?, fallback: String = "3f2a9c.epub") =
        EpubExport.suggestedFileName(title, author, fallback)

    // ---------- suggestedFileName ----------

    @Test fun `title and author make the name`() {
        assertThat(name("Dune", "Frank Herbert")).isEqualTo("Dune - Frank Herbert.epub")
    }

    @Test fun `characters filesystems reject become single spaces`() {
        assertThat(name("Dune: Messiah / \"Part\" <2>?*|\\", "A\\B"))
            .isEqualTo("Dune Messiah Part 2 - A B.epub")
        assertThat(name("Line\nBreak\tand\u0000nul", null)).isEqualTo("Line Break and nul.epub")
    }

    @Test fun `leading and trailing dots and spaces are dropped`() {
        assertThat(name("  ...Hidden.  ", " Someone. ")).isEqualTo("Hidden - Someone.epub")
    }

    @Test fun `a missing or blank author leaves the title alone`() {
        assertThat(name("Dune", null)).isEqualTo("Dune.epub")
        assertThat(name("Dune", "   ")).isEqualTo("Dune.epub")
        assertThat(name("Dune", "???")).isEqualTo("Dune.epub")
    }

    @Test fun `without a usable title the original file name is used with an epub extension`() {
        assertThat(name(null, "Frank Herbert")).isEqualTo("3f2a9c.epub")
        assertThat(name("  ", null)).isEqualTo("3f2a9c.epub")
        assertThat(name("???", null)).isEqualTo("3f2a9c.epub")
        assertThat(name(null, null, fallback = "My Scan.pdf")).isEqualTo("My Scan.epub")
        assertThat(name(null, null, fallback = "notes")).isEqualTo("notes.epub")
        assertThat(name(null, null, fallback = "")).isEqualTo("book.epub")
    }

    @Test fun `non-Latin titles and authors are kept as written`() {
        assertThat(name("Война и мир", "Лев Толстой")).isEqualTo("Война и мир - Лев Толстой.epub")
        assertThat(name("ノルウェイの森", "村上春樹")).isEqualTo("ノルウェイの森 - 村上春樹.epub")
        assertThat(name("مئة عام من العزلة", null)).isEqualTo("مئة عام من العزلة.epub")
    }

    @Test fun `long names are cut to the byte budget`() {
        assertThat(name("a".repeat(500), "b")).isEqualTo("a".repeat(EpubExport.MAX_STEM_BYTES) + ".epub")
        // The cut never leaves a dangling separator.
        val title = "t".repeat(EpubExport.MAX_STEM_BYTES - 2)
        assertThat(name(title, "Author")).isEqualTo("$title.epub")
    }

    @Test fun `long non-Latin names are cut on whole characters`() {
        // Three UTF-8 bytes each: 66 fit in 200 bytes.
        assertThat(name("書".repeat(100), null)).isEqualTo("書".repeat(66) + ".epub")
        // Four bytes and a surrogate pair each: exactly 50 fit, none split in half.
        assertThat(name("📚".repeat(80), null)).isEqualTo("📚".repeat(50) + ".epub")
    }

    // ---------- copyTo ----------

    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val target: Uri = Uri.parse("content://io.theficos.test/document/1")

    private fun sourceFile(bytes: ByteArray) = tmp.newFile("book.epub").apply { writeBytes(bytes) }

    @Test fun `copies the exact bytes and leaves the library copy untouched`() = runTest {
        val bytes = Random(7).nextBytes(300 * 1024)
        val source = sourceFile(bytes)
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(target, out)

        assertThat(EpubExport.copyTo(resolver, source, target)).isTrue()

        assertThat(out.toByteArray()).isEqualTo(bytes)
        assertThat(source.readBytes()).isEqualTo(bytes)
    }

    @Test fun `a failing destination reports failure and leaves the library copy untouched`() = runTest {
        val bytes = Random(8).nextBytes(64 * 1024)
        val source = sourceFile(bytes)
        shadowOf(resolver).registerOutputStream(target, object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        })

        assertThat(EpubExport.copyTo(resolver, source, target)).isFalse()

        assertThat(source.readBytes()).isEqualTo(bytes)
    }

    @Test fun `a missing library file reports failure`() = runTest {
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(target, out)

        assertThat(EpubExport.copyTo(resolver, File(tmp.root, "gone.epub"), target)).isFalse()

        assertThat(out.size()).isEqualTo(0)
    }
}
