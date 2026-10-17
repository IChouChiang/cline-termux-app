package dev.cline.termux.ui.composer

import dev.cline.termux.data.agents.AgentsRepository
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.agents.buildSessionAgentActivity
import dev.cline.termux.data.agents.mergeAgentActivity
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.ui.agents.AgentsRosterSource
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The composer's running-agents count (desktop app/page.tsx `agentActivity`, passed to the
 * input bar as `hasRunningAgents={agentActivity.running > 0}`): the persisted roster merged with
 * the session's spawn/team tool rows, the same `mergeAgentActivity` the agents chip and roster
 * sheet use. A parent turn that finished while its agents or team still run keeps Stop
 * (chat-input-bar `canAbort = status === "running" || status === "stopping" || hasRunningAgents`).
 */
object ComposerAgents {
    private data class Derived(val activity: SessionAgentActivity, val active: Boolean)

    /** desktop `isSessionActive`: starting, running or stopping (the tally's `sessionActive`). */
    private fun derived(state: SessionState): Derived {
        val active = state.chatStatus.isBusy
        return Derived(buildSessionAgentActivity(state.messages, sessionActive = active), active)
    }

    /** `mergeAgentActivity(agents, derivedAgentActivity, {sessionActive}).running`. */
    fun running(entries: List<SessionAgentEntry>, state: SessionState): Int {
        val d = derived(state)
        return mergeAgentActivity(entries, d.activity, sessionActive = d.active).running
    }

    /**
     * The count for [sessionId] while collected: the roster ([roster], refreshed on the parent's
     * activity and polled while a child runs) combined with the tool tally of [state]. The
     * roster's `expectedTotal` is the tally's total, as in the roster sheet.
     */
    fun running(sessionId: String, state: Flow<SessionState>, roster: AgentsRosterSource): Flow<Int> {
        val expected = AtomicInteger(0)
        val tally = state.map(::derived).distinctUntilChanged()
        return combine(roster.observe(sessionId) { expected.get() }, tally) { r: AgentsRepository.Roster, d ->
            expected.set(d.activity.total)
            mergeAgentActivity(r.entries, d.activity, sessionActive = d.active).running
        }.distinctUntilChanged()
    }
}
