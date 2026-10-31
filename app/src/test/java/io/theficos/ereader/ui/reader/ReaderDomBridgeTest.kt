package io.theficos.ereader.ui.reader

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReaderDomBridgeTest {
    private val currentUrl = "https://readium/publication/EPUB/current.xhtml"
    private val adjacentUrl = "https://readium/publication/EPUB/adjacent.xhtml"
    private val href = "EPUB/current.xhtml"
    private fun envelope(url: String, value: Any, after: String = url) = JSONObject()
        .put("documentUrl", url).put("afterDocumentUrl", after).put("value", value).toString()

    @Test fun `capture and proof choose matching current resource even when adjacent view comes first`() = runTest {
        val urls = mapOf("adjacent" to adjacentUrl, "current" to currentUrl)
        val selected = mutableListOf<String>()
        val candidates = urls.map { ReaderWebViewCandidate(it.key, it.value) }
        for (script in listOf("({cssSelector:'#p4'})", "true")) {
            val result = evaluateReaderResource(href, { href }, candidates, { urls[it] }, script) { view, wrapped ->
                selected += view
                assertThat(wrapped).contains("afterDocumentUrl")
                envelope(urls.getValue(view), if (script == "true") true else JSONObject().put("cssSelector", "#p4"))
            }
            assertThat(result?.documentUrl).isEqualTo(currentUrl)
            if (script == "true") assertThat(strictJavascriptTrue(result?.value)).isTrue()
            else assertThat(JSONObject(result!!.value).getString("cssSelector")).isEqualTo("#p4")
        }
        assertThat(selected).containsExactly("current", "current")
    }

    @Test fun `multiple matching views or no matching view fail closed without evaluating DOM`() = runTest {
        var evaluations = 0
        for (candidates in listOf(
            listOf(ReaderWebViewCandidate("one", currentUrl), ReaderWebViewCandidate("two", currentUrl)),
            listOf(ReaderWebViewCandidate("adjacent", adjacentUrl)),
        )) {
            val result = evaluateReaderResource(href, { href }, candidates, { currentUrl }, "true") { _, _ -> evaluations++; envelope(currentUrl, true) }
            assertThat(result).isNull()
        }
        assertThat(evaluations).isEqualTo(0)
    }

    @Test fun `requested current URL cannot relabel adjacent or redirected actual DOM`() {
        assertThat(verifiedReaderScript(envelope(adjacentUrl, true), currentUrl, href)).isNull()
        assertThat(verifiedReaderScript(envelope(currentUrl, true, adjacentUrl), currentUrl, href)).isNull()
        assertThat(verifiedReaderScript("true", currentUrl, href)).isNull()
    }

    @Test fun `current reading resource changing while capture awaits rejects callback`() = runTest {
        var current = href
        val pending = CompletableDeferred<String>()
        val result = async {
            evaluateReaderResource(href, { current }, listOf(ReaderWebViewCandidate("view", currentUrl)),
                { currentUrl }, "({cssSelector:'#p4'})") { _, _ -> pending.await() }
        }
        testScheduler.runCurrent()
        current = "EPUB/adjacent.xhtml"
        pending.complete(envelope(currentUrl, JSONObject().put("cssSelector", "#p4")))
        assertThat(result.await()).isNull()
    }

    @Test fun `selected WebView navigation during callback also rejects capture`() = runTest {
        var requested = currentUrl
        val pending = CompletableDeferred<String>()
        val result = async {
            evaluateReaderResource(href, { href }, listOf(ReaderWebViewCandidate("view", currentUrl)),
                { requested }, "true") { _, _ -> pending.await() }
        }
        testScheduler.runCurrent()
        requested = adjacentUrl
        pending.complete(envelope(currentUrl, true))
        assertThat(result.await()).isNull()
    }

    @Test fun `relative href resolves exact Readium URL including directory origin and query`() {
        assertThat(readerResourceMatches(currentUrl, href)).isTrue()
        assertThat(readerResourceMatches("https://readium/publication/else/EPUB/current.xhtml", href)).isFalse()
        assertThat(readerResourceMatches("https://other/publication/EPUB/current.xhtml", href)).isFalse()
        assertThat(readerResourceMatches("https://readium/publication/EPUB/current.xhtml?different=1", href)).isFalse()
        assertThat(readerResourceMatches(currentUrl + "#section", href)).isTrue()
        assertThat(readerResourceMatches("https://readium/publication/nested/current.xhtml", "current.xhtml")).isFalse()
    }
}
