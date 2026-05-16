package io.theficos.ereader.ui.bookdetail

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiStyle
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.Citation
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
class InsightAuditViewModelTest {

    private val identity = DocumentIdentity(metadataId = "m1", contentHash = "h1")

    private val sampleResponse = BookInsightResponse(
        payload = BookInsightPayload(intro = "About this book.", schemaVersion = 2),
        sources = listOf(
            Citation(kind = "wikipedia", title = "Wikipedia", url = "https://en.wikipedia.org/wiki/X"),
        ),
        modelId = "gpt-4o-mini-2024-07-18",
        promptVersion = "3",
        generatedAt = "2026-05-17T13:42:11Z",
    )

    private lateinit var source: FakeSource

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeSource(identity = identity)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads cached insight into Loaded state`() = runTest {
        source.cached = sampleResponse
        source.styleFlow.value = AiStyle(tone = "scholarly", language = "it")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)

        vm.state.test {
            // initial Loading may or may not be observed depending on dispatcher;
            // skip until we reach a terminal state.
            var s: InsightAuditViewModel.State = awaitItem()
            while (s is InsightAuditViewModel.State.Loading) s = awaitItem()
            assertThat(s).isInstanceOf(InsightAuditViewModel.State.Loaded::class.java)
            val loaded = s as InsightAuditViewModel.State.Loaded
            assertThat(loaded.identity).isEqualTo(identity)
            assertThat(loaded.response.modelId).isEqualTo("gpt-4o-mini-2024-07-18")
            assertThat(loaded.response.promptVersion).isEqualTo("3")
            assertThat(loaded.response.payload.schemaVersion).isEqualTo(2)
            assertThat(loaded.currentStyle?.tone).isEqualTo("scholarly")
            assertThat(loaded.currentStyle?.language).isEqualTo("it")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces NotCached when source returns null`() = runTest {
        source.cached = null

        val vm = InsightAuditViewModel(documentId = 1L, source = source)

        vm.state.test {
            var s: InsightAuditViewModel.State = awaitItem()
            while (s is InsightAuditViewModel.State.Loading) s = awaitItem()
            assertThat(s).isEqualTo(InsightAuditViewModel.State.NotCached)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces Error on network failure during load`() = runTest {
        source.loadError = AiHttpException(code = 503, body = "boom")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)

        vm.state.test {
            var s: InsightAuditViewModel.State = awaitItem()
            while (s is InsightAuditViewModel.State.Loading) s = awaitItem()
            assertThat(s).isInstanceOf(InsightAuditViewModel.State.Error::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces Error when documentId unknown`() = runTest {
        source.unknownDocumentId = true

        val vm = InsightAuditViewModel(documentId = 999L, source = source)

        vm.state.test {
            var s: InsightAuditViewModel.State = awaitItem()
            while (s is InsightAuditViewModel.State.Loading) s = awaitItem()
            assertThat(s).isInstanceOf(InsightAuditViewModel.State.Error::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalidate success transitions to Done and emits Invalidated event`() = runTest {
        source.cached = sampleResponse

        val vm = InsightAuditViewModel(documentId = 1L, source = source)
        vm.awaitLoaded()

        vm.events.test {
            vm.invalidate()
            val event = awaitItem()
            assertThat(event).isEqualTo(InsightAuditViewModel.Event.Invalidated)
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(vm.state.value).isEqualTo(InsightAuditViewModel.State.Done)
        assertThat(source.invalidateCalls).containsExactly(identity)
    }

    @Test
    fun `invalidate treats 404 as success (already evicted)`() = runTest {
        source.cached = sampleResponse
        source.invalidateError = AiHttpException(code = 404, body = "")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)
        vm.awaitLoaded()

        vm.events.test {
            vm.invalidate()
            assertThat(awaitItem()).isEqualTo(InsightAuditViewModel.Event.Invalidated)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.state.value).isEqualTo(InsightAuditViewModel.State.Done)
    }

    @Test
    fun `invalidate failure stays in Loaded and emits InvalidateFailed event`() = runTest {
        source.cached = sampleResponse
        source.invalidateError = AiHttpException(code = 500, body = "boom")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)
        vm.awaitLoaded()

        vm.events.test {
            vm.invalidate()
            val event = awaitItem()
            assertThat(event).isInstanceOf(InsightAuditViewModel.Event.InvalidateFailed::class.java)
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(vm.state.value).isInstanceOf(InsightAuditViewModel.State.Loaded::class.java)
    }

    @Test
    fun `style snapshot reflects current AiPreferences at load time`() = runTest {
        source.cached = sampleResponse
        source.styleFlow.value = AiStyle(tone = "casual", language = "auto")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)
        vm.awaitLoaded()

        val loaded = vm.state.value as InsightAuditViewModel.State.Loaded
        assertThat(loaded.currentStyle?.tone).isEqualTo("casual")
        assertThat(loaded.currentStyle?.language).isEqualTo("auto")

        // Later changes to the live preferences must not retroactively mutate
        // the snapshot the audit screen displays.
        source.styleFlow.value = AiStyle(tone = "enthusiastic", language = "fr")
        val stillLoaded = vm.state.value as InsightAuditViewModel.State.Loaded
        assertThat(stillLoaded.currentStyle?.tone).isEqualTo("casual")
    }

    @Test
    fun `retry re-runs load`() = runTest {
        source.loadError = AiHttpException(code = 503, body = "boom")

        val vm = InsightAuditViewModel(documentId = 1L, source = source)
        vm.awaitTerminal()
        assertThat(vm.state.value).isInstanceOf(InsightAuditViewModel.State.Error::class.java)

        // recover
        source.loadError = null
        source.cached = sampleResponse
        vm.retry()
        vm.awaitLoaded()

        assertThat(vm.state.value).isInstanceOf(InsightAuditViewModel.State.Loaded::class.java)
    }

    private suspend fun InsightAuditViewModel.awaitLoaded() {
        state.test {
            var s = awaitItem()
            while (s !is InsightAuditViewModel.State.Loaded) s = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun InsightAuditViewModel.awaitTerminal() {
        state.test {
            var s = awaitItem()
            while (s is InsightAuditViewModel.State.Loading ||
                s is InsightAuditViewModel.State.Invalidating
            ) {
                s = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/**
 * Hand-rolled fake for [InsightAuditViewModel.Source]. Keeps tests hermetic
 * without bringing in a mocking library (matches the repo's existing test
 * style — see [io.theficos.ereader.ui.library.LibraryViewModelTest]).
 */
private class FakeSource(
    private val identity: DocumentIdentity,
) : InsightAuditViewModel.Source {
    var cached: BookInsightResponse? = null
    var loadError: Throwable? = null
    var invalidateError: Throwable? = null
    var unknownDocumentId: Boolean = false
    val styleFlow = MutableStateFlow<AiStyle?>(AiStyle())
    val invalidateCalls = mutableListOf<DocumentIdentity>()

    override suspend fun resolveIdentity(documentId: Long): DocumentIdentity? =
        if (unknownDocumentId) null else identity

    override suspend fun getCachedInsight(id: DocumentIdentity): BookInsightResponse? {
        loadError?.let { throw it }
        return cached
    }

    override suspend fun invalidate(id: DocumentIdentity) {
        invalidateCalls += id
        invalidateError?.let { throw it }
    }

    override fun currentStyle(): AiStyle? = styleFlow.value
}
