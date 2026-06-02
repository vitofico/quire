package io.theficos.ereader.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for #78: on API < 31 WorkManager runs an expedited
 * [SyncWorker] as a foreground service and calls `getForegroundInfo()`.
 * The default `CoroutineWorker` implementation throws
 * `IllegalStateException("Not implemented")`, which crashed the app on
 * Android 9. The override must return a valid `ForegroundInfo`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncWorkerForegroundTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `getForegroundInfo returns a notification on Android 9`() = runTest {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        val info = worker.getForegroundInfo()

        assertThat(info.notificationId).isNotEqualTo(0)
        assertThat(info.notification).isNotNull()
    }
}
