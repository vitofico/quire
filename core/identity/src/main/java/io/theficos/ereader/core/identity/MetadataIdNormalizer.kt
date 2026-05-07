package io.theficos.ereader.core.identity

private val SCHEMES = listOf("isbn", "uuid", "calibre", "mobi-asin", "asin", "doi", "url")
// Order matters: longer/compound schemes (e.g. "mobi-asin") must precede their prefixes ("asin")
// since the code breaks on first match. Keep this list ordered most-specific to least-specific.
private val SCHEME_PREFIX = Regex("^(${SCHEMES.joinToString("|")})[:\\s]+")
private val WHITESPACE_AND_HYPHEN = Regex("[\\s-]")

fun normalizeMetadataId(raw: String?): String? {
    if (raw == null) return null
    var s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    if (s.startsWith("urn:")) s = s.removePrefix("urn:")
    s = SCHEME_PREFIX.replaceFirst(s, "")
    s = s.replace(WHITESPACE_AND_HYPHEN, "")
    return s.ifEmpty { null }
}
