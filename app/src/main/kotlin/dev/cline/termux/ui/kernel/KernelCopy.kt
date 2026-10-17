package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubSessionActivity
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelGate.BusyKind
import dev.cline.termux.core.kernel.KernelGate.BusyLabel
import dev.cline.termux.core.kernel.KernelGate.DownVariant
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.StartFailure
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.data.session.DotTone

/**
 * The F.2 copy table of onboarding-kernel.md as pure data: one projection from [KernelGate]
 * to every kernel surface (KernelStatePanel, KernelBanner, KernelChip, the composer's
 * read-only row). Screens never pick kernel copy themselves; they render these models.
 */

/** What a kernel surface's button does. Hosts map each to a coordinator call or a navigation. */
enum class KernelAction {
    /** `kernel.ensureConnected(startIfDown = true, userInitiated = true)`. */
    StartKernel,

    /** Same call as [StartKernel], worded "Try again" after a failure (F.2). */
    TryAgain,

    /** Undo "Disconnect": `ensureConnected(startIfDown = false, userInitiated = true)`. */
    Connect,

    /** The E.9 restart flow (reopens the Hub update dialog when it was dismissed). */
    RestartKernel,

    /** Opens the Kernel status sheet (E). */
    KernelStatus,

    /** Opens the Kernel status sheet scrolled to its diagnostics block (E.6). */
    KernelStatusDiagnostics,

    /** Opens the "Keep the kernel running" checklist (D, item 4). */
    WhyDidThisHappen,

    /** Opens the Kernel status sheet from the draining banner. */
    Details,

    /** Opens the pushed repair variant (A.3) for the degraded banner. */
    Fix,
}

/** The label of an action button (E.5, F.2). */
fun KernelAction.label(): UiText = UiText.Res(
    when (this) {
        KernelAction.StartKernel -> R.string.kernel_start
        KernelAction.TryAgain -> R.string.kernel_try_again
        KernelAction.Connect -> R.string.kernel_connect
        KernelAction.RestartKernel -> R.string.kernel_restart
        KernelAction.KernelStatus, KernelAction.KernelStatusDiagnostics -> R.string.state_kernel_status
        KernelAction.WhyDidThisHappen -> R.string.state_asleep_why
        KernelAction.Details -> R.string.state_details
        KernelAction.Fix -> R.string.banner_fix
    },
)

/** The busy label a primary shows while its call runs (E.5: "Start kernel" → "Starting…"). */
fun KernelAction.busyLabel(): UiText = when (this) {
    KernelAction.RestartKernel -> UiText.Res(R.string.kernel_restarting)
    else -> UiText.Res(R.string.kernel_starting)
}

/** Panel icons (F.2 "Panel icon"); [Progress] is the 40dp indeterminate indicator. */
enum class KernelPanelIcon { Bedtime, StopCircle, LinkOff, Error, Progress }

/** One [KernelStatePanel][dev.cline.termux.ui.components.KernelStatePanel] variant. */
data class KernelPanelModel(
    val icon: KernelPanelIcon,
    val title: UiText,
    val body: List<UiText>,
    /** Monospace detail block (stderr tail, timeout sentence); kernel text is [UiText.Raw]. */
    val detail: UiText?,
    val primary: KernelAction?,
    val secondary: KernelAction?,
) {
    /** Panel action rule (0.7): with a composer under it, only the secondary is shown. */
    fun actions(hasComposer: Boolean): Pair<KernelAction?, KernelAction?> =
        if (hasComposer) null to secondary else primary to secondary
}

/** One [KernelBanner][dev.cline.termux.ui.components.KernelBanner] variant (0.7, F.2). */
data class KernelBannerModel(
    /** A 16dp spinner replaces the dot. */
    val busy: Boolean,
    val tone: DotTone?,
    val pulsing: Boolean,
    val line1: UiText,
    val line2: UiText?,
    /** Line 2 in `error` (down variants) instead of `onSurfaceVariant`. */
    val line2Error: Boolean,
    val action: KernelAction,
    /** F.4 precedence, higher wins when a host has more than one banner source. */
    val precedence: Int,
    /** F.4: reconnects shorter than this never show a banner. */
    val showAfterMs: Long,
)

/** KernelChip content (F.2 "Chip line 1 / dot"); line 2 only for the start-failed variant. */
data class KernelChipModel(
    val tone: DotTone,
    val pulsing: Boolean,
    val line1: UiText,
    val line2: UiText?,
)

/** The composer's settings row while the kernel is not usable (F.3, 0.10, CA C1.6). */
sealed interface ComposerKernelRow {
    /** Kernel usable: the normal settings row. */
    data object Normal : ComposerKernelRow

    /** Busy variants: a 16dp spinner + chip line 1, no button. */
    data class Busy(val line1: UiText) : ComposerKernelRow

    /** Down variants: 8dp `outline` dot + chip line 1 + end-aligned F.2 primary ([action] null for Blocked). */
    data class Down(val line1: UiText, val action: KernelAction?) : ComposerKernelRow
}

object KernelCopy {
    /** F.4: the reconnect debounce. */
    const val RECONNECT_BANNER_DELAY_MS = 1_500L

    /** "Cline Hub @25463", or "@unknown" without a port. */
    fun hubLine(port: Int?): UiText =
        if (port == null) UiText.Res(R.string.hub_at_unknown) else UiText.Res(R.string.hub_at_port, port.toString())

    /** `kernel_step_*` for a start step. */
    fun stepLabel(step: StartStep): UiText = UiText.Res(
        when (step) {
            StartStep.CheckingTermux -> R.string.kernel_step_checking_termux
            StartStep.CheckingKernel -> R.string.kernel_step_checking_kernel
            StartStep.StartingHub -> R.string.kernel_step_starting_hub
            StartStep.KeepingAwake -> R.string.kernel_step_keeping_awake
            StartStep.ReadingCredentials -> R.string.kernel_step_reading_credentials
            StartStep.Connecting -> R.string.kernel_step_connecting
        },
    )

    /** `kernel_restart_phase_*` for a restart phase (E.7). */
    fun phaseLabel(phase: RestartPhase): UiText = UiText.Res(
        when (phase) {
            RestartPhase.CheckingSessions -> R.string.kernel_restart_phase_checking
            RestartPhase.Disconnecting -> R.string.kernel_restart_phase_disconnecting
            RestartPhase.Replacing -> R.string.kernel_restart_phase_replacing
            RestartPhase.Starting -> R.string.kernel_restart_phase_starting
            RestartPhase.Reconnecting -> R.string.kernel_restart_phase_reconnecting
        },
    )

    fun busyLabel(label: BusyLabel?): UiText? = when (label) {
        is BusyLabel.Step -> stepLabel(label.step)
        is BusyLabel.Phase -> phaseLabel(label.phase)
        null -> null
    }

    /** The KS §0.4 sentence for an app-detected start failure. */
    fun failureText(failure: StartFailure): UiText = UiText.Res(
        when (failure) {
            StartFailure.CredentialsUnreadable -> R.string.kernel_failure_credentials_unreadable
            StartFailure.HandshakeRefused -> R.string.kernel_failure_handshake
        },
    )

    /**
     * The start-failed detail block (F.2): the app's own sentence for [StartFailure], the
     * 60 s timeout sentence for `StartTimedOut`, else the kernel's stderr tail as data. The
     * gate drops both distinctions, so hosts that have the state pass this as `detail`.
     */
    fun downDetail(state: KernelState): UiText? {
        val down = state as? KernelState.HubDown ?: return null
        down.failure?.let { return failureText(it) }
        if (down.reason == DownReason.StartTimedOut) return UiText.Res(R.string.kernel_failure_timed_out)
        return down.lastError?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }
    }

    /** The detail a surface shows for [gate] when the host has no state-derived [downDetail]. */
    fun gateDetail(gate: KernelGate): UiText? =
        (gate as? KernelGate.Down)?.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }

    // ---- KernelStatePanel ------------------------------------------------------------------

    /** F.2 panel, or null for variants that keep the content (Reconnecting, Restarting, Draining, Degraded, Ready, Blocked). */
    fun panel(gate: KernelGate, detail: UiText? = gateDetail(gate)): KernelPanelModel? = when (gate) {
        is KernelGate.Down -> when (gate.variant) {
            DownVariant.Asleep -> KernelPanelModel(
                KernelPanelIcon.Bedtime, UiText.Res(R.string.kernel_status_asleep),
                listOf(UiText.Res(R.string.state_asleep_body)), null,
                KernelAction.StartKernel, KernelAction.WhyDidThisHappen,
            )
            DownVariant.Stopped -> KernelPanelModel(
                KernelPanelIcon.StopCircle, UiText.Res(R.string.kernel_status_stopped),
                listOf(UiText.Res(R.string.state_stopped_body)), null,
                KernelAction.StartKernel, null,
            )
            DownVariant.StoppedInTermux -> KernelPanelModel(
                KernelPanelIcon.StopCircle, UiText.Res(R.string.kernel_status_stopped),
                listOf(UiText.Res(R.string.state_stopped_in_termux_body)), null,
                KernelAction.StartKernel, KernelAction.KernelStatus,
            )
            DownVariant.Disconnected -> KernelPanelModel(
                KernelPanelIcon.LinkOff, UiText.Res(R.string.kernel_status_disconnected),
                listOf(UiText.Res(R.string.state_disconnected_body)), null,
                KernelAction.Connect, null,
            )
            DownVariant.StartFailed, DownVariant.CrashLoop -> KernelPanelModel(
                KernelPanelIcon.Error, UiText.Res(R.string.state_failed_title),
                buildList {
                    add(UiText.Res(R.string.hub_not_connected))
                    if (gate.variant == DownVariant.CrashLoop) add(UiText.Res(R.string.state_crash_loop))
                },
                detail,
                KernelAction.TryAgain, KernelAction.KernelStatusDiagnostics,
            )
            DownVariant.Protocol -> KernelPanelModel(
                KernelPanelIcon.Error, UiText.Res(R.string.state_connect_failed_title),
                listOf(UiText.Res(R.string.kernel_protocol_unsupported)), null,
                KernelAction.RestartKernel, KernelAction.KernelStatus,
            )
            DownVariant.Auth -> KernelPanelModel(
                KernelPanelIcon.Error, UiText.Res(R.string.state_connect_failed_title),
                listOf(UiText.Res(R.string.kernel_auth_failed)), null,
                KernelAction.TryAgain, KernelAction.KernelStatus,
            )
        }
        is KernelGate.Busy -> when (gate.kind) {
            BusyKind.Starting, BusyKind.Connecting -> KernelPanelModel(
                KernelPanelIcon.Progress, UiText.Res(R.string.kernel_status_connecting),
                buildList {
                    busyLabel(gate.stepLabel)?.let(::add)
                    if (gate.slow) add(UiText.Res(R.string.kernel_slow))
                },
                null, null, KernelAction.KernelStatus,
            )
            BusyKind.Reconnecting, BusyKind.Restarting, BusyKind.Draining -> null
        }
        KernelGate.Ready, KernelGate.DegradedNoCommands, is KernelGate.Blocked -> null
    }

    // ---- KernelBanner ----------------------------------------------------------------------

    /**
     * F.2 banner for [gate]; null when no banner shows (Ready, Restarting, Blocked: the
     * blocking repair replaces the shell). [hasComposer] picks the trailing action (0.7):
     * "Kernel status" where the composer's read-only row carries the primary, else the
     * variant's primary.
     */
    fun banner(gate: KernelGate, hasComposer: Boolean): KernelBannerModel? {
        fun down(line1: UiText, line2: UiText?, primary: KernelAction, precedence: Int) = KernelBannerModel(
            busy = false, tone = DotTone.Outline, pulsing = false, line1 = line1, line2 = line2,
            line2Error = line2 != null, action = if (hasComposer) KernelAction.KernelStatus else primary,
            precedence = precedence, showAfterMs = 0L,
        )
        return when (gate) {
            is KernelGate.Down -> when (gate.variant) {
                DownVariant.Asleep -> down(
                    UiText.Res(R.string.kernel_status_asleep), UiText.Res(R.string.kernel_status_asleep_line2),
                    KernelAction.StartKernel, PRECEDENCE_ASLEEP,
                )
                DownVariant.Stopped, DownVariant.StoppedInTermux -> down(
                    UiText.Res(R.string.banner_stopped), UiText.Res(R.string.hub_not_connected),
                    KernelAction.StartKernel, PRECEDENCE_STOPPED,
                )
                DownVariant.Disconnected -> down(
                    UiText.Res(R.string.banner_disconnected), null, KernelAction.Connect, PRECEDENCE_DISCONNECTED,
                )
                DownVariant.StartFailed, DownVariant.CrashLoop -> down(
                    UiText.Res(R.string.hub_at_unknown), UiText.Res(R.string.hub_not_connected),
                    KernelAction.TryAgain, PRECEDENCE_FAILED,
                )
                DownVariant.Protocol -> down(
                    UiText.Res(R.string.state_connect_failed_title), UiText.Res(R.string.kernel_protocol_unsupported),
                    KernelAction.RestartKernel, PRECEDENCE_FAILED,
                )
                DownVariant.Auth -> down(
                    UiText.Res(R.string.state_connect_failed_title), UiText.Res(R.string.kernel_auth_failed),
                    KernelAction.TryAgain, PRECEDENCE_FAILED,
                )
            }
            is KernelGate.Busy -> when (gate.kind) {
                BusyKind.Starting, BusyKind.Connecting -> KernelBannerModel(
                    busy = true, tone = DotTone.Warning, pulsing = true,
                    line1 = UiText.Res(R.string.kernel_status_connecting), line2 = busyLabel(gate.stepLabel),
                    line2Error = false, action = KernelAction.KernelStatus,
                    precedence = PRECEDENCE_STARTING, showAfterMs = 0L,
                )
                BusyKind.Reconnecting -> KernelBannerModel(
                    busy = true, tone = DotTone.Warning, pulsing = true,
                    line1 = UiText.Res(R.string.kernel_status_reconnecting), line2 = null,
                    line2Error = false, action = KernelAction.KernelStatus,
                    precedence = PRECEDENCE_RECONNECTING, showAfterMs = RECONNECT_BANNER_DELAY_MS,
                )
                BusyKind.Draining -> KernelBannerModel(
                    busy = false, tone = DotTone.Warning, pulsing = false,
                    line1 = UiText.Res(R.string.kernel_draining_note), line2 = null,
                    line2Error = false, action = KernelAction.Details,
                    precedence = PRECEDENCE_DRAINING, showAfterMs = 0L,
                )
                // 0.3: an intended restart is not an outage.
                BusyKind.Restarting -> null
            }
            KernelGate.DegradedNoCommands -> KernelBannerModel(
                busy = false, tone = DotTone.Success, pulsing = false,
                line1 = UiText.Res(R.string.banner_degraded), line2 = UiText.Res(R.string.banner_degraded_line2),
                line2Error = false, action = KernelAction.Fix,
                precedence = PRECEDENCE_DEGRADED, showAfterMs = 0L,
            )
            KernelGate.Ready, is KernelGate.Blocked -> null
        }
    }

    // ---- KernelChip ------------------------------------------------------------------------

    /** F.2 chip for [gate]; Blocked reads like E.5's prerequisites row ("Cline Hub @unknown"). */
    fun chip(gate: KernelGate, port: Int?): KernelChipModel = when (gate) {
        KernelGate.Ready -> KernelChipModel(DotTone.Success, false, hubLine(port), null)
        KernelGate.DegradedNoCommands -> KernelChipModel(DotTone.Success, false, hubLine(port), null)
        is KernelGate.Busy -> when (gate.kind) {
            BusyKind.Starting, BusyKind.Connecting -> KernelChipModel(DotTone.Warning, true, UiText.Res(R.string.kernel_status_connecting), null)
            BusyKind.Reconnecting -> KernelChipModel(DotTone.Warning, true, UiText.Res(R.string.kernel_status_reconnecting), null)
            BusyKind.Restarting -> KernelChipModel(DotTone.Warning, true, UiText.Res(R.string.kernel_status_restarting), null)
            BusyKind.Draining -> KernelChipModel(DotTone.Warning, false, hubLine(port), null)
        }
        is KernelGate.Down -> when (gate.variant) {
            DownVariant.Asleep -> KernelChipModel(DotTone.Outline, false, UiText.Res(R.string.kernel_status_asleep), null)
            DownVariant.Stopped, DownVariant.StoppedInTermux -> KernelChipModel(DotTone.Outline, false, hubLine(null), null)
            DownVariant.StartFailed, DownVariant.CrashLoop ->
                KernelChipModel(DotTone.Outline, false, hubLine(null), UiText.Res(R.string.hub_not_connected))
            DownVariant.Disconnected, DownVariant.Protocol, DownVariant.Auth -> KernelChipModel(DotTone.Outline, false, hubLine(port), null)
        }
        is KernelGate.Blocked -> KernelChipModel(DotTone.Outline, false, hubLine(null), null)
    }

    /** The F.2 primary of a down variant (the composer row's and the drawer header's button). */
    fun primary(gate: KernelGate): KernelAction? = when (gate) {
        is KernelGate.Down -> when (gate.variant) {
            DownVariant.Asleep, DownVariant.Stopped, DownVariant.StoppedInTermux -> KernelAction.StartKernel
            DownVariant.Disconnected -> KernelAction.Connect
            DownVariant.StartFailed, DownVariant.CrashLoop, DownVariant.Auth -> KernelAction.TryAgain
            DownVariant.Protocol -> KernelAction.RestartKernel
        }
        else -> null
    }

    // ---- other helpers ---------------------------------------------------------------------

    /**
     * `describeOutdatedHubSessions` with plurals (E.7): "1 active session from 2 connected
     * Cline clients", "2 active sessions", fallback "active sessions from other Cline clients".
     */
    fun describeSessions(activity: HubSessionActivity): UiText {
        val sessions = activity.activeSessionCount
        val clients = activity.participantClientCount
        if (sessions <= 0) return UiText.Res(R.string.hub_sessions_fallback)
        val sessionText = UiText.Plural(R.plurals.hub_sessions_count, sessions, listOf(sessions))
        if (clients <= 0) return sessionText
        return UiText.Plural(R.plurals.hub_sessions_from_clients, clients, listOf(sessionText, clients))
    }

    /** "Cline kernel connected" is announced when the gate returns to Ready from a banner variant (F.6). */
    fun connectedAnnouncement(previous: KernelGate?, current: KernelGate): UiText? =
        if (current == KernelGate.Ready && previous != null && previous != KernelGate.Ready && previous !is KernelGate.Blocked) {
            UiText.Res(R.string.kernel_connected_announce)
        } else {
            null
        }

    const val PRECEDENCE_DEGRADED = 1
    const val PRECEDENCE_DRAINING = 2
    const val PRECEDENCE_RECONNECTING = 3
    const val PRECEDENCE_STARTING = 4
    const val PRECEDENCE_DISCONNECTED = 5
    const val PRECEDENCE_STOPPED = 6
    const val PRECEDENCE_ASLEEP = 7
    const val PRECEDENCE_FAILED = 8
}
