package dev.cline.termux.data.notifications

import dev.cline.termux.data.notifications.NotificationStates.T0
import dev.cline.termux.data.notifications.NotificationStates.ended
import dev.cline.termux.data.notifications.NotificationStates.idle
import dev.cline.termux.data.notifications.NotificationStates.running
import dev.cline.termux.data.notifications.NotificationStates.withApproval
import dev.cline.termux.data.notifications.NotificationStates.withMistakeLimit
import dev.cline.termux.data.notifications.NotificationStates.withQuestion
import dev.cline.termux.data.notifications.NotificationStates.withQueued
import dev.cline.termux.data.notifications.NotificationStates.withoutApproval
import dev.cline.termux.data.notifications.NotificationStates.withoutQuestion
import dev.cline.termux.data.notifications.NotificationStates.factsOf
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The posting contract (SP S3.3, CR §11.3–11.5) as pure state diffs; ports the desktop watcher's cases. */
class NotificationPlannerTest {
    private val background = PlanContext(nowMs = T0 + 5_000, foreground = false, settings = NotificationSettings.DEFAULT)
    private val foreground = background.copy(foreground = true)

    private fun NotificationPlanner.step(state: SessionState, ctx: PlanContext = background) = plan(factsOf(state), ctx).actions

    @Test
    fun completedRunPostsOnceInTheBackground() {
        val planner = NotificationPlanner()
        val run = running("s1")
        assertEquals(listOf(NotificationAction.CancelResult("s1")), planner.step(run))
        val done = run.ended(SessionStatus.Completed, at = T0 + 4_000)
        assertEquals(
            listOf(NotificationAction.PostResult("s1", "Fix the login redirect", failed = false, detail = null, at = T0 + 4_000)),
            planner.step(done),
        )
        // Desktop "deduplicates chat completion and session-ended terminal events": a later snapshot does not repost.
        assertEquals(emptyList<NotificationAction>(), planner.step(done.copy(facts = done.facts.copy(lastSequence = 99))))
    }

    @Test
    fun aNewRunCancelsTheOldResultAndRearmsTheTerminalNotice() {
        val planner = NotificationPlanner()
        planner.step(running("s1"))
        planner.step(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000))
        val second = running("s1", runId = "run-2", startedAt = T0 + 2_000, seq = 10)
        assertEquals(listOf(NotificationAction.CancelResult("s1")), planner.step(second))
        val actions = planner.step(second.ended(SessionStatus.Completed, at = T0 + 3_000))
        assertTrue(actions.single() is NotificationAction.PostResult)
    }

    @Test
    fun nothingPostsInTheForegroundButTheRunStillCounts() {
        val planner = NotificationPlanner()
        planner.step(running("s1"), foreground)
        val done = running("s1").ended(SessionStatus.Completed, at = T0 + 1_000)
        assertEquals(emptyList<NotificationAction>(), planner.step(done, foreground))
        assertEquals(emptyList<NotificationAction>(), planner.step(done, background))
    }

    @Test
    fun failedRunCarriesTheErrorText() {
        val planner = NotificationPlanner()
        planner.step(running("s1"))
        val failed = running("s1").ended(SessionStatus.Failed, at = T0 + 1_000, error = "Provider returned 401: invalid api key")
        val post = planner.step(failed).single() as NotificationAction.PostResult
        assertTrue(post.failed)
        assertEquals("Provider returned 401: invalid api key", post.detail)
    }

    @Test
    fun failedRunWithoutTextUsesTheFallbackLater() {
        val planner = NotificationPlanner()
        planner.step(running("s1"))
        val post = planner.step(running("s1").ended(SessionStatus.Failed, at = T0 + 1_000)).single() as NotificationAction.PostResult
        assertEquals(null, post.detail)
    }

    @Test
    fun cancelledAndOtherEndingsNeverNotify() {
        val planner = NotificationPlanner()
        planner.step(running("s1"))
        assertEquals(emptyList<NotificationAction>(), planner.step(running("s1").ended(SessionStatus.Cancelled, at = T0 + 1_000)))
        planner.step(running("s1", runId = "run-2", seq = 20))
        // run.completed with another reason (e.g. "disposed") leaves the status idle.
        assertEquals(emptyList<NotificationAction>(), planner.step(running("s1", runId = "run-2", seq = 20).ended(SessionStatus.Idle, at = T0 + 2_000)))
    }

    @Test
    fun queuedPromptsSuppressCompletionButNotErrors() {
        val planner = NotificationPlanner()
        planner.step(running("s1").withQueued(1))
        assertEquals(emptyList<NotificationAction>(), planner.step(running("s1").withQueued(1).ended(SessionStatus.Completed, at = T0 + 1_000)))
        planner.step(running("s1", runId = "run-2", seq = 30).withQueued(1))
        val error = planner.step(running("s1", runId = "run-2", seq = 30).withQueued(1).ended(SessionStatus.Failed, at = T0 + 2_000))
        assertTrue((error.single() as NotificationAction.PostResult).failed)
    }

    @Test
    fun disabledEventsDoNotPost() {
        val planner = NotificationPlanner()
        val off = background.copy(settings = NotificationSettings.DEFAULT.with(NotificationEventType.TaskCompletion, NotificationPreference(enabled = false)))
        planner.step(running("s1"), off)
        assertEquals(emptyList<NotificationAction>(), planner.step(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000), off))
        planner.step(running("s1", runId = "run-2", seq = 40), off)
        assertTrue(planner.step(running("s1", runId = "run-2", seq = 40).ended(SessionStatus.Failed, at = T0 + 2_000), off).single() is NotificationAction.PostResult)
    }

    @Test
    fun eventsOlderThanTwoMinutesAreDropped() {
        val planner = NotificationPlanner()
        val late = background.copy(nowMs = T0 + NotificationPlanner.STALE_AFTER_MS + 1)
        planner.step(running("s1").withApproval("a1", at = T0), late).let { actions ->
            assertTrue(actions.none { it is NotificationAction.PostApproval })
        }
        planner.step(running("s1").ended(SessionStatus.Completed, at = T0), late).let { actions ->
            assertTrue(actions.none { it is NotificationAction.PostResult })
        }
        val fresh = background.copy(nowMs = T0 + NotificationPlanner.STALE_AFTER_MS)
        assertTrue(NotificationPlanner().step(running("s2").withApproval("a2", at = T0), fresh).any { it is NotificationAction.PostApproval })
    }

    /** Decided A1: under Auto-approve all the open chat answers new approvals of our runs itself. */
    @Test
    fun anApprovalTheAppAnswersItselfNeverPosts() {
        val auto = background.copy(autoAnswers = { it == "s1" })
        val planner = NotificationPlanner()
        assertTrue(planner.step(running("s1").withApproval("a1"), auto).none { it is NotificationAction.PostApproval })
        // Resolved moments later: nothing to cancel either way, and it never posts afterwards.
        assertTrue(planner.step(running("s1"), auto).none { it is NotificationAction.PostApproval })
        // Another client's run, or a session the app does not answer for, still notifies.
        val theirs = running("s1", runId = "run-2", seq = 10).let { s -> s.copy(facts = s.facts.copy(activeRun = s.facts.activeRun!!.copy(ours = false))) }
        assertTrue(planner.step(theirs.withApproval("a2"), auto).any { it is NotificationAction.PostApproval })
        assertTrue(NotificationPlanner().step(running("s2").withApproval("a3"), auto).any { it is NotificationAction.PostApproval })
    }

    /** Desktop "notifies once when a background approval remains in state snapshots". */
    @Test
    fun approvalNotifiesOnceAndIsCancelledWhenResolved() {
        val planner = NotificationPlanner()
        val waiting = running("s1").withApproval("a1", toolName = "run_commands")
        val first = planner.step(waiting)
        assertTrue(NotificationAction.PostApproval("s1", "Fix the login redirect", "a1", "run_commands", T0) in first)
        assertEquals(emptyList<NotificationAction>(), planner.step(waiting))
        assertEquals(listOf(NotificationAction.CancelApproval("s1", "a1")), planner.step(waiting.withoutApproval("a1")))
    }

    @Test
    fun seenIdsSurviveARestartThroughThePersistedCopy() {
        val planner = NotificationPlanner(seen = listOf("a1"))
        val actions = planner.plan(factsOf(running("s1").withApproval("a1")), background)
        assertTrue(actions.actions.none { it is NotificationAction.PostApproval })
        assertEquals(emptyList<String>(), actions.newlySeen)
        val next = planner.plan(factsOf(running("s1").withApproval("a1").withApproval("a2")), background)
        assertEquals(listOf("a2"), next.newlySeen)
    }

    @Test
    fun anApprovalSeenInTheForegroundDoesNotPostLater() {
        val planner = NotificationPlanner()
        planner.step(running("s1").withApproval("a1"), foreground)
        // Re-issued on subscribe after a reconnect: same id, now in the background.
        val fresh = NotificationPlanner(seen = emptyList())
        planner.plan(emptyMap(), background)
        assertTrue(planner.step(running("s1").withApproval("a1")).none { it is NotificationAction.PostApproval })
        assertTrue(fresh.step(running("s1").withApproval("a1")).any { it is NotificationAction.PostApproval })
    }

    @Test
    fun seenMemoryIsCappedAt500() {
        val planner = NotificationPlanner(seen = (0 until 500).map { "old-$it" })
        planner.plan(factsOf(running("s1").withApproval("new")), background)
        // old-0 fell out of the LRU, so it notifies again if re-issued.
        assertTrue(planner.step(running("s2").withApproval("old-0")).any { it is NotificationAction.PostApproval })
        assertTrue(planner.step(running("s3").withApproval("old-2")).none { it is NotificationAction.PostApproval })
    }

    @Test
    fun questionsPostWithTheirTextAndCancelWhenAnswered() {
        val planner = NotificationPlanner()
        val asked = running("s1").withQuestion("q1", "Which branch should I use?")
        assertTrue(NotificationAction.PostQuestion("s1", "Fix the login redirect", "q1", "Which branch should I use?", T0) in planner.step(asked))
        assertEquals(listOf(NotificationAction.CancelQuestion("s1", "q1")), planner.step(asked.withoutQuestion("q1")))
    }

    @Test
    fun aMistakeLimitQuestionPostsWithItsKindAndTheSheetsDetail() {
        val planner = NotificationPlanner()
        val long = "x".repeat(650)
        val asked = running("s1").withMistakeLimit("m1", details = "  $long  ")
        val post = planner.step(asked).filterIsInstance<NotificationAction.PostQuestion>().single()
        assertEquals(QuestionKind.MistakeLimit, post.kind)
        assertEquals("", post.question)
        assertEquals("the detail is cut like the sheet's", "x".repeat(600) + "…", post.mistakeDetail)
        // No detail: the notifier leaves the "Latest:" line out.
        val bare = NotificationPlanner().step(running("s2").withMistakeLimit("m2", details = "   "))
            .filterIsInstance<NotificationAction.PostQuestion>().single()
        assertEquals(null, bare.mistakeDetail)
        // An agent's question keeps its own kind and no detail.
        val ask = NotificationPlanner().step(running("s3").withQuestion("q1", "Which branch?"))
            .filterIsInstance<NotificationAction.PostQuestion>().single()
        assertEquals(QuestionKind.AskQuestion, ask.kind)
        assertEquals(null, ask.mistakeDetail)
    }

    @Test
    fun aRunEndingClearsItsPendingRequests() {
        val planner = NotificationPlanner()
        planner.step(running("s1").withApproval("a1").withQuestion("q1", "?"))
        val actions = planner.step(running("s1").ended(SessionStatus.Cancelled, at = T0 + 1_000))
        assertTrue(NotificationAction.CancelApproval("s1", "a1") in actions)
        assertTrue(NotificationAction.CancelQuestion("s1", "q1") in actions)
        assertTrue(actions.none { it is NotificationAction.PostResult })
    }

    @Test
    fun aFirstObservationOfAFreshTerminalStatePosts() {
        // A burst replay can conflate run.started and run.completed into one observation.
        val planner = NotificationPlanner()
        val actions = planner.step(idle("s1", seq = 7).copy(facts = idle("s1", seq = 7).facts.copy(status = SessionStatus.Completed, lastEventAt = T0 + 1_000)))
        assertTrue(actions.single() is NotificationAction.PostResult)
        // A record seeded without events (lastSequence 0) never notifies.
        val seeded = idle("s2", seq = 0).copy(facts = idle("s2", seq = 0).facts.copy(status = SessionStatus.Completed))
        assertEquals(emptyList<NotificationAction>(), NotificationPlanner().step(seeded))
    }

    @Test
    fun sessionsAreIndependent() {
        val planner = NotificationPlanner()
        val states = factsOf(running("s1").withApproval("a1"), running("s2").withApproval("a2"))
        val actions = planner.plan(states, background).actions
        assertEquals(setOf("a1", "a2"), actions.filterIsInstance<NotificationAction.PostApproval>().map { it.approvalId }.toSet())
    }

    /** The store evicts an idle session after 10 minutes; the next event recreates its state from scratch. */
    @Test
    fun anEvictedSessionThatComesBackDoesNotRepostItsResult() {
        val planner = NotificationPlanner()
        planner.step(running("s1"))
        assertTrue(planner.step(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000)).single() is NotificationAction.PostResult)
        assertEquals(emptyList<NotificationAction>(), planner.plan(emptyMap(), background).actions)
        // A `session.updated` for the evicted session: a fresh state that only knows the status.
        val recreated = idle("s1", seq = 60, at = T0 + 4_000).let { it.copy(facts = it.facts.copy(status = SessionStatus.Completed)) }
        assertEquals(emptyList<NotificationAction>(), planner.step(recreated))
        // Without the kept track the same observation reads as a fresh terminal state.
        assertTrue(NotificationPlanner().step(recreated).single() is NotificationAction.PostResult)
        // A new run of the returning session notifies as usual.
        val next = running("s1", runId = "run-2", startedAt = T0 + 4_500, seq = 61)
        assertEquals(listOf(NotificationAction.CancelResult("s1")), planner.step(next))
        assertTrue(planner.step(next.ended(SessionStatus.Completed, at = T0 + 4_800)).single() is NotificationAction.PostResult)
    }

    @Test
    fun keptTracksAreCappedAndTheOldestAbsentOneGoesFirst() {
        val planner = NotificationPlanner()
        planner.step(running("gone"))
        planner.step(running("gone").ended(SessionStatus.Completed, at = T0 + 1_000))
        val others = (0 until NotificationPlanner.MAX_TRACKED).map { idle("s$it", seq = 0) }
        planner.plan(factsOf(*others.toTypedArray()), background)
        // "gone" lost its track to the cap, so its terminal state reads as new again.
        val back = idle("gone", seq = 70, at = T0 + 4_000).let { it.copy(facts = it.facts.copy(status = SessionStatus.Completed)) }
        assertTrue(planner.step(back).single() is NotificationAction.PostResult)
    }

    @Test
    fun conciseCollapsesWhitespaceAndCapsAt180() {
        assertEquals("a b c", concise("  a \n\t b  c  "))
        val long = "x".repeat(400)
        val cut = concise(long)
        assertEquals(180, cut.length)
        assertEquals("x".repeat(179) + "…", cut)
        assertEquals("y".repeat(180), concise("y".repeat(180)))
    }

    @Test
    fun conciseNeverSplitsAGraphemeAtTheCut() {
        // A surrogate-pair emoji occupying UTF-16 indices 178..179 straddles the 179-unit cut.
        val pair = "x".repeat(178) + "\uD83D\uDE00" + "tail".repeat(10)
        assertEquals("x".repeat(178) + "…", concise(pair))

        // A ZWJ family (man, ZWJ, woman, ZWJ, girl: 8 units) starting at index 175 straddles it too.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val zwj = "y".repeat(175) + family + "z".repeat(20)
        assertEquals("y".repeat(175) + "…", concise(zwj))

        // A flag (two regional indicators, 4 units) at 177..180 is dropped whole.
        val flag = "w".repeat(177) + "\uD83C\uDDEF\uD83C\uDDF5" + "v".repeat(20)
        val cut = concise(flag)
        assertEquals("w".repeat(177) + "…", cut)
        assertTrue(cut.none { Character.isSurrogate(it) })
    }
}
