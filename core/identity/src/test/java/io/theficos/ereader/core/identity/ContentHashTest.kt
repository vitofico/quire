package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ContentHashTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `matches reference KOReader-style sampled MD5`() {
        val file = tmp.newFile("book.epub")
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        file.writeBytes(bytes)

        val expected = referenceHash(bytes)
        assertThat(contentHash(file)).isEqualTo(expected)
    }

    @Test fun `tiny file is hashed in full when step is 1`() {
        val file = tmp.newFile("tiny.epub")
        val bytes = ByteArray(64) { it.toByte() }
        file.writeBytes(bytes)

        // For a 64-byte file: step = max(1, 64/1024) = 1.
        // Loop reads up to 64 bytes from offsets 0..63 (offset == size breaks the loop).
        val expected = referenceHash(bytes)
        assertThat(contentHash(file)).isEqualTo(expected)
    }

    private fun referenceHash(bytes: ByteArray): String {
        val size = bytes.size.toLong()
        val step = maxOf(1L, size / 1024L)
        val buf = ByteArray((1024 * 64).coerceAtMost(Int.MAX_VALUE))
        var len = 0
        for (i in 0 until 1024) {
            val offset = (i * step).toInt()
            if (offset >= bytes.size) break
            val n = minOf(64, bytes.size - offset)
            System.arraycopy(bytes, offset, buf, len, n)
            len += n
        }
        val md = MessageDigest.getInstance("MD5")
        md.update(buf, 0, len)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
