package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * PR-ε / corrections.md REJECT (g): Android DTO catch-up from schema v2 → v3 → v4.
 *
 * Forward-compat invariant: payloads at every prior schema_version MUST
 * continue to deserialize cleanly because the server keeps stale rows in
 * `book_insights` at their original schema_version (we never run a payload
 * migration). v4 round-trip preserves all new fields.
 */
class BookInsightPayloadJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun deserializes_v2_payload_without_themes_or_v4_fields() {
        val raw = """{"intro":"i","confidence":"low","schema_version":2}"""
        val p = json.decodeFromString(BookInsightPayload.serializer(), raw)
        assertThat(p.schemaVersion).isEqualTo(2)
        assertThat(p.themes).isNull()
        assertThat(p.themeAnalysis).isNull()
        assertThat(p.craftNotes).isNull()
        assertThat(p.comparativeAnchors).isNull()
        assertThat(p.distinctiveTake).isNull()
        assertThat(p.discussionPrompts).isNull()
    }

    @Test
    fun deserializes_v3_payload_with_themes() {
        val raw = """{"intro":"i","themes":["mystery"],"confidence":"low","schema_version":3}"""
        val p = json.decodeFromString(BookInsightPayload.serializer(), raw)
        assertThat(p.schemaVersion).isEqualTo(3)
        assertThat(p.themes).containsExactly("mystery")
        assertThat(p.themeAnalysis).isNull()
    }

    @Test
    fun deserializes_v4_payload_with_all_new_fields() {
        val raw = """
            {"intro":"i","themes":["mystery"],
             "theme_analysis":{"mystery":"How it shows up"},
             "craft_notes":"Tight POV.",
             "comparative_anchors":[{"book":"X","author":"Y","similar_in":"Z"}],
             "distinctive_take":"Apart.",
             "discussion_prompts":["Q1?","Q2?"],
             "confidence":"medium","schema_version":4}
        """.trimIndent()
        val p = json.decodeFromString(BookInsightPayload.serializer(), raw)
        assertThat(p.schemaVersion).isEqualTo(4)
        assertThat(p.themes).containsExactly("mystery")
        assertThat(p.themeAnalysis).containsExactly("mystery", "How it shows up")
        assertThat(p.craftNotes).isEqualTo("Tight POV.")
        assertThat(p.comparativeAnchors).hasSize(1)
        assertThat(p.comparativeAnchors!![0].similarIn).isEqualTo("Z")
        assertThat(p.comparativeAnchors!![0].differentIn).isNull()
        assertThat(p.distinctiveTake).isEqualTo("Apart.")
        assertThat(p.discussionPrompts).containsExactly("Q1?", "Q2?")
    }

    @Test
    fun v4_round_trip_preserves_all_fields() {
        val original = BookInsightPayload(
            intro = "i",
            themes = listOf("mystery"),
            themeAnalysis = mapOf("mystery" to "details"),
            craftNotes = "craft",
            comparativeAnchors = listOf(ComparativeAnchor("B", "A", "sim", "diff")),
            distinctiveTake = "take",
            discussionPrompts = listOf("q1", "q2"),
            schemaVersion = 4,
        )
        val encoded = json.encodeToString(BookInsightPayload.serializer(), original)
        val decoded = json.decodeFromString(BookInsightPayload.serializer(), encoded)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun default_schema_version_is_4() {
        assertThat(BookInsightPayload().schemaVersion).isEqualTo(4)
    }

    @Test
    fun comparative_anchor_different_in_optional() {
        val raw = """{"book":"B","author":"A","similar_in":"S"}"""
        val a = json.decodeFromString(ComparativeAnchor.serializer(), raw)
        assertThat(a.differentIn).isNull()
    }
}
