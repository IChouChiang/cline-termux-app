package dev.cline.termux.core.kernel.termux

/**
 * Strips ANSI escape sequences from RUN_COMMAND output before it is matched, displayed
 * or logged (docs/07 §3.3; SP §0.4 "Save"). The CLI writes through an unconditional colour
 * table, so even without a TTY stdout reads `ESC[32mProvider configured:ESC[0m`.
 *
 * [SGR] is the contract's regex. The two extra passes remove the other CSI sequences
 * (cursor moves, erase, private modes such as `ESC[?25l`) and OSC sequences (titles,
 * hyperlinks), so no `\u001B` remnant can reach a snackbar or an error line (SP §12).
 */
object AnsiStrip {
    /** Select Graphic Rendition: colours, bold, reset. `Regex("\u001B\\[[0-9;]*m")` per docs/07. */
    val SGR: Regex = Regex("\u001B\\[[0-9;]*m")

    private val CSI: Regex = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

    private val OSC: Regex = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    fun strip(text: String): String {
        if (text.indexOf('\u001B') < 0) return text
        return OSC.replace(CSI.replace(SGR.replace(text, ""), ""), "")
    }
}

fun String.stripAnsi(): String = AnsiStrip.strip(this)
