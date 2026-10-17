package dev.cline.termux.ui.suggestions

import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.MentionProblem
import dev.cline.termux.data.chat.MentionSearch
import dev.cline.termux.data.chat.SlashCommandSource
import dev.cline.termux.data.chat.TextEdit
import dev.cline.termux.fakes.FakeTermuxExecutor
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionMenuControllerTest {

    private val root = "/home/proj"
    private val searchLabel = ClineCommands.Labels.SEARCH_WORKSPACE_FILES

    private class Harness(val controller: SuggestionMenuController, val termux: FakeTermuxExecutor, val settingsCalls: () -> Int)

    private fun TestScope.harness(
        settings: suspend () -> JsonObject = { settingsReply() },
        context: SuggestionContext = SuggestionContext("/home/proj", SuggestionKernel.Up),
    ): Harness {
        var calls = 0
        val termux = FakeTermuxExecutor()
        val source = SlashCommandSource(settingsList = { calls++; settings() }, termux = termux)
        val mention = MentionSearch(termux, backgroundScope)
        val controller = SuggestionMenuController(source, mention, backgroundScope, Collator.getInstance(Locale.ENGLISH))
        controller.setContext(context)
        return Harness(controller, termux) { calls }
    }

    private fun slash(h: Harness) = h.controller.state.value as SuggestionMenuState.Slash
    private fun mention(h: Harness) = h.controller.state.value as SuggestionMenuState.Mention

    @Test fun `typing a slash shows built-ins at once, then the loaded commands`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(settings = { gate.await(); settingsReply() })
        h.controller.update("/", 1)
        runCurrent()
        assertEquals(listOf("team"), slash(h).items.map { it.name })
        assertTrue(slash(h).loading)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("team", "code-review", "release-notes"), slash(h).items.map { it.name })
        assertFalse(slash(h).loading)
    }

    @Test fun `typing filters, no match, and a whitespace closes`() = runTest {
        val h = harness()
        h.controller.update("/", 1)
        settle()
        h.controller.update("/te", 3)
        assertEquals(listOf("team", "release-notes"), slash(h).items.map { it.name })
        h.controller.update("/zz", 3)
        assertTrue(slash(h).items.isEmpty())
        h.controller.update("/zz ", 4)
        assertNull(h.controller.state.value)
    }

    @Test fun `each open refreshes, typing within one open does not`() = runTest {
        val h = harness()
        h.controller.update("/", 1)
        settle()
        h.controller.update("/c", 2)
        settle()
        assertEquals(1, h.settingsCalls())
        h.controller.update("", 0)
        h.controller.update("/", 1)
        settle()
        assertEquals(2, h.settingsCalls())
    }

    @Test fun `kernel down shows built-ins only without a loading row`() = runTest {
        val h = harness(context = SuggestionContext(root, SuggestionKernel.Down))
        h.controller.update("/", 1)
        runCurrent()
        assertEquals(listOf("team"), slash(h).items.map { it.name })
        assertFalse(slash(h).loading)
        assertEquals(0, h.settingsCalls())
    }

    @Test fun `settings failure keeps built-ins and drops the loading row`() = runTest {
        val h = harness(settings = { error("settings_list_failed") })
        h.controller.update("/", 1)
        settle()
        assertEquals(listOf("team"), slash(h).items.map { it.name })
        assertFalse(slash(h).loading)
    }

    @Test fun `selecting a command inserts it and closes the panel`() = runTest {
        val h = harness()
        h.controller.update("/rel", 4)
        settle()
        val edit = h.controller.select(0)
        assertEquals(TextEdit("/release-notes ", 15), edit)
        assertNull(h.controller.state.value)
    }

    @Test fun `back dismisses for this token only`() = runTest {
        val h = harness()
        h.controller.update("/t", 2)
        settle()
        assertTrue(h.controller.dismiss())
        assertNull(h.controller.state.value)
        h.controller.update("/t", 2)
        assertNull(h.controller.state.value)
        assertFalse(h.controller.dismiss())
        h.controller.update("/te", 3)
        assertEquals("te", slash(h).query)
    }

    @Test fun `highlight moves with wrap and accept inserts it`() = runTest {
        val h = harness()
        h.controller.update("/", 1)
        settle()
        h.controller.moveHighlight(-1)
        assertEquals(2, slash(h).highlighted)
        h.controller.moveHighlight(1)
        assertEquals(0, slash(h).highlighted)
        h.controller.moveHighlight(1)
        assertEquals(TextEdit("/code-review ", 13), h.controller.acceptHighlighted())
    }

    @Test fun `settings changed refreshes an open panel`() = runTest {
        var withDeploy = false
        val termux = FakeTermuxExecutor()
        val source = SlashCommandSource(
            settingsList = { if (withDeploy) settingsReply(workflows = listOf(item("w2", "deploy", "workflow"))) else settingsReply() },
            termux = termux,
        )
        val controller = SuggestionMenuController(source, MentionSearch(termux, backgroundScope), backgroundScope)
        controller.setContext(SuggestionContext(root, SuggestionKernel.Up))
        controller.update("/dep", 4)
        settle()
        assertTrue((controller.state.value as SuggestionMenuState.Slash).items.isEmpty())
        withDeploy = true
        source.invalidate()
        settle()
        assertEquals(listOf("deploy"), (controller.state.value as SuggestionMenuState.Slash).items.map { it.name })
    }

    // ---- mentions -----------------------------------------------------------------------------

    @Test fun `mention searches after the debounce and caches per query`() = runTest {
        val h = harness()
        h.termux.on(searchLabel, TermuxResult.success("src/parser.ts\ndocs/parsing.md\n"))
        h.controller.update("explain @pars", 13)
        runCurrent()
        assertTrue(mention(h).loading)
        advanceTimeBy(MentionSearch.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(h.termux.commands.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("src/parser.ts", "docs/parsing.md"), mention(h).items)
        assertFalse(mention(h).loading)
        // Leave and come back: served from the cache, no second command.
        h.controller.update("explain ", 8)
        h.controller.update("explain @pars", 13)
        runCurrent()
        assertEquals(listOf("src/parser.ts", "docs/parsing.md"), mention(h).items)
        assertEquals(1, h.termux.commands.size)
    }

    @Test fun `a newer query keeps old results while refreshing`() = runTest {
        val h = harness()
        h.termux.on(searchLabel, TermuxResult.success("src/parser.ts\n"))
        h.controller.update("@p", 2)
        settle()
        h.controller.update("@pa", 3)
        runCurrent()
        assertTrue(mention(h).refreshing)
        assertEquals(listOf("src/parser.ts"), mention(h).items)
        settle()
        assertFalse(mention(h).refreshing)
    }

    @Test fun `mention insert puts the path and a space`() = runTest {
        val h = harness()
        h.termux.on(searchLabel, TermuxResult.success("src/parser.ts\n"))
        h.controller.update("explain @pars", 13)
        settle()
        assertEquals(TextEdit("explain @src/parser.ts ", 23), h.controller.select(0))
        assertNull(h.controller.state.value)
    }

    @Test fun `mention problems come from the context`() = runTest {
        val h = harness(context = SuggestionContext(null, SuggestionKernel.Up))
        h.controller.update("@", 1)
        assertEquals(MentionProblem.NoWorkspace, mention(h).problem)
        h.controller.setContext(SuggestionContext(root, SuggestionKernel.Down))
        assertEquals(MentionProblem.KernelDown, mention(h).problem)
        h.controller.setContext(SuggestionContext(root, KernelGate.Blocked(CheckId.Permission).toSuggestionKernel()))
        assertEquals(MentionProblem.PermissionMissing, mention(h).problem)
        assertEquals(0, mention(h).rowCount)
        h.termux.on(searchLabel, TermuxResult.success("a.kt\n"))
        h.controller.setContext(SuggestionContext(root, SuggestionKernel.Up))
        settle()
        assertNull(mention(h).problem)
        assertEquals(listOf("a.kt"), mention(h).items)
    }

    @Test fun `search error keeps cached rows above the error`() = runTest {
        val h = harness()
        h.termux.on(searchLabel, TermuxResult.success("src/a.kt\n"))
        h.controller.update("@a", 2)
        settle()
        h.termux.on(searchLabel, TermuxResult(stdout = "", stderr = "boom", exitCode = 2, err = TermuxResult.ERR_NONE, errmsg = null))
        h.controller.update("@ab", 3)
        settle()
        assertEquals(MentionProblem.Error, mention(h).problem)
        assertEquals(listOf("src/a.kt"), mention(h).items)
        assertEquals(1, mention(h).rowCount)
    }

    @Test fun `kernel gate maps to the menu kernel`() {
        assertEquals(SuggestionKernel.Up, KernelGate.Ready.toSuggestionKernel())
        assertEquals(SuggestionKernel.NoCommands, KernelGate.DegradedNoCommands.toSuggestionKernel())
        assertEquals(SuggestionKernel.NoCommands, KernelGate.Blocked(CheckId.ExternalApps).toSuggestionKernel())
        assertEquals(SuggestionKernel.Down, KernelGate.Blocked(CheckId.Termux).toSuggestionKernel())
        assertEquals(SuggestionKernel.Down, KernelGate.Down(KernelGate.DownVariant.Asleep, null).toSuggestionKernel())
        assertEquals(SuggestionKernel.Busy, KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false).toSuggestionKernel())
    }

    private companion object {
        fun settingsReply(
            skills: List<JsonObject> = listOf(item("s1", "code-review", "skill")),
            workflows: List<JsonObject> = listOf(item("w1", "release-notes", "workflow")),
        ): JsonObject = buildJsonObject {
            put("snapshot", buildJsonObject {
                put("skills", JsonArray(skills))
                put("workflows", JsonArray(workflows))
                put("tools", JsonArray(emptyList()))
            })
        }

        fun item(id: String, name: String, kind: String): JsonObject = buildJsonObject {
            put("id", id)
            put("name", name)
            put("path", "/p/$id")
            put("kind", kind)
            put("source", "global")
        }
    }

    /** Background-scope work is not counted by `advanceUntilIdle`; advance virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }
}
