package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookQuoteTest {
    @Test fun `quote preserves paragraph breaks and includes title and author`() {
        assertThat(formatBookQuote("  First paragraph.\n\nSecond paragraph.  ", "A Book", "An Author"))
            .isEqualTo("“First paragraph.\n\nSecond paragraph.”\n— A Book, An Author")
    }

    @Test fun `missing author omits the separator and blank metadata adds no empty attribution`() {
        assertThat(formatBookQuote("Passage", "A Book", null)).isEqualTo("“Passage”\n— A Book")
        assertThat(formatBookQuote("Passage", " ", " ")).isEqualTo("“Passage”")
    }
}
