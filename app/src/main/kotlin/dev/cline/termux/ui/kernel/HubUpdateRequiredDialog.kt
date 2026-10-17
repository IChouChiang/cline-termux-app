package dev.cline.termux.ui.kernel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cline.termux.R
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubProtocol
import dev.cline.termux.ui.components.CommandBlock
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which way the Hub and the app disagree (E.9; the direction comes from the H1 range check). */
enum class HubMismatch {
    /** `minClientProtocolVersion > v1`: the desktop `unsupported_protocol` dialog. */
    HubNewer,

    /** `maxClientProtocolVersion < v1`: the desktop `outdated_hub` title, made dismissible. */
    HubOlder,
    ;

    companion object {
        /** Unknown directions (an unparsable protocol) read as "Hub newer", the only one possible today. */
        fun of(info: HubInfo): HubMismatch = when (
            HubProtocol.direction(info.protocolVersion, info.minClientProtocolVersion, info.maxClientProtocolVersion)
        ) {
            HubProtocol.Direction.HubOlder -> HubOlder
            HubProtocol.Direction.HubNewer, HubProtocol.Direction.Unknown -> HubNewer
        }
    }
}

/** Everything the E.9 dialog renders. */
data class HubUpdateDialogState(
    val mismatch: HubMismatch,
    val coreVersion: String?,
    /** The restart is running ("Restarting…", both buttons disabled, not dismissible). */
    val busy: Boolean = false,
    /** A restart came back to an equally unsupported Hub: extra sentence + "Try again". */
    val stillUnsupported: Boolean = false,
    /** E.7's inline outcome text (stderr verbatim + hint, or the generic failure). */
    val error: List<UiText> = emptyList(),
) {
    val title: UiText get() = UiText.Res(if (mismatch == HubMismatch.HubNewer) R.string.hub_updated_title else R.string.hub_outdated_title)

    val body: List<UiText>
        get() = buildList {
            when (mismatch) {
                HubMismatch.HubNewer -> {
                    val core = coreVersion?.trim()?.takeIf { it.isNotEmpty() }
                    add(if (core != null) UiText.Res(R.string.hub_updated_body, core) else UiText.Res(R.string.hub_updated_body_no_core))
                    add(UiText.Res(R.string.hub_updated_body_android))
                }
                HubMismatch.HubOlder -> add(UiText.Res(R.string.hub_outdated_body))
            }
            if (stillUnsupported) add(UiText.Res(R.string.hub_still_unsupported))
        }

    /** The "Hub older" text ends with the installer command block. */
    val showsInstaller: Boolean get() = mismatch == HubMismatch.HubOlder

    val confirmLabel: UiText
        get() = when {
            busy -> UiText.Res(R.string.kernel_status_restarting)
            stillUnsupported -> UiText.Res(R.string.hub_update_try_again)
            else -> UiText.Res(R.string.kernel_restart)
        }
}

/**
 * The shell's E.9 logic: shows the dialog whenever the kernel enters `ProtocolUnsupported`,
 * keeps it hidden after "Later" until the next entry into that state or the next `ON_START`
 * (a dismissal is never persisted, desktop `isPersistableHubMismatchKey`), and runs the E.7
 * restart from step 4 (not connected: no pre-check, no disconnect). Lives in
 * `AppShellViewModel`; [restart] is `{ kernel.restart(forced = false) }`.
 */
class HubUpdatePresenter(
    private val scope: CoroutineScope,
    private val restart: suspend () -> RestartOutcome,
) {
    private val _dialog = MutableStateFlow<HubUpdateDialogState?>(null)

    /** Non-null while the dialog is on screen. */
    val dialog: StateFlow<HubUpdateDialogState?> = _dialog.asStateFlow()

    private val _restarted = MutableStateFlow(0)

    /** Increments when a restart from the dialog reached `Ready` ("Cline Hub restarted" snackbar). */
    val restarted: StateFlow<Int> = _restarted.asStateFlow()

    private var lastState: KernelState? = null
    private var dismissed = false
    private var restartJob: Job? = null

    /** Feed every coordinator state. */
    fun onKernelState(state: KernelState) {
        val previous = lastState
        lastState = state
        if (restartJob?.isActive == true) return
        if (state is KernelState.ProtocolUnsupported) {
            val entered = previous !is KernelState.ProtocolUnsupported
            if (entered) dismissed = false
            if (!dismissed) {
                val current = _dialog.value
                _dialog.value = current?.copy(mismatch = HubMismatch.of(state.info), coreVersion = state.info.coreVersion)
                    ?: HubUpdateDialogState(HubMismatch.of(state.info), state.info.coreVersion)
            }
        } else {
            _dialog.value = null
        }
    }

    /** `ON_START` of the activity: a dismissed dialog returns (E.9). */
    fun onStart() {
        dismissed = false
        lastState?.let { if (it is KernelState.ProtocolUnsupported) onKernelState(it) }
    }

    /** "Later" or back. */
    fun later() {
        if (_dialog.value?.busy == true) return
        dismissed = true
        _dialog.value = null
    }

    /** Opens the dialog again from F's "Restart kernel" primary while the state is still unsupported. */
    fun reopen() {
        val state = lastState as? KernelState.ProtocolUnsupported ?: return
        dismissed = false
        _dialog.value = HubUpdateDialogState(HubMismatch.of(state.info), state.info.coreVersion)
    }

    /** "Restart kernel" / "Try again". */
    fun confirm(): Job? {
        val current = _dialog.value ?: return null
        if (current.busy) return null
        _dialog.value = current.copy(busy = true, error = emptyList())
        return scope.launch {
            val outcome = restart()
            val after = _dialog.value ?: current
            val next = reduce(after.copy(busy = false), outcome)
            if (next == null) {
                _dialog.value = null
                _restarted.value += 1
            } else {
                _dialog.value = next
            }
        }.also { restartJob = it }
    }

    companion object {
        /**
         * E.9 + E.7 outcome table inside the dialog: null closes it (restarted and compatible);
         * a Hub that is still unsupported keeps it with the extra sentence and "Try again";
         * failures show E.7's inline texts.
         */
        fun reduce(state: HubUpdateDialogState, outcome: RestartOutcome): HubUpdateDialogState? = when (outcome) {
            RestartOutcome.Restarted -> null
            is RestartOutcome.StillServing -> state.copy(
                error = listOfNotNull(outcome.stderr.trim().takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }, UiText.Res(R.string.kernel_restart_busy_hint)),
            )
            is RestartOutcome.Failed -> state.copy(
                error = listOf(outcome.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.kernel_restart_failed)),
            )
            is RestartOutcome.Down -> when (val down = outcome.state) {
                is KernelState.ProtocolUnsupported -> state.copy(
                    mismatch = HubMismatch.of(down.info), coreVersion = down.info.coreVersion, stillUnsupported = true, error = emptyList(),
                )
                else -> state.copy(error = listOf(KernelCopy.downDetail(down) ?: UiText.Res(R.string.kernel_restart_failed)))
            }
        }
    }
}

/**
 * KS E.9 `AlertDialog`, copied from the desktop `hub-update-required-dialog.tsx`
 * (`unsupported_protocol`, and the `outdated_hub` title made dismissible). Not dismissible
 * by a scrim tap; back is "Later"; while restarting nothing dismisses it.
 */
@Composable
fun HubUpdateRequiredDialog(
    state: HubUpdateDialogState,
    onRestart: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onLater() },
        modifier = modifier,
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = false),
        title = { Text(state.title.resolve()) },
        text = {
            Column {
                state.body.forEachIndexed { index, paragraph ->
                    if (index > 0) Spacer(Modifier.height(Spacing.s))
                    Text(paragraph.resolve())
                }
                if (state.showsInstaller) {
                    Spacer(Modifier.height(Spacing.m))
                    CommandBlock(stringResource(R.string.hub_installer_command), maxLines = 1)
                }
                if (state.error.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        state.error.map { it.resolve() }.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRestart, enabled = !state.busy) {
                if (state.busy) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(
                    state.confirmLabel.resolve(),
                    modifier = if (state.busy) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !state.busy) { Text(stringResource(R.string.hub_update_later)) }
        },
    )
}


@LightDarkPreviews
@Composable
private fun HubUpdatedPreview() {
    ComponentPreview(padding = false) {
        HubUpdateRequiredDialog(HubUpdateDialogState(HubMismatch.HubNewer, coreVersion = "0.1.4"), onRestart = {}, onLater = {})
    }
}

@LightDarkPreviews
@Composable
private fun HubUpdatedNoCorePreview() {
    ComponentPreview(padding = false) {
        HubUpdateRequiredDialog(HubUpdateDialogState(HubMismatch.HubNewer, coreVersion = null, busy = true), onRestart = {}, onLater = {})
    }
}

@LightDarkPreviews
@Composable
private fun HubStillUnsupportedPreview() {
    ComponentPreview(padding = false) {
        HubUpdateRequiredDialog(
            HubUpdateDialogState(HubMismatch.HubNewer, coreVersion = "0.1.4", stillUnsupported = true),
            onRestart = {},
            onLater = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun HubOutdatedPreview() {
    ComponentPreview(padding = false) {
        HubUpdateRequiredDialog(
            HubUpdateDialogState(
                HubMismatch.HubOlder,
                coreVersion = "0.0.80",
                error = listOf(UiText.Res(R.string.kernel_restart_failed)),
            ),
            onRestart = {},
            onLater = {},
        )
    }
}
