package dev.cline.termux.data.chat

import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.UserInputFormat

/*
 * The composer's send pipeline (design/screens/composer-approvals.md C1.7 + C5.7): the
 * pre-send checks in their desktop order, the send-time slash resolution (`/team`, workflows,
 * skills; no `/fork`, decided A16 and A19, so a typed `/fork` goes out as typed), and the
 * failure table that maps store outcomes and signals onto what the composer does with the
 * draft. Pure apart from the injected file read; prompt text is never logged (CA §0.8).
 */

/** Why a tap on Send did nothing (C1.7 steps 1–4), in check order. */
enum class SendBlock {
    /** Step 1: `canSend` is false (empty, voice active, kernel not connected, held). */
    NotAllowed,

    /** Step 2: images the selected model cannot read (`composer_images_unsupported`). */
    ImagesUnsupported,

    /** Step 3: attachments without text (`composer_needs_text`). */
    NeedsText,

    /** Step 4 (Android): no provider configured (`composer_connect_model`). */
    ConnectModel,
}

/** The facts the pre-send checks read. */
data class SendCheckInput(
    val canSend: Boolean,
    val text: String,
    val attachmentCount: Int,
    val imagesUnsupported: Boolean,
    /** null while the catalog is not read yet (never blocks). */
    val providerConfigured: Boolean?,
)

/** A slash refusal; the composer keeps the draft and says why (C5.5 copy). */
sealed interface SlashRefusal {
    data object TeamDisabled : SlashRefusal
    data object TeamUsage : SlashRefusal
    data object TeamPlanMode : SlashRefusal
    data class WorkflowReadFailed(val name: String) : SlashRefusal
    data class SkillReadFailed(val name: String) : SlashRefusal
}

/** C5.7 result. */
sealed interface SlashResolution {
    /** Send [prompt] (typed, rewritten or expanded). */
    data class Send(val prompt: String) : SlashResolution

    data class Refused(val reason: SlashRefusal) : SlashResolution
}

/** A user command the pipeline can expand (normalised name, settings path). */
data class UserSlashCommand(val name: String, val kind: Kind, val path: String) {
    enum class Kind { Skill, Workflow }
}

/** What the composer does after a send attempt resolved (C1.7 failure table). */
sealed interface SendResult {
    /** The Hub took it (direct, queued, or held for the reconnect by the store). */
    data class Accepted(val direct: Boolean) : SendResult

    /** Put the draft back and say "Message not sent. Cline Hub disconnected." + Retry. */
    data object Disconnected : SendResult

    /** Held for a reconnect that found no kernel: put the draft back and say "Message not sent. Start kernel to continue." (HC §3.12 step 6). */
    data object KernelDown : SendResult

    /** Put the draft back and say "Could not resume this session." + Retry. */
    data object ResumeFailed : SendResult

    /** Put the draft back and say "Message not sent. <message>" + Retry. */
    data class Failed(val message: String) : SendResult

    /** `invalid_session_input`: put the draft back, record the code only. */
    data object RestoreOnly : SendResult
}

class SendPipeline(
    /** `global-settings.json` `disabledTools` (C5.7 steps 2 and 4); read only when a check needs it. */
    private val disabledTools: DisabledToolsSource,
    /** RUN_COMMAND `cat <path>` (60 s); the text on exit 0, a failure otherwise. */
    private val readTextFile: suspend (String) -> Result<String>,
) {

    /**
     * C5.7 steps 1–5 on the trimmed [text]. [snapshot] is the cached `settings.list` of the
     * session's workspace, used for its skills and workflows only (null when none is cached:
     * unknown `/words` are sent as typed). Whether the built-in `teams` and `skills` tools are
     * switched off comes from [disabledTools], never from `snapshot.tools` (plugin tools only).
     */
    suspend fun resolve(text: String, mode: PromptMode, snapshot: SettingsSnapshot?): SlashResolution {
        val trimmed = text.trim()
        // 1. `/team` followed by whitespace or the end (before user commands). Desktop
        // `rewriteDesktopTeamPrompt` order: usage, then the disabled tool, then the mode.
        TEAM.matchEntire(trimmed)?.let { match ->
            val task = match.groupValues[1].trim()
            if (task.isEmpty()) return SlashResolution.Refused(SlashRefusal.TeamUsage)
            if (TEAMS_TOOL in disabledTools.disabledTools()) return SlashResolution.Refused(SlashRefusal.TeamDisabled)
            if (mode == PromptMode.Plan) return SlashResolution.Refused(SlashRefusal.TeamPlanMode)
            return SlashResolution.Send(teamPrompt(task))
        }
        val command = FIRST_TOKEN.matchEntire(trimmed) ?: return SlashResolution.Send(trimmed)
        val name = command.groupValues[1].lowercase()
        val rest = command.groupValues[2].trim()
        val target = userCommands(snapshot).firstOrNull { it.name == name } ?: return SlashResolution.Send(trimmed)
        return when (target.kind) {
            // 2. Workflows are always expanded client-side.
            UserSlashCommand.Kind.Workflow -> expand(target, rest) ?: SlashResolution.Refused(SlashRefusal.WorkflowReadFailed(target.name))
            // 3. Skills go as typed while the `skills` tool is available, else expand like a workflow.
            UserSlashCommand.Kind.Skill -> if (SKILLS_TOOL !in disabledTools.disabledTools()) {
                SlashResolution.Send(trimmed)
            } else {
                expand(target, rest) ?: SlashResolution.Refused(SlashRefusal.SkillReadFailed(target.name))
            }
        }
    }

    private suspend fun expand(command: UserSlashCommand, rest: String): SlashResolution.Send? {
        val raw = readTextFile(command.path).getOrNull() ?: return null
        return SlashResolution.Send(expandUserCommand(command.name, stripFrontMatter(raw), rest))
    }

    companion object {
        const val TEAMS_TOOL = "teams"
        const val SKILLS_TOOL = "skills"

        /** Names that hide user commands of the same name (desktop `BUILTIN_SLASH_COMMANDS` without `/fork`, decided A19). */
        val BUILTIN_NAMES = setOf("team")

        private val TEAM = Regex("^/team(?:\\s([\\s\\S]*))?$", RegexOption.IGNORE_CASE)

        /** `/name` then optional whitespace + the rest (CLI `^\/([a-zA-Z0-9_.-]+)(\s+[\s\S]*)?$`, Unicode names kept). */
        private val FIRST_TOKEN = Regex("^/([\\p{L}\\p{N}_.:@-]+)(\\s+[\\s\\S]*)?$")

        private val FRONT_MATTER = Regex("^\\uFEFF?---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(?:\\r?\\n|$)")

        /** C1.7 steps 1–4, first failing check wins. */
        fun precheck(input: SendCheckInput): SendBlock? = when {
            !input.canSend -> SendBlock.NotAllowed
            input.imagesUnsupported -> SendBlock.ImagesUnsupported
            input.text.isBlank() && input.attachmentCount > 0 -> SendBlock.NeedsText
            input.text.isBlank() -> SendBlock.NotAllowed
            input.providerConfigured == false -> SendBlock.ConnectModel
            else -> null
        }

        /** `normalizeRuntimeCommandName` (core runtime-commands.ts). */
        fun commandName(name: String): String = name
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^\\p{L}\\p{N}_.:@-]+"), "-")
            .replace(Regex("-+"), "-")
            .removePrefix("-")
            .removeSuffix("-")

        /**
         * The user commands in build order (C5.6): enabled skills then enabled workflows as
         * `settings.list` returns them, a workflow whose name equals a skill's dropped,
         * built-in names hidden.
         */
        fun userCommands(snapshot: SettingsSnapshot?): List<UserSlashCommand> {
            if (snapshot == null) return emptyList()
            val seen = BUILTIN_NAMES.toMutableSet()
            val out = ArrayList<UserSlashCommand>()
            fun add(items: List<SettingsItem>, kind: UserSlashCommand.Kind) {
                for (item in items) {
                    if (item.enabled == false) continue
                    val name = commandName(item.name)
                    if (name.isEmpty() || !seen.add(name)) continue
                    out += UserSlashCommand(name, kind, item.path)
                }
            }
            add(snapshot.skills, UserSlashCommand.Kind.Skill)
            add(snapshot.workflows, UserSlashCommand.Kind.Workflow)
            return out
        }

        /** `rewriteDesktopTeamPrompt` once the checks passed. */
        fun teamPrompt(task: String): String =
            UserInputFormat.formatUserCommandBlock("spawn a team of agents for the following task: $task", "team")

        /** CLI `expandUserCommandPrompt`: the block, then " " + rest when rest is not blank. */
        fun expandUserCommand(name: String, instructions: String, rest: String): String {
            val block = UserInputFormat.formatUserCommandBlock(instructions, name)
            return if (rest.isBlank()) block else "$block ${rest.trim()}"
        }

        /** Strips a leading YAML front matter block (`---` … `---`) and the blank lines after it. */
        fun stripFrontMatter(text: String): String {
            val match = FRONT_MATTER.find(text) ?: return text.trim()
            return text.substring(match.range.last + 1).trim()
        }

        /** The failure table for a store outcome (C1.7). */
        fun resultOf(outcome: SendOutcome): SendResult = when (outcome) {
            is SendOutcome.Sent -> SendResult.Accepted(direct = true)
            is SendOutcome.Queued -> SendResult.Accepted(direct = false)
            // The store resends after the re-register (at most once per session).
            SendOutcome.Held -> SendResult.Accepted(direct = true)
            SendOutcome.AlreadyHeld, SendOutcome.NotConnected -> SendResult.Disconnected
            is SendOutcome.ResumeFailed -> SendResult.ResumeFailed
        }

        /** The failure table for a later store signal; null for signals that are not failures. */
        fun resultOf(signal: SessionSignal): SendResult? = when (signal) {
            is SessionSignal.NotSent -> if (signal.kernelDown) SendResult.KernelDown else SendResult.ResumeFailed
            is SessionSignal.SendFailed -> when (signal.code) {
                "invalid_session_input" -> SendResult.RestoreOnly
                "hub_connection_closed" -> SendResult.Disconnected
                else -> SendResult.Failed(signal.message)
            }
            is SessionSignal.Interrupted -> null
        }
    }
}
