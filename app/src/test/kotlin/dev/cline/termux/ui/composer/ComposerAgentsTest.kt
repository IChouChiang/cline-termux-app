package dev.cline.termux.ui.composer

import dev.cline.termux.data.agents.AgentsRepository
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Desktop `agentActivity.running` (page.tsx `mergeAgentActivity(agents, derived, {sessionActive})`) for Stop. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerAgentsTest {

    private fun entry(id: String, status: String) = SessionAgentEntry(
        sessionId = id,
        agentId = "agent-$id",
        kind = SessionAgentEntry.Kind.Subagent,
        status = status,
        startedAt = "2026-10-05T10:00:00Z",
        hasMessages = true,
    )

    private fun roster(vararg entries: SessionAgentEntry) =
        AgentsRepository.Roster(entries.map { AgentsRepository.Child(it, updatedAt = 1L, createdAt = 1L) }, error = null, loading = false)

    @Test fun rosterRowsStillRunningCountWhileTheParentIsIdle() {
        val idle = SessionState("p1")
        assertEquals(2, ComposerAgents.running(listOf(entry("c1", "running"), entry("c2", "pending"), entry("c3", "completed")), idle))
        assertEquals(0, ComposerAgents.running(listOf(entry("c1", "completed"), entry("c2", "failed")), idle))
        assertEquals(0, ComposerAgents.running(emptyList(), idle))
    }

    @Test fun theFlowFollowsTheRosterOfItsSession() = runTest {
        val rosters = MutableStateFlow(AgentsRepository.Roster(emptyList(), error = null, loading = true))
        val asked = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ComposerAgents.running("p1", MutableStateFlow(SessionState("p1"))) { parent, _ ->
                asked += parent
                rosters
            }.collect { counts += it }
        }
        rosters.value = roster(entry("c1", "running"))
        rosters.value = roster(entry("c1", "running"), entry("c2", "running"))
        rosters.value = roster(entry("c1", "completed"), entry("c2", "cancelled"))
        assertEquals(listOf("p1"), asked)
        assertEquals(listOf(0, 1, 2, 0), counts)
    }
}
