package dev.cline.termux.ui.chat.rows

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.LocalReducedMotion

/*
 * The running-label shimmer (DS §6, desktop `cline-chat-title-shimmer 2.25s linear infinite`):
 * a 55 % → 100 % → 55 % band of the label colour sweeping across the text. The label is laid
 * out and composed once; the band is drawn in the draw phase over an offscreen layer
 * (`SrcIn` keeps only the glyphs), so a shimmering row redraws per frame without recomposing.
 * Under reduced motion the label is static (DS §6).
 */

/**
 * The shimmer's progress (0 → 1 over [ClineMotion.ShimmerMillis], repeating), or null when
 * [enabled] is false or animations are off. Read it only in the draw phase ([shimmer]).
 */
@Composable
fun rememberShimmerProgress(enabled: Boolean = true): State<Float>? {
    val reducedMotion = LocalReducedMotion.current
    if (!enabled || reducedMotion) return null
    val transition = rememberInfiniteTransition(label = "label-shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ClineMotion.ShimmerMillis, easing = LinearEasing)),
        label = "label-shimmer-progress",
    )
}

/**
 * Where the band's bright centre sits for [progress] across a label [width]: the desktop
 * gradient is twice the text width and moves from 200 % to −200 %, so the centre travels from
 * 1.5 widths to −0.5 widths, with the dim colour clamped outside.
 */
internal fun shimmerCenter(width: Float, progress: Float): Float = width * (1.5f - 2f * progress)

/**
 * Draws the shimmer band of [color] over the content (glyphs drawn opaque), reading [progress]
 * in the draw phase only.
 */
fun Modifier.shimmer(color: Color, progress: State<Float>): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val width = size.width.coerceAtLeast(1f)
            val dim = color.copy(alpha = color.alpha * SHIMMER_DIM_ALPHA)
            val colors = listOf(dim, color, dim)
            onDrawWithContent {
                drawContent()
                val center = shimmerCenter(width, progress.value)
                drawRect(
                    brush = Brush.horizontalGradient(colors, startX = center - width / 2f, endX = center + width / 2f, tileMode = TileMode.Clamp),
                    blendMode = BlendMode.SrcIn,
                )
            }
        }

private const val SHIMMER_DIM_ALPHA = 0.55f

/**
 * A one-line label that shimmers while [shimmer] is true (running tool rows, "Thinking").
 * Code parts keep their `ClineMono.label` span styles; the band colours the whole text.
 */
@Composable
fun ShimmerText(
    text: AnnotatedString,
    shimmer: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = 1,
) {
    val progress = rememberShimmerProgress(shimmer)
    Text(
        text = text,
        modifier = if (progress != null) modifier.shimmer(color, progress) else modifier,
        style = style.copy(lineBreak = LineBreak.Simple),
        // Shimmering glyphs are drawn opaque; the band supplies the colour and its alpha.
        color = if (progress != null) color.copy(alpha = 1f) else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [String] variant of [ShimmerText]. */
@Composable
fun ShimmerText(
    text: String,
    shimmer: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = 1,
) = ShimmerText(AnnotatedString(text), shimmer, color, modifier, style, maxLines)

@LightDarkPreviews
@Composable
private fun ShimmerTextPreview() {
    ComponentPreview {
        Column {
            ShimmerText("Running command", shimmer = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShimmerText("正在运行命令 ./gradlew test", shimmer = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShimmerText("Ran command", shimmer = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
