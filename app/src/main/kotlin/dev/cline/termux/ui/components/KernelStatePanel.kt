package dev.cline.termux.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.KernelPanelIcon
import dev.cline.termux.ui.kernel.KernelPanelModel
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.kernel.busyLabel
import dev.cline.termux.ui.kernel.label
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * KS 0.7 KernelStatePanel (desktop `app/page.tsx` boot gate, `ConversationEmptyState`): the
 * EmptyState layout with a 48dp icon (or a 40dp indicator for the busy variant), a
 * `heading()` title, the body, an optional monospace detail block and the button stack.
 * Max width 480dp, 24dp gutters, a polite live region so a variant change is announced.
 *
 * Action rule: [hasComposer] = a composer is composed under the panel (Chat with nothing
 * cached); the panel then shows only its secondary action, because the composer's read-only
 * row carries the primary.
 */
@Composable
fun KernelStatePanel(
    model: KernelPanelModel,
    hasComposer: Boolean,
    onAction: (KernelAction) -> Unit,
    modifier: Modifier = Modifier,
    primaryInFlight: Boolean = false,
) {
    val (primary, secondary) = model.actions(hasComposer)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val icon = model.icon.vector()) {
                null -> CircularProgressIndicator(Modifier.size(40.dp))
                else -> Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.l))
            Text(
                model.title.resolve(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            model.body.forEach { paragraph ->
                Spacer(Modifier.height(Spacing.s))
                Text(
                    paragraph.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            model.detail?.let { detail ->
                Spacer(Modifier.height(Spacing.l))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer(Modifier.horizontalScroll(rememberScrollState()).padding(Spacing.m)) {
                        LtrText(detail.resolve(), style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (primary != null || secondary != null) Spacer(Modifier.height(Spacing.xxl))
            if (primary != null) {
                Button(onClick = { onAction(primary) }, enabled = !primaryInFlight) {
                    if (primaryInFlight) {
                        InlineSpinner()
                        Spacer(Modifier.width(Spacing.s))
                        Text(primary.busyLabel().resolve())
                    } else {
                        Text(primary.label().resolve())
                    }
                }
            }
            if (secondary != null) {
                if (primary != null) Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = { onAction(secondary) }) { Text(secondary.label().resolve()) }
            }
        }
    }
}

/** Projects [gate] and renders its panel; nothing for variants that keep the content (F.2). */
@Composable
fun KernelStatePanel(
    gate: KernelGate,
    hasComposer: Boolean,
    onAction: (KernelAction) -> Unit,
    modifier: Modifier = Modifier,
    detail: UiText? = KernelCopy.gateDetail(gate),
    primaryInFlight: Boolean = false,
) {
    val model = KernelCopy.panel(gate, detail) ?: return
    KernelStatePanel(model, hasComposer, onAction, modifier, primaryInFlight)
}

@Composable
private fun KernelPanelIcon.vector(): ImageVector? = when (this) {
    KernelPanelIcon.Bedtime -> ClineIcons.Bedtime
    KernelPanelIcon.StopCircle -> ClineIcons.StopCircle
    KernelPanelIcon.LinkOff -> ClineIcons.LinkOff
    KernelPanelIcon.Error -> ClineIcons.Error
    KernelPanelIcon.Progress -> null
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelAsleepPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Asleep, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelAsleepWithComposerPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Asleep, null), hasComposer = true, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelStoppedPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Stopped, null), hasComposer = false, onAction = {}, primaryInFlight = true) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelStoppedInTermuxPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.StoppedInTermux, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelDisconnectedPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Disconnected, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelStartFailedPreview() {
    ComponentPreview {
        KernelStatePanel(
            KernelGate.Down(KernelGate.DownVariant.StartFailed, "Timed out after 15000ms waiting for detached hub startup."),
            hasComposer = false,
            onAction = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelCrashLoopPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.CrashLoop, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelProtocolPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Protocol, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelAuthPreview() {
    ComponentPreview { KernelStatePanel(KernelGate.Down(KernelGate.DownVariant.Auth, null), hasComposer = false, onAction = {}) }
}

@LightDarkPreviews
@Composable
private fun KernelStatePanelStartingSlowPreview() {
    ComponentPreview {
        KernelStatePanel(
            KernelGate.Busy(KernelGate.BusyKind.Starting, KernelGate.BusyLabel.Step(StartStep.StartingHub), slow = true),
            hasComposer = false,
            onAction = {},
        )
    }
}
