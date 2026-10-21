package io.theficos.ereader.ui.bookdetail

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Progress
import org.junit.Test
import java.util.Locale

class BookFactsTest {

    private val document = Document(
        id = 1L,
        identity = DocumentIdentity(metadataId = "m1", contentHash = "h1"),
        title = "Foundation",
        author = "Isaac Asimov",
        downloadUrl = "https://example.org/book.epub",
        localPath = "/books/book.epub",
        coverPath = null,
        downloadedAt = 100L,
    )

    private fun progress(
        percent: Double = 0.429,
        updatedAt: Long = 200L,
        finishedAt: Long? = null,
        abandonedAt: Long? = null,
    ) = Progress(
        documentId = document.id,
        locator = "",
        percent = percent,
        updatedAt = updatedAt,
        finishedAt = finishedAt,
        abandonedAt = abandonedAt,
    )

    @Test
    fun `publication year hides calibre placeholders and malformed dates`() {
        assertThat(publishedYear("0101-01-01")).isNull()
        assertThat(publishedYear("0999-12-31")).isNull()
        assertThat(publishedYear("unknown")).isNull()
        assertThat(publishedYear("year 1951")).isNull()
        assertThat(publishedYear("951")).isNull()
        assertThat(publishedYear("")).isNull()
        assertThat(publishedYear(null)).isNull()
    }

    @Test
    fun `publication year takes the leading four digits`() {
        assertThat(publishedYear("1951-06-01")).isEqualTo("1951")
        assertThat(publishedYear("1951")).isEqualTo("1951")
        assertThat(publishedYear("1000-01-01")).isEqualTo("1000")
    }

    @Test
    fun `language accepts two and three letter ISO codes`() {
        assertThat(languageName("en", Locale.ENGLISH)).isEqualTo("English")
        assertThat(languageName("eng", Locale.ENGLISH)).isEqualTo("English")
        assertThat(languageName("eng-US", Locale.ENGLISH)).isEqualTo("English")
        assertThat(languageName("fra", Locale.ENGLISH)).isEqualTo("French")
        assertThat(languageName("fr", Locale.FRENCH)).isEqualTo("Français")
    }

    @Test
    fun `language hides undefined codes and falls back for unrecognised codes`() {
        assertThat(languageName("und", Locale.ENGLISH)).isNull()
        assertThat(languageName(" UND ", Locale.ENGLISH)).isNull()
        assertThat(languageName("  ", Locale.ENGLISH)).isNull()
        assertThat(languageName(null, Locale.ENGLISH)).isNull()
        assertThat(languageName("zzz", Locale.ENGLISH)).isEqualTo("zzz")
    }

    @Test
    fun `series formats whole and fractional positions without trailing zeros`() {
        assertThat(seriesLine("Foundation", 3.0)).isEqualTo("Foundation, book 3")
        assertThat(seriesLine("Foundation", 1.5)).isEqualTo("Foundation, book 1.5")
        assertThat(seriesLine(" Foundation ", null)).isEqualTo("Foundation")
        assertThat(seriesLine(null, 3.0)).isNull()
        assertThat(seriesLine("  ", 3.0)).isNull()
    }

    @Test
    fun `subjects keep first spelling and order while ignoring case and blanks`() {
        assertThat(subjectsLine(listOf(" Science Fiction ", "science fiction", "", "Space", "SPACE", " ")))
            .isEqualTo("Science Fiction, Space")
        assertThat(subjectsLine(emptyList())).isNull()
        assertThat(subjectsLine(listOf("", "  "))).isNull()
    }

    @Test
    fun `reading status is not started without meaningful progress`() {
        assertThat(readingStatus(null) { "date-$it" }).isEqualTo("Not started")
        assertThat(readingStatus(progress(percent = 0.0)) { "date-$it" }).isEqualTo("Not started")
        assertThat(readingStatus(progress(percent = 0.0000001)) { "date-$it" }).isEqualTo("Not started")
    }

    @Test
    fun `reading status rounds down the percentage`() {
        assertThat(readingStatus(progress()) { "date-$it" }).isEqualTo("42% read")
        assertThat(readingStatus(progress(percent = 0.999)) { "date-$it" }).isEqualTo("99% read")
    }

    @Test
    fun `reading status formats finished and abandoned dates before the percentage`() {
        assertThat(readingStatus(progress(percent = 1.0, finishedAt = 300L)) { "date-$it" })
            .isEqualTo("Finished on date-300")
        assertThat(readingStatus(progress(percent = 0.0, abandonedAt = 400L)) { "date-$it" })
            .isEqualTo("Abandoned on date-400")
    }

    @Test
    fun `book facts keep their order and use the supplied formatters`() {
        val facts = bookFacts(
            metadata = MetadataBundle(
                title = "Foundation",
                publisher = "Gnome Press",
                publishDate = "1951-06-01",
                language = "eng",
                subjects = listOf("Fiction", "Space", "fiction"),
                isbn = "1234567890",
            ),
            document = document,
            progress = progress(),
            fileSizeBytes = 1024L,
            formatDate = { "date-$it" },
            formatRelative = { "relative-$it" },
            formatSize = { "size-$it" },
        )

        assertThat(facts.map { it.label }).containsExactly(
            "Publisher", "Published", "Language", "Subjects", "ISBN", "Added", "Last read", "File size",
        ).inOrder()
        assertThat(facts.first().value).isEqualTo("Gnome Press")
        assertThat(facts[1].value).isEqualTo("1951")
        assertThat(facts[3].value).isEqualTo("Fiction, Space")
        assertThat(facts.takeLast(3).map { it.value })
            .containsExactly("date-100", "relative-200", "size-1024").inOrder()
    }

    @Test
    fun `book facts omit empty metadata missing progress and nonpositive file size`() {
        val metadata = MetadataBundle(
            title = "Foundation",
            publisher = "  ",
            publishDate = "0101-01-01",
            language = "und",
            subjects = listOf(" "),
            isbn = "",
        )
        for (size in listOf(null, 0L, -1L)) {
            val facts = bookFacts(
                metadata, document, null, size,
                formatDate = { "date-$it" },
                formatRelative = { error("No progress to format") },
                formatSize = { error("No size to format") },
            )

            assertThat(facts).containsExactly(BookFact("Added", "date-100"))
        }
    }

    @Test
    fun `book facts omit last read when its timestamp is not positive`() {
        val facts = bookFacts(
            null, document, progress(updatedAt = 0L), null,
            formatDate = { "date-$it" },
            formatRelative = { error("No last read timestamp to format") },
            formatSize = { error("No size to format") },
        )

        assertThat(facts).containsExactly(BookFact("Added", "date-100"))
    }

    @Test
    fun `book facts omit last read for a book not started yet`() {
        val facts = bookFacts(
            null, document, progress(percent = 0.0), null,
            formatDate = { "date-$it" },
            formatRelative = { error("A book not started has no last read") },
            formatSize = { error("No size to format") },
        )

        assertThat(facts).containsExactly(BookFact("Added", "date-100"))
    }
}
