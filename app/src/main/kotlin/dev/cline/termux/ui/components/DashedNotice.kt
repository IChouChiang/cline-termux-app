package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing

/**
 * The desktop `PageEmptyState`'s dashed variant (CR Appendix A): a 1dp dashed
 * `outlineVariant` border on `shapes.medium`, centred `bodyMedium` `onSurfaceVariant` text,
 * 24dp padding, optional action. Used for per-tab empty / search-empty text.
 */
@Composable
fun DashedNotice(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val color = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                val dash = 4.dp.toPx()
                val inset = stroke / 2f
                translate(inset, inset) {
                    drawOutline(
                        outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this),
                        color = color,
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                    )
                }
            }
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@LightDarkPreviews
@Composable
private fun DashedNoticePreview() {
    ComponentPreview { DashedNotice(text = "No skills installed yet.") }
}

@LightDarkPreviews
@Composable
private fun DashedNoticeWithActionPreview() {
    ComponentPreview { DashedNotice(text = "No MCP servers match your search.", actionLabel = "Clear search", onAction = {}) }
}
