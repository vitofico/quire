package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiProviderException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.DiscussAnswer
import io.theficos.ereader.data.ai.DiscussLimits
import io.theficos.ereader.data.ai.DiscussRequest
import io.theficos.ereader.data.ai.QuotaInfo
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightsDiscussionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val limits = MutableStateFlow<DiscussLimits?>(
        DiscussLimits(20, 2000, 1000, 500, 10, 200, 1000, 4000, 3, 60_000, 10),
    )
    private val sent = mutableListOf<DiscussRequest>()
    private val answers = ArrayDeque<CompletableDeferred<DiscussAnswer>>()
    private lateinit var vm: HighlightsDiscussionViewModel

    private fun highlight(id: String, text: String, comment: String? = null) =
        Highlight(id, DocumentIdentity(contentHash = "h"), "{}", text, HighlightColour.GREEN, "Dune", "Frank Herbert", 0, 0, comment = comment)

    /** The next answer the fake server gives; complete it to answer. */
    private fun nextAnswer() = CompletableDeferred<DiscussAnswer>().also(answers::addLast)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = HighlightsDiscussionViewModel(limits) { request ->
            sent += request
            answers.removeFirst().await()
        }
        vm.open(listOf(highlight("a", "Fear is the mind-killer.", "Stoics"), highlight("b", "The spice must flow.")))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an answer is added, the box empties and the notes choice locks`() = runTest(dispatcher) {
        val answer = nextAnswer()
        vm.setDraft("What connects these?")
        vm.ask(vm.state.value.draft)
        runCurrent()
        assertThat(vm.state.value.asking).isEqualTo("What connects these?")
        assertThat(vm.state.value.notesLocked).isTrue()
        vm.setIncludeNotes(false)
        assertThat(vm.state.value.includeNotes).isTrue()

        answer.complete(DiscussAnswer("Control.", listOf(1)))
        runCurrent()
        val state = vm.state.value
        assertThat(state.asking).isNull()
        assertThat(state.draft).isEmpty()
        assertThat(state.exchanges.single().answer).isEqualTo("Control.")
        assertThat(state.questionsLeft(limits.value!!)).isEqualTo(2)
        assertThat(sent.single().highlights.first().note).isEqualTo("Stoics")
    }

    @Test fun `only reader turns count and the limit stops further questions until Start again`() = runTest(dispatcher) {
        repeat(3) { turn ->
            val answer = nextAnswer()
            vm.ask("Question $turn")
            runCurrent()
            answer.complete(DiscussAnswer("Answer $turn"))
            runCurrent()
        }
        // The third question carried the two earlier exchanges: five turns, three of them the reader's.
        assertThat(sent.last().messages.count { it.role == "reader" }).isEqualTo(3)
        assertThat(sent.last().messages).hasSize(5)
        assertThat(vm.state.value.questionsLeft(limits.value!!)).isEqualTo(0)
        vm.ask("One more")
        assertThat(sent).hasSize(3)

        vm.startAgain()
        assertThat(vm.state.value.exchanges).isEmpty()
        assertThat(vm.state.value.passages).hasSize(2)
        vm.setIncludeNotes(false)
        assertThat(vm.state.value.includeNotes).isFalse()
    }

    @Test fun `cancel drops the wait and an answer that arrives later is ignored`() = runTest(dispatcher) {
        val late = nextAnswer()
        vm.ask("Slow?")
        runCurrent()
        vm.cancel()
        assertThat(vm.state.value.asking).isNull()
        late.complete(DiscussAnswer("Too late"))
        runCurrent()
        assertThat(vm.state.value.exchanges).isEmpty()
        assertThat(vm.state.value.notesLocked).isFalse()
    }

    @Test fun `an answer for a conversation that was started again or closed is dropped`() = runTest(dispatcher) {
        val first = nextAnswer()
        vm.ask("First?")
        runCurrent()
        vm.startAgain()
        first.complete(DiscussAnswer("Stale"))
        runCurrent()
        assertThat(vm.state.value.exchanges).isEmpty()

        val second = nextAnswer()
        vm.ask("Second?")
        runCurrent()
        vm.close()
        second.complete(DiscussAnswer("Stale too"))
        runCurrent()
        assertThat(vm.state.value.open).isFalse()
        assertThat(vm.state.value.exchanges).isEmpty()
    }

    @Test fun `a failure keeps the question in the box and says why`() = runTest(dispatcher) {
        val answer = nextAnswer()
        vm.setDraft("Why?")
        vm.ask("Why?")
        runCurrent()
        answer.completeExceptionally(AiHttpException(429, """{"detail":"discuss_rate_limited"}"""))
        runCurrent()
        assertThat(vm.state.value.error).isEqualTo(uiText(R.string.details_busy))
        assertThat(vm.state.value.draft).isEqualTo("Why?")
        assertThat(vm.state.value.exchanges).isEmpty()
    }

    @Test fun `a conversation over the total is refused before anything is sent`() = runTest(dispatcher) {
        limits.value = limits.value!!.copy(maxTotalChars = 10)
        vm.ask("What connects these?")
        assertThat(sent).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(uiText(R.string.discuss_too_large))
    }

    @Test fun `nothing is asked once the server stops offering the feature`() = runTest(dispatcher) {
        limits.value = null
        vm.ask("Still there?")
        assertThat(sent).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(uiText(R.string.discuss_not_offered))
    }

    @Test fun `opening another selection starts a new conversation`() = runTest(dispatcher) {
        val answer = nextAnswer()
        vm.ask("First?")
        runCurrent()
        vm.open(listOf(highlight("c", "Another passage.")))
        answer.complete(DiscussAnswer("Stale"))
        runCurrent()
        assertThat(vm.state.value.passages.single().text).isEqualTo("Another passage.")
        assertThat(vm.state.value.exchanges).isEmpty()
        assertThat(vm.state.value.hasNotes).isFalse()
    }

    @Test fun `the share note is the latest answer`() = runTest(dispatcher) {
        assertThat(vm.shareNote("Passages")).isNull()
        val answer = nextAnswer()
        vm.ask("Q?")
        runCurrent()
        answer.complete(DiscussAnswer("A.", listOf(2)))
        runCurrent()
        assertThat(vm.shareNote("Passages")).isEqualTo("Q?\n\nA.\n\nPassages\n1. \"The spice must flow.\" (Dune, Frank Herbert)")
    }

    @Test fun `server errors map to the reader's words`() {
        fun http(code: Int, body: String) = discussErrorMessage(AiHttpException(code, body))
        assertThat(discussErrorMessage(AiQuotaException(QuotaInfo(200, 200, "2026-11-27T00:00:00Z"))))
            .isEqualTo(uiText(R.string.details_allowance_used_until, "2026-11-27"))
        assertThat(http(429, """{"detail":"monthly_ai_quota_exhausted"}""")).isEqualTo(uiText(R.string.details_allowance_used))
        assertThat(http(429, """{"detail":"discuss_rate_limited"}""")).isEqualTo(uiText(R.string.details_busy))
        assertThat(http(409, """{"detail":"ai_not_opted_in"}""")).isEqualTo(uiText(R.string.details_ai_not_available))
        assertThat(http(409, """{"detail":"ai_provider_unavailable"}""")).isEqualTo(uiText(R.string.discuss_provider_gone))
        assertThat(http(422, """{"detail":"ai_provider_unknown"}""")).isEqualTo(uiText(R.string.discuss_provider_gone))
        assertThat(http(503, """{"detail":"ai_disabled"}""")).isEqualTo(uiText(R.string.discuss_not_offered))
        assertThat(http(404, "")).isEqualTo(uiText(R.string.discuss_not_offered))
        assertThat(http(503, """{"detail":"ai_policy_unavailable"}""")).isEqualTo(uiText(R.string.details_busy))
        assertThat(http(422, """{"detail":{"code":"too_large","field":"request","limit":60000}}""")).isEqualTo(uiText(R.string.discuss_too_large))
        assertThat(http(422, """{"detail":{"code":"too_long","field":"messages[0].content","limit":1000}}""")).isEqualTo(uiText(R.string.discuss_refused))
        assertThat(http(502, "")).isEqualTo(uiText(R.string.discuss_failed))
        assertThat(discussErrorMessage(AiProviderException(504, "", "provider_timeout", "The AI provider did not answer.", null, null)))
            .isEqualTo(UiText.Dynamic("The AI provider did not answer."))
        assertThat(discussErrorMessage(SocketTimeoutException())).isEqualTo(uiText(R.string.bookdetail_the_server_took_too_long_to_answer))
        assertThat(discussErrorMessage(IOException())).isEqualTo(uiText(R.string.bookdetail_couldn_t_reach_the_server_check_the))
    }
}
