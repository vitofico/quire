package io.theficos.ereader.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ThemeLabelsTest {
    @Test fun `every theme the server can tag a book with has a translated label`() {
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "server/quire_server/core/ai/themes.py") }
            .first { it.isFile }
            .readText()
        val vocabulary = source.substringAfter("CONTROLLED_THEMES")
            .substringAfter("{")
            .substringBefore("}")
            .let { Regex("\"([a-z_]+)\"").findAll(it).map { match -> match.groupValues[1] }.toList() }
        assertThat(vocabulary).isNotEmpty()
        assertThat(THEME_LABELS.keys).containsExactlyElementsIn(vocabulary)
    }
}
