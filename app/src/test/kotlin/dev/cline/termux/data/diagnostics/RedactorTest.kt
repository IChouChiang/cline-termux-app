package dev.cline.termux.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val hex64 = "0123456789abcdef".repeat(4)

    @Test
    fun `rule 1 - the desktop secret field pattern`() {
        assertEquals("\"apiKey\": \"<redacted>\"", Redactor.redact("\"apiKey\": \"sk-ant-123\""))
        assertEquals("api_key=<redacted> next", Redactor.redact("api_key=abc123 next"))
        assertEquals("\"accessToken\":\"<redacted>\"", Redactor.redact("\"accessToken\":\"eyJhbGciOi\""))
        assertEquals("client_secret: <redacted>", Redactor.redact("client_secret: s3cr3t"))
        assertEquals("PASSWORD=<redacted>", Redactor.redact("PASSWORD=hunter2"))
        // Counters that merely end in "tokens" are not secrets.
        assertEquals("\"totalTokens\": 1234", Redactor.redact("\"totalTokens\": 1234"))
    }

    @Test
    fun `rule 2 - the hub auth subprotocol`() {
        assertEquals("Sec-WebSocket-Protocol: cline-hub-auth.<redacted>", Redactor.redact("Sec-WebSocket-Protocol: cline-hub-auth.$hex64"))
        assertEquals("cline-hub-auth.<redacted>", Redactor.redact("cline-hub-auth.deadbeef"))
    }

    @Test
    fun `rule 3 - a standalone 64-hex run`() {
        assertEquals("token is <redacted> here", Redactor.redact("token is $hex64 here"))
        // 63 or 65 hex characters are not the token shape.
        val shorter = hex64.dropLast(1)
        assertEquals("id $shorter", Redactor.redact("id $shorter"))
        assertEquals("id ${hex64}a", Redactor.redact("id ${hex64}a"))
    }

    @Test
    fun `rule 4 - bearer values`() {
        assertEquals("Bearer <redacted>", Redactor.redact("Bearer abc.def.ghi"))
        val header = Redactor.redact("authorization: Bearer abc.def.ghi")
        assertFalse(header.contains("abc.def"))
    }

    @Test
    fun `rule 5 - api key flags`() {
        assertEquals("cline auth --provider x --apikey ••• --modelid m", Redactor.redact("cline auth --provider x --apikey sk-live-9 --modelid m"))
        assertEquals("cline auth -k •••", Redactor.redact("cline auth -k sk-live-9"))
    }

    @Test
    fun `rule 6 - the termux home becomes a tilde`() {
        assertEquals("~/.cline/data/logs/cline.log", Redactor.redact("/data/data/com.termux/files/home/.cline/data/logs/cline.log"))
    }

    @Test
    fun `a real-shaped production json and providers json keep no secret values`() {
        val production = """
            {
              "hubId": "hub_7f3a",
              "url": "ws://127.0.0.1:25463/hub",
              "port": 25463,
              "pid": 12345,
              "startedAt": "2026-10-03T10:00:00.000Z",
              "authToken": "$hex64"
            }
        """.trimIndent()
        val providers = """
            {"lastUsedProvider":"anthropic","providers":{"anthropic":{"settings":{"provider":"anthropic","apiKey":"sk-ant-api03-SECRET","model":"claude-sonnet"}},
             "cline":{"settings":{"auth":{"accessToken":"workos:eyJSECRET","refreshToken":"rt_SECRET","accountId":"acc_1"}}}}}
        """.trimIndent()
        val out = Redactor.redact(production + "\n" + providers)
        assertFalse(out.contains(hex64))
        assertFalse(out.contains("SECRET"))
        assertTrue(out.contains("\"hubId\": \"hub_7f3a\""))
        assertTrue(out.contains("claude-sonnet"))
        assertFalse(Regex("[0-9a-f]{64}|sk-|Bearer [^<]").containsMatchIn(out))
    }
}
