package dev.cline.termux.data.customize

import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsString
import java.text.Collator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The Hub's settings shapes (`sdk/packages/core/src/settings/types.ts`) and the pure list
 * rules of `extensions-view.tsx` (customize-and-rest.md §1.8): scope, ordering, counts and the
 * marketplace match values. Parsing is lenient: a missing or mistyped field is dropped, never
 * thrown.
 */

/** `CoreSettingsType`: the `type` of `settings.toggle` and of `settings.changed.types`. */
enum class SettingsType(val wire: String) {
    Skills("skills"), Workflows("workflows"), Rules("rules"), Plugins("plugins"), Tools("tools"), Mcp("mcp");

    companion object {
        fun parse(value: String?): SettingsType? = entries.firstOrNull { it.wire == value }
    }
}

/** `CoreSettingsItemKind`. */
enum class SettingsKind(val wire: String, val type: SettingsType) {
    Skill("skill", SettingsType.Skills),
    Workflow("workflow", SettingsType.Workflows),
    Rule("rule", SettingsType.Rules),
    Plugin("plugin", SettingsType.Plugins),
    Tool("tool", SettingsType.Tools),
    Mcp("mcp", SettingsType.Mcp);

    companion object {
        fun parse(value: String?): SettingsKind? = entries.firstOrNull { it.wire == value }
    }
}

/** `CorePluginContributions`. */
data class PluginContributions(
    val inspectionStatus: String,
    val capabilities: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val hooks: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
)

/** `CoreSettingsItem`. [source] is the wire value (`global`, `workspace`, `builtin`, `global-plugin`, `workspace-plugin`). */
data class SettingsItem(
    val id: String,
    val name: String,
    val path: String,
    val kind: SettingsKind,
    val source: String,
    val enabled: Boolean? = null,
    val description: String? = null,
    val toggleable: Boolean? = null,
    val pluginName: String? = null,
    val pluginPath: String? = null,
    val agentPlugin: Boolean = false,
    val loadError: String? = null,
    val contributions: PluginContributions? = null,
) {
    /** Desktop: a missing `enabled` reads as on. */
    val isEnabled: Boolean get() = enabled != false
}

/** `CoreSettingsSnapshot`. */
data class SettingsSnapshot(
    val workflows: List<SettingsItem> = emptyList(),
    val rules: List<SettingsItem> = emptyList(),
    val plugins: List<SettingsItem> = emptyList(),
    val skills: List<SettingsItem> = emptyList(),
    val tools: List<SettingsItem> = emptyList(),
    val mcp: List<SettingsItem> = emptyList(),
) {
    fun of(type: SettingsType): List<SettingsItem> = when (type) {
        SettingsType.Skills -> skills
        SettingsType.Workflows -> workflows
        SettingsType.Rules -> rules
        SettingsType.Plugins -> plugins
        SettingsType.Tools -> tools
        SettingsType.Mcp -> mcp
    }

    companion object {
        val EMPTY = SettingsSnapshot()

        /** `{snapshot: CoreSettingsSnapshot}` (settings.list / settings.toggle reply, settings.changed payload). */
        fun fromReply(reply: JsonObject?): SettingsSnapshot? = (reply?.get("snapshot") as? JsonObject)?.let(::parse)

        fun parse(snapshot: JsonObject): SettingsSnapshot = SettingsSnapshot(
            workflows = items(snapshot["workflows"]),
            rules = items(snapshot["rules"]),
            plugins = items(snapshot["plugins"]),
            skills = items(snapshot["skills"]),
            tools = items(snapshot["tools"]),
            mcp = items(snapshot["mcp"]),
        )

        private fun items(element: JsonElement?): List<SettingsItem> =
            (element as? JsonArray).orEmpty().mapNotNull { parseItem(it as? JsonObject ?: return@mapNotNull null) }

        fun parseItem(o: JsonObject): SettingsItem? {
            val kind = SettingsKind.parse(o["kind"].jsString()) ?: return null
            val id = o["id"].jsString() ?: return null
            return SettingsItem(
                id = id,
                name = o["name"].jsString() ?: id,
                path = o["path"].jsString().orEmpty(),
                kind = kind,
                source = o["source"].jsString().orEmpty(),
                enabled = o["enabled"].jsBoolean(),
                description = o["description"].jsString(),
                toggleable = o["toggleable"].jsBoolean(),
                pluginName = o["pluginName"].jsString(),
                pluginPath = o["pluginPath"].jsString(),
                agentPlugin = o["agentPlugin"].jsBoolean() == true,
                loadError = o["loadError"].jsString(),
                contributions = (o["contributions"] as? JsonObject)?.let { c ->
                    PluginContributions(
                        inspectionStatus = c["inspectionStatus"].jsString() ?: "available",
                        capabilities = strings(c["capabilities"]),
                        tools = strings(c["tools"]),
                        skills = strings(c["skills"]),
                        rules = strings(c["rules"]),
                        hooks = strings(c["hooks"]),
                        commands = strings(c["commands"]),
                        mcpServers = strings(c["mcpServers"]),
                        providers = strings(c["providers"]),
                    )
                },
            )
        }

        internal fun strings(element: JsonElement?): List<String> =
            (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}

/** A built-in tool from `cline config tools --json` (entries without `type:"plugin"`). */
data class BuiltinTool(
    val id: String,
    val description: String?,
    val enabled: Boolean,
    val headlessToolNames: List<String>,
) {
    /** `distinct([tool.id] + tool.headlessToolNames)`: the names one toggle sends (CR §1.8). */
    val toggleNames: List<String> get() = (listOf(id) + headlessToolNames).distinct()
}

/** A hook file from `cline config hooks --json`. */
data class HookFile(val fileName: String, val hookEventName: String?, val path: String)

object CliCatalogs {
    /** The `tasks` card the CLI-scoped catalog lacks (CR §1.8; `BASE_TOOL_CATALOG` description). */
    const val TASKS_TOOL_ID = "tasks"
    const val TASKS_TOOL_DESCRIPTION = "Create and manage explicitly requested one-time and recurring agent schedules."

    /**
     * Built-in entries of `cline config tools --json` (`enabled = defaultEnabled`), plus the
     * `tasks` card when absent; [tasksEnabled] comes from `global-settings.json` `disabledTools`.
     */
    fun parseBuiltinTools(stdout: String, tasksEnabled: Boolean): List<BuiltinTool> {
        val array = parseArray(stdout)
        val builtins = array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            if (o["type"].jsString() == "plugin") return@mapNotNull null
            val id = o["id"].jsString() ?: return@mapNotNull null
            BuiltinTool(
                id = id,
                description = o["description"].jsString(),
                enabled = o["defaultEnabled"].jsBoolean() != false,
                headlessToolNames = SettingsSnapshot.strings(o["headlessToolNames"]),
            )
        }
        if (builtins.any { it.id == TASKS_TOOL_ID }) return builtins
        return builtins + BuiltinTool(TASKS_TOOL_ID, TASKS_TOOL_DESCRIPTION, tasksEnabled, listOf(TASKS_TOOL_ID))
    }

    fun parseHooks(stdout: String): List<HookFile> = parseArray(stdout).mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val path = o["path"].jsString() ?: return@mapNotNull null
        HookFile(fileName = o["fileName"].jsString() ?: path.substringAfterLast('/'), hookEventName = o["hookEventName"].jsString(), path = path)
    }

    /** The CLI prints the JSON array, possibly after log lines; the array starts at the first `[` line. */
    private fun parseArray(stdout: String): JsonArray {
        val trimmed = stdout.trim()
        val start = trimmed.indexOf('[')
        if (start < 0) throw IllegalArgumentException("no JSON array in CLI output")
        return CustomizeJson.parseToJsonElement(trimmed.substring(start)) as? JsonArray
            ?: throw IllegalArgumentException("CLI output is not a JSON array")
    }
}

/** Global or Project (desktop scope badge). */
enum class Scope { Global, Project }

/** The scope rules of `extensions-view.tsx` (CR §1.8). [root] is `normalizeRoot(ws)`. */
object ScopeRules {
    /** Trailing slashes removed, backslashes normalised (desktop `normalizePath`). */
    fun normalizeRoot(path: String?): String = path.orEmpty().trim().replace('\\', '/').trimEnd('/')

    private fun isUnder(path: String, base: String): Boolean {
        if (base.isEmpty()) return false
        val p = normalizeRoot(path)
        return p == base || p.startsWith("$base/")
    }

    fun rule(path: String, root: String): Scope =
        if (isUnder(path, "$root/.clinerules") || isUnder(path, "$root/.cline/rules")) Scope.Project else Scope.Global

    fun hook(path: String, root: String): Scope =
        if (isUnder(path, root) && normalizeRoot(path).contains("/.clinerules/hooks")) Scope.Project else Scope.Global

    fun plugin(item: SettingsItem, root: String): Scope {
        if (item.source == "workspace-plugin") return Scope.Project
        val p = normalizeRoot(item.path)
        return if (isUnder(p, root) && (p.contains("/.cline/plugins") || p.contains("/.agents/plugins"))) Scope.Project else Scope.Global
    }

    /** Skills and workflows. */
    fun command(path: String, root: String): Scope = if (isUnder(path, root)) Scope.Project else Scope.Global

    /** Global first, then Project, keeping the order inside each group (desktop `scopedRules` …). */
    fun <T> globalFirst(items: List<T>, scope: (T) -> Scope): List<T> =
        items.filter { scope(it) == Scope.Global } + items.filter { scope(it) == Scope.Project }
}

/** List ordering (CR §1.8): `localeCompare` → [Collator]. */
object CustomizeOrdering {
    private val collator: Collator get() = Collator.getInstance()

    /** Skills and workflows merged and sorted by name. */
    fun commands(snapshot: SettingsSnapshot): List<SettingsItem> =
        (snapshot.skills + snapshot.workflows).sortedWith { a, b -> collator.compare(a.name, b.name) }

    fun byName(items: List<SettingsItem>): List<SettingsItem> = items.sortedWith { a, b -> collator.compare(a.name, b.name) }

    fun builtinTools(tools: List<BuiltinTool>): List<BuiltinTool> = tools.sortedWith { a, b -> collator.compare(a.id, b.id) }

    /** MCP: case-insensitive by name. */
    fun mcpNames(names: List<String>): List<String> = names.sortedBy { it.lowercase() }
}

/** The Customize tabs' counts (desktop `refreshCounts`). */
data class CustomizeCounts(val tools: Int, val plugins: Int, val skills: Int, val rules: Int, val mcp: Int, val hooks: Int)

fun customizeCounts(snapshot: SettingsSnapshot?, builtinTools: List<BuiltinTool>, hooks: List<HookFile>, mcpServers: Int? = null): CustomizeCounts =
    CustomizeCounts(
        tools = builtinTools.size + (snapshot?.tools?.size ?: 0),
        plugins = snapshot?.plugins?.size ?: 0,
        skills = (snapshot?.skills?.size ?: 0) + (snapshot?.workflows?.size ?: 0),
        rules = snapshot?.rules?.size ?: 0,
        mcp = mcpServers ?: snapshot?.mcp?.size ?: 0,
        hooks = hooks.size,
    )

/** Desktop `getLocalMarketplaceMatchValues`: values, path segments (extension stripped) and adjacent segment pairs. */
fun localMarketplaceMatchValues(vararg values: String?): List<String> {
    val out = LinkedHashSet<String>()
    for (value in values) {
        val normalized = value?.trim()?.replace('\\', '/')
        if (normalized.isNullOrEmpty()) continue
        out += normalized
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        for (segment in segments) out += segment.replace(EXTENSION, "")
        for (index in 0 until segments.size - 1) out += "${segments[index]}/${segments[index + 1]}"
    }
    return out.toList()
}

private val EXTENSION = Regex("\\.[^.]+$")

/** Desktop `normalizeMatchValue`. */
fun normalizeMatchValue(value: String): String = value.trim().lowercase()

/** Lenient JSON for CLI output, files and replies read by the tier-4 repositories. */
val CustomizeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
