package io.theficos.ereader.data.opds

data class OpdsFeed(
    val title: String,
    val navigation: List<OpdsNavigationLink>,
    val publications: List<OpdsPublication>,
)

data class OpdsNavigationLink(
    val title: String,
    val href: String,
)

data class OpdsPublication(
    val title: String,
    val author: String?,
    val epubDownloadHref: String,
    val coverHref: String?,
)
