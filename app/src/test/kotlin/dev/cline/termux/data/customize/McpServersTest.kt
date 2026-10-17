package dev.cline.termux.data.customize

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServersTest {
    @Test
    fun `the settings file is read transport-first like readMcpServersResponse`() {
        val text = """
            {"mcpServers":{
              "github":{"transport":{"type":"stdio","command":"npx","args":["-y","gh"],"env":{"GITHUB_TOKEN":"ghp_x"}},"disabled":true},
              "legacy":{"command":"uvx","args":["srv"],"cwd":"/w"},
              "remote":{"type":"sse","url":"https://x/sse","headers":{"Authorization":"Bearer t"}},
              "bad":"nope"}}
        """.trimIndent()
        val response = McpServersResponse.fromSettingsFile(text, "/settings.json")
        assertTrue(response.hasSettingsFile)
        assertEquals(McpSource.File, response.source)
        assertEquals(listOf("github", "legacy", "remote"), response.sorted.map { it.name })
        val github = response.find("github")!!
        assertTrue(github.disabled)
        assertEquals(listOf("-y", "gh"), github.args)
        assertEquals("ghp_x", github.env!!["GITHUB_TOKEN"])
        assertTrue(github.hasEnvOrCwd)
        assertFalse(github.toString().contains("ghp_x"))
        val legacy = response.find("legacy")!!
        assertEquals("uvx", legacy.command)
        assertEquals(McpTransportType.Stdio, legacy.transportType)
        val remote = response.find("remote")!!
        assertEquals(McpTransportType.Sse, remote.transportType)
        assertEquals("https://x/sse", remote.url)
    }

    @Test
    fun `a missing file has no servers and no file`() {
        val response = McpServersResponse.fromSettingsFile(null, "/settings.json")
        assertFalse(response.hasSettingsFile)
        assertTrue(response.servers.isEmpty())
    }

    @Test
    fun `the helper reply parses`() {
        val reply = CustomizeJson.parseToJsonElement(
            """{"settingsPath":"/s.json","hasSettingsFile":true,"servers":[{"name":"a","transportType":"streamableHttp","disabled":false,"url":"https://a"}]}""",
        )
        val response = McpServersResponse.fromHelper(reply)
        assertEquals(McpSource.Helper, response.source)
        assertEquals(McpTransportType.StreamableHttp, response.servers.single().transportType)
        assertEquals("/s.json", response.settingsPath)
    }

    @Test
    fun `splitCsv and parseKeyValuePairs port the desktop`() {
        assertEquals(listOf("a", "b c"), McpInputs.splitCsv(" a , ,b c,"))
        assertEquals(mapOf("A" to "1", "B" to "x=y"), McpInputs.parseKeyValuePairs("A=1, =skip, novalue, B = x=y"))
        assertNull(McpInputs.parseKeyValuePairs(" , "))
        assertEquals("A=1, B=2", McpInputs.stringifyKeyValuePairs(mapOf("A" to "1", "B" to "2")))
        assertEquals("A=[REDACTED]", McpInputs.stringifyRedactedKeyValuePairs(mapOf("A" to "secret")))
    }

    @Test
    fun `buildServerInput validates and shapes stdio and remote input`() {
        val missingName = McpInputs.buildServerInput(McpServerForm(name = " ", previousName = "", transportType = McpTransportType.Stdio))
        assertEquals(McpInputError.NameRequired, (missingName.exceptionOrNull() as McpInputException).error)
        val missingCommand = McpInputs.buildServerInput(McpServerForm(name = "a", previousName = "", transportType = McpTransportType.Stdio))
        assertEquals("Command is required for local servers.", missingCommand.exceptionOrNull()!!.message)
        val missingUrl = McpInputs.buildServerInput(McpServerForm(name = "a", previousName = "", transportType = McpTransportType.Sse))
        assertEquals(McpInputError.UrlRequired, (missingUrl.exceptionOrNull() as McpInputException).error)

        val stdio = McpInputs.buildServerInput(
            McpServerForm(
                name = " gh ", previousName = "old", transportType = McpTransportType.Stdio, command = " npx ", argsText = "-y, gh",
                env = listOf("TOKEN" to "v", " " to "dropped"), enabled = false,
            ),
        ).getOrThrow()
        assertTrue(stdio.isRename)
        val json = stdio.toJson()
        assertEquals("gh", json["name"]!!.jsonPrimitive.content)
        assertEquals("old", json["previousName"]!!.jsonPrimitive.content)
        assertEquals("stdio", json["transportType"]!!.jsonPrimitive.content)
        assertEquals("npx", json["command"]!!.jsonPrimitive.content)
        assertEquals("v", json["env"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content)
        assertEquals("true", json["disabled"]!!.jsonPrimitive.content)
        assertFalse(stdio.toString().contains("\"v\""))

        val remote = McpInputs.buildServerInput(
            McpServerForm(name = "r", previousName = "r", transportType = McpTransportType.StreamableHttp, url = "https://r", headersText = "X-Key=1"),
        ).getOrThrow()
        assertFalse(remote.isRename)
        assertEquals(mapOf("X-Key" to "1"), remote.headers)
        assertNull(remote.toJson()["command"])
    }
}
