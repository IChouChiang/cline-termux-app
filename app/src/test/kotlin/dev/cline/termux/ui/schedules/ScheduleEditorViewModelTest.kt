package dev.cline.termux.ui.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.schedules.CLINE_DEFAULT_MODEL_ID
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
class ScheduleEditorViewModelTest {
    private fun json(text: String): JsonObject = CustomizeJson.parseToJsonElement(text).jsonObject

    /** 2026-10-04T16:00:00Z. */
    private val now = LocalDateTime.of(2026, 10, 4, 16, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val catalog = CatalogState(
        source = CatalogSource.Catalog,
        providers = listOf(ProviderListItem("cline", enabled = true), ProviderListItem("openai", enabled = true)),
        modelsByProvider = mapOf(
            "cline" to listOf(ProviderModel(CLINE_DEFAULT_MODEL_ID), ProviderModel("anthropic/claude-haiku-5")),
            "openai" to listOf(ProviderModel("gpt-a"), ProviderModel("gpt-b")),
        ),
    )

    private class FakeSources(catalog: CatalogState, var selection: ModelSelection = ModelSelection(), var recent: String? = null) : ScheduleEditorSources {
        override val catalog = MutableStateFlow(catalog)
        var loads = 0
        override suspend fun loadCatalog() {
            loads++
        }
        override suspend fun rememberedSelection(): ModelSelection = selection
        override val workspaces: Flow<List<String>> = MutableStateFlow(listOf("/w/app", "/w/lib"))
        override suspend fun recentWorkspace(): String? = recent
    }

    private class Rig(
        val vm: ScheduleEditorViewModel,
        val hub: FakeHubTransport,
        val termux: FakeTermuxExecutor,
        val sources: FakeSources,
        val saved: SavedStateHandle,
        val events: MutableList<EditorEvent>,
    ) {
        val state: ScheduleEditorUiState get() = vm.uiState.value
        fun sent(command: String): List<HubCommandEnvelope> = hub.current.commands.filter { it.command == command }
        fun close() = vm.viewModelScope.cancel()
    }

    private suspend fun TestScope.rig(
        scheduleId: String? = null,
        template: String? = null,
        sources: FakeSources = FakeSources(catalog),
        homeOnly: String? = null,
        saved: SavedStateHandle = SavedStateHandle(),
        extra: (HubCommandEnvelope) -> HubReplyEnvelope? = { null },
    ): Rig {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            extra(env) ?: when (env.command) {
                HubCommands.SCHEDULE_GET -> env.ok(json("""{"schedule":{"scheduleId":"s1","name":"Every quarter hour","cronPattern":"*/15 * * * *","prompt":"old","workspaceRoot":"/w/app","cwd":"/w/app","enabled":true,"mode":"act","modelSelection":{"providerId":"openai","modelId":"gpt-z"}}}"""))
                HubCommands.SCHEDULE_LIST_EXECUTIONS -> env.ok(json("""{"executions":[]}"""))
                HubCommands.SCHEDULE_CREATE, HubCommands.SCHEDULE_UPDATE -> env.ok(json("""{"schedule":{"scheduleId":"s1","name":"n","cronPattern":"0"}}"""))
                HubCommands.SCHEDULE_LIST -> env.ok(json("""{"schedules":[]}"""))
                HubCommands.SCHEDULE_ACTIVE -> env.ok(json("""{"executions":[]}"""))
                HubCommands.SCHEDULE_UPCOMING -> env.ok(json("""{"runs":[]}"""))
                else -> null
            }
        }
        val termux = FakeTermuxExecutor()
        val repo = SchedulesRepository(hub, termux, clockMs = { now })
        val vm = ScheduleEditorViewModel(
            repo = repo,
            sources = sources,
            kernelGate = MutableStateFlow(KernelGate.Ready),
            savedState = saved,
            scheduleId = scheduleId,
            templateId = template,
            homeOnly = homeOnly,
            nowMs = { now },
            zone = { ZoneOffset.UTC },
        )
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.effects.collect { events += it } }
        settle()
        return Rig(vm, transport, termux, sources, saved, events)
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

    @Test
    fun `a new schedule starts from the remembered model and the recent workspace`() = test {
        val r = rig(sources = FakeSources(catalog, ModelSelection("openai", mapOf("openai" to "gpt-b")), recent = "/w/lib"))
        val form = r.state.form!!
        assertEquals("openai", form.provider)
        assertEquals("gpt-b", form.model)
        assertEquals("/w/lib", form.workspaceRoot)
        assertEquals(ScheduleType.Once, form.type)
        assertEquals(LocalDate.of(2026, 10, 5), form.date)
        assertFalse(r.state.dirty)
        assertFalse(r.state.editing)
        assertEquals(1, r.sources.loads)
        assertEquals(listOf("cline", "openai"), r.state.providers)
        assertEquals(listOf("gpt-a", "gpt-b"), r.state.models)
        assertEquals(LocalDate.of(2026, 10, 4), r.state.minDate)
        assertNotNull(r.saved.get<ScheduleFormState>(ScheduleEditorViewModel.KEY_FORM))
        r.close()
    }

    @Test
    fun `without memory the defaults are cline, the default model and the Termux home`() = test {
        val r = rig()
        val form = r.state.form!!
        assertEquals("cline", form.provider)
        assertEquals(CLINE_DEFAULT_MODEL_ID, form.model)
        assertEquals(ScheduleEditorViewModel.TERMUX_HOME, form.workspaceRoot)
        r.close()
    }

    @Test
    fun `a template prefills and a restored form survives process death`() = test {
        val r = rig(template = "daily-digest")
        assertEquals("Daily change digest", r.state.form!!.name)
        assertEquals(ScheduleType.Daily, r.state.form!!.type)
        r.vm.setName("Renamed")
        val restored = rig(template = "daily-digest", saved = r.saved)
        assertEquals("Renamed", restored.state.form!!.name)
        assertTrue(restored.state.dirty)
        r.close()
        restored.close()
    }

    @Test
    fun `validation follows the desktop order then the Android checks`() = test {
        val r = rig()
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_name_required, FormField.Name), r.state.error)
        r.vm.setName("Weekly review")
        r.vm.setType(ScheduleType.Weekly)
        DayOfWeek.entries.filter { it in r.state.form!!.days }.forEach(r.vm::toggleDay)
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_no_weekday, FormField.Days), r.state.error)
        r.vm.toggleDay(DayOfWeek.FRIDAY)
        r.vm.setTimeout("90s")
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_timeout_invalid, FormField.Timeout), r.state.error)
        r.vm.setTimeout("")
        r.vm.setPrompt("  ")
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_prompt_required, FormField.Prompt), r.state.error)
        settle()
        assertTrue(r.sent(HubCommands.SCHEDULE_CREATE).isEmpty())
        r.close()
    }

    @Test
    fun `a once time in the past is refused`() = test {
        val r = rig()
        r.vm.setName("Once")
        r.vm.setDate(LocalDate.of(2026, 10, 4))
        // The date clamp moved 9:00 to the minimum (16:01); force a past time through the type switch.
        assertEquals(16 to 1, r.state.form!!.hour to r.state.form!!.minute)
        r.vm.setType(ScheduleType.Daily)
        r.vm.setTime(8, 0)
        r.vm.setType(ScheduleType.Once)
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_once_in_past, FormField.Time), r.state.error)
        r.close()
    }

    @Test
    fun `create checks the workspace with test -d and sends the desktop payload`() = test {
        val r = rig(template = "security-scan")
        r.termux.on(ClineCommands.Labels.TEST_DIRECTORY, TermuxResult.failure(1, ""))
        r.vm.save()
        settle()
        assertEquals(EditorError.Validation(R.string.schedule_error_workspace_missing, FormField.Workspace), r.state.error)
        assertTrue(r.sent(HubCommands.SCHEDULE_CREATE).isEmpty())

        r.termux.on(ClineCommands.Labels.TEST_DIRECTORY, TermuxResult.success(""))
        r.vm.setTags("automation, review")
        r.vm.save()
        settle()
        val payload = r.sent(HubCommands.SCHEDULE_CREATE).single().payload!!
        assertEquals("Security scan", payload["name"]!!.jsonPrimitive.content)
        assertEquals("0 8 * * MON", payload["cronPattern"]!!.jsonPrimitive.content)
        assertEquals("Z", payload["timezone"]!!.jsonPrimitive.content)
        assertEquals("yolo", payload["mode"]!!.jsonPrimitive.content)
        assertEquals("android", payload["createdBy"]!!.jsonPrimitive.content)
        assertEquals(listOf(EditorEvent.Saved), r.events)
        assertFalse(r.state.saving)
        r.close()
    }

    @Test
    fun `a workspace scope refusal marks the workspace field`() = test {
        val r = rig { env -> if (env.command == HubCommands.SCHEDULE_CREATE) env.fail("schedule_command_failed", "schedule cwd is outside the client workspace scope") else null }
        r.vm.setName("n")
        r.vm.save()
        settle()
        assertEquals(
            EditorError.Failure(RepoFailure.Hub("schedule_command_failed", "schedule cwd is outside the client workspace scope"), FormField.Workspace),
            r.state.error,
        )
        assertTrue(r.events.isEmpty())
        r.close()
    }

    @Test
    fun `an Origin-only connection locks the workspace to the home folder`() = test {
        val r = rig(homeOnly = ScheduleEditorViewModel.TERMUX_HOME, sources = FakeSources(catalog, recent = "/w/lib"))
        assertEquals(ScheduleEditorViewModel.TERMUX_HOME, r.state.form!!.workspaceRoot)
        r.vm.setName("n")
        r.vm.setWorkspace("/w/lib")
        r.vm.save()
        assertEquals(EditorError.Validation(R.string.schedule_error_home_only, FormField.Workspace), r.state.error)
        r.vm.setWorkspace(ScheduleEditorViewModel.TERMUX_HOME + "/")
        r.vm.save()
        settle()
        assertEquals(1, r.sent(HubCommands.SCHEDULE_CREATE).size)
        r.close()
    }

    @Test
    fun `editing keeps a custom cron when only the prompt changes`() = test {
        val r = rig(scheduleId = "s1")
        val form = r.state.form!!
        assertTrue(r.state.editing)
        assertEquals("*/15 * * * *", r.state.currentCron)
        assertEquals("openai", form.provider)
        assertEquals("gpt-z", form.model)
        assertTrue("gpt-z" in r.state.models)
        r.vm.setPrompt("new prompt")
        assertTrue(r.state.dirty)
        r.vm.save()
        settle()
        val payload = r.sent(HubCommands.SCHEDULE_UPDATE).single().payload!!
        assertEquals("*/15 * * * *", payload["cronPattern"]!!.jsonPrimitive.content)
        assertEquals("new prompt", payload["prompt"]!!.jsonPrimitive.content)
        assertNull(payload["mode"])
        assertEquals(listOf(EditorEvent.Saved), r.events)
        r.close()
    }

    @Test
    fun `an unreadable schedule shows the reply and retries`() = test {
        var fail = true
        val r = rig(scheduleId = "s1") { env -> if (env.command == HubCommands.SCHEDULE_GET && fail) env.fail("schedule_command_failed", "gone") else null }
        assertNull(r.state.form)
        assertEquals(RepoFailure.Hub("schedule_command_failed", "gone"), r.state.loadError)
        fail = false
        r.vm.retryLoad()
        settle()
        assertNotNull(r.state.form)
        assertNull(r.state.loadError)
        r.close()
    }

    @Test
    fun `a provider change picks the remembered model when offered, else the first`() = test {
        val r = rig(sources = FakeSources(catalog, ModelSelection("cline", mapOf("openai" to "gpt-b"))))
        r.vm.setProvider("openai")
        assertEquals("gpt-b", r.state.form!!.model)
        r.vm.setProvider("cline")
        assertEquals(CLINE_DEFAULT_MODEL_ID, r.state.form!!.model)
        r.vm.setModel("typed/model")
        assertTrue("typed/model" in r.state.models)
        r.close()
    }
}
