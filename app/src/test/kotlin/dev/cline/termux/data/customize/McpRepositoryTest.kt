package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRepositoryTest {
    private val listReply = """{"settingsPath":"/s.json","hasSettingsFile":true,"servers":[{"name":"gh","transportType":"stdio","disabled":false,"command":"npx","args":["gh"]}]}"""

    private class Rig(val repo: McpRepository, val hub: FakeHubTransport, val termux: FakeTermuxExecutor, val helper: FakeHelperChannel, val inventory: MutableSharedFlow<Unit>)

    private suspend fun TestScope.rig(state: HelperState): Rig {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope)
        val termux = FakeTermuxExecutor()
        val helper = FakeHelperChannel(state)
        val inventory = MutableSharedFlow<Unit>(replay = 1)
        return Rig(McpRepository(hub, termux, helper, inventory), transport, termux, helper, inventory)
    }

    @Test
    fun `list uses the helper when ready and the settings file otherwise`() = runTest {
        val r = rig(HelperState.Ready)
        r.helper.client.respond(McpRepository.LIST, CustomizeJson.parseToJsonElement(listReply))
        val viaHelper = (r.repo.list() as Outcome.Ok).value
        assertEquals(McpSource.Helper, viaHelper.source)
        assertFalse(r.termux.ran(ClineCommands.Labels.READ_MCP_SETTINGS))

        r.helper.unavailable()
        r.termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.failure(1, "cat: /x: No such file or directory"))
        val viaFile = (r.repo.list() as Outcome.Ok).value
        assertEquals(McpSource.File, viaFile.source)
        assertFalse(viaFile.hasSettingsFile)
    }

    @Test
    fun `a helper save sends the input and announces the inventory change`() = runTest {
        val r = rig(HelperState.Ready)
        r.helper.client.respond(McpRepository.UPSERT, CustomizeJson.parseToJsonElement(listReply))
        val input = McpServerInput("gh", null, McpTransportType.Stdio, command = "npx", args = listOf("gh"))
        val result = r.repo.save(input, previous = null)
        assertTrue(result is McpRepository.WriteResult.Done)
        val sent = r.helper.client.callsFor(McpRepository.UPSERT).single().args["input"]!!.jsonObject
        assertEquals("gh", sent["name"]!!.jsonPrimitive.content)
        assertEquals(1, r.inventory.replayCache.size)
    }

    @Test
    fun `a helper that drops mid-save is confirmed by re-reading`() = runTest {
        val r = rig(HelperState.Ready)
        r.helper.client.respond(McpRepository.UPSERT) { throw HelperUnavailable.TransportClosed() }
        r.helper.client.respond(McpRepository.LIST, CustomizeJson.parseToJsonElement(listReply))
        val matching = McpServerInput("gh", null, McpTransportType.Stdio, command = "npx", args = listOf("gh"))
        assertTrue(r.repo.save(matching, null) is McpRepository.WriteResult.Done)
        val other = matching.copy(name = "new")
        assertEquals(McpRepository.WriteResult.Unconfirmed, r.repo.save(other, null))
    }

    @Test
    fun `the fallback renames by uninstall, install, then re-applies disabled over the hub`() = runTest {
        val r = rig(HelperState.Unavailable(dev.cline.termux.core.kernel.dashboard.HelperFailure.PortInUse, null))
        r.termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{"new":{"transport":{"type":"stdio","command":"npx"},"disabled":true}}}"""))
        val previous = McpServer("old", McpTransportType.Stdio, "stdio", disabled = false, command = "npx", args = listOf("a"))
        val input = McpServerInput("new", "old", McpTransportType.Stdio, command = "npx", args = listOf("b"), disabled = true)
        val result = r.repo.save(input, previous)
        assertTrue(result is McpRepository.WriteResult.Done)
        val labels = r.termux.labels
        assertEquals(listOf(ClineCommands.Labels.MCP_UNINSTALL, ClineCommands.Labels.MCP_INSTALL, ClineCommands.Labels.READ_MCP_SETTINGS), labels)
        assertEquals(listOf("mcp", "uninstall", "old", "--json"), r.termux.commands[0].args)
        assertEquals(listOf("mcp", "install", "new", "--yes", "--json", "--", "npx", "b"), r.termux.commands[1].args)
        val toggle = r.hub.current.command(HubCommands.SETTINGS_TOGGLE)!!.payload!!
        assertEquals("mcp", toggle["type"]!!.jsonPrimitive.content)
        assertEquals("new", toggle["name"]!!.jsonPrimitive.content)
        assertEquals("false", toggle["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed fallback install after a rename reinstalls the previous server`() = runTest {
        val r = rig(HelperState.Idle)
        var installs = 0
        r.termux.on(ClineCommands.Labels.MCP_INSTALL) { installs++; if (installs == 1) TermuxResult.failure(2, "bad command") else TermuxResult.success("{}") }
        val previous = McpServer("old", McpTransportType.StreamableHttp, "streamableHttp", disabled = false, url = "https://old")
        val input = McpServerInput("new", "old", McpTransportType.Stdio, command = "npx")
        val result = r.repo.save(input, previous) as McpRepository.WriteResult.Failed
        assertEquals(2, (result.failure as RepoFailure.Command).exitCode)
        val restore = r.termux.commandsLabelled(ClineCommands.Labels.MCP_INSTALL).last()
        assertEquals(listOf("mcp", "install", "old", "https://old", "--transport", "streamable-http", "--yes", "--json"), restore.args)
        assertTrue(r.inventory.replayCache.isEmpty())
    }

    @Test
    fun `the fallback refuses a server with env or cwd`() = runTest {
        val r = rig(HelperState.Idle)
        val previous = McpServer("gh", McpTransportType.Stdio, "stdio", disabled = false, command = "npx", env = mapOf("K" to "v"))
        assertEquals(McpRepository.WriteResult.BlockedInFallback, r.repo.save(McpServerInput("gh", "gh", McpTransportType.Stdio, command = "npx"), previous))
        assertTrue(r.termux.commands.isEmpty())
    }

    @Test
    fun `delete routes and the mcp switch works without the helper`() = runTest {
        val r = rig(HelperState.Idle)
        r.termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{}}"""))
        assertTrue(r.repo.delete("gh") is McpRepository.WriteResult.Done)
        assertEquals(listOf("mcp", "uninstall", "gh", "--json"), r.termux.commands.first().args)
        assertTrue(r.repo.setEnabled("gh", true) is Outcome.Ok)
        assertEquals("true", r.hub.current.command(HubCommands.SETTINGS_TOGGLE)!!.payload!!["enabled"]!!.jsonPrimitive.content)
    }
}
