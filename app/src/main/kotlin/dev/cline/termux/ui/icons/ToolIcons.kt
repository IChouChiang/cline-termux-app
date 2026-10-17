package dev.cline.termux.ui.icons

/**
 * Tool-row icon selection, ported 1:1 from the desktop.
 *
 * Sources in the Cline monorepo:
 * - `sdk/packages/ui/components/agent-chat/tool-summary/index.ts`
 *   (`TOOL_NAME_ALIASES`, `normalizeToolName`, `classifyTool`, `ToolKind`)
 * - `apps/examples/desktop-app/webview/components/views/chat/messages/tool-icons.ts`
 *   (`TOOL_NAME_ICONS`, `TOOL_KIND_ICONS`, `getToolNameIcon`)
 *
 * The desktop returns Lucide components; here the result is a [ToolSymbol], the Material
 * Symbol the design system maps each kind to (design-system.md section 7.2). This file is
 * pure Kotlin so the mapping can be unit-tested without Android; `ClineIcons` turns a
 * [ToolSymbol] into a drawable.
 */
enum class ToolKind {
    Read,
    Edit,
    Command,
    Search,
    Web,
    Spawn,
    Team,
    Skill,
    Mcp,
    Question,
    Other,
}

/** Material Symbols used in tool rows. [symbolName] is the `ms_<name>` drawable suffix. */
enum class ToolSymbol(val symbolName: String) {
    FileCopy("file_copy"),
    Edit("edit"),
    Terminal("terminal"),
    ManageSearch("manage_search"),
    Web("web"),
    Person("person"),
    Group("group"),
    LibraryBooks("library_books"),
    DeployedCode("deployed_code"),
    ContactSupport("contact_support"),
    Build("build"),
    Extension("extension"),
    ExitToApp("exit_to_app"),
}

/** `TOOL_NAME_ALIASES` from tool-summary/index.ts. */
val TOOL_NAME_ALIASES: Map<String, String> = mapOf(
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

/** `normalizeToolName`: lower-case, then apply the alias table. */
fun normalizeToolName(toolName: String): String {
    val normalized = toolName.lowercase()
    return TOOL_NAME_ALIASES[normalized] ?: normalized
}

/** `classifyTool`, including the fuzzy fallbacks for runtimes with their own tool names. */
fun classifyTool(toolName: String): ToolKind {
    val name = normalizeToolName(toolName)
    if (name == "read_files") return ToolKind.Read
    if (name == "editor" || name == "apply_patch") return ToolKind.Edit
    if (name == "run_commands") return ToolKind.Command
    if (name == "search_codebase") return ToolKind.Search
    if (name == "fetch_web_content") return ToolKind.Web
    if (name == "spawn_agent" || name.startsWith("subagent_")) return ToolKind.Spawn
    if (name == "team" || name == "teams" || name.startsWith("team_")) return ToolKind.Team
    if (name == "skills" || name == "skill") return ToolKind.Skill
    if (name == "ask_question" || name == "ask_followup_question") return ToolKind.Question
    if (name == "mcp" || name.startsWith("mcp_") || name.startsWith("mcp-")) return ToolKind.Mcp
    // Fuzzy fallbacks for runtimes with their own tool names.
    if ("command" in name || "terminal" in name) return ToolKind.Command
    if ("read" in name) return ToolKind.Read
    if ("edit" in name || "write" in name) return ToolKind.Edit
    if ("search" in name || "grep" in name) return ToolKind.Search
    if ("browser" in name || "fetch" in name || "web" in name) return ToolKind.Web
    if ("mcp" in name) return ToolKind.Mcp
    return ToolKind.Other
}

/** `TOOL_NAME_ICONS`: overrides for tools whose kind-based icon would be misleading. */
val TOOL_NAME_ICONS: Map<String, ToolSymbol> = mapOf(
    "plugins" to ToolSymbol.Extension,
    "submit_and_exit" to ToolSymbol.ExitToApp,
)

/** `TOOL_KIND_ICONS` with the Material Symbols from design-system.md section 7.2. */
val TOOL_KIND_ICONS: Map<ToolKind, ToolSymbol> = mapOf(
    ToolKind.Command to ToolSymbol.Terminal,
    ToolKind.Edit to ToolSymbol.Edit,
    ToolKind.Mcp to ToolSymbol.DeployedCode,
    ToolKind.Other to ToolSymbol.Build,
    ToolKind.Question to ToolSymbol.ContactSupport,
    ToolKind.Read to ToolSymbol.FileCopy,
    ToolKind.Search to ToolSymbol.ManageSearch,
    ToolKind.Skill to ToolSymbol.LibraryBooks,
    ToolKind.Spawn to ToolSymbol.Person,
    ToolKind.Team to ToolSymbol.Group,
    ToolKind.Web to ToolSymbol.Web,
)

/** `getToolNameIcon`: name override first, otherwise the icon of the classified kind. */
fun getToolNameIcon(toolName: String): ToolSymbol {
    val normalized = normalizeToolName(toolName)
    return TOOL_NAME_ICONS[normalized] ?: TOOL_KIND_ICONS.getValue(classifyTool(normalized))
}
