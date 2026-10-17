package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettings
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsPatch
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Why Settings → Context compaction could not save; the screen picks the sentence. */
sealed interface CompactionSaveError {
    /** The merge script refused a `global-settings.json` it could not parse (exit 3); the file is unchanged. */
    data object FileUnreadable : CompactionSaveError

    /** Termux did not run the command (not installed, no permission, no result in time). */
    data object TermuxUnavailable : CompactionSaveError

    /** Any other failure; [detail] is the command's own last stderr line, or blank. */
    data class Failed(val detail: String) : CompactionSaveError
}

class CompactionSaveException(val error: CompactionSaveError) : Exception("compaction setting not saved")

/**
 * The compaction mode every `session.create` carries (G1, A11 row 2): the last read of the
 * kernel's `global-settings.json` (seam S1), shared with the terminal. Refreshed on app start,
 * when Settings opens and before a create when the last read is older than
 * [Config.staleAfterMs]; a failed read keeps the last known value, and with none the sessions
 * get `{enabled:true}` (core's default strategy). Written only by Settings ([set]).
 *
 * Reads run in [scope] (the app scope), one at a time and shared by every caller that asks
 * meanwhile, so a create that stops waiting does not cancel the read: its result still lands in
 * [settings] for the next create.
 */
class CompactionModeSource(
    /** `cat global-settings.json` → [KernelGlobalSettings.readOrNull]; null = unknown (Termux unreachable, bad JSON). */
    private val read: suspend () -> KernelGlobalSettings?,
    /** The S1 merge write; returns the merged file, throws [CompactionSaveException]. */
    private val write: suspend (KernelGlobalSettingsPatch) -> KernelGlobalSettings,
    private val nowMs: () -> Long,
    /** Where reads run: outlives any one caller, so a late answer is kept. */
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {
    data class Config(
        /** "Before a create when older than 60 s" (batch 1 brief §6.1). */
        val staleAfterMs: Long = 60_000L,
        /** How long a create waits for that refresh; a later answer still updates [settings] for the next create. */
        val createWaitMs: Long = 1_500L,
    )

    private val _settings = MutableStateFlow<KernelGlobalSettings?>(null)

    /** The last known settings; null until a read succeeded. */
    val settings: StateFlow<KernelGlobalSettings?> = _settings.asStateFlow()

    /** The Settings row's value; null while unknown. */
    val mode: Flow<CompactionMode?> = settings.map { it?.let(CompactionMode::of) }.distinctUntilChanged()

    @Volatile private var readAtMs: Long? = null
    private val writeLock = Mutex()
    private val inFlightLock = Any()
    private var inFlight: Deferred<KernelGlobalSettings?>? = null

    /** Bumped by every adopted write: a read that started before it must not overwrite it. */
    private val writes = AtomicLong(0)

    /** `sessionConfig.compaction` from the last known settings (`{enabled:true}` while unknown). */
    fun current(): JsonObject = compactionConfig(_settings.value)

    /** Reads the file now (or joins the read in flight); returns the last known settings afterwards. */
    suspend fun refresh(): KernelGlobalSettings? = readShared().await()

    /** App start: a refresh that never blocks the caller. */
    fun refreshInBackground() {
        readShared()
    }

    /** Before a `session.create`: refresh a stale value (waiting at most [Config.createWaitMs]), then [current]. */
    suspend fun forCreate(): JsonObject {
        val at = readAtMs
        if (at == null || nowMs() - at > config.staleAfterMs) {
            val pending = readShared()
            withTimeoutOrNull(config.createWaitMs) { pending.await() }
        }
        return current()
    }

    /** Settings → Context compaction: writes [mode] and adopts the merged file. */
    suspend fun set(mode: CompactionMode): KernelGlobalSettings = writeLock.withLock {
        val merged = write(mode.patch())
        writes.incrementAndGet()
        _settings.value = merged
        readAtMs = nowMs()
        merged
    }

    /** The read in flight, or a new one in [scope]. */
    private fun readShared(): Deferred<KernelGlobalSettings?> = synchronized(inFlightLock) {
        inFlight?.takeIf { it.isActive } ?: scope.async { readOnce() }.also { inFlight = it }
    }

    private suspend fun readOnce(): KernelGlobalSettings? {
        val before = writes.get()
        val fresh = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fresh != null && writes.get() == before) {
            _settings.value = fresh
            readAtMs = nowMs()
        }
        return _settings.value
    }

    companion object {
        /** The production reader and writer over the RUN_COMMAND bridge. */
        fun of(termux: TermuxExecutor, scope: CoroutineScope, nowMs: () -> Long): CompactionModeSource = CompactionModeSource(
            read = {
                try {
                    KernelGlobalSettings.readOrNull(termux.run(ClineCommands.readGlobalSettings()))
                } catch (_: TermuxUnavailable) {
                    null
                }
            },
            write = { patch ->
                val result = try {
                    termux.run(KernelGlobalSettingsCommands.write(patch))
                } catch (_: TermuxUnavailable) {
                    throw CompactionSaveException(CompactionSaveError.TermuxUnavailable)
                }
                when {
                    result.ok -> KernelGlobalSettings.parse(result.stdout)
                        ?: throw CompactionSaveException(CompactionSaveError.Failed(""))
                    !result.delivered -> throw CompactionSaveException(CompactionSaveError.TermuxUnavailable)
                    result.exitCode == KernelGlobalSettingsCommands.EXIT_UNREADABLE -> throw CompactionSaveException(CompactionSaveError.FileUnreadable)
                    else -> throw CompactionSaveException(CompactionSaveError.Failed(result.lastStderrLine.orEmpty()))
                }
            },
            nowMs = nowMs,
            scope = scope,
        )
    }
}
