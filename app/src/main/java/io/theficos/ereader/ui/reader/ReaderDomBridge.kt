package io.theficos.ereader.ui.reader

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

internal data class ReaderWebViewCandidate<T>(val view: T, val url: String?)
internal data class VerifiedReaderScript(val documentUrl: String, val value: String)

// Pinned Readium 3.0 WebViewServer.publicationBaseHref. Quire opens local EPUB assets;
// Readium resolves their relative reading-order hrefs against this exact server URL.
private val READER_PUBLICATION_BASE = URI("https://readium/publication/")

private fun resourceUri(value: String): URI = URI(value.substringBefore('#')).normalize()

internal fun readerResourceMatches(url: String?, href: String): Boolean = runCatching {
    val actual = resourceUri(url ?: return false)
    val relative = resourceUri(href)
    val expected = if (relative.isAbsolute) relative else READER_PUBLICATION_BASE.resolve(relative).normalize()
    actual == expected
}.getOrDefault(false)

internal fun <T> selectReaderResource(
    candidates: List<ReaderWebViewCandidate<T>>, expectedHref: String,
): ReaderWebViewCandidate<T>? = candidates.filter { readerResourceMatches(it.url, expectedHref) }.singleOrNull()

internal fun readerScriptEnvelope(script: String): String = """
    (function () {
      var before = String(window.location.href);
      var value = (${script.trim().removeSuffix(";")});
      return { documentUrl: before, afterDocumentUrl: String(window.location.href), value: value };
    })();
""".trimIndent()

internal fun verifiedReaderScript(raw: String?, requestedUrl: String?, expectedHref: String): VerifiedReaderScript? {
    val requested = requestedUrl ?: return null
    val obj = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
    val source = obj.optString("documentUrl")
    val after = obj.optString("afterDocumentUrl")
    fun sameDocument(a: String, b: String) = runCatching { resourceUri(a) == resourceUri(b) }.getOrDefault(false)
    if (!readerResourceMatches(source, expectedHref) || !sameDocument(source, requested) || !sameDocument(source, after)) return null
    val value = when (val result = obj.opt("value")) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray, is Boolean, is Number -> result.toString()
        is String -> JSONObject.quote(result)
        else -> return null
    }
    return VerifiedReaderScript(source, value)
}

/** Shared selection/callback contract for capture and proof, independently testable without a WebView. */
internal suspend fun <T> evaluateReaderResource(
    expectedHref: String,
    currentHref: () -> String,
    candidates: List<ReaderWebViewCandidate<T>>,
    currentUrl: (T) -> String?,
    script: String,
    evaluate: suspend (T, String) -> String?,
): VerifiedReaderScript? {
    fun currentStillMatches() = currentHref().substringBefore('#') == expectedHref.substringBefore('#')
    if (!currentStillMatches()) return null
    val candidate = selectReaderResource(candidates, expectedHref) ?: return null
    val raw = evaluate(candidate.view, readerScriptEnvelope(script))
    if (!currentStillMatches() || currentUrl(candidate.view) != candidate.url) return null
    return verifiedReaderScript(raw, candidate.url, expectedHref)
}

/** Readium's own JS wait is not cancellation-aware. Platform callbacks permit bounded waits. */
internal suspend fun evaluateReaderJavascript(
    navigator: EpubNavigatorFragment, script: String, expectedHref: String,
): VerifiedReaderScript? {
    val root = runCatching { navigator.publicationView }.getOrNull() ?: return null
    return evaluateReaderResource(expectedHref, { navigator.currentLocator.value.href.toString() },
        visibleWebViews(root).map { ReaderWebViewCandidate(it, it.url) }, { it.url }, script) { view, wrapped ->
        awaitReaderJavascript { callback -> view.evaluateJavascript(wrapped, callback) }
    }
}

internal suspend fun awaitReaderJavascript(evaluate: (ValueCallback<String>) -> Unit): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            evaluate(ValueCallback { result -> if (continuation.isActive) continuation.resume(result) })
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

private fun visibleWebViews(view: View): List<WebView> {
    if (!view.isShown || !view.getGlobalVisibleRect(Rect())) return emptyList()
    if (view is WebView) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { visibleWebViews(view.getChildAt(it)) }
}
