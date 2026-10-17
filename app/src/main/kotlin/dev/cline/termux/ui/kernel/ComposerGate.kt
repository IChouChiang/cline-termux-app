package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelGate.BusyKind
import dev.cline.termux.core.kernel.KernelGate.DownVariant

/**
 * What the kernel allows the composer to do (composer-approvals.md §0.3 and C1.6, bound to
 * [KernelGate] by onboarding-kernel.md 0.10 and F.3; D12 replaces `KernelAvailability`).
 *
 * - Ready / degraded: the composer works normally (a degraded kernel still has the Hub).
 * - Draining: sends are held by the send pipeline until `hub.drain_changed` (C1.6 "Held for
 *   Hub restart"), so the composer stays fully usable.
 * - Starting / Connecting / Reconnecting / Restarting: the draft stays editable, Send is
 *   disabled, and the settings row shows a 16dp spinner + the chip line 1 (0.10 `Busy(*)`).
 * - Down: the field is read-only with the F.3 placeholder; the row shows the `outline` dot,
 *   the chip line 1 and the F.2 primary, the only place that primary appears on Home and Chat.
 * - Blocked: read-only, placeholder = the repair title, no button (the blocking repair
 *   replaces the shell).
 */
data class ComposerGate(
    /** The field keeps its text but takes no input (DS §4.1 #6 readOnly, never disabled). */
    val readOnly: Boolean,
    /** The kernel allows a send right now (combined with the composer's own conditions). */
    val kernelAllowsSend: Boolean,
    /** Replaces the composer's own placeholder while non-null. */
    val placeholder: UiText?,
    val row: ComposerKernelRow,
) {
    companion object {
        val Usable = ComposerGate(readOnly = false, kernelAllowsSend = true, placeholder = null, row = ComposerKernelRow.Normal)

        fun of(gate: KernelGate, port: Int?): ComposerGate = when (gate) {
            KernelGate.Ready, KernelGate.DegradedNoCommands -> Usable
            is KernelGate.Busy -> when (gate.kind) {
                BusyKind.Draining -> Usable
                BusyKind.Starting, BusyKind.Connecting, BusyKind.Reconnecting, BusyKind.Restarting -> ComposerGate(
                    readOnly = false,
                    kernelAllowsSend = false,
                    placeholder = null,
                    row = ComposerKernelRow.Busy(KernelCopy.chip(gate, port).line1),
                )
            }
            is KernelGate.Down -> ComposerGate(
                readOnly = true,
                kernelAllowsSend = false,
                placeholder = UiText.Res(
                    if (gate.variant == DownVariant.Asleep) R.string.kernel_status_asleep_line2 else R.string.hub_not_connected,
                ),
                row = ComposerKernelRow.Down(KernelCopy.chip(gate, port).line1, KernelCopy.primary(gate)),
            )
            is KernelGate.Blocked -> ComposerGate(
                readOnly = true,
                kernelAllowsSend = false,
                placeholder = UiText.Res(R.string.kernel_fix_kernel),
                row = ComposerKernelRow.Down(KernelCopy.chip(gate, port).line1, action = null),
            )
        }
    }
}

/**
 * The read-only row's button while its call runs (C1.6 "Starting kernel"): a 16dp spinner +
 * the busy label, disabled. [inFlight] is the host's own "tap pending" flag; it clears when the
 * gate leaves the down variant.
 */
data class ComposerKernelButton(val action: KernelAction, val inFlight: Boolean) {
    val label: UiText get() = if (inFlight) action.busyLabel() else action.label()
    val enabled: Boolean get() = !inFlight
}
