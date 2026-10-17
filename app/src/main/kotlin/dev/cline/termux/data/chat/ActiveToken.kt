package dev.cline.termux.data.chat

/*
 * Ports of the desktop composer's token helpers (apps/examples/desktop-app/webview/components/
 * views/chat/chat-input-bar.tsx: `getActiveMention`, `getActiveSlash`, `insertSlashCommandItem`,
 * `insertMentionFile`) for design/screens/composer-approvals.md C5.8 and C6.7. Pure: the
 * composer feeds them the field text and cursor; the suggestion panels read the result.
 */

/** `ActiveMention`: the `@query` being typed, `start` at the `@`, `end` at the cursor. */
data class ActiveMention(val start: Int, val end: Int, val query: String)

/** `ActiveSlash`: the `/query` being typed, `slashIndex` at the `/`. */
data class ActiveSlash(val slashIndex: Int, val query: String)

/** A replacement of the whole field text with the cursor placed at [cursor]. */
data class TokenEdit(val text: String, val cursor: Int)

object ActiveToken {
    private val MENTION_BODY = Regex("^[^\\s@]*$")
    private val WHITESPACE = Regex("\\s")

    /** `(^|\s)\/[a-zA-Z0-9_.-]+\s`: a completed slash command already sits before this one. */
    private val FIRST_SLASH_COMMAND = Regex("(^|\\s)/[a-zA-Z0-9_.-]+\\s")

    /** `getActiveMention(input, cursor)`. */
    fun mention(input: String, cursor: Int): ActiveMention? {
        if (cursor < 0 || cursor > input.length) return null
        val left = input.substring(0, cursor)
        val atIndex = left.lastIndexOf('@')
        if (atIndex == -1) return null
        if (atIndex > 0 && !left[atIndex - 1].isJsWhitespace()) return null
        val body = left.substring(atIndex + 1)
        if (!MENTION_BODY.matches(body)) return null
        return ActiveMention(start = atIndex, end = cursor, query = body)
    }

    /** `getActiveSlash(input, cursor)`. */
    fun slash(input: String, cursor: Int): ActiveSlash? {
        if (cursor < 0 || cursor > input.length) return null
        val left = input.substring(0, cursor)
        val slashIndex = left.lastIndexOf('/')
        if (slashIndex == -1) return null
        // Slash must be at the start or preceded by whitespace.
        if (slashIndex > 0 && !left[slashIndex - 1].isJsWhitespace()) return null
        val query = left.substring(slashIndex + 1)
        // No whitespace inside the query: once a space is typed the command is committed.
        if (WHITESPACE.containsMatchIn(query)) return null
        // Don't open slash mode if a completed slash command already precedes this one.
        if (FIRST_SLASH_COMMAND.containsMatchIn(input.substring(0, slashIndex))) return null
        return ActiveSlash(slashIndex = slashIndex, query = query)
    }

    /**
     * `insertSlashCommandItem`: replaces only the typed `/query` (slashIndex up to the cursor),
     * keeps the text after the cursor, and adds a separator unless that text already starts
     * with whitespace. The cursor lands just past the separator (or the suffix's own space).
     */
    fun insertSlashCommand(input: String, cursor: Int, slash: ActiveSlash, commandName: String): TokenEdit {
        val safeCursor = cursor.coerceIn(0, input.length)
        val suffix = input.substring(safeCursor)
        val separator = if (suffix.firstOrNull()?.isJsWhitespace() == true) "" else " "
        val text = input.substring(0, slash.slashIndex) + "/" + commandName + separator + suffix
        return TokenEdit(text, slash.slashIndex + commandName.length + 2)
    }

    /** `insertMentionFile`: `@path ` replaces the active mention; the cursor goes past the space. */
    fun insertMention(input: String, mention: ActiveMention, filePath: String): TokenEdit {
        val text = input.substring(0, mention.start) + "@" + filePath + " " + input.substring(mention.end.coerceAtMost(input.length))
        return TokenEdit(text, mention.start + filePath.length + 2)
    }

    /** JavaScript `\s` (Unicode white space plus the BOM), which `Char.isWhitespace` nearly matches. */
    private fun Char.isJsWhitespace(): Boolean = isWhitespace() || this == '\uFEFF' || this == '\u00A0'
}
