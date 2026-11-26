package io.theficos.ereader.ui.reader

import java.util.TimeZone

/** A finished stretch of reading, before it is tied to a book and stored. */
data class RecordedSession(
    val startedAt: Long,
    val endedAt: Long,
    val utcOffsetSeconds: Int,
    /** The share of the book (0..1) gained by page turns and scrolling. */
    val progressionRead: Double,
) {
    val activeSeconds: Long get() = (endedAt - startedAt) / 1_000
}

/**
 * Turns what the reader does with an open book into reading sessions. Pure bookkeeping: every
 * call is a few comparisons, nothing here touches the disk, and finished sessions go to [emit].
 *
 * The clock runs from [begin] (the book open and in the foreground) and is kept running by page
 * turns and scrolls ([onGesture]). With none for [idleCutoffMs] the reader has stopped: the
 * session ends a short grace after the last one (the usual gap between their page turns, at
 * least [minGraceMs]), and the next gesture starts a new session. [end] (background, screen off,
 * book closed) ends the session at once. A session shorter than [minSessionMs] is dropped.
 *
 * Durations come from [elapsed], a clock that only moves forward (so a change of the wall clock
 * cannot stretch a session); [wallNow] only dates the start.
 *
 * Amount read is the forward movement through the book ([onPosition], 0..1) that follows a
 * reading gesture within [gestureWindowMs]. A deliberate jump ([onJump]: slider, highlight,
 * saved place) and anything farther than [maxStep] in one move (a link) only moves the baseline.
 */
class ReadingSessionRecorder(
    private val elapsed: () -> Long,
    private val wallNow: () -> Long,
    private val emit: (RecordedSession) -> Unit,
    private val utcOffsetSecondsAt: (Long) -> Int = { TimeZone.getDefault().getOffset(it) / 1_000 },
    private val idleCutoffMs: Long = IDLE_CUTOFF_MS,
    private val minGraceMs: Long = MIN_GRACE_MS,
    private val minSessionMs: Long = MIN_SESSION_MS,
    private val gestureWindowMs: Long = GESTURE_WINDOW_MS,
) {
    /** The largest move one page turn or scroll can make, as a share of the book. */
    var maxStep: Double = DEFAULT_MAX_STEP

    private var running = false
    private var startedAt = 0L
    private var startedAtWall = 0L
    private var lastActivity = 0L
    private var lastGesture = Long.MIN_VALUE
    private var jumpSettlesAt = Long.MIN_VALUE
    private val gaps = ArrayList<Long>()
    private var position: Double? = null
    private var read = 0.0

    /** The book is open and in front of the reader. A session already running carries on. */
    fun begin() {
        if (running) return
        start(elapsed())
    }

    fun onGesture() {
        val t = elapsed()
        if (!running) return
        if (t - lastActivity > idleCutoffMs) {
            finish(lastActivity + grace())
            start(t)
        } else if (t > lastActivity) {
            gaps += t - lastActivity
        }
        lastActivity = t
        lastGesture = t
    }

    fun onJump() {
        jumpSettlesAt = elapsed() + JUMP_SETTLE_MS
    }

    fun onPosition(progression: Double) {
        if (!progression.isFinite()) return
        val p = progression.coerceIn(0.0, 1.0)
        val t = elapsed()
        val previous = position
        position = p
        if (!running || previous == null || t < jumpSettlesAt || t > lastGesture + gestureWindowMs) return
        val step = p - previous
        if (step > 0 && step <= maxStep) read += step
    }

    /** Background, screen off or book closed: the session ends now, or at its grace if idle. */
    fun end() {
        if (!running) return
        val t = elapsed()
        finish(if (t - lastActivity > idleCutoffMs) lastActivity + grace() else t)
    }

    /**
     * How long after the last page turn an idle reader was probably still reading: their median
     * gap. It cannot pass the cut-off, since a longer gap starts a new session.
     */
    private fun grace(): Long = maxOf(gaps.sorted().getOrNull(gaps.size / 2) ?: 0L, minGraceMs)

    private fun start(t: Long) {
        running = true
        startedAt = t
        startedAtWall = wallNow()
        lastActivity = t
        gaps.clear()
        read = 0.0
    }

    private fun finish(endedAt: Long) {
        running = false
        if (endedAt - startedAt < minSessionMs) return
        emit(RecordedSession(
            startedAt = startedAtWall,
            endedAt = startedAtWall + (endedAt - startedAt),
            utcOffsetSeconds = utcOffsetSecondsAt(startedAtWall),
            progressionRead = read,
        ))
    }

    companion object {
        const val IDLE_CUTOFF_MS = 3 * 60_000L
        const val MIN_GRACE_MS = 30_000L
        const val MIN_SESSION_MS = 30_000L
        const val GESTURE_WINDOW_MS = 5_000L
        const val JUMP_SETTLE_MS = 2_000L
        /** Without the book's positions: a fiftieth of the book. */
        const val DEFAULT_MAX_STEP = 0.02

        /** About ten Readium positions (some 10,000 characters): more than any screen holds. */
        fun maxStepFor(positionCount: Int): Double =
            if (positionCount <= 0) DEFAULT_MAX_STEP else 10.0 / positionCount
    }
}
