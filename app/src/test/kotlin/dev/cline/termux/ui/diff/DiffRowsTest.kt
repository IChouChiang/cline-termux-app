package dev.cline.termux.ui.diff

import dev.cline.termux.data.diff.DiffLineKind
import dev.cline.termux.data.diff.LineDiff
import dev.cline.termux.data.diff.SessionDiffHunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRowsTest {

    private fun hunk(old: List<String>, new: List<String>) =
        LineDiff.forHunk(SessionDiffHunk(1, 1, old.joinToString("\n"), new.joinToString("\n")))!!

    private fun shape(rows: List<DiffRow>): String = rows.joinToString("") {
        when (it) {
            is DiffRow.Collapsed -> "[${it.count}]"
            is DiffRow.Line -> when (it.line.kind) {
                DiffLineKind.Context -> "c"
                DiffLineKind.Added -> "+"
                DiffLineKind.Removed -> "-"
            }
        }
    }

    @Test
    fun `short context stays visible`() {
        val ctx = List(8) { "ctx $it" }
        val diff = hunk(listOf("a") + ctx + listOf("b"), listOf("A") + ctx + listOf("B"))
        assertEquals("-+cccccccc-+", shape(DiffRows.build(diff)))
    }

    @Test
    fun `long unchanged runs collapse beyond four lines of context`() {
        val ctx = List(12) { "ctx $it" }
        val diff = hunk(listOf("a") + ctx + listOf("b"), listOf("A") + ctx + listOf("B"))
        val rows = DiffRows.build(diff)
        assertEquals("-+cccc[4]cccc-+", shape(rows))
        val collapsed = rows.filterIsInstance<DiffRow.Collapsed>().single()
        assertEquals("-+cccccccccccc-+", shape(DiffRows.build(diff, setOf(collapsed.id))))
    }

    @Test
    fun `leading and trailing context collapse toward the change`() {
        val lead = List(6) { "lead $it" }
        val tail = List(5) { "tail $it" }
        val diff = hunk(lead + listOf("x") + tail, lead + listOf("y") + tail)
        assertEquals("[2]cccc-+cccc[1]", shape(DiffRows.build(diff)))
    }

    @Test
    fun `paths shrink from the middle and never cut the file name`() {
        assertEquals(
            listOf(
                "app/src/main/kotlin/Main.kt",
                "app/src/main/…/Main.kt",
                "app/src/…/Main.kt",
                "app/…/Main.kt",
                "…/Main.kt",
                "Main.kt",
            ),
            pathCandidates("app/src/main/kotlin/Main.kt"),
        )
        assertEquals(listOf("src/a.kt"), pathCandidates("src/a.kt"))
        assertEquals(listOf("a.kt"), pathCandidates("a.kt"))
        assertEquals("/…/y.kt", pathCandidates("/data/x/y.kt")[2])
        assertTrue(pathCandidates("C:\\ws\\src\\a.kt").contains("C:\\…\\a.kt"))
    }

    @Test
    fun `basename split`() {
        assertEquals("app/src/" to "Main.kt", splitBasename("app/src/Main.kt"))
        assertEquals("" to "Main.kt", splitBasename("Main.kt"))
        assertEquals("…/" to "Main.kt", splitBasename("…/Main.kt"))
    }

    @Test
    fun `display text replaces controls clips long lines and drops a trailing CR`() {
        assertEquals("a\tb\uFFFDc", DiffRows.displayText("a\tb\u0000c"))
        assertEquals("line", DiffRows.displayText("line\r"))
        val long = DiffRows.displayText("x".repeat(5_000))
        assertEquals(DiffRows.MAX_LINE_CHARS + 1, long.length)
        assertTrue(long.endsWith("…"))
    }
}
