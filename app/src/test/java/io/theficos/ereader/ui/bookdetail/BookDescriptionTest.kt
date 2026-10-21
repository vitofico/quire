package io.theficos.ereader.ui.bookdetail

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.ui.components.cleanDescription
import org.junit.Test

class BookDescriptionTest {

    @Test
    fun `plain description keeps paragraphs while trimming and collapsing excess newlines`() {
        val cleaned = cleanDescription(AnnotatedString("  First\nline\n\n\n\nSecond  "))

        assertThat(cleaned.text).isEqualTo("First\nline\n\nSecond")
    }

    @Test
    fun `image placeholders do not leave blank content or excess paragraph gaps`() {
        assertThat(cleanDescription(AnnotatedString(" \uFFFC ")).text).isEmpty()
        assertThat(cleanDescription(AnnotatedString("First\n\uFFFC\n\nSecond")).text)
            .isEqualTo("First\n\nSecond")
    }

    @Test
    fun `cleanup preserves style and link annotations at their new positions`() {
        val raw = buildAnnotatedString {
            append(" \uFFFC")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("First") }
            append("\n\n\n")
            pushStringAnnotation("URL", "https://example.org")
            append("Second")
            pop()
            append(" ")
        }

        val cleaned = cleanDescription(raw)

        assertThat(cleaned.text).isEqualTo("First\n\nSecond")
        assertThat(cleaned.spanStyles).containsExactly(
            AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 5),
        )
        assertThat(cleaned.getStringAnnotations("URL", 7, 13).single().item)
            .isEqualTo("https://example.org")
    }
}
