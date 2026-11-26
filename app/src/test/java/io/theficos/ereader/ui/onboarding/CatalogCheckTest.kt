package io.theficos.ereader.ui.onboarding

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogCheckTest {
    private val server = MockWebServer()
    private val check = CatalogCheck(ServerProbe(), OkHttpClient())

    @After fun tearDown() = server.shutdown()

    private fun feedOf(entryLink: String): CatalogCheck.Result {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/atom+xml").setBody(
                """<feed xmlns="http://www.w3.org/2005/Atom"><title>Home</title>
                     <entry><title>Latest</title>$entryLink</entry>
                   </feed>""",
            ),
        )
        return check.feed(AccountCredentials.OpdsOnly(server.url("/opds/").toString()))
    }

    @Test fun `an entry linking a bare Atom feed is navigation, as Ebooks libres et gratuits does`() {
        assertThat(feedOf("""<link type="application/atom+xml" href="feed.php"/>"""))
            .isInstanceOf(CatalogCheck.Result.Found::class.java)
    }

    @Test fun `an entry with only a comments feed is not a catalog`() {
        assertThat(feedOf("""<link rel="replies" type="application/atom+xml" href="/post/1/feed/"/>"""))
            .isEqualTo(CatalogCheck.Result.Failed(uiText(R.string.onboarding_that_url_answered_but_it_isn_t)))
    }

    @Test fun `an unreachable host is worded, not the raw exception`() {
        val result = check.feed(AccountCredentials.OpdsOnly("https://catalog.invalid/opds"))
        assertThat(result).isEqualTo(CatalogCheck.Result.Failed(uiText(R.string.onboarding_we_couldn_t_resolve_that_hostname_check)))
    }
}
