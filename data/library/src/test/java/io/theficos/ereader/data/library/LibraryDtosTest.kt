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
              "top_authors": [{"name":"Asimov","count":3}],
              "top_themes": [{"theme":"noir","count":2,"note":"v3+ insights only"}],
              "themes_caveat": "Theme stats include books with AI theme data; older cached insights may be missing until regenerated."
            }
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.totalBooks).isEqualTo(5)
        assertThat(parsed.finishedCount).isEqualTo(2)
        assertThat(parsed.inProgressCount).isEqualTo(1)
        assertThat(parsed.topAuthors).containsExactly(TopAuthor("Asimov", 3))
        assertThat(parsed.topThemes).containsExactly(TopTheme("noir", 2, "v3+ insights only"))
        assertThat(parsed.themesCaveat).contains("may be missing")
    }

    @Test
    fun `parses empty lists`() {
        val body = """
            {"total_books":0,"finished_count":0,"in_progress_count":0,"top_authors":[],"top_themes":[],"themes_caveat":"x"}
        """.trimIndent()
        val parsed = json.decodeFromString(LibraryStatsResponse.serializer(), body)
        assertThat(parsed.topAuthors).isEmpty()
        assertThat(parsed.topThemes).isEmpty()
    }
}
