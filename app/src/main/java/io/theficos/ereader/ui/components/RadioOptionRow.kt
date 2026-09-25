package io.theficos.ereader.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One choice in a single-choice list. The whole row, label included, selects it, and it reads as
 * one radio button to TalkBack. The button itself takes no clicks of its own; it keeps its 48 dp
 * box so the list looks as it did when only the circle responded.
 *
 * Put the rows in a `Column(Modifier.selectableGroup())` so the group is announced as one.
 */
@Composable
fun RadioOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
        Text(label)
    }
}
