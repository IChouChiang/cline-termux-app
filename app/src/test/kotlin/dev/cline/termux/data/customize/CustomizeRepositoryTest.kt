package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
class CustomizeRepositoryTest {
    private val ws = "/data/data/com.termux/files/home/app"
    private val snapshotJson = """
        {"snapshot":{"workflows":[],"rules":[],"plugins":[{"id":"p","name":"lint","path":"$ws/.cline/plugins/lint","kind":"plugin","source":"workspace-plugin","enabled":true,"toggleable":true}],
          "skills":[{"id":"s","name":"review","path":"/h/.cline/skills/review/SKILL.md","kind":"skill","source":"global","enabled":true,"toggleable":true}],
          "tools":[{"id":"t","name":"lint_run","path":"$ws/.cline/plugins/lint","kind":"tool","source":"workspace-plugin","enabled":true,"pluginName":"lint"}],"mcp":[]}}
    """.trimIndent()
    private val toolsJson = """[{"id":"read_files","description":"Read","defaultEnabled":true,"headlessToolNames":["read_file"]}]"""

    private fun json(text: String) = CustomizeJson.parseToJsonElement(text).jsonObject

    private class Rig(
        val repo: CustomizeRepository,
        val hub: FakeHubTransport,
        val termux: FakeTermuxExecutor,
        val helper: FakeHelperChannel,
        val clock: FakeClock,
        val inventory: MutableSharedFlow<Unit>,
    )

    private suspend fun TestScope.rig(
        helperState: HelperState = HelperState.Idle,
        replies: (dev.cline.termux.core.kernel.hub.HubCommandEnvelope) -> dev.cline.termux.core.kernel.hub.HubReplyEnvelope? = { env ->
            when (env.command) {
                HubCommands.SETTINGS_LIST, HubCommands.SETTINGS_TOGGLE -> env.ok(json(snapshotJson))
                else -> null
            }
        },
    ): Rig {
        val transport = FakeHubTransport()
        val client = connectedHub(transport, backgroundScope, replies)
        val termux = FakeTermuxExecutor().apply {
            on(ClineCommands.Labels.CONFIG_TOOLS, TermuxResult.success(toolsJson))
            on(ClineCommands.Labels.CONFIG_HOOKS, TermuxResult.success("""[{"fileName":"Pre.sh","hookEventName":"PreToolUse","path":"$ws/.clinerules/hooks/Pre.sh"}]"""))
            on(ClineCommands.Labels.READ_GLOBAL_SETTINGS, TermuxResult.success("""{"disabledTools":["tasks"]}"""))
        }
        val helper = FakeHelperChannel(helperState)
        val clock = FakeClock()
        val inventory = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val repo = CustomizeRepository(client, termux, helper, inventory, backgroundScope, clock)
        runCurrent()
        return Rig(repo, transport, termux, helper, clock, inventory)
    }

    @Test
    fun `load combines settings list, the CLI tool catalog with tasks, and hooks`() = runTest {
        val r = rig()
        val lists = r.repo.load(ws)
        assertNull(lists.loadError)
        assertTrue(lists.warnings.isEmpty())
        assertEquals(listOf("review"), lists.snapshot!!.skills.map { it.name })
        assertEquals(listOf("read_files", "tasks"), lists.builtinTools.map { it.id })
        assertFalse("tasks is in disabledTools", lists.builtinTools.last().enabled)
        assertEquals(1, lists.hooks.size)
        assertEquals(CustomizeCounts(tools = 3, plugins = 1, skills = 1, rules = 0, mcp = 0, hooks = 1), lists.counts)
        val list = r.hub.current.command(HubCommands.SETTINGS_LIST)!!
        assertEquals(ws, list.payload!!["workspaceRoot"]!!.jsonPrimitive.content)
        assertEquals(ws, list.payload!!["cwd"]!!.jsonPrimitive.content)
        assertEquals("true", list.payload!!["includePluginTools"]!!.jsonPrimitive.content)
        assertEquals(60_000L, list.timeoutMs)
        assertEquals(ws, r.termux.commandsLabelled(ClineCommands.Labels.CONFIG_TOOLS).single().workdir)
    }

    @Test
    fun `the 60 s cache is keyed by workspace and force bypasses it`() = runTest {
        val r = rig()
        r.repo.load(ws)
        r.repo.load(ws)
        assertEquals(1, r.hub.current.commands.count { it.command == HubCommands.SETTINGS_LIST })
        r.repo.load("/other")
        assertEquals(2, r.hub.current.commands.count { it.command == HubCommands.SETTINGS_LIST })
        r.clock.advanceMillis(60_001)
        assertFalse(r.repo.isFresh(ws))
        assertNotNull("stale lists stay available for the loading-with-cache state", r.repo.cached(ws))
        r.repo.load(ws)
        r.repo.load(ws, force = true)
        assertEquals(4, r.hub.current.commands.count { it.command == HubCommands.SETTINGS_LIST })
    }

    @Test
    fun `a failing hooks source is a partial result and a blocked termux marks the tab`() = runTest {
        val r = rig()
        r.termux.never(ClineCommands.Labels.CONFIG_HOOKS)
        r.termux.on(ClineCommands.Labels.CONFIG_TOOLS) { throw dev.cline.termux.core.kernel.termux.TermuxUnavailable.ExternalAppsBlocked("allow-external-apps") }
        val lists = r.repo.load(ws)
        assertNull(lists.loadError)
        assertEquals(
            listOf(CustomizeRepository.Source.Tools to RepoFailure.Termux(TermuxProblem.ExternalAppsBlocked), CustomizeRepository.Source.Hooks to RepoFailure.Termux(TermuxProblem.TimedOut)),
            lists.warnings.map { it.source to it.failure },
        )
        assertEquals(TermuxProblem.ExternalAppsBlocked, lists.toolsBlocked)
        assertEquals(TermuxProblem.TimedOut, lists.hooksBlocked)
        assertEquals("plugin tools still come from the hub", 1, lists.counts.tools)
    }

    @Test
    fun `a settings list failure is the load error, kernel text verbatim`() = runTest {
        val r = rig(replies = { env -> if (env.command == HubCommands.SETTINGS_LIST) env.fail("settings_list_failed", "plugin setup exploded") else null })
        val lists = r.repo.load(ws)
        assertEquals(RepoFailure.Hub("settings_list_failed", "plugin setup exploded"), lists.loadError)
        assertNull(lists.snapshot)
        assertEquals(2, lists.builtinTools.size)
    }

    @Test
    fun `a tool toggle sends each distinct name in order and updates the built-in in place`() = runTest {
        val r = rig()
        r.repo.load(ws)
        val tool = r.repo.cached(ws)!!.builtinTools.first()
        val out = r.repo.toggleTool(ws, tool.toggleNames, tool.id, enabled = false)
        val toggles = r.hub.current.commands.filter { it.command == HubCommands.SETTINGS_TOGGLE }
        assertEquals(listOf("read_files", "read_file"), toggles.map { it.payload!!["name"]!!.jsonPrimitive.content })
        assertTrue(toggles.all { it.payload!!["type"]!!.jsonPrimitive.content == "tools" && it.payload!!["enabled"]!!.jsonPrimitive.content == "false" })
        val lists = (out as Outcome.Ok).value
        assertFalse(lists.builtinTools.first().enabled)
        assertEquals("the CLI catalog is not re-run", 1, r.termux.commandsLabelled(ClineCommands.Labels.CONFIG_TOOLS).size)
    }

    @Test
    fun `an item toggle sends type, path and workspace and applies the reply snapshot`() = runTest {
        val r = rig()
        r.repo.load(ws)
        val skill = r.repo.cached(ws)!!.snapshot!!.skills.single()
        r.repo.toggleItem(ws, skill, enabled = false)
        val toggle = r.hub.current.command(HubCommands.SETTINGS_TOGGLE)!!.payload!!
        assertEquals("skills", toggle["type"]!!.jsonPrimitive.content)
        assertEquals(skill.path, toggle["path"]!!.jsonPrimitive.content)
        assertEquals(ws, toggle["workspaceRoot"]!!.jsonPrimitive.content)
    }

    @Test
    fun `settings changed and inventory changes invalidate the cache`() = runTest {
        val r = rig()
        r.repo.load(ws)
        val seen = mutableListOf<Unit>()
        backgroundScope.launch { r.repo.invalidations.collect { seen += it } }
        runCurrent()
        r.hub.current.event(HubEvents.SETTINGS_CHANGED, payload = JsonObject(emptyMap()))
        runCurrent()
        assertEquals(1, seen.size)
        assertNull(r.repo.cached(ws))
        r.repo.load(ws)
        r.inventory.emit(Unit)
        runCurrent()
        assertEquals(2, seen.size)
        assertNull(r.repo.cached(ws))
    }

    @Test
    fun `uninstall uses the helper when ready and the CLI otherwise`() = runTest {
        val r = rig(helperState = HelperState.Ready)
        r.helper.client.respond(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE, json("""{"status":"uninstalled","message":"Uninstalled review."}"""))
        val lists = r.repo.load(ws)
        val skill = lists.snapshot!!.skills.single()
        assertEquals(CustomizeRepository.UninstallResult.Done("Uninstalled review."), r.repo.uninstall(ws, skill))
        val args = r.helper.client.callsFor(CustomizeRepository.UNINSTALL_LOCAL_PRIMITIVE).single().args
        assertEquals("skill", args["type"]!!.jsonPrimitive.content)
        assertEquals(skill.path, args["path"]!!.jsonPrimitive.content)

        r.helper.unavailable()
        assertEquals(CustomizeRepository.UninstallResult.Done(null), r.repo.uninstall(ws, skill))
        assertEquals(listOf("skill", "remove", "review", "-g", "-y"), r.termux.commandsLabelled(ClineCommands.Labels.SKILL_REMOVE).single().args)
        val workflow = skill.copy(kind = SettingsKind.Workflow)
        assertFalse(r.repo.canUninstall(workflow))
        assertEquals(CustomizeRepository.UninstallResult.NotSupported, r.repo.uninstall(ws, workflow))

        r.termux.on(ClineCommands.Labels.PLUGIN_UNINSTALL, TermuxResult.failure(1, "error: plugin not found\napiKey=sk-123"))
        val plugin = r.repo.cached(ws)!!.snapshot!!.plugins.single()
        val failed = r.repo.uninstall(ws, plugin) as CustomizeRepository.UninstallResult.Failed
        val message = (failed.failure as RepoFailure.Command).message
        assertTrue(message.contains("plugin not found"))
        assertFalse("CLI output is redacted", message.contains("sk-123"))
    }

    @Test
    fun `a dropped socket on settings list is retried once after the reconnect`() = runTest {
        var calls = 0
        val r = rig(replies = { env ->
            if (env.command == HubCommands.SETTINGS_LIST) {
                calls++
                if (calls == 1) env.fail("hub_connection_closed", "connection closed") else env.ok(json(snapshotJson))
            } else {
                null
            }
        })
        val lists = r.repo.load(ws)
        assertNull(lists.loadError)
        assertEquals(2, calls)
        assertEquals(HelperState.Idle, r.helper.state.first())
    }
}
