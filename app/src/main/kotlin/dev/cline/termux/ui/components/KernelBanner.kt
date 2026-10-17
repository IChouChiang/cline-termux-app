package dev.cline.termux.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.KernelBannerModel
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.busyLabel
import dev.cline.termux.ui.kernel.label
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * KS 0.7 KernelBanner (desktop `chat-messages.tsx` transport line and `agent-sidebar.tsx`
 * Hub card): `surfaceContainerHigh` + 1dp `outlineVariant`, one neutral treatment for every
 * variant. The two lines merge into one TalkBack node; the trailing action stays its own
 * button. [actionInFlight] turns the button into a spinner + busy label ("Starting…").
 */
@Composable
fun KernelBanner(
    model: KernelBannerModel,
    onAction: (KernelAction) -> Unit,
    modifier: Modifier = Modifier,
    actionInFlight: Boolean = false,
) {
    val line1 = model.line1.resolve()
    val line2 = model.line2?.resolve()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (model.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    StatusDot(tone = model.tone, pulsing = model.pulsing)
                }
            }
            Spacer(Modifier.width(Spacing.m))
            val description = if (line2 != null) stringResource(R.string.kernel_lines_cd, line1, line2) else line1
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.s)
                    .semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                Text(line1, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (line2 != null) {
                    Text(
                        line2,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (model.line2Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s))
            TextButton(onClick = { onAction(model.action) }, enabled = !actionInFlight, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                if (actionInFlight) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                    Text(model.action.busyLabel().resolve())
                } else {
                    Text(model.action.label().resolve())
                }
            }
        }
    }
}

/**
 * Where a surface hosts the banner (F.3, F.4): projects [gate] with [hasComposer] (Home and
 * Chat pass true, so the trailing action is "Kernel status"), debounces reconnects for
 * 1.5 s, animates in and out with the DS §6 disclosure motion (static under reduced
 * motion), announces line 1 once per variant change and "Cline kernel connected" when the
 * gate returns to Ready. [contentPadding] goes around the banner only while it shows, so a
 * host with no banner takes no space.
 */
@Composable
fun KernelBannerHost(
    gate: KernelGate,
    hasComposer: Boolean,
    onAction: (KernelAction) -> Unit,
    modifier: Modifier = Modifier,
    actionInFlight: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val target = remember(gate, hasComposer) { KernelCopy.banner(gate, hasComposer) }
    var shown by remember { mutableStateOf<KernelBannerModel?>(null) }
    var last by remember { mutableStateOf<KernelBannerModel?>(null) }
    var previousGate by remember { mutableStateOf<KernelGate?>(null) }
    val live = rememberLiveRegionState()
    val connected = KernelCopy.connectedAnnouncement(previousGate, gate)?.resolve()

    LaunchedEffect(target) {
        if (target == null) {
            shown = null
            return@LaunchedEffect
        }
        if (target.showAfterMs > 0 && shown == null) delay(target.showAfterMs)
        last = target
        shown = target
    }
    val shownLine1 = shown?.line1?.resolve()
    LaunchedEffect(shownLine1) { shownLine1?.let(live::announce) }
    LaunchedEffect(gate) {
        connected?.let(live::announce)
        previousGate = gate
    }

    val reduced = ClineTheme.reducedMotion
    Box(modifier) {
        AnimatedVisibility(
            visible = shown != null,
            enter = if (reduced) EnterTransition.None else expandVertically(tween(ClineMotion.Short4)) + fadeIn(tween(ClineMotion.Short4)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(ClineMotion.Short4)) + fadeOut(tween(ClineMotion.Short4)),
        ) {
            last?.let { KernelBanner(it, onAction, Modifier.padding(contentPadding), actionInFlight = actionInFlight) }
        }
        LiveRegionAnnouncer(live)
    }
}

@LightDarkPreviews
@Composable
private fun KernelBannerDownPreview() {
    ComponentPreview {
        Column {
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Asleep, null), hasComposer = true)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Asleep, null), hasComposer = false)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Stopped, null), hasComposer = false)!!, onAction = {}, actionInFlight = true)
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Disconnected, null), hasComposer = false)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.StartFailed, "exit 1"), hasComposer = false)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Protocol, null), hasComposer = false)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Down(KernelGate.DownVariant.Auth, null), hasComposer = true)!!, onAction = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun KernelBannerBusyPreview() {
    ComponentPreview {
        Column {
            KernelBanner(
                KernelCopy.banner(
                    KernelGate.Busy(KernelGate.BusyKind.Starting, KernelGate.BusyLabel.Step(StartStep.StartingHub), slow = false),
                    hasComposer = true,
                )!!,
                onAction = {},
            )
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), hasComposer = true)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.Busy(KernelGate.BusyKind.Draining, null, false), hasComposer = false)!!, onAction = {})
            Spacer(Modifier.size(Spacing.s))
            KernelBanner(KernelCopy.banner(KernelGate.DegradedNoCommands, hasComposer = true)!!, onAction = {})
        }
    }
}
