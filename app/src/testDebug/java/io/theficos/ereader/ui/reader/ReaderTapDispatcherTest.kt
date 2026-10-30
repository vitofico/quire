package io.theficos.ereader.ui.reader

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderTapDispatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<String>()
    private val child = RecordingView(context)
    private val dispatcher = ReaderTapDispatcher(context).apply {
        onPrev = { actions += "previous" }
        onNext = { actions += "next" }
        onToggleChrome = { actions += "chrome" }
        addView(child, FrameLayout.LayoutParams(300, 300))
        measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, 300, 300)
    }
    private var downTime = 0L

    @Test fun `normal taps navigate at edges and toggle chrome in the middle`() {
        tap(30f)
        tap(150f)
        tap(270f)

        assertThat(actions).containsExactly("previous", "chrome", "next").inOrder()
        assertThat(child.events).containsExactly(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
        ).inOrder()
    }

    @Test fun `visible chrome hides on a tap in any region without turning a page`() {
        dispatcher.chromeVisible = true
        tap(30f)
        tap(150f)
        tap(270f)

        assertThat(actions).containsExactly("chrome", "chrome", "chrome")
    }

    @Test fun `disabling tap navigation leaves only the middle chrome action`() {
        dispatcher.tapNavigationEnabled = false
        tap(30f)
        tap(150f)
        tap(270f)

        assertThat(actions).containsExactly("chrome")
    }

    @Test fun `selection mode forwards taps but never hides chrome or navigates`() {
        dispatcher.selectionModeEnabled = true
        dispatcher.chromeVisible = true
        tap(30f)
        tap(150f)
        tap(270f)

        assertThat(actions).isEmpty()
        assertThat(child.events).hasSize(6)
        assertThat(child.events.count { it == MotionEvent.ACTION_UP }).isEqualTo(3)
    }

    @Test fun `selection mode forwards swipes and long presses unchanged`() {
        dispatcher.selectionModeEnabled = true
        swipe()
        longPress()

        assertThat(actions).isEmpty()
        assertThat(child.events).containsExactly(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
        ).inOrder()
    }

    @Test fun `normal swipes and long presses never become page taps`() {
        swipe()
        longPress()

        assertThat(actions).isEmpty()
        assertThat(child.events).hasSize(5)
    }

    @Test fun `entering and leaving selection mode mid touch cancels the pending reader tap`() {
        send(MotionEvent.ACTION_DOWN, 270f)
        dispatcher.selectionModeEnabled = true
        dispatcher.selectionModeEnabled = false
        advance(30)
        send(MotionEvent.ACTION_UP, 270f)

        assertThat(actions).isEmpty()
        // The cancellation is private to the gesture observer, never sent to Readium.
        assertThat(child.events).containsExactly(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).inOrder()
        tap(270f)
        assertThat(actions).containsExactly("next")
    }

    @Test fun `a touch begun in selection mode cannot navigate after Done`() {
        dispatcher.selectionModeEnabled = true
        send(MotionEvent.ACTION_DOWN, 30f)
        dispatcher.selectionModeEnabled = false
        advance(30)
        send(MotionEvent.ACTION_UP, 30f)

        assertThat(actions).isEmpty()
        tap(30f)
        assertThat(actions).containsExactly("previous")
    }

    @Test fun `selection mode starting during a normal touch suppresses its up action`() {
        send(MotionEvent.ACTION_DOWN, 150f)
        dispatcher.selectionModeEnabled = true
        advance(30)
        send(MotionEvent.ACTION_UP, 150f)

        assertThat(actions).isEmpty()
        assertThat(child.events).containsExactly(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).inOrder()
    }

    @Test fun `reassigning the current mode does not cancel an ordinary tap`() {
        send(MotionEvent.ACTION_DOWN, 150f)
        dispatcher.selectionModeEnabled = false
        advance(30)
        send(MotionEvent.ACTION_UP, 150f)

        assertThat(actions).containsExactly("chrome")
    }

    private fun tap(x: Float) {
        send(MotionEvent.ACTION_DOWN, x)
        advance(30)
        send(MotionEvent.ACTION_UP, x)
        advance(300)
    }

    private fun swipe() {
        send(MotionEvent.ACTION_DOWN, 270f)
        advance(30)
        send(MotionEvent.ACTION_MOVE, 150f)
        advance(30)
        send(MotionEvent.ACTION_UP, 30f)
        advance(300)
    }

    private fun longPress() {
        send(MotionEvent.ACTION_DOWN, 150f)
        advance(1_000)
        send(MotionEvent.ACTION_UP, 150f)
        advance(300)
    }

    private fun send(action: Int, x: Float) {
        val time = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val event = MotionEvent.obtain(downTime, time, action, x, 150f, 0)
        try {
            assertThat(dispatcher.dispatchTouchEvent(event)).isTrue()
        } finally {
            event.recycle()
        }
    }

    private fun advance(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    }

    private class RecordingView(context: Context) : View(context) {
        val events = mutableListOf<Int>()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            events += event.actionMasked
            return true
        }
    }
}
