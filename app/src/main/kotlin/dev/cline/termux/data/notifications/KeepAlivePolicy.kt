package dev.cline.termux.data.notifications

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.NotifyFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the "Cline is working..." notification shows (customize-and-rest.md §12.3). Titles are kernel data, null when untitled. */
sealed interface KeepAliveVariant {
    /** One run working; the chronometer counts from [startedAt] (`run.started`). */
    data class Working(val sessionId: String, val title: String?, val startedAt: Long) : KeepAliveVariant

    /**
     * A first send whose session the Hub is still creating (`session.create` can take seconds while
     * MCP servers start): no session id yet, so no "Stop agent" and the tap opens Home.
     */
    data class Sending(val title: String?, val startedAt: Long) : KeepAliveVariant

    /** One run waiting for an approval of [toolName] (`PendingApproval.toolName`, possibly blank). */
    data class WaitingApproval(val sessionId: String, val toolName: String) : KeepAliveVariant

    /** One run waiting for an answer. */
    data class WaitingAnswer(val sessionId: String, val title: String?) : KeepAliveVariant

    /** Several sessions hold the service; [titles] in run-start order (null = untitled). */
    data class Several(val titles: List<String?>) : KeepAliveVariant {
        val count: Int get() = titles.size
    }

    /** "Stop agent" was confirmed (or the in-app stop is in flight) until the terminal `run.*`. */
    data class Stopping(val sessionId: String, val title: String?) : KeepAliveVariant

    /** `notifications.keepConnected` with nothing running. */
    data object ConnectedIdle : KeepAliveVariant

    data object None : KeepAliveVariant
}

/** A first send in flight before its session exists ([KeepAlivePolicy.setFirstSend]). */
data class PendingFirstSend(val title: String?, val startedAt: Long)

/**
 * The policy's output (docs/07 §4.7). [hold] is debounced: it turns false only after the raw
 * rule has been false for [KeepAlivePolicy.RELEASE_DEBOUNCE_MS]; meanwhile the last held
 * [variant] stays. [reconnecting] (`Busy(Reconnecting)`) replaces the text with "Reconnecting...";
 * [stopAvailable] (`KernelGate.Ready`) shows "Stop agent".
 */
@Immutable
data class HoldState(
    val hold: Boolean,
    val variant: KeepAliveVariant,
    val reconnecting: Boolean = false,
    val stopAvailable: Boolean = false,
) {
    companion object {
        val Released = HoldState(hold = false, variant = KeepAliveVariant.None)
    }
}

/** Whether the foreground service runs (customize-and-rest.md §12.2; docs/07 §9 `KeepAlive.Denied`). */
sealed interface KeepAlive {
    data object Inactive : KeepAlive
    data object Running : KeepAlive

    /** `ForegroundServiceStartNotAllowedException`: SP S3's "Background connection" row says so. */
    data object Denied : KeepAlive
}

/**
 * When the foreground service is held (customize-and-rest.md §12.2, composer-approvals.md §0.7):
 *
 * ```
 * hold = anyRun(phase in {Starting, Running, AwaitingUser, Stopping})
 *     || approvals.hasPending || questions.hasPending
 *     || a first send is creating its session (the user just sent a prompt, §12.2)
 *     || (keepConnected && gate is Ready && app in background)
 * ```
 *
 * Every term counts only the sessions in [states] (already restricted to `KnownSessions`).
 * Nothing holds while the gate is `Down` or `Blocked`: with the socket gone no event can end
 * a run, so the service would otherwise outlive a dead Hub (§12.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAlivePolicy(
    states: Flow<Map<String, NotifyFacts>>,
    keepConnected: Flow<Boolean>,
    gate: Flow<KernelGate>,
    foreground: Flow<Boolean>,
    scope: CoroutineScope,
    private val releaseDebounceMs: Long = RELEASE_DEBOUNCE_MS,
) {
    private data class Inputs(
        val states: Map<String, NotifyFacts> = emptyMap(),
        val keepConnected: Boolean = false,
        val gate: KernelGate? = null,
        val foreground: Boolean = true,
        val stopping: Set<String> = emptySet(),
        val firstSends: Map<String, PendingFirstSend> = emptyMap(),
    )

    private val stopConfirmed = MutableStateFlow<Set<String>>(emptySet())
    private val firstSends = MutableStateFlow<Map<String, PendingFirstSend>>(emptyMap())

    private val inputs: StateFlow<Inputs> =
        combine(
            combine(states, keepConnected, gate, foreground) { s, keep, g, fg -> Inputs(s, keep, g, fg) },
            stopConfirmed,
            firstSends,
        ) { base, stopping, sends -> base.copy(stopping = stopping, firstSends = sends) }
            .stateIn(scope, SharingStarted.Eagerly, Inputs())

    @Volatile
    private var lastEmitted: HoldState = HoldState.Released

    /** The debounced hold the service renders. */
    val state: StateFlow<HoldState> = inputs
        .map(::compute)
        .distinctUntilChanged()
        .transformLatest { next ->
            if (!next.hold && lastEmitted.hold) {
                delay(releaseDebounceMs)
            }
            lastEmitted = next
            emit(next)
        }
        .stateIn(scope, SharingStarted.Eagerly, HoldState.Released)

    private val _status = MutableStateFlow<KeepAlive>(KeepAlive.Inactive)
    val status: StateFlow<KeepAlive> = _status.asStateFlow()

    init {
        // A confirmed stop lasts until the run ends (any terminal run.* clears the busy phase).
        scope.launch {
            states.collect { map ->
                stopConfirmed.update { ids -> ids.filterTo(HashSet()) { id -> map[id]?.runPhase?.isBusy == true } }
            }
        }
    }

    /** True when the raw rule holds if the app were in the background now (start at `ON_PAUSE`, §12.2). */
    fun wouldHoldInBackground(): Boolean = compute(inputs.value.copy(foreground = false)).hold

    /** True when the raw (undebounced) rule holds now. */
    fun holdsNow(): Boolean = compute(inputs.value).hold

    /** The `run.abort` reply was ok: show "Stopping..." until the terminal event (§12.3 "Stop agent"). */
    fun onStopConfirmed(sessionId: String) {
        if (inputs.value.states[sessionId]?.runPhase?.isBusy == true) stopConfirmed.update { it + sessionId }
    }

    /**
     * A first send of [owner] (its local id) is creating its session ([send]), or no longer is
     * (null): from the composer's send until the prompt reached the new session, whose own run
     * phase holds from then on. Without it, Home pressed while `session.create` is pending finds
     * nothing to hold at `ON_PAUSE`, and the run then goes unwatched with the process frozen.
     */
    fun setFirstSend(owner: String, send: PendingFirstSend?) {
        firstSends.update { if (send == null) it - owner else it + (owner to send) }
    }

    fun reportRunning() {
        _status.value = KeepAlive.Running
    }

    fun reportStopped() {
        if (_status.value == KeepAlive.Running) _status.value = KeepAlive.Inactive
    }

    fun reportDenied() {
        _status.value = KeepAlive.Denied
    }

    private fun compute(input: Inputs): HoldState {
        val gate = input.gate ?: return HoldState.Released
        val socketUsable = gate is KernelGate.Ready || gate is KernelGate.Busy || gate is KernelGate.DegradedNoCommands
        if (!socketUsable) return HoldState.Released
        val reconnecting = gate is KernelGate.Busy && gate.kind == KernelGate.BusyKind.Reconnecting
        val stopAvailable = gate is KernelGate.Ready
        val active = input.states.values
            .filter { s -> s.runPhase.isBusy || s.hasPendingInput || s.sessionId in input.stopping }
            .sortedWith(compareBy({ it.activeRun?.startedAt ?: Long.MAX_VALUE }, { it.sessionId }))
        val sending = input.firstSends.values.sortedBy { it.startedAt }
        val variant = when {
            active.size + sending.size >= 2 ->
                KeepAliveVariant.Several(active.map { it.title.cleanTitle() } + sending.map { it.title.cleanTitle() })
            active.size == 1 -> single(active[0], input.stopping)
            sending.size == 1 -> KeepAliveVariant.Sending(sending[0].title.cleanTitle(), sending[0].startedAt)
            input.keepConnected && gate is KernelGate.Ready && !input.foreground -> KeepAliveVariant.ConnectedIdle
            else -> return HoldState.Released
        }
        return HoldState(hold = true, variant = variant, reconnecting = reconnecting, stopAvailable = stopAvailable)
    }

    private fun single(f: NotifyFacts, stopping: Set<String>): KeepAliveVariant {
        val title = f.title.cleanTitle()
        return when {
            f.sessionId in stopping || f.runPhase == RunPhase.Stopping ->
                KeepAliveVariant.Stopping(f.sessionId, title)
            f.approvals.isNotEmpty() ->
                KeepAliveVariant.WaitingApproval(f.sessionId, f.approvals.values.minBy { it.requestedAt }.toolName)
            f.questions.isNotEmpty() -> KeepAliveVariant.WaitingAnswer(f.sessionId, title)
            else -> KeepAliveVariant.Working(f.sessionId, title, f.activeRun?.startedAt ?: f.eventAt ?: 0L)
        }
    }

    private fun String?.cleanTitle(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** §12.2: iterations and approval → run transitions must not flap the notification. */
        const val RELEASE_DEBOUNCE_MS = 10_000L
    }
}
