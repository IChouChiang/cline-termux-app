package dev.cline.termux.ui.customize

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.customize.BuiltinTool
import dev.cline.termux.data.customize.CustomizeOrdering
import dev.cline.termux.data.customize.HookFile
import dev.cline.termux.data.customize.McpServer
import dev.cline.termux.data.customize.McpServersResponse
import dev.cline.termux.data.customize.McpSource
import dev.cline.termux.data.customize.McpTransportType
import dev.cline.termux.data.customize.Scope
import dev.cline.termux.data.customize.ScopeRules
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsKind
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.customize.localMarketplaceMatchValues
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceType

/*
 * The Customize screen's UI models and the pure rules of `extensions-view.tsx` /
 * `mcp-view.tsx` that turn repository data into cards (customize-and-rest.md §1.5, §1.6,
 * §1.8): card projection, scope, ordering, search matching, bulk toggles, preview text and
 * the MCP detail lines with their redaction. Everything here is plain Kotlin and unit-tested;
 * the composables only render it.
 */

/** The six tabs, in the desktop order (CR §1.2). [wire] is the `customize?tab=` value. */
enum class CustomizeTab(val wire: String) {
    Tools("tools"), Plugins("plugins"), Skills("skills"), Rules("rules"), Mcp("mcp"), Hooks("hooks");

    /** The marketplace `type` the app-bar action presets from this tab (CR §1.7); null opens it unfiltered. */
    val marketplaceType: String?
        get() = when (this) {
            Plugins -> MarketplaceType.Plugin.wire
            Skills -> MarketplaceType.Skill.wire
            Mcp -> MarketplaceType.Mcp.wire
            else -> null
        }

    /** Rules and Hooks have no search field (CR §1.5). */
    val searchable: Boolean get() = this != Rules && this != Hooks

    companion object {
        fun parse(value: String?): CustomizeTab? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** A tool card: a built-in from the CLI catalog or a plugin tool from `settings.list`. */
@Immutable
data class ToolCard(
    val key: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val pluginName: String?,
    val path: String?,
    val headlessNames: List<String>,
    /** The names one toggle sends (`[name] + headlessToolNames`, distinct). */
    val toggleNames: List<String>,
    /** The built-in id the repository updates in place; null for plugin tools. */
    val builtinId: String?,
) {
    /** Headless names worth a line of their own (the spec's "editor, apply_patch" line). */
    val headlessLine: String? get() = headlessNames.takeIf { names -> names.isNotEmpty() && names != listOf(name) }?.joinToString(", ")
}

@Immutable
data class ToolsState(val builtin: List<ToolCard> = emptyList(), val plugin: List<ToolCard> = emptyList())

enum class CommandType { Skill, Workflow }

/** A skill or workflow card (Skills tab). */
@Immutable
data class CommandCard(
    val key: String,
    val item: SettingsItem,
    val type: CommandType,
    val name: String,
    val preview: String,
    val path: String,
    val scope: Scope,
    val enabled: Boolean,
    /** False for workflows and Agent Plugin skills (desktop disables the switch). */
    val toggleable: Boolean,
    val agentPlugin: Boolean,
    val canUninstall: Boolean,
    val marketplaceMatches: List<MarketplaceEntry>,
)

/** The plugin contribution groups, in the desktop order. */
enum class ContributionKind { Tools, Skills, Rules, Hooks, Commands, McpServers, Providers, Capabilities }

@Immutable
data class ContributionGroup(val kind: ContributionKind, val items: List<String>)

@Immutable
data class PluginCard(
    val key: String,
    val item: SettingsItem,
    val name: String,
    val description: String?,
    val loadError: String?,
    val scope: Scope,
    val enabled: Boolean,
    val toggleable: Boolean,
    val agentPlugin: Boolean,
    /** `contributions.inspectionStatus == "disabled"`: "Enable this plugin to inspect…". */
    val inspectionDisabled: Boolean,
    val groups: List<ContributionGroup>,
    val canUninstall: Boolean,
    val marketplaceMatches: List<MarketplaceEntry>,
)

@Immutable
data class RuleCard(val key: String, val name: String, val preview: String, val path: String, val scope: Scope)

@Immutable
data class HookCard(val key: String, val fileName: String, val eventName: String?, val path: String, val scope: Scope)

/** One header on an MCP card: [value] null = a secret-looking key, rendered `[REDACTED]`. */
@Immutable
data class McpHeader(val key: String, val value: String?)

/**
 * An MCP server card. It holds env **keys** only and header values only for keys that do
 * not look secret (docs/07 §3.7): values never enter the UI state.
 */
@Immutable
data class McpServerCard(
    val key: String,
    val name: String,
    /** The desktop label kind, or null when the raw wire value is shown ([transportRaw]). */
    val transport: McpTransportType?,
    val transportRaw: String,
    val enabled: Boolean,
    val command: String?,
    val args: List<String>,
    val cwd: String?,
    val url: String?,
    val envKeys: List<String>,
    val headers: List<McpHeader>,
    val marketplaceMatches: List<MarketplaceEntry>,
) {
    /** "No launch command configured." when there is neither a command nor a URL. */
    val noLaunchCommand: Boolean get() = command.isNullOrBlank() && url.isNullOrBlank()

    /** `stringifyRedactedKeyValuePairs(env)`. */
    val envLine: String? get() = envKeys.takeIf { it.isNotEmpty() }?.joinToString(", ") { "$it=$REDACTED" }

    /** `stringifyKeyValuePairs(headers)` with secret-looking values redacted. */
    val headersLine: String? get() = headers.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key}=${it.value ?: REDACTED}" }

    /** The same line with every value hidden, for the semantics tree (CR §1.10). */
    val headersSpoken: String? get() = headers.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.key }

    companion object {
        const val REDACTED = "[REDACTED]"
    }
}

/** The MCP tab's own state (CR §1.8 `McpTabState`). */
@Immutable
data class McpTabState(
    val settingsPath: String? = null,
    val hasSettingsFile: Boolean = true,
    val servers: List<McpServerCard> = emptyList(),
    val source: McpSource? = null,
    val loaded: Boolean = false,
)

/** Pure projection and matching rules (CR §1.8; desktop `extensions-view.tsx`, `mcp-view.tsx`). */
object CustomizeRules {
    /** Desktop `previewText`: collapse whitespace, cut at [maxLength], trim, append "...". */
    fun previewText(input: String?, maxLength: Int = 150): String {
        val compact = input.orEmpty().replace(WHITESPACE, " ").trim()
        if (compact.length <= maxLength) return compact
        return takeGraphemePrefix(compact, maxLength).trimEnd() + "..."
    }

    // ---- keys ---------------------------------------------------------------------------------

    fun builtinKey(id: String) = "tool:$id"
    fun pluginToolKey(item: SettingsItem) = "ptool:${item.id}"
    fun commandKey(item: SettingsItem) = "${item.kind.wire}:${item.path}"
    fun pluginKey(item: SettingsItem) = "plugin:${item.path}"
    fun ruleKey(item: SettingsItem) = "rule:${item.path}"
    fun hookKey(hook: HookFile) = "hook:${hook.path}"
    fun mcpKey(name: String) = "mcp:$name"

    // ---- projection ---------------------------------------------------------------------------

    fun builtinTools(tools: List<BuiltinTool>): List<ToolCard> = CustomizeOrdering.builtinTools(tools).map { tool ->
        ToolCard(
            key = builtinKey(tool.id),
            name = tool.id,
            description = tool.description,
            enabled = tool.enabled,
            pluginName = null,
            path = null,
            headlessNames = tool.headlessToolNames,
            toggleNames = tool.toggleNames,
            builtinId = tool.id,
        )
    }

    fun pluginTools(snapshot: SettingsSnapshot?): List<ToolCard> = CustomizeOrdering.byName(snapshot?.tools.orEmpty()).map { item ->
        ToolCard(
            key = pluginToolKey(item),
            name = item.name,
            description = item.description,
            enabled = item.isEnabled,
            pluginName = item.pluginName,
            path = item.path.ifEmpty { null },
            headlessNames = emptyList(),
            toggleNames = listOf(item.name),
            builtinId = null,
        )
    }

    /** Skills and workflows merged and sorted by name (desktop `commands`). */
    fun commands(
        snapshot: SettingsSnapshot?,
        root: String,
        catalog: MarketplaceCatalog?,
        canUninstall: (SettingsItem) -> Boolean,
    ): List<CommandCard> {
        if (snapshot == null) return emptyList()
        return CustomizeOrdering.commands(snapshot).map { item ->
            val workflow = item.kind == SettingsKind.Workflow
            CommandCard(
                key = commandKey(item),
                item = item,
                type = if (workflow) CommandType.Workflow else CommandType.Skill,
                name = item.name,
                preview = previewText(item.description),
                path = item.path,
                scope = ScopeRules.command(item.path, root),
                enabled = item.isEnabled,
                toggleable = !workflow && !item.agentPlugin && item.toggleable != false,
                agentPlugin = item.agentPlugin,
                canUninstall = !item.agentPlugin && canUninstall(item),
                marketplaceMatches = matches(catalog, MarketplaceType.Skill, item.id, item.name, item.path),
            )
        }
    }

    fun plugins(
        snapshot: SettingsSnapshot?,
        root: String,
        catalog: MarketplaceCatalog?,
        canUninstall: (SettingsItem) -> Boolean,
    ): List<PluginCard> {
        if (snapshot == null) return emptyList()
        val toolsByPlugin = snapshot.tools.groupBy { it.pluginPath ?: it.path }
        val plugins = snapshot.plugins.map { item ->
            PluginCard(
                key = pluginKey(item),
                item = item,
                name = item.name,
                description = item.description?.trim()?.ifEmpty { null },
                loadError = item.loadError?.trim()?.ifEmpty { null },
                scope = ScopeRules.plugin(item, root),
                enabled = item.isEnabled,
                toggleable = item.toggleable != false,
                agentPlugin = item.agentPlugin,
                inspectionDisabled = item.contributions?.inspectionStatus == "disabled",
                groups = contributionGroups(item, toolsByPlugin[item.path].orEmpty().map { it.name }),
                canUninstall = !item.agentPlugin && canUninstall(item),
                marketplaceMatches = matches(catalog, MarketplaceType.Plugin, item.name, item.path),
            )
        }
        return ScopeRules.globalFirst(plugins) { it.scope }
    }

    /** The non-empty groups, Tools falling back to the plugin's tools from the snapshot (desktop). */
    fun contributionGroups(item: SettingsItem, pluginToolNames: List<String>): List<ContributionGroup> {
        val c = item.contributions
        return listOf(
            ContributionGroup(ContributionKind.Tools, c?.tools?.takeIf { it.isNotEmpty() } ?: pluginToolNames),
            ContributionGroup(ContributionKind.Skills, c?.skills.orEmpty()),
            ContributionGroup(ContributionKind.Rules, c?.rules.orEmpty()),
            ContributionGroup(ContributionKind.Hooks, c?.hooks.orEmpty()),
            ContributionGroup(ContributionKind.Commands, c?.commands.orEmpty()),
            ContributionGroup(ContributionKind.McpServers, c?.mcpServers.orEmpty()),
            ContributionGroup(ContributionKind.Providers, c?.providers.orEmpty()),
            ContributionGroup(ContributionKind.Capabilities, c?.capabilities.orEmpty()),
        ).filter { it.items.isNotEmpty() }
    }

    fun rules(snapshot: SettingsSnapshot?, root: String): List<RuleCard> {
        val rules = snapshot?.rules.orEmpty().map { item ->
            RuleCard(ruleKey(item), item.name, previewText(item.description), item.path, ScopeRules.rule(item.path, root))
        }
        return ScopeRules.globalFirst(rules) { it.scope }
    }

    fun hooks(hooks: List<HookFile>, root: String): List<HookCard> {
        val cards = hooks.map { hook ->
            HookCard(hookKey(hook), hook.fileName, hook.hookEventName?.trim()?.ifEmpty { null }, hook.path, ScopeRules.hook(hook.path, root))
        }
        return ScopeRules.globalFirst(cards) { it.scope }
    }

    fun mcp(response: McpServersResponse?, catalog: MarketplaceCatalog?): McpTabState {
        if (response == null) return McpTabState()
        return McpTabState(
            settingsPath = response.settingsPath,
            hasSettingsFile = response.hasSettingsFile,
            servers = response.sorted.map { mcpCard(it, catalog) },
            source = response.source,
            loaded = true,
        )
    }

    fun mcpCard(server: McpServer, catalog: MarketplaceCatalog?): McpServerCard = McpServerCard(
        key = mcpKey(server.name),
        name = server.name,
        transport = transportLabel(server.transportLabel),
        transportRaw = server.transportLabel,
        enabled = !server.disabled,
        command = server.command?.ifBlank { null },
        args = server.args.orEmpty(),
        cwd = server.cwd?.ifBlank { null },
        url = server.url?.ifBlank { null },
        envKeys = server.env.orEmpty().keys.toList(),
        headers = server.headers.orEmpty().map { (k, v) -> McpHeader(k, if (isSecretLookingKey(k)) null else v) },
        marketplaceMatches = matches(catalog, MarketplaceType.Mcp, server.name),
    )

    /** Desktop `TRANSPORT_TYPE_LABELS`: only the three exact wire values have a label. */
    fun transportLabel(raw: String): McpTransportType? = when (raw) {
        "stdio" -> McpTransportType.Stdio
        "sse" -> McpTransportType.Sse
        "streamableHttp" -> McpTransportType.StreamableHttp
        else -> null
    }

    /** Header keys whose values are never shown (CR §1.5 MCP card details, §1.10). */
    fun isSecretLookingKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key)

    /** Catalog entries of [type] that match a local item (desktop `entryMatchesLocalItem`). */
    fun matches(catalog: MarketplaceCatalog?, type: MarketplaceType, vararg values: String?): List<MarketplaceEntry> {
        if (catalog == null) return emptyList()
        val local = localMarketplaceMatchValues(*values)
        return catalog.entries.filter { it.type == type && it.matchesLocal(local) }
    }

    /** Entries worth a "Marketplace setup instructions" disclosure (desktop `entryHasSetupGuidance`). */
    fun withSetupGuidance(entries: List<MarketplaceEntry>): List<MarketplaceEntry> =
        entries.filter { !it.install.env.isNullOrEmpty() || !it.install.notes.isNullOrBlank() }

    // ---- search -------------------------------------------------------------------------------

    private fun normalized(query: String): String = query.trim().lowercase()

    /** Desktop `toolMatchesQuery`: name, description, plugin name, path, headless names. */
    fun toolMatches(tool: ToolCard, query: String): Boolean {
        val q = normalized(query)
        if (q.isEmpty()) return true
        return (listOf(tool.name, tool.description, tool.pluginName, tool.path) + tool.headlessNames)
            .any { it?.lowercase()?.contains(q) == true }
    }

    /** Skills and plugins: name, description, path. */
    fun itemMatches(name: String, description: String?, path: String?, query: String): Boolean {
        val q = normalized(query)
        if (q.isEmpty()) return true
        return listOf(name, description, path).any { it?.lowercase()?.contains(q) == true }
    }

    /** MCP: name, plus the command or URL the card shows. */
    fun mcpMatches(card: McpServerCard, query: String): Boolean {
        val q = normalized(query)
        if (q.isEmpty()) return true
        return listOf(card.name, card.command, card.url).any { it?.lowercase()?.contains(q) == true }
    }

    fun filterTools(tools: List<ToolCard>, query: String) = tools.filter { toolMatches(it, query) }
    fun filterCommands(cards: List<CommandCard>, query: String) = cards.filter { itemMatches(it.name, it.item.description, it.path, query) }
    fun filterPlugins(cards: List<PluginCard>, query: String) = cards.filter { itemMatches(it.name, it.description, it.item.path, query) }
    fun filterMcp(cards: List<McpServerCard>, query: String) = cards.filter { mcpMatches(it, query) }

    // ---- bulk toggles -------------------------------------------------------------------------

    /** Desktop `allBuiltinToolsEnabled`: the label reads "Disable all" only when every shown tool is on. */
    fun allEnabled(tools: List<ToolCard>): Boolean = tools.isNotEmpty() && tools.all { it.enabled }

    /** Desktop `setAllToolsEnabled` targets: every tool when disabling, only the disabled ones when enabling. */
    fun bulkTargets(tools: List<ToolCard>, enable: Boolean): List<ToolCard> = tools.filter { !enable || !it.enabled }

    /** The workspace chip label: the last path segment. */
    fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    private val WHITESPACE = Regex("\\s+")
    private val SECRET_KEY = Regex("(?i)(auth|token|secret|key|pass|cookie|credential|session|bearer|signature)")
}
