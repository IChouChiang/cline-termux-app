package dev.cline.termux.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing

/**
 * Keyboard focus ring (DS §8.1.7, desktop `--ring`): a 2dp `primary` outline offset 2dp
 * outside the node, drawn only while the node is focused **and** the input mode is
 * keyboard (a Tab S7+ Book Cover, DeX); touch focus never shows it.
 *
 * Place it before the node's `clickable` / `combinedClickable` / `selectable` (it reads the
 * focus of that focus target) and outside any `clip` of its own, which would cut the ring
 * off. A full-bleed list row passes [inset]: the ring is then drawn 2dp inside the row,
 * where the list's clip and the next row's background cannot hide it.
 */
@Composable
fun Modifier.focusRing(
    shape: Shape = MaterialTheme.shapes.small,
    forceVisible: Boolean = false,
    inset: Boolean = false,
): Modifier {
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val color = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .drawWithContent {
            drawContent()
            if ((focused && keyboard) || forceVisible) {
                val stroke = 2.dp.toPx()
                val gap = 2.dp.toPx()
                val pad = (gap + stroke / 2f) * if (inset) -1f else 1f
                translate(-pad, -pad) {
                    drawOutline(
                        outline = shape.createOutline(Size(size.width + 2 * pad, size.height + 2 * pad), layoutDirection, this),
                        color = color,
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
}

@LightDarkPreviews
@Composable
private fun FocusRingPreview() {
    ComponentPreview {
        Row {
            Button(onClick = {}, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge, forceVisible = true)) { Text("Focused") }
            Spacer(Modifier.width(Spacing.l))
            OutlinedButton(onClick = {}, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text("Idle") }
        }
    }
}

@LightDarkPreviews
@Composable
private fun FocusRingInsetRowPreview() {
    ComponentPreview(padding = false) {
        ListItem(
            headlineContent = { Text("Full-bleed row") },
            supportingContent = { Text("Inset ring") },
            modifier = Modifier.focusRing(RectangleShape, forceVisible = true, inset = true).clickable {},
        )
    }
}
