package dev.cline.termux.data.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedDiffTextTest {
    private val note = "No hunk details available."

    @Test
    fun `fragment hunk keeps its stored starts and counts from the line diff`() {
        val file = SessionFileDiff(
            path = "app/Main.kt",
            additions = 2,
            deletions = 1,
            hunks = listOf(SessionDiffHunk(oldStart = 3, newStart = 3, old = "fun main() {\n  println(x)\n}", new = "fun main() {\n  println(x + y)\n  println(z)\n}")),
        )
        assertEquals(
            """
            --- a/app/Main.kt
            +++ b/app/Main.kt
            @@ -3,3 +3,4 @@
             fun main() {
            -  println(x)
            +  println(x + y)
            +  println(z)
             }

            """.trimIndent(),
            UnifiedDiffText.forFile(file, note),
        )
    }

    @Test
    fun `new file and file without hunks`() {
        val created = SessionFileDiff("hello.py", 2, 0, listOf(SessionDiffHunk(1, 1, "", "print(1)\nprint(2)")))
        val deleted = SessionFileDiff("old.txt", 0, 0, emptyList())
        assertEquals(
            "--- a/hello.py\n+++ b/hello.py\n@@ -1,0 +1,2 @@\n+print(1)\n+print(2)\n" +
                "--- a/old.txt\n+++ b/old.txt\n# No hunk details available.\n",
            UnifiedDiffText.forSession(listOf(created, deleted), note),
        )
    }

    @Test
    fun `identical hunks are skipped and long lines stay whole`() {
        val long = "x".repeat(10_000)
        val file = SessionFileDiff(
            "a.txt",
            1,
            0,
            listOf(SessionDiffHunk(1, 1, "same", "same"), SessionDiffHunk(5, 5, "", long)),
        )
        val text = UnifiedDiffText.forFile(file, note)
        assertEquals("--- a/a.txt\n+++ b/a.txt\n@@ -5,0 +5,1 @@\n+$long\n", text)
        assertNull(UnifiedDiffText.forHunk(SessionDiffHunk(1, 1, "same", "same")))
    }
}
