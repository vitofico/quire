package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class HighlightsDiscussTest {
    private val limits = DiscussLimits(
        maxHighlights = 20, maxTextChars = 10, maxNoteChars = 5, maxTitleChars = 4, maxAuthors = 2,
        maxAuthorChars = 3, maxQuestionChars = 100, maxAnswerChars = 400, maxQuestions = 6,
        maxTotalChars = 1_000, questionsPerMinute = 10,
    )

    private fun highlight(text: String, comment: String? = null, title: String = "Dune", author: String? = "Frank Herbert") =
        Highlight("id-$text", DocumentIdentity(contentHash = "h"), "{}", text, HighlightColour.YELLOW, title, author, 0, 0, comment = comment)

    @Test fun `passages are numbered in order and blank notes, titles and authors count as none`() {
        val passages = discussPassages(listOf(highlight("One", comment = "  "), highlight("Two", comment = " Mine ", title = " ", author = " ")))
        assertThat(passages).containsExactly(
            DiscussPassage(1, "One", null, "Dune", listOf("Frank Herbert")),
            DiscussPassage(2, "Two", "Mine", null, emptyList()),
        ).inOrder()
    }

    @Test fun `long values are clipped by code point and only a clipped text is reported`() {
        // Each emoji is one code point but two UTF-16 units, so a char-based cut would split one.
        val emoji = "😀".repeat(12)
        val passages = listOf(DiscussPassage(1, emoji, "123456", "Title", listOf("Abcd", "Efg", "Hij")))
        val draft = discussDraft(passages, emptyList(), "  Why?  ", includeNotes = true, limits = limits)
        val sent = draft.request.highlights.single()
        assertThat(sent.text).isEqualTo("😀".repeat(10))
        assertThat(sent.note).isEqualTo("12345")
        assertThat(sent.title).isEqualTo("Titl")
        assertThat(sent.authors).containsExactly("Abc", "Efg").inOrder()
        assertThat(draft.clipped).isTrue()
        assertThat(draft.request.messages).containsExactly(DiscussMessage("reader", "Why?"))

        val short = discussDraft(listOf(DiscussPassage(1, "Short", "a much longer note", null, emptyList())), emptyList(), "Q", true, limits)
        assertThat(short.clipped).isFalse()
    }

    @Test fun `notes stay home when left out and turns alternate ending with the new question`() {
        val passages = listOf(DiscussPassage(1, "Text", "Note", "T", emptyList()))
        val history = listOf(DiscussExchange("First?", "Because.", listOf(1)))
        val draft = discussDraft(passages, history, "Then?", includeNotes = false, limits = limits)
        assertThat(draft.request.highlights.single().note).isNull()
        assertThat(draft.request.messages.map { it.role to it.content }).containsExactly(
            "reader" to "First?", "assistant" to "Because.", "reader" to "Then?",
        ).inOrder()
    }

    @Test fun `the total counts text, note, title, authors and every message, as the server does`() {
        val passages = listOf(DiscussPassage(1, "abcde", "fg", "hi", listOf("jk", "l")))
        val history = listOf(DiscussExchange("12", "345", emptyList()))
        // 5 + 2 + 2 + 3 for the passage, 2 + 3 + 1 for the turns: 18.
        val at = limits.copy(maxTotalChars = 18)
        assertThat(discussDraft(passages, history, "6", true, at).tooLarge).isFalse()
        assertThat(discussDraft(passages, history, "67", true, at).tooLarge).isTrue()
        // Without the note the same question fits again.
        assertThat(discussDraft(passages, history, "67", false, at).tooLarge).isFalse()
    }

    @Test fun `the share note lists the cited passages, or all of them, with their source and a short excerpt`() {
        val passages = listOf(
            DiscussPassage(1, "First passage", null, "Dune", listOf("Frank Herbert")),
            DiscussPassage(2, "x".repeat(301), null, null, emptyList()),
        )
        val cited = discussShareNote(DiscussExchange("Q?", "A.", listOf(2)), passages, "Passages")
        assertThat(cited).isEqualTo("Q?\n\nA.\n\nPassages\n1. \"${"x".repeat(300)}…\"")
        val all = discussShareNote(DiscussExchange("Q?", "A.", emptyList()), passages, "Passages")
        assertThat(all.lines()).contains("1. \"First passage\" (Dune, Frank Herbert)")
        assertThat(all.lines().last()).startsWith("2. \"x")
    }

    @Test fun `the feature is offered only with the route, its limits, AI on, a provider and the opt-in`() {
        val config = AiConfig(configured = true, modelId = "m", highlightsDiscuss = true, highlightsDiscussLimits = limits)
        val optedIn = AiPreferences(aiEnabled = true)
        assertThat(discussLimits(config, optedIn)).isEqualTo(limits)
        assertThat(discussLimits(config, AiPreferences(aiEnabled = false))).isNull()
        assertThat(discussLimits(config, null)).isNull()
        assertThat(discussLimits(config.copy(highlightsDiscuss = false), optedIn)).isNull()
        assertThat(discussLimits(config.copy(highlightsDiscussLimits = null), optedIn)).isNull()
        assertThat(discussLimits(config.copy(configured = false), optedIn)).isNull()
        // The reader's provider is gone from the server.
        assertThat(discussLimits(config.copy(modelId = null), optedIn)).isNull()
    }

    @Test fun `an older server's config leaves the feature off`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AiConfig.serializer(), """{"configured":true,"model_id":"m"}""")
        assertThat(old.highlightsDiscuss).isFalse()
        assertThat(old.highlightsDiscussLimits).isNull()
    }

    @Test fun `the error detail is read from a string or from a limit object`() {
        assertThat(AiHttpException(409, """{"detail":"ai_not_opted_in"}""").detail).isEqualTo("ai_not_opted_in")
        assertThat(AiHttpException(422, """{"detail":{"code":"too_large","field":"request","limit":60000}}""").detail).isEqualTo("too_large")
        assertThat(AiHttpException(422, """{"detail":[{"type":"missing"}]}""").detail).isNull()
        assertThat(AiHttpException(502, "<html>").detail).isNull()
    }

    @Test fun `the client posts the conversation and reads the answer and the config limits`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val client = AiClient({ server.url("").toString() }, OkHttpClient())
            server.enqueue(MockResponse().setBody(
                """{"configured":true,"model_id":"m","highlights_discuss":true,"highlights_discuss_limits":{"max_highlights":20,"max_text_chars":2000,"max_note_chars":1000,"max_title_chars":500,"max_authors":10,"max_author_chars":200,"max_question_chars":1000,"max_answer_chars":4000,"max_questions":6,"max_total_chars":60000,"questions_per_minute":10}}""",
            ))
            assertThat(client.getConfig().highlightsDiscussLimits?.maxQuestions).isEqualTo(6)
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"answer":"Both are about control.","cited":[2,1]}"""))
            val request = discussDraft(
                listOf(DiscussPassage(1, "Fear", "Stoics", "Dune", listOf("Frank Herbert")), DiscussPassage(2, "Spice", null, "Dune", emptyList())),
                emptyList(), "What connects these?", true, limits.copy(maxTextChars = 2000, maxNoteChars = 1000),
            ).request
            val answer = client.discussHighlights(request)
            assertThat(answer).isEqualTo(DiscussAnswer("Both are about control.", listOf(2, 1)))
            val sent = server.takeRequest()
            assertThat(sent.method).isEqualTo("POST")
            assertThat(sent.path).isEqualTo("/ai/v1/highlights/discuss")
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(body.keys).containsExactly("highlights", "messages")
            val first = body["highlights"]!!.jsonArray[0].jsonObject
            assertThat(first["n"]!!.jsonPrimitive.content).isEqualTo("1")
            assertThat(first["note"]!!.jsonPrimitive.content).isEqualTo("Stoics")
            assertThat(body["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("reader")
        } finally {
            server.shutdown()
        }
    }
}
