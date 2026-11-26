package io.theficos.ereader.ui.catalog

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.SavedCatalog
import java.net.URI
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OpenCatalogsTest {
    /** The packaged list, read from the repository's `catalogs/open-catalogs.json`. */
    private val catalogs = OpenCatalogs.load(ApplicationProvider.getApplicationContext())

    @Test fun `every open catalog is complete, https and described in each app language`() {
        assertThat(catalogs).isNotEmpty()
        assertThat(catalogs.map { it.id }).containsNoDuplicates()
        assertThat(catalogs.map { it.url }).containsNoDuplicates()
        catalogs.forEach { catalog ->
            assertThat(URI(catalog.url).scheme).isEqualTo("https")
            assertThat(URI(catalog.home).scheme).isEqualTo("https")
            assertThat(catalog.name.length).isAtMost(SavedCatalog.MAX_NAME_LENGTH)
            assertThat(catalog.languages).isNotEmpty()
            assertThat(catalog.offers).isAnyOf("public_domain", "public_domain_us", "open_licence")
            assertThat(catalog.descriptions.keys).containsExactly("en", "de", "es", "fr", "it")
            catalog.descriptions.values.forEach { assertThat(it).doesNotContain("—") }
        }
    }

    @Test fun `catalogs in the reader's language come first, the rest keep their order`() {
        val ordered = catalogs.orderedFor("fr")
        val french = catalogs.filter { "fr" in it.languages }
        assertThat(ordered.take(french.size)).isEqualTo(french)
        assertThat(ordered.drop(french.size)).isEqualTo(catalogs.filterNot { "fr" in it.languages })
        assertThat(catalogs.orderedFor("xx")).isEqualTo(catalogs)
    }

    @Test fun `a description falls back to English`() {
        val catalog = catalogs.first()
        assertThat(catalog.description("it")).isEqualTo(catalog.descriptions["it"])
        assertThat(catalog.description("pt")).isEqualTo(catalog.descriptions["en"])
    }
}
