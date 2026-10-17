package dev.cline.termux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons

/**
 * The welcome illustration (after the desktop `@cline/ui` `AgentWelcomeHero`, DS §4.2): the app's
 * mark ([ClineIcons.AppLogo]) at [size] (72dp compact, 96dp expanded) in `primary` at 13 % fill and
 * 12 % stroke over a faint 40dp grid (`outlineVariant` @ 20 %). The desktop draws Cline's bot here;
 * this app does not use the Cline logo. Decorative (`aria-hidden` on the desktop), so it clears
 * its semantics; the desktop's sr-only "What would you like to build?" heading is the caller's
 * job (Home wraps the hero in it). Hidden on height-compact windows by the caller.
 */
@Composable
fun WelcomeHero(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    gridHeight: Dp = size * 2,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    val fill: Painter = rememberVectorPainter(ClineIcons.AppLogo)
    // The same edge as a stroke, on the same 24-unit canvas, so the two layers coincide.
    val outline: Painter = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_app_logo_outline))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        HeroGrid(color = gridColor, modifier = Modifier.fillMaxWidth().height(gridHeight))
        Canvas(Modifier.size(size)) {
            with(fill) { draw(this@Canvas.size, colorFilter = ColorFilter.tint(primary.copy(alpha = 0.13f))) }
            with(outline) { draw(this@Canvas.size, colorFilter = ColorFilter.tint(primary.copy(alpha = 0.12f))) }
        }
    }
}

/** A 40dp grid, strongest at the centre and fading out (desktop radial mask). */
@Composable
private fun HeroGrid(color: Color, modifier: Modifier = Modifier) {
    val cell = 40.dp
    Canvas(modifier) {
        val step = cell.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = maxOf(size.width, size.height) / 2f
        val mask = Brush.radialGradient(
            colorStops = arrayOf(0f to color, 0.55f to color, 1f to Color.Transparent),
            center = Offset(cx, cy),
            radius = radius,
        )
        val stroke = 1.dp.toPx()
        var x = cx % step
        while (x <= size.width) {
            drawLine(mask, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            x += step
        }
        var y = cy % step
        while (y <= size.height) {
            drawLine(mask, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += step
        }
    }
}

@LightDarkPreviews
@Composable
private fun WelcomeHeroCompactPreview() {
    ComponentPreview {
        WelcomeHero()
    }
}

@LightDarkPreviews
@Composable
private fun WelcomeHeroLargePreview() {
    ComponentPreview {
        WelcomeHero(size = 96.dp)
    }
}
