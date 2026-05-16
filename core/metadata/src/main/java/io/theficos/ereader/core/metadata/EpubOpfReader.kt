package io.theficos.ereader.core.metadata

import java.io.File
import java.io.IOException
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
        ZipFile(epub).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return@use null
            val containerXml = zip.getInputStream(container).use { it.readBytes() }.decodeToString()
            val opfPath = Regex("""full-path="([^"]+)"""")
                .find(containerXml)?.groupValues?.get(1)
                ?: return@use null
            val opfEntry = zip.getEntry(opfPath) ?: return@use null
            zip.getInputStream(opfEntry).use { it.readBytes() }
        }
    } catch (_: ZipException) { null
    } catch (_: IOException) { null
    } catch (_: SecurityException) { null }
    return opfBytes
        ?.let { OpfMetadataExtractor.extract(it, fallbackTitle) }
        ?: MetadataBundle(title = fallbackTitle)
}
