package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.hub.HubJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSessionActivityTest {
    private val reply = HubJson.parseToJsonElement(
        """{"sessions":[
            {"id":"a","status":"idle","participants":[{"clientId":"android-me"},{"clientId":"cli-1"}]},
            {"id":"b","status":"running","participants":[{"clientId":"android-me"}]},
            {"id":"c","status":"idle","participants":[{"clientId":"cli-1"},{"clientId":"desktop-2"},{"clientId":" "}]},
            {"id":"d","status":"idle","participants":[]},
            {"id":"e","status":"idle"}
        ]}""",
    ).jsonObject

    @Test
    fun `sessions with another participant count, own participation does not`() {
        val activity = HubSessionActivity.summarizeOtherClients(reply, "android-me")
        assertEquals(HubSessionActivity(activeSessionCount = 2, participantClientCount = 2), activity)
        assertEquals(HubSessionActivity.NONE, HubSessionActivity.summarizeOtherClients(HubJson.parseToJsonElement("{}").jsonObject, "x"))
        assertTrue(HubSessionActivity.NONE.empty)
    }

    @Test
    fun `describe matches upstream describeOutdatedHubSessions`() {
        assertEquals("1 active session from 2 connected Cline clients", HubSessionActivity.describe(HubSessionActivity(1, 2)))
        assertEquals("3 active sessions from 1 connected Cline client", HubSessionActivity.describe(HubSessionActivity(3, 1)))
        assertEquals("2 active sessions", HubSessionActivity.describe(HubSessionActivity(2, 0)))
        assertEquals("active sessions from other Cline clients", HubSessionActivity.describe(HubSessionActivity.NONE))
        assertEquals("active sessions from other Cline clients", HubSessionActivity.describe(HubSessionActivity.UNKNOWN))
    }
}
