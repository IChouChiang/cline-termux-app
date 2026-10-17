package dev.cline.termux.ui.tool

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.diff.SessionDiff
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.data.tools.ToolLookup
import dev.cline.termux.ui.changes.ChangesViewModel
import dev.cline.termux.ui.changes.KernelConnectionVisual
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What the Tool detail screen resolves to before the local selections are applied. */
internal sealed interface ToolDetailSource {
    data object Loading : ToolDetailSource
    data object NotFound : ToolDetailSource
    data class LoadFailed(val message: String) : ToolDetailSource
    data class Found(val content: ToolDetailContent) : ToolDetailSource
}

private data class ToolLocal(
    val selectedCommand: Int,
    val streamFilter: StreamFilter,
    val proceed: ProceedState = ProceedState.Idle,
    val abortPending: Boolean = false,
    val copied: CopyTarget? = null,
)

/** One-off effects the Tool detail composable performs. */
sealed interface ToolDetailEffect {
    /** TalkBack polite "Command finished" / "Command failed" / "Command detached" (B.10). */
    data class Announce(val status: ToolDetailStatus) : ToolDetailEffect

    /** Height-compact layout: the proceed error shows as a snackbar (B.4). */
    data class ProceedFailed(val state: ProceedState.Error) : ToolDetailEffect
}

/**
 * CT B.8 `ToolDetailViewModel(sessionId, toolCallId)`. Collects the tool from the session's
 * [ToolLookup] flow (live reducer state with terminal buffers fed off the main thread and
 * published at most every 50 ms by the store; the store reads `session.messages` once when
 * the tool is not live), the kernel connection and the app-wide "Wrap lines" preference.
 * Sends `run.proceed_while_running` and `run.abort` through the injected calls. The selected
 * command and stream filter live in [savedState]; the text scale is saved by the terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolDetailViewModel(
    val sessionId: String,
    val toolCallId: String,
    private val savedState: SavedStateHandle,
    lookup: Flow<ToolLookup>,
    kernel: Flow<KernelConnectionVisual>,
    wrapLines: Flow<Boolean>,
    private val setWrapLines: suspend (Boolean) -> Unit,
    /** `run.proceed_while_running {sessionId, toolCallId}`; returns the reply payload. */
    private val proceed: suspend () -> JsonObject,
    /** `run.abort {sessionId, reason: "User pressed Stop"}`. */
    private val abort: suspend () -> Result<Unit>,
    /** Re-reads `session.messages` after a history failure. */
    private val reload: suspend () -> Unit,
    private val startKernel: suspend () -> Unit,
    /** The session cwd (`facts.cwd ?: facts.workspaceRoot`) relative file paths resolve against. */
    private val cwd: () -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    compute: CoroutineDispatcher = Dispatchers.Default,
    /** Diagnostic sink for failure reasons (class names and codes only, never payloads). */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /**
     * Holds the session open (subscribe + attach) while this screen lives, so a route restored
     * after process death, with no chat under it yet, is live and offers "Stop agent" (B.5).
     */
    holdSession: () -> Unit = {},
    private val releaseSession: () -> Unit = {},
) : ViewModel() {

    private val local = MutableStateFlow(
        ToolLocal(
            selectedCommand = savedState[KEY_COMMAND] ?: 0,
            streamFilter = savedState.get<String>(KEY_FILTER)?.let { name -> StreamFilter.entries.firstOrNull { it.name == name } } ?: StreamFilter.All,
        ),
    )

    init {
        holdSession()
    }

    override fun onCleared() {
        releaseSession()
    }

    private val _effects = Channel<ToolDetailEffect>(Channel.BUFFERED)
    val effects: Flow<ToolDetailEffect> = _effects.receiveAsFlow()

    private var copiedJob: Job? = null
    private var previousStatus: ToolDetailStatus? = null
    private var latest: ToolDetailUiState = ToolDetailUiState(sessionId, toolCallId)

    private val source: Flow<ToolDetailSource> = run {
        val parsed = ParseCache()
        lookup.map { result ->
            when (result) {
                ToolLookup.Loading -> ToolDetailSource.Loading
                ToolLookup.NotFound -> ToolDetailSource.NotFound
                is ToolLookup.HistoryFailed -> ToolDetailSource.LoadFailed(result.message)
                is ToolLookup.Found -> ToolDetailSource.Found(ToolDetailModel.build(sessionId, result.view, parsed::parse))
            }
        }.flowOn(compute).distinctUntilChanged()
    }

    /** "Running · Ns" ticks every second while the tool runs (B.4 status line). */
    private val clock: Flow<Long> = source
        .map { (it as? ToolDetailSource.Found)?.content?.runningSince != null }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (!running) {
                flowOf(0L)
            } else {
                flow {
                    while (true) {
                        emit(nowMs())
                        delay(TICK_MS)
                    }
                }
            }
        }

    val state: StateFlow<ToolDetailUiState> = combine(source, kernel, wrapLines, local, clock) { src, k, wrap, l, now ->
        compose(src, k, wrap, l, now)
    }
        .onEach { onState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ToolDetailUiState(sessionId, toolCallId))

    // ---- events (B.7) -----------------------------------------------------------------------

    fun onSelectCommand(index: Int) = updateLocal { it.copy(selectedCommand = index) }

    fun onFilter(filter: StreamFilter) = updateLocal { it.copy(streamFilter = filter) }

    fun onToggleWrap() {
        val next = !latest.wrapLines
        viewModelScope.launch { runSafely("wrap lines") { setWrapLines(next) } }
    }

    /** "Proceed while running" (B.7, CT §1.5.4 replies). */
    fun onProceed() {
        val current = latest
        if (!current.canProceed || current.proceed == ProceedState.Pending || !current.actionsEnabled) return
        local.update { it.copy(proceed = ProceedState.Pending) }
        viewModelScope.launch {
            val outcome: ProceedState = try {
                val reply = proceed()
                val count = (reply["detachedCount"] as? JsonPrimitive)?.intOrNull ?: 0
                if (count >= 1) ProceedState.Detached else ProceedState.Error(ProceedError.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubCommandException) {
                when (e.code) {
                    HubErrorCodes.UNSUPPORTED_COMMAND -> ProceedState.Error(ProceedError.Unsupported, e.hubMessage.takeIf { it.isNotBlank() })
                    // Socket dropped: re-evaluate canProceed after the reconnect (the button returns while still detachable).
                    HubErrorCodes.HUB_CONNECTION_CLOSED -> ProceedState.Idle
                    else -> {
                        warn("proceed failed: ${e.code}")
                        ProceedState.Error(ProceedError.Generic)
                    }
                }
            } catch (e: HubConnectionClosedException) {
                ProceedState.Idle
            } catch (e: Exception) {
                warn("proceed failed: ${e.javaClass.simpleName}")
                ProceedState.Error(ProceedError.Generic)
            }
            local.update { it.copy(proceed = outcome) }
            if (outcome is ProceedState.Error) _effects.trySend(ToolDetailEffect.ProceedFailed(outcome))
        }
    }

    /** "Stop agent": no confirm (desktop); disabled until the run ends or the abort fails. */
    fun onStopAgent() {
        val current = latest
        if (!current.canAbort || current.abortPending || !current.actionsEnabled) return
        local.update { it.copy(abortPending = true) }
        viewModelScope.launch {
            val result = try {
                abort()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (result.isFailure) {
                warn("abort failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                local.update { it.copy(abortPending = false) }
            }
        }
    }

    /** A copy succeeded: that target shows "Copied …" for 1600 ms. */
    fun onCopied(target: CopyTarget) {
        copiedJob?.cancel()
        local.update { it.copy(copied = target) }
        copiedJob = viewModelScope.launch {
            delay(ChangesViewModel.COPIED_MS)
            local.update { if (it.copied == target) it.copy(copied = null) else it }
        }
    }

    /** Edit layout "Copy file path": the absolute Termux path, as Changes copies it (A.7). */
    fun absolutePath(path: String): String = SessionDiff.resolveAbsolutePath(path, cwd())

    fun onRetry() {
        viewModelScope.launch { runSafely("reload") { reload() } }
    }

    fun onStartKernel() {
        viewModelScope.launch { runSafely("start kernel") { startKernel() } }
    }

    // ---- internals ------------------------------------------------------------------------------

    /** The proceed / abort offers last seen while the kernel was connected (see [compose]). */
    private var offeredWhileConnected = false to false

    private fun compose(src: ToolDetailSource, k: KernelConnectionVisual, wrap: Boolean, l: ToolLocal, now: Long): ToolDetailUiState {
        val base = ToolDetailUiState(sessionId = sessionId, toolCallId = toolCallId, kernel = k, wrapLines = wrap, streamFilter = l.streamFilter, copied = l.copied)
        return when (src) {
            // The store keeps retrying the history seed while the socket is down: without the
            // kernel nothing arrives, so the down state replaces the endless progress bar (B.5).
            ToolDetailSource.Loading -> if (k.down) base.copy(loading = false, kernelUnavailable = true) else base.copy(loading = true)
            ToolDetailSource.NotFound -> base.copy(loading = false, notFound = true)
            is ToolDetailSource.LoadFailed -> base.copy(loading = false, loadError = src.message)
            is ToolDetailSource.Found -> {
                val c = src.content
                val selected = l.selectedCommand.coerceIn(0, maxOf(0, c.commands.lastIndex))
                val stillRunning = c.status.running || c.status == ToolDetailStatus.AwaitingApproval
                // B.5 "Offline / kernel down": the thread stops being live when the socket drops,
                // which would hide the bar; the buttons stay as last offered while the tool still
                // runs, disabled through actionsEnabled with the strip explaining why.
                if (k.connected || !stillRunning) offeredWhileConnected = c.proceedOffered to c.abortOffered
                val (proceedOffered, abortOffered) = offeredWhileConnected
                base.copy(
                    loading = false,
                    toolName = c.toolName,
                    kind = c.kind,
                    icon = c.icon,
                    label = c.label,
                    status = c.status,
                    durationMs = c.durationMs ?: c.runningSince?.let { since -> (if (now > 0) now else nowMs()) - since }?.coerceAtLeast(0),
                    layout = c.layout,
                    commands = c.commands,
                    selectedCommand = selected,
                    showTerminal = c.showTerminal,
                    fileItems = c.fileItems,
                    details = c.details,
                    outputText = c.outputText,
                    submitMarkdown = c.submitMarkdown,
                    images = c.images,
                    otherMedia = c.otherMedia,
                    inputJson = c.inputJson,
                    errorText = c.errorText,
                    detachedLogPath = c.detachedLogPath,
                    canProceed = proceedOffered && l.proceed != ProceedState.Detached &&
                        !(l.proceed is ProceedState.Error && l.proceed.error == ProceedError.Finished),
                    proceed = l.proceed,
                    canAbort = abortOffered,
                    abortPending = l.abortPending && stillRunning,
                    sessionLive = c.sessionLive,
                )
            }
        }
    }

    private fun onState(state: ToolDetailUiState) {
        latest = state
        if (!state.found) return
        val before = previousStatus
        previousStatus = state.status
        val wasRunning = before != null && (before.running || before == ToolDetailStatus.AwaitingApproval)
        if (wasRunning && before != state.status) {
            when (state.status) {
                ToolDetailStatus.Completed, ToolDetailStatus.Failed, ToolDetailStatus.Detached ->
                    _effects.trySend(ToolDetailEffect.Announce(state.status))
                else -> Unit
            }
        }
        // The run ended: a pending abort is settled, and a proceed error belongs to the finished command.
        if (!state.status.running && state.status != ToolDetailStatus.AwaitingApproval) {
            local.update { l ->
                if (!l.abortPending && l.proceed == ProceedState.Idle) l else l.copy(abortPending = false, proceed = if (l.proceed == ProceedState.Pending) l.proceed else ProceedState.Idle)
            }
        }
    }

    private fun updateLocal(transform: (ToolLocal) -> ToolLocal) {
        local.update(transform)
        val l = local.value
        savedState[KEY_COMMAND] = l.selectedCommand
        savedState[KEY_FILTER] = l.streamFilter.name
    }

    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("$what failed: ${e.javaClass.simpleName}")
        }
    }

    /** History outputs re-parse only when their text changes. */
    private class ParseCache {
        private var text: String? = null
        private var snapshot: TerminalSnapshot = TerminalSnapshot.EMPTY

        fun parse(value: String): TerminalSnapshot {
            if (value != text) {
                text = value
                snapshot = TerminalBuffer.parse(value)
            }
            return snapshot
        }
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 1_000L
        private const val TAG = "ClineToolDetail"
        internal const val KEY_COMMAND = "tool.selectedCommand"
        internal const val KEY_FILTER = "tool.streamFilter"

        fun factory(graph: AppGraph, sessionId: String, toolCallId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                var hold: Job? = null
                ToolDetailViewModel(
                    sessionId = sessionId,
                    toolCallId = toolCallId,
                    savedState = createSavedStateHandle(),
                    lookup = flow { emitAll(graph.toolStores.tool(sessionId, toolCallId)) }.flowOn(Dispatchers.Default),
                    kernel = ChangesViewModel.kernelVisual(graph),
                    wrapLines = flow { emitAll(graph.prefs.toolDetailWrapLines) },
                    setWrapLines = { graph.prefs.setToolDetailWrapLines(it) },
                    proceed = { graph.sessionApi.proceedWhileRunning(sessionId, toolCallId) },
                    abort = { graph.sessionStore.abort(sessionId) },
                    reload = { graph.toolStores.store(sessionId).reload() },
                    startKernel = { graph.kernel.ensureConnected(startIfDown = true, userInitiated = true) },
                    cwd = { graph.sessionStore.state(sessionId).value.facts.let { it.cwd ?: it.workspaceRoot } },
                    nowMs = { graph.clock.millis() },
                    holdSession = { hold = graph.appScope.launch { runCatching { graph.sessionStore.open(sessionId) } } },
                    // After the open has run, so the pair always balances.
                    releaseSession = {
                        val opened = hold
                        graph.appScope.launch {
                            opened?.join()
                            runCatching { graph.sessionStore.close(sessionId) }
                        }
                    },
                )
            }
        }
    }
}
