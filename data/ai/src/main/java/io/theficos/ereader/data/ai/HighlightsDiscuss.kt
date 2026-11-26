package io.theficos.ereader.data.ai

import io.theficos.ereader.core.model.Highlight
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The limits `POST /ai/v1/highlights/discuss` enforces, as `GET /ai/v1/config` publishes them.
 * Each falls back to the server's own default, so a key a server renames or drops cannot fail
 * the whole config, and with it every AI feature.
 */
@Serializable
data class DiscussLimits(
    @SerialName("max_highlights") val maxHighlights: Int = 20,
    @SerialName("max_text_chars") val maxTextChars: Int = 2000,
    @SerialName("max_note_chars") val maxNoteChars: Int = 1000,
    @SerialName("max_title_chars") val maxTitleChars: Int = 500,
    @SerialName("max_authors") val maxAuthors: Int = 10,
    @SerialName("max_author_chars") val maxAuthorChars: Int = 200,
    @SerialName("max_question_chars") val maxQuestionChars: Int = 1000,
    @SerialName("max_questions") val maxQuestions: Int = 6,
    @SerialName("max_total_chars") val maxTotalChars: Int = 60_000,
)

/**
 * The limits when thinking through highlights is offered: the server answers the route, AI is
 * on there, and the reader has opted in with a provider the server still has. Null otherwise.
 */
fun discussLimits(config: AiConfig?, preferences: AiPreferences?): DiscussLimits? =
    config?.highlightsDiscussLimits?.takeIf {
        config.configured && config.highlightsDiscuss && config.modelId != null && preferences?.aiEnabled == true
    }

@Serializable
data class DiscussHighlight(
    val n: Int,
    val text: String,
    val note: String? = null,
    val title: String? = null,
    val authors: List<String> = emptyList(),
)

@Serializable
data class DiscussMessage(val role: String, val content: String)

@Serializable
data class DiscussRequest(val highlights: List<DiscussHighlight>, val messages: List<DiscussMessage>)

@Serializable
data class DiscussAnswer(val answer: String, val cited: List<Int> = emptyList())

/** A selected highlight as the conversation shows and sends it, numbered from 1 in the order shown. */
data class DiscussPassage(val n: Int, val text: String, val note: String?, val title: String?, val authors: List<String>)

data class DiscussExchange(val question: String, val answer: String, val cited: List<Int>)

fun discussPassages(highlights: List<Highlight>): List<DiscussPassage> = highlights.mapIndexed { index, h ->
    DiscussPassage(
        n = index + 1,
        text = h.text,
        note = h.comment?.trim()?.takeIf { it.isNotEmpty() },
        title = h.bookTitle.trim().takeIf { it.isNotEmpty() },
        authors = listOfNotNull(h.bookAuthor?.trim()?.takeIf { it.isNotEmpty() }),
    )
}

/** [clipped] when some passage text was shortened; [tooLarge] when the whole is still over the server's total. */
data class DiscussDraft(val request: DiscussRequest, val clipped: Boolean, val tooLarge: Boolean)

/**
 * The request for the next [question], within the server's limits. Long passages, notes, titles
 * and authors are clipped by code point, as the server counts them; questions and answers are
 * sent as they are, since the input and the server keep both within their own limits.
 */
fun discussDraft(
    passages: List<DiscussPassage>,
    exchanges: List<DiscussExchange>,
    question: String,
    includeNotes: Boolean,
    limits: DiscussLimits,
): DiscussDraft {
    var clipped = false
    val highlights = passages.map { p ->
        val text = p.text.clipCodePoints(limits.maxTextChars)
        if (text != p.text) clipped = true
        DiscussHighlight(
            n = p.n,
            text = text,
            note = p.note?.takeIf { includeNotes }?.clipCodePoints(limits.maxNoteChars),
            title = p.title?.clipCodePoints(limits.maxTitleChars),
            authors = p.authors.take(limits.maxAuthors).map { it.clipCodePoints(limits.maxAuthorChars) },
        )
    }
    val messages = exchanges.flatMap {
        listOf(DiscussMessage("reader", it.question), DiscussMessage("assistant", it.answer))
    } + DiscussMessage("reader", question.trim())
    val total = highlights.sumOf { h ->
        h.text.codePointLength() + (h.note?.codePointLength() ?: 0) + (h.title?.codePointLength() ?: 0) + h.authors.sumOf { it.codePointLength() }
    } + messages.sumOf { it.content.codePointLength() }
    return DiscussDraft(DiscussRequest(highlights, messages), clipped, total > limits.maxTotalChars)
}

/** The plain-text note that leaves Quire: question, answer, then the passages it used (all when it named none). */
fun discussShareNote(exchange: DiscussExchange, passages: List<DiscussPassage>, heading: String): String {
    val cited = if (exchange.cited.isEmpty()) passages else exchange.cited.mapNotNull { n -> passages.firstOrNull { it.n == n } }
    val lines = cited.mapIndexed { index, p ->
        val source = listOfNotNull(p.title, p.authors.joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(", ")
        "${index + 1}. \"${p.text.excerpt(SHARE_EXCERPT_CHARS)}\"" + if (source.isNotEmpty()) " ($source)" else ""
    }
    return (listOf(exchange.question, "", exchange.answer, "", heading) + lines).joinToString("\n")
}

/** [this] cut to at most [max] code points, with "…" when anything was cut. */
fun String.excerpt(max: Int): String = if (codePointLength() > max) clipCodePoints(max).trimEnd() + "…" else this

private fun String.codePointLength(): Int = codePointCount(0, length)

/** At most [max] code points, so an emoji or other surrogate pair is never cut in half. */
fun String.clipCodePoints(max: Int): String = if (codePointLength() > max) substring(0, offsetByCodePoints(0, max)) else this

private const val SHARE_EXCERPT_CHARS = 300
