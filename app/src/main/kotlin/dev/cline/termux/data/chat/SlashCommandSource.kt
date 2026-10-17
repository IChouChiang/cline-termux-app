package dev.cline.termux.data.chat

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.tools.JS_WHITESPACE_RUN
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * The slash menu's commands and their send-time resolution (composer-approvals.md C5).
 * Ports of the desktop `chat-input-bar.tsx` slash helpers (`getActiveSlash`,
 * `BUILTIN_SLASH_COMMANDS`, `buildUserInstructionSlashCommands`, `filteredSlashCommands`,
 * `insertSlashCommandItem`), core `runtime-commands.ts` (`normalizeRuntimeCommandName`) and
 * the CLI's `expandUserCommandPrompt`. Plain data: descriptions the desktop hard-codes
 * (built-ins, "Skill command", "Workflow command") are left null here and the UI maps
 * them by [SlashKind]. No `/fork` (decided A16, A19): the whole-session fork lives in the
 * chat's three-dot menu and the drawer's row menu only.
 */
@Immutable
data class SlashCommand(
    val name: String,
    /** The configured description (first sentence, ≤ 100 chars); null → the UI's per-kind copy. */
    val description: String?,
    val kind: SlashKind,
    /** `SKILL.md` or the workflow file (`CoreSettingsItem.path`); null for built-ins. */
    val path: String?,
)

enum class SlashKind { BuiltinTeam, Skill, Workflow }

/** A replacement for the composer field: new text and the collapsed cursor position. */
data class TextEdit(val text: String, val cursor: Int)

/**
 * One workspace's `settings.list` projection for the slash menu. It holds commands only: the
 * built-in `teams` and `skills` switches are not in `settings.list` (its `tools` are plugin
 * tools), so C5.7 reads them from [DisabledToolsSource] at send time.
 */
@Immutable
data class SlashCatalog(
    /** Built-ins first, then skills and workflows in `settings.list` order (never sorted). */
    val commands: List<SlashCommand>,
) {
    fun userCommand(name: String): SlashCommand? =
        commands.firstOrNull { it.name == name && (it.kind == SlashKind.Skill || it.kind == SlashKind.Workflow) }

    companion object {
        val BUILTINS_ONLY = SlashCatalog(SlashCommands.BUILTINS)
    }
}

// SlashRefusal and SlashResolution (C5.5 / C5.7) live in SendPipeline.kt; ActiveSlash in ActiveToken.kt.

object SlashCommands {
    const val MAX_ROWS = 10
    const val TEAM = "team"

    /** Desktop `BUILTIN_SLASH_COMMANDS` without `/fork` (decided A19: one place per function). */
    val BUILTINS: List<SlashCommand> = listOf(
        SlashCommand(TEAM, null, SlashKind.BuiltinTeam, null),
    )

    private val BUILTIN_NAMES = BUILTINS.map { it.name }.toSet()

    /** `runtime-commands.ts normalizeRuntimeCommandName`: Unicode letters and digits kept, whitespace and symbol runs → "-". */
    fun normalizeRuntimeCommandName(name: String): String = name
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE_RUN, "-")
        .replace(SYMBOL_RUN, "-")
        .replace(DASH_RUN, "-")
        .removePrefix("-")
        .removeSuffix("-")

    /** `stableRuntimeCommandSuffix`: slug of the id plus 12 hex chars of its SHA-256. */
    fun stableRuntimeCommandSuffix(id: String): String {
        val slug = normalizeRuntimeCommandName(id)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .removePrefix("-")
            .removeSuffix("-")
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return "${slug.ifEmpty { "command" }}-$hash"
    }

    /** `@cline/shared truncateSplit(str, ".")`: ≤ 100 chars, else the first sentence plus "…". */
    fun truncateSplit(str: String?, splitBy: String = ".", maxLen: Int = 100): String {
        if (str.isNullOrEmpty() || str.length <= maxLen) return str.orEmpty()
        // Whole graphemes only (DS §8.2.7): a skill description may hold CJK, emoji or combining marks.
        val prefix = takeGraphemePrefix(str.split(splitBy).first().trim(), maxLen - 1)
        return if (prefix.isNotEmpty()) "$prefix…" else takeGraphemePrefix(str, maxLen - 1) + "…"
    }

    /**
     * The user commands of a `settings.list` snapshot (desktop `buildUserInstructionSlashCommands`
     * over the core's runtime commands): disabled items skipped, skills before workflows, a
     * workflow whose name equals a skill's dropped, built-in names hidden, first entry wins.
     */
    fun userCommands(snapshot: SettingsSnapshot): List<SlashCommand> {
        val seen = HashSet(BUILTIN_NAMES)
        val result = ArrayList<SlashCommand>()
        fun add(item: SettingsItem, kind: SlashKind) {
            if (!item.isEnabled) return
            val prefix = if (kind == SlashKind.Skill) "skill" else "workflow"
            val name = normalizeRuntimeCommandName(item.name).ifEmpty { "$prefix-${stableRuntimeCommandSuffix(item.id)}" }
            if (!seen.add(name)) return
            val description = item.description?.trim()?.takeIf { it.isNotEmpty() }?.let { truncateSplit(it) }
            result += SlashCommand(name, description, kind, item.path.takeIf { it.isNotBlank() })
        }
        snapshot.skills.forEach { add(it, SlashKind.Skill) }
        snapshot.workflows.forEach { add(it, SlashKind.Workflow) }
        return result
    }

    fun catalog(snapshot: SettingsSnapshot): SlashCatalog = SlashCatalog(commands = BUILTINS + userCommands(snapshot))

    /** Desktop `filteredSlashCommands`: build order for an empty query; else contains, prefix first, then `localeCompare`. */
    fun filter(commands: List<SlashCommand>, query: String, collator: Collator = Collator.getInstance()): List<SlashCommand> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return commands.take(MAX_ROWS)
        return commands
            .filter { it.name.lowercase(Locale.ROOT).contains(q) }
            .sortedWith { a, b ->
                val aStarts = a.name.lowercase(Locale.ROOT).startsWith(q)
                val bStarts = b.name.lowercase(Locale.ROOT).startsWith(q)
                when {
                    aStarts && !bStarts -> -1
                    !aStarts && bStarts -> 1
                    else -> collator.compare(a.name, b.name)
                }
            }
            .take(MAX_ROWS)
    }

    /**
     * Desktop `getActiveSlash`: a `/` at the start or after whitespace, no whitespace in the
     * query, and no completed `/command ` earlier in the input.
     */
    fun getActiveSlash(input: String, cursor: Int): ActiveSlash? {
        if (cursor < 0 || cursor > input.length) return null
        val left = input.substring(0, cursor)
        val slashIndex = left.lastIndexOf('/')
        if (slashIndex == -1) return null
        if (slashIndex > 0 && !left[slashIndex - 1].isJsWhitespace()) return null
        val query = left.substring(slashIndex + 1)
        if (query.any { it.isJsWhitespace() }) return null
        if (COMPLETED_SLASH_COMMAND.containsMatchIn(input.substring(0, slashIndex))) return null
        return ActiveSlash(slashIndex, query)
    }

    /**
     * Desktop `insertSlashCommandItem`: replaces only the typed `/query`, keeps the text after
     * the cursor, adds a separator unless that text already starts with whitespace, and puts
     * the cursor just past the separator.
     */
    fun insertSlashCommandItem(input: String, cursor: Int, active: ActiveSlash, name: String): TextEdit {
        val safeCursor = cursor.coerceIn(0, input.length)
        val suffix = input.substring(safeCursor)
        val separator = if (suffix.firstOrNull()?.isJsWhitespace() == true) "" else " "
        val next = input.substring(0, active.slashIndex) + "/" + name + separator + suffix
        return TextEdit(next, (active.slashIndex + name.length + 2).coerceAtMost(next.length))
    }

    /** Strips a leading YAML front matter block (`---` … `---`) and trims (C5.6 workflow invoke). */
    fun stripFrontMatter(text: String): String {
        val body = text.removePrefix("\uFEFF")
        val match = FRONT_MATTER.find(body) ?: return body.trim()
        return body.substring(match.range.last + 1).trim()
    }

    /** CLI `expandUserCommandPrompt` for a leading command: the block, then " " + rest when rest is not blank. */
    fun expandUserCommand(instructions: String, name: String, rest: String): String {
        val block = UserInputFormat.formatUserCommandBlock(instructions, name)
        val trimmedRest = rest.trim()
        return if (trimmedRest.isNotEmpty()) "$block $trimmedRest" else block
    }

    /** The CLI `rewriteTeamPrompt` wrapper for a non-empty task (C5.7 step 2). */
    fun teamPrompt(task: String): String =
        UserInputFormat.formatUserCommandBlock("spawn a team of agents for the following task: $task", TEAM)

    /** `/team` followed by whitespace or the end (C5.7 step 2): the task (possibly blank), or null when not a team command. */
    fun teamTask(trimmed: String): String? = TEAM_COMMAND.find(trimmed)?.groupValues?.get(1)?.trim()

    /** The first token `/<raw>` of [trimmed] and what follows it, or null when it does not start with a command. */
    fun leadingCommand(trimmed: String): Pair<String, String>? {
        if (!trimmed.startsWith("/") || trimmed.length < 2) return null
        val raw = LEADING_COMMAND.find(trimmed)?.groupValues?.get(1) ?: return null
        return raw to trimmed.substring(raw.length + 1)
    }

    private fun Char.isJsWhitespace(): Boolean = isWhitespace() || this == '\u00A0' || this == '\uFEFF'

    /** JavaScript `\s+` as an explicit class: Android's ICU regex rejects the JVM-only Unicode-class flag. */
    private val WHITESPACE_RUN = JS_WHITESPACE_RUN
    private val SYMBOL_RUN = Regex("[^\\p{L}\\p{N}_.:@-]+")
    private val DASH_RUN = Regex("-+")
    private val COMPLETED_SLASH_COMMAND = Regex("(^|\\s)/[a-zA-Z0-9_.-]+\\s")
    private val FRONT_MATTER = Regex("\\A---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(\\r?\\n|\\z)")
    private val TEAM_COMMAND = Regex("^/team(?:\\s+([\\s\\S]*))?$", RegexOption.IGNORE_CASE)
    private val LEADING_COMMAND = Regex("^/(\\S+)")
}

/**
 * Process-wide slash command cache keyed by workspace root (C5.6), refreshed on each panel
 * open and invalidated on `settings.changed`, plus the C5.7 send-time resolution the send
 * pipeline and the prompt-queue editor run. One instance per process (AppGraph).
 */
class SlashCommandSource(
    /** `settings.list {workspaceRoot, cwd}` (HubSessionApi.settingsList). */
    private val settingsList: suspend (workspaceRoot: String) -> JsonObject,
    private val termux: TermuxExecutor,
    /** `global-settings.json` `disabledTools` for the `teams` and `skills` checks (C5.7 steps 2 and 4). */
    private val disabledTools: DisabledToolsSource = GlobalSettingsDisabledTools(termux),
) {
    private val cache = ConcurrentHashMap<String, SlashCatalog>()
    private val _catalogs = MutableStateFlow<Map<String, SlashCatalog>>(emptyMap())

    /** Every cached workspace's catalog; the menu re-renders when a refresh lands. */
    val catalogs: StateFlow<Map<String, SlashCatalog>> = _catalogs.asStateFlow()

    private val _invalidations = MutableStateFlow(0)

    /** Bumped by [invalidate]; an open panel refreshes when it changes. */
    val invalidations: StateFlow<Int> = _invalidations.asStateFlow()

    /** The last known catalog for [workspaceRoot], or null when it was never loaded. */
    fun cached(workspaceRoot: String?): SlashCatalog? = workspaceRoot?.let { cache[it] }

    /** `settings.list` → catalog; the cache keeps the previous catalog on failure (desktop "Keep built-in commands on error"). */
    suspend fun refresh(workspaceRoot: String): Result<SlashCatalog> {
        val reply = try {
            settingsList(workspaceRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val snapshot = SettingsSnapshot.fromReply(reply) ?: return Result.failure(IllegalStateException("settings.list returned no snapshot"))
        val catalog = SlashCommands.catalog(snapshot)
        cache[workspaceRoot] = catalog
        _catalogs.update { it + (workspaceRoot to catalog) }
        return Result.success(catalog)
    }

    /** `settings.changed` (docs/07 §4.2): the next open, and any open panel, reloads. */
    fun invalidate() {
        _invalidations.update { it + 1 }
    }

    /** `cat <path>` through RUN_COMMAND (60 s), front matter stripped; failure on a non-zero exit, a cut result or an empty body. */
    suspend fun readInstructions(path: String): Result<String> {
        val result = try {
            termux.run(ClineCommands.readTextFile(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (!result.ok || result.truncated) return Result.failure(IllegalStateException("cat exited ${result.exitCode}"))
        val body = SlashCommands.stripFrontMatter(result.stdout)
        return if (body.isEmpty()) Result.failure(IllegalStateException("empty file")) else Result.success(body)
    }

    /**
     * C5.7 steps 1-5 for [text] in [workspaceRoot] and [mode]. With [kernelUp] and no cached
     * catalog, `settings.list` is read first so a typed `/workflow` still expands.
     */
    suspend fun resolve(text: String, workspaceRoot: String?, mode: PromptMode, kernelUp: Boolean = true): SlashResolution {
        val trimmed = text.trim()
        val catalog = cached(workspaceRoot)
            ?: if (kernelUp && workspaceRoot != null && trimmed.startsWith("/")) refresh(workspaceRoot).getOrNull() else null
        SlashCommands.teamTask(trimmed)?.let { task ->
            // C5.7 step 2 order (desktop `rewriteDesktopTeamPrompt`, as SendPipeline.resolve): usage, then the disabled tool, then the mode.
            return when {
                task.isEmpty() -> SlashResolution.Refused(SlashRefusal.TeamUsage)
                SendPipeline.TEAMS_TOOL in disabledTools.disabledTools() -> SlashResolution.Refused(SlashRefusal.TeamDisabled)
                mode == PromptMode.Plan -> SlashResolution.Refused(SlashRefusal.TeamPlanMode)
                else -> SlashResolution.Send(SlashCommands.teamPrompt(task))
            }
        }
        val (raw, rest) = SlashCommands.leadingCommand(trimmed) ?: return SlashResolution.Send(trimmed)
        val name = SlashCommands.normalizeRuntimeCommandName(raw)
        val command = catalog?.userCommand(name) ?: return SlashResolution.Send(trimmed)
        if (command.kind == SlashKind.Skill && SendPipeline.SKILLS_TOOL !in disabledTools.disabledTools()) return SlashResolution.Send(trimmed)
        val refusal = if (command.kind == SlashKind.Skill) SlashRefusal.SkillReadFailed(command.name) else SlashRefusal.WorkflowReadFailed(command.name)
        val path = command.path ?: return SlashResolution.Refused(refusal)
        val instructions = readInstructions(path).getOrElse { return SlashResolution.Refused(refusal) }
        return SlashResolution.Send(SlashCommands.expandUserCommand(instructions, command.name, rest))
    }

    companion object {
        fun of(api: HubSessionApi, termux: TermuxExecutor): SlashCommandSource =
            SlashCommandSource(settingsList = { root -> api.settingsList(root) }, termux = termux)
    }
}
