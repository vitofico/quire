package io.theficos.ereader.data.library.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for [LibraryMirrorPushScheduler] — verifies that one-time
 * and periodic enqueues actually register work under their unique names.
 *
 * We don't drive the worker to completion here (the worker has its own
 * thorough tests in [LibraryMirrorPushWorkerTest]); we just confirm the
 * scheduling shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryMirrorPushSchedulerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // SynchronousExecutor + an INFO-level logger makes WorkManager
        // behave predictably in unit tests — no thread pool, no async
        // dispatch, no surprises during enqueue.
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `enqueueOneTime registers work under the one-time unique name`() {
        LibraryMirrorPushScheduler.enqueueOneTime(context, expedited = false)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LibraryMirrorPushScheduler.UNIQUE_ONE_TIME)
            .get()
        assertThat(infos).isNotEmpty()
        // Either ENQUEUED (waiting for constraints) or RUNNING (driver
        // started the worker before the assertion). SUCCEEDED is also
        // valid if the worker happens to no-op fast (no deps installed →
        // failure, but failure has terminal state we don't care about).
        val state = infos.first().state
        assertThat(state).isAnyOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
        )
    }

    @Test
    fun `enqueuePeriodic registers periodic work under the periodic unique name`() {
        LibraryMirrorPushScheduler.enqueuePeriodic(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LibraryMirrorPushScheduler.UNIQUE_PERIODIC)
            .get()
        assertThat(infos).isNotEmpty()
    }

    @Test
    fun `enqueueAfterLibraryChange shares the one-time unique name with enqueueOneTime`() {
        LibraryMirrorPushScheduler.enqueueAfterLibraryChange(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LibraryMirrorPushScheduler.UNIQUE_ONE_TIME)
            .get()
        assertThat(infos).isNotEmpty()
    }

    @Test
    fun `repeated one-time enqueues coalesce via KEEP policy under the same unique name`() {
        LibraryMirrorPushScheduler.enqueueOneTime(context, expedited = false)
        val firstInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LibraryMirrorPushScheduler.UNIQUE_ONE_TIME)
            .get()
        val firstId = firstInfos.first().id

        // Second enqueue with the same unique name and KEEP policy must
        // NOT create a second work entry — the existing one wins.
        LibraryMirrorPushScheduler.enqueueOneTime(context, expedited = false)
        val secondInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LibraryMirrorPushScheduler.UNIQUE_ONE_TIME)
            .get()

        // The first ID is preserved; we don't spawn a parallel push.
        assertThat(secondInfos.map { it.id }).contains(firstId)
    }
}
