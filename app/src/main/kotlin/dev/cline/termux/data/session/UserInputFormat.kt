package dev.cline.termux.data.session

/**
 * The Hub persists user prompts wrapped as `<user_input mode="act">…</user_input>` (and may
 * prepend `<mode_notice>` blocks or wrap slash commands in `<user_command slash="…">`).
 * 1:1 port of the display helpers in upstream `sdk/packages/shared/src/prompt/format.ts`
 * (`parseUserInputMode`, `normalizeUserInput`, `stripModeNotices`, `parseUserCommandEnvelope`,
 * `formatDisplayUserInput`, `xmlTagsRemoval` and the block formatters); their tests are
 * ported in `UserInputFormatTest`.
 */
object UserInputFormat {
    data class Parsed(val text: String, val mode: PromptMode)

    data class UserCommandEnvelope(val slash: String, val content: String)

    /** Display text plus the recovered mode (Act when the input is not wrapped). */
    fun parse(raw: String): Parsed = Parsed(formatDisplayUserInput(raw), PromptMode.parse(parseUserInputMode(raw)))

    fun display(raw: String): String = formatDisplayUserInput(raw)

    /** A prompt the user really typed is always wrapped (docs/08); steering notes are not. */
    fun isWrappedUserInput(raw: String): Boolean = USER_INPUT_OPEN.containsMatchIn(raw) || parseUserCommandEnvelope(raw) != null

    fun formatUserInputBlock(input: String, mode: PromptMode = PromptMode.Act): String =
        "<user_input mode=\"${mode.wire}\">$input</user_input>"

    fun formatUserCommandBlock(input: String, slash: String): String = "<user_command slash=\"$slash\">$input</user_command>"

    fun formatModeSwitchNotice(from: PromptMode, to: PromptMode): String =
        "<mode_notice>The user switched from ${from.wire} mode to ${to.wire} mode before sending this message.</mode_notice>"

    private val USER_INPUT_MODE = Regex("""<user_input\b[^>]*\bmode="(act|plan|yolo)"""")
    private val USER_INPUT_OPEN = Regex("""<user_input\b[^>]*>""")

    /** `act` | `plan` | `yolo`, or null when the input is not wrapped or the mode is unknown. */
    fun parseUserInputMode(input: String?): String? = USER_INPUT_MODE.find(input ?: "")?.groupValues?.get(1)

    fun xmlTagsRemoval(input: String?, tag: String?): String {
        if (input.isNullOrBlank()) return ""
        if (tag == null) return input
        return Regex("""<$tag\b[^>]*>([\s\S]*?)</$tag>""").replace(input) { it.groupValues[1] }
    }

    fun normalizeUserInput(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var next = input.trim()
        for (tag in listOf("user_input", "user_command")) {
            val extracted = xmlTagsRemoval(next, tag)
            next = (if (extracted != next) extracted else next.replace(Regex("<$tag[^>]*>"), "")).trim()
        }
        return next
    }

    /** Removes `<mode_notice>` elements (content included); unclosed ones stay. Linear scan, like upstream. */
    fun stripModeNotices(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return removeTagElements(input, "mode_notice").trim()
    }

    private fun removeTagElements(input: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val result = StringBuilder(input)
        var start = result.indexOf(open)
        while (start != -1) {
            val end = result.indexOf(close, start + open.length)
            if (end == -1) break
            result.delete(start, end + close.length)
            start = result.indexOf(open, start)
        }
        return result.toString()
    }

    fun parseUserCommandEnvelope(input: String?): UserCommandEnvelope? {
        if (input.isNullOrBlank()) return null
        val match = Regex("""^<user_command\b([^>]*)>([\s\S]*?)</user_command>$""", RegexOption.IGNORE_CASE).find(input.trim()) ?: return null
        val attrs = match.groupValues[1]
        val slash = Regex("""slash="([^"]+)"""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return UserCommandEnvelope(slash, match.groupValues[2].trim())
    }

    fun formatDisplayUserInput(input: String?): String {
        val normalized = stripModeNotices(normalizeUserInput(input))
        val envelope = parseUserCommandEnvelope(input) ?: return normalized
        if (envelope.slash.lowercase() == "team") {
            val prefix = "spawn a team of agents for the following task:"
            val stripped = if (normalized.lowercase().startsWith(prefix)) normalized.substring(prefix.length).trim() else normalized
            return if (stripped.isNotEmpty()) "/team $stripped" else "/team"
        }
        return if (normalized.isNotEmpty()) "/${envelope.slash} $normalized" else "/${envelope.slash}"
    }
}
