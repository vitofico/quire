package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadingSessionRecorderTest {
    private var clock = 1_000_000L
    /** The wall clock runs far from the monotonic one and can be changed under the recorder. */
    private var wallShift = 1_700_000_000_000L
    private val sessions = mutableListOf<RecordedSession>()
    private val recorder = ReadingSessionRecorder(
        elapsed = { clock },
        wallNow = { clock + wallShift },
        emit = { sessions += it },
        utcOffsetSecondsAt = { 3_600 },
    ).apply { maxStep = 0.01 }

    private fun advance(ms: Long) { clock += ms }

    /** A page turn: the gesture, then the position it lands on. */
    private fun turn(to: Double, after: Long = 20_000) {
        advance(after)
        recorder.onGesture()
        advance(300)
        recorder.onPosition(to)
    }

    @Test
    fun `page turns are counted and the session ends on stop`() {
        recorder.onPosition(0.10)
        recorder.begin()
        val start = clock
        turn(0.105)
        turn(0.110)
        turn(0.115)
        advance(10_000)
        recorder.end()

        val s = sessions.single()
        assertThat(s.startedAt).isEqualTo(start + wallShift)
        assertThat(s.endedAt).isEqualTo(clock + wallShift)
        assertThat(s.utcOffsetSeconds).isEqualTo(3_600)
        assertThat(s.progressionRead).isWithin(1e-9).of(0.015)
        assertThat(s.activeSeconds).isEqualTo(70)
    }

    @Test
    fun `an idle pause ends the session a usual page-turn gap after the last activity`() {
        recorder.onPosition(0.2)
        recorder.begin()
        val start = clock
        turn(0.205, after = 40_000)
        turn(0.21, after = 50_000)
        turn(0.215, after = 60_000)
        val lastTurn = clock - 300
        advance(10 * 60_000)
        turn(0.22, after = 0)
        advance(40_000)
        recorder.end()

        assertThat(sessions).hasSize(2)
        // Gaps between gestures: 40 s, 50.3 s, 60.3 s. The median is 50.3 s.
        assertThat(sessions[0].endedAt - wallShift).isEqualTo(lastTurn + 50_300)
        assertThat(sessions[0].startedAt - wallShift).isEqualTo(start)
        assertThat(sessions[0].progressionRead).isWithin(1e-9).of(0.015)
        assertThat(sessions[1].startedAt - wallShift).isEqualTo(clock - 40_300)
        assertThat(sessions[1].progressionRead).isWithin(1e-9).of(0.005)
    }

    @Test
    fun `stopping long after the last page turn counts only a short grace`() {
        recorder.onPosition(0.0)
        recorder.begin()
        val start = clock
        turn(0.005, after = 10_000)
        turn(0.01, after = 10_000)
        advance(30 * 60_000)
        recorder.end()

        // Gaps of about 10 s are below the floor, so the grace is 30 s.
        assertThat(sessions.single().endedAt - wallShift)
            .isEqualTo(start + 20_300 + ReadingSessionRecorder.MIN_GRACE_MS)
    }

    @Test
    fun `a wall clock change does not stretch a session, it only dates its start`() {
        recorder.onPosition(0.0)
        recorder.begin()
        val startWall = clock + wallShift
        turn(0.005)
        wallShift += 3_600_000 // the clock jumps an hour ahead
        turn(0.01)
        advance(20_000)
        recorder.end()

        val s = sessions.single()
        assertThat(s.startedAt).isEqualTo(startWall)
        assertThat(s.activeSeconds).isEqualTo(60)
        assertThat(s.endedAt).isEqualTo(startWall + 60_600)
    }

    @Test
    fun `background ends the session and foreground starts a new one`() {
        recorder.onPosition(0.3)
        recorder.begin()
        turn(0.305)
        advance(20_000)
        recorder.end()
        advance(5 * 60_000)
        recorder.onGesture() // ignored while in the background
        recorder.begin()
        turn(0.31)
        advance(20_000)
        recorder.end()

        assertThat(sessions).hasSize(2)
        assertThat(sessions[1].progressionRead).isWithin(1e-9).of(0.005)
        assertThat(sessions[1].activeSeconds).isEqualTo(40)
    }

    @Test
    fun `begin while running carries the session on`() {
        recorder.onPosition(0.3)
        recorder.begin()
        val start = clock
        turn(0.305)
        recorder.begin()
        advance(20_000)
        recorder.end()

        assertThat(sessions.single().startedAt - wallShift).isEqualTo(start)
    }

    @Test
    fun `sessions under thirty seconds are dropped`() {
        recorder.onPosition(0.3)
        recorder.begin()
        turn(0.305, after = 10_000)
        advance(10_000)
        recorder.end()

        assertThat(sessions).isEmpty()
    }

    @Test
    fun `a jump moves the baseline without counting`() {
        recorder.onPosition(0.1)
        recorder.begin()
        turn(0.105)
        advance(20_000)
        recorder.onJump()
        recorder.onPosition(0.106) // a small jump, within max step, still not counted
        advance(2_500)
        recorder.onJump()
        recorder.onPosition(0.5) // a large jump
        turn(0.505)
        advance(10_000)
        recorder.end()

        assertThat(sessions.single().progressionRead).isWithin(1e-9).of(0.010)
    }

    @Test
    fun `a page turn right after a jump does not count the jump`() {
        recorder.onPosition(0.1)
        recorder.begin()
        advance(20_000)
        recorder.onGesture()
        recorder.onJump()
        advance(200)
        recorder.onPosition(0.105)
        advance(20_000)
        recorder.end()

        assertThat(sessions.single().progressionRead).isEqualTo(0.0)
    }

    @Test
    fun `a move without a recent gesture, or farther than a page, is not read`() {
        recorder.onPosition(0.1)
        recorder.begin()
        advance(20_000)
        recorder.onPosition(0.105) // no gesture: a link or the navigator settling
        turn(0.3) // a gesture, but a link far away
        turn(0.295) // backwards
        advance(20_000)
        recorder.end()

        assertThat(sessions.single().progressionRead).isEqualTo(0.0)
    }

    @Test
    fun `positions before begin set the baseline only`() {
        recorder.onPosition(0.1)
        recorder.onPosition(0.105)
        recorder.begin()
        turn(0.11)
        advance(20_000)
        recorder.end()

        assertThat(sessions.single().progressionRead).isWithin(1e-9).of(0.005)
    }

    @Test
    fun `max step follows the book's positions`() {
        assertThat(ReadingSessionRecorder.maxStepFor(1_000)).isWithin(1e-12).of(0.01)
        assertThat(ReadingSessionRecorder.maxStepFor(0)).isEqualTo(ReadingSessionRecorder.DEFAULT_MAX_STEP)
    }
}
