package io.theficos.ereader.ui.catalogdetail

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.AiStyle
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.ui.bookdetail.InsightUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailViewModelTest {

    private val publication = OpdsPublication(
        title = "The Long Earth",
        author = "Pratchett & Baxter",
        epubDownloadHref = "https://calibre.example/opds/download/42/epub",
        coverUrl = null,
        webUrl = "https://calibre.example/book/42",
        opdsDcId = "urn:uuid:abc-123",
        calibreBookId = "42",
    )

    private val response = BookInsightResponse(
        payload = BookInsightPayload(intro = "About this book.", schemaVersion = 3),
        sources = listOf(
            Citation(kind = "wikipedia", title = "Wikipedia", url = "https://en.wikipedia.org/wiki/X"),
        ),
        modelId = "gpt-4o-mini",
        promptVersion = "4",
        generatedAt = "2026-05-17T00:00:00Z",
    )

    private lateinit var fakeAi: FakeAi

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeAi = FakeAi()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- buildIdentity contract -------------------------------------------------

    @Test
    fun `buildIdentity sets metadataId and opdsHref to the same opds-href sha`() {
        val ident = CatalogDetailViewModel.buildIdentity(publication)
        assertThat(ident.metadataId).isNotNull()
        assertThat(ident.metadataId!!).startsWith("opds-href:")
        // sha256 hex length = 64; plus prefix length
        assertThat(ident.metadataId!!.length).isEqualTo("opds-href:".length + 64)
        // opds_href is identical — same value, different scheme for symmetry.
        assertThat(ident.opdsHref).isEqualTo(ident.metadataId)
        // content_hash stays null pre-download.
        assertThat(ident.contentHash).isNull()
    }

    @Test
    fun `buildIdentity includes opdsDcId when present`() {
        val ident = CatalogDetailViewModel.buildIdentity(publication)
        assertThat(ident.opdsDcId).isEqualTo("urn:uuid:abc-123")
    }

    @Test
    fun `buildIdentity omits opdsDcId when null`() {
        val ident = CatalogDetailViewModel.buildIdentity(publication.copy(opdsDcId = null))
        assertThat(ident.opdsDcId).isNull()
    }

    @Test
    fun `buildIdentity includes calibreBookId when present`() {
        val ident = CatalogDetailViewModel.buildIdentity(publication)
        assertThat(ident.calibreBookId).isEqualTo("42")
    }

    @Test
    fun `buildIdentity omits calibreBookId when null`() {
        val ident = CatalogDetailViewModel.buildIdentity(publication.copy(calibreBookId = null))
        assertThat(ident.calibreBookId).isNull()
    }

    @Test
    fun `buildIdentity metadataId is stable across calls for same href`() {
        val a = CatalogDetailViewModel.buildIdentity(publication)
        val b = CatalogDetailViewModel.buildIdentity(publication)
        assertThat(a.metadataId).isEqualTo(b.metadataId)
    }

    @Test
    fun `buildIdentity metadataId differs across different hrefs`() {
        val a = CatalogDetailViewModel.buildIdentity(publication)
        val b = CatalogDetailViewModel.buildIdentity(
            publication.copy(epubDownloadHref = "https://other.example/book.epub"),
        )
        assertThat(a.metadataId).isNotEqualTo(b.metadataId)
    }

    // -- state machine ---------------------------------------------------------

    @Test
    fun `state is Hidden when AI is disabled`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = false)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = false)

        val vm = CatalogDetailViewModel(publication, fakeAi)

        vm.state.test {
            var s = awaitItem()
            while (s.insight is InsightUiState.Loading) s = awaitItem()
            assertThat(s.insight).isEqualTo(InsightUiState.Hidden)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(fakeAi.lookupCallCount).isEqualTo(0)
    }

    @Test
    fun `state is Loaded on cache hit and lookup is not called`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = response

        val vm = CatalogDetailViewModel(publication, fakeAi)

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Loaded) s = awaitItem()
            assertThat((s.insight as InsightUiState.Loaded).payload.intro)
                .isEqualTo("About this book.")
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(fakeAi.lookupCallCount).isEqualTo(0)
    }

    @Test
    fun `state goes Loading then Loaded on cache miss and identity contains canonical plus aliases`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = null
        fakeAi.lookupResult = response

        val vm = CatalogDetailViewModel(publication, fakeAi)

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Loaded) s = awaitItem()
            // Loading may or may not be observed depending on dispatcher coalescing;
            // the important assertions are the lookup body shape and final Loaded state.
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(fakeAi.lookupCallCount).isEqualTo(1)

        val sent = fakeAi.lastLookupIdentity!!
        assertThat(sent.metadataId).startsWith("opds-href:")
        assertThat(sent.opdsHref).isEqualTo(sent.metadataId)
        assertThat(sent.opdsDcId).isEqualTo("urn:uuid:abc-123")
        assertThat(sent.calibreBookId).isEqualTo("42")
        assertThat(sent.contentHash).isNull()

        val bundle = fakeAi.lastLookupBundle!!
        assertThat(bundle.title).isEqualTo("The Long Earth")
        assertThat(bundle.author).isEqualTo("Pratchett & Baxter")
    }

    @Test
    fun `state is Error on lookup failure`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = null
        fakeAi.lookupError = RuntimeException("boom")

        val vm = CatalogDetailViewModel(publication, fakeAi)

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Error) s = awaitItem()
            assertThat((s.insight as InsightUiState.Error).message).isNotEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -- PR-ζ: stash writes ---------------------------------------------------

    @Test
    fun `stash captures catalog identity and style on successful cache hit`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(
            aiEnabled = true,
            style = AiStyle(tone = "scholarly", language = "fr"),
        )
        fakeAi.cached = response
        val stash = io.theficos.ereader.data.ai.CatalogInsightStash()

        val vm = CatalogDetailViewModel(
            publication = publication,
            ai = fakeAi,
            insightStash = stash,
            subjectProvider = { "alice" },
        )

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Loaded) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        val entry = stash.peek("alice", publication.epubDownloadHref)
        assertThat(entry).isNotNull()
        assertThat(entry!!.tone).isEqualTo("scholarly")
        assertThat(entry.language).isEqualTo("fr")
        assertThat(entry.catalogIdentity.metadataId).startsWith("opds-href:")
    }

    @Test
    fun `stash captures style after a cache-miss lookup success`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = null
        fakeAi.lookupResult = response
        val stash = io.theficos.ereader.data.ai.CatalogInsightStash()

        val vm = CatalogDetailViewModel(
            publication = publication,
            ai = fakeAi,
            insightStash = stash,
            subjectProvider = { "alice" },
        )

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Loaded) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        val entry = stash.peek("alice", publication.epubDownloadHref)
        assertThat(entry).isNotNull()
        // Defaults from AiStyle().
        assertThat(entry!!.tone).isEqualTo("neutral")
        assertThat(entry.language).isEqualTo("auto")
    }

    @Test
    fun `stash write is skipped when subjectProvider returns null`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = response
        val stash = io.theficos.ereader.data.ai.CatalogInsightStash()

        val vm = CatalogDetailViewModel(
            publication = publication,
            ai = fakeAi,
            insightStash = stash,
            subjectProvider = { null },
        )

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Loaded) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(stash.peek("alice", publication.epubDownloadHref)).isNull()
    }

    @Test
    fun `state Error on AiHttpException maps to friendly message`() = runTest {
        fakeAi.configFlow.value = AiConfig(configured = true)
        fakeAi.prefsFlow.value = AiPreferences(aiEnabled = true, style = AiStyle())
        fakeAi.cached = null
        fakeAi.lookupError = AiHttpException(code = 502, body = "bad gateway")

        val vm = CatalogDetailViewModel(publication, fakeAi)

        vm.state.test {
            var s = awaitItem()
            while (s.insight !is InsightUiState.Error) s = awaitItem()
            assertThat((s.insight as InsightUiState.Error).message).contains("502")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeAi : CatalogAiPort {
        override val configFlow = MutableStateFlow<AiConfig?>(null)
        override val prefsFlow = MutableStateFlow<AiPreferences?>(null)
        var cached: BookInsightResponse? = null
        var lookupResult: BookInsightResponse? = null
        var lookupError: Throwable? = null
        var lookupCallCount = 0
        var lastLookupIdentity: DocumentIdentity? = null
        var lastLookupBundle: MetadataBundle? = null

        override suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse? =
            cached

        override suspend fun lookupInsight(
            identity: DocumentIdentity,
            bundle: MetadataBundle,
        ): BookInsightResponse {
            lookupCallCount++
            lastLookupIdentity = identity
            lastLookupBundle = bundle
            lookupError?.let { throw it }
            return lookupResult ?: error("no result configured")
        }
    }
}
