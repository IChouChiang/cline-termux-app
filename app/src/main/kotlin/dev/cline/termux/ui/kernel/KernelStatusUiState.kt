package dev.cline.termux.ui.kernel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.formatUptime
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.toGate
import dev.cline.termux.data.session.DotTone
import java.time.Instant

/**
 * Everything the Kernel status sheet (onboarding-kernel.md E) can do. [Start], [TryAgain]
 * and [Connect] are `ensureConnected`; [Restart] is the E.7 flow (E.9's dialog in
 * `ProtocolUnsupported`); [FixKernel] pushes the repair variant; the rest are the E.4 rows.
 */
enum class KernelStatusAction { Start, TryAgain, Connect, Restart, FixKernel, OpenTermux, RunDiagnostics, Disconnect, Stop }

/** One label/value row of E.5 (value is data: versions, pids, ids). */
@Immutable
data class StatusRow(@param:StringRes val label: Int, val value: UiText, val detail: UiText? = null)

/** E.5 update row: "Restart to use <release>". */
@Immutable
data class UpdateRowUi(val release: String)

/** The confirm dialogs of E.7 (Fix processes asks inside the diagnostics block). */
sealed interface StatusConfirm {
    val text: UiText

    /** [forced]: other clients hold sessions (or the count is unknown), so `hub stop` + `hub start`. */
    data class Restart(override val text: UiText, val forced: Boolean) : StatusConfirm

    data class Stop(override val text: UiText) : StatusConfirm
}

/** E.8 `KernelStatusUiState`. Never carries the Hub token or any key (0.6). */
@Immutable
data class KernelStatusUiState(
    val appVersion: String?,
    val dot: DotTone,
    val dotPulsing: Boolean,
    /** "Cline Hub @25463" / "Cline Hub @unknown". */
    val hubLine: UiText,
    /** The port as TalkBack reads it ("25463" or "unknown"). */
    val hubPortSpoken: String?,
    /** Second header line, `error` colour. */
    val errorLine: UiText?,
    val statusLabel: UiText,
    /** Under the status value: the start step, restart phase or the draining note. */
    val statusDetail: UiText?,
    val rows: List<StatusRow>,
    val moreRows: List<StatusRow>,
    val updateRow: UpdateRowUi?,
    val keepAwake: Boolean,
    val keepAwakeEnabled: Boolean,
    /** "Couldn't take Termux's wake lock: %1$s". */
    val wakeLockError: UiText?,
    val reliability: ReliabilitySummary?,
    val primary: KernelStatusAction?,
    /** No primary in this state but work is in progress: a 16dp spinner + [statusDetail] in its place. */
    val primarySpinner: Boolean,
    val rowActions: List<KernelStatusAction>,
    /** Which action shows its busy label. */
    val busy: KernelStatusAction?,
    /** Every action is disabled (a call runs, or the coordinator is restarting). */
    val actionsLocked: Boolean,
    val restartPhase: RestartPhase?,
    /** Inline error under the primary (E.7 outcome table, E.10), assertive. */
    val actionError: List<UiText>,
    val diagnostics: DiagnosticsUi,
    val fixing: Boolean,
    val confirm: StatusConfirm?,
    /** The checklist row the "Fix the Cline kernel" primary focuses. */
    val repairFocus: CheckId?,
) {
    fun enabled(action: KernelStatusAction): Boolean = !actionsLocked || busy == action
}

/** What the projection needs besides the ViewModel's own transient state. */
data class StatusInputs(
    val state: KernelState,
    val commandsAvailable: Boolean = true,
    /** The coordinator's last known Hub (kept through `HubDown` for "@port"). */
    val lastInfo: HubInfo? = null,
    val portVersion: PortVersion? = null,
    val hubLifetimeRelease: String? = null,
    val keepAwake: Boolean = true,
    /** Kernel text of the last failed `termux-wake-lock` (coordinator). */
    val wakeLockError: String? = null,
    val reliability: ReliabilitySummary? = null,
    val nowWallMs: Long = 0L,
    val appVersion: String? = null,
)

/** The ViewModel's own state layered over the projection. */
data class StatusLocal(
    val busy: KernelStatusAction? = null,
    val confirm: StatusConfirm? = null,
    val actionError: List<UiText> = emptyList(),
    /** The switch value while its call runs (optimistic, E.7). */
    val keepAwakeOverride: Boolean? = null,
    /** The switch's own failure (rolled back). */
    val wakeLockFailure: UiText? = null,
    val diagnostics: DiagnosticsUi = DiagnosticsUi.Idle,
    val fixing: Boolean = false,
)

/**
 * The E.5 table as a pure function of [StatusInputs] and [StatusLocal]: header, status value,
 * detail rows, the one primary and the ordered action rows for every [KernelState].
 */
object KernelStatusProjection {

    fun project(inputs: StatusInputs, local: StatusLocal = StatusLocal()): KernelStatusUiState {
        val state = inputs.state
        val gate = state.toGate(0L, inputs.commandsAvailable)
        val chip = KernelCopy.chip(gate, null)
        val port = port(state, inputs.lastInfo)
        val restarting = state is KernelState.Restarting
        val diagnosticsBusy = local.diagnostics == DiagnosticsUi.Loading || local.fixing
        val busy = local.busy ?: if (local.diagnostics == DiagnosticsUi.Loading) KernelStatusAction.RunDiagnostics else null
        val primary = primary(state, inputs.commandsAvailable)
        return KernelStatusUiState(
            appVersion = inputs.appVersion,
            dot = chip.tone,
            dotPulsing = chip.pulsing,
            hubLine = KernelCopy.hubLine(port),
            hubPortSpoken = port?.toString(),
            errorLine = errorLine(state, inputs.commandsAvailable),
            statusLabel = statusLabel(state),
            statusDetail = statusDetail(state, inputs.commandsAvailable),
            rows = rows(inputs),
            moreRows = moreRows(inputs),
            updateRow = updateRow(inputs),
            keepAwake = local.keepAwakeOverride ?: inputs.keepAwake,
            keepAwakeEnabled = state is KernelState.Ready && inputs.commandsAvailable && local.keepAwakeOverride == null && !restarting,
            wakeLockError = local.wakeLockFailure?.let { UiText.Res(R.string.kernel_keep_awake_failed, it) }
                ?: inputs.wakeLockError?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Res(R.string.kernel_keep_awake_failed, it) },
            reliability = inputs.reliability,
            primary = primary,
            primarySpinner = primary == null && isWorking(state),
            rowActions = rowActions(state, inputs.commandsAvailable),
            busy = busy,
            actionsLocked = busy != null || restarting || diagnosticsBusy || local.confirm != null,
            restartPhase = (state as? KernelState.Restarting)?.phase,
            actionError = local.actionError,
            diagnostics = local.diagnostics,
            fixing = local.fixing,
            confirm = local.confirm,
            repairFocus = (gate as? KernelGate.Blocked)?.focus,
        )
    }

    /** The port the header names: the state's Hub, else the last known one, except where E.5 says "@unknown". */
    fun port(state: KernelState, lastInfo: HubInfo?): Int? = when (state) {
        is KernelState.HubDown -> when (state.reason) {
            DownReason.Asleep, DownReason.Disconnected -> lastInfo?.port
            else -> null
        }
        is KernelState.Starting, KernelState.Unknown -> lastInfo?.port
        else -> state.hubInfo?.port ?: if (isPrerequisite(state)) null else lastInfo?.port
    }

    fun isPrerequisite(state: KernelState): Boolean = when (state) {
        is KernelState.TermuxMissing, is KernelState.TermuxTooOld, KernelState.PermissionMissing,
        is KernelState.ExternalAppsBlocked, KernelState.TermuxNotSetUp, KernelState.PortMissing, is KernelState.PortTooOld,
        -> true
        else -> false
    }

    private fun isWorking(state: KernelState): Boolean = when (state) {
        KernelState.Unknown, is KernelState.Starting, is KernelState.Connecting, is KernelState.Restarting,
        is KernelState.Reconnecting, is KernelState.Draining,
        -> true
        else -> false
    }

    /** E.5 header line 2 (`error`). */
    fun errorLine(state: KernelState, commandsAvailable: Boolean = true): UiText? = when (state) {
        is KernelState.HubDown -> when (state.reason) {
            DownReason.Asleep -> UiText.Res(R.string.kernel_status_asleep_line2)
            DownReason.Stopped, DownReason.StoppedInTermux, DownReason.Disconnected -> UiText.Res(R.string.hub_not_connected)
            DownReason.StartFailed, DownReason.StartTimedOut, DownReason.Unreachable, DownReason.CrashLoop ->
                KernelCopy.downDetail(state) ?: UiText.Res(R.string.hub_not_connected)
        }
        is KernelState.ProtocolUnsupported -> UiText.Res(R.string.kernel_protocol_unsupported)
        is KernelState.AuthFailed -> UiText.Res(R.string.kernel_auth_failed)
        is KernelState.Ready -> if (commandsAvailable) null else UiText.Res(R.string.banner_degraded)
        else -> if (isPrerequisite(state)) UiText.Res(R.string.kernel_fix_kernel) else null
    }

    /** E.5 "Status value". */
    fun statusLabel(state: KernelState): UiText = UiText.Res(
        when (state) {
            is KernelState.Ready -> R.string.kernel_status_connected
            KernelState.Unknown, is KernelState.Starting, is KernelState.Connecting -> R.string.kernel_status_connecting
            is KernelState.Restarting -> R.string.kernel_status_restarting
            is KernelState.Reconnecting -> R.string.kernel_status_reconnecting
            is KernelState.Draining -> R.string.kernel_status_draining
            is KernelState.HubDown -> when (state.reason) {
                DownReason.Asleep -> R.string.kernel_status_asleep
                DownReason.Stopped -> R.string.kernel_status_stopped
                DownReason.StoppedInTermux -> R.string.kernel_status_stopped_in_termux
                DownReason.Disconnected -> R.string.kernel_status_disconnected
                DownReason.StartFailed, DownReason.StartTimedOut, DownReason.Unreachable, DownReason.CrashLoop -> R.string.kernel_status_not_connected
            }
            is KernelState.ProtocolUnsupported -> R.string.kernel_status_update_required
            is KernelState.AuthFailed -> R.string.kernel_status_not_connected
            else -> R.string.kernel_status_setup_needed
        },
    )

    /** Under the status value: the step or phase label, the draining note, or the degraded second line. */
    fun statusDetail(state: KernelState, commandsAvailable: Boolean = true): UiText? = when (state) {
        KernelState.Unknown -> KernelCopy.stepLabel(dev.cline.termux.core.kernel.StartStep.Connecting)
        is KernelState.Starting -> KernelCopy.stepLabel(state.step)
        is KernelState.Connecting -> KernelCopy.stepLabel(dev.cline.termux.core.kernel.StartStep.Connecting)
        is KernelState.Restarting -> KernelCopy.phaseLabel(state.phase)
        is KernelState.Draining -> UiText.Res(R.string.kernel_draining_note)
        is KernelState.Ready -> if (commandsAvailable) null else UiText.Res(R.string.banner_degraded_line2)
        is KernelState.HubDown -> if (state.reason == DownReason.CrashLoop) UiText.Res(R.string.state_crash_loop) else null
        else -> null
    }

    /** E.5 "Primary": exactly one per state, or none while work is in progress. */
    fun primary(state: KernelState, commandsAvailable: Boolean = true): KernelStatusAction? = when (state) {
        // Degraded: anything that needs RUN_COMMAND opens the pushed repair first (KS 0.4).
        is KernelState.Ready -> if (commandsAvailable) KernelStatusAction.Restart else KernelStatusAction.FixKernel
        KernelState.Unknown, is KernelState.Starting, is KernelState.Connecting, is KernelState.Restarting,
        is KernelState.Reconnecting, is KernelState.Draining,
        -> null
        is KernelState.HubDown -> when (state.reason) {
            DownReason.Asleep, DownReason.Stopped, DownReason.StoppedInTermux -> KernelStatusAction.Start
            DownReason.Disconnected -> KernelStatusAction.Connect
            DownReason.StartFailed, DownReason.StartTimedOut, DownReason.Unreachable, DownReason.CrashLoop -> KernelStatusAction.TryAgain
        }
        is KernelState.ProtocolUnsupported -> KernelStatusAction.Restart
        is KernelState.AuthFailed -> KernelStatusAction.TryAgain
        else -> KernelStatusAction.FixKernel
    }

    /** E.5 "Action rows", in the E.4 order; an unavailable action is omitted, not disabled. */
    fun rowActions(state: KernelState, commandsAvailable: Boolean = true): List<KernelStatusAction> {
        return when (state) {
            is KernelState.Ready -> if (commandsAvailable) {
                listOf(KernelStatusAction.OpenTermux, KernelStatusAction.RunDiagnostics, KernelStatusAction.Disconnect, KernelStatusAction.Stop)
            } else {
                listOf(KernelStatusAction.OpenTermux, KernelStatusAction.Disconnect)
            }
            KernelState.Unknown, is KernelState.Starting, is KernelState.Connecting -> listOf(KernelStatusAction.OpenTermux)
            is KernelState.Restarting -> emptyList()
            is KernelState.Reconnecting -> listOf(KernelStatusAction.OpenTermux, KernelStatusAction.Disconnect)
            is KernelState.Draining -> listOf(KernelStatusAction.OpenTermux)
            is KernelState.HubDown -> when (state.reason) {
                DownReason.Asleep -> listOf(KernelStatusAction.OpenTermux, KernelStatusAction.RunDiagnostics)
                DownReason.Stopped, DownReason.StoppedInTermux -> listOf(KernelStatusAction.OpenTermux)
                DownReason.Disconnected -> listOf(KernelStatusAction.OpenTermux, KernelStatusAction.Stop)
                DownReason.StartFailed, DownReason.StartTimedOut, DownReason.Unreachable, DownReason.CrashLoop ->
                    listOf(KernelStatusAction.OpenTermux, KernelStatusAction.RunDiagnostics)
            }
            is KernelState.ProtocolUnsupported -> listOf(KernelStatusAction.OpenTermux, KernelStatusAction.RunDiagnostics)
            is KernelState.AuthFailed -> listOf(KernelStatusAction.OpenTermux, KernelStatusAction.RunDiagnostics, KernelStatusAction.Restart)
            // Open Termux needs Termux to exist (and be enabled).
            is KernelState.TermuxMissing -> emptyList()
            else -> listOf(KernelStatusAction.OpenTermux)
        }
    }

    /**
     * E.5 detail rows. A down or blocked kernel shows only "Status" and "Kernel"; missing
     * values omit their row (desktop `filter(Boolean)`).
     */
    fun rows(inputs: StatusInputs): List<StatusRow> {
        val state = inputs.state
        val info = state.hubInfo
        val release = inputs.portVersion?.release ?: info?.release ?: inputs.lastInfo?.release
        return buildList {
            add(StatusRow(R.string.kernel_row_status, statusLabel(state), statusDetail(state, inputs.commandsAvailable)))
            release?.takeIf { it.isNotBlank() }?.let { add(StatusRow(R.string.kernel_row_kernel, UiText.Raw(it))) }
            if (info == null) return@buildList
            info.coreVersion?.takeIf { it.isNotBlank() }?.let { add(StatusRow(R.string.kernel_row_core, UiText.Raw(it))) }
            info.buildId?.takeIf { it.isNotBlank() }?.let { add(StatusRow(R.string.kernel_row_build, UiText.Raw(it))) }
            info.pid?.let { add(StatusRow(R.string.kernel_row_process, UiText.Res(R.string.kernel_value_pid, it))) }
            startedAtMs(info.startedAt)?.let { started ->
                add(StatusRow(R.string.kernel_row_uptime, UiText.Raw(formatUptime(inputs.nowWallMs - started))))
            }
            (state as? KernelState.Ready)?.hub?.let { hub ->
                add(StatusRow(R.string.kernel_row_active_runs, UiText.Res(R.string.kernel_value_runs, hub.activeRpcTurns, hub.pendingRuns)))
            }
        }
    }

    /** "More details": Protocol, Hub ID and Event log (E.5). */
    fun moreRows(inputs: StatusInputs): List<StatusRow> {
        val info = inputs.state.hubInfo ?: return emptyList()
        val hub: HubLiveStatus? = (inputs.state as? KernelState.Ready)?.hub
        return buildList {
            info.protocolVersion?.takeIf { it.isNotBlank() }?.let { add(StatusRow(R.string.kernel_row_protocol, UiText.Raw(it))) }
            (info.hubId ?: hub?.hubId)?.takeIf { it.isNotBlank() }?.let { add(StatusRow(R.string.kernel_row_hub_id, UiText.Raw(it))) }
            hub?.let { add(StatusRow(R.string.kernel_row_event_log, UiText.Res(R.string.kernel_value_sequence, it.lastSequence))) }
        }
    }

    /** E.5 update row: shown only when the installed release differs from the one the running Hub started from. */
    fun updateRow(inputs: StatusInputs): UpdateRowUi? {
        val state = inputs.state
        if (state !is KernelState.Ready && state !is KernelState.Draining) return null
        val installed = inputs.portVersion?.release?.takeIf { it.isNotBlank() } ?: return null
        val running = inputs.hubLifetimeRelease?.takeIf { it.isNotBlank() } ?: return null
        return if (installed != running) UpdateRowUi(installed) else null
    }

    /** `/version.startedAt` as epoch ms: ISO-8601 or a plain number. */
    fun startedAtMs(startedAt: String?): Long? {
        val raw = startedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        raw.toDoubleOrNull()?.let { return it.toLong() }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
