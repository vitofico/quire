package io.theficos.ereader.core.identity

import io.theficos.ereader.core.model.DocumentIdentity
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

private const val DC_NS = "http://purl.org/dc/elements/1.1/"
private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

fun extractMetadataId(epub: File): String? = try {
    ZipFile(epub).use { zip ->
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return@use null
        val opfPath = zip.getInputStream(containerEntry).use { input ->
            val doc = newSafeDocumentBuilder().parse(input)
            val rootfile = doc.getElementsByTagNameNS(CONTAINER_NS, "rootfile").item(0) as? Element
                ?: return@use null
            rootfile.getAttribute("full-path").ifEmpty { return@use null }
        } ?: return@use null
        val opfEntry = zip.getEntry(opfPath) ?: return@use null
        zip.getInputStream(opfEntry).use { input ->
            val doc = newSafeDocumentBuilder().parse(input)
            val ids = doc.getElementsByTagNameNS(DC_NS, "identifier")
            // Spec §5.3: take the first <dc:identifier> with a non-empty trimmed value, then normalize.
            // If that one normalizes to empty, treat the document as missing a metadata-id (do NOT
            // continue to subsequent identifiers).
            for (i in 0 until ids.length) {
                val raw = ids.item(i).textContent?.trim().orEmpty()
                if (raw.isEmpty()) continue
                return@use normalizeMetadataId(raw)
            }
            null
        }
    }
} catch (_: ZipException) { null
} catch (_: IOException) { null
} catch (_: SAXException) { null }

fun extractIdentity(epub: File): DocumentIdentity =
    DocumentIdentity(metadataId = extractMetadataId(epub), contentHash = contentHash(epub))

private fun newSafeDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // XXE hardening — sideloaded EPUBs are user-trusted but not verified. Block external
        // entities and DTDs that could exfiltrate data, hang the parser, or trigger network IO.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
    }
    return factory.newDocumentBuilder()
}
