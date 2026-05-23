package io.theficos.ereader.data.library

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class LibraryDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses full response`() {
        val body = """
            {
              "total_books": 5,
              "finished_count": 2,
              "in_progress_count": 1,
              "abandoned_count": 1,
              "top_authors": [{"name":"Asimov","count":3}],
              "top_themes": [{"theme":"noir","count":2,"note":"v3+ insights only"}],
              "themes_caveat": "Theme stats include books with AI theme data; older cached insights may be missing until regenerated."
            }
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.totalBooks).isEqualTo(5)
        assertThat(parsed.finishedCount).isEqualTo(2)
        assertThat(parsed.inProgressCount).isEqualTo(1)
        assertThat(parsed.abandonedCount).isEqualTo(1)
        assertThat(parsed.topAuthors).containsExactly(TopAuthor("Asimov", 3))
        assertThat(parsed.topThemes).containsExactly(TopTheme("noir", 2, "v3+ insights only"))
        assertThat(parsed.themesCaveat).contains("may be missing")
    }

    @Test
    fun `parses empty lists`() {
        val body = """
            {"total_books":0,"finished_count":0,"in_progress_count":0,"abandoned_count":0,"top_authors":[],"top_themes":[],"themes_caveat":"x"}
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.topAuthors).isEmpty()
        assertThat(parsed.topThemes).isEmpty()
    }

    @Test
    fun `parses payload without abandoned_count field (back-compat)`() {
        // Pre-PR-9 server payload — the Kotlin default `= 0` kicks in so
        // a newer app talking to an older server doesn't crash.
        val body = """
            {"total_books":3,"finished_count":1,"in_progress_count":2,"top_authors":[],"top_themes":[],"themes_caveat":""}
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.abandonedCount).isEqualTo(0)
        assertThat(parsed.totalBooks).isEqualTo(3)
    }

    @Test
    fun `tolerates unknown additive fields (forward-compat)`() {
        // `ignoreUnknownKeys = true` lets the server add fields freely
        // without bumping the API version.
        val body = """
            {"total_books":3,"finished_count":1,"in_progress_count":2,"abandoned_count":0,"top_authors":[],"top_themes":[],"themes_caveat":"","future_field":"ignored","another":42}
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.totalBooks).isEqualTo(3)
        assertThat(parsed.abandonedCount).isEqualTo(0)
    }

    // ---------- Phase-0 / F-2: identity_hash_version ----------

    @Test
    fun `LibraryItemRequest carries identity_hash_version on the wire`() {
        // Round-trip with encodeDefaults=true so we can see what would
        // travel over the wire if an AI-style client were used (the real
        // LibraryClient uses default encodeDefaults=false, so the field
        // is omitted on encode there — that's fine, the server applies
        // its own DEFAULT 1).
        val encJson = Json { encodeDefaults = true }
        val req = LibraryItemRequest(
            contentHash = "abc",
            title = "T",
            identityHashVersion = 2,
        )
        val encoded = encJson.encodeToString(LibraryItemRequest.serializer(), req)
        assertThat(encoded).contains("\"identity_hash_version\":2")
    }

    @Test
    fun `LibraryItemResponse decodes default 1 when server omits identity_hash_version`() {
        // Older server (pre-F-1) doesn't emit the field. Every existing
        // hash on the wire is v1, so the default `= 1` is correct.
        val body = """
            {
              "content_hash":"h1","title":"T","authors":[],"created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"
            }
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryItemResponse.serializer(), body)
        assertThat(parsed.identityHashVersion).isEqualTo(1)
    }

    @Test
    fun `LibraryItemResponse decodes non-default identity_hash_version`() {
        val body = """
            {
              "content_hash":"h1","title":"T","authors":[],"identity_hash_version":3,
              "created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"
            }
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryItemResponse.serializer(), body)
        assertThat(parsed.identityHashVersion).isEqualTo(3)
    }
}
