package dev.cline.termux.ui.customize

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.CustomizeRepository
import dev.cline.termux.data.customize.McpRepository
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.WorkspaceValidation
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomizeViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val ws = "/data/data/com.termux/files/home/app"
    private val snapshotJson = """
        {"snapshot":{"workflows":[],"rules":[{"id":"r","name":"style.md","path":"$ws/.clinerules/style.md","kind":"rule","source":"workspace","enabled":true,"toggleable":false,"description":"Use   tabs"}],
          "plugins":[{"id":"p","name":"lint","path":"$ws/.cline/plugins/lint","kind":"plugin","source":"workspace-plugin","enabled":true,"toggleable":true}],
          "skills":[{"id":"s","name":"review","path":"/h/.cline/skills/review/SKILL.md","kind":"skill","source":"global","enabled":true,"toggleable":true}],
          "tools":[{"id":"t","name":"lint_run","path":"$ws/.cline/plugins/lint","kind":"tool","source":"workspace-plugin","enabled":true,"pluginName":"lint"}],"mcp":[]}}
    """.trimIndent()
    private val mcpJson = """{"settingsPath":"/s.json","hasSettingsFile":true,"servers":[{"name":"gh","transportType":"stdio","disabled":false,"command":"npx","env":{"GITHUB_TOKEN":"ghp_secret"}}]}"""

    private class Rig(
        val vm: CustomizeViewModel,
        val hub: FakeHubTransport,
        val helper: FakeHelperChannel,
        val lease: FakeLease,
        val workspace: FakeWorkspace,
        val gate: MutableStateFlow<KernelGate>,
        val saved: SavedStateHandle,
        val running: MutableStateFlow<Boolean>,
        val termux: FakeTermuxExecutor,
    )

    private suspend fun TestScope.rig(
        routeTab: String? = null,
        saved: SavedStateHandle = SavedStateHandle(),
        toggleFails: Boolean = false,
        listFails: Boolean = false,
        clockMs: () -> Long = { 0L },
    ): Rig {
        val transport = FakeHubTransport()
        val client = connectedHub(transport, backgroundScope) { env ->
            when (env.command) {
                HubCommands.SETTINGS_LIST -> if (listFails) env.fail("settings_list_failed", "hub unavailable") else env.ok(CustomizeJson.parseToJsonElement(snapshotJson).jsonObject)
                HubCommands.SETTINGS_TOGGLE -> if (toggleFails) env.fail("toggle_failed", "nope") else env.ok(CustomizeJson.parseToJsonElement(snapshotJson).jsonObject)
                else -> null
            }
        }
        val termux = FakeTermuxExecutor().apply {
            on(ClineCommands.Labels.CONFIG_TOOLS, TermuxResult.success("""[{"id":"read_files","description":"Read","defaultEnabled":true,"headlessToolNames":["read_file"]}]"""))
            on(ClineCommands.Labels.CONFIG_HOOKS, TermuxResult.success("[]"))
            on(ClineCommands.Labels.READ_GLOBAL_SETTINGS, TermuxResult.success("{}"))
        }
        val helper = FakeHelperChannel(HelperState.Ready)
        helper.client.respond(McpRepository.LIST, CustomizeJson.parseToJsonElement(mcpJson))
        val inventory = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val repo = CustomizeRepository(client, termux, helper, inventory, backgroundScope, FakeClock())
        val mcp = McpRepository(client, termux, helper, inventory)
        val lease = FakeLease(helper)
        val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
        val workspace = FakeWorkspace(ws)
        val running = MutableStateFlow(false)
        val vm = CustomizeViewModel(
            repo = repo,
            mcp = mcp,
            helper = lease,
            gate = gate,
            workspace = workspace,
            catalog = { null },
            sessionRunning = running,
            savedState = saved,
            routeTab = routeTab,
            clockMs = clockMs,
            leaseScope = backgroundScope,
        )
        runCurrent()
        return Rig(vm, transport, helper, lease, workspace, gate, saved, running, termux)
    }

    @Test
    fun `init loads every tab for the stored workspace`() = runTest(main) {
        val r = rig()
        val s = r.vm.state.value
        assertEquals(ws, s.workspaceRoot)
        assertEquals(CustomizeTab.Tools, s.tab)
        assertTrue(s.hasData)
        assertFalse(s.loading)
        assertNull(s.loadError)
        assertEquals(listOf("read_files", "tasks"), s.tools.builtin.map { it.name })
        assertEquals(listOf("lint_run"), s.tools.plugin.map { it.name })
        assertEquals(listOf("lint"), s.plugins.map { it.name })
        assertEquals(listOf("review"), s.commands.map { it.name })
        assertEquals("Use tabs", s.rules.single().preview)
        assertEquals(listOf("gh"), s.mcp.servers.map { it.name })
        assertEquals(1, s.counts?.mcp)
    }

    @Test
    fun `mcp env values never reach the ui state`() = runTest(main) {
        val r = rig()
        val card = r.vm.state.value.mcp.servers.single()
        assertEquals(listOf("GITHUB_TOKEN"), card.envKeys)
        assertFalse("ghp_secret" in r.vm.state.value.toString())
    }

    @Test
    fun `route tab opens the page, saved state wins after recreation, queries persist`() = runTest(main) {
        val r = rig(routeTab = "mcp")
        assertEquals(CustomizeTab.Mcp, r.vm.state.value.tab)
        r.vm.selectTab(CustomizeTab.Skills)
        r.vm.setQuery(CustomizeTab.Skills, "rev")
        val again = rig(routeTab = "mcp", saved = r.saved)
        assertEquals(CustomizeTab.Skills, again.vm.state.value.tab)
        assertEquals("rev", again.vm.state.value.query(CustomizeTab.Skills))
    }

    @Test
    fun `the helper is leased only while the pager rests on mcp`() = runTest(main) {
        val r = rig()
        assertTrue(r.lease.owners.isEmpty())
        r.vm.onSettledTab(CustomizeTab.Mcp)
        assertEquals(1, r.lease.owners.size)
        r.vm.onSettledTab(CustomizeTab.Mcp)
        assertEquals("settling twice does not lease twice", 1, r.lease.owners.size)
        r.vm.onSettledTab(CustomizeTab.Hooks)
        runCurrent()
        assertEquals(r.lease.owners, r.lease.released)
    }

    @Test
    fun `a tool toggle sends the names and a failure is reported`() = runTest(main) {
        val r = rig(toggleFails = true)
        val tool = r.vm.state.value.tools.builtin.first { it.name == "read_files" }
        r.vm.events.test {
            r.vm.toggleTool(tool, enabled = false)
            assertTrue(awaitItem() is CustomizeEvent.ToggleFailed)
        }
        assertTrue(r.vm.state.value.pending.isEmpty())
        val toggle = r.hub.current.command(HubCommands.SETTINGS_TOGGLE)!!.payload!!
        assertEquals("read_files", toggle["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mutations are off while the kernel is down`() = runTest(main) {
        val r = rig()
        r.gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        runCurrent()
        assertFalse(r.vm.state.value.mutationsAllowed)
        r.vm.toggleTool(r.vm.state.value.tools.builtin.first(), enabled = false)
        assertNull(r.hub.current.command(HubCommands.SETTINGS_TOGGLE))
    }

    @Test
    fun `picking a workspace validates it first`() = runTest(main) {
        val r = rig()
        r.workspace.validation = { WorkspaceValidation.Invalid(it) }
        r.vm.pickWorkspace("/nope")
        assertEquals("/nope", r.vm.state.value.workspacePicker.invalidPath)
        assertTrue(r.workspace.stored.isEmpty())
        r.vm.clearWorkspaceError()

        r.workspace.validation = { WorkspaceValidation.Valid("/data/other") }
        r.vm.events.test {
            r.vm.pickWorkspace("/data/other/")
            assertEquals(CustomizeEvent.WorkspacePicked, awaitItem())
        }
        assertEquals(listOf("/data/other"), r.workspace.stored)
        assertEquals("/data/other", r.vm.state.value.workspaceRoot)
        val last = r.hub.current.commands.last { it.command == HubCommands.SETTINGS_LIST }
        assertEquals("/data/other", last.payload!!["workspaceRoot"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a running session and the mcp delete dialog`() = runTest(main) {
        val r = rig()
        r.running.value = true
        runCurrent()
        assertTrue(r.vm.state.value.sessionRunning)
        r.helper.client.respond(McpRepository.DELETE, CustomizeJson.parseToJsonElement("""{"settingsPath":"/s.json","hasSettingsFile":true,"servers":[]}"""))
        r.vm.requestMcpDelete("gh")
        assertEquals("gh", r.vm.state.value.mcpDelete?.name)
        r.vm.confirmMcpDelete()
        runCurrent()
        assertNull(r.vm.state.value.mcpDelete)
        assertEquals("gh", r.helper.client.callsFor(McpRepository.DELETE).single().args["name"]!!.jsonPrimitive.content)
        advanceTimeBy(1)
    }

    // ---- uninstall (CR §1.6, §1.9) -----------------------------------------------------------------

    private fun CustomizeUiState.plugin() = plugins.single { it.name == "lint" }

    @Test
    fun `uninstall shows Uninstalling while the helper runs, then the helper's own message`() = runTest(main) {
        val r = rig()
        val reply = CompletableDeferred<JsonElement>()
        r.helper.client.respond(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE) { reply.await() }
        val card = r.vm.state.value.plugin()

        r.vm.uninstall(card.key, card.item)
        runCurrent()
        assertTrue(card.key in r.vm.state.value.pending)
        assertTrue(card.key in r.vm.state.value.uninstalling)
        r.vm.uninstall(card.key, card.item)
        runCurrent()
        assertEquals("a pending key is not uninstalled twice", 1, r.helper.client.callsFor(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE).size)

        reply.complete(buildJsonObject { put("message", JsonPrimitive("Removed lint.")) })
        runCurrent()
        val s = r.vm.state.value
        assertTrue(s.pending.isEmpty())
        assertTrue(s.uninstalling.isEmpty())
        assertEquals(ActionMessage.Uninstalled("lint", "Removed lint."), s.messages[card.key])
        val args = r.helper.client.callsFor(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE).single().args
        assertEquals("plugin", args["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without the helper uninstall runs the CLI and says Uninstalled name`() = runTest(main) {
        val r = rig()
        r.helper.unavailable()
        runCurrent()
        r.termux.on(ClineCommands.Labels.PLUGIN_UNINSTALL, TermuxResult.success("{}"))
        val card = r.vm.state.value.plugin()
        r.vm.uninstall(card.key, card.item)
        runCurrent()
        assertEquals(ActionMessage.Uninstalled("lint", null), r.vm.state.value.messages[card.key])
        assertTrue(r.vm.state.value.uninstalling.isEmpty())
        assertTrue(r.termux.ran(ClineCommands.Labels.PLUGIN_UNINSTALL))
    }

    @Test
    fun `a failed uninstall leaves the failure message on the card`() = runTest(main) {
        val r = rig()
        r.helper.unavailable()
        runCurrent()
        r.termux.on(ClineCommands.Labels.PLUGIN_UNINSTALL, TermuxResult.failure(1, "Error: plugin is busy"))
        val card = r.vm.state.value.plugin()
        r.vm.uninstall(card.key, card.item)
        runCurrent()
        val s = r.vm.state.value
        assertTrue(s.messages[card.key] is ActionMessage.Failed)
        assertTrue(s.pending.isEmpty())
        assertTrue(s.uninstalling.isEmpty())
    }

    @Test
    fun `resume clears an uninstall older than 120 s and ignores its late reply`() = runTest(main) {
        var now = 0L
        val r = rig(clockMs = { now })
        val reply = CompletableDeferred<JsonElement>()
        r.helper.client.respond(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE) { reply.await() }
        val card = r.vm.state.value.plugin()
        r.vm.uninstall(card.key, card.item)
        runCurrent()

        r.vm.events.test {
            now = CustomizeRepository.UNINSTALL_STALE_MS - 1
            r.vm.onResume()
            runCurrent()
            expectNoEvents()
            assertTrue("not stale yet", card.key in r.vm.state.value.uninstalling)

            now = CustomizeRepository.UNINSTALL_STALE_MS
            r.vm.onResume()
            assertEquals(CustomizeEvent.UninstallMayHaveFinished, awaitItem())
        }
        runCurrent()
        assertFalse(card.key in r.vm.state.value.pending)
        assertFalse(card.key in r.vm.state.value.uninstalling)

        reply.complete(buildJsonObject { put("message", JsonPrimitive("Removed lint.")) })
        runCurrent()
        assertNull("the cleared uninstall's late reply is dropped", r.vm.state.value.messages[card.key])
    }

    // ---- workspace scope (CR §1.9) -----------------------------------------------------------------

    @Test
    fun `a vanished workspace reverts the scope to the Termux home`() = runTest(main) {
        val r = rig()
        val tools = """[{"id":"read_files","description":"Read","defaultEnabled":true,"headlessToolNames":["read_file"]}]"""
        r.termux.on(ClineCommands.Labels.CONFIG_TOOLS) { command ->
            if (command.workdir == ws) TermuxResult.failure(1, "bash: cd: $ws: No such file or directory") else TermuxResult.success(tools)
        }
        r.vm.events.test {
            r.vm.refresh()
            assertEquals(CustomizeEvent.WorkspaceMissing, awaitItem())
        }
        runCurrent()
        assertEquals(listOf(TERMUX_HOME), r.workspace.stored)
        assertEquals(TERMUX_HOME, r.vm.state.value.workspaceRoot)
        assertEquals(TERMUX_HOME, r.termux.commandsLabelled(ClineCommands.Labels.CONFIG_TOOLS).last().workdir)
    }

    @Test
    fun `a workdir Termux refuses before running reverts the scope to the Termux home`() = runTest(main) {
        val r = rig()
        val tools = """[{"id":"read_files","description":"Read","defaultEnabled":true,"headlessToolNames":["read_file"]}]"""
        val refused = TermuxResult(
            stdout = "", stderr = "", exitCode = -1, err = TermuxResult.ERR_EXECUTABLE_FAILED,
            errmsg = "Error Code: `150`\nError Message (FileUtils Error):\n```\nThe working directory not found at path \"$ws\".\n```",
        )
        r.termux.on(ClineCommands.Labels.CONFIG_TOOLS) { command ->
            if (command.workdir == ws) refused else TermuxResult.success(tools)
        }
        r.vm.events.test {
            r.vm.refresh()
            assertEquals(CustomizeEvent.WorkspaceMissing, awaitItem())
        }
        runCurrent()
        assertEquals(TERMUX_HOME, r.vm.state.value.workspaceRoot)
        assertEquals(TERMUX_HOME, r.termux.commandsLabelled(ClineCommands.Labels.CONFIG_TOOLS).last().workdir)
    }

    // ---- MCP switch and bulk tools (CR §1.8) -------------------------------------------------------

    @Test
    fun `an mcp switch is pending until the reply and a failure is reported`() = runTest(main) {
        val r = rig()
        val card = r.vm.state.value.mcp.servers.single()
        val answer = r.hub.onCommand
        r.hub.onCommand = { env -> if (env.command == HubCommands.SETTINGS_TOGGLE) null else answer(env) }

        r.vm.toggleMcp(card, enabled = false)
        runCurrent()
        assertTrue(card.key in r.vm.state.value.pending)
        r.vm.toggleMcp(card, enabled = false)
        runCurrent()
        val sent = r.hub.current.commands.filter { it.command == HubCommands.SETTINGS_TOGGLE }
        assertEquals("a pending switch is not sent twice", 1, sent.size)
        assertEquals("mcp", sent.single().payload!!["type"]!!.jsonPrimitive.content)
        assertEquals("gh", sent.single().payload!!["name"]!!.jsonPrimitive.content)

        r.vm.events.test {
            r.hub.current.reply(sent.single().requestId!!, ok = false, error = FakeHubTransport.errorReply(sent.single(), "toggle_failed", "nope").error)
            assertTrue(awaitItem() is CustomizeEvent.ToggleFailed)
        }
        runCurrent()
        assertFalse(card.key in r.vm.state.value.pending)
    }

    @Test
    fun `enable all toggles only the tools that are off`() = runTest(main) {
        val r = rig()
        val readFiles = r.vm.state.value.tools.builtin.first { it.name == "read_files" }
        r.vm.toggleTool(readFiles, enabled = false)
        runCurrent()
        val shown = r.vm.state.value.tools.builtin
        assertFalse(shown.first { it.name == "read_files" }.enabled)
        assertTrue(shown.filter { it.name != "read_files" }.all { it.enabled })
        val before = r.hub.current.commands.count { it.command == HubCommands.SETTINGS_TOGGLE }

        r.vm.setAllTools(shown, enable = true)
        runCurrent()
        val sent = r.hub.current.commands.filter { it.command == HubCommands.SETTINGS_TOGGLE }.drop(before)
        assertEquals(readFiles.toggleNames.distinct(), sent.map { it.payload!!["name"]!!.jsonPrimitive.content })
        assertTrue(sent.all { it.payload!!["enabled"]!!.jsonPrimitive.content == "true" })
        assertTrue(r.vm.state.value.tools.builtin.all { it.enabled })
        assertTrue(r.vm.state.value.pending.isEmpty())
    }

    @Test
    fun `enable all with every tool on sends nothing`() = runTest(main) {
        val r = rig()
        r.vm.setAllTools(r.vm.state.value.tools.builtin, enable = true)
        runCurrent()
        assertNull(r.hub.current.command(HubCommands.SETTINGS_TOGGLE))
    }

    // ---- kernel down on first open (CR §0.4) -------------------------------------------------------

    @Test
    fun `hub data counts as loaded only once settings list succeeded`() = runTest(main) {
        val ok = rig()
        assertTrue(ok.vm.state.value.hubLoaded)

        // The Termux sources still answer, so cards exist, but the KernelStatePanel stays while the Hub is down.
        val r = rig(listFails = true)
        r.gate.value = KernelGate.Down(KernelGate.DownVariant.Disconnected, null)
        runCurrent()
        val s = r.vm.state.value
        assertTrue(s.hasData)
        assertTrue(s.loadError != null)
        assertFalse(s.hubLoaded)
    }
}
