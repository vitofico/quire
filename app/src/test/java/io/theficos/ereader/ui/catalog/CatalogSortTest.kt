package io.theficos.ereader.ui.catalog

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.opds.OpdsPublication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogSortTest {

    /**
     * The reporter's Kavita series page from issue #105, reduced to what the
     * sort can see. Volume 19's entry names a single author where every other
     * volume names two, and volume 16's title carries a stray colon.
     */
    private fun reZeroSeries(): List<OpdsPublication> = (1..29).map { n ->
        val series = if (n == 16) "Re:ZERO: -Starting Life in Another World-" else "Re:ZERO -Starting Life in Another World-"
        OpdsPublication(
            title = "Re:ZERO -Starting Life in Another World - $series, Vol. %02d".format(n),
            author = if (n == 19) "Shinichirou Otsuka" else "Tappei Nagatsuki and Shinichirou Otsuka",
            epubDownloadHref = "https://feed.example/download/$n.epub",
            coverUrl = null,
        )
    }

    private fun volumes(list: List<OpdsPublication>) = list.map { it.title.substringAfterLast("Vol. ") }

    @Test fun `AS_SHOWN leaves a series page in reading order`() {
        val sorted = applyCatalogSort(reZeroSeries(), CatalogSort.AS_SHOWN)
        assertThat(volumes(sorted)).isEqualTo((1..29).map { "%02d".format(it) })
    }

    @Test fun `AUTHOR sort is what scrambled the series page`() {
        // Not a regression to fix but the reason AS_SHOWN is the default: a
        // single differing author line and one stray colon are enough to throw
        // two volumes to opposite ends of a reading order.
        val sorted = applyCatalogSort(reZeroSeries(), CatalogSort.AUTHOR)
        assertThat(volumes(sorted).first()).isEqualTo("19")
        assertThat(volumes(sorted).last()).isEqualTo("16")
    }

    @Test fun `AUTHOR sort still groups a flat listing by author`() {
        val flat = listOf(
            OpdsPublication("Dune", "Frank Herbert", "https://f/1.epub", null),
            OpdsPublication("The Hobbit", "J.R.R. Tolkien", "https://f/2.epub", null),
            OpdsPublication("Children of Dune", "Frank Herbert", "https://f/3.epub", null),
        )
        val sorted = applyCatalogSort(flat, CatalogSort.AUTHOR)
        assertThat(sorted.map { it.title })
            .containsExactly("Children of Dune", "Dune", "The Hobbit").inOrder()
    }

    @Test fun `publications with no author sort last`() {
        val list = listOf(
            OpdsPublication("Anonymous", null, "https://f/1.epub", null),
            OpdsPublication("Dune", "Frank Herbert", "https://f/2.epub", null),
        )
        val sorted = applyCatalogSort(list, CatalogSort.AUTHOR)
        assertThat(sorted.map { it.title }).containsExactly("Dune", "Anonymous").inOrder()
    }

    @Test fun `TITLE sort ignores case`() {
        val list = listOf(
            OpdsPublication("banana", "a", "https://f/1.epub", null),
            OpdsPublication("Apple", "b", "https://f/2.epub", null),
        )
        val sorted = applyCatalogSort(list, CatalogSort.TITLE)
        assertThat(sorted.map { it.title }).containsExactly("Apple", "banana").inOrder()
    }

    @Test fun `a fresh install defaults to the server's order`() {
        val store = CatalogPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store.flow.value).isEqualTo(CatalogSort.AS_SHOWN)
    }

    @Test fun `an explicit choice still survives`() {
        val store = CatalogPreferencesStore(ApplicationProvider.getApplicationContext())
        store.update(CatalogSort.TITLE)
        assertThat(store.flow.value).isEqualTo(CatalogSort.TITLE)
        assertThat(CatalogPreferencesStore(ApplicationProvider.getApplicationContext()).flow.value)
            .isEqualTo(CatalogSort.TITLE)
    }
}
