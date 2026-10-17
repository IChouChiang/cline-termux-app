package dev.cline.termux.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.session.DotTone
import dev.cline.termux.data.session.KernelVisual
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.session.SessionVisualFlags
import dev.cline.termux.data.session.StatusGlyph
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/*
 * The one place a status becomes a colour, an icon or a label (DS §1.6, desktop
 * `ui/components/session-status.tsx`). Everything here resolves a `SessionVisual` or
 * `KernelVisual` from `data/session/SessionVisual.kt`; no screen picks a status colour.
 */

/** [DotTone] → the theme colour of an 8dp dot (non-text, 3:1). */
@Composable
fun DotTone.color(): Color = when (this) {
    DotTone.Error -> MaterialTheme.colorScheme.error
    DotTone.Warning -> ClineTheme.colors.warning.dot
    DotTone.Success -> ClineTheme.colors.success.dot
    DotTone.Info -> ClineTheme.colors.info.dot
    DotTone.Outline -> MaterialTheme.colorScheme.outline
}

/** The visible label (sessions screen supporting line, details, roster). */
@Composable
fun SessionVisual.label(): String = when (this) {
    is SessionVisual.ActionPending -> when (kind) {
        PendingKind.Rename -> stringResource(R.string.components_status_renaming)
        PendingKind.Fork -> stringResource(R.string.components_status_forking)
        PendingKind.Delete -> stringResource(R.string.components_status_deleting)
    }
    SessionVisual.Failed -> stringResource(R.string.components_status_failed)
    SessionVisual.ApprovalRequired -> stringResource(R.string.components_status_approval_required)
    SessionVisual.QuestionAsked -> stringResource(R.string.components_status_question_asked)
    SessionVisual.Pending -> stringResource(R.string.components_status_pending)
    SessionVisual.Starting -> stringResource(R.string.components_status_starting)
    SessionVisual.Running -> stringResource(R.string.components_status_running)
    SessionVisual.Unread -> stringResource(R.string.components_status_unread)
    SessionVisual.Completed -> stringResource(R.string.components_status_completed)
    SessionVisual.Cancelled -> stringResource(R.string.components_status_cancelled)
    SessionVisual.Idle -> stringResource(R.string.components_status_idle)
}

/** "Session status: <label>" (DS §1.6 TalkBack column). */
@Composable
fun SessionVisual.talkBack(): String = stringResource(R.string.components_status_a11y, label())

/** Icon and tint for a [StatusGlyph]; `Running` has no vector (it is a 16dp progress indicator). */
@Composable
fun StatusGlyph.icon(): ImageVector? = when (this) {
    StatusGlyph.Failed -> ClineIcons.States.Failed
    StatusGlyph.ApprovalRequired -> ClineIcons.States.ApprovalRequired
    StatusGlyph.QuestionAsked -> ClineIcons.States.QuestionAsked
    StatusGlyph.Pending -> ClineIcons.States.Pending
    StatusGlyph.Running -> null
    StatusGlyph.Completed -> ClineIcons.States.Completed
    StatusGlyph.Cancelled -> ClineIcons.States.Cancelled
}

@Composable
fun StatusGlyph.tint(): Color = when (this) {
    StatusGlyph.Failed -> MaterialTheme.colorScheme.error
    StatusGlyph.ApprovalRequired, StatusGlyph.QuestionAsked, StatusGlyph.Pending -> ClineTheme.colors.warning.color
    StatusGlyph.Running, StatusGlyph.Completed -> ClineTheme.colors.success.color
    StatusGlyph.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Kernel line 1 (DS §1.6 kernel table). */
@Composable
fun KernelVisual.line1(): String = when (this) {
    is KernelVisual.Connected -> stringResource(R.string.components_kernel_connected, port.toString())
    KernelVisual.Connecting -> stringResource(R.string.components_kernel_connecting)
    KernelVisual.Reconnecting -> stringResource(R.string.components_kernel_reconnecting)
    KernelVisual.Unavailable -> stringResource(R.string.components_kernel_unavailable)
    KernelVisual.TermuxAsleep -> stringResource(R.string.components_kernel_asleep)
}

/** Kernel line 2 in `error`, only for the two down states. */
@Composable
fun KernelVisual.line2(): String? = when (this) {
    KernelVisual.Unavailable -> stringResource(R.string.components_kernel_unavailable_detail)
    KernelVisual.TermuxAsleep -> stringResource(R.string.components_kernel_asleep_detail)
    else -> null
}

/** "Kernel status: <line 1>" for the KernelChip. */
@Composable
fun KernelVisual.talkBack(): String = stringResource(R.string.components_kernel_status_a11y, line1())

/**
 * The raw dot: an 8dp circle inside a 24dp box (so titles align whether or not a dot shows).
 * A null [tone] draws nothing (desktop `visibility: hidden`). [pulsing] = alpha 1 → 0.5 → 1
 * over 2000ms with the `Pulse` easing, static under reduced motion (DS §6). Decorative
 * unless [contentDescription] is given.
 */
@Composable
fun StatusDot(
    tone: DotTone?,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    contentDescription: String? = null,
    boxSize: Dp = 24.dp,
    dotSize: Dp = 8.dp,
) {
    // A State read in graphicsLayer only: the pulse redraws the dot without recomposing it.
    val alpha = if (pulsing && tone != null) pulseAlpha() else null
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Box(modifier.size(boxSize).then(semantics), contentAlignment = Alignment.Center) {
        if (tone != null) {
            val color = tone.color()
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { this.alpha = alpha?.value ?: 1f }
                    .background(color, CircleShape),
            )
        }
    }
}

/** DS §1.6 session dot. [standalone] rows announce "Session status: <s>"; inside a merged row pass false. */
@Composable
fun SessionStatusDot(
    visual: SessionVisual,
    modifier: Modifier = Modifier,
    standalone: Boolean = true,
    boxSize: Dp = 24.dp,
) {
    StatusDot(
        tone = visual.dot,
        modifier = modifier,
        pulsing = visual.pulsing,
        contentDescription = if (standalone) visual.talkBack() else null,
        boxSize = boxSize,
    )
}

/** DS §1.6 kernel dot (same component, the kernel table's tones). */
@Composable
fun KernelStatusDot(
    visual: KernelVisual,
    modifier: Modifier = Modifier,
    standalone: Boolean = true,
    boxSize: Dp = 24.dp,
) {
    StatusDot(
        tone = visual.dot,
        modifier = modifier,
        pulsing = visual.pulsing,
        contentDescription = if (standalone) visual.talkBack() else null,
        boxSize = boxSize,
    )
}

/**
 * The status icon where one is drawn (DS §1.6 "Icon" column): the glyph in its tint, or a
 * 16dp `CircularProgressIndicator` for Running; nothing for Starting, Unread, Idle and
 * action-pending. Decorative: the row's label carries the meaning.
 */
@Composable
fun SessionStatusIcon(visual: SessionVisual, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val glyph = visual.glyph ?: return
    if (glyph == StatusGlyph.Running) {
        CircularProgressIndicator(modifier.size(16.dp), strokeWidth = 2.dp, color = glyph.tint())
    } else {
        Icon(glyph.icon()!!, contentDescription = null, modifier = modifier.size(size), tint = glyph.tint())
    }
}

/** Tailwind `animate-pulse`: 1 → 0.5 → 1, 2000ms, `Pulse` easing, restart; null (static) under reduced motion. */
@Composable
private fun pulseAlpha(): State<Float>? {
    if (ClineTheme.reducedMotion) return null
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = ClineMotion.PulseMillis
                1f at 0 using ClineMotion.Pulse
                0.5f at ClineMotion.PulseMillis / 2 using ClineMotion.Pulse
                1f at ClineMotion.PulseMillis
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "alpha",
    )
}

private val previewVisuals: List<SessionVisual> = listOf(
    SessionVisual.of("failed"),
    SessionVisual.of("running", SessionVisualFlags(approvalRequired = true)),
    SessionVisual.of("running", SessionVisualFlags(questionAsked = true)),
    SessionVisual.of("pending"),
    SessionVisual.of(null, SessionVisualFlags(starting = true)),
    SessionVisual.of("running"),
    SessionVisual.of("completed", SessionVisualFlags(unread = true)),
    SessionVisual.of("completed"),
    SessionVisual.of("aborted"),
    SessionVisual.of("idle"),
    SessionVisual.of("idle", SessionVisualFlags(pendingAction = PendingKind.Delete)),
)

@LightDarkPreviews
@Composable
private fun SessionStatusDotPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            previewVisuals.forEach { visual ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionStatusDot(visual)
                    Spacer(Modifier.width(Spacing.s))
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { SessionStatusIcon(visual) }
                    Spacer(Modifier.width(Spacing.s))
                    Text(visual.label(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun KernelStatusDotPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf(KernelVisual.Connected(25463), KernelVisual.Connecting, KernelVisual.Reconnecting, KernelVisual.Unavailable, KernelVisual.TermuxAsleep).forEach { visual ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KernelStatusDot(visual)
                    Spacer(Modifier.width(Spacing.s))
                    Column {
                        Text(visual.line1(), style = MaterialTheme.typography.titleSmall)
                        visual.line2()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
