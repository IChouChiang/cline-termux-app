package dev.cline.termux.ui.onboarding

import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.StartFailure
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.hub.HubEndpoints
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.UiText

/*
 * The five setup rows of onboarding-kernel.md A.5 / A.7 as a pure projection of
 * `KernelState` plus the synchronous P1/P2 facts. The ViewModel feeds it; tests replay
 * kernel timelines through it.
 */

/** A row's status glyph (KS 0.7 SetupCheckRow: ring / spinner / check / triangle / circle). */
enum class CheckStatus { Waiting, Checking, Passed, NeedsAction, Failed }

/** How row 2 asks for the RUN_COMMAND permission (A.5 row 2). */
enum class PermissionMode { Ask, AskAgain, OpenSettings }

/** The one fix a failing row offers (A.7 `CheckFix`). */
sealed interface CheckFix {
    /** Row 1 missing or too old: "Install Termux" → B. */
    data object InstallTermux : CheckFix

    /** Row 1 disabled: "Open Termux settings". */
    data object OpenTermuxSettings : CheckFix

    /** Row 2: "Allow" (Ask / AskAgain) or "Open app settings" with the path line. */
    data class RequestPermission(val mode: PermissionMode) : CheckFix

    /** Rows 3 and 4: CommandBlock + "Open Termux" + "Check again". */
    data class RunInTermux(val command: String) : CheckFix

    /** Row 4, bootstrap not run: "Open Termux" + "Check again". */
    data object OpenTermux : CheckFix

    /** Row 5 idle: "Start kernel". */
    data object StartKernel : CheckFix

    /**
     * Row 5 failed: [detail] (stderr tail or the app's sentence, mono, 6 lines) + "Try again";
     * "Run diagnostics" unless [timedOut], where the doctor rows are already filled.
     */
    data class StartFailed(val detail: UiText?, val timedOut: Boolean) : CheckFix

    /** Row 5 protocol unsupported: "Restart kernel" (the E.9 flow inline). */
    data object UpdateHub : CheckFix
}

/** One rendered row: [supporting] is the line under the title (it carries the version when passed). */
@Immutable
data class SetupCheckUi(
    val id: CheckId,
    val status: CheckStatus,
    val supporting: UiText,
    val fix: CheckFix? = null,
)

/** The facts the projection reads besides the kernel state. */
data class SetupFacts(
    val presence: TermuxPresence,
    val permissionGranted: Boolean,
    val portVersion: PortVersion?,
    /** The user tapped "Start kernel" / "Try again" and that start is still running. */
    val startRequested: Boolean = false,
    /** This screen saw the Hub `Ready` earlier (row 5 then says "The Cline Hub stopped."). */
    val sawReady: Boolean = false,
    val permissionMode: PermissionMode = PermissionMode.Ask,
)

object KernelSetupRows {
    /** A.5 row 3 fix: enable `allow-external-apps` (copied exactly, no trailing newline). */
    const val ALLOW_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"

    /** A.5 row 4 fix: the public installer one-liner (README of the port). */
    const val INSTALLER_COMMAND =
        "curl -fsSL https://github.com/IChouChiang/cline-termux/releases/latest/download/install-cline-termux.sh | bash"

    val ORDER: List<CheckId> = listOf(CheckId.Termux, CheckId.Permission, CheckId.ExternalApps, CheckId.Kernel, CheckId.Hub)

    fun rows(state: KernelState, facts: SetupFacts): List<SetupCheckUi> {
        val termuxPassed = SetupCheckUi(CheckId.Termux, CheckStatus.Passed, termuxVersionText(facts.presence))
        val permissionPassed = SetupCheckUi(CheckId.Permission, CheckStatus.Passed, UiText.Res(R.string.check_permission_passed))
        val externalPassed = SetupCheckUi(CheckId.ExternalApps, CheckStatus.Passed, UiText.Res(R.string.check_external_passed))
        val kernelPassed = SetupCheckUi(CheckId.Kernel, CheckStatus.Passed, kernelVersionText(facts.portVersion, state))
        val firstFour = listOf(termuxPassed, permissionPassed, externalPassed, kernelPassed)

        fun waitingFrom(passed: List<SetupCheckUi>, current: SetupCheckUi): List<SetupCheckUi> =
            passed + current + ORDER.drop(passed.size + 1).map { waiting(it) }

        fun hub(status: CheckStatus, supporting: UiText, fix: CheckFix? = null) = firstFour + SetupCheckUi(CheckId.Hub, status, supporting, fix)

        val hubChecking = hub(CheckStatus.Checking, UiText.Res(R.string.check_hub_starting))

        // A tapped start shows row 5 checking at once, before the coordinator leaves the old state.
        if (facts.startRequested && !isPrerequisiteFailure(state) && state !is KernelState.Ready && state !is KernelState.Draining) {
            return hubChecking
        }
        return when (state) {
            KernelState.Unknown -> waitingFrom(emptyList(), checking(CheckId.Termux))
            is KernelState.TermuxMissing -> waitingFrom(
                emptyList(),
                if (state.disabled) {
                    SetupCheckUi(CheckId.Termux, CheckStatus.NeedsAction, UiText.Res(R.string.check_termux_disabled), CheckFix.OpenTermuxSettings)
                } else {
                    SetupCheckUi(CheckId.Termux, CheckStatus.NeedsAction, UiText.Res(R.string.check_termux_missing), CheckFix.InstallTermux)
                },
            )
            is KernelState.TermuxTooOld -> waitingFrom(
                emptyList(),
                SetupCheckUi(CheckId.Termux, CheckStatus.NeedsAction, UiText.Res(R.string.check_termux_too_old, state.versionName), CheckFix.InstallTermux),
            )
            KernelState.PermissionMissing -> waitingFrom(listOf(termuxPassed), permissionRow(facts.permissionMode))
            is KernelState.ExternalAppsBlocked -> waitingFrom(
                listOf(termuxPassed, permissionPassed),
                SetupCheckUi(
                    CheckId.ExternalApps,
                    CheckStatus.NeedsAction,
                    UiText.Res(R.string.check_external_body),
                    CheckFix.RunInTermux(ALLOW_EXTERNAL_APPS_COMMAND),
                ),
            )
            KernelState.TermuxNotSetUp -> waitingFrom(
                listOf(termuxPassed, permissionPassed, externalPassed),
                SetupCheckUi(CheckId.Kernel, CheckStatus.NeedsAction, UiText.Res(R.string.check_kernel_not_set_up), CheckFix.OpenTermux),
            )
            KernelState.PortMissing -> waitingFrom(
                listOf(termuxPassed, permissionPassed, externalPassed),
                SetupCheckUi(CheckId.Kernel, CheckStatus.NeedsAction, UiText.Res(R.string.check_kernel_missing), CheckFix.RunInTermux(INSTALLER_COMMAND)),
            )
            is KernelState.PortTooOld -> waitingFrom(
                listOf(termuxPassed, permissionPassed, externalPassed),
                SetupCheckUi(
                    CheckId.Kernel,
                    CheckStatus.NeedsAction,
                    state.release?.takeIf { it.isNotBlank() }?.let { UiText.Res(R.string.check_kernel_too_old, it) }
                        ?: UiText.Res(R.string.check_kernel_too_old_unknown),
                    CheckFix.RunInTermux(INSTALLER_COMMAND),
                ),
            )
            is KernelState.Starting -> when {
                facts.startRequested -> hubChecking
                state.step == StartStep.CheckingTermux -> waitingFrom(emptyList(), checking(CheckId.Termux))
                state.step == StartStep.CheckingKernel ->
                    listOf(termuxPassed, permissionPassed, checking(CheckId.ExternalApps), checking(CheckId.Kernel), waiting(CheckId.Hub))
                else -> hubChecking
            }
            is KernelState.Connecting, is KernelState.Reconnecting, is KernelState.Restarting -> hubChecking
            is KernelState.Ready, is KernelState.Draining -> if (facts.permissionGranted) {
                hub(CheckStatus.Passed, hubPassedText(state))
            } else {
                // Degraded mode (KS §0.4): the Hub answers but RUN_COMMAND is revoked.
                waitingFrom(listOf(termuxPassed), permissionRow(facts.permissionMode))
            }
            is KernelState.ProtocolUnsupported -> hub(CheckStatus.Failed, UiText.Res(R.string.check_hub_protocol), CheckFix.UpdateHub)
            is KernelState.AuthFailed -> hub(CheckStatus.Failed, UiText.Res(R.string.check_hub_auth), CheckFix.StartFailed(null, timedOut = false))
            is KernelState.HubDown -> when (state.reason) {
                DownReason.StartFailed, DownReason.CrashLoop -> hub(
                    CheckStatus.Failed,
                    UiText.Res(R.string.check_hub_failed),
                    CheckFix.StartFailed(startFailedDetail(state), timedOut = false),
                )
                DownReason.StartTimedOut -> hub(
                    CheckStatus.Failed,
                    UiText.Res(R.string.check_hub_timed_out),
                    CheckFix.StartFailed(null, timedOut = true),
                )
                DownReason.Asleep, DownReason.Stopped, DownReason.StoppedInTermux, DownReason.Disconnected, DownReason.Unreachable -> hub(
                    CheckStatus.NeedsAction,
                    UiText.Res(if (facts.sawReady) R.string.check_hub_stopped_meanwhile else R.string.check_hub_idle),
                    CheckFix.StartKernel,
                )
            }
        }
    }

    /** Row 5's detail block for a failed start: the app's sentence, the crash-loop sentence, or kernel text. */
    fun startFailedDetail(state: KernelState.HubDown): UiText? = when {
        state.failure == StartFailure.CredentialsUnreadable -> UiText.Res(R.string.check_hub_credentials_unreadable)
        state.failure == StartFailure.HandshakeRefused -> UiText.Res(R.string.check_hub_handshake)
        state.reason == DownReason.CrashLoop -> UiText.Res(R.string.check_hub_crash_loop)
        else -> state.lastError?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }
    }

    /** The row-5 busy label: the start step, the restart phase, or "Connecting…". */
    fun busyLabel(state: KernelState): UiText? = when (state) {
        is KernelState.Starting -> KernelCopy.stepLabel(state.step)
        is KernelState.Connecting, is KernelState.Reconnecting -> KernelCopy.stepLabel(StartStep.Connecting)
        is KernelState.Restarting -> KernelCopy.phaseLabel(state.phase)
        else -> null
    }

    fun readyCount(rows: List<SetupCheckUi>): Int = rows.count { it.status == CheckStatus.Passed }

    /** The first row that is not passed, or null when all five passed. */
    fun firstOpen(rows: List<SetupCheckUi>): SetupCheckUi? = rows.firstOrNull { it.status != CheckStatus.Passed }

    /** States in which the prerequisites (rows 1–4) fail: `ON_RESUME` re-checks them (A.5 rows 2–3). */
    fun isPrerequisiteFailure(state: KernelState): Boolean = when (state) {
        is KernelState.TermuxMissing, is KernelState.TermuxTooOld, KernelState.PermissionMissing,
        is KernelState.ExternalAppsBlocked, KernelState.TermuxNotSetUp, KernelState.PortMissing, is KernelState.PortTooOld,
        -> true
        else -> false
    }

    /** The coordinator is re-running the prerequisite probe (P1–P3), not starting the Hub. */
    fun isProbing(state: KernelState, facts: SetupFacts): Boolean = state == KernelState.Unknown ||
        (
            state is KernelState.Starting && !facts.startRequested &&
                (state.step == StartStep.CheckingTermux || state.step == StartStep.CheckingKernel)
            )

    /** A start, connect or restart is in progress. */
    fun isBusy(state: KernelState): Boolean = state is KernelState.Starting || state is KernelState.Connecting ||
        state is KernelState.Reconnecting || state is KernelState.Restarting || state == KernelState.Unknown

    private fun permissionRow(mode: PermissionMode) = SetupCheckUi(
        CheckId.Permission,
        CheckStatus.NeedsAction,
        UiText.Res(if (mode == PermissionMode.OpenSettings) R.string.check_permission_denied else R.string.check_permission_body),
        CheckFix.RequestPermission(mode),
    )

    private fun waiting(id: CheckId) = SetupCheckUi(id, CheckStatus.Waiting, UiText.Res(R.string.setup_waiting_above))

    private fun checking(id: CheckId) = SetupCheckUi(id, CheckStatus.Checking, UiText.Res(R.string.setup_checking))

    private fun termuxVersionText(presence: TermuxPresence): UiText {
        val version = (presence as? TermuxPresence.Installed)?.let { it.versionName?.takeIf { name -> name.isNotBlank() } ?: it.versionCode.toString() }
        return if (version != null) UiText.Res(R.string.check_termux_passed, version) else UiText.Res(R.string.check_termux_title)
    }

    private fun kernelVersionText(version: PortVersion?, state: KernelState): UiText {
        val release = version?.release?.takeIf { it.isNotBlank() } ?: state.hubInfo?.release?.takeIf { it.isNotBlank() }
        return if (release != null) UiText.Res(R.string.check_kernel_passed, release) else UiText.Res(R.string.check_kernel_passed_unknown)
    }

    private fun hubPassedText(state: KernelState): UiText {
        val info = state.hubInfo ?: return UiText.Res(R.string.check_hub_passed_no_core, HubEndpoints.DEFAULT_PORT.toString())
        val core = info.coreVersion?.takeIf { it.isNotBlank() }
        return if (core != null) {
            UiText.Res(R.string.check_hub_passed, info.port.toString(), core)
        } else {
            UiText.Res(R.string.check_hub_passed_no_core, info.port.toString())
        }
    }

    /** `setup_status_*` for the merged row description and the status announcement. */
    fun statusWord(status: CheckStatus): Int = when (status) {
        CheckStatus.Waiting -> R.string.setup_status_waiting
        CheckStatus.Checking -> R.string.setup_status_checking
        CheckStatus.Passed -> R.string.setup_status_passed
        CheckStatus.NeedsAction -> R.string.setup_status_needs_action
        CheckStatus.Failed -> R.string.setup_status_failed
    }

    /** `check_*_title` of a row. */
    fun title(id: CheckId): Int = when (id) {
        CheckId.Termux -> R.string.check_termux_title
        CheckId.Permission -> R.string.check_permission_title
        CheckId.ExternalApps -> R.string.check_external_title
        CheckId.Kernel -> R.string.check_kernel_title
        CheckId.Hub -> R.string.check_hub_title
    }
}
