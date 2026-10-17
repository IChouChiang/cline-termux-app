package dev.cline.termux.data.agents

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.fail
import dev.cline.termux.fakes.ok
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsRepositoryTest {
    private fun obj(text: String): JsonObject = CustomizeJson.parseToJsonElement(text).jsonObject
    private fun arr(text: String): JsonArray = CustomizeJson.parseToJsonElement(text).jsonArray

    private fun record(id: String, parent: String?, agentId: String?, status: String, createdAt: Long, team: String? = null) = """
        {"sessionId":"$id","status":"$status","createdAt":$createdAt,"updatedAt":${createdAt + 1},
         "metadata":{${listOfNotNull(parent?.let { "\"parentSessionId\":\"$it\"" }, agentId?.let { "\"agentId\":\"$it\"" }, "\"prompt\":\"task $id\"", "\"messagesPath\":\"/m\"").joinToString(",")}}
         ${team?.let { ""","runtimeSession":{"agentId":"x","team":{"teamId":"$it"}}""" } ?: ""}}
    """.trimIndent()

    @Test
    fun `children are filtered by parent and agent id, kinded, team-named and sorted by start`() {
        val records = arr(
            "[" + listOf(
                record("late", "p", "a2", "completed", 20),
                record("p__teamtask__1", "p", "a1", "running", 10, team = "squad"),
                record("orphan", "p", null, "running", 5),
                record("other", "q", "a3", "running", 1),
                record("blank-team", "p", "a4", "idle", 30, team = " "),
            ).joinToString(",") + "]",
        ).map { it.jsonObject }
        val children = AgentsRepository.childrenOf(records, "p").sortedBy { it.createdAt }
        assertEquals(listOf("p__teamtask__1", "late", "blank-team"), children.map { it.entry.sessionId })
        val team = children.first().entry
        assertEquals(SessionAgentEntry.Kind.Teamtask, team.kind)
        assertEquals("squad", team.teamName)
        assertEquals("task p__teamtask__1", team.prompt)
        assertNull(team.endedAt)
        assertEquals(SessionAgentEntry.Kind.Subagent, children[1].entry.kind)
        assertNull("a blank team id is no name", children[2].entry.teamName)
        assertEquals(SessionAgentRunState.Unresolved, agentEntryState(children[2].entry.status))
    }

    @Test
    fun `deriveLastAction ports the sidecar`() {
        assertEquals(LastAction.Tool("read_file"), AgentsRepository.deriveLastAction(arr("""[{"role":"assistant","content":[{"type":"text","text":"x"},{"type":"tool_use","name":"read_file"}]}]""")))
        assertEquals(LastAction.Text("done now"), AgentsRepository.deriveLastAction(arr("""[{"role":"assistant","content":"  done \n now "},{"role":"user","content":"hi"}]""")))
        assertEquals(LastAction.Text("older"), AgentsRepository.deriveLastAction(arr("""[{"role":"assistant","content":"older"},{"role":"assistant","content":"   "}]""")))
        val long = "a".repeat(200)
        assertEquals(LastAction.Text("a".repeat(160) + "..."), AgentsRepository.deriveLastAction(arr("""[{"role":"assistant","content":[{"type":"text","text":"$long"}]}]""")))
        assertNull(AgentsRepository.deriveLastAction(arr("""[{"role":"user","content":"x"}]""")))
    }

    @Test
    fun `truncate never splits a surrogate pair or a combining sequence on the 160 boundary`() {
        // An emoji (2 UTF-16 units) straddling units 159..160: the cut falls before it.
        val emoji = "a".repeat(159) + "\uD83D\uDE00" + "tail"
        assertEquals("a".repeat(159) + "...", AgentsRepository.truncate(emoji))
        // CJK Extension B 𠮷 (U+20BB7) straddling the boundary.
        val cjk = "a".repeat(159) + "\uD842\uDFB7" + "tail"
        assertEquals("a".repeat(159) + "...", AgentsRepository.truncate(cjk))
        // A whole surrogate pair that ends exactly at 160 is kept.
        val fits = "a".repeat(158) + "\uD842\uDFB7" + "tail"
        assertEquals("a".repeat(158) + "\uD842\uDFB7...", AgentsRepository.truncate(fits))
        // e + combining acute at units 159..160 stays together.
        val combining = "a".repeat(159) + "e\u0301" + "tail"
        assertEquals("a".repeat(159) + "...", AgentsRepository.truncate(combining))
        // Within the budget nothing changes.
        assertEquals("\uD842\uDFB7 ok", AgentsRepository.truncate("  \uD842\uDFB7   ok "))
    }

    @Test
    fun `a full first page with fewer children than the tally re-requests with limit 1000`() = runTest {
        val transport = FakeHubTransport()
        val limits = mutableListOf<Int>()
        val hub = connectedHub(transport, backgroundScope) { env ->
            when (env.command) {
                HubCommands.SESSION_LIST -> {
                    val limit = env.payload!!["limit"]!!.jsonPrimitive.content.toInt()
                    limits += limit
                    assertEquals("false", env.payload!!["rootOnly"]!!.jsonPrimitive.content)
                    val filler = (1..200).joinToString(",") { record("f$it", "z", "a", "completed", it.toLong()) }
                    val extra = if (limit == 1000) "," + record("c2", "p", "a2", "running", 2000) else ""
                    env.ok(obj("""{"sessions":[$filler$extra]}"""))
                }
                HubCommands.SESSION_MESSAGES -> env.ok(obj("""{"messages":[{"role":"assistant","content":"working"}]}"""))
                else -> null
            }
        }
        val repo = AgentsRepository(hub, { emptyFlow() })
        val children = (repo.load("p", expectedTotal = 1) as Outcome.Ok).value
        assertEquals(listOf(200, 1000), limits)
        assertEquals(LastAction.Text("working"), children.single().entry.lastAction)
        assertEquals(listOf(200), limits.take(1))
    }

    @Test
    fun `finished rows reuse the cached last action, running rows refetch, and a failed refresh keeps the rows`() = runTest {
        val transport = FakeHubTransport()
        var messageCalls = 0
        var failList = false
        val hub = connectedHub(transport, backgroundScope) { env ->
            when (env.command) {
                HubCommands.SESSION_LIST -> if (failList) env.fail("command_failed", "db locked") else
                    env.ok(obj("""{"sessions":[${record("done", "p", "a1", "completed", 1)},${record("run", "p", "a2", "running", 2)}]}"""))
                HubCommands.SESSION_MESSAGES -> {
                    messageCalls++
                    env.ok(obj("""{"messages":[]}"""))
                }
                else -> null
            }
        }
        val activity = MutableStateFlow(0)
        val repo = AgentsRepository(hub, { activity })
        val rosters = mutableListOf<AgentsRepository.Roster>()
        backgroundScope.launch { repo.observe("p") { 2 }.collect { rosters += it } }
        runCurrent()
        assertEquals(2, rosters.last().children.size)
        assertEquals(2, messageCalls)

        activity.value = 1
        advanceTimeBy(800)
        runCurrent()
        assertEquals("only the running row is fetched again", 3, messageCalls)

        failList = true
        advanceTimeBy(5_000)
        runCurrent()
        val last = rosters.last()
        assertEquals(2, last.children.size)
        assertTrue(last.error != null)
    }
}
