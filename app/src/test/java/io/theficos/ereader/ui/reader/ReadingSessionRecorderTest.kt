package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadingSessionRecorderTest {
    private var clock = 1_000_000L
    private val sessions = mutableListOf<RecordedSession>()
    private val recorder = ReadingSessionRecorder(
        now = { clock },
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
        assertThat(s.startedAt).isEqualTo(start)
        assertThat(s.endedAt).isEqualTo(clock)
        assertThat(s.utcOffsetSeconds).isEqualTo(3_600)
        assertThat(s.startProgression).isEqualTo(0.10)
        assertThat(s.endProgression).isEqualTo(0.115)
        assertThat(s.progressionRead).isWithin(1e-9).of(0.015)
        assertThat(s.activeSeconds).isEqualTo(70)
    }

    @Test
    fun `an idle pause ends the session three minutes after the last activity`() {
        recorder.onPosition(0.2)
        recorder.begin()
        turn(0.205)
        val lastTurn = clock - 300
        advance(10 * 60_000)
        turn(0.21, after = 0)
        advance(40_000)
        recorder.end()

        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].endedAt).isEqualTo(lastTurn + ReadingSessionRecorder.IDLE_CUTOFF_MS)
        assertThat(sessions[0].progressionRead).isWithin(1e-9).of(0.005)
        assertThat(sessions[1].startedAt).isEqualTo(clock - 40_300)
        assertThat(sessions[1].startProgression).isEqualTo(0.205)
        assertThat(sessions[1].progressionRead).isWithin(1e-9).of(0.005)
    }

    @Test
    fun `stopping long after the last page turn counts only up to the idle cut-off`() {
        recorder.onPosition(0.0)
        recorder.begin()
        val start = clock
        turn(0.005)
        advance(30 * 60_000)
        recorder.end()

        assertThat(sessions.single().endedAt).isEqualTo(start + 20_000 + ReadingSessionRecorder.IDLE_CUTOFF_MS)
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
        assertThat(sessions[1].startProgression).isEqualTo(0.305)
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

        assertThat(sessions.single().startedAt).isEqualTo(start)
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

        val s = sessions.single()
        assertThat(s.progressionRead).isWithin(1e-9).of(0.010)
        assertThat(s.endProgression).isEqualTo(0.505)
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

        assertThat(sessions.single().startProgression).isEqualTo(0.105)
        assertThat(sessions.single().progressionRead).isWithin(1e-9).of(0.005)
    }

    @Test
    fun `max step follows the book's positions`() {
        assertThat(ReadingSessionRecorder.maxStepFor(1_000)).isWithin(1e-12).of(0.01)
        assertThat(ReadingSessionRecorder.maxStepFor(0)).isEqualTo(ReadingSessionRecorder.DEFAULT_MAX_STEP)
    }
}
