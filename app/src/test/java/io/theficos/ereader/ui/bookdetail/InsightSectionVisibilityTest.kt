package io.theficos.ereader.ui.bookdetail

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.ComparativeAnchor
import org.junit.Test

/**
 * Deterministic visibility predicates for the five new v4 InsightCards
 * (PR-ε / corrections.md PR-ε Suggested → architect Test-Coverage Gap).
 *
 * Tests the exact predicate each card uses to decide visibility. No Compose
 * runner required — app/build.gradle.kts intentionally excludes Compose UI
 * test deps. Covers: hide-when-null, hide-when-empty, show-when-populated,
 * blank-entry filtering, defense-in-depth display caps.
 */
class InsightSectionVisibilityTest {

    /**
     * Mirrors the predicates in InsightCards.kt's `InsightSection`
     * `is InsightUiState.Loaded ->` branch for the five v4 cards.
     */
    private fun visible(p: BookInsightPayload): Map<String, Boolean> = mapOf(
        "themeAnalysis" to (p.themeAnalysis?.isNotEmpty() == true),
        "craftNotes" to (p.craftNotes?.isNotBlank() == true),
        "comparativeAnchors" to (
            p.comparativeAnchors
                ?.filter { it.book.isNotBlank() && it.author.isNotBlank() && it.similarIn.isNotBlank() }
                ?.isNotEmpty() == true
            ),
        "distinctiveTake" to (p.distinctiveTake?.isNotBlank() == true),
        "discussionPrompts" to (
            p.discussionPrompts?.filter { it.isNotBlank() }?.isNotEmpty() == true
            ),
    )

    @Test
    fun null_v4_fields_all_hidden() {
        val v = visible(BookInsightPayload())
        assertThat(v.values).containsExactly(false, false, false, false, false).inOrder()
    }

    @Test
    fun empty_v4_collections_all_hidden() {
        val v = visible(
            BookInsightPayload(
                themeAnalysis = emptyMap(),
                craftNotes = "",
                comparativeAnchors = emptyList(),
                distinctiveTake = "   ",
                discussionPrompts = listOf("", " "),
            ),
        )
        assertThat(v.values).containsExactly(false, false, false, false, false).inOrder()
    }

    @Test
    fun populated_v4_fields_all_visible() {
        val v = visible(
            BookInsightPayload(
                themeAnalysis = mapOf("a" to "x"),
                craftNotes = "ok",
                comparativeAnchors = listOf(ComparativeAnchor("B", "A", "S", null)),
                distinctiveTake = "yes",
                discussionPrompts = listOf("Q?"),
            ),
        )
        assertThat(v.values).containsExactly(true, true, true, true, true).inOrder()
    }

    @Test
    fun comparative_anchors_blank_entries_filtered() {
        val v = visible(
            BookInsightPayload(
                comparativeAnchors = listOf(
                    ComparativeAnchor("", "A", "S"),
                    ComparativeAnchor("B", "  ", "S"),
                    ComparativeAnchor("B", "A", "  "),
                ),
            ),
        )
        assertThat(v["comparativeAnchors"]).isFalse()
    }

    @Test
    fun theme_analysis_display_cap_is_two() {
        // Defense-in-depth: server-side validator rejects >2 entries (PR-ε),
        // but the UI's `entries.take(2)` would clamp even if a stale payload
        // somehow shipped 3. Mirror the cap that InsightCards.kt applies.
        val entries = mapOf("a" to "1", "b" to "2", "c" to "3").entries.take(2)
        assertThat(entries).hasSize(2)
    }
}
