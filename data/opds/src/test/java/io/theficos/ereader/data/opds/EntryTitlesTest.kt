package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntryTitlesTest {

    @Test fun `strips the unread glyph Kavita prefixes to a title`() {
        assertThat(cleanEntryTitle("⭘ EXP Is Golden - Volume 3"))
            .isEqualTo("EXP Is Golden - Volume 3")
    }

    @Test fun `strips every partial-progress glyph`() {
        for (glyph in listOf("◔", "◑", "◕", "⬤")) {
            assertThat(cleanEntryTitle("$glyph Grimms' Fairy Tales"))
                .isEqualTo("Grimms' Fairy Tales")
        }
    }

    @Test fun `leaves an ordinary title untouched`() {
        assertThat(cleanEntryTitle("The Sample Book")).isEqualTo("The Sample Book")
    }

    @Test fun `trims surrounding whitespace`() {
        assertThat(cleanEntryTitle("  The Sample Book \n")).isEqualTo("The Sample Book")
    }

    @Test fun `keeps a glyph that is part of the title rather than a prefix`() {
        // No separating space: this is the book's name, not Kavita's marker.
        assertThat(cleanEntryTitle("⬤Point")).isEqualTo("⬤Point")
    }

    @Test fun `strips only one glyph`() {
        assertThat(cleanEntryTitle("⭘ ⭘ Twice")).isEqualTo("⭘ Twice")
    }

    @Test fun `handles an empty title`() {
        assertThat(cleanEntryTitle("")).isEqualTo("")
        assertThat(cleanEntryTitle("   ")).isEqualTo("")
    }
}
