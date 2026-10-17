package dev.cline.termux.ui.diff

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.diff.DiffLine
import dev.cline.termux.data.diff.DiffLineKind
import dev.cline.termux.data.diff.HunkDiff

/** One rendered row of a hunk: a diff line, or a collapsed run of unchanged lines. */
@Immutable
sealed interface DiffRow {
    @Immutable
    data class Line(val line: DiffLine) : DiffRow

    /** [count] unchanged lines starting at line index [id] of the hunk, hidden until expanded. */
    @Immutable
    data class Collapsed(val id: Int, val count: Int) : DiffRow
}

/**
 * The hunk's rows with long unchanged stretches collapsed, as `@pierre/diffs` renders a
 * jsdiff patch: [CONTEXT_LINES] unchanged lines stay visible around every change and the
 * rest of an unchanged run becomes one "N unmodified lines" separator (collapse threshold
 * 1, so even a single hidden line collapses). [expanded] holds the ids of separators the
 * user opened.
 */
object DiffRows {
    /** jsdiff's default patch context, used by `parseDiffFromFile`. */
    const val CONTEXT_LINES = 4

    /** Long hunks render this many rows at first, then this many more per "Show more" (CT A.4). */
    const val PAGE_ROWS = 400

    /** Very long lines are clipped in the hunk view (the share text keeps them whole, CT A.9). */
    const val MAX_LINE_CHARS = 4_000

    fun build(diff: HunkDiff, expanded: Set<Int> = emptySet()): List<DiffRow> {
        val lines = diff.lines
        val rows = ArrayList<DiffRow>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind != DiffLineKind.Context) {
                rows.add(DiffRow.Line(lines[i]))
                i++
                continue
            }
            val start = i
            while (i < lines.size && lines[i].kind == DiffLineKind.Context) i++
            val end = i
            val leading = start == 0
            val trailing = end == lines.size
            val hiddenStart = if (leading) start else start + CONTEXT_LINES
            val hiddenEnd = if (trailing) end else end - CONTEXT_LINES
            if (leading && trailing || hiddenEnd - hiddenStart < 1 || hiddenStart in expanded) {
                for (k in start until end) rows.add(DiffRow.Line(lines[k]))
                continue
            }
            for (k in start until hiddenStart) rows.add(DiffRow.Line(lines[k]))
            rows.add(DiffRow.Collapsed(hiddenStart, hiddenEnd - hiddenStart))
            for (k in hiddenEnd until end) rows.add(DiffRow.Line(lines[k]))
        }
        return rows
    }

    /**
     * Display text of a diff line (CT A.9): a trailing `\r` dropped, control characters other
     * than tab replaced with U+FFFD, and the line clipped to [MAX_LINE_CHARS] with "…".
     */
    fun displayText(text: String): String {
        val trimmed = text.removeSuffix("\r")
        val clipped = if (trimmed.length > MAX_LINE_CHARS) {
            var cut = MAX_LINE_CHARS
            if (trimmed[cut - 1].isHighSurrogate()) cut--
            trimmed.substring(0, cut) + ELLIPSIS
        } else {
            trimmed
        }
        if (clipped.none { it.isReplaceable() }) return clipped
        return buildString(clipped.length) {
            for (ch in clipped) append(if (ch.isReplaceable()) REPLACEMENT else ch)
        }
    }

    private fun Char.isReplaceable(): Boolean = (this < ' ' && this != '\t') || this == '\u007F' || this in '\u0080'..'\u009F'

    private const val REPLACEMENT = '�'
    private const val ELLIPSIS = "…"
}

/**
 * Middle-ellipsis candidates for a path, widest first (CT A.4): the full path, then whole
 * segments removed before the file name (`app/src/…/Main.kt`, `app/…/Main.kt`), then
 * `…/Main.kt`, then the bare file name. The file name itself is never cut.
 */
fun pathCandidates(path: String): List<String> {
    val separator = if (path.contains('/') || !path.contains('\\')) '/' else '\\'
    val segments = path.split(separator)
    if (segments.size <= 2) return listOf(path)
    val basename = segments.last()
    val out = ArrayList<String>(segments.size + 1)
    out.add(path)
    val sep = separator.toString()
    for (keep in segments.size - 2 downTo 1) {
        out.add(segments.subList(0, keep).joinToString(sep) + sep + "…" + sep + basename)
    }
    out.add("…$sep$basename")
    out.add(basename)
    return out.distinct()
}

/** Splits a path into its directory part (with the trailing separator) and its file name. */
fun splitBasename(path: String): Pair<String, String> {
    val index = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (index < 0) "" to path else path.substring(0, index + 1) to path.substring(index + 1)
}
