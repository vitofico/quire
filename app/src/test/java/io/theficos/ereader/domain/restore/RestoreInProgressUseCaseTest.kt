package io.theficos.ereader.domain.restore

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.LibraryItemResponse
import io.theficos.ereader.data.sync.DocumentIdDto
import io.theficos.ereader.data.sync.ProgressItemDto
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RestoreInProgressUseCaseTest {

    private fun item(hash: String, href: String?) = LibraryItemResponse(
        contentHash = hash, title = "T-$hash", authors = listOf("A"),
        opdsHref = href, createdAt = "1970-01-01T00:00:00+00:00",
        updatedAt = "1970-01-01T00:00:00+00:00",
    )

    private fun progress(
        hash: String, percent: Double = 0.5,
        finishedAt: String? = null, abandonedAt: String? = null,
    ) = ProgressItemDto(
        document = DocumentIdDto(metadataId = "m-$hash", contentHash = hash),
        locator = "loc-$hash", percent = percent,
        clientUpdatedAt = "1970-01-01T00:00:00.500Z",
        finishedAt = finishedAt, abandonedAt = abandonedAt,
    )

    private fun useCase(
        mirror: List<LibraryItemResponse>,
        prog: List<ProgressItemDto>,
        present: Set<String> = emptySet(),
        onDownload: suspend (RestoreCandidate) -> Unit = {},
        applied: MutableList<ProgressItemDto>? = null,
    ) = RestoreInProgressUseCase(
        fetchLibraryItems = { mirror },
        fetchInProgress = { prog },
        isPresent = { id -> id.contentHash in present },
        downloadAndInsert = onDownload,
        applyPositions = { items -> applied?.addAll(items) },
    )

    @Test fun `downloads in-progress books joined to the mirror`() = runTest {
        val downloaded = mutableListOf<String>()
        val summary = useCase(
            mirror = listOf(item("h1", "https://s/1.epub")),
            prog = listOf(progress("h1")),
            onDownload = { downloaded += it.opdsHref },
        ).run()
        assertThat(downloaded).containsExactly("https://s/1.epub")
        assertThat(summary.downloaded).isEqualTo(1)
        assertThat(summary.requested).isEqualTo(1)
    }

    @Test fun `excludes finished, abandoned and zero-percent rows`() = runTest {
        val downloaded = mutableListOf<String>()
        val summary = useCase(
            mirror = listOf(item("h1", "https://s/1.epub"), item("h2", "https://s/2.epub"), item("h3", "https://s/3.epub")),
            prog = listOf(
                progress("h1", finishedAt = "1970-01-01T00:00:00.500Z"),
                progress("h2", abandonedAt = "1970-01-01T00:00:00.500Z"),
                progress("h3", percent = 0.0),
            ),
            onDownload = { downloaded += it.opdsHref },
        ).run()
        assertThat(downloaded).isEmpty()
        assertThat(summary.requested).isEqualTo(0)
    }

    @Test fun `skips already-present and unfetchable books`() = runTest {
        val downloaded = mutableListOf<String>()
        val summary = useCase(
            mirror = listOf(item("h1", "https://s/1.epub"), item("h2", "sideload://v1/h2"), item("h3", "https://s/3.epub")),
            prog = listOf(progress("h1"), progress("h2"), progress("h3")),
            present = setOf("h1"),
            onDownload = { downloaded += it.opdsHref },
        ).run()
        assertThat(downloaded).containsExactly("https://s/3.epub")
        assertThat(summary.skippedExisting).isEqualTo(1)
        assertThat(summary.skippedUnfetchable).isEqualTo(1)
        assertThat(summary.downloaded).isEqualTo(1)
    }

    @Test fun `a per-book failure is counted and does not abort the batch`() = runTest {
        val downloaded = mutableListOf<String>()
        val summary = useCase(
            mirror = listOf(item("h1", "https://s/1.epub"), item("h2", "https://s/2.epub")),
            prog = listOf(progress("h1"), progress("h2")),
            onDownload = { c -> if (c.progress.document.contentHash == "h1") error("boom") else downloaded += c.opdsHref },
        ).run()
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.downloaded).isEqualTo(1)
        assertThat(downloaded).containsExactly("https://s/2.epub")
    }

    @Test fun `applies positions for all in-progress items`() = runTest {
        val applied = mutableListOf<ProgressItemDto>()
        useCase(
            mirror = listOf(item("h1", "https://s/1.epub")),
            prog = listOf(progress("h1")),
            present = setOf("h1"),
            applied = applied,
        ).run()
        assertThat(applied.map { it.document.contentHash }).containsExactly("h1")
    }
}
