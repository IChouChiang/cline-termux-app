package dev.cline.termux.data.schedules

import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulesRepositoryTest {
    private fun json(text: String): JsonObject = CustomizeJson.parseToJsonElement(text).jsonObject

    private val schedules = """
        {"schedules":[
          {"scheduleId":"b","name":"beta","cronPattern":"0 9 * * MON-FRI","timezone":"Europe/Berlin","prompt":"p","workspaceRoot":"/w","enabled":true,
           "modelSelection":{"providerId":"cline","modelId":"m"},"createdAt":1,"updatedAt":2,"nextRunAt":1000,"tags":["x"]},
          {"scheduleId":"a","name":"Alpha","cronPattern":"0","prompt":"q","workspaceRoot":"/w","enabled":false,"metadata":{"__hubScheduleRunAt":5000,"keep":1}}]}
    """.trimIndent()

    private class Rig(val repo: SchedulesRepository, val hub: FakeHubTransport, val termux: FakeTermuxExecutor)

    private suspend fun TestScope.rig(extra: (HubCommandEnvelope) -> HubReplyEnvelope? = { null }): Rig {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            extra(env) ?: when (env.command) {
                HubCommands.SCHEDULE_LIST -> env.ok(json(schedules))
                HubCommands.SCHEDULE_ACTIVE -> env.ok(json("""{"executions":[{"executionId":"e1","scheduleId":"b","status":"running","triggeredAt":10}]}"""))
                HubCommands.SCHEDULE_UPCOMING -> env.ok(json("""{"runs":[{"scheduleId":"b","name":"beta","nextRunAt":2000}]}"""))
                HubCommands.SCHEDULE_LIST_EXECUTIONS -> env.ok(json("""{"executions":[{"executionId":"e0","scheduleId":"${env.payload!!["scheduleId"]!!.jsonPrimitive.content}","status":"success","endedAt":9}]}"""))
                HubCommands.SCHEDULE_CREATE, HubCommands.SCHEDULE_UPDATE, HubCommands.SCHEDULE_ENABLE, HubCommands.SCHEDULE_DISABLE, HubCommands.SCHEDULE_GET ->
                    env.ok(json("""{"schedule":{"scheduleId":"b","name":"beta","cronPattern":"0 9 * * *","enabled":true}}"""))
                else -> null
            }
        }
        val termux = FakeTermuxExecutor()
        val repo = SchedulesRepository(hub, termux, clockMs = { testScheduler.currentTime })
        return Rig(repo, transport, termux)
    }

    @Test
    fun `the overview reads list, active, upcoming and each last execution with allWorkspaces`() = runTest {
        val r = rig()
        val overview = (r.repo.refresh() as Outcome.Ok).value
        assertEquals(listOf("Alpha", "beta"), overview.schedules.map { it.name })
        assertEquals("e1", overview.activeFor("b")!!.executionId)
        assertEquals(2000L, overview.nextRunFor("b"))
        assertEquals("e0", overview.lastExecutions["a"]!!.executionId)
        assertTrue(overview.hasActive)
        val sent = r.hub.current.commands.filter { it.command.startsWith("schedule.") }
        assertTrue(sent.all { it.payload!!["allWorkspaces"]!!.jsonPrimitive.content == "true" })
        assertEquals("200", r.hub.current.command(HubCommands.SCHEDULE_LIST)!!.payload!!["limit"]!!.jsonPrimitive.content)
        assertEquals("1", r.hub.current.command(HubCommands.SCHEDULE_LIST_EXECUTIONS)!!.payload!!["limit"]!!.jsonPrimitive.content)
        assertEquals(SchedulesRepository.State(overview = overview), r.repo.state.value)

        val alpha = overview.schedules.first()
        assertTrue(alpha.isOneTime)
        assertEquals(5000L, alpha.facts().oneTimeRunAt)
        assertEquals("m", overview.schedules[1].facts().selectionModelId)
    }

    @Test
    fun `create sends cronPattern, modelSelection, yolo mode and createdBy android`() = runTest {
        val r = rig()
        val form = ScheduleForm(name = " Digest ", scheduleType = ScheduleType.Weekly, scheduleHour = "8", scheduleMinute = "30", scheduleDays = listOf("MON", "WED"),
            prompt = "do it", provider = "cline", model = "m", workspaceRoot = "/w", timeoutSeconds = "120", tags = "a, b")
        val valid = validateScheduleForm(form, editingCron = null, nowMs = 0).getOrThrow()
        val draft = ScheduleDraft.of(form, valid, existing = null, deviceZone = ZoneId.of("Asia/Shanghai"))
        assertTrue(r.repo.create(draft) is Outcome.Ok)
        val payload = r.hub.current.command(HubCommands.SCHEDULE_CREATE)!!.payload!!
        assertEquals("true", payload["allWorkspaces"]!!.jsonPrimitive.content)
        assertEquals("Digest", payload["name"]!!.jsonPrimitive.content)
        assertEquals("30 8 * * MON,WED", payload["cronPattern"]!!.jsonPrimitive.content)
        assertEquals("Asia/Shanghai", payload["timezone"]!!.jsonPrimitive.content)
        assertEquals("cline", payload["modelSelection"]!!.jsonObject["providerId"]!!.jsonPrimitive.content)
        assertEquals("yolo", payload["mode"]!!.jsonPrimitive.content)
        assertEquals("/w", payload["cwd"]!!.jsonPrimitive.content)
        assertEquals("1", payload["maxParallel"]!!.jsonPrimitive.content)
        assertEquals("120", payload["timeoutSeconds"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), payload["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("android", payload["createdBy"]!!.jsonPrimitive.content)
        assertNull(payload["systemPrompt"])
        assertNull(payload["cron"])
    }

    @Test
    fun `a one-time create omits the timezone and carries the run-at metadata`() = runTest {
        val zone = ZoneOffset.UTC
        val form = ScheduleForm(name = "Once", scheduleType = ScheduleType.Once, scheduleDate = "2026-10-04", scheduleHour = "9", scheduleMinute = "0",
            prompt = "p", provider = "cline", model = "m", workspaceRoot = "/w")
        val valid = validateScheduleForm(form, null, nowMs = 0, zone = zone).getOrThrow()
        val payload = SchedulePayloads.create(ScheduleDraft.of(form, valid, null, zone))
        assertEquals(ONE_TIME_SCHEDULE_CRON_PATTERN, payload["cronPattern"]!!.jsonPrimitive.content)
        assertNull(payload["timezone"])
        assertEquals(valid.runAt.toString(), payload["metadata"]!!.jsonObject[ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY]!!.jsonPrimitive.content)
    }

    @Test
    fun `update keeps mode, clears optional fields with null and drops run-at when going recurring`() = runTest {
        val r = rig()
        val existing = (r.repo.refresh() as Outcome.Ok).value.schedules.first { it.scheduleId == "a" }
        val form = ScheduleForm(name = "Alpha", scheduleType = ScheduleType.Daily, scheduleHour = "7", scheduleMinute = "5", prompt = "q",
            provider = "cline", model = "m", workspaceRoot = "/w")
        val valid = validateScheduleForm(form, existing.cronPattern, nowMs = 0).getOrThrow()
        r.repo.update(existing, ScheduleDraft.of(form, valid, existing))
        val payload = r.hub.current.command(HubCommands.SCHEDULE_UPDATE)!!.payload!!
        assertNull(payload["mode"])
        assertEquals("a", payload["scheduleId"]!!.jsonPrimitive.content)
        assertEquals("5 7 * * *", payload["cronPattern"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["timezone"])
        assertEquals(JsonNull, payload["systemPrompt"])
        assertEquals(JsonNull, payload["timeoutSeconds"])
        assertEquals("false", payload["enabled"]!!.jsonPrimitive.content)
        val metadata = payload["metadata"]!!.jsonObject
        assertFalse(metadata.containsKey(ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY))
        assertEquals("1", metadata["keep"]!!.jsonPrimitive.content)
    }

    @Test
    fun `run now polls only its own execution until it has a session`() = runTest {
        var polls = 0
        val r = rig { env ->
            when {
                env.command == HubCommands.SCHEDULE_TRIGGER -> env.ok(json("""{"execution":{"executionId":"x9","scheduleId":"b","status":"pending"}}"""))
                env.command == HubCommands.SCHEDULE_LIST_EXECUTIONS && env.payload!!["limit"]!!.jsonPrimitive.content == "5" -> {
                    polls++
                    val mine = if (polls >= 3) ""","sessionId":"sess-9"""" else ""
                    env.ok(json("""{"executions":[{"executionId":"newer","scheduleId":"b","status":"running","sessionId":"other"},{"executionId":"x9","scheduleId":"b","status":"running"$mine}]}"""))
                }
                else -> null
            }
        }
        val started = (r.repo.trigger("b") as Outcome.Ok).value as SchedulesRepository.TriggerResult.Started
        assertEquals("x9", started.execution.executionId)
        assertEquals("false", r.hub.current.command(HubCommands.SCHEDULE_TRIGGER)!!.payload!!["wait"]!!.jsonPrimitive.content)
        assertEquals("sess-9", r.repo.awaitExecutionSession("b", "x9"))
        assertEquals(3, polls)
    }

    @Test
    fun `run now without an execution is not started, and a run that never gets a session gives up after 15 s`() = runTest {
        val r = rig { env ->
            when (env.command) {
                HubCommands.SCHEDULE_TRIGGER -> env.ok(json("{}"))
                else -> null
            }
        }
        assertEquals(SchedulesRepository.TriggerResult.NotStarted, (r.repo.trigger("b") as Outcome.Ok).value)
        val start = testScheduler.currentTime
        assertNull(r.repo.awaitExecutionSession("b", "never"))
        assertTrue(testScheduler.currentTime - start <= 15_000)
    }

    @Test
    fun `schedule command failures carry the kernel message`() = runTest {
        val r = rig { env -> if (env.command == HubCommands.SCHEDULE_CREATE) env.fail("schedule_command_failed", "schedule cwd is outside the client workspace scope") else null }
        val form = ScheduleForm(name = "n", prompt = "p", provider = "cline", model = "m", workspaceRoot = "/elsewhere")
        val draft = ScheduleDraft.of(form, validateScheduleForm(form, null, 0).getOrThrow(), null)
        assertEquals(Outcome.Failed(RepoFailure.Hub("schedule_command_failed", "schedule cwd is outside the client workspace scope")), r.repo.create(draft))
    }

    @Test
    fun `execution events refresh silently after the debounce while observed`() = runTest {
        val r = rig()
        backgroundScope.launch { r.repo.observe().collect { } }
        advanceTimeBy(100)
        runCurrent()
        val before = r.hub.current.commands.count { it.command == HubCommands.SCHEDULE_LIST }
        assertEquals(1, before)
        r.hub.current.event(HubEvents.SCHEDULE_EXECUTION_COMPLETED, payload = JsonObject(emptyMap()))
        r.hub.current.event(HubEvents.SCHEDULE_EXECUTION_FAILED, payload = JsonObject(emptyMap()))
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, r.hub.current.commands.count { it.command == HubCommands.SCHEDULE_LIST })
        // An active execution keeps the 30 s poll going.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, r.hub.current.commands.count { it.command == HubCommands.SCHEDULE_LIST })
    }

    @Test
    fun `workspace validation is test -d`() = runTest {
        val r = rig()
        r.termux.on(ClineCommands.Labels.TEST_DIRECTORY, TermuxResult.failure(1, ""))
        assertEquals(false, r.repo.workspaceExists("~/gone"))
        assertEquals(listOf("-d", "/data/data/com.termux/files/home/gone"), r.termux.commands.single().args)
        r.termux.on(ClineCommands.Labels.TEST_DIRECTORY, TermuxResult.success(""))
        assertEquals(true, r.repo.workspaceExists("/w"))
        r.termux.permitted = false
        assertNull(r.repo.workspaceExists("/w"))
        advanceUntilIdle()
    }
}
