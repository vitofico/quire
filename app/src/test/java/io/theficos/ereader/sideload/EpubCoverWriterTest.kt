package io.theficos.ereader.sideload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubCoverWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(cover: TestCover?) =
        writeTestEpub(tmp.newFile(), id = "urn:uuid:1", title = "T", cover = cover)

    private fun dest() = File(tmp.newFolder(), "book.cover")

    private fun boundsOf(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return opts.outWidth to opts.outHeight
    }

    @Test fun `a small cover is stored byte for byte`() {
        val bytes = testImage(300, 450, Bitmap.CompressFormat.PNG)
        val out = EpubCoverWriter.write(epub(TestCover("image/png", bytes)), dest())

        assertThat(out).isNotNull()
        assertThat(out!!.readBytes()).isEqualTo(bytes)
    }

    @Test fun `a cover somewhat over the edge but small on disk is kept byte for byte`() {
        val bytes = testImage(1200, 1800)
        val out = EpubCoverWriter.write(epub(TestCover("image/jpeg", bytes)), dest())

        assertThat(out!!.readBytes()).isEqualTo(bytes)
    }

    @Test fun `a cover with huge dimensions is shrunk to fit the maximum edge`() {
        val bytes = testImage(2000, 3000)
        val out = EpubCoverWriter.write(epub(TestCover("image/jpeg", bytes)), dest())

        assertThat(out).isNotNull()
        assertThat(boundsOf(out!!)).isEqualTo(600 to EpubCoverWriter.MAX_EDGE_PX)
        assertThat(out.parentFile!!.list()!!.toList()).containsExactly("book.cover")
    }

    @Test fun `a cover that is big on disk is shrunk and re-encoded smaller`() {
        val bytes = testImage(1000, 1500, noisy = true)
        assertThat(bytes.size).isGreaterThan(512 * 1024)

        val out = EpubCoverWriter.write(epub(TestCover("image/jpeg", bytes)), dest())

        assertThat(boundsOf(out!!)).isEqualTo(600 to EpubCoverWriter.MAX_EDGE_PX)
        assertThat(out.length()).isLessThan(bytes.size.toLong())
    }

    @Test fun `an epub without a cover writes nothing`() {
        val dest = dest()

        assertThat(EpubCoverWriter.write(epub(cover = null), dest)).isNull()
        assertThat(dest.parentFile!!.list()!!.toList()).isEmpty()
    }

    @Test fun `a cover that does not decode writes nothing`() {
        val dest = dest()

        assertThat(EpubCoverWriter.write(epub(TestCover("image/jpeg", "not a jpeg".toByteArray())), dest)).isNull()
        assertThat(dest.parentFile!!.list()!!.toList()).isEmpty()
    }
}
