package dev.cline.termux.data.tools

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Port of `apps/examples/desktop-app/webview/lib/command-output.ts`: the live command output
 * cap. Past the cap the **head** is dropped and the output restarts with the marker
 * `ESC[0m[Earlier command output truncated]\n` (CT §1.4, DS-A3).
 */
object CommandOutputCap {
    /** Inline tool row in the chat (desktop `MAX_LIVE_COMMAND_OUTPUT_CHARS`). */
    const val MAX_LIVE_COMMAND_OUTPUT_CHARS = 48_000

    /** Tool detail screen, per execution (CT §1.4 caps table). */
    const val MAX_DETAIL_COMMAND_OUTPUT_CHARS = 1_048_576

    const val COMMAND_OUTPUT_TRUNCATION_MARKER = "\u001b[0m[Earlier command output truncated]\n"

    data class Capped(val output: String, val truncated: Boolean)

    /** Desktop `appendCappedCommandOutput`, with the cap as a parameter for the detail buffer. */
    fun appendCapped(current: String, chunk: String, maxChars: Int = MAX_LIVE_COMMAND_OUTPUT_CHARS): Capped {
        val combined = current + chunk
        if (combined.length <= maxChars) {
            return Capped(combined, current.startsWith(COMMAND_OUTPUT_TRUNCATION_MARKER))
        }
        val tailLength = maxOf(0, maxChars - COMMAND_OUTPUT_TRUNCATION_MARKER.length)
        var tail = combined.substring(combined.length - tailLength)
        // Never start on the second half of a surrogate pair (JS slices code units too, but
        // a lone low surrogate renders as a replacement glyph).
        if (tail.isNotEmpty() && tail[0].isLowSurrogate()) tail = tail.substring(1)
        return Capped(COMMAND_OUTPUT_TRUNCATION_MARKER + tail, true)
    }
}

/** A terminal colour: one of the 256 indexed colours, or a 24-bit truecolour value. */
@Immutable
sealed interface TermColor {
    /** 0–15 are the ANSI palette (DS §1.5), 16–231 the 6×6×6 cube, 232–255 the grey ramp. */
    @Immutable
    data class Indexed(val index: Int) : TermColor

    /** `0xRRGGBB`. */
    @Immutable
    data class Rgb(val rgb: Int) : TermColor
}

/** The SGR attributes of a run of text; `null` colours mean the terminal default. */
@Immutable
data class TermStyle(
    val fg: TermColor? = null,
    val bg: TermColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strike: Boolean = false,
    /** OSC 8 hyperlink target. */
    val link: String? = null,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * Applies one SGR sequence. [params] holds one entry per `;`-separated parameter, each
     * with its `:` sub-parameters; `-1` is an omitted value (which SGR reads as 0).
     */
    fun applySgr(params: List<IntArray>): TermStyle {
        if (params.isEmpty()) return DEFAULT.copy(link = link)
        var style = this
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when (val code = p.getOrElse(0) { -1 }.coerceAtLeast(0)) {
                0 -> style = DEFAULT.copy(link = style.link)
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = p.size < 2 || p[1] != 0)
                7 -> style = style.copy(inverse = true)
                9 -> style = style.copy(strike = true)
                21 -> style = style.copy(underline = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                29 -> style = style.copy(strike = false)
                in 30..37 -> style = style.copy(fg = TermColor.Indexed(code - 30))
                39 -> style = style.copy(fg = null)
                in 40..47 -> style = style.copy(bg = TermColor.Indexed(code - 40))
                49 -> style = style.copy(bg = null)
                in 90..97 -> style = style.copy(fg = TermColor.Indexed(code - 90 + 8))
                in 100..107 -> style = style.copy(bg = TermColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val (color, consumed) = extendedColor(params, i)
                    if (color != null) style = if (code == 38) style.copy(fg = color) else style.copy(bg = color)
                    i += consumed
                }
            }
            i++
        }
        return style
    }

    companion object {
        val DEFAULT = TermStyle()

        /**
         * `38;5;n` / `38;2;r;g;b` (semicolon form, consumes the following parameters) or
         * `38:5:n` / `38:2::r:g:b` / `38:2:r:g:b` (colon form, inside one parameter).
         * Returns the colour (null when malformed) and how many extra parameters it used.
         */
        private fun extendedColor(params: List<IntArray>, at: Int): Pair<TermColor?, Int> {
            val p = params[at]
            if (p.size > 1) {
                val color = when (p[1]) {
                    5 -> p.getOrNull(2)?.let(::indexed)
                    2 -> if (p.size >= 6) rgb(p[3], p[4], p[5]) else if (p.size == 5) rgb(p[2], p[3], p[4]) else null
                    else -> null
                }
                return color to 0
            }
            val mode = params.getOrNull(at + 1)?.firstOrNull() ?: return null to 0
            return when (mode) {
                5 -> (params.getOrNull(at + 2)?.firstOrNull()?.let(::indexed)) to 2
                2 -> {
                    val r = params.getOrNull(at + 2)?.firstOrNull()
                    val g = params.getOrNull(at + 3)?.firstOrNull()
                    val b = params.getOrNull(at + 4)?.firstOrNull()
                    (if (r != null && g != null && b != null) rgb(r, g, b) else null) to 4
                }
                else -> null to 1
            }
        }

        private fun indexed(n: Int): TermColor? = if (n in 0..255) TermColor.Indexed(n) else null

        private fun rgb(r: Int, g: Int, b: Int): TermColor? {
            val values = intArrayOf(r.coerceAtLeast(0), g.coerceAtLeast(0), b.coerceAtLeast(0))
            if (values.any { it > 255 }) return null
            return TermColor.Rgb((values[0] shl 16) or (values[1] shl 8) or values[2])
        }
    }
}

/** A styled range `[start, end)` of a [TerminalLine]'s text. */
@Immutable
data class TermSpan(val start: Int, val end: Int, val style: TermStyle)

/**
 * One terminal line with its styled spans (default-styled ranges carry no span). Notice
 * rows (the truncation, skip, detach and leftover-children markers, CT B.4) are matched as
 * whole lines and excluded from copied output.
 */
@Immutable
data class TerminalLine(val text: String, val spans: List<TermSpan> = emptyList()) {
    /** Computed once, where the line is built (on `Dispatchers.Default`), not per frame. */
    val isNotice: Boolean = TerminalNotices.isNotice(text)

    /** Display width in terminal cells: East Asian wide characters count 2, combining marks 0. */
    val columns: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { displayColumns(text) }
}

/** The whole-line notices the terminal renders as notice rows (CT B.4). */
object TerminalNotices {
    const val EARLIER_TRUNCATED = "[Earlier command output truncated]"
    const val LEFTOVER_CHILDREN =
        "[Command completed with background processes still running; their output is no longer captured]"
    private val SKIPPED = Regex("^\\[\\d+ command-output characters skipped while streaming]$")
    private val DETACHED = Regex("^\\[Command is still running\\. Output will continue in .+]$")

    fun isNotice(line: String): Boolean {
        if (line.isEmpty() || line[0] != '[' || line[line.length - 1] != ']') return false
        return line == EARLIER_TRUNCATED || line == LEFTOVER_CHILDREN || SKIPPED.matches(line) || DETACHED.matches(line)
    }

    /** The notices the kernel writes only into the final result, never into streamed chunks. */
    fun isResultOnly(line: String): Boolean = line == LEFTOVER_CHILDREN || DETACHED.matches(line)
}

/**
 * An immutable view of a [TerminalBuffer]. [lines] includes the partial last line when it
 * has text; [endsWithNewline] says the cursor sits at the start of a fresh, empty line.
 * [headTruncated] means the buffer dropped its head at the cap and the view shows the
 * "[Earlier command output truncated]" notice row first.
 *
 * [firstLineIndex] is the absolute index of `lines[0]` in the buffer's output: the number of
 * whole lines the head cap has dropped since the start (or the last [TerminalBuffer.clear]).
 * `firstLineIndex + i` ([lineKey]) therefore names the same output line across snapshots
 * while the head drops, which is what the terminal list keys its rows on.
 */
@Immutable
class TerminalSnapshot(
    val lines: List<TerminalLine>,
    val headTruncated: Boolean,
    val endsWithNewline: Boolean,
    val version: Long,
    val firstLineIndex: Long = 0,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && !headTruncated

    /** The stable absolute index of `lines[index]` (see [firstLineIndex]). */
    fun lineKey(index: Int): Long = firstLineIndex + index

    /** Widest line in cells (for the wrap-off width); computed once per snapshot. */
    val maxColumns: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { lines.maxOfOrNull { it.columns } ?: 0 }

    /** The output as plain text without ANSI escapes or notice rows ("Copy output", CT B.4). */
    fun copyText(): String = lines.asSequence().filterNot { it.isNotice }.joinToString("\n") { it.text }

    /**
     * Structural, and cheap where it is called per frame (Compose keys and state policies):
     * the scalar fields first, then the lines from the end, where consecutive snapshots of a
     * growing output differ, with an identity check per line (kept lines are shared).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalSnapshot) return false
        if (other.version != version || other.headTruncated != headTruncated ||
            other.endsWithNewline != endsWithNewline || other.firstLineIndex != firstLineIndex ||
            other.lines.size != lines.size
        ) {
            return false
        }
        for (i in lines.indices.reversed()) {
            val mine = lines[i]
            val theirs = other.lines[i]
            if (mine !== theirs && mine != theirs) return false
        }
        return true
    }

    override fun hashCode(): Int = 31 * version.hashCode() + lines.size

    companion object {
        val EMPTY = TerminalSnapshot(emptyList(), headTruncated = false, endsWithNewline = false, version = 0)
    }
}

/**
 * The terminal line model behind command output (CT B.4): an incremental escape-sequence
 * interpreter over a head-capped list of lines. Not thread-safe; feed it from one coroutine
 * (see [TerminalFeed]) and hand out [snapshot]s.
 *
 * Handled: SGR (colours, 256, truecolour, attributes) into [TermStyle]; `ESC[K` / `1K` /
 * `2K` erase in line; `G`, `C`, `D` cursor moves within the current line; OSC 8 links;
 * `\r\n` newline, lone `\r` back to column 0 (overwrite, so progress bars update in place),
 * `\b` back one cell, `\t` to the next multiple of 8. Everything else (other CSI, other
 * OSC, DCS/APC/PM/SOS strings, charset selection, C0/C1 controls) is dropped. An escape
 * split across chunks is completed by the next [append]; CSI parameters are kept up to 64
 * characters and OSC payloads up to 2,048 (enough for OSC 8 URLs); longer sequences are
 * swallowed whole and ignored.
 *
 * Cap: when the stored text (lines joined by `\n`) exceeds [capChars], the head is dropped
 * so the kept tail plus the marker line fit the cap, the same arithmetic as the desktop's
 * `appendCappedCommandOutput`, and [TerminalSnapshot.headTruncated] turns on.
 */
class TerminalBuffer(val capChars: Int = CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS) {

    private val lines = ArrayDeque<TerminalLine>()
    private var committedChars = 0L

    private val current = StringBuilder()
    private var currentStyles = IntArray(64)
    private var column = 0

    private val styles = ArrayList<TermStyle>().apply { add(TermStyle.DEFAULT) }
    private val styleIds = HashMap<TermStyle, Int>().apply { put(TermStyle.DEFAULT, 0) }
    private var style = TermStyle.DEFAULT
    private var styleId = 0

    private var state = State.Ground
    private val sequence = StringBuilder()
    private var sequenceOverflow = false

    private var headTruncated = false
    private var droppedLines = 0L
    private var version = 0L

    /** Total characters held (lines joined with `\n`), the quantity the cap applies to. */
    val length: Long get() = committedChars + current.length

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        var sinceCapCheck = 0
        for (ch in chunk) {
            feed(ch)
            if (++sinceCapCheck >= CAP_CHECK_INTERVAL) {
                sinceCapCheck = 0
                enforceCap()
            }
        }
        enforceCap()
        version++
    }

    fun clear() {
        lines.clear()
        committedChars = 0
        current.setLength(0)
        column = 0
        style = TermStyle.DEFAULT
        styleId = 0
        state = State.Ground
        sequence.setLength(0)
        sequenceOverflow = false
        headTruncated = false
        droppedLines = 0
        version++
    }

    fun snapshot(): TerminalSnapshot {
        val out = ArrayList<TerminalLine>(lines.size + 1)
        out.addAll(lines)
        val partial = current.isNotEmpty()
        if (partial) out.add(currentLine())
        return TerminalSnapshot(
            lines = out,
            headTruncated = headTruncated,
            endsWithNewline = !partial && lines.isNotEmpty(),
            version = version,
            firstLineIndex = droppedLines,
        )
    }

    // region interpreter

    private enum class State { Ground, Escape, Csi, Osc, OscEscape, IgnoredString, IgnoredStringEscape, Charset }

    private fun feed(ch: Char) {
        when (state) {
            State.Ground -> ground(ch)
            State.Escape -> escape(ch)
            State.Csi -> csi(ch)
            State.Osc -> when (ch) {
                BEL -> endOsc()
                ESC -> state = State.OscEscape
                else -> collect(ch, OSC_LIMIT)
            }
            State.OscEscape -> if (ch == '\\') {
                endOsc()
            } else {
                // Not a string terminator: the OSC is abandoned and this ESC starts anew.
                resetSequence()
                state = State.Escape
                escape(ch)
            }
            State.IgnoredString -> when (ch) {
                BEL, ST_C1 -> state = State.Ground
                ESC -> state = State.IgnoredStringEscape
                else -> Unit
            }
            State.IgnoredStringEscape -> state = if (ch == '\\') State.Ground else State.IgnoredString
            State.Charset -> state = State.Ground
        }
    }

    private fun ground(ch: Char) {
        when {
            ch == ESC -> state = State.Escape
            ch == '\n' -> newLine()
            ch == '\r' -> column = 0
            ch == '\b' -> if (column > 0) column--
            ch == '\t' -> column = (column / TAB_WIDTH + 1) * TAB_WIDTH
            ch == CSI_C1 -> beginSequence(State.Csi)
            ch == OSC_C1 -> beginSequence(State.Osc)
            ch == DCS_C1 || ch == SOS_C1 || ch == PM_C1 || ch == APC_C1 -> state = State.IgnoredString
            ch < ' ' || ch == '\u007F' || ch in '\u0080'..'\u009F' -> Unit
            else -> put(ch)
        }
    }

    private fun escape(ch: Char) {
        when (ch) {
            '[' -> beginSequence(State.Csi)
            ']' -> beginSequence(State.Osc)
            'P', 'X', '^', '_' -> state = State.IgnoredString
            '(', ')', '*', '+', '-', '.', '/', '#', '%', ' ' -> state = State.Charset
            ESC -> state = State.Escape
            else -> state = State.Ground
        }
    }

    private fun beginSequence(next: State) {
        resetSequence()
        state = next
    }

    private fun resetSequence() {
        sequence.setLength(0)
        sequenceOverflow = false
    }

    private fun collect(ch: Char, limit: Int) {
        if (sequenceOverflow) return
        if (sequence.length >= limit) {
            sequenceOverflow = true
            sequence.setLength(0)
            return
        }
        sequence.append(ch)
    }

    private fun csi(ch: Char) {
        when {
            ch in '@'..'~' -> {
                state = State.Ground
                if (!sequenceOverflow) dispatchCsi(sequence.toString(), ch)
                resetSequence()
            }
            ch == ESC -> {
                resetSequence()
                state = State.Escape
            }
            ch == '\n' || ch == '\r' -> {
                // A broken sequence: drop it and keep the line structure intact.
                resetSequence()
                state = State.Ground
                ground(ch)
            }
            else -> collect(ch, CSI_LIMIT)
        }
    }

    private fun dispatchCsi(params: String, final: Char) {
        val private = params.isNotEmpty() && params[0] in "<=>?"
        when (final) {
            'm' -> if (!private && params.none { it in " !\"#$%&'()*+,-./" }) setStyle(style.applySgr(parseParams(params)))
            'K' -> if (!private) eraseInLine(firstParam(params, 0))
            'G' -> if (!private) column = (firstParam(params, 1).coerceAtLeast(1) - 1).coerceAtMost(MAX_COLUMN)
            'C' -> if (!private) column = (column + firstParam(params, 1).coerceAtLeast(1)).coerceAtMost(MAX_COLUMN)
            'D' -> if (!private) column = (column - firstParam(params, 1).coerceAtLeast(1)).coerceAtLeast(0)
            else -> Unit
        }
    }

    private fun endOsc() {
        val payload = if (sequenceOverflow) null else sequence.toString()
        resetSequence()
        state = State.Ground
        if (payload == null || !payload.startsWith("8;")) return
        // OSC 8 ; params ; URI  — an empty URI closes the link.
        val uriStart = payload.indexOf(';', startIndex = 2)
        if (uriStart < 0) return
        val uri = payload.substring(uriStart + 1)
        setStyle(style.copy(link = uri.ifEmpty { null }))
    }

    private fun setStyle(next: TermStyle) {
        if (next == style) return
        style = next
        styleId = styleIds.getOrPut(next) {
            styles.add(next)
            styles.lastIndex
        }
    }

    // endregion

    // region line model

    private fun put(ch: Char) {
        if (column > current.length) {
            val pad = column - current.length
            ensureStyleCapacity(column + 1)
            repeat(pad) {
                currentStyles[current.length] = 0
                current.append(' ')
            }
        }
        if (column < current.length) {
            current.setCharAt(column, ch)
        } else {
            ensureStyleCapacity(current.length + 1)
            current.append(ch)
        }
        currentStyles[column] = styleId
        column++
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> if (column < current.length) current.setLength(column)
            1 -> {
                val end = minOf(column + 1, current.length)
                for (i in 0 until end) {
                    current.setCharAt(i, ' ')
                    currentStyles[i] = 0
                }
            }
            2 -> current.setLength(0)
        }
    }

    private fun newLine() {
        val line = currentLine()
        lines.addLast(line)
        committedChars += line.text.length + 1
        current.setLength(0)
        column = 0
    }

    private fun ensureStyleCapacity(size: Int) {
        if (size > currentStyles.size) {
            currentStyles = currentStyles.copyOf(maxOf(size, currentStyles.size * 2))
        }
    }

    private fun currentLine(): TerminalLine {
        val text = current.toString()
        if (text.isEmpty()) return TerminalLine(text)
        val spans = ArrayList<TermSpan>()
        var runStart = 0
        var runId = currentStyles[0]
        for (i in 1..text.length) {
            val id = if (i < text.length) currentStyles[i] else -1
            if (id != runId) {
                if (runId != 0) spans.add(TermSpan(runStart, i, styles[runId]))
                runStart = i
                runId = id
            }
        }
        return TerminalLine(text, spans)
    }

    private fun enforceCap() {
        if (length <= capChars) return
        val keep = (capChars - MARKER_LINE_LENGTH).coerceAtLeast(0).toLong()
        var excess = length - keep
        while (excess > 0 && lines.isNotEmpty()) {
            val first = lines.first()
            val size = first.text.length + 1L
            if (size <= excess) {
                lines.removeFirst()
                droppedLines++
                committedChars -= size
                excess -= size
            } else {
                val trimmed = dropHead(first, excess.toInt())
                committedChars -= first.text.length - trimmed.text.length
                lines[0] = trimmed
                excess = 0
            }
        }
        if (excess > 0) {
            var drop = minOf(excess.toInt(), current.length)
            if (drop < current.length && current[drop].isLowSurrogate()) drop++
            current.delete(0, drop)
            System.arraycopy(currentStyles, drop, currentStyles, 0, current.length)
            column = (column - drop).coerceAtLeast(0)
        }
        headTruncated = true
    }

    private fun dropHead(line: TerminalLine, count: Int): TerminalLine {
        var drop = count.coerceAtMost(line.text.length)
        if (drop < line.text.length && line.text[drop].isLowSurrogate()) drop++
        val text = line.text.substring(drop)
        val spans = line.spans.mapNotNull { span ->
            val start = (span.start - drop).coerceAtLeast(0)
            val end = span.end - drop
            if (end <= 0) null else TermSpan(start, end, span.style)
        }
        return TerminalLine(text, spans)
    }

    // endregion

    companion object {
        /** Notice line "[Earlier command output truncated]" plus its newline. */
        private val MARKER_LINE_LENGTH = TerminalNotices.EARLIER_TRUNCATED.length + 1
        private const val CAP_CHECK_INTERVAL = 65_536
        private const val CSI_LIMIT = 64
        private const val OSC_LIMIT = 2_048
        private const val TAB_WIDTH = 8
        private const val MAX_COLUMN = 65_535

        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
        private const val CSI_C1 = '\u009B'
        private const val OSC_C1 = '\u009D'
        private const val DCS_C1 = '\u0090'
        private const val SOS_C1 = '\u0098'
        private const val PM_C1 = '\u009E'
        private const val APC_C1 = '\u009F'
        private const val ST_C1 = '\u009C'

        /** Parses a whole output string (history, a finished buffer) into a snapshot. */
        fun parse(text: String, capChars: Int = CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS): TerminalSnapshot =
            TerminalBuffer(capChars).apply { append(text) }.snapshot()

        internal fun parseParams(params: String): List<IntArray> {
            if (params.isEmpty()) return emptyList()
            return params.split(';').map { part ->
                val subs = part.split(':')
                IntArray(subs.size) { subs[it].toIntOrNull()?.coerceIn(0, 65_535) ?: -1 }
            }
        }

        private fun firstParam(params: String, default: Int): Int {
            val first = params.substringBefore(';').substringBefore(':')
            return first.toIntOrNull()?.takeIf { it > 0 || default == 0 } ?: default
        }
    }
}

/**
 * Display width in terminal cells (CT B.4 wrap-off width): East Asian wide and fullwidth
 * characters and emoji count 2, combining marks and zero-width characters 0, the rest 1.
 */
fun displayColumns(text: String): Int {
    var columns = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        columns += cellWidth(cp)
    }
    return columns
}

/** The cell width of one code point (0, 1 or 2). */
fun cellWidth(cp: Int): Int {
    if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return 0
    when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt() -> return 0
    }
    return if (isWide(cp)) 2 else 1
}

private fun isWide(cp: Int): Boolean =
    cp in 0x1100..0x115F ||
        cp in 0x2E80..0x303E ||
        cp in 0x3041..0x33FF ||
        cp in 0x3400..0x4DBF ||
        cp in 0x4E00..0x9FFF ||
        cp in 0xA000..0xA4CF ||
        cp in 0xAC00..0xD7A3 ||
        cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F ||
        cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 ||
        cp in 0x1F300..0x1F64F ||
        cp in 0x1F900..0x1F9FF ||
        cp in 0x20000..0x3FFFD

/**
 * Feeds a [TerminalBuffer] from any thread and publishes [snapshots] at most every
 * [periodMillis] (50 ms, docs/07 §8: `TerminalBuffer` appends run on `Dispatchers.Default`).
 * The first chunk after a quiet period is published at once; chunks that arrive during the
 * wait are applied together and published as one snapshot, and the last state is always
 * published. [close] lets the queued chunks drain; [cancel] stops at once.
 */
class TerminalFeed(
    scope: CoroutineScope,
    capChars: Int = CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val periodMillis: Long = CONFLATION_MILLIS,
) {
    private val buffer = TerminalBuffer(capChars)
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val state = MutableStateFlow(TerminalSnapshot.EMPTY)

    val snapshots: StateFlow<TerminalSnapshot> = state.asStateFlow()

    private val job: Job = scope.launch(dispatcher) {
        for (first in chunks) {
            buffer.append(first)
            while (true) {
                val next = chunks.tryReceive().getOrNull() ?: break
                buffer.append(next)
            }
            state.value = buffer.snapshot()
            delay(periodMillis)
        }
    }

    /** Thread-safe and non-suspending (OkHttp and reducer threads may call it). */
    fun append(chunk: String) {
        if (chunk.isNotEmpty()) chunks.trySend(chunk)
    }

    fun close() {
        chunks.close()
    }

    fun cancel() {
        chunks.cancel()
        job.cancel()
    }

    companion object {
        const val CONFLATION_MILLIS = 50L
    }
}

/**
 * Conflates a fast flow to at most one value per [periodMillis]: the first value passes at
 * once, values arriving during the wait collapse to the latest, and the last value is
 * always delivered. Used for streamed command output (50 ms).
 */
fun <T> Flow<T>.conflateEvery(periodMillis: Long = TerminalFeed.CONFLATION_MILLIS): Flow<T> =
    conflate().transform {
        emit(it)
        delay(periodMillis)
    }
