package dev.cline.termux.ui.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulesViewModelTest {
    private fun json(text: String): JsonObject = CustomizeJson.parseToJsonElement(text).jsonObject

    private val list = """
        {"schedules":[
          {"scheduleId":"b","name":"beta","cronPattern":"0 9 * * *","prompt":"p","workspaceRoot":"/w","enabled":true,"mode":"yolo"},
          {"scheduleId":"a","name":"Alpha","cronPattern":"0 17 * * *","prompt":"q","workspaceRoot":"/w","enabled":false,"mode":"act"}]}
    """.trimIndent()

    private class FakeKeepAlive(awake: Boolean = true, confirmed: Boolean = true, var dismissed: String? = null) : KeepAliveInputs {
        override val keepAwake = MutableStateFlow(awake)
        override val checklistConfirmed = MutableStateFlow(confirmed)
        override suspend fun dismissedFor(): String? = dismissed
        override suspend fun setDismissedFor(version: String) {
            dismissed = version
        }
    }

    private class Rig(val vm: SchedulesViewModel, val hub: FakeHubTransport, val keepAlive: FakeKeepAlive, val events: MutableList<SchedulesEvent>) {
        fun sent(command: String): List<HubCommandEnvelope> = hub.current.commands.filter { it.command == command }
    }

    private fun scheduleReply(env: HubCommandEnvelope): HubReplyEnvelope {
        val id = env.payload!!["scheduleId"]!!.jsonPrimitive.content
        return env.ok(json("""{"schedule":{"scheduleId":"$id","name":"${if (id == "a") "Alpha" else "beta"}","cronPattern":"0 9 * * *","enabled":true,"mode":"yolo"}}"""))
    }

    private suspend fun TestScope.rig(
        savedState: SavedStateHandle = SavedStateHandle(),
        keepAlive: FakeKeepAlive = FakeKeepAlive(),
        gate: KernelGate = KernelGate.Ready,
        extra: (HubCommandEnvelope) -> HubReplyEnvelope? = { null },
    ): Rig {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            extra(env) ?: when (env.command) {
                HubCommands.SCHEDULE_LIST -> env.ok(json(list))
                HubCommands.SCHEDULE_ACTIVE -> env.ok(json("""{"executions":[]}"""))
                HubCommands.SCHEDULE_UPCOMING -> env.ok(json("""{"runs":[]}"""))
                HubCommands.SCHEDULE_LIST_EXECUTIONS -> env.ok(json("""{"executions":[{"executionId":"e0","scheduleId":"x","status":"completed","sessionId":"s0"}]}"""))
                HubCommands.SCHEDULE_GET, HubCommands.SCHEDULE_ENABLE, HubCommands.SCHEDULE_DISABLE -> scheduleReply(env)
                HubCommands.SCHEDULE_DELETE -> env.ok(json("""{"deleted":true}"""))
                else -> null
            }
        }
        val repo = SchedulesRepository(hub, FakeTermuxExecutor(), clockMs = { testScheduler.currentTime })
        val vm = SchedulesViewModel(
            repo = repo,
            kernelGate = MutableStateFlow(gate),
            keepAlive = keepAlive,
            appVersion = "0.1.0",
            reducedScopeWorkspace = null,
            savedState = savedState,
            zone = { ZoneOffset.UTC },
            locale = { Locale.US },
            words = { SchedulePreviewData.words },
        )
        val events = mutableListOf<SchedulesEvent>()
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.effects.collect { events += it } }
        settle()
        return Rig(vm, transport, keepAlive, events)
    }

    /** Advances past the run-now poll (15 s) without spinning on the 30 s active-run poll forever. */
    private fun TestScope.settle() {
        advanceTimeBy(20_000)
        runCurrent()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun Rig.close() = vm.viewModelScope.cancel()

    @Test
    fun `the list loads sorted with templates and the keep-alive notice`() = test {
        val r = rig(keepAlive = FakeKeepAlive(awake = false))
        val state = r.vm.uiState.value
        assertTrue(state.loaded)
        assertFalse(state.loading)
        assertEquals(listOf("Alpha", "beta"), state.schedules.map { it.name })
        assertEquals(4, state.templates.size)
        assertTrue(state.keepAliveNotice)
        assertTrue(state.mutationsEnabled)

        r.vm.dismissKeepAliveNotice()
        settle()
        assertFalse(r.vm.uiState.value.keepAliveNotice)
        assertEquals("0.1.0", r.keepAlive.dismissed)
        r.close()
    }

    @Test
    fun `the notice stays hidden when it was dismissed for this version or keep-alive is complete`() = test {
        val r = rig(keepAlive = FakeKeepAlive(awake = false, dismissed = "0.1.0"))
        assertFalse(r.vm.uiState.value.keepAliveNotice)
        r.close()
        val q = rig(keepAlive = FakeKeepAlive(awake = true, confirmed = true))
        assertFalse(q.vm.uiState.value.keepAliveNotice)
        q.keepAlive.checklistConfirmed.value = false
        settle()
        assertTrue(q.vm.uiState.value.keepAliveNotice)
        q.close()
    }

    @Test
    fun `run now announces the start and opens its own execution's session while visible`() = test {
        var polls = 0
        val r = rig { env ->
            when {
                env.command == HubCommands.SCHEDULE_TRIGGER -> env.ok(json("""{"execution":{"executionId":"x9","scheduleId":"b","status":"pending"}}"""))
                env.command == HubCommands.SCHEDULE_LIST_EXECUTIONS && env.payload!!["limit"]!!.jsonPrimitive.content == "5" -> {
                    polls++
                    val mine = if (polls >= 2) ""","sessionId":"sess-9"""" else ""
                    env.ok(json("""{"executions":[{"executionId":"newer","scheduleId":"b","status":"running","sessionId":"other"},{"executionId":"x9","scheduleId":"b","status":"running"$mine}]}"""))
                }
                else -> null
            }
        }
        r.vm.onStart()
        r.vm.runNow("b")
        assertTrue("b" in r.vm.uiState.value.triggering)
        assertTrue("b" in r.vm.uiState.value.busy)
        r.vm.runNow("b") // ignored while busy
        settle()
        assertEquals(1, r.sent(HubCommands.SCHEDULE_TRIGGER).size)
        assertEquals(listOf(SchedulesEvent.RunStarted("beta"), SchedulesEvent.OpenSession("sess-9")), r.events)
        assertTrue(r.vm.uiState.value.busy.isEmpty())
        assertTrue(r.vm.uiState.value.triggering.isEmpty())
        r.close()
    }

    @Test
    fun `run now does not navigate once the screen is stopped`() = test {
        val r = rig { env ->
            when (env.command) {
                HubCommands.SCHEDULE_TRIGGER -> env.ok(json("""{"execution":{"executionId":"x9","scheduleId":"b","status":"pending","sessionId":"sess-1"}}"""))
                else -> null
            }
        }
        r.vm.onStart()
        r.vm.onStop()
        r.vm.runNow("b")
        settle()
        assertEquals(listOf<SchedulesEvent>(SchedulesEvent.RunStarted("beta")), r.events)
        r.close()
    }

    @Test
    fun `a trigger without an execution is not started, and a failure is reported`() = test {
        var fail = false
        val r = rig { env ->
            when (env.command) {
                HubCommands.SCHEDULE_TRIGGER -> if (fail) env.fail("schedule_command_failed", "nope") else env.ok(json("{}"))
                else -> null
            }
        }
        r.vm.runNow("a")
        settle()
        assertEquals(listOf<SchedulesEvent>(SchedulesEvent.RunNotStarted("Alpha")), r.events)
        fail = true
        r.vm.runNow("a")
        settle()
        val failure = RepoFailure.Hub("schedule_command_failed", "nope")
        assertEquals(SchedulesEvent.RunFailed(failure), r.events.last())
        assertEquals(failure, r.vm.uiState.value.actionError)
        r.close()
    }

    @Test
    fun `the switch waits for the reply and failures land in the error surface`() = test {
        var fail = false
        val r = rig { env -> if (fail && env.command == HubCommands.SCHEDULE_ENABLE) env.fail("schedule_command_failed", "locked") else null }
        r.vm.setEnabled("b", false)
        settle()
        assertEquals("b", r.sent(HubCommands.SCHEDULE_DISABLE).single().payload!!["scheduleId"]!!.jsonPrimitive.content)
        fail = true
        r.vm.setEnabled("a", true)
        settle()
        assertEquals(RepoFailure.Hub("schedule_command_failed", "locked"), r.vm.uiState.value.actionError)
        r.vm.clearActionError()
        assertNull(r.vm.uiState.value.actionError)
        r.close()
    }

    @Test
    fun `a disconnected write is a transient snackbar`() = test {
        val r = rig { env -> if (env.command == HubCommands.SCHEDULE_DISABLE) env.fail("hub_connection_closed") else null }
        r.vm.setEnabled("b", false)
        settle()
        assertEquals(listOf<SchedulesEvent>(SchedulesEvent.Transient(RepoFailure.HubDisconnected)), r.events)
        assertNull(r.vm.uiState.value.actionError)
        r.close()
    }

    @Test
    fun `the pushed details load and a delete pops back`() = test {
        val r = rig(savedState = SavedStateHandle(mapOf(SchedulesViewModel.KEY_ROUTE_ID to "b")))
        val details = r.vm.uiState.value.details
        assertNotNull(details)
        assertEquals("b", details!!.scheduleId)
        assertEquals("beta", details.details!!.card.name)
        assertTrue(r.sent(HubCommands.SCHEDULE_LIST_EXECUTIONS).any { it.payload!!["scheduleId"]!!.jsonPrimitive.content == "b" && it.payload!!["limit"]!!.jsonPrimitive.content == "50" })
        assertEquals("s0", details.details!!.runs.single().sessionId)

        r.vm.requestDelete("b")
        assertEquals(PendingDelete("b", "beta"), r.vm.uiState.value.pendingDelete)
        r.vm.confirmDelete()
        settle()
        assertEquals(1, r.sent(HubCommands.SCHEDULE_DELETE).size)
        assertNull(r.vm.uiState.value.pendingDelete)
        assertEquals(listOf<SchedulesEvent>(SchedulesEvent.DetailsDeleted), r.events)
        r.close()
    }

    @Test
    fun `a failed delete keeps the dialog with the reply`() = test {
        val r = rig { env -> if (env.command == HubCommands.SCHEDULE_DELETE) env.fail("schedule_command_failed", "busy") else null }
        r.vm.requestDelete("a")
        r.vm.confirmDelete()
        settle()
        val pending = r.vm.uiState.value.pendingDelete!!
        assertFalse(pending.deleting)
        assertEquals(RepoFailure.Hub("schedule_command_failed", "busy"), pending.error)
        r.vm.dismissDelete()
        assertNull(r.vm.uiState.value.pendingDelete)
        r.close()
    }

    @Test
    fun `the expanded selection loads details, toggles runs and clears on delete`() = test {
        val saved = SavedStateHandle()
        val r = rig(savedState = saved)
        assertNull(r.vm.uiState.value.details)
        r.vm.select("a")
        settle()
        assertEquals("a", saved[SchedulesViewModel.KEY_SELECTED])
        assertEquals("a", r.vm.uiState.value.selectedId)
        assertEquals("Alpha", r.vm.uiState.value.details!!.details!!.card.name)
        r.vm.toggleShowAllRuns()
        assertTrue(r.vm.uiState.value.details!!.showAllRuns)

        r.vm.requestDelete("a")
        r.vm.confirmDelete()
        settle()
        assertNull(r.vm.uiState.value.selectedId)
        assertNull(r.vm.uiState.value.details)
        assertTrue(r.events.isEmpty())
        r.close()
    }

    @Test
    fun `a details load failure shows the reply and retries`() = test {
        var fail = true
        val r = rig(savedState = SavedStateHandle(mapOf(SchedulesViewModel.KEY_ROUTE_ID to "zz"))) { env ->
            if (env.command == HubCommands.SCHEDULE_GET && fail) env.fail("schedule_command_failed", "schedule does not exist in this workspace") else null
        }
        assertEquals(RepoFailure.Hub("schedule_command_failed", "schedule does not exist in this workspace"), r.vm.uiState.value.details!!.error)
        fail = false
        r.vm.retryDetails()
        settle()
        assertNull(r.vm.uiState.value.details!!.error)
        assertNotNull(r.vm.uiState.value.details!!.details)
        r.close()
    }

    @Test
    fun `mutations are disabled while the kernel is down`() = test {
        val r = rig(gate = KernelGate.Down(KernelGate.DownVariant.Asleep, null))
        assertFalse(r.vm.uiState.value.mutationsEnabled)
        r.close()
    }
}
