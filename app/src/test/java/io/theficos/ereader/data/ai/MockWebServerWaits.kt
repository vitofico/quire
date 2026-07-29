package io.theficos.ereader.data.ai

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/** How long a drain waits for a request that should already have landed. */
private const val REQUEST_WAIT_SECONDS = 10L

/**
 * Bounded replacement for `MockWebServer.takeRequest()`.
 *
 * The no-argument overload is a bare `LinkedBlockingQueue.take()`: if the suite
 * drains more requests than actually reached the server, it parks the calling
 * thread forever. That is a live hazard in this package because
 * [AiRepository.refresh] wraps both of its HTTP calls in `runCatching` and is
 * documented as "silent on failure" — a call that dies before its request hits
 * the wire (an OkHttp `callTimeout` expiring on a contended CI runner, a
 * connect-time reset) leaves `refresh()` returning normally with only one
 * recorded request, and the second drain never comes back.
 *
 * Blocking the thread is what makes that fatal rather than flaky. On the JVM
 * `runTest` runs inside `runBlocking` and schedules its 60-second wall-clock
 * guard on that same event loop, so a test that blocks the loop's own thread
 * can never be timed out: the worker hangs until CI kills the job. That is how
 * `v2026.07.28.223` came to be tagged with no APK attached (issue #93).
 *
 * Waiting with a deadline turns the hang into an ordinary test failure. The
 * budget is deliberately well past the 5-second `callTimeout` these suites
 * configure, so a merely slow runner still passes.
 */
internal fun MockWebServer.awaitRequest(): RecordedRequest =
    checkNotNull(takeRequest(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS)) {
        "MockWebServer recorded no request within ${REQUEST_WAIT_SECONDS}s: the client " +
            "call never reached the server, so this drain is off by one."
    }
