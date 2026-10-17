package dev.cline.termux.ui.chat

import dev.cline.termux.data.catalog.ProviderUsageCost
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.session.LiveProjection
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.ui.chat.rows.TranscriptRowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A11.9 in the transcript pipeline: the controller re-projects when the mode changes; Brief
 * leaves the recoverable-error notices out and carries no stats, Verbose shows the notices
 * and hands the final answer of a finished turn its stats (by renderKey, then by history id).
 * The recovery notices are synthetic rows: no current kernel emits them (see
 * `ChatProjector.RECOVERABLE_ERROR_REASONS`), so on a real Hub only the stats differ. Turns
 * without a record get the totals the kernel persisted in history.
 */
class TranscriptMessageDetailsTest {
    private val sid = "s1"
    private val t0 = 1_791_064_689_782L

    private val strings = TranscriptStrings(
        runError = RunErrorStrings(
            runFailedFormat = "The run failed: %1\$s",
            runFailedEmpty = "The run failed before a response was produced.",
            hintLocalCliFormat = "Sign in again with the `%1\$s` CLI in a terminal, then try again.",
            hintCline = "Sign in to Cline again in Settings → Account, then try again.",
            hintUnknownAuth = "Sign in again using your provider's authentication method, then try again.",
            hintProviders = "Check your model connection in Settings → Providers (or sign in with Cline), then try again.",
        ),
        startErrorFormat = "Couldn't start the session: %1\$s",
        resumeErrorFormat = "Couldn't resume this session: %1\$s",
        historyErrorFormat = "Couldn't load this session: %1\$s",
        reasonText = emptyMap(),
    )

    private fun msg(id: String, role: Role, content: String, at: Long, renderKey: String = id, meta: Meta = Meta()) =
        ChatMessage(id = id, sessionId = sid, role = role, content = content, createdAt = at, renderKey = renderKey, meta = meta)

    private val stats = TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120)

    private val canonical = listOf(
        msg("h:u1", Role.User, "Read the README", t0),
        msg("h:a1:0", Role.Assistant, "It describes the app.", t0 + 3_000, renderKey = "a:r1:1:0"),
    )
    private val live = listOf(
        msg("n:7", Role.Status, "Tool execution failed; retrying", t0 + 1_000, meta = Meta(noticeType = "recovery", reason = "tool_execution_failed")),
        msg("n:8", Role.Status, "Session resumed", t0 + 2_000, meta = Meta(noticeType = "status")),
    )

    private fun session(turns: List<TurnRecord>) = SessionState(
        sessionId = sid,
        facts = SessionFacts(sessionId = sid, status = SessionStatus.Completed),
        canonical = canonical,
        canonicalCount = canonical.size,
        projection = LiveProjection(messages = live),
        turns = turns,
        seed = SeedState.Seeded(10),
        live_ = true,
    )

    private val finished = TurnRecord(requestId = "r1", sessionId = sid, sentAt = t0, open = false, stats = stats, answerRenderKey = "a:r1:1:0")

    private fun TranscriptController.reduce(state: SessionState, details: MessageDetails) =
        reduce(TranscriptInput(sid, state, details = details))

    private fun noticeTexts(ui: TranscriptUiState): List<String> =
        ui.model.rows.filter { it.type == TranscriptRowType.Notice }.map { (it.item as dev.cline.termux.data.chat.ChatRenderItem.Message).message.content }

    @Test
    fun `brief hides the recoverable error and shows no stats`() {
        val ui = TranscriptController(strings).reduce(session(listOf(finished)), MessageDetails.Brief)
        assertEquals(listOf("Session resumed"), noticeTexts(ui))
        assertNull(ui.model.factsOf("h:a1:0").turnStats)
    }

    @Test
    fun `verbose shows the recoverable error inline and the answer's stats`() {
        val ui = TranscriptController(strings).reduce(session(listOf(finished)), MessageDetails.Verbose)
        assertEquals(listOf("Tool execution failed; retrying", "Session resumed"), noticeTexts(ui))
        assertEquals(stats, ui.model.factsOf("h:a1:0").turnStats)
        assertNull(ui.model.factsOf("h:u1").turnStats)
    }

    @Test
    fun `a cold start finds the stats by the history id`() {
        val coldStart = finished.copy(answerRenderKey = "a:gone", answerMessageId = "h:a1:0")
        val ui = TranscriptController(strings).reduce(session(listOf(coldStart)), MessageDetails.Verbose)
        assertEquals(stats, ui.model.factsOf("h:a1:0").turnStats)
    }

    @Test
    fun `changing the mode re-projects the same session, the same mode reuses the model`() {
        val controller = TranscriptController(strings)
        val state = session(listOf(finished))
        val brief = controller.reduce(state, MessageDetails.Brief)
        assertSame(brief.model, controller.reduce(state, MessageDetails.Brief).model)
        val verbose = controller.reduce(state, MessageDetails.Verbose)
        assertNotSame(brief.model, verbose.model)
        assertEquals(2, noticeTexts(verbose).size)
        val back = controller.reduce(state, MessageDetails.Brief)
        assertEquals(1, noticeTexts(back).size)
    }

    @Test
    fun `in verbose a turn that just closed rebuilds the model with its stats`() {
        val controller = TranscriptController(strings)
        val open = finished.copy(open = true, stats = null, answerRenderKey = null)
        val before = controller.reduce(session(listOf(open)), MessageDetails.Verbose)
        assertNull(before.model.factsOf("h:a1:0").turnStats)
        val after = controller.reduce(session(listOf(finished)), MessageDetails.Verbose)
        assertEquals(stats, after.model.factsOf("h:a1:0").turnStats)
    }

    @Test
    fun `a subscription provider's turn shows no cost, as the terminal's verbose line`() {
        val costs = ProviderUsageCost.parse("""{"schema":1,"providers":{"openai-codex":"subscription"}}""")
        val controller = TranscriptController(strings)
        val astra = controller.reduce(TranscriptInput(sid, session(listOf(finished.copy(providerId = "openai-codex"))), details = MessageDetails.Verbose, usageCost = costs))
        assertEquals(stats.copy(totalCost = null), astra.model.factsOf("h:a1:0").turnStats)
        val deepseek = controller.reduce(TranscriptInput(sid, session(listOf(finished.copy(providerId = "deepseek"))), details = MessageDetails.Verbose, usageCost = costs))
        assertEquals(stats, deepseek.model.factsOf("h:a1:0").turnStats)
    }

    @Test
    fun `verbose falls back to the history totals for a turn this app did not record`() {
        val metered = listOf(
            msg("h:u1", Role.User, "Read the README", t0),
            msg("h:a1:0", Role.Assistant, "It describes the app.", t0 + 3_000, renderKey = "a:r1:1:0", meta = Meta(inputTokens = 900, outputTokens = 120, totalCost = 0.002, providerId = "deepseek")),
        )
        val state = session(emptyList()).copy(canonical = metered, canonicalCount = metered.size)
        val controller = TranscriptController(strings)
        val verbose = controller.reduce(state, MessageDetails.Verbose)
        assertEquals(TurnStats(inputTokens = 900, outputTokens = 120, totalCost = 0.002, durationMs = 3_000), verbose.model.factsOf("h:a1:0").turnStats)
        assertNull(controller.reduce(state, MessageDetails.Brief).model.factsOf("h:a1:0").turnStats)
        val recorded = controller.reduce(state.copy(turns = listOf(finished)), MessageDetails.Verbose)
        assertEquals("the run.start result wins over the history", stats, recorded.model.factsOf("h:a1:0").turnStats)
    }
}
