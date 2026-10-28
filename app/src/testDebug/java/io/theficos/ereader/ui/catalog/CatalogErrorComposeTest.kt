package io.theficos.ereader.ui.catalog

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.ui.UiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogErrorComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `catalog failure exposes retry and cached back actions`() {
        var retried = false
        var wentBack = false
        composeRule.setContent {
            CatalogError(
                state = CatalogUiState.Error(UiText.Dynamic("Offline"), canGoBack = true),
                onRetry = { retried = true },
                onBack = { wentBack = true },
            )
        }

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Back").performClick()

        assertThat(retried).isTrue()
        assertThat(wentBack).isTrue()
    }
}
