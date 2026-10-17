package dev.cline.termux.data.composer

import dev.cline.termux.core.kernel.termux.InputHistoryCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the history file has been read (A11.6). */
enum class InputHistoryStatus { NotLoaded, Loading, Loaded, Failed }

/**
 * The recent prompts as the app knows them: [entries] newest first (at most 20), the
 * terminal's `~/.cline/data/cache/user_input_history.jsonl` plus the app's own sends since.
 */
data class InputHistory(
    val entries: List<String> = emptyList(),
    val status: InputHistoryStatus = InputHistoryStatus.NotLoaded,
) {
    val loaded: Boolean get() = status == InputHistoryStatus.Loaded
}

/** The composer's seam onto the prompt history (A11.6); tests pass a fake. */
interface InputHistorySource {
    val state: StateFlow<InputHistory>

    /** Reads the file again unless it was read (or tried) less than 30 s ago; returns at once. */
    fun refresh()

    /** Suspends until the file has been read once (reads it now when it never was); never throws. */
    suspend fun ensureLoaded()

    /**
     * A send the Hub accepted: the list shows it first at once, and the file is rewritten
     * through Termux in the background (fire and forget; a failure is logged, never shown).
     */
    fun record(prompt: String)
}

/**
 * The app's [InputHistorySource]: one cached list shared by every composer. Reads and appends
 * run one at a time (a FIFO mutex), so a read never overtakes an append queued before it; a
 * prompt recorded while a read is in flight is applied on top of what that read returns.
 *
 * Prompts are user content: only command labels, exit codes and exception types are logged.
 */
class InputHistoryRepository(
    private val termux: TermuxExecutor,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
) : InputHistorySource {
    private val _state = MutableStateFlow(InputHistory())
    override val state: StateFlow<InputHistory> = _state.asStateFlow()

    private val io = Mutex()
    private val lock = Any()

    /** Recorded prompts whose append has not finished yet, oldest first (guarded by [lock]). */
    private val pending = ArrayList<String>()
    private var lastReadAt: Long? = null
    private var inFlight: Deferred<Unit>? = null

    override fun refresh() {
        synchronized(lock) {
            if (inFlight?.isActive == true) return
            val now = clockMs()
            val last = lastReadAt
            if (last != null && now - last < refreshIntervalMs) return
            startReadLocked(now)
        }
    }

    override suspend fun ensureLoaded() {
        if (_state.value.loaded) return
        val read = synchronized(lock) {
            inFlight?.takeIf { it.isActive } ?: startReadLocked(clockMs())
        }
        read.await()
    }

    override fun record(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            pending += trimmed
            _state.update { it.copy(entries = InputHistoryCommands.prepend(it.entries, trimmed)) }
        }
        scope.launch {
            try {
                io.withLock { append(trimmed) }
            } finally {
                synchronized(lock) { pending.remove(trimmed) }
            }
        }
    }

    private fun startReadLocked(now: Long): Deferred<Unit> {
        lastReadAt = now
        if (!_state.value.loaded) _state.update { it.copy(status = InputHistoryStatus.Loading) }
        return scope.async { io.withLock { read() } }.also { inFlight = it }
    }

    private suspend fun read() {
        val result = try {
            termux.run(InputHistoryCommands.read())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${InputHistoryCommands.LABEL_READ}: ${e.javaClass.simpleName}")
            return failed()
        }
        val entries = InputHistoryCommands.readOrNull(result)
        if (entries == null) {
            log("${InputHistoryCommands.LABEL_READ} failed: exit=${result.exitCode} err=${result.err}")
            return failed()
        }
        synchronized(lock) {
            val merged = pending.fold(entries) { list, prompt -> InputHistoryCommands.prepend(list, prompt) }
            _state.value = InputHistory(merged, InputHistoryStatus.Loaded)
        }
    }

    /** A failed read keeps what is known; a list read once stays Loaded. */
    private fun failed() {
        _state.update { if (it.loaded) it else it.copy(status = InputHistoryStatus.Failed) }
    }

    private suspend fun append(prompt: String) {
        val result = try {
            termux.run(InputHistoryCommands.append(prompt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${InputHistoryCommands.LABEL_APPEND}: ${e.javaClass.simpleName}")
            return
        }
        if (!result.ok) log("${InputHistoryCommands.LABEL_APPEND} failed: exit=${result.exitCode} err=${result.err}")
    }

    companion object {
        /** At most one read per 30 s from focus and sheet opens (brief §9.1). */
        const val REFRESH_INTERVAL_MS = 30_000L
    }
}
