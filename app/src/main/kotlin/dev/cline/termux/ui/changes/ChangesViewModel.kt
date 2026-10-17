package dev.cline.termux.ui.changes

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.data.diff.DiffStoreState
import dev.cline.termux.data.diff.SessionDiff
import dev.cline.termux.data.diff.UnifiedDiffText
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The session facts Changes reads (title for the share subject, busy for the streaming state). */
internal data class ChangesSessionInfo(val title: String?, val busy: Boolean)

/** The screen-local part of [ChangesUiState] (all of it survives rotation and process death but [copiedPath]). */
private data class ChangesLocal(
    val collapsed: Set<String>,
    val selectedPath: String?,
    val shown: Map<HunkKey, Int>,
    val copiedPath: String? = null,
)

/**
 * CT A.8 `ChangesViewModel(sessionId)`. Collects the session's [DiffStoreState] (shared with
 * the header chip and Tool detail), the session record (title, status) and the kernel
 * connection. The only Hub command it causes is the re-seed `session.messages` on Retry, or
 * once automatically when the kernel reconnects after a failed seed.
 *
 * File toggles, the two-pane selection and "Show more" counts live in [savedState]; they last
 * as long as this screen instance (desktop `collapsedFiles` is component state), so a reopened
 * Changes starts with every file expanded again.
 */
class ChangesViewModel(
    val sessionId: String,
    private val savedState: SavedStateHandle,
    diff: Flow<DiffStoreState>,
    session: Flow<SessionState>,
    kernel: Flow<KernelConnectionVisual>,
    removedSessions: Flow<String>,
    private val retrySeed: suspend () -> Unit,
    private val startKernel: suspend () -> Unit,
    /** The localised "No hunk details available." for the share text. */
    private val noHunksNote: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Diagnostic sink for failure reasons (class names only, never payloads). */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) : ViewModel() {

    private val local = MutableStateFlow(
        ChangesLocal(
            collapsed = savedState.get<ArrayList<String>>(KEY_COLLAPSED)?.toSet().orEmpty(),
            selectedPath = savedState[KEY_SELECTED],
            shown = decodeShown(savedState.get<ArrayList<String>>(KEY_SHOWN)),
        ),
    )

    private val _effects = Channel<ChangesEffect>(Channel.BUFFERED)
    val effects: Flow<ChangesEffect> = _effects.receiveAsFlow()

    // Transition memory of this screen instance (A.3 "Summary drops to zero", A.5 announcements).
    private var hadFiles = false
    private var lastReadyAt: Long? = null
    private var lastAnnounceAt = Long.MIN_VALUE / 2
    private var wasConnected: Boolean? = null
    private var copiedJob: Job? = null
    private var latest: ChangesUiState = ChangesUiState(sessionId)

    private val sessionInfo: Flow<ChangesSessionInfo> = session
        .map { s -> ChangesSessionInfo(s.facts.title?.takeIf { it.isNotBlank() }, s.facts.status == SessionStatus.Running || s.facts.status == SessionStatus.Pending) }
        .distinctUntilChanged()

    val state: StateFlow<ChangesUiState> = combine(diff, sessionInfo, kernel, local) { d, info, k, l ->
        val content = contentFor(d, k)
        ChangesUiState(
            sessionId = sessionId,
            sessionTitle = info.title,
            kernel = k,
            content = content,
            isSessionBusy = info.busy,
            collapsed = l.collapsed,
            copiedPath = l.copiedPath,
            selectedPath = l.selectedPath,
            shownLinesPerHunk = l.shown,
        )
    }
        .onEach { onState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChangesUiState(sessionId))

    init {
        viewModelScope.launch {
            removedSessions.collect { id -> if (id == sessionId) _effects.send(ChangesEffect.Close) }
        }
    }

    // ---- events (A.8) -----------------------------------------------------------------------

    /**
     * The supporting pane reuses one ViewModel per session in the chat's store; each opening
     * of the pane is a new screen instance whose toggles start fresh (desktop component state),
     * while rotation and process death keep [instanceId] (saved by the pane) and the toggles.
     */
    fun bindInstance(instanceId: String) {
        if (savedState.get<String>(KEY_INSTANCE) == instanceId) return
        val first = savedState.get<String>(KEY_INSTANCE) == null
        savedState[KEY_INSTANCE] = instanceId
        if (first) return
        hadFiles = false
        lastReadyAt = null
        copiedJob?.cancel()
        local.value = ChangesLocal(collapsed = emptySet(), selectedPath = null, shown = emptyMap())
        updateLocal { it }
    }

    fun onToggle(path: String) = updateLocal { l ->
        l.copy(collapsed = if (path in l.collapsed) l.collapsed - path else l.collapsed + path)
    }

    fun onExpandAll() = updateLocal { it.copy(collapsed = emptySet()) }

    fun onCollapseAll() = updateLocal { l -> l.copy(collapsed = latest.files.mapTo(LinkedHashSet()) { it.path }) }

    fun onSelect(path: String) = updateLocal { it.copy(selectedPath = path) }

    fun onShowMore(key: HunkKey) = updateLocal { l ->
        l.copy(shown = l.shown + (key to ((l.shown[key] ?: ChangesUiState.PAGE_ROWS) + ChangesUiState.PAGE_ROWS)))
    }

    /** Copy button / "Copy file path": the absolute Termux path, as the desktop copies the resolved path (A.7). */
    fun onCopyPath(path: String) {
        val ready = latest.content as? ChangesUiState.Content.Ready ?: return
        if (ready.files.none { it.path == path }) return
        val absolute = SessionDiff.resolveAbsolutePath(path, cwd)
        _effects.trySend(ChangesEffect.Copy(path, absolute))
    }

    /** The clipboard write succeeded: the icon shows a check for 1600 ms. */
    fun onCopied(path: String) {
        copiedJob?.cancel()
        local.update { it.copy(copiedPath = path) }
        copiedJob = viewModelScope.launch {
            delay(COPIED_MS)
            local.update { if (it.copiedPath == path) it.copy(copiedPath = null) else it }
        }
    }

    fun onShareFile(path: String) {
        val file = latest.files.firstOrNull { it.path == path } ?: return
        share { UnifiedDiffText.forFile(file, noHunksNote) }
    }

    fun onShareAll() {
        val files = latest.files
        if (files.isEmpty()) return
        share { UnifiedDiffText.forSession(files, noHunksNote) }
    }

    /** Re-seeds `session.messages` (A.5 "Error" → Retry). */
    fun onRetry() {
        viewModelScope.launch { runSafely("retry") { retrySeed() } }
    }

    /** "Start kernel" outside the shell (the shell's own action is preferred; KS F.6). */
    fun onStartKernel() {
        viewModelScope.launch { runSafely("start kernel") { startKernel() } }
    }

    // ---- internals ------------------------------------------------------------------------------

    private var cwd: String? = null

    private fun contentFor(d: DiffStoreState, k: KernelConnectionVisual): ChangesUiState.Content = when (d) {
        DiffStoreState.Loading -> if (k.down) ChangesUiState.Content.Unavailable else ChangesUiState.Content.Loading
        is DiffStoreState.Failed -> when {
            k.connected -> ChangesUiState.Content.Failed(d.message)
            k.down -> ChangesUiState.Content.Unavailable
            // Reconnecting: the seed is retried on connect (onState); keep the loading state meanwhile.
            else -> ChangesUiState.Content.Loading
        }
        is DiffStoreState.Ready -> {
            cwd = d.cwd
            ChangesUiState.Content.Ready(
                files = d.diff.fileDiffs,
                summary = d.diff.summary,
                changedPaths = d.changedPaths,
                anyTruncated = d.diff.fileDiffs.any { it.hunksTruncated },
                updatedAt = d.updatedAt,
            )
        }
    }

    private suspend fun onState(state: ChangesUiState) {
        latest = state
        val connected = state.kernel.connected
        val reconnected = wasConnected == false && connected
        wasConnected = connected
        val content = state.content
        if (reconnected && (content is ChangesUiState.Content.Failed || content is ChangesUiState.Content.Loading)) {
            runSafely("reseed after reconnect") { retrySeed() }
        }
        if (content !is ChangesUiState.Content.Ready) return
        val count = content.files.size
        if (count == 0) {
            // Only a transition from files to none closes; a fresh seed to zero shows Empty.
            if (hadFiles) {
                hadFiles = false
                _effects.send(ChangesEffect.Close)
            }
            lastReadyAt = content.updatedAt
            return
        }
        hadFiles = true
        val previous = lastReadyAt
        lastReadyAt = content.updatedAt
        if (previous != null && previous != content.updatedAt && content.changedPaths.isNotEmpty()) {
            val now = nowMs()
            if (now - lastAnnounceAt >= ANNOUNCE_INTERVAL_MS) {
                lastAnnounceAt = now
                _effects.send(ChangesEffect.Announce(count))
            }
        }
    }

    private fun share(build: () -> String) {
        val title = latest.sessionTitle
        viewModelScope.launch {
            val text = withContext(compute) { build() }
            _effects.send(ChangesEffect.Share(text, "$sessionId.diff", title))
        }
    }

    private fun updateLocal(transform: (ChangesLocal) -> ChangesLocal) {
        val next = local.updateAndGet(transform)
        savedState[KEY_COLLAPSED] = ArrayList(next.collapsed)
        savedState[KEY_SELECTED] = next.selectedPath
        savedState[KEY_SHOWN] = encodeShown(next.shown)
    }

    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The store / coordinator report outcomes through their own state; log the reason only.
            warn("$what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val COPIED_MS = 1_600L
        const val ANNOUNCE_INTERVAL_MS = 5_000L
        private const val TAG = "ClineChanges"
        internal const val KEY_COLLAPSED = "changes.collapsed"
        internal const val KEY_SELECTED = "changes.selected"
        internal const val KEY_SHOWN = "changes.shown"
        internal const val KEY_INSTANCE = "changes.instance"

        /** `count|index|path` per entry: SavedStateHandle only stores Bundle types. */
        internal fun encodeShown(shown: Map<HunkKey, Int>): ArrayList<String> =
            shown.mapTo(ArrayList()) { (key, count) -> "$count|${key.index}|${key.path}" }

        internal fun decodeShown(raw: List<String>?): Map<HunkKey, Int> = raw.orEmpty().mapNotNull { entry ->
            val parts = entry.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val count = parts[0].toIntOrNull() ?: return@mapNotNull null
            val index = parts[1].toIntOrNull() ?: return@mapNotNull null
            HunkKey(parts[2], index) to count
        }.toMap()

        /** The kernel projection for these screens, built off Main (docs/07 §8: the coordinator's lazy init reads DataStore). */
        fun kernelVisual(graph: AppGraph): Flow<KernelConnectionVisual> = flow {
            val kernel = graph.kernel
            emitAll(combine(kernel.gate, kernel.state) { gate, state -> KernelConnectionVisual(gate, state.hubInfo?.port) })
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

        fun factory(graph: AppGraph, sessionId: String, noHunksNote: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChangesViewModel(
                    sessionId = sessionId,
                    savedState = createSavedStateHandle(),
                    diff = flow { emitAll(graph.diffStores.state(sessionId)) }.flowOn(Dispatchers.Default),
                    session = flow { emitAll(graph.sessionStore.state(sessionId)) }.flowOn(Dispatchers.Default),
                    kernel = kernelVisual(graph),
                    removedSessions = flow { emitAll(graph.sessionsRepository.removed) }.flowOn(Dispatchers.Default),
                    retrySeed = { graph.diffStores.retry(sessionId) },
                    startKernel = { graph.kernel.ensureConnected(startIfDown = true, userInitiated = true) },
                    noHunksNote = noHunksNote,
                    nowMs = { graph.clock.millis() },
                )
            }
        }
    }
}
