package dev.cline.termux.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.DirectoryListing
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.TERMUX_SHARED_STORAGE
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.data.workspace.WorkspaceValidation
import dev.cline.termux.data.workspace.expandHomePath
import dev.cline.termux.ui.components.KernelBlock
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One subfolder row. */
@Immutable
data class FolderEntry(val name: String, val path: String)

/** Whether `~/storage` exists (`termux-setup-storage` has run), checked per browser open and on resume. */
enum class SharedStorageState {
    Checking,
    Available,
    NotSetUp,

    /** `termux-setup-storage` is running in Termux's foreground session. */
    SettingUp,

    /** Termux could not run the check (the blocked card covers it). */
    Unknown,
}

/** SP S9.5 `FolderBrowserUiState`. */
@Immutable
data class FolderBrowserUiState(
    val path: String = TERMUX_HOME,
    val parent: String? = null,
    val atHome: Boolean = true,
    /** Folders visited, start first; back pops it (kept in the SavedStateHandle). */
    val history: List<String> = listOf(TERMUX_HOME),
    val entries: List<FolderEntry> = emptyList(),
    val loading: Boolean = true,
    /** The listing failed: the stripped stderr's last line, or null with [listFailed] for an empty one. */
    val error: String? = null,
    val listFailed: Boolean = false,
    val truncated: Boolean = false,
    val sharedStorage: SharedStorageState = SharedStorageState.Checking,
    /** `termux-setup-storage` exited early with this stripped stderr line. */
    val storageError: String? = null,
    val blocked: KernelBlock? = null,
    /** "Use this folder" validation in flight. */
    val using: Boolean = false,
    /** "Use this folder" failed validation for this path. */
    val useError: String? = null,
) {
    /** Desktop: disabled while loading or after a listing error. */
    val canUse: Boolean get() = !loading && !listFailed && blocked == null && !using

    val inSharedStorage: Boolean get() = path == TERMUX_SHARED_STORAGE || path.startsWith("$TERMUX_SHARED_STORAGE/")

    /** "Set up shared storage" row: storage not set up, or a listing inside it was refused. */
    val showStorageSetup: Boolean
        get() = sharedStorage == SharedStorageState.NotSetUp || sharedStorage == SharedStorageState.SettingUp ||
            (inSharedStorage && error?.contains(PERMISSION_DENIED, ignoreCase = true) == true)

    val canGoBack: Boolean get() = history.size > 1

    companion object {
        const val PERMISSION_DENIED = "Permission denied"
    }
}

sealed interface FolderBrowserEffect {
    /** "Use this folder" adopted [path] (canonical); the host closes the browser and the sheet. */
    data class Done(val path: String) : FolderBrowserEffect

    /** `termux-setup-storage` started in the foreground: bring Termux's activity to the front (S7 step 2 mechanism). */
    data object BringTermuxForward : FolderBrowserEffect
}

/** The listing side of the browser over RUN_COMMAND `find` (SP S9.3). */
interface DirectorySource {
    sealed interface Listing {
        data class Listed(val listing: DirectoryListing) : Listing

        /** `exitCode != 0`: [message] is the stripped stderr's last line. */
        data class Failed(val message: String?) : Listing
        data class Blocked(val error: TermuxUnavailable) : Listing
    }

    suspend fun list(dir: String): Listing

    /** `find $HOME/storage -maxdepth 0`: true when it exists; null when Termux could not run it. */
    suspend fun hasSharedStorage(): Boolean?

    /**
     * Starts `termux-setup-storage` in a foreground session (`runDetached`, no completion timeout).
     * The deferred completes only on an early exit.
     * @throws TermuxUnavailable when the intent cannot be delivered.
     */
    fun setupStorage(): Deferred<TermuxResult>

    companion object {
        fun of(termux: TermuxExecutor): DirectorySource = object : DirectorySource {
            override suspend fun list(dir: String): Listing = try {
                val result = termux.run(ClineCommands.listDirectories(dir))
                if (result.exitCode != 0 || !result.delivered) Listing.Failed(result.lastStderrLine ?: result.errmsg) else Listing.Listed(DirectoryListing.from(result))
            } catch (e: TermuxUnavailable) {
                Listing.Blocked(e)
            }

            override suspend fun hasSharedStorage(): Boolean? = try {
                termux.run(ClineCommands.hasSharedStorage()).ok
            } catch (_: TermuxUnavailable) {
                null
            }

            override fun setupStorage(): Deferred<TermuxResult> = termux.runDetached(ClineCommands.setupStorage()).earlyExit
        }
    }
}

/**
 * SP S9 folder browser (desktop `RemoteDirectoryPicker`: Home / Parent / path / Refresh, "Use
 * this folder"), over RUN_COMMAND `find` in Termux. Tapping a folder navigates into it and
 * pushes it on the history; Home, Up and the root chips push their target; Refresh does not;
 * back pops one folder until the start, then the host closes the browser.
 */
class FolderBrowserViewModel(
    private val savedState: SavedStateHandle,
    start: String?,
    private val store: WorkspaceStore,
    private val source: DirectorySource,
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY },
) : ViewModel() {

    private val _state = MutableStateFlow(FolderBrowserUiState())
    val uiState: StateFlow<FolderBrowserUiState> = _state.asStateFlow()

    private val _effects = Channel<FolderBrowserEffect>(Channel.BUFFERED)
    val effects: Flow<FolderBrowserEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var storageJob: Job? = null

    init {
        val saved = savedState.get<ArrayList<String>>(KEY_HISTORY)?.takeIf { it.isNotEmpty() }
        val history = saved ?: arrayListOf(normalize(start ?: TERMUX_HOME))
        setHistory(history)
        load(history.last())
        checkSharedStorage()
    }

    /** A folder row: navigate into it (no selection yet). */
    fun open(entry: FolderEntry) = push(entry.path)

    fun home() = push(TERMUX_HOME)

    fun up() {
        _state.value.parent?.let(::push)
    }

    fun sharedStorage() = push(TERMUX_SHARED_STORAGE)

    /** Refresh reloads the current folder without touching the history. */
    fun refresh() {
        if (_state.value.loading) return
        load(_state.value.path)
    }

    /** Back inside the browser: pops one visited folder; false at the starting folder (the host closes). */
    fun back(): Boolean {
        val history = _state.value.history
        if (history.size <= 1) return false
        val next = ArrayList(history.dropLast(1))
        setHistory(next)
        load(next.last())
        return true
    }

    /** "Use this folder": validate the current path → adopt → [FolderBrowserEffect.Done]. */
    fun useThisFolder() {
        val current = _state.value
        if (!current.canUse) return
        viewModelScope.launch {
            _state.update { it.copy(using = true, useError = null) }
            when (val result = store.open(current.path)) {
                is WorkspaceValidation.Valid -> {
                    _state.update { it.copy(using = false) }
                    _effects.send(FolderBrowserEffect.Done(result.path))
                }
                is WorkspaceValidation.Invalid -> _state.update { it.copy(using = false, useError = current.path) }
                is WorkspaceValidation.Blocked -> {
                    val block = KernelBlock.of(result.error)
                    _state.update { if (block == null) it.copy(using = false, useError = current.path) else it.copy(using = false, blocked = block) }
                }
            }
        }
    }

    /** "Set up shared storage": `termux-setup-storage` in the foreground, then re-check (also on resume). */
    fun setUpSharedStorage() {
        if (_state.value.sharedStorage == SharedStorageState.SettingUp) return
        val exit = try {
            source.setupStorage()
        } catch (e: TermuxUnavailable) {
            val block = KernelBlock.of(e)
            _state.update { if (block == null) it.copy(storageError = e.message) else it.copy(blocked = block) }
            return
        }
        _state.update { it.copy(sharedStorage = SharedStorageState.SettingUp, storageError = null) }
        viewModelScope.launch { _effects.send(FolderBrowserEffect.BringTermuxForward) }
        storageJob?.cancel()
        storageJob = viewModelScope.launch {
            val result = try {
                exit.await()
            } catch (e: TermuxUnavailable) {
                val block = KernelBlock.of(e)
                _state.update {
                    val failed = it.copy(sharedStorage = SharedStorageState.NotSetUp)
                    if (block == null) failed.copy(storageError = e.message) else failed.copy(blocked = block)
                }
                return@launch
            }
            if (result.ok) {
                recheckStorage(reloadShared = true, setupRunning = false)
            } else {
                _state.update {
                    it.copy(sharedStorage = SharedStorageState.NotSetUp, storageError = result.lastStderrLine ?: result.errmsg)
                }
            }
        }
    }

    /** The screen came back to the front (after Termux's permission prompt): re-check storage. */
    fun onResumed() {
        if (_state.value.sharedStorage == SharedStorageState.Checking) return
        checkSharedStorage(reloadShared = true)
    }

    /** The KernelBlockedCard's "Retry": reload the current folder. */
    fun retry() {
        _state.update { it.copy(blocked = null) }
        load(_state.value.path)
        checkSharedStorage()
    }

    private fun push(target: String) {
        val path = normalize(target)
        val current = _state.value
        if (current.loading && path == current.path) return
        if (path == current.path) {
            load(path)
            return
        }
        val next = ArrayList(current.history + path)
        setHistory(next)
        load(path)
    }

    private fun setHistory(history: ArrayList<String>) {
        savedState[KEY_HISTORY] = history
        val path = history.last()
        _state.update {
            it.copy(
                path = path,
                parent = parentOf(path),
                atHome = path == TERMUX_HOME,
                history = history.toList(),
                useError = null,
            )
        }
    }

    private fun load(path: String) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null, listFailed = false, truncated = false, entries = emptyList()) }
        loadJob = viewModelScope.launch {
            val listing = source.list(path)
            if (_state.value.path != path) return@launch
            when (listing) {
                is DirectorySource.Listing.Listed -> _state.update {
                    it.copy(
                        loading = false,
                        entries = entriesOf(listing.listing.directories),
                        truncated = listing.listing.truncated,
                        blocked = null,
                    )
                }
                is DirectorySource.Listing.Failed -> _state.update {
                    it.copy(loading = false, listFailed = true, error = listing.message?.takeIf { msg -> msg.isNotBlank() })
                }
                is DirectorySource.Listing.Blocked -> {
                    val block = KernelBlock.of(listing.error)
                    _state.update {
                        if (block == null) it.copy(loading = false, listFailed = true, error = listing.error.message) else it.copy(loading = false, blocked = block)
                    }
                }
            }
        }
    }

    private fun checkSharedStorage(reloadShared: Boolean = false) {
        viewModelScope.launch { recheckStorage(reloadShared, setupRunning = storageJob?.isActive == true) }
    }

    /**
     * `find ~/storage -maxdepth 0`. While a foreground setup still runs (the user is in Termux's
     * permission prompt) a negative answer keeps "SettingUp"; a positive one wins at once.
     */
    private suspend fun recheckStorage(reloadShared: Boolean, setupRunning: Boolean) {
        if (_state.value.sharedStorage != SharedStorageState.SettingUp) {
            _state.update { it.copy(sharedStorage = SharedStorageState.Checking) }
        }
        val available = source.hasSharedStorage()
        val next = when (available) {
            true -> SharedStorageState.Available
            false -> if (setupRunning) SharedStorageState.SettingUp else SharedStorageState.NotSetUp
            null -> SharedStorageState.Unknown
        }
        _state.update { it.copy(sharedStorage = next, storageError = if (next == SharedStorageState.Available) null else it.storageError) }
        if (available == true && reloadShared && _state.value.inSharedStorage && _state.value.listFailed) load(_state.value.path)
    }

    private fun entriesOf(lines: List<String>): List<FolderEntry> = lines
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .map { FolderEntry(name = it.substringAfterLast('/'), path = it) }
        .sortedWith { a, b -> collator.compare(a.name, b.name) }

    companion object {
        const val KEY_HISTORY = "folderBrowser.history"

        /** `~` expanded by the app (KC: no expansion in RUN_COMMAND arguments), trailing slashes dropped. */
        fun normalize(path: String): String {
            val expanded = expandHomePath(path.trim().ifEmpty { TERMUX_HOME })
            val trimmed = expanded.trimEnd('/')
            return trimmed.ifEmpty { "/" }
        }

        /** The parent computed client-side; null only at `/` (the app invents no boundary, S9.3). */
        fun parentOf(path: String): String? {
            if (path == "/") return null
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            return parent.ifEmpty { "/" }
        }

        fun factory(graph: AppGraph, start: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FolderBrowserViewModel(
                    savedState = createSavedStateHandle(),
                    start = start,
                    store = graph.workspaceStore,
                    source = DirectorySource.of(graph.termux),
                )
            }
        }
    }
}
