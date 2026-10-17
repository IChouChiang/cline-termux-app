package dev.cline.termux.core.kernel.hub

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubFramesTest {
    @Test
    fun `command frame encodes with kind and envelope`() {
        val frame = HubFrame.Command(
            HubCommandEnvelope(
                command = HubCommands.CLIENT_REGISTER,
                requestId = "req-1",
                clientId = "android-1",
                payload = buildJsonObject { put("clientType", JsonPrimitive("android")) },
            ),
        )
        val text = HubJson.encodeToString(HubFrame.serializer(), frame)
        val obj = HubJson.parseToJsonElement(text).jsonObject
        assertEquals("command", obj["kind"]!!.jsonPrimitive.content)
        val envelope = obj["envelope"]!!.jsonObject
        assertEquals("v1", envelope["version"]!!.jsonPrimitive.content)
        assertEquals("client.register", envelope["command"]!!.jsonPrimitive.content)
        assertEquals("android", envelope["payload"]!!.jsonObject["clientType"]!!.jsonPrimitive.content)
        // Absent optionals are omitted, not sent as null.
        assertNull(envelope["sessionId"])
        assertNull(envelope["timeoutMs"])
    }

    @Test
    fun `stream subscribe frame is flat like the TypeScript union`() {
        val frame = HubFrame.StreamSubscribe(clientId = "android-1", sessionId = "s1", sinceSequence = 41)
        val text = HubJson.encodeToString(HubFrame.serializer(), frame)
        val obj = HubJson.parseToJsonElement(text).jsonObject
        assertEquals("stream.subscribe", obj["kind"]!!.jsonPrimitive.content)
        assertEquals("android-1", obj["clientId"]!!.jsonPrimitive.content)
        assertEquals("41", obj["sinceSequence"]!!.jsonPrimitive.content)
        assertNull(obj["envelope"])
    }

    @Test
    fun `reply and event frames decode with unknown fields ignored`() {
        val reply = HubJson.decodeFromString(
            HubFrame.serializer(),
            """{"kind":"reply","envelope":{"version":"v1","requestId":"r","ok":false,
               "error":{"code":"session_not_found","message":"gone","extra":1},"future":true}}""",
        )
        assertTrue(reply is HubFrame.Reply)
        reply as HubFrame.Reply
        assertEquals(false, reply.envelope.ok)
        assertEquals("session_not_found", reply.envelope.error?.code)

        val event = HubJson.decodeFromString(
            HubFrame.serializer(),
            """{"kind":"event","envelope":{"version":"v1","event":"assistant.delta","sequence":1234567890123,
               "sessionId":"s1","timestamp":1700000000000,"payload":{"text":"Hi"}}}""",
        )
        assertTrue(event is HubFrame.Event)
        event as HubFrame.Event
        assertEquals(HubEvents.ASSISTANT_DELTA, event.envelope.event)
        assertEquals(1234567890123L, event.envelope.sequence)
        assertEquals("Hi", event.envelope.payload!!["text"]!!.jsonPrimitive.content)
    }

    @Test(expected = HubProtocolException::class)
    fun `unknown kind is rejected`() {
        HubJson.decodeFromString(HubFrame.serializer(), """{"kind":"telemetry","x":1}""")
    }
}
