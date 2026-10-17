package dev.cline.termux.ui.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons

/**
 * SP S8.4 reasoning menu (DS #7): a `DropdownMenu` anchored to the composer's reasoning chip
 * (place it in the same `Box` as the chip). Items are the desktop `EFFORT_LEVELS` in order with
 * a trailing `check` on [selected]; 48dp items, min width 200dp (DS #15). Picking closes the menu.
 */
@Composable
fun ReasoningMenu(
    expanded: Boolean,
    selected: ReasoningEffort?,
    onSelect: (ReasoningEffort) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp),
    ) {
        ReasoningEffort.entries.forEach { effort ->
            val isSelected = effort == selected
            DropdownMenuItem(
                text = { Text(reasoningEffortLabel(effort)) },
                onClick = {
                    onSelect(effort)
                    onDismissRequest()
                },
                trailingIcon = if (isSelected) {
                    { Icon(ClineIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp)) }
                } else {
                    null
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                    },
            )
        }
    }
}

/** The desktop `effortLabel` of one level: "None", "Low", "Medium", "High", "Extra". */
@Composable
fun reasoningEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.None -> R.string.reasoning_level_none
        ReasoningEffort.Low -> R.string.reasoning_level_low
        ReasoningEffort.Medium -> R.string.reasoning_level_medium
        ReasoningEffort.High -> R.string.reasoning_level_high
        ReasoningEffort.XHigh -> R.string.reasoning_level_extra
    },
)

/** The composer chip's label (S8.4): "Reasoning" with no choice and unknown support, else a level. */
@Composable
fun reasoningChipText(label: ReasoningChipLabel): String = when (label) {
    ReasoningChipLabel.Generic -> stringResource(R.string.reasoning_label_generic)
    is ReasoningChipLabel.Level -> reasoningEffortLabel(label.effort)
}

@LightDarkPreviews
@Composable
private fun ReasoningMenuPreview() {
    ComponentPreview {
        var open by remember { mutableStateOf(true) }
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(reasoningEffortLabel(ReasoningEffort.Low)) },
                leadingIcon = { Icon(ClineIcons.Neurology, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            ReasoningMenu(expanded = open, selected = ReasoningEffort.Low, onSelect = {}, onDismissRequest = { open = false })
        }
    }
}

@LightDarkPreviews
@Composable
private fun ReasoningChipLabelsPreview() {
    ComponentPreview {
        androidx.compose.foundation.layout.Column {
            Text(reasoningChipText(ReasoningChipLabel.Generic))
            Text(reasoningChipText(ReasoningChipLabel.Level(ReasoningEffort.None)))
            Text(reasoningChipText(ReasoningChipLabel.Level(ReasoningEffort.XHigh)))
        }
    }
}
