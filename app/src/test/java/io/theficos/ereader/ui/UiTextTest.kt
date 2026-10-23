package io.theficos.ereader.ui

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.bookdetail.insightErrorMessage
import io.theficos.ereader.ui.library.buildSeriesLabel
import io.theficos.ereader.ui.reader.displayLabel
import io.theficos.quire.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class UiTextTest {
    @Test fun `one retained message resolves against the current display locale`() {
        val message = uiText(R.string.common_settings)
        val expected = mapOf(
            "en-US" to "Settings", "es" to "Ajustes", "it" to "Impostazioni",
            "fr" to "Paramètres", "de" to "Einstellungen",
        )
        expected.forEach { (locale, text) ->
            assertThat(message.asString(localeContext(locale))).isEqualTo(text)
        }
        assertThat(message.asString(localeContext("nl"))).isEqualTo("Settings")
    }

    @Test fun `app errors translate while retaining protocol status codes`() {
        val error = insightErrorMessage(AiHttpException(502, "upstream diagnostic"))
        assertThat(error.asString(localeContext("es")))
            .isEqualTo("No se pudieron generar los análisis (502).")
        assertThat(error.asString(localeContext("de")))
            .isEqualTo("Einblicke konnten nicht erstellt werden (502).")
    }

    @Test fun `metadata and external diagnostics are never treated as resource keys`() {
        val metadata = "A title with 50% & <tags>"
        assertThat(UiText.Dynamic(metadata).asString(localeContext("it"))).isEqualTo(metadata)
        assertThat(buildSeriesLabel(metadata, 2.5)?.asString(localeContext("fr")))
            .isEqualTo("$metadata · Tome 2.5")
        assertThat(uiText(R.string.common_imported, metadata).asString(localeContext("de")))
            .isEqualTo("Importiert: $metadata")
    }

    @Test fun `reader controls resolve localized theme names`() {
        assertThat(ReaderTheme.DARK_SEPIA.displayLabel().asString(localeContext("es")))
            .isEqualTo("Sepia oscuro")
        assertThat(ReaderTheme.DARK_SEPIA.displayLabel().asString(localeContext("de")))
            .isEqualTo("Dunkles Sepia")
    }

    @Test fun `prompt counts use Android plural selection in every shipped language`() {
        val expected = mapOf(
            "en-US" to ("Show 1 prompt" to "Show 2 prompts"),
            "es" to ("Mostrar 1 pregunta" to "Mostrar 2 preguntas"),
            "it" to ("Mostra 1 domanda" to "Mostra 2 domande"),
            "fr" to ("Afficher 1 question" to "Afficher 2 questions"),
            "de" to ("1 Frage anzeigen" to "2 Fragen anzeigen"),
        )
        expected.forEach { (locale, text) ->
            val res = localeContext(locale).resources
            assertThat(res.getQuantityString(R.plurals.common_show_prompts, 1, 1)).isEqualTo(text.first)
            assertThat(res.getQuantityString(R.plurals.common_show_prompts, 2, 2)).isEqualTo(text.second)
        }
    }

    @Test fun `finished counts keep singular grammar`() {
        val expected = mapOf("es" to "1 terminado", "it" to "1 completato", "fr" to "1 terminé", "de" to "1 beendet")
        expected.forEach { (locale, text) ->
            assertThat(localeContext(locale).resources.getQuantityString(R.plurals.common_finished, 1, 1))
                .isEqualTo(text)
        }
    }

    @Test fun `affinity API band codes resolve localized labels instead of leaking wire values`() {
        val expected = mapOf("strong" to "Alta", "moderate" to "Moderada", "weak" to "Baja")
        expected.forEach { (code, text) ->
            assertThat(affinityBandLabel(code).asString(localeContext("es"))).isEqualTo(text)
        }
        assertThat(affinityBandLabel("strong").asString(englishContext())).isEqualTo("Strong")
        assertThat(affinityBandLabel("new_band").asString(localeContext("de"))).isEqualTo("New_band")
    }

    @Test fun `formatted strings retain percent signs and significant spaces`() {
        listOf("en-US", "es", "it", "fr", "de").forEach { locale ->
            val context = localeContext(locale)
            assertThat(uiText(R.string.common_reading_percent, 42).asString(context)).isEqualTo("42%")
            assertThat(context.getString(R.string.common_based_on)).endsWith(" ")
            assertThat(context.getString(R.string.brand_name)).isEqualTo("Quire")
        }
    }
    @Test fun `current book details keep status series and filter copy localized`() {
        val spanish = localeContext("es")
        assertThat(uiText(R.string.book_finished_on, "21/10/2026").asString(spanish))
            .isEqualTo("Terminado el 21/10/2026")
        assertThat(uiText(R.string.book_percent_read, 42).asString(localeContext("de")))
            .isEqualTo("42% gelesen")
        assertThat(io.theficos.ereader.ui.bookdetail.seriesLine("Foundation", 3.0)?.asString(spanish))
            .isEqualTo("Foundation, libro 3")
        assertThat(spanish.resources.getQuantityString(R.plurals.library_filter_hiding_books, 1, 1))
            .isEqualTo("El filtro está ocultando 1 libro.")
        assertThat(spanish.resources.getQuantityString(R.plurals.library_filter_hiding_books, 2, 2))
            .isEqualTo("El filtro está ocultando tus 2 libros.")
        assertThat(spanish.getString(io.theficos.ereader.ui.theme.Palette.PAPER.label)).isEqualTo("Papel")
        assertThat(spanish.getString(io.theficos.ereader.ui.theme.ThemeMode.LIGHT.label)).isEqualTo("Claro")
    }

}
