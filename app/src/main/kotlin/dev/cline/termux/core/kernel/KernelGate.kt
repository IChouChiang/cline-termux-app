package dev.cline.termux.core.kernel

/** The setup checklist rows (onboarding-kernel.md A.7); `KernelGate.Blocked` names the one to focus. */
enum class CheckId { Termux, Permission, ExternalApps, Kernel, Hub }

/**
 * The one kernel projection every screen binds to (onboarding-kernel.md F.6, docs/07 §3.2).
 * Derived purely from [KernelState] by [toGate]; no screen switches on the state itself.
 *
 * The busy step label is carried as [BusyLabel] (the step or phase enum) rather than a
 * string resource: the kernel-ui package owns `strings_kernel.xml` and maps it there.
 */
sealed interface KernelGate {
    data object Ready : KernelGate

    /** Something is in progress; [slow] after [SLOW_START_MS] in a start (F.2 "taking longer than usual"). */
    data class Busy(val kind: BusyKind, val stepLabel: BusyLabel?, val slow: Boolean) : KernelGate

    /** The Hub is not usable; [detail] is kernel text (stderr, close reason), shown as data. */
    data class Down(val variant: DownVariant, val detail: String?) : KernelGate

    /** Connected, but RUN_COMMAND is revoked (KS §0.4 degraded mode). */
    data object DegradedNoCommands : KernelGate

    /** A prerequisite fails; the setup/repair flow opens on [focus]. */
    data class Blocked(val focus: CheckId) : KernelGate

    enum class BusyKind { Starting, Connecting, Reconnecting, Restarting, Draining }

    enum class DownVariant { Asleep, Stopped, StoppedInTermux, Disconnected, StartFailed, CrashLoop, Protocol, Auth }

    /** What the busy line says under "Connecting..." / "Restarting…" (`kernel_step_*` / `kernel_restart_phase_*`). */
    sealed interface BusyLabel {
        data class Step(val step: StartStep) : BusyLabel
        data class Phase(val phase: RestartPhase) : BusyLabel
    }

    companion object {
        /** A start that has run longer than this shows the "taking longer than usual" hint. */
        const val SLOW_START_MS = 20_000L
    }
}

fun DownReason.toVariant(): KernelGate.DownVariant = when (this) {
    DownReason.Asleep -> KernelGate.DownVariant.Asleep
    DownReason.Stopped -> KernelGate.DownVariant.Stopped
    DownReason.StoppedInTermux -> KernelGate.DownVariant.StoppedInTermux
    DownReason.Disconnected -> KernelGate.DownVariant.Disconnected
    DownReason.StartFailed, DownReason.StartTimedOut, DownReason.Unreachable -> KernelGate.DownVariant.StartFailed
    DownReason.CrashLoop -> KernelGate.DownVariant.CrashLoop
}

/**
 * F.6 projection. [commandsAvailable] is false in degraded mode (permission revoked while the
 * Hub stays reachable); [nowElapsedMs] decides `slow` for a long start.
 */
fun KernelState.toGate(nowElapsedMs: Long, commandsAvailable: Boolean = true): KernelGate = when (this) {
    KernelState.Unknown -> KernelGate.Busy(KernelGate.BusyKind.Connecting, KernelGate.BusyLabel.Step(StartStep.Connecting), slow = false)
    is KernelState.Connecting -> KernelGate.Busy(KernelGate.BusyKind.Connecting, KernelGate.BusyLabel.Step(StartStep.Connecting), slow = false)
    is KernelState.Starting -> KernelGate.Busy(
        KernelGate.BusyKind.Starting,
        KernelGate.BusyLabel.Step(step),
        slow = nowElapsedMs - startedAtElapsedMs > KernelGate.SLOW_START_MS,
    )
    is KernelState.Reconnecting -> KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
    is KernelState.Restarting -> KernelGate.Busy(KernelGate.BusyKind.Restarting, KernelGate.BusyLabel.Phase(phase), slow = false)
    is KernelState.Draining -> KernelGate.Busy(KernelGate.BusyKind.Draining, null, slow = false)
    is KernelState.Ready -> if (commandsAvailable) KernelGate.Ready else KernelGate.DegradedNoCommands
    is KernelState.HubDown -> KernelGate.Down(reason.toVariant(), lastError)
    is KernelState.ProtocolUnsupported -> KernelGate.Down(KernelGate.DownVariant.Protocol, null)
    is KernelState.AuthFailed -> KernelGate.Down(KernelGate.DownVariant.Auth, null)
    is KernelState.TermuxMissing, is KernelState.TermuxTooOld, KernelState.TermuxNotSetUp -> KernelGate.Blocked(CheckId.Termux)
    KernelState.PermissionMissing -> KernelGate.Blocked(CheckId.Permission)
    is KernelState.ExternalAppsBlocked -> KernelGate.Blocked(CheckId.ExternalApps)
    KernelState.PortMissing, is KernelState.PortTooOld -> KernelGate.Blocked(CheckId.Kernel)
}
