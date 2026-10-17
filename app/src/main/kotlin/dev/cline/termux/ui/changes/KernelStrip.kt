package dev.cline.termux.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.StatusDot
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.busyLabel
import dev.cline.termux.ui.kernel.label
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.Spacing

/**
 * The kernel connection as Changes and Tool detail see it (CT A.8 / B.8 `kernel`): the
 * [KernelGate] every screen binds to (docs/07 rule 7) plus the last known Hub port for the
 * DS §1.6 line "Cline Hub @25463".
 */
@Immutable
data class KernelConnectionVisual(val gate: KernelGate, val hubPort: Int? = null) {
    /** The Hub answers commands: no strip, actions enabled. A draining Hub still answers reads. */
    val connected: Boolean
        get() = when (gate) {
            KernelGate.Ready, KernelGate.DegradedNoCommands -> true
            is KernelGate.Busy -> gate.kind == KernelGate.BusyKind.Draining
            else -> false
        }

    /** Down or blocked: nothing will connect without the user ("Start kernel"). */
    val down: Boolean get() = gate is KernelGate.Down || gate is KernelGate.Blocked

    /** The strip's action (F.2 primary of the down variant); null while busy. */
    val action: KernelAction? get() = KernelCopy.primary(gate)

    companion object {
        val Connected = KernelConnectionVisual(KernelGate.Ready, null)
    }
}

/**
 * DS-A2 **KernelStrip** (CT A.4, B.4): a `surfaceContainerHigh` row in `shapes.large` with
 * `heightIn(min = 48dp)`, the DS §1.6 kernel dot, line 1 / line 2 of the kernel-connection
 * table and a trailing `TextButton` with the down variant's action ("Start kernel"). The
 * content is a `FlowRow`, so at large font scales the button wraps under the text. Nothing
 * renders while the kernel is connected. The lines come from [KernelCopy.chip], the same
 * words as the drawer's KernelChip; "Termux asleep" gains line 2 "Start kernel to continue."
 * (DS §1.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KernelStrip(
    kernel: KernelConnectionVisual,
    onAction: (KernelAction) -> Unit,
    modifier: Modifier = Modifier,
    actionInFlight: KernelAction? = null,
) {
    if (kernel.connected) return
    val chip = KernelCopy.chip(kernel.gate, kernel.hubPort)
    val line1 = chip.line1.resolve()
    val asleep = (kernel.gate as? KernelGate.Down)?.variant == KernelGate.DownVariant.Asleep
    val line2 = when {
        chip.line2 != null -> chip.line2.resolve()
        asleep -> stringResource(R.string.changes_kernel_start_to_continue)
        // DS §1.6 "unavailable": the strip always explains a down or blocked kernel in
        // words (CT A.11 "Cline Hub is not connected."), even where the drawer chip is one line.
        kernel.gate is KernelGate.Down || kernel.gate is KernelGate.Blocked -> stringResource(R.string.hub_not_connected)
        else -> null
    }
    val spoken = if (line2 != null) stringResource(R.string.changes_kernel_strip_lines, line1, line2) else line1
    val description = stringResource(R.string.changes_kernel_strip_a11y, spoken)
    val action = kernel.action
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .padding(end = Spacing.s)
                    .semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                StatusDot(tone = chip.tone, pulsing = chip.pulsing, boxSize = 24.dp)
                Column(Modifier.padding(start = Spacing.s)) {
                    Text(line1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (line2 != null) {
                        Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (action != null) {
                val busy = actionInFlight == action
                TextButton(onClick = { onAction(action) }, enabled = !busy) {
                    if (busy) {
                        InlineSpinner()
                        Text(action.busyLabel().resolve(), Modifier.padding(start = Spacing.s))
                    } else {
                        Text(action.label().resolve())
                    }
                }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun KernelStripPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            KernelStrip(KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.StartFailed, null)), onAction = {})
            KernelStrip(KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Asleep, null)), onAction = {})
            KernelStrip(
                KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Stopped, null)),
                onAction = {},
                actionInFlight = KernelAction.StartKernel,
            )
            KernelStrip(KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), 25463), onAction = {})
            KernelStrip(
                KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Restarting, KernelGate.BusyLabel.Phase(RestartPhase.Replacing), false)),
                onAction = {},
            )
        }
    }
}
