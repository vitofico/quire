package io.theficos.ereader.ui.reader

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Observes reader taps without consuming the WebView's touches. Swipes, long presses and
 * selection handles still belong to Readium. Selection mode bypasses every reader tap action,
 * including hiding the chrome; disabling tap navigation alone deliberately does not.
 */
internal class ReaderTapDispatcher(context: Context) : FrameLayout(context) {
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onToggleChrome: () -> Unit = {}
    var chromeVisible: Boolean = false
    var tapNavigationEnabled: Boolean = true

    var selectionModeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // A mode change can arrive between DOWN and UP. Cancel only our observer, leaving
            // the child's touch stream intact, and require a fresh DOWN before observing again.
            observeCurrentGesture = false
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try {
                gesture.onTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
        }

    private var observeCurrentGesture = false

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width.toFloat()
            if (w <= 0f) return false
            val frac = e.x / w
            // While the controls show, a tap on the page puts them away rather than turning it:
            // it is most likely a reach for a control that missed.
            if (chromeVisible) {
                onToggleChrome()
            } else if (tapNavigationEnabled) {
                when {
                    frac < 0.33f -> onPrev()
                    frac > 0.67f -> onNext()
                    else -> onToggleChrome()
                }
            } else if (frac in 0.33f..0.67f) {
                onToggleChrome()
            }
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            observeCurrentGesture = !selectionModeEnabled
        }
        if (!selectionModeEnabled && observeCurrentGesture) gesture.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            observeCurrentGesture = false
        }
        return super.dispatchTouchEvent(ev)
    }
}
