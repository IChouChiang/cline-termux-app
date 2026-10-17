package dev.cline.termux.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.cline.termux.data.tools.CommandOutputCap
import dev.cline.termux.data.tools.TermColor
import dev.cline.termux.data.tools.TermStyle
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalLine
import dev.cline.termux.data.tools.cellWidth
import dev.cline.termux.ui.theme.ClineColors

/**
 * The terminal's colours (DS §1.5): one ground and foreground for both themes and the 16
 * ANSI colours; built from [ClineColors] so nothing outside `ui/theme` names a colour.
 */
@Immutable
data class TerminalPalette(val foreground: Color, val background: Color, val ansi: List<Color>) {
    init {
        require(ansi.size == 16) { "the ANSI palette has 16 entries" }
    }

    companion object {
        fun from(colors: ClineColors) = TerminalPalette(colors.terminalForeground, colors.terminalBackground, colors.ansi)
    }
}

/**
 * ANSI SGR → [AnnotatedString] (CT B.4 parser table, DS §1.5). The escape interpretation
 * (cursor, `\r`, erase, OSC 8, chunk splitting) lives in [TerminalBuffer]; this object turns
 * its styled lines into Compose text:
 *
 * - 16 colours from the DS palette; **bold maps 0–7 to 8–15** and uses SemiBold (never
 *   Medium on mixed text, DS §2.3);
 * - 256 colours: 16–231 the 6×6×6 cube, 232–255 the grey ramp; truecolour as given;
 * - dim = alpha 0.6, italic, underline, strikethrough; inverse swaps foreground and
 *   background (defaults included);
 * - OSC 8 hyperlinks become [LinkAnnotation.Url] (opened through the platform's
 *   `UriHandler`).
 */
object AnsiParser {
    /** Lines longer than this many cells are clipped with "…" when wrapping is off (CT B.4). */
    const val MAX_UNWRAPPED_COLUMNS = 2_000

    /** Parses raw output (with escapes) into one [AnnotatedString], lines joined by `\n`. */
    fun parse(
        text: String,
        palette: TerminalPalette,
        capChars: Int = CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS,
    ): AnnotatedString {
        val snapshot = TerminalBuffer.parse(text, capChars)
        return buildAnnotatedString {
            snapshot.lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(toAnnotatedString(line, palette))
            }
        }
    }

    /**
     * One terminal line as styled text. With [maxColumns] set, a longer line is cut at that
     * many cells and ends with "…".
     */
    fun toAnnotatedString(line: TerminalLine, palette: TerminalPalette, maxColumns: Int? = null): AnnotatedString {
        val cut = maxColumns?.let { clipIndex(line.text, it) }
        val end = cut ?: line.text.length
        return buildAnnotatedString {
            var position = 0
            for (span in line.spans) {
                if (span.start >= end) break
                if (span.start > position) append(line.text, position, span.start)
                val spanEnd = minOf(span.end, end)
                appendStyled(line.text.substring(span.start, spanEnd), span.style, palette)
                position = spanEnd
            }
            if (position < end) append(line.text, position, end)
            if (cut != null) append(ELLIPSIS)
        }
    }

    private fun AnnotatedString.Builder.appendStyled(text: String, style: TermStyle, palette: TerminalPalette) {
        val spanStyle = spanStyleFor(style, palette)
        val link = style.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(style = spanStyle.merge(SpanStyle(textDecoration = TextDecoration.Underline))))) {
                append(text)
            }
        } else if (spanStyle == SpanStyle()) {
            append(text)
        } else {
            withStyle(spanStyle) { append(text) }
        }
    }

    /** The Compose style for one SGR state. Unset fields inherit the terminal foreground. */
    fun spanStyleFor(style: TermStyle, palette: TerminalPalette): SpanStyle {
        var fg: Color? = style.fg?.let { resolve(it, palette, style.bold) }
        var bg: Color? = style.bg?.let { resolve(it, palette, bold = false) }
        if (style.inverse) {
            val swappedFg = bg ?: palette.background
            val swappedBg = fg ?: palette.foreground
            fg = swappedFg
            bg = swappedBg
        }
        if (style.dim) fg = (fg ?: palette.foreground).copy(alpha = DIM_ALPHA)
        val decorations = buildList {
            if (style.underline) add(TextDecoration.Underline)
            if (style.strike) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            color = fg ?: Color.Unspecified,
            background = bg ?: Color.Unspecified,
            fontWeight = if (style.bold) FontWeight.SemiBold else null,
            fontStyle = if (style.italic) FontStyle.Italic else null,
            textDecoration = when (decorations.size) {
                0 -> null
                1 -> decorations[0]
                else -> TextDecoration.combine(decorations)
            },
        )
    }

    /** Resolves a [TermColor]; [bold] brightens the eight basic colours (0–7 → 8–15). */
    fun resolve(color: TermColor, palette: TerminalPalette, bold: Boolean): Color = when (color) {
        is TermColor.Indexed -> {
            val index = if (bold && color.index in 0..7) color.index + 8 else color.index
            indexed(index, palette)
        }
        is TermColor.Rgb -> rgb(color.rgb)
    }

    /** xterm 256-colour table with the DS palette for 0–15. */
    fun indexed(index: Int, palette: TerminalPalette): Color = when (index) {
        in 0..15 -> palette.ansi[index]
        in 16..231 -> {
            val n = index - 16
            rgb((CUBE[n / 36] shl 16) or (CUBE[(n / 6) % 6] shl 8) or CUBE[n % 6])
        }
        in 232..255 -> {
            val level = 8 + (index - 232) * 10
            rgb((level shl 16) or (level shl 8) or level)
        }
        else -> palette.foreground
    }

    private fun rgb(value: Int): Color = Color(
        red = (value shr 16) and 0xFF,
        green = (value shr 8) and 0xFF,
        blue = value and 0xFF,
    )

    /** The char index where the line passes [maxColumns] cells, or null when it fits. */
    internal fun clipIndex(text: String, maxColumns: Int): Int? {
        var columns = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = cellWidth(cp)
            if (columns + width > maxColumns) return i
            columns += width
            i += Character.charCount(cp)
        }
        return null
    }

    private const val DIM_ALPHA = 0.6f
    private const val ELLIPSIS = "…"
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)
}
