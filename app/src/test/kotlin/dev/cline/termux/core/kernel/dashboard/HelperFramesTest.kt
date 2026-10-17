package dev.cline.termux.core.kernel.dashboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperFramesTest {
    @Test
    fun `ready and desktopCommand frames encode the way the webview protocol expects`() {
        assertEquals("""{"type":"ready"}""", HelperFrames.encodeReady())
        assertEquals(
            """{"type":"desktopCommand","id":"req-1","command":"list_providers"}""",
            HelperFrames.encodeCommand("req-1", "list_providers", null),
        )
        val withArgs = HelperFrames.encodeCommand("req-2", "add_provider", buildJsonObject { put("provider", "anthropic"); put("apiKey", "k") })
        val parsed = HelperFrames.json.parseToJsonElement(withArgs).jsonObject
        assertEquals("desktopCommand", parsed["type"]!!.jsonPrimitive.content)
        assertEquals("anthropic", parsed["args"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decode reads a successful result`() {
        val frame = HelperFrames.decode("""{"type":"desktopCommandResult","id":"req-1","ok":true,"result":{"providers":[{"id":"anthropic"}]}}""")
        val result = frame as HelperInbound.CommandResult
        assertEquals("req-1", result.id)
        assertTrue(result.ok)
        assertNull(result.error)
        assertTrue(result.result is JsonObject)
    }

    @Test
    fun `decode reads a failed result and treats a null result as absent`() {
        val failed = HelperFrames.decode("""{"type":"desktopCommandResult","id":"req-9","ok":false,"error":"Unsupported desktop command: x","result":null}""")
        val result = failed as HelperInbound.CommandResult
        assertEquals(false, result.ok)
        assertEquals("Unsupported desktop command: x", result.error)
        assertNull(result.result)
    }

    @Test
    fun `every other frame type is ignored without parsing its body`() {
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"status","hub":{"state":"running"}}"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"provider_catalog","providers":[{"apiKey":"sk-plaintext"}]}"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"providers","items":[}"""))
        assertEquals("provider_catalog", HelperFrames.peekType("""{"type":"provider_catalog","x":1}"""))
        assertNull(HelperFrames.peekType("not json at all"))
    }

    @Test
    fun `malformed result frames are ignored rather than closing the socket`() {
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"desktopCommandResult","ok":true}"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"desktopCommandResult","id":"r","ok":"yes"}"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""{"type":"desktopCommandResult","id":"r","ok":true"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode("""["type","desktopCommandResult"]"""))
        assertEquals(HelperInbound.Ignored, HelperFrames.decode(""))
    }

    @Test
    fun `type does not have to be the first key`() {
        val frame = HelperFrames.decode("""{"id":"r","ok":true,"result":"x","type":"desktopCommandResult"}""") as HelperInbound.CommandResult
        assertEquals(JsonPrimitive("x"), frame.result)
    }
}
