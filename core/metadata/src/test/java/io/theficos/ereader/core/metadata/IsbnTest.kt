package io.theficos.ereader.core.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsbnTest {
    @Test fun `isbn13 passthrough and cleaning`() {
        assertThat(Isbn.toIsbn13("978-0-261-10357-3")).isEqualTo("9780261103573")
    }

    @Test fun `isbn10 converts`() {
        assertThat(Isbn.toIsbn13("0261103571")).isEqualTo("9780261103573")
        assertThat(Isbn.toIsbn13("080442957X")).isEqualTo("9780804429573")
    }

    @Test fun `invalid returns null`() {
        assertThat(Isbn.toIsbn13("9780261103574")).isNull()
        assertThat(Isbn.toIsbn13("hello")).isNull()
        assertThat(Isbn.toIsbn13("")).isNull()
    }
}
