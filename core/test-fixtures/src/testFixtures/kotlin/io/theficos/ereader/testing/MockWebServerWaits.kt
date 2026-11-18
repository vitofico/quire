package io.theficos.ereader.testing

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

private const val REQUEST_WAIT_SECONDS = 10L

/** A missing request must fail the test instead of blocking its coroutine timeout (issue #93). */
fun MockWebServer.awaitRequest(): RecordedRequest =
    takeRequest(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS)
        ?: throw AssertionError("MockWebServer received no request within ${REQUEST_WAIT_SECONDS}s")
