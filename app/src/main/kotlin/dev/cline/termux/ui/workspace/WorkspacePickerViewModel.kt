package dev.cline.termux.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.WorkspaceRow
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.data.workspace.WorkspaceValidation
import dev.cline.termux.data.workspace.looksLikeFolderPath
import dev.cline.termux.data.workspace.normalizeWorkspacePath
import dev.cline.termux.ui.components.KernelBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** SP S9.5 `WorkspacePickerUiState`. */
@Immutable
data class WorkspacePickerUiState(
    val active: WorkspaceChoice = WorkspaceChoice.Chat,
    val query: String = "",
    /** The "Workspaces" rows, filtered by [query]; `display` is the `~` form, CD reads `path`. */
    val recents: List<WorkspaceRow> = emptyList(),
    /** `looksLikeFolderPath(query)`: the "Open folder “<text>”" row shows. */
    val pathLike: Boolean = false,
    /** The path being validated (row spinner; the other rows, "Open folder..." and "Just chat" disabled). */
    val validating: String? = null,
    /** The text that failed validation: "Couldn't open "<path>". Check that the folder exists and try again." */
    val error: String? = null,
    /** A Termux prerequisite fails: the KernelBlockedCard replaces the list; Just chat stays. */
    val blocked: KernelBlock? = null,
) {
    val busy: Boolean get() = validating != null
}

/** One-shot effects for the sheet host. */
sealed interface WorkspacePickerEffect {
    /** A folder was adopted, Just chat was picked, or the active folder was tapped. */
    data object Close : WorkspacePickerEffect

    /** "Open folder...": the folder browser at [start] (the active folder, or Home for chat). */
    data class OpenBrowser(val start: String) : WorkspacePickerEffect
}

/**
 * SP S9 workspace picker (desktop `WorkspacePicker`, `switchWorkspace`, `selectChat`). Validates
 * before adopting (`realpath -e` + `find -maxdepth 0 -type d` through [WorkspaceStore]); the
 * session-history recents arrive asynchronously while the stored ones show at once.
 */
class WorkspacePickerViewModel(
    private val store: WorkspaceStore,
    kernelBlock: Flow<KernelBlock?> = flowOf(null),
) : ViewModel() {

    private data class Transient(
        val query: String = "",
        val validating: String? = null,
        val error: String? = null,
        val blocked: KernelBlock? = null,
    )

    private val transient = MutableStateFlow(Transient())
    private val _effects = Channel<WorkspacePickerEffect>(Channel.BUFFERED)
    val effects: Flow<WorkspacePickerEffect> = _effects.receiveAsFlow()

    /** The last validation that hit a Termux failure, for the card's "Retry". */
    private var lastAttempt: String? = null
    private var validationJob: Job? = null

    val uiState: StateFlow<WorkspacePickerUiState> =
        combine(store.active, store.catalog, transient, kernelBlock) { active, paths, t, gateBlock ->
            WorkspacePickerUiState(
                active = active,
                query = t.query,
                recents = WorkspaceStore.rows(paths, active, t.query),
                pathLike = looksLikeFolderPath(t.query.trim()),
                validating = t.validating,
                error = t.error,
                blocked = gateBlock ?: t.blocked,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspacePickerUiState())

    /** The sheet opened: a fresh search and the session-history recents (desktop `onRefreshWorkspaces`). */
    fun onOpened() {
        transient.update { Transient(blocked = it.blocked) }
        viewModelScope.launch { runCatching { store.refreshSessionWorkspaces() } }
    }

    fun setQuery(query: String) = transient.update { it.copy(query = query, error = null) }

    /** A recent row (S9.4): the active one only closes; otherwise validate → adopt → close. */
    fun select(path: String) {
        val next = path.trim()
        if (next.isEmpty() || transient.value.validating != null) return
        validationJob = viewModelScope.launch {
            val active = store.activeNow()
            if (active is WorkspaceChoice.Folder && normalizeWorkspacePath(next) == normalizeWorkspacePath(active.path)) {
                _effects.send(WorkspacePickerEffect.Close)
                return@launch
            }
            validate(next)
        }
    }

    /** "Open folder “<text>”" or IME Go on path-like text. */
    fun openTyped() {
        val text = transient.value.query.trim()
        if (!looksLikeFolderPath(text)) return
        select(text)
    }

    /** "Open folder...": the browser starts at the active folder, or Home for the chat workspace. */
    fun openBrowser() {
        if (transient.value.validating != null) return
        viewModelScope.launch {
            val start = (store.activeNow() as? WorkspaceChoice.Folder)?.path ?: TERMUX_HOME
            _effects.send(WorkspacePickerEffect.OpenBrowser(start))
        }
    }

    /** "Just chat": local and instantaneous (no RUN_COMMAND), so it works while Termux is blocked. */
    fun justChat() {
        if (transient.value.validating != null) return
        viewModelScope.launch {
            store.selectChat()
            _effects.send(WorkspacePickerEffect.Close)
        }
    }

    /** The KernelBlockedCard's "Retry" (timeout card): run the last validation again. */
    fun retry() {
        transient.update { it.copy(blocked = null) }
        lastAttempt?.let(::select)
    }

    private suspend fun validate(path: String) {
        transient.update { it.copy(validating = path, error = null, blocked = null) }
        val result = try {
            store.open(path)
        } catch (e: CancellationException) {
            transient.update { it.copy(validating = null) }
            throw e
        }
        when (result) {
            is WorkspaceValidation.Valid -> {
                lastAttempt = null
                transient.update { it.copy(validating = null) }
                _effects.send(WorkspacePickerEffect.Close)
            }
            is WorkspaceValidation.Invalid -> {
                lastAttempt = null
                transient.update { it.copy(validating = null, error = path) }
            }
            is WorkspaceValidation.Blocked -> {
                lastAttempt = path
                val block = KernelBlock.of(result.error)
                transient.update {
                    if (block == null) it.copy(validating = null, error = path) else it.copy(validating = null, blocked = block)
                }
            }
        }
    }

    companion object {
        /**
         * The gate checks that stop RUN_COMMAND (SP §0.5): Termux missing, permission missing,
         * allow-external-apps off, or RUN_COMMAND revoked while connected. A port or Hub problem
         * does not block folder work, so it maps to null.
         */
        fun blockOf(gate: KernelGate): KernelBlock? = when (gate) {
            is KernelGate.Blocked -> when (gate.focus) {
                CheckId.Termux -> KernelBlock.TermuxMissing
                CheckId.Permission -> KernelBlock.PermissionMissing
                CheckId.ExternalApps -> KernelBlock.ExternalAppsBlocked
                CheckId.Kernel, CheckId.Hub -> null
            }
            KernelGate.DegradedNoCommands -> KernelBlock.PermissionMissing
            else -> null
        }

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorkspacePickerViewModel(
                    store = graph.workspaceStore,
                    // Deferred until the sheet collects: the kernel graph stays untouched at screen creation (docs/07 §8).
                    kernelBlock = flow { emitAll(graph.kernel.gate.map(::blockOf)) },
                )
            }
        }
    }
}
