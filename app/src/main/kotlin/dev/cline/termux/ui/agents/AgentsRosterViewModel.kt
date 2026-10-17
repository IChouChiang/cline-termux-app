package dev.cline.termux.ui.agents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.agents.AgentsRepository
import dev.cline.termux.data.agents.LastAction
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.agents.SessionAgentRunState
import dev.cline.termux.data.agents.agentEntryState
import dev.cline.termux.data.agents.buildSessionAgentActivity
import dev.cline.termux.data.agents.mergeAgentActivity
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.session.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A roster row (customize-and-rest.md §6.7 `AgentRowUi`). */
@Immutable
data class AgentRowUi(
    val sessionId: String,
    val kind: SessionAgentEntry.Kind,
    val state: SessionAgentRunState,
    /** `metadata.prompt`; null → "Untitled task". */
    val task: String?,
    /**
     * `deriveLastAction`: [LastAction.Tool] → "Running <name>", [LastAction.Text] verbatim;
     * null → "Starting up..." (running) / "No activity recorded (<state>)".
     */
    val lastAction: LastAction?,
    /** `runtimeSession.team.teamId`, team tasks only; null → overline "team". */
    val teamName: String?,
    /** `hasMessages == false`: still enabled; the child chat shows its own empty state (§6.4). */
    val hasMessages: Boolean,
)

/** §6.7 `AgentsRosterUiState`; the header line is resolved from [activity] in the sheet. */
@Immutable
data class AgentsRosterUiState(
    /** `mergeAgentActivity(roster, tool-derived tally)`: the header line and the chip agree. */
    val activity: SessionAgentActivity = SessionAgentActivity.EMPTY,
    val loading: Boolean = true,
    /** The last refresh failed (rows stay: "Could not refresh — showing the last known agents."). */
    val error: RepoFailure? = null,
    val agents: List<AgentRowUi> = emptyList(),
    /** Drives "Waiting for the first agent…" versus "No agent details were recorded…". */
    val runningInTally: Int = 0,
    val kernel: KernelGate = KernelGate.Ready,
) {
    /** The Hub is not usable: rows stay, the footer carries the KS F.2 banner text (§6.4 "Kernel down"). */
    val kernelDown: Boolean get() = kernel is KernelGate.Down || kernel is KernelGate.Blocked

    /** Show the stale footer. */
    val stale: Boolean get() = agents.isNotEmpty() && (error != null || kernelDown)
}

/** The roster as the sheet consumes it (`AgentsRepository.observe`). */
fun interface AgentsRosterSource {
    fun observe(parentSessionId: String, expectedTotal: () -> Int): Flow<AgentsRepository.Roster>
}

/**
 * C9 Agents roster (customize-and-rest.md §6): the children of [parentSessionId] with their
 * states and latest steps, refreshed by the repository on the parent's activity (debounced
 * 750 ms) and every 5 s while one is running; a failed refresh keeps the last good rows.
 * The tally merges the roster with the parent's spawn/team tool rows (desktop
 * `mergeAgentActivity`), the same model the header chip shows.
 */
class AgentsRosterViewModel(
    val parentSessionId: String,
    roster: AgentsRosterSource,
    parentState: Flow<SessionState>,
    kernelGate: StateFlow<KernelGate>,
) : ViewModel() {
    private data class Derived(val activity: SessionAgentActivity, val active: Boolean)

    @Volatile
    private var expectedTotal = 0

    private val derived: Flow<Derived> = parentState
        .map { state ->
            val active = state.chatStatus.isBusy
            Derived(buildSessionAgentActivity(state.messages, sessionActive = active), active)
        }
        .distinctUntilChanged()

    val ui: StateFlow<AgentsRosterUiState> = combine(
        roster.observe(parentSessionId) { expectedTotal },
        derived,
        kernelGate,
    ) { list, tally, gate ->
        expectedTotal = tally.activity.total
        project(list, tally.activity, tally.active, gate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsRosterUiState(kernel = kernelGate.value))

    companion object {
        /** Pure projection (unit-tested): merged tally, rows in roster order, stale/kernel flags. */
        fun project(
            roster: AgentsRepository.Roster,
            derived: SessionAgentActivity,
            sessionActive: Boolean,
            gate: KernelGate,
        ): AgentsRosterUiState {
            val merged = mergeAgentActivity(roster.entries, derived, sessionActive)
            return AgentsRosterUiState(
                activity = merged,
                loading = roster.loading,
                error = roster.error,
                agents = roster.entries.map(::row),
                runningInTally = merged.running,
                kernel = gate,
            )
        }

        fun row(entry: SessionAgentEntry): AgentRowUi = AgentRowUi(
            sessionId = entry.sessionId,
            kind = entry.kind,
            state = agentEntryState(entry.status),
            task = entry.prompt?.trim()?.takeIf { it.isNotEmpty() },
            lastAction = entry.lastAction?.takeIf { it.isPresent() },
            teamName = if (entry.kind == SessionAgentEntry.Kind.Teamtask) entry.teamName?.takeIf { it.isNotBlank() } else null,
            hasMessages = entry.hasMessages,
        )

        fun factory(graph: AppGraph, parentSessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AgentsRosterViewModel(
                    parentSessionId = parentSessionId,
                    roster = { parent, expected -> graph.agents.observe(parent, expected) },
                    parentState = graph.sessionStore.state(parentSessionId),
                    kernelGate = graph.kernel.gate,
                )
            }
        }
    }
}

/** A blank tool name or text carries no step and falls back to the state line. */
private fun LastAction.isPresent(): Boolean = when (this) {
    is LastAction.Tool -> name.isNotBlank()
    is LastAction.Text -> text.isNotBlank()
}
