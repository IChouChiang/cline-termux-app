package dev.cline.termux.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * Details-sheet row (DS §4.1 #13, HC §6.5): label `bodyMedium` `onSurfaceVariant`, value
 * `ClineMono.small` `onSurface`, two columns (`max-content` label + flexible value), 48dp
 * target. Long-press copies [copyValue] (the value by default; the Workspace row passes
 * the full path) as a plain clip, and the same copy is a TalkBack custom action
 * ([copyActionLabel], "Copy <label>" by default). Values are read as written.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    copyValue: String = value,
    copyActionLabel: String = stringResource(R.string.components_copy_value, label),
    copyable: Boolean = true,
    labelWidth: androidx.compose.ui.unit.Dp = 112.dp,
    maxValueLines: Int = 3,
) {
    val copier = rememberPlainCopier()
    val haptic = rememberLongPressHaptic()
    val copy = { copier.copy(label, copyValue) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (copyable) {
                    Modifier
                        .pointerInput(copyValue) { detectTapGestures(onLongPress = { haptic(); copy() }) }
                        .semantics(mergeDescendants = true) {
                            customActions = listOf(CustomAccessibilityAction(copyActionLabel) { copy(); true })
                        }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp, max = labelWidth),
        )
        Spacer(Modifier.width(Spacing.l))
        LtrText(
            text = value,
            style = ClineMono.small,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxValueLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@LightDarkPreviews
@Composable
private fun KeyValueRowPreview() {
    ComponentPreview(padding = false) {
        Column {
            KeyValueRow(label = "Workspace", value = "cline-app", copyValue = "/data/data/com.termux/files/home/cline-app", copyActionLabel = "Copy full path")
            KeyValueRow(label = "Model", value = "claude-sonnet-5-5")
            KeyValueRow(label = "Tokens", value = "12k")
            KeyValueRow(label = "Session ID", value = "sess_01HZXQ4V8K2Y9M7N3P6R5T1W9Q")
        }
    }
}
