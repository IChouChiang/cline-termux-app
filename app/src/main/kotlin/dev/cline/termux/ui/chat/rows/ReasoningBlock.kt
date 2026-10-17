package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.markdown.MarkdownText

/*
 * The thinking row (HC §3.4.3, DS §4.1 #3; desktop `reasoning-block.tsx` + `ThinkingBlock`):
 * `neurology` icon, "Thinking" (shimmering while streaming) then "Thought for Ns", collapsed by
 * default even while streaming, and a markdown body capped at 192dp that scrolls. The row has
 * the tool rows' compact metrics ([DisclosureRowTokens], A24).
 */

/** Desktop `max-height: 12rem`. */
internal val ReasoningBodyMaxHeight = 192.dp

/**
 * [stateKey] keeps the open state across recompositions and reconciliation (the owning
 * message's renderKey). Renders nothing for empty, unredacted content (desktop returns null).
 */
@Composable
fun ReasoningBlock(
    content: ReasoningContent,
    streaming: Boolean,
    durationMilliseconds: Long?,
    stateKey: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    if (content.isEmpty) return
    var open by rememberSaveable(stateKey) { mutableStateOf(initiallyOpen) }
    val toggled = LocalDisclosureToggled.current
    val label = when (val thought = thoughtLabel(streaming, durationMilliseconds)) {
        ThoughtLabel.Thinking -> stringResource(R.string.reasoning_thinking)
        is ThoughtLabel.ThoughtFor -> stringResource(R.string.reasoning_thought, thought.seconds.toInt())
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        DisclosureHeader(
            expanded = open,
            onToggle = {
                toggled?.invoke()
                open = !open
            },
            contentDescription = label,
        ) {
            DisclosureIconSlot {
                Icon(ClineIcons.Tools.Thinking, contentDescription = null, tint = muted, modifier = Modifier.size(DisclosureRowTokens.IconSize))
            }
            ShimmerText(text = label, shimmer = streaming, color = muted, modifier = Modifier.weight(1f), style = DisclosureRowTokens.labelStyle)
        }
        DisclosureBody(visible = open) {
            val bodyColor = lerp(muted, MaterialTheme.colorScheme.onSurface, 0.75f)
            val text = if (content.showsRedactedPlaceholder) stringResource(R.string.reasoning_redacted) else content.text
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = ReasoningBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownText(
                    markdown = text,
                    style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
                    streaming = streaming,
                )
            }
        }
    }
}
