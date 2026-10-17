package dev.cline.termux.data.diff

import dev.cline.termux.data.diff.DiffLineKind.Added
import dev.cline.termux.data.diff.DiffLineKind.Context
import dev.cline.termux.data.diff.DiffLineKind.Removed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LineDiffTest {

    private fun kinds(old: List<String>, new: List<String>) = LineDiff.myers(old, new).map { it.kind to it.text }

    /** Applying the script must rebuild both sides, and the script must be minimal. */
    private fun assertValidScript(old: List<String>, new: List<String>) {
        val script = LineDiff.myers(old, new)
        assertEquals(old, script.filter { it.kind != Added }.map { it.text })
        assertEquals(new, script.filter { it.kind != Removed }.map { it.text })
        assertEquals(lcsLength(old, new), script.count { it.kind == Context })
    }

    private fun lcsLength(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        return dp[0][0]
    }

    @Test
    fun `splitLines follows ensureTrailingNewline`() {
        assertEquals(emptyList<String>(), LineDiff.splitLines(""))
        assertEquals(listOf("a", "b"), LineDiff.splitLines("a\nb"))
        assertEquals(listOf("a"), LineDiff.splitLines("a\n"))
        assertEquals(listOf("a", ""), LineDiff.splitLines("a\n\n"))
        assertEquals(listOf(""), LineDiff.splitLines("\n"))
    }

    @Test
    fun `identical sides render nothing`() {
        assertNull(LineDiff.forHunk(SessionDiffHunk(1, 1, "same\n", "same")))
        assertNull(LineDiff.forHunk(SessionDiffHunk(1, 1, "", "")))
    }

    @Test
    fun `deletions come before insertions inside a change block`() {
        assertEquals(
            listOf(Context to "a", Removed to "b", Removed to "c", Added to "x", Added to "y", Context to "d"),
            kinds(listOf("a", "b", "c", "d"), listOf("a", "x", "y", "d")),
        )
    }

    @Test
    fun `pure insertions and deletions`() {
        assertEquals(listOf(Added to "1", Added to "2"), kinds(emptyList(), listOf("1", "2")))
        assertEquals(listOf(Removed to "1"), kinds(listOf("1"), emptyList()))
        assertEquals(
            listOf(Context to "a", Added to "b", Context to "c"),
            kinds(listOf("a", "c"), listOf("a", "b", "c")),
        )
    }

    @Test
    fun `numbers lines from the hunk starts`() {
        val diff = LineDiff.forHunk(SessionDiffHunk(oldStart = 10, newStart = 12, old = "a\nb\nc", new = "a\nB\nc\nd"))!!
        assertEquals(
            listOf(
                DiffLine(Context, "a", 10, 12),
                DiffLine(Removed, "b", 11, null),
                DiffLine(Added, "B", null, 13),
                DiffLine(Context, "c", 12, 14),
                DiffLine(Added, "d", null, 15),
            ),
            diff.lines,
        )
        assertEquals(3, diff.oldCount)
        assertEquals(4, diff.newCount)
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
    }

    @Test
    fun `complete new contents number every added line from one`() {
        val diff = LineDiff.forHunk(SessionDiffHunk(1, 1, "", "x\ny\n"))!!
        assertEquals(listOf(DiffLine(Added, "x", null, 1), DiffLine(Added, "y", null, 2)), diff.lines)
    }

    @Test
    fun `scripts are valid and minimal on classic and random inputs`() {
        assertValidScript("ABCABBA".map { it.toString() }, "CBABAC".map { it.toString() })
        assertValidScript(listOf("x"), listOf("y"))
        assertValidScript(listOf("a", "b"), listOf("b", "a"))
        val random = Random(7)
        repeat(200) {
            val old = List(random.nextInt(0, 30)) { "l${random.nextInt(0, 6)}" }
            val new = List(random.nextInt(0, 30)) { "l${random.nextInt(0, 6)}" }
            assertValidScript(old, new)
        }
    }

    @Test
    fun `falls back to remove-all then add-all above the size limit`() {
        val old = List(LineDiff.MAX_LINES + 1) { "line $it" }
        val new = listOf("only")
        val script = LineDiff.myers(old, new)
        assertEquals(old.size + 1, script.size)
        assertEquals(Removed, script.first().kind)
        assertEquals(Added to "only", script.last().kind to script.last().text)
    }

    @Test
    fun `handles the size limit itself without falling back`() {
        val old = List(LineDiff.MAX_LINES) { "line $it" }
        val new = old.toMutableList().apply { this[1000] = "changed" }
        val script = LineDiff.myers(old, new)
        assertEquals(LineDiff.MAX_LINES + 1, script.size)
        assertEquals(1, script.count { it.kind == Removed })
    }
}
