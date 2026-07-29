package io.theficos.ereader.data.library.sync

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** How long a drain waits for a request that should already have landed. */
private const val REQUEST_WAIT_SECONDS = 10L

/**
 * Bounded replacement for `MockWebServer.takeRequest()`.
 *
 * The no-argument overload is a bare `LinkedBlockingQueue.take()`, so draining
 * one request more than actually reached the server parks the calling thread
 * forever. A `ListenableWorker` reports trouble by returning `Result.retry()`
 * or `Result.failure()` rather than by throwing, so a push that dies before its
 * request hits the wire leaves `doWork()` returning normally with a short
 * request queue and the next drain never comes back.
 *
 * Blocking the thread is what makes that fatal rather than flaky: on the JVM
 * `runTest` runs inside `runBlocking` and schedules its 60-second wall-clock
 * guard on the same event loop, so a test that blocks that loop's own thread
 * can never be timed out — the worker hangs until CI kills the job. An
 * equivalent unbounded drain in `:app` is what stalled a build for six hours
 * and left `v2026.07.28.223` tagged with no APK attached (issue #93).
 *
 * The budget is well past the 5-second `callTimeout` this suite configures, so
 * a merely slow runner still passes.
 */
internal fun MockWebServer.awaitRequest(): RecordedRequest =
    checkNotNull(takeRequest(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS)) {
        "MockWebServer recorded no request within ${REQUEST_WAIT_SECONDS}s: the worker's " +
            "call never reached the server, so this drain is off by one."
    }
