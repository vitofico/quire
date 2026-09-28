package io.theficos.ereader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader's font and theme pickers used to select an option only when the small circle was
 * tapped; a tap on the name did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RadioOptionRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun `tapping an option's label selects it`() {
        composeRule.setContent {
            var picked by remember { mutableStateOf("Lora") }
            Column(Modifier.selectableGroup()) {
                listOf("Lora", "Literata").forEach { name ->
                    RadioOptionRow(label = name, selected = picked == name, onSelect = { picked = name })
                }
            }
        }

        composeRule.onNodeWithText("Literata").performClick()

        // The label merges into the row, so the node found by its text is the row itself.
        composeRule.onNodeWithText("Literata").assertIsSelected()
        composeRule.onNodeWithText("Lora").assertIsNotSelected()
    }

    @Test fun `the row reads as one radio button`() {
        composeRule.setContent {
            RadioOptionRow(label = "Charis", selected = true, onSelect = {})
        }

        composeRule.onNodeWithText("Charis")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }
}
