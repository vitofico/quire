package io.theficos.ereader.core.metadata

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.SAXException

private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

/**
 * EPUB structural validation gate. Required because MIME-typed senders
 * (and bare ACTION_VIEW filters) don't actually prove the bytes are an
 * EPUB — a sender can claim `application/epub+zip` for any zip, or even
 * for non-zip content. Without this gate, identity extraction would still
 * happily hash arbitrary bytes and we'd write garbage rows.
 *
 * Checks: opens as ZIP, finds `META-INF/container.xml`, parses out the
 * rootfile `full-path`, and confirms that OPF entry exists. XML parsing
 * uses the same XXE-hardened factory pattern as
 * [io.theficos.ereader.core.identity.extractMetadataId] — sideloaded
 * EPUBs are user-trusted but not verified, and an attacker-controlled
 * `container.xml` could otherwise hang the parser or trigger network IO.
 */
fun validateEpubContainer(file: File): Boolean = try {
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
} catch (_: Exception) { false }

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
