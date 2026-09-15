package io.theficos.ereader.data.opds

import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parse [bytes] into a namespace-aware DOM, hardened as far as the platform
 * allows, returning null instead of throwing on any failure.
 *
 * Hardening is applied BEST EFFORT, one feature at a time, each in its own
 * catch. That detail is the whole point of this function.
 *
 * Android's XML stack rejects feature names outside the `javax.xml.XMLConstants`
 * namespace by throwing `ParserConfigurationException`, while the Xerces
 * implementation that Robolectric supplies on the JVM accepts the Apache names.
 * Code that set `disallow-doctype-decl` unconditionally inside a single
 * `runCatching` therefore parsed nothing at all on a real device, and every unit
 * test still passed. That shipped as a total failure of the OPDS probe: a valid
 * Atom feed was reported as "not an Atom feed" on device only. See issue #101.
 *
 * So: never let an unsupported hardening feature abort the parse. Ask for each
 * one, accept a refusal, and keep going. `FEATURE_SECURE_PROCESSING` is the
 * portable one and is honored on both platforms.
 */
fun parseXmlOrNull(bytes: ByteArray): Document? = runCatching {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    // Portable: supported on Android and on the JVM. Bounds entity expansion.
    trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
    // Apache and SAX names: honored on the JVM, refused on Android. Either way
    // the parse must still run.
    trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
    trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
    trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
    runCatching { factory.isExpandEntityReferences = false }
    factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}.getOrNull()

/** True when [bytes] is an XML document whose root element is an Atom `feed`. */
fun looksLikeAtomFeed(bytes: ByteArray): Boolean {
    val root = parseXmlOrNull(bytes)?.documentElement ?: return false
    return root.localName == "feed" && root.namespaceURI == ATOM_NAMESPACE
}

/** The Atom 1.0 namespace every OPDS 1.x feed declares on its root element. */
const val ATOM_NAMESPACE: String = "http://www.w3.org/2005/Atom"

private fun trySetFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
    runCatching { factory.setFeature(name, value) }
}
