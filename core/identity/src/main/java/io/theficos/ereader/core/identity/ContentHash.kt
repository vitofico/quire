package io.theficos.ereader.core.identity

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

fun contentHash(file: File): String {
    require(file.isFile) { "Not a regular file: $file" }
    val size = file.length()
    val step = maxOf(1L, size / 1024L)
    val md = MessageDigest.getInstance("MD5")
    val chunk = ByteArray(64)
    RandomAccessFile(file, "r").use { raf ->
        for (i in 0 until 1024) {
            val offset = i * step
            if (offset >= size) break
            raf.seek(offset)
            val n = raf.read(chunk, 0, 64)
            if (n > 0) md.update(chunk, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
