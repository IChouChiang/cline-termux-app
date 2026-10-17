package dev.cline.termux.ui.customize.mcp

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.McpInputError
import dev.cline.termux.data.customize.McpRepository
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.ui.customize.FakeLease
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
import kotlinx.serialization.json.jsonArray
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
class McpServerEditorViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val listReply = """
        {"settingsPath":"/s.json","hasSettingsFile":true,"servers":[
          {"name":"gh","transportType":"stdio","disabled":false,"command":"npx","args":["-y","gh"],"env":{"GITHUB_TOKEN":"ghp_secret"}},
          {"name":"docs","transportType":"streamableHttp","disabled":true,"url":"https://d.example/mcp","metadata":{"autoApprove":["x"]}}
        ]}
    """.trimIndent()

    private class Rig(
        val vm: McpServerEditorViewModel,
        val helper: FakeHelperChannel,
        val lease: FakeLease,
        val termux: FakeTermuxExecutor,
        val saved: SavedStateHandle,
        val gate: MutableStateFlow<KernelGate>,
    )

    private suspend fun TestScope.rig(
        name: String? = null,
        helperState: HelperState = HelperState.Ready,
        saved: SavedStateHandle = SavedStateHandle(),
        setup: (FakeTermuxExecutor) -> Unit = {},
    ): Rig {
        val hub = connectedHub(FakeHubTransport(), backgroundScope)
        val termux = FakeTermuxExecutor().also(setup)
        val helper = FakeHelperChannel(helperState)
        helper.client.respond(McpRepository.LIST, CustomizeJson.parseToJsonElement(listReply))
        val repo = McpRepository(hub, termux, helper, MutableSharedFlow(extraBufferCapacity = 1))
        val lease = FakeLease(helper)
        val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
        val vm = McpServerEditorViewModel(repo, lease, gate, saved, name, backgroundScope, helperWaitMs = 5_000)
        runCurrent()
        return Rig(vm, helper, lease, termux, saved, gate)
    }

    @Test
    fun `create starts blank, acquires the helper and validates the required fields`() = runTest(main) {
        val r = rig()
        val s = r.vm.state.value
        assertFalse(s.isEdit)
        assertEquals("/s.json", s.settingsPath)
        assertFalse(s.dirty)
        assertEquals(1, r.lease.owners.size)

        r.vm.save()
        assertEquals(McpField.Name, r.vm.state.value.fieldError)
        assertEquals(McpFormError.Validation(McpInputError.NameRequired), r.vm.state.value.formError)

        r.vm.setName("new")
        assertNull("an edit clears the error", r.vm.state.value.formError)
        r.vm.save()
        assertEquals(McpField.Command, r.vm.state.value.fieldError)

        r.vm.setType(ServerType.Remote)
        r.vm.save()
        assertEquals(McpFormError.Validation(McpInputError.UrlRequired), r.vm.state.value.formError)
    }

    @Test
    fun `a name that already exists is a collision, nothing is sent`() = runTest(main) {
        val r = rig()
        r.vm.setName("gh")
        r.vm.setCommand("npx")
        r.vm.save()
        assertEquals(McpFormError.Collision("gh"), r.vm.state.value.formError)
        assertTrue(r.helper.client.callsFor(McpRepository.UPSERT).isEmpty())
    }

    @Test
    fun `a helper save sends env and closes`() = runTest(main) {
        val r = rig()
        r.helper.client.respond(McpRepository.UPSERT, CustomizeJson.parseToJsonElement(listReply))
        r.vm.setName("fs")
        r.vm.setCommand("npx")
        r.vm.setArgs("-y, server-fs")
        val row = r.vm.state.value.env.single().id
        r.vm.setEnvKey(row, "API_KEY")
        r.vm.setEnvValue(row, "value-1")
        assertTrue(r.vm.state.value.dirty)
        r.vm.events.test {
            r.vm.save()
            assertEquals(McpEditorEvent.Close, awaitItem())
        }
        val input = r.helper.client.callsFor(McpRepository.UPSERT).single().args["input"]!!.jsonObject
        assertEquals("fs", input["name"]!!.jsonPrimitive.content)
        assertEquals("value-1", input["env"]!!.jsonObject["API_KEY"]!!.jsonPrimitive.content)
        assertEquals(listOf("-y", "server-fs"), input["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(r.vm.state.value.saving)
    }

    @Test
    fun `edit fills the form, keeps secrets out of saved state and toString`() = runTest(main) {
        val r = rig(name = "gh")
        val s = r.vm.state.value
        assertTrue(s.isEdit)
        assertFalse(s.loading)
        assertEquals("gh", s.name)
        assertEquals("npx", s.command)
        assertEquals("GITHUB_TOKEN", s.env.single().key)
        assertEquals("ghp_secret", s.env.single().value)
        assertFalse(s.dirty)
        val persisted = r.saved.keys().map { r.saved.get<Any>(it).toString() }
        assertTrue(persisted.none { "ghp_secret" in it })
        assertFalse("ghp_secret" in s.env.single().toString())
        assertFalse("ghp_secret" in s.toString())
    }

    @Test
    fun `a restored edit asks for env values again`() = runTest(main) {
        val first = rig(name = "gh")
        val saved = first.saved
        val r = rig(name = "gh", saved = saved)
        assertTrue(r.vm.state.value.reenterEnv)
        assertEquals("GITHUB_TOKEN", r.vm.state.value.env.single().key)
        assertEquals("", r.vm.state.value.env.single().value)
    }

    @Test
    fun `metadata needs a confirmation before saving`() = runTest(main) {
        val r = rig(name = "docs")
        r.helper.client.respond(McpRepository.UPSERT, CustomizeJson.parseToJsonElement(listReply))
        assertEquals(listOf("autoApprove"), r.vm.state.value.metadataKeys)
        assertTrue("Advanced opens when metadata exists", r.vm.state.value.advancedOpen)
        assertEquals(ServerType.Remote, r.vm.state.value.type)
        r.vm.save()
        assertTrue(r.vm.state.value.metadataConfirm)
        assertTrue(r.helper.client.callsFor(McpRepository.UPSERT).isEmpty())
        r.vm.dismissMetadataConfirm()
        assertFalse(r.vm.state.value.metadataConfirm)
        r.vm.save()
        r.vm.confirmMetadataSave()
        assertEquals(1, r.helper.client.callsFor(McpRepository.UPSERT).size)
    }

    @Test
    fun `the fallback strips env from a new server`() = runTest(main) {
        val down = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        val r = rig(helperState = down) { termux ->
            termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{}}"""))
            termux.on(ClineCommands.Labels.MCP_INSTALL, TermuxResult.success("{}"))
        }
        assertTrue(r.vm.state.value.fallback)
        // The re-read after the install confirms the new server.
        r.termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{"fs":{"transport":{"type":"stdio","command":"npx"}}}}"""))
        r.vm.setName("fs")
        r.vm.setCommand("npx")
        val row = r.vm.state.value.env.single().id
        r.vm.setEnvKey(row, "TOKEN")
        r.vm.setEnvValue(row, "dropped")
        r.vm.save()
        val install = r.termux.commandsLabelled(ClineCommands.Labels.MCP_INSTALL).single()
        assertFalse(install.args.any { "dropped" in it })
    }

    @Test
    fun `the fallback refuses an existing server that has env`() = runTest(main) {
        val r = rig(name = "gh")
        r.helper.unavailable()
        runCurrent()
        assertTrue(r.vm.state.value.blockedInFallback)
        assertFalse(r.vm.state.value.canSave)
        r.vm.retryHelper()
        assertEquals(1, r.lease.retries)
        assertFalse(r.vm.state.value.blockedInFallback)
    }

    @Test
    fun `save waits for a starting helper, then uses it`() = runTest(main) {
        val r = rig(helperState = HelperState.Starting(0L)) { it.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{}}""")) }
        r.termux.on(ClineCommands.Labels.READ_MCP_SETTINGS, TermuxResult.success("""{"mcpServers":{}}"""))
        r.helper.client.respond(McpRepository.UPSERT, CustomizeJson.parseToJsonElement(listReply))
        r.vm.setName("fs")
        r.vm.setCommand("npx")
        r.vm.save()
        assertTrue(r.vm.state.value.saving)
        assertEquals(McpRoute.Waiting, r.vm.state.value.route)
        r.helper.mutableState.value = HelperState.Ready
        runCurrent()
        assertFalse(r.vm.state.value.saving)
        assertEquals(1, r.helper.client.callsFor(McpRepository.UPSERT).size)
    }

    @Test
    fun `saving is blocked while the kernel is down`() = runTest(main) {
        val r = rig()
        r.gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        runCurrent()
        assertFalse(r.vm.state.value.canSave)
        r.vm.setName("fs")
        r.vm.setCommand("npx")
        r.vm.save()
        assertTrue(r.helper.client.callsFor(McpRepository.UPSERT).isEmpty())
    }

    @Test
    fun `delete confirms, sends the name and closes, a failure stays in the dialog`() = runTest(main) {
        val r = rig(name = "gh")
        r.helper.client.respond(McpRepository.DELETE) { throw dev.cline.termux.core.kernel.dashboard.HelperUnavailable.TransportClosed() }
        r.vm.requestDelete()
        assertTrue(r.vm.state.value.deleteDialog)
        r.vm.confirmDelete()
        assertTrue(r.vm.state.value.deleteDialog)
        assertTrue(r.vm.state.value.deleteError != null)
        assertFalse(r.vm.state.value.deleting)

        r.helper.client.respond(McpRepository.DELETE, CustomizeJson.parseToJsonElement(listReply))
        r.vm.events.test {
            r.vm.confirmDelete()
            assertEquals(McpEditorEvent.Close, awaitItem())
        }
        assertFalse(r.vm.state.value.deleteDialog)
        assertEquals("gh", r.helper.client.callsFor(McpRepository.DELETE).last().args["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `removing the last env row clears it and reveal is per row`() = runTest(main) {
        val r = rig()
        val first = r.vm.state.value.env.single().id
        r.vm.setEnvKey(first, "A")
        r.vm.addEnv()
        assertEquals(2, r.vm.state.value.env.size)
        r.vm.toggleReveal(first)
        assertTrue(r.vm.state.value.anyRevealed)
        r.vm.removeEnv(first)
        assertFalse(r.vm.state.value.anyRevealed)
        val last = r.vm.state.value.env.single().id
        r.vm.removeEnv(last)
        assertEquals("", r.vm.state.value.env.single().key)
        advanceTimeBy(1)
    }
}
