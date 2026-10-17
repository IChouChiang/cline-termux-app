package dev.cline.termux.data.diagnostics

import dev.cline.termux.core.kernel.termux.TermuxPaths

/**
 * customize-and-rest.md §9.6: blanks credential-shaped text in the diagnostics report and in
 * every piece of CLI output a screen shows (C1 uninstall, C2 MCP fallback, C3 installs).
 *
 * Rules, applied so that no rule can expose what another hides (the Bearer value is replaced
 * before the field rule rewrites the word `Bearer` itself):
 * 2. `cline-hub-auth.<hex>` (the `Sec-WebSocket-Protocol` value) → `cline-hub-auth.<redacted>`;
 * 4. `Bearer <value>` → `Bearer <redacted>`;
 * 5. `--apikey <value>` / `-k <value>` → `--apikey •••` / `-k •••` (KS §0.6 rule 4);
 * 1. the desktop `SECRET_FIELD_PATTERN` verbatim (sidecar/diagnostics.ts) → `$1<redacted>`;
 * 3. any standalone run of exactly 64 hex digits (the Hub token shape) → `<redacted>`;
 * 6. the Termux home `/data/data/com.termux/files/home` → `~` (desktop home replacement).
 */
object Redactor {
    const val REDACTED = "<redacted>"
    const val MASK = "•••"

    /** Desktop `SECRET_FIELD_PATTERN` (`gi`). */
    val SECRET_FIELD_PATTERN = Regex(
        "((?:api[_-]?key|token|secret|password|authorization)\"?\\s*[:=]\\s*\"?)([^\",}\\s]+)",
        RegexOption.IGNORE_CASE,
    )
    private val HUB_AUTH_PROTOCOL = Regex("cline-hub-auth\\.[0-9a-fA-F]+")
    private val HEX_64 = Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{64}(?![0-9A-Za-z])")
    private val BEARER = Regex("Bearer\\s+(?!<redacted>)\\S+")
    private val API_KEY_FLAG = Regex("(--apikey|(?<!\\S)-k)(\\s+|=)(?!•••)\\S+")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = HUB_AUTH_PROTOCOL.replace(text, "cline-hub-auth.$REDACTED")
        result = BEARER.replace(result, "Bearer $REDACTED")
        result = API_KEY_FLAG.replace(result) { "${it.groupValues[1]} $MASK" }
        result = SECRET_FIELD_PATTERN.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = HEX_64.replace(result, REDACTED)
        result = result.replace(TermuxPaths.HOME, "~")
        return result
    }
}
