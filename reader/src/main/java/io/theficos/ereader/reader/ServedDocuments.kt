package io.theficos.ereader.reader

import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map

/**
 * Wraps the book's container so that each HTML and XHTML document in [manifest] reaches Readium,
 * and whatever else reads the book (search, the XHTML check), in UTF-8 (see [utf8Document]).
 *
 * Only those documents are touched, and only read in full, which is how Readium reads them anyway.
 * Their properties, the archive entry length the positions are counted from included, are the
 * book's own, so locations saved before this stay where they were.
 */
internal fun Container<Resource>.servingDocuments(manifest: Manifest): Container<Resource> {
    val html = (manifest.readingOrder + manifest.resources)
        .mapNotNull { link ->
            val type = link.mediaType?.takeIf { it.isHtml } ?: return@mapNotNull null
            link.url().normalize() to type.matches(MediaType.HTML)
        }
        .toMap()
    if (html.isEmpty()) return this
    return TransformingContainer(this) { url, resource ->
        val servedAsHtml = html[url.removeQuery().removeFragment().normalize()]
        if (servedAsHtml == null) resource else resource.map { Try.success(utf8Document(it, servedAsHtml)) }
    }
}
