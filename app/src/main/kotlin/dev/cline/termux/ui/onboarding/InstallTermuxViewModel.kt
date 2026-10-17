package dev.cline.termux.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.TermuxPresence
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** B.5 variants. */
sealed interface InstallTermuxVariant {
    data object Missing : InstallTermuxVariant
    data class TooOld(val versionName: String) : InstallTermuxVariant
    data object Disabled : InstallTermuxVariant
    data object NotSetUp : InstallTermuxVariant
}

@Immutable
data class InstallTermuxUiState(
    val variant: InstallTermuxVariant = InstallTermuxVariant.Missing,
    /** "Check again" or a package broadcast is being handled: "Checking…", buttons disabled. */
    val checking: Boolean = false,
    /** Pushed from A (shows the back arrow); false when it replaces the shell. */
    val pushedFromSetup: Boolean = false,
    /** Everything passed: "Termux is ready" is announced and the screen leaves 600 ms later. */
    val ready: Boolean = false,
)

/** B leaves: back to A when pushed from it, else back to the shell. */
sealed interface InstallTermuxEvent {
    data object Done : InstallTermuxEvent
}

/**
 * Install Termux (onboarding-kernel.md B.5–B.7): P1 synchronously, and P3 through the
 * coordinator's `recheck` once Termux is installed, so a Termux whose bootstrap never ran shows
 * "Open Termux once". No Hub calls.
 */
class InstallTermuxViewModel(
    private val kernel: SetupKernel,
    pushedFromSetup: Boolean,
) : ViewModel() {

    private val _ui = MutableStateFlow(InstallTermuxUiState(variant = variantOf(kernel.termuxPresence(), kernel.state.value) ?: InstallTermuxVariant.NotSetUp, pushedFromSetup = pushedFromSetup))
    val ui: StateFlow<InstallTermuxUiState> = _ui.asStateFlow()

    private val _events = Channel<InstallTermuxEvent>(Channel.BUFFERED)
    val events: Flow<InstallTermuxEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    init {
        check(userInitiated = false)
    }

    /** `ON_RESUME`: P1, and P3 when P1 passes (B.6). */
    fun onResume() = check(userInitiated = false)

    /** `PACKAGE_ADDED` / `REPLACED` / `CHANGED` for `com.termux` (B.6). */
    fun onPackageChanged() = check(userInitiated = true)

    /** "Check again". */
    fun checkAgain() = check(userInitiated = true)

    private fun check(userInitiated: Boolean) {
        if (_ui.value.ready || job?.isActive == true) return
        job = viewModelScope.launch {
            if (userInitiated) _ui.update { it.copy(checking = true) }
            try {
                val presence = kernel.termuxPresence()
                val p1 = variantOf(presence, null)
                if (p1 != null) {
                    if (userInitiated) delay(MIN_CHECKING_MS)
                    _ui.update { it.copy(variant = p1) }
                    return@launch
                }
                // P1 passed: P3 tells a Termux whose bootstrap never ran from a ready one.
                kernel.recheck()
                val state = kernel.state.value
                val after = variantOf(kernel.termuxPresence(), state)
                if (after != null) {
                    _ui.update { it.copy(variant = after) }
                } else {
                    _ui.update { it.copy(ready = true) }
                    delay(LEAVE_DELAY_MS)
                    _events.send(InstallTermuxEvent.Done)
                }
            } finally {
                _ui.update { it.copy(checking = false) }
            }
        }
    }

    companion object {
        /** B.6: navigate this long after everything passed. */
        const val LEAVE_DELAY_MS = 600L

        /** "Check again" keeps "Checking…" visible at least this long, so the tap is acknowledged. */
        const val MIN_CHECKING_MS = 400L

        /**
         * B.5 trigger column: P1 decides Missing / Disabled / TooOld; with P1 passing, the
         * coordinator's state decides NotSetUp. Null = nothing for B to show (Termux is ready).
         */
        fun variantOf(presence: TermuxPresence, state: KernelState?): InstallTermuxVariant? = when (presence) {
            TermuxPresence.Missing -> InstallTermuxVariant.Missing
            TermuxPresence.Disabled -> InstallTermuxVariant.Disabled
            is TermuxPresence.Installed -> when {
                !presence.supported -> InstallTermuxVariant.TooOld(presence.versionName ?: presence.versionCode.toString())
                state == KernelState.TermuxNotSetUp -> InstallTermuxVariant.NotSetUp
                state is KernelState.TermuxMissing -> if (state.disabled) InstallTermuxVariant.Disabled else InstallTermuxVariant.Missing
                state is KernelState.TermuxTooOld -> InstallTermuxVariant.TooOld(state.versionName)
                else -> null
            }
        }
    }
}
