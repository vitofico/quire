package io.theficos.ereader.core.metadata

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads the OPF document out of an EPUB file and returns a [MetadataBundle].
 *
 * Best-effort: any IO/zip/parse failure produces a [MetadataBundle] built from
 * [fallbackTitle] alone, matching [OpfMetadataExtractor.extract]'s tolerance.
 */
fun readOpfBundle(epub: File, fallbackTitle: String): MetadataBundle {
    val opfBytes = try {
        ZipFile(epub).use { zip -> zip.readOpf()?.second }
    } catch (_: ZipException) { null
    } catch (_: IOException) { null
    } catch (_: SecurityException) { null }
    return opfBytes
        ?.let { OpfMetadataExtractor.extract(it, fallbackTitle) }
        ?: MetadataBundle(title = fallbackTitle)
}

/**
 * The zip entry holding this EPUB's cover image, or null when the OPF declares no image cover
 * (see [OpfCoverLocator.coverImagePath]) or the declared file is missing from the zip.
 * IO failures propagate: callers decide how a cover failure is handled.
 */
fun ZipFile.findCoverImageEntry(): ZipEntry? {
    val (opfPath, opfBytes) = readOpf() ?: return null
    return OpfCoverLocator.coverImagePath(opfBytes, opfPath)?.let(::getEntry)
}

/** The OPF's zip path and bytes, located through `META-INF/container.xml`. */
private fun ZipFile.readOpf(): Pair<String, ByteArray>? {
    val container = getEntry("META-INF/container.xml") ?: return null
    val containerXml = getInputStream(container).use { it.readBytes() }.decodeToString()
    val opfPath = Regex("""full-path="([^"]+)"""")
        .find(containerXml)?.groupValues?.get(1)
        ?: return null
    val opfEntry = getEntry(opfPath) ?: return null
    return opfPath to getInputStream(opfEntry).use { it.readBytes() }
}
