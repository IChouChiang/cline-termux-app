package dev.cline.termux.ui.customize

import dev.cline.termux.data.customize.McpServer
import dev.cline.termux.data.customize.McpTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizeRulesTest {
    private fun tool(name: String, enabled: Boolean, description: String? = null, headless: List<String> = emptyList()) = ToolCard(
        key = CustomizeRules.builtinKey(name),
        name = name,
        description = description,
        enabled = enabled,
        pluginName = null,
        path = null,
        headlessNames = headless,
        toggleNames = listOf(name),
        builtinId = name,
    )

    @Test
    fun `previewText collapses whitespace and cuts at 150 with an ellipsis`() {
        assertEquals("a b c", CustomizeRules.previewText("  a\n\n b\t c "))
        assertEquals("", CustomizeRules.previewText(null))
        val long = "x".repeat(149) + " yyyy"
        assertEquals("x".repeat(149) + "...", CustomizeRules.previewText(long))
    }

    @Test
    fun `previewText never cuts a surrogate pair on the boundary`() {
        val grin = "😀"
        // The emoji occupies units 149-150, so the 150-unit cut backs off to 149.
        assertEquals("x".repeat(149) + "...", CustomizeRules.previewText("x".repeat(149) + grin + "more"))
        assertEquals("x".repeat(148) + grin + "...", CustomizeRules.previewText("x".repeat(148) + grin + "more"))
        val emoji = CustomizeRules.previewText(grin.repeat(76) + "é")
        assertEquals(grin.repeat(75) + "...", emoji)
    }

    @Test
    fun `tool search covers description and headless names, case-insensitively`() {
        val tools = listOf(tool("read_files", true, "Read files", listOf("read_file")), tool("bash", false, "Run shell"))
        assertEquals(listOf("read_files"), CustomizeRules.filterTools(tools, "READ_FILE").map { it.name })
        assertEquals(listOf("bash"), CustomizeRules.filterTools(tools, " shell ").map { it.name })
        assertEquals(2, CustomizeRules.filterTools(tools, "  ").size)
    }

    @Test
    fun `bulk toggles follow the desktop targets and label`() {
        val tools = listOf(tool("a", true), tool("b", false))
        assertFalse(CustomizeRules.allEnabled(tools))
        assertEquals(listOf("b"), CustomizeRules.bulkTargets(tools, enable = true).map { it.name })
        assertEquals(listOf("a", "b"), CustomizeRules.bulkTargets(tools, enable = false).map { it.name })
        assertTrue(CustomizeRules.allEnabled(listOf(tool("a", true))))
        assertFalse("an empty section is never all-enabled", CustomizeRules.allEnabled(emptyList()))
    }

    @Test
    fun `mcp cards carry env keys only and redact secret-looking headers`() {
        val server = McpServer(
            name = "gh",
            transportType = McpTransportType.StreamableHttp,
            transportLabel = "streamableHttp",
            disabled = true,
            url = "https://x.example/mcp",
            env = mapOf("GITHUB_TOKEN" to "ghp_secret"),
            headers = mapOf("Authorization" to "Bearer abc", "X-Region" to "eu"),
        )
        val card = CustomizeRules.mcpCard(server, catalog = null)
        assertFalse(card.enabled)
        assertEquals(listOf("GITHUB_TOKEN"), card.envKeys)
        assertEquals("GITHUB_TOKEN=[REDACTED]", card.envLine)
        assertEquals("Authorization=[REDACTED], X-Region=eu", card.headersLine)
        assertEquals("Authorization, X-Region", card.headersSpoken)
        assertFalse("ghp_secret" in card.toString())
        assertFalse("Bearer abc" in card.toString())
        assertFalse(card.noLaunchCommand)
    }

    @Test
    fun `secret-looking keys and mcp search`() {
        listOf("API_KEY", "x-auth", "Cookie", "SESSION_ID", "password").forEach { assertTrue(it, CustomizeRules.isSecretLookingKey(it)) }
        listOf("X-Region", "Accept", "PATH").forEach { assertFalse(it, CustomizeRules.isSecretLookingKey(it)) }
        val card = CustomizeRules.mcpCard(McpServer("fs", McpTransportType.Stdio, "stdio", disabled = false, command = "npx"), null)
        assertTrue(CustomizeRules.mcpMatches(card, "NPX"))
        assertFalse(CustomizeRules.mcpMatches(card, "http"))
        assertNull(card.envLine)
    }

    @Test
    fun `tab parsing and the chip basename`() {
        assertEquals(CustomizeTab.Mcp, CustomizeTab.parse(" MCP "))
        assertNull(CustomizeTab.parse("nope"))
        assertEquals("app", CustomizeRules.basename("/data/home/app/"))
        assertEquals("/", CustomizeRules.basename("/"))
    }
}
