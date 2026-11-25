package io.theficos.ereader.ui.bookdetail

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.BookDetails
import io.theficos.ereader.data.ai.DetailsBook
import io.theficos.ereader.data.ai.DetailsCover
import io.theficos.ereader.data.ai.DetailsResponse
import io.theficos.quire.R
import org.junit.Test

class FoundDetailsTest {
    private val doc = Document(1, DocumentIdentity(contentHash = "h"), "dune_frank_herbert", null, "u", "/p", null, 0)

    private fun found(
        origins: Map<String, String>,
        book: DetailsBook = DetailsBook(title = "Dune"),
        cover: DetailsCover = DetailsCover(),
        coverPath: String? = null,
    ) = BookDetails(DetailsResponse(key = "k", book = book, origins = origins, cover = cover, tidy = "done"), coverPath, useOriginal = false)

    @Test fun `each origin gets its own label, and an unknown one counts as made by AI`() {
        assertThat(originNote("file")).isNull()
        assertThat(originNote("catalog")).isNull()
        assertThat(originNote(null)).isNull()
        assertThat(originNote("openlibrary")).isEqualTo(R.string.details_from_open_library)
        assertThat(originNote("ai")).isEqualTo(R.string.details_tidied_by_ai)
        assertThat(originNote("ai_written")).isEqualTo(R.string.details_written_by_ai)
        assertThat(originNote("something_new")).isEqualTo(R.string.details_tidied_by_ai)
    }

    @Test fun `the header says AI tidied it over Open Library, and names a found cover`() {
        val notes = headerNotes(
            doc,
            found(
                mapOf("title" to "ai", "authors" to "openlibrary"),
                cover = DetailsCover(source = "generated", url = "/details/v1/covers/k?v=1", generated = true),
                coverPath = "/covers/k.cover",
            ),
        )
        assertThat(notes).containsExactly(R.string.details_tidied_by_ai, R.string.details_generated_cover).inOrder()

        assertThat(headerNotes(doc.copy(coverPath = "/own.jpg"), found(mapOf("authors" to "openlibrary"), coverPath = "/covers/k.cover")))
            .containsExactly(R.string.details_from_open_library)
        assertThat(headerNotes(doc, found(mapOf("title" to "file")))).isEmpty()
    }

    @Test fun `found values fill the facts, and a description passed back from the book stays the book's own`() {
        val own = MetadataBundle(title = "dune_frank_herbert", description = "<p>Own blurb</p>", publisher = "Chilton")
        val book = DetailsBook(title = "Dune", description = "Own blurb", year = 1965, subjects = listOf("Science fiction"))

        val passedBack = foundMetadata(own, doc, found(mapOf("description" to "file", "year" to "openlibrary"), book))
        assertThat(passedBack.description).isEqualTo("<p>Own blurb</p>")
        assertThat(passedBack.publishDate).isEqualTo("1965")
        assertThat(passedBack.publisher).isEqualTo("Chilton")
        assertThat(passedBack.subjects).containsExactly("Science fiction")

        val written = foundMetadata(null, doc, found(mapOf("description" to "ai_written"), book.copy(description = "A desert planet.")))
        assertThat(written.description).isEqualTo("A desert planet.")
    }

    @Test fun `a found fact carries where it came from`() {
        val facts = bookFacts(
            metadata = MetadataBundle(title = "Dune", publisher = "Chilton", publishDate = "1965"),
            document = doc,
            progress = null,
            fileSizeBytes = null,
            formatDate = { "date" },
            formatRelative = { "relative" },
            formatSize = { "size" },
            origins = mapOf("publisher" to "file", "year" to "openlibrary"),
        )
        assertThat(facts.first { it.label == R.string.book_publisher }.note).isNull()
        assertThat(facts.first { it.label == R.string.book_published }.note).isEqualTo(R.string.details_from_open_library)
    }
}
