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
)

data class OpdsSearchLink(
    /** Raw href from the feed; may be relative and may contain `{searchTerms}`. */
    val href: String,
    /** Absolute URL to resolve the substituted template against (the feed URL, or the description URL for description links). */
    val baseUrl: String,
    /** true when [href] points to an OpenSearch description document; false when it is itself a {searchTerms} template. */
    val isDescription: Boolean,
)
