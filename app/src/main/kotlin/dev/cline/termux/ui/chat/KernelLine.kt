package dev.cline.termux.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.ui.chat.rows.ChatTransportState

/*
 * home-chat.md §1.6: one line of kernel state for the chat group, derived from the coordinator's
 * [KernelGate] (docs/07 rule 7: the only kernel projection screens read) plus the socket state
 * of `HubClient` and its `draining` flag. It decides what Home's kernel notice says, which
 * transport line the thread shows (§3.9) and what the composer may do.
 *
 * | KernelGate                                  | Socket            | KernelLine   |
 * | Ready / DegradedNoCommands                  | Connected         | Connected (Draining while `draining`) |
 * | Ready / DegradedNoCommands                  | Connecting        | Connecting   |
 * | Ready / DegradedNoCommands                  | Reconnecting, Disconnected | Reconnecting |
 * | Ready / DegradedNoCommands                  | Unavailable       | Unavailable  |
 * | Busy(Starting | Connecting)                 | any               | Connecting   |
 * | Busy(Reconnecting | Restarting)             | any               | Reconnecting |
 * | Busy(Draining)                              | any               | Draining     |
 * | Down(Asleep | Stopped | StoppedInTermux | Disconnected) | any | TermuxAsleep (needs "Start kernel") |
 * | Down(StartFailed | CrashLoop | Protocol | Auth) | any           | Unavailable  |
 * | Blocked                                     | any               | Blocked: the app routes to Kernel setup, nothing shows here |
 */

/** What the composer may do under a [KernelLine] (§1.6 last column). */
enum class ComposerAvailability {
    /** Connected: send works. */
    Enabled,

    /** Connecting: the field stays editable, send is disabled. */
    SendDisabled,

    /** Reconnecting: the field stays editable; a send waits in memory until the socket is back (§3.12 step 6). */
    QueueLocally,

    /** Hub down or unavailable: read-only; "Start kernel" replaces the settings row (DS §4.1 #6). */
    ReadOnly,

    /** A prerequisite is missing: the Kernel setup screen is shown instead. */
    Hidden,
}

/** Home's kernel notice (§1.6 "Home (A)" column): line 1 and the optional `error`-coloured line 2. */
@Immutable
data class HomeKernelNotice(@param:StringRes val line1: Int, @param:StringRes val line2: Int? = null)

enum class KernelLine {
    Connected,
    Connecting,
    Reconnecting,

    /** `hub_draining` / `hub.drain_changed {draining:true}`: "Cline Hub is updating..." (§3.9). */
    Draining,

    /** The Hub is down and the user must start it ("Start kernel to continue."). */
    TermuxAsleep,

    /** The Hub failed or refused the app ("Cline Hub @unknown" / "Cline Hub is not connected."). */
    Unavailable,

    /** Termux missing, permission missing, port too old: routed to Kernel setup before the chat. */
    Blocked,
    ;

    /** The thread's transport line state (§3.9); [ChatTransportState.Connected] hides it. */
    val transport: ChatTransportState
        get() = when (this) {
            Connected, Blocked -> ChatTransportState.Connected
            Connecting -> ChatTransportState.Connecting
            Reconnecting -> ChatTransportState.Reconnecting
            Draining -> ChatTransportState.Draining
            TermuxAsleep -> ChatTransportState.TermuxAsleep
            Unavailable -> ChatTransportState.Unavailable
        }

    val composer: ComposerAvailability
        get() = when (this) {
            // Draining: the store holds the send and resends on `draining:false` (§3.12 step 5).
            Connected, Draining -> ComposerAvailability.Enabled
            Connecting -> ComposerAvailability.SendDisabled
            Reconnecting -> ComposerAvailability.QueueLocally
            TermuxAsleep, Unavailable -> ComposerAvailability.ReadOnly
            Blocked -> ComposerAvailability.Hidden
        }

    /** "Start kernel" is offered (composer row, Home notice action). */
    val offersStartKernel: Boolean get() = this == TermuxAsleep || this == Unavailable

    /** Home's kernel notice; null when connected (nothing shows) or blocked (setup screen instead). */
    val homeNotice: HomeKernelNotice?
        get() = when (this) {
            Connected, Blocked -> null
            Connecting -> HomeKernelNotice(R.string.components_kernel_connecting)
            Reconnecting -> HomeKernelNotice(R.string.components_kernel_reconnecting)
            Draining -> HomeKernelNotice(R.string.chat_transport_draining)
            TermuxAsleep -> HomeKernelNotice(R.string.components_kernel_asleep, R.string.components_kernel_asleep_detail)
            Unavailable -> HomeKernelNotice(R.string.components_kernel_unavailable, R.string.components_kernel_unavailable_detail)
        }

    companion object {
        /** §1.6 mapping; see the table at the top of this file. */
        fun of(gate: KernelGate, socket: HubConnectionState, draining: Boolean): KernelLine = when (gate) {
            is KernelGate.Blocked -> Blocked
            is KernelGate.Down -> when (gate.variant) {
                KernelGate.DownVariant.Asleep,
                KernelGate.DownVariant.Stopped,
                KernelGate.DownVariant.StoppedInTermux,
                KernelGate.DownVariant.Disconnected,
                -> TermuxAsleep
                KernelGate.DownVariant.StartFailed,
                KernelGate.DownVariant.CrashLoop,
                KernelGate.DownVariant.Protocol,
                KernelGate.DownVariant.Auth,
                -> Unavailable
            }
            is KernelGate.Busy -> when (gate.kind) {
                KernelGate.BusyKind.Starting, KernelGate.BusyKind.Connecting -> Connecting
                KernelGate.BusyKind.Reconnecting, KernelGate.BusyKind.Restarting -> Reconnecting
                KernelGate.BusyKind.Draining -> Draining
            }
            KernelGate.Ready, KernelGate.DegradedNoCommands -> when (socket) {
                is HubConnectionState.Connected -> if (draining) Draining else Connected
                HubConnectionState.Connecting -> Connecting
                is HubConnectionState.Reconnecting, HubConnectionState.Disconnected -> Reconnecting
                is HubConnectionState.Unavailable -> Unavailable
            }
        }
    }
}
