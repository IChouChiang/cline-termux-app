package dev.cline.termux.ui.agents

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.agents.AgentsRepository
import dev.cline.termux.data.agents.LastAction
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.agents.SessionAgentRunState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsRosterViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(
        id: String,
        status: String,
        kind: SessionAgentEntry.Kind = SessionAgentEntry.Kind.Subagent,
        prompt: String? = "Do $id",
        lastAction: LastAction? = null,
        team: String? = null,
    ) = SessionAgentEntry(
        sessionId = "s-$id",
        agentId = id,
        kind = kind,
        status = status,
        prompt = prompt,
        lastAction = lastAction,
        teamName = team,
        startedAt = "2026-10-01T00:00:00Z",
        hasMessages = true,
    )

    private fun roster(vararg entries: SessionAgentEntry, error: RepoFailure? = null, loading: Boolean = false) =
        AgentsRepository.Roster(entries.map { AgentsRepository.Child(it, null, null) }, error, loading)

    @Test
    fun `row maps states, blank fields and team names`() {
        val row = AgentsRosterViewModel.row(entry("a", "pending", prompt = "  ", lastAction = LastAction.Text(" ")))
        assertEquals(SessionAgentRunState.Running, row.state)
        assertNull(row.task)
        assertNull(row.lastAction)
        assertNull(row.teamName)

        val team = AgentsRosterViewModel.row(entry("b", "weird", kind = SessionAgentEntry.Kind.Teamtask, team = "docs", lastAction = LastAction.Text("Read file")))
        assertEquals(SessionAgentRunState.Unresolved, team.state)
        assertEquals("docs", team.teamName)
        assertEquals(LastAction.Text("Read file"), team.lastAction)

        val tool = AgentsRosterViewModel.row(entry("d", "running", lastAction = LastAction.Tool("read_files")))
        assertEquals(LastAction.Tool("read_files"), tool.lastAction)
        assertNull("a blank tool name falls back to the state line", AgentsRosterViewModel.row(entry("e", "running", lastAction = LastAction.Tool(" "))).lastAction)

        val subagentWithTeam = AgentsRosterViewModel.row(entry("c", "completed", team = "ignored"))
        assertNull(subagentWithTeam.teamName)
    }

    @Test
    fun `project merges the tally and keeps rows on a failed refresh`() {
        val derived = SessionAgentActivity(total = 3, running = 1, completed = 2)
        val state = AgentsRosterViewModel.project(
            roster(entry("a", "completed"), entry("b", "running"), error = RepoFailure.HubTimeout),
            derived,
            sessionActive = true,
            gate = KernelGate.Ready,
        )
        assertEquals(3, state.activity.total)
        assertEquals(2, state.activity.running) // one roster + one spawn the roster has not caught up with
        assertEquals(2, state.runningInTally)
        assertEquals(listOf("s-a", "s-b"), state.agents.map { it.sessionId })
        assertTrue(state.stale)
        assertFalse(state.kernelDown)
    }

    @Test
    fun `kernel down marks rows stale but an empty roster is not`() {
        val down = KernelGate.Down(KernelGate.DownVariant.Disconnected, null)
        val withRows = AgentsRosterViewModel.project(roster(entry("a", "completed")), SessionAgentActivity.EMPTY, false, down)
        assertTrue(withRows.kernelDown)
        assertTrue(withRows.stale)
        val empty = AgentsRosterViewModel.project(roster(loading = true), SessionAgentActivity.EMPTY, false, down)
        assertFalse(empty.stale)
        assertTrue(empty.loading)
    }

    @Test
    fun `view model combines roster, parent and gate`() = runTest(main) {
        val rosterFlow = MutableStateFlow(roster(loading = true))
        val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
        var observedParent: String? = null
        var expected: (() -> Int)? = null
        val vm = AgentsRosterViewModel(
            parentSessionId = "p",
            roster = { parent, total ->
                observedParent = parent
                expected = total
                rosterFlow
            },
            parentState = MutableStateFlow(SessionState("p")),
            kernelGate = gate,
        )
        backgroundScope.launch { vm.ui.collect {} }
        assertEquals("p", observedParent)
        assertTrue(vm.ui.value.loading)

        rosterFlow.value = roster(entry("a", "failed"), entry("b", "cancelled"))
        assertEquals(2, vm.ui.value.activity.total)
        assertEquals(1, vm.ui.value.activity.failed)
        assertFalse(vm.ui.value.loading)
        assertEquals(0, expected!!())

        gate.value = KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Permission)
        assertTrue(vm.ui.value.stale)
    }

    @Test
    fun `chip model is hidden without agents and folds cancelled with unresolved`() {
        assertFalse(AgentsChipModel.Hidden.visible)
        val idle = AgentsChipModel.of(SessionState("p"))
        assertFalse(idle.visible)
        val model = AgentsChipModel.of(SessionState("p"), listOf(entry("a", "cancelled"), entry("b", "???"), entry("c", "completed")))
        assertTrue(model.visible)
        assertEquals(3, model.activity.total)
        assertEquals(2, model.other)
    }
}
