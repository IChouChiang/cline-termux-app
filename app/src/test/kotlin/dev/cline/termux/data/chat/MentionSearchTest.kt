package dev.cline.termux.data.chat

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C6: desktop `getActiveMention` / `insertMentionFile`, the ranking (MentionRankingTest, docs/07 §10.1) and the cache. */
class MentionSearchTest {

    @Test fun `mention opens at the start or after whitespace`() {
        assertEquals(ActiveMention(0, 1, ""), MentionTokens.getActiveMention("@", 1))
        assertEquals(ActiveMention(8, 13, "pars"), MentionTokens.getActiveMention("explain @pars", 13))
        assertNull(MentionTokens.getActiveMention("me@example.com", 14))
    }

    @Test fun `whitespace or a second at closes the token`() {
        assertNull(MentionTokens.getActiveMention("@src file", 9))
        assertNull(MentionTokens.getActiveMention("@a@b", 4))
        assertNull(MentionTokens.getActiveMention("@a", 3))
        assertNull(MentionTokens.getActiveMention("plain", 5))
    }

    @Test fun `insert replaces the token and puts the cursor after the space`() {
        val active = MentionTokens.getActiveMention("explain @pars", 13)!!
        assertEquals(TextEdit("explain @src/parser.ts ", 23), MentionTokens.insertMentionFile("explain @pars", active, "src/parser.ts"))
        val middle = MentionTokens.getActiveMention("see @p and more", 6)!!
        assertEquals(TextEdit("see @a/b.kt  and more", 12), MentionTokens.insertMentionFile("see @p and more", middle, "a/b.kt"))
    }

    // ---- ranking ------------------------------------------------------------------------------

    @Test fun `exact basename beats prefix beats contains beats path`() {
        val paths = listOf(
            "docs/parsing.md",
            "src/parser/tokens.ts",
            "test/parser.test.ts",
            "src/parser.ts",
            "lib/sparse.ts",
            "pars",
        )
        assertEquals(
            listOf("pars", "src/parser.ts", "docs/parsing.md", "test/parser.test.ts", "lib/sparse.ts", "src/parser/tokens.ts"),
            MentionRanking.rank(paths, "pars"),
        )
    }

    @Test fun `ties go to the shorter path, then alphabetical`() {
        assertEquals(listOf("a/x.kt", "b/x.kt", "long/x.kt"), MentionRanking.rank(listOf("long/x.kt", "b/x.kt", "a/x.kt"), "x.kt"))
    }

    @Test fun `ranking is case-insensitive and drops non-matches`() {
        assertEquals(listOf("src/Parser.kt"), MentionRanking.rank(listOf("src/Parser.kt", "README.md"), "PARSER"))
    }

    @Test fun `empty query takes the first ten by depth then name`() {
        val paths = (1..12).map { "a/b/f$it" } + listOf("z.md", "b/y.md", "a.md")
        assertEquals(listOf("a.md", "z.md", "b/y.md") + listOf("a/b/f1", "a/b/f10", "a/b/f11", "a/b/f12", "a/b/f2", "a/b/f3", "a/b/f4"), MentionRanking.rank(paths, ""))
    }

    @Test fun `leading dot-slash and blanks are cleaned`() {
        assertEquals(listOf("src/a.kt"), MentionRanking.rank(listOf("./src/a.kt", "", "src/a.kt"), "a"))
    }

    // ---- search + cache -----------------------------------------------------------------------

    private val label = ClineCommands.Labels.SEARCH_WORKSPACE_FILES

    @Test fun `search runs the documented argv, ranks and caches`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(label, TermuxResult.success("src/parser/tokens.ts\nsrc/parser.ts\n"))
        val search = MentionSearch(termux, backgroundScope)
        assertEquals(MentionResult.Files(listOf("src/parser.ts", "src/parser/tokens.ts")), search.search("/w", "parser"))
        val args = termux.commands.single().args
        assertEquals(listOf("-c", ClineCommands.SEARCH_WORKSPACE_FILES_SCRIPT, "_", "/w", "parser"), args)
        assertEquals(listOf("src/parser.ts", "src/parser/tokens.ts"), search.cached("/w", "parser"))
        search.search("/w", "parser")
        assertEquals(1, termux.commands.size)
    }

    @Test fun `failures map to problems and are not cached`() = runTest {
        val termux = FakeTermuxExecutor()
        val search = MentionSearch(termux, backgroundScope)
        termux.on(label, TermuxResult(stdout = "", stderr = "cd: no such dir", exitCode = 1, err = TermuxResult.ERR_NONE, errmsg = null))
        assertEquals(MentionResult.Failed(MentionProblem.Error), search.search("/w", "x"))
        assertNull(search.cached("/w", "x"))
        termux.throwing(label, TermuxUnavailable.PermissionMissing())
        assertEquals(MentionResult.Failed(MentionProblem.PermissionMissing), search.search("/w", "x"))
        termux.throwing(label, TermuxUnavailable.ExternalAppsBlocked("allow-external-apps"))
        assertEquals(MentionResult.Failed(MentionProblem.PermissionMissing), search.search("/w", "x"))
        termux.throwing(label, TermuxUnavailable.NotInstalled())
        assertEquals(MentionResult.Failed(MentionProblem.KernelDown), search.search("/w", "x"))
        termux.never(label)
        assertEquals(MentionResult.Failed(MentionProblem.Error), search.search("/w", "x"))
    }

    @Test fun `workspace change and clear empty the cache`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(label, TermuxResult.success("a.kt\n"))
        val search = MentionSearch(termux, backgroundScope)
        search.search("/w1", "a")
        assertEquals(listOf("a.kt"), search.cached("/w1", "a"))
        assertNull(search.cached("/w2", "a"))
        assertNull(search.cached("/w1", "a"))
        search.search("/w1", "a")
        search.clear()
        assertNull(search.cached("/w1", "a"))
    }

    @Test fun `a cancelled waiter still caches the result`() = runTest {
        val termux = FakeTermuxExecutor()
        val gate = CompletableDeferred<Unit>()
        termux.on(label) { gate.await(); TermuxResult.success("slow.kt\n") }
        val search = MentionSearch(termux, backgroundScope)
        val waiter = async { search.search("/w", "slow") }
        advanceUntilIdle()
        waiter.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("slow.kt"), search.cached("/w", "slow"))
    }
}
