package dev.cline.termux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** Which way a [DisclosureChevron] turns between its collapsed and expanded states. */
enum class ChevronTurn {
    /** Points to the end when collapsed and down when expanded (DS §4.1 section/drawer rows). */
    EndToDown,

    /** Points to the end when collapsed and up when expanded (desktop work row: −90° → −180°). */
    EndToUp,

    /** Points down when collapsed and up when expanded (inline "show more" toggles). */
    DownToUp,
}

/** Sizes for [DisclosureChevron]: tool rows use 20dp, meta rows 16dp, icon buttons 24dp. */
object DisclosureChevronDefaults {
    val ToolRowSize: Dp = 20.dp
    val MetaRowSize: Dp = 16.dp
    val IconButtonSize: Dp = 24.dp
}

/**
 * The rotation of the `expand_more` glyph for [expanded] in [rtl]. "The end" is the right in LTR
 * (−90°) and the left in RTL (+90°), DS §8.2.6; the expanded turn continues in the same direction
 * so the chevron never swings back through its collapsed side.
 */
internal fun disclosureChevronAngle(expanded: Boolean, rtl: Boolean, turn: ChevronTurn = ChevronTurn.EndToDown): Float {
    val end = if (rtl) 90f else -90f
    return when (turn) {
        ChevronTurn.EndToDown -> if (expanded) 0f else end
        ChevronTurn.EndToUp -> if (expanded) 2 * end else end
        ChevronTurn.DownToUp -> if (expanded) 180f else 0f
    }
}

/**
 * The one disclosure chevron (DS §4.1 #4, §6 "Chevron rotation"): `expand_more` rotated by
 * [disclosureChevronAngle], animated with `tween(Short4, Standard)` and instant under reduced
 * motion. Decorative: the owning control carries the label and the expanded/collapsed state.
 */
@Composable
fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = DisclosureChevronDefaults.ToolRowSize,
    tint: Color = LocalContentColor.current,
    turn: ChevronTurn = ChevronTurn.EndToDown,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = ClineTheme.reducedMotion
    val angle by animateFloatAsState(
        targetValue = disclosureChevronAngle(expanded, rtl, turn),
        animationSpec = tween(if (reduced) 0 else ClineMotion.Short4, easing = ClineMotion.Standard),
        label = "chevron",
    )
    Icon(
        imageVector = ClineIcons.ExpandMore,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size).rotate(angle),
    )
}

@Composable
private fun ChevronStates() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ChevronTurn.entries.forEach { turn ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                DisclosureChevron(expanded = false, turn = turn)
                DisclosureChevron(expanded = true, turn = turn)
                DisclosureChevron(expanded = false, size = DisclosureChevronDefaults.MetaRowSize, turn = turn)
                DisclosureChevron(expanded = true, size = DisclosureChevronDefaults.MetaRowSize, turn = turn)
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun DisclosureChevronPreview() {
    ComponentPreview { ChevronStates() }
}

@LightDarkPreviews
@Composable
private fun DisclosureChevronRtlPreview() {
    ComponentPreview {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { ChevronStates() }
    }
}
