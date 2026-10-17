package dev.cline.termux.data.approvals

import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.session.AUTO_APPROVE_TOOLS_KEY
import dev.cline.termux.data.session.RunEnded
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.arr
import dev.cline.termux.data.session.bool
import dev.cline.termux.data.session.obj
import dev.cline.termux.data.session.str
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Applies a changed global auto-approve to the sessions the app drives, at their run
 * boundaries (decided A1; auto-approve-control.md §1 item 3, §6; batch 1 brief §7.1).
 *
 * A session's tool policies are fixed when it is created (`metadata.autoApproveTools`), so a
 * session whose value differs from the global mode is restarted with its messages and the new
 * policies ([SessionStore.restart]: `session.detach`, then the resume path), silently:
 *
 * - (a) when a run this client started ends in a session it has open, for any reason,
 *   including the user's Stop;
 * - (b) just before the app sends to an idle session ([beforeSend]; the send waits, as it does
 *   for a resume).
 *
 * Never when another participant is attached (the terminal or the desktop is driving it),
 * never at the end of a run another client started, never while a run is going, and not when
 * the Hub's record already shows the global value (a terminal that restarted it itself).
 *
 * The session's policy is the one its live runtime was created with when that create was seen
 * ([SessionState.createdAutoApprove]: this app's own create, or a `session.created` event). It
 * outranks the Hub's records: core 0.0.91 stamps a resume's policy only in memory, so the
 * `session.updated` / `run.*` records at a run's end and `session.get` after it carry the
 * session's creation-time value again (V-P1-A1: a session resumed under Auto read Ask after its
 * run, was never restarted into Ask and went on auto-approving). Without a seen create the
 * record's `metadata.autoApproveTools` decides. The Hub drops that key on every
 * `session.update` that carries metadata (a pin), while the live runtime keeps its policy; the
 * value last seen then stands in ([SessionState.autoApproveTools]); when none was seen it is
 * unknown, and an unknown policy counts as different: one restart stamps the key again.
 */
class RunBoundaryCoordinator(
    private val store: SessionStore,
    private val approvals: ApprovalModeRepository,
    private val clientId: () -> String,
    private val scope: CoroutineScope,
    /** RUN_COMMAND is never issued from the background: a run end there decides on the last known mode. */
    private val foreground: () -> Boolean = { true },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private var job: Job? = null

    /**
     * True while the global mode is Ask and a session the app has open still runs with
     * auto-approve (it switches when that run ends): Settings' sentence (auto-approve-control.md §4).
     */
    val pendingSwitch: Flow<Boolean> = combine(approvals.mode, store.notifyFacts) { mode, facts ->
        mode == ApprovalMode.Ask && facts.values.any { f ->
            (f.runPhase.isBusy || f.status.isRunning) && store.isOpen(f.sessionId) &&
                store.state(f.sessionId).value.autoApproveTools == true
        }
    }.distinctUntilChanged()

    /** Starts listening for run ends; idempotent. */
    fun start() {
        synchronized(this) {
            if (job != null) return
            job = scope.launch { store.runEnds.collect { end -> launch { onRunEnded(end) } } }
        }
    }

    /**
     * (a): a run ended in [RunEnded.sessionId]. In an open session the setting is re-read first
     * (§6); only the end of a run this client started can restart. Returns whether it restarted.
     */
    suspend fun onRunEnded(end: RunEnded): Boolean {
        if (!store.isOpen(end.sessionId)) return false
        val mode = if (foreground()) approvals.refresh(ApprovalRefresh.RunEnd) else approvals.mode.value
        if (!end.ours || mode == ApprovalMode.Unknown) return false
        return restartIfNeeded(end.sessionId, auto = mode == ApprovalMode.Auto, config = null)
    }

    /**
     * (b): called by the chat right before a direct send to [sessionId]. Re-reads the setting
     * and restarts an idle session whose policies differ, with [config] (a picked connection)
     * when given. A failed restart fails the send (the store shows the resume banner).
     */
    suspend fun beforeSend(sessionId: String, config: SessionConfigInput?): Result<Unit> {
        // A busy session gets a queued or steered prompt, which never restarts it.
        if (running(sessionId)) return Result.success(Unit)
        val mode = approvals.refresh(ApprovalRefresh.BeforeSend, maxAgeMs = BEFORE_SEND_FRESH_MS)
        if (mode == ApprovalMode.Unknown) return Result.success(Unit)
        return runCatching { restartIfNeeded(sessionId, auto = mode == ApprovalMode.Auto, config = config, failOnError = true) }.map { }
    }

    private suspend fun restartIfNeeded(sessionId: String, auto: Boolean, config: SessionConfigInput?, failOnError: Boolean = false): Boolean =
        lockFor(sessionId).withLock {
            val state = store.state(sessionId).value
            // A not-live session resumes on its next send anyway, and every resume takes the global value.
            if (state.persistedHint || state.creating || running(sessionId)) return@withLock false
            if (state.autoApproveTools == auto) return@withLock false
            val record = store.record(sessionId) ?: return@withLock false
            // A seen create outranks the record (which may be stale); a record without the key (a
            // pin dropped it) falls back to the value last seen.
            val decide = RestartRule.shouldRestart(record, auto, clientId(), lastSeen = state.runtimeAutoApprove, created = state.createdAutoApprove)
            if (!decide) return@withLock false
            val result = store.restart(sessionId, config)
            if (failOnError) result.getOrThrow()
            result.isSuccess
        }

    /** A run is going or the Hub holds queued prompts that will start one. */
    private fun running(sessionId: String): Boolean {
        val s = store.state(sessionId).value
        return s.facts.runPhase.isBusy || s.facts.status.isRunning || s.facts.pendingPrompts.isNotEmpty()
    }

    private fun lockFor(sessionId: String): Mutex = locks.computeIfAbsent(sessionId) { Mutex() }

    /** The decision on the Hub's fresh `session.get` record (pure, unit-tested). */
    object RestartRule {
        /**
         * [created]: the policy of the live runtime's create as seen ([SessionState.createdAutoApprove]);
         * it wins over the record, whose key core resets to the creation-time value at a run's end.
         * [lastSeen]: the session's policy as last seen ([SessionState.runtimeAutoApprove]), used
         * when there is no [created] and the record lacks `metadata.autoApproveTools`. Unknown
         * counts as different: an idle session nobody else drives restarts once, and its new
         * create fixes the policy.
         */
        fun shouldRestart(record: JsonObject, auto: Boolean, clientId: String, lastSeen: Boolean? = null, created: Boolean? = null): Boolean {
            val session = record.obj("session")
            val metadata = HubPayloads.metadataOf(record)
            val current = created ?: metadata?.bool(AUTO_APPROVE_TOOLS_KEY) ?: lastSeen
            if (current == auto) return false
            val others = session?.arr("participants").orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("clientId") }
                .any { it != clientId }
            if (others) return false
            val status = SessionStatus.parse(session?.str("status") ?: (record.obj("snapshot"))?.str("status"))
            return !status.isRunning
        }
    }

    companion object {
        /**
         * A send re-reads the setting unless a read or write is at most this old (the menu pick,
         * the session open or a run end just before): the read is a Termux command.
         */
        const val BEFORE_SEND_FRESH_MS = 5_000L
    }
}
