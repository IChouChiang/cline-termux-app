package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Trial row 18, live path: the `$ <command>` line that starts a later command's output on the
 * tool row is the command from the call's input (`tool.started.input`), not the update's
 * `query`, which the kernel caps at 200 characters and ends with a truncation note.
 */
class LiveCommandHeaderTest {
    private val sid = "s1"
    private var sequence = 0L

    private val long = "echo " + "x".repeat(295)
    private val echo = long.take(200) + " ... [command truncated: 100 more chars; full command is in the tool call input]"

    private fun event(name: String, payload: JsonObject) = HubEventEnvelope(
        event = name,
        sequence = ++sequence,
        sessionId = sid,
        timestamp = 1_000L + sequence,
        payload = payload,
    )

    private fun started(input: JsonElement?) = event(
        HubEvents.TOOL_STARTED,
        buildJsonObject {
            put("toolCallId", "call_1")
            put("toolName", "run_commands")
            input?.let { put("input", it) }
        },
    )

    private fun updated(index: Int, query: String, chunk: String) = event(
        HubEvents.TOOL_UPDATED,
        buildJsonObject {
            put("toolCallId", "call_1")
            putJsonObject("update") {
                put("executionId", "x$index")
                put("commandIndex", index)
                put("query", query)
                put("stream", "stdout")
                put("chunk", chunk)
            }
        },
    )

    private fun rowOutput(vararg events: HubEventEnvelope): String {
        val ctx = ProjectContext(sessionId = sid, now = 5_000L)
        val state = events.fold(LiveProjection()) { s, e -> ChatProjector.project(s, e, ctx) }
        return state.messages.single { it.id == ChatProjector.toolId("call_1") }.meta.toolOutput.orEmpty()
    }

    @Test
    fun `a later command's line is the input's 300-character command`() {
        val input = buildJsonObject { putJsonArray("commands") { add("ls"); add(long) } }
        val output = rowOutput(started(input), updated(0, "ls", "a.txt\n"), updated(1, echo, "done\n"))
        assertEquals("a.txt\n\n$ $long\ndone\n", output)
        assertFalse(output.contains("command truncated"))
    }

    @Test
    fun `without an input the echo stands in, without its note`() {
        val output = rowOutput(started(null), updated(0, "ls", "a.txt\n"), updated(1, echo, "done\n"))
        assertEquals("a.txt\n\n$ ${long.take(200)}…\ndone\n", output)
    }

    @Test
    fun `the line follows the kernel's command order when a heredoc spans entries`() {
        val input = buildJsonObject { putJsonArray("commands") { add("cat > a <<'EOF'"); add("one"); add("EOF"); add(long) } }
        val output = rowOutput(started(input), updated(0, "cat > a <<'EOF'\none\nEOF", ""), updated(1, echo, "done\n"))
        assertEquals("\n$ $long\ndone\n", output)
    }
}
