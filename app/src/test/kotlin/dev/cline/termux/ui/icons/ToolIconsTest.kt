package dev.cline.termux.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the alias table and the classification branches of the desktop's
 * `tool-summary/index.ts` and `tool-icons.ts`.
 */
class ToolIconsTest {

    @Test
    fun `normalizeToolName lower-cases and applies every alias`() {
        val aliases = mapOf(
            "apply-patch" to "apply_patch",
            "bash" to "run_commands",
            "edit" to "editor",
            "edit_file" to "editor",
            "file-read" to "read_files",
            "file_read" to "read_files",
            "read_file" to "read_files",
            "search" to "search_codebase",
            "spawn-agent" to "spawn_agent",
            "spawn_agent_tool" to "spawn_agent",
            "web-fetch" to "fetch_web_content",
            "web_fetch" to "fetch_web_content",
        )
        assertEquals(aliases, TOOL_NAME_ALIASES)
        for ((alias, canonical) in aliases) {
            assertEquals(alias, canonical, normalizeToolName(alias))
            assertEquals(alias.uppercase(), canonical, normalizeToolName(alias.uppercase()))
        }
        assertEquals("read_files", normalizeToolName("Read_File"))
        assertEquals("run_commands", normalizeToolName("BASH"))
        assertEquals("editor", normalizeToolName("editor"))
        assertEquals("some_unknown_tool", normalizeToolName("Some_Unknown_Tool"))
    }

    @Test
    fun `classifyTool recognises the canonical names and their aliases`() {
        val expected = mapOf(
            "read_files" to ToolKind.Read,
            "read_file" to ToolKind.Read,
            "file-read" to ToolKind.Read,
            "editor" to ToolKind.Edit,
            "edit" to ToolKind.Edit,
            "edit_file" to ToolKind.Edit,
            "apply_patch" to ToolKind.Edit,
            "apply-patch" to ToolKind.Edit,
            "run_commands" to ToolKind.Command,
            "bash" to ToolKind.Command,
            "search_codebase" to ToolKind.Search,
            "search" to ToolKind.Search,
            "fetch_web_content" to ToolKind.Web,
            "web_fetch" to ToolKind.Web,
            "web-fetch" to ToolKind.Web,
            "spawn_agent" to ToolKind.Spawn,
            "spawn-agent" to ToolKind.Spawn,
            "spawn_agent_tool" to ToolKind.Spawn,
            "subagent_result" to ToolKind.Spawn,
            "team" to ToolKind.Team,
            "teams" to ToolKind.Team,
            "team_create" to ToolKind.Team,
            "skills" to ToolKind.Skill,
            "skill" to ToolKind.Skill,
            "ask_question" to ToolKind.Question,
            "ask_followup_question" to ToolKind.Question,
            "mcp" to ToolKind.Mcp,
            "mcp_github_search" to ToolKind.Mcp,
            "mcp-github-search" to ToolKind.Mcp,
        )
        for ((name, kind) in expected) {
            assertEquals(name, kind, classifyTool(name))
            assertEquals(name.uppercase(), kind, classifyTool(name.uppercase()))
        }
    }

    @Test
    fun `classifyTool fuzzy fallbacks follow the desktop's order`() {
        val expected = mapOf(
            "execute_command" to ToolKind.Command,
            "open_terminal" to ToolKind.Command,
            "read_document" to ToolKind.Read,
            "write_to_file" to ToolKind.Edit,
            "str_replace_editor" to ToolKind.Edit,
            "grep_tool" to ToolKind.Search,
            "codebase_search_v2" to ToolKind.Search,
            "browser_action" to ToolKind.Web,
            "fetch_url" to ToolKind.Web,
            "web_search" to ToolKind.Search,
            "use_mcp_tool" to ToolKind.Mcp,
            "plugins" to ToolKind.Other,
            "submit_and_exit" to ToolKind.Other,
            "attempt_completion" to ToolKind.Other,
            "" to ToolKind.Other,
        )
        for ((name, kind) in expected) {
            assertEquals(name, kind, classifyTool(name))
        }
        // "command"/"terminal" win over "read", "read" wins over "edit", and so on.
        assertEquals(ToolKind.Command, classifyTool("read_command_output"))
        assertEquals(ToolKind.Read, classifyTool("read_and_edit"))
        assertEquals(ToolKind.Edit, classifyTool("edit_search"))
        assertEquals(ToolKind.Search, classifyTool("search_web"))
        assertEquals(ToolKind.Web, classifyTool("web_mcp"))
    }

    @Test
    fun `kind icons match design-system section 7_2`() {
        assertEquals(ToolKind.entries.toSet(), TOOL_KIND_ICONS.keys)
        assertEquals(ToolSymbol.FileCopy, TOOL_KIND_ICONS[ToolKind.Read])
        assertEquals(ToolSymbol.Edit, TOOL_KIND_ICONS[ToolKind.Edit])
        assertEquals(ToolSymbol.Terminal, TOOL_KIND_ICONS[ToolKind.Command])
        assertEquals(ToolSymbol.ManageSearch, TOOL_KIND_ICONS[ToolKind.Search])
        assertEquals(ToolSymbol.Web, TOOL_KIND_ICONS[ToolKind.Web])
        assertEquals(ToolSymbol.Person, TOOL_KIND_ICONS[ToolKind.Spawn])
        assertEquals(ToolSymbol.Group, TOOL_KIND_ICONS[ToolKind.Team])
        assertEquals(ToolSymbol.LibraryBooks, TOOL_KIND_ICONS[ToolKind.Skill])
        assertEquals(ToolSymbol.DeployedCode, TOOL_KIND_ICONS[ToolKind.Mcp])
        assertEquals(ToolSymbol.ContactSupport, TOOL_KIND_ICONS[ToolKind.Question])
        assertEquals(ToolSymbol.Build, TOOL_KIND_ICONS[ToolKind.Other])
        assertEquals(
            mapOf("plugins" to ToolSymbol.Extension, "submit_and_exit" to ToolSymbol.ExitToApp),
            TOOL_NAME_ICONS,
        )
    }

    @Test
    fun `getToolNameIcon prefers name overrides and otherwise uses the kind`() {
        assertEquals(ToolSymbol.Extension, getToolNameIcon("plugins"))
        assertEquals(ToolSymbol.Extension, getToolNameIcon("Plugins"))
        assertEquals(ToolSymbol.ExitToApp, getToolNameIcon("submit_and_exit"))
        assertEquals(ToolSymbol.FileCopy, getToolNameIcon("read_file"))
        assertEquals(ToolSymbol.Edit, getToolNameIcon("edit"))
        assertEquals(ToolSymbol.Edit, getToolNameIcon("apply-patch"))
        assertEquals(ToolSymbol.Terminal, getToolNameIcon("bash"))
        assertEquals(ToolSymbol.ManageSearch, getToolNameIcon("search"))
        assertEquals(ToolSymbol.Web, getToolNameIcon("web_fetch"))
        assertEquals(ToolSymbol.Person, getToolNameIcon("spawn-agent"))
        assertEquals(ToolSymbol.Group, getToolNameIcon("team_send_message"))
        assertEquals(ToolSymbol.LibraryBooks, getToolNameIcon("skills"))
        assertEquals(ToolSymbol.DeployedCode, getToolNameIcon("mcp_fetch"))
        assertEquals(ToolSymbol.ContactSupport, getToolNameIcon("ask_question"))
        assertEquals(ToolSymbol.Build, getToolNameIcon("attempt_completion"))
    }

    @Test
    fun `every tool symbol names an ms_ drawable`() {
        for (symbol in ToolSymbol.entries) {
            val name = symbol.symbolName
            assertTrue("$name is not a resource name", name.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }
}
