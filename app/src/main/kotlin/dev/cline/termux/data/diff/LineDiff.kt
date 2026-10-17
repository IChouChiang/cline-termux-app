package dev.cline.termux.data.diff

import androidx.compose.runtime.Immutable

/** What a rendered diff line is: unchanged context, a removal or an addition. */
enum class DiffLineKind { Context, Removed, Added }

/**
 * One line of a unified diff. [oldNumber] is set for context and removed lines,
 * [newNumber] for context and added lines (1-based, from the hunk's starts).
 */
@Immutable
data class DiffLine(val kind: DiffLineKind, val text: String, val oldNumber: Int?, val newNumber: Int?)

/** The unified line list of one [SessionDiffHunk], with the counts a `@@` header needs. */
@Immutable
data class HunkDiff(val lines: List<DiffLine>, val oldStart: Int, val newStart: Int) {
    val oldCount: Int get() = lines.count { it.kind != DiffLineKind.Added }
    val newCount: Int get() = lines.count { it.kind != DiffLineKind.Removed }
    val additions: Int get() = lines.count { it.kind == DiffLineKind.Added }
    val deletions: Int get() = lines.count { it.kind == DiffLineKind.Removed }
}

/**
 * Line diff for hunk rendering (CT A.4). The desktop renders hunks with `@pierre/diffs`
 * (`parseDiffFromFile`, which runs jsdiff's Myers line diff, unified style); this is the
 * pure Kotlin equivalent: a linear-space Myers diff (the bisect of Myers 1986 §4b) with
 * deletions placed before insertions inside each change block.
 */
object LineDiff {
    /** Above this many lines on either side the diff falls back to "all removed, then all added". */
    const val MAX_LINES = 2_000

    /**
     * Desktop `ensureTrailingNewline` + line split: empty text is zero lines (an empty side
     * diffs as nothing rather than one blank line), and a missing final newline is never
     * reported ("No newline at end of file" is noise for tool fragments).
     */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = if (text.endsWith("\n")) text else "$text\n"
        return normalized.substring(0, normalized.length - 1).split("\n")
    }

    /**
     * The unified diff of one hunk, or null when the two sides are identical (desktop
     * `ToolFileDiff` renders nothing for identical contents).
     */
    fun forHunk(hunk: SessionDiffHunk): HunkDiff? {
        val oldLines = splitLines(hunk.old)
        val newLines = splitLines(hunk.new)
        if (oldLines == newLines) return null
        return HunkDiff(number(myers(oldLines, newLines), hunk.oldStart, hunk.newStart), hunk.oldStart, hunk.newStart)
    }

    /** The edit script as unnumbered lines (numbers are added by [forHunk]). */
    fun myers(old: List<String>, new: List<String>): List<DiffLine> {
        if (old.size > MAX_LINES || new.size > MAX_LINES) {
            return old.map { DiffLine(DiffLineKind.Removed, it, null, null) } +
                new.map { DiffLine(DiffLineKind.Added, it, null, null) }
        }
        // Intern lines as ints so the inner loops compare ints, not strings.
        val ids = HashMap<String, Int>()
        val a = IntArray(old.size) { ids.getOrPut(old[it]) { ids.size } }
        val b = IntArray(new.size) { ids.getOrPut(new[it]) { ids.size } }
        val ops = ArrayList<Op>(old.size + new.size)
        diff(a, 0, a.size, b, 0, b.size, ops)
        return regroup(ops, old, new)
    }

    // region Myers

    private class Op(val kind: DiffLineKind, val aIndex: Int, val bIndex: Int)

    private fun diff(a: IntArray, aLo0: Int, aHi0: Int, b: IntArray, bLo0: Int, bHi0: Int, out: MutableList<Op>) {
        var aLo = aLo0
        var bLo = bLo0
        var aHi = aHi0
        var bHi = bHi0
        // Common prefix.
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
            out.add(Op(DiffLineKind.Context, aLo, bLo))
            aLo++
            bLo++
        }
        // Common suffix, emitted after the middle.
        var suffix = 0
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
            aHi--
            bHi--
            suffix++
        }
        when {
            aLo == aHi -> for (j in bLo until bHi) out.add(Op(DiffLineKind.Added, -1, j))
            bLo == bHi -> for (i in aLo until aHi) out.add(Op(DiffLineKind.Removed, i, -1))
            else -> {
                val split = bisect(a, aLo, aHi, b, bLo, bHi)
                if (split == null) {
                    for (i in aLo until aHi) out.add(Op(DiffLineKind.Removed, i, -1))
                    for (j in bLo until bHi) out.add(Op(DiffLineKind.Added, -1, j))
                } else {
                    diff(a, aLo, split.first, b, bLo, split.second, out)
                    diff(a, split.first, aHi, b, split.second, bHi, out)
                }
            }
        }
        for (k in 0 until suffix) out.add(Op(DiffLineKind.Context, aHi + k, bHi + k))
    }

    /**
     * Finds the middle snake of the ranges and returns the absolute split point, or null when
     * the ranges share nothing. The ranges have no common prefix or suffix here.
     */
    private fun bisect(a: IntArray, aLo: Int, aHi: Int, b: IntArray, bLo: Int, bHi: Int): Pair<Int, Int>? {
        val n = aHi - aLo
        val m = bHi - bLo
        val maxD = (n + m + 1) / 2
        val vOffset = maxD
        val vLength = 2 * maxD + 2
        val v1 = IntArray(vLength) { -1 }
        val v2 = IntArray(vLength) { -1 }
        v1[vOffset + 1] = 0
        v2[vOffset + 1] = 0
        val delta = n - m
        val front = delta % 2 != 0
        var k1start = 0
        var k1end = 0
        var k2start = 0
        var k2end = 0
        for (d in 0 until maxD) {
            var k1 = -d + k1start
            while (k1 <= d - k1end) {
                val k1Offset = vOffset + k1
                var x1 = if (k1 == -d || (k1 != d && v1[k1Offset - 1] < v1[k1Offset + 1])) {
                    v1[k1Offset + 1]
                } else {
                    v1[k1Offset - 1] + 1
                }
                var y1 = x1 - k1
                while (x1 < n && y1 < m && a[aLo + x1] == b[bLo + y1]) {
                    x1++
                    y1++
                }
                v1[k1Offset] = x1
                if (x1 > n) {
                    k1end += 2
                } else if (y1 > m) {
                    k1start += 2
                } else if (front) {
                    val k2Offset = vOffset + delta - k1
                    if (k2Offset in 0 until vLength && v2[k2Offset] != -1) {
                        val x2 = n - v2[k2Offset]
                        if (x1 >= x2) return (aLo + x1) to (bLo + y1)
                    }
                }
                k1 += 2
            }
            var k2 = -d + k2start
            while (k2 <= d - k2end) {
                val k2Offset = vOffset + k2
                var x2 = if (k2 == -d || (k2 != d && v2[k2Offset - 1] < v2[k2Offset + 1])) {
                    v2[k2Offset + 1]
                } else {
                    v2[k2Offset - 1] + 1
                }
                var y2 = x2 - k2
                while (x2 < n && y2 < m && a[aLo + n - x2 - 1] == b[bLo + m - y2 - 1]) {
                    x2++
                    y2++
                }
                v2[k2Offset] = x2
                if (x2 > n) {
                    k2end += 2
                } else if (y2 > m) {
                    k2start += 2
                } else if (!front) {
                    val k1Offset = vOffset + delta - k2
                    if (k1Offset in 0 until vLength && v1[k1Offset] != -1) {
                        val x1 = v1[k1Offset]
                        val y1 = vOffset + x1 - k1Offset
                        if (x1 >= n - x2) return (aLo + x1) to (bLo + y1)
                    }
                }
                k2 += 2
            }
        }
        return null
    }

    /** Unified order: inside each run of changes, every removal comes before every addition. */
    private fun regroup(ops: List<Op>, old: List<String>, new: List<String>): List<DiffLine> {
        val result = ArrayList<DiffLine>(ops.size)
        val removed = ArrayList<DiffLine>()
        val added = ArrayList<DiffLine>()
        fun flush() {
            result.addAll(removed)
            result.addAll(added)
            removed.clear()
            added.clear()
        }
        for (op in ops) {
            when (op.kind) {
                DiffLineKind.Context -> {
                    flush()
                    result.add(DiffLine(DiffLineKind.Context, old[op.aIndex], null, null))
                }
                DiffLineKind.Removed -> removed.add(DiffLine(DiffLineKind.Removed, old[op.aIndex], null, null))
                DiffLineKind.Added -> added.add(DiffLine(DiffLineKind.Added, new[op.bIndex], null, null))
            }
        }
        flush()
        return result
    }

    // endregion

    private fun number(lines: List<DiffLine>, oldStart: Int, newStart: Int): List<DiffLine> {
        var oldNo = oldStart
        var newNo = newStart
        return lines.map { line ->
            when (line.kind) {
                DiffLineKind.Context -> line.copy(oldNumber = oldNo++, newNumber = newNo++)
                DiffLineKind.Removed -> line.copy(oldNumber = oldNo++)
                DiffLineKind.Added -> line.copy(newNumber = newNo++)
            }
        }
    }
}
