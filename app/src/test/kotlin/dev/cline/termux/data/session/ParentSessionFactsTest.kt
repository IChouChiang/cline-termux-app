package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** HC §6.4: the subagent back chip keys off the parent the Hub records for a child session. */
class ParentSessionFactsTest {
    private fun record(json: String) = HubJson.parseToJsonElement(json) as JsonObject

    @Test
    fun `a subagent's parent is read from metadata, as the Hub writes it`() {
        // Shape recorded on the dev Hub (spawn_agent child): the parent sits only in metadata.
        val child = record("""{"sessionId":"c1","status":"completed","metadata":{"sessionHistoryOrigin":{"mode":"subagent"},"parentSessionId":"p1","parentAgentId":"agent_a"}}""")
        assertEquals("p1", SessionFactsReducer.fromRecord("c1", child, null).parentSessionId)
    }

    @Test
    fun `a top-level parentSessionId still counts`() {
        val child = record("""{"sessionId":"c1","parentSessionId":"p2","metadata":{}}""")
        assertEquals("p2", SessionFactsReducer.fromRecord("c1", child, null).parentSessionId)
    }
}
