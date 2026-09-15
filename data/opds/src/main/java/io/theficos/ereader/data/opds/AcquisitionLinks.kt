package io.theficos.ereader.data.opds

/**
 * What counts as a downloadable book in an OPDS feed.
 *
 * These live at the top level rather than inside [OpdsClient] because the
 * onboarding probe has to answer the same question before any client exists,
 * and a second, stricter copy of the rules there would reject a catalog that
 * the library screen goes on to render perfectly well. See issue #101.
 */

private const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
private const val OPEN_ACCESS_REL = "http://opds-spec.org/acquisition/open-access"

/**
 * True for the two acquisition rels that mean "here are the bytes".
 *
 * An allowlist rather than a `startsWith` prefix match: the OPDS spec also
 * defines `/buy`, `/borrow`, `/subscribe` and `/sample` under the same
 * namespace, and surfacing any of those as a download button would be wrong.
 * calibre-web, Komga, COPS and Gutenberg emit the bare rel; Kavita and
 * Flibusta emit open-access.
 */
fun isAcquisitionRel(rel: String): Boolean =
    rel == ACQUISITION_REL || rel == OPEN_ACCESS_REL

/**
 * True for the EPUB media types seen in real feeds. The bare `application/epub`
 * is not spec-correct, but Flibusta emits it on a real minority of entries and
 * rejecting those hides books for no gain.
 */
fun isEpubMediaType(type: String): Boolean =
    type == "application/epub+zip" || type == "application/epub"
