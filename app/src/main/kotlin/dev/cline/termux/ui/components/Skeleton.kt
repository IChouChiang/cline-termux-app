package dev.cline.termux.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * Loading skeleton block (DS §4.2; desktop `ui/skeleton.tsx` `animate-pulse`):
 * `surfaceContainerHighest` with an alpha shimmer 0.6 ⇄ 1.0 over 1000ms (`Standard`),
 * static under reduced motion. For list first loads only; detail panes use a
 * `LinearProgressIndicator`. Decorative unless [contentDescription] is given.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    contentDescription: String? = null,
) {
    val alpha = skeletonAlpha()
    val semantics = if (contentDescription == null) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Spacer(
        modifier
            .then(semantics)
            // Read in the draw phase only: the pulse redraws the block without recomposing it.
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    )
}

/**
 * The pulse so a group of blocks breathes together. A [State]: read it in a draw-phase lambda
 * (`graphicsLayer { alpha = state.value }`), never in composition.
 */
@Composable
fun skeletonAlpha(): State<Float> {
    if (ClineTheme.reducedMotion) return remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ClineMotion.SkeletonMillis, easing = ClineMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
}

/**
 * [count] three-line list rows (72dp, DS #11 "loading (3 skeleton rows)"): a leading 8dp
 * dot slot, a headline and two supporting lines.
 */
@Composable
fun SkeletonListRows(count: Int = 3, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Column(modifier.fillMaxWidth().then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(Modifier.size(8.dp), shape = CircleShape)
                Spacer(Modifier.width(Spacing.l))
                Column(Modifier.weight(1f)) {
                    Skeleton(Modifier.fillMaxWidth(0.62f).height(16.dp))
                    Spacer(Modifier.height(Spacing.s))
                    Skeleton(Modifier.fillMaxWidth(0.84f).height(12.dp))
                    Spacer(Modifier.height(Spacing.xs))
                    Skeleton(Modifier.fillMaxWidth(0.4f).height(12.dp))
                }
                Spacer(Modifier.width(Spacing.l))
                Skeleton(Modifier.width(32.dp).height(12.dp))
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun SkeletonPreview() {
    ComponentPreview(padding = false) { SkeletonListRows() }
}

@LightDarkPreviews
@Composable
private fun SkeletonBlockPreview() {
    ComponentPreview { Skeleton(Modifier.fillMaxWidth().height(96.dp), shape = MaterialTheme.shapes.medium) }
}
