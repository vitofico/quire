package io.theficos.ereader.data.opds

data class OpdsFeed(
    val title: String,
    val navigation: List<OpdsNavigationLink>,
    val publications: List<OpdsPublication>,
    val searchLink: OpdsSearchLink? = null,
)

data class OpdsNavigationLink(
    val title: String,
    val href: String,
)

data class OpdsPublication(
    val title: String,
    val author: String?,
    val epubDownloadHref: String,
    val coverUrl: String?,
    /** OPDS `rel=alternate type=text/html` href — the book's web detail page on the OPDS server (calibre-web's `/book/{id}`). Null if the feed didn't expose one. */
    val webUrl: String? = null,
    /**
     * Trimmed `<dc:identifier>` text from the Atom entry (e.g. `urn:uuid:…`).
     * Surfaced when the OPDS feed includes one; calibre-web's stock template
     * does NOT, so this is null on most real feeds. Used as the `opds_dc_id`
     * alias hint by the catalog-preview AI lookup (PR7).
     */
    val opdsDcId: String? = null,
    /**
     * Numeric book id parsed from a calibre-web-style acquisition href
     * (`…/opds/download/<id>/<format>`). Used as the `calibre_book_id`
     * alias hint by the catalog-preview AI lookup (PR7). Null for non-
     * calibre-web feeds.
     */
    val calibreBookId: String? = null,
)

data class OpdsSearchLink(
    /** Raw href from the feed; may be relative and may contain `{searchTerms}`. */
    val href: String,
    /** Absolute URL to resolve the substituted template against (the feed URL, or the description URL for description links). */
    val baseUrl: String,
    /** true when [href] points to an OpenSearch description document; false when it is itself a {searchTerms} template. */
    val isDescription: Boolean,
)
