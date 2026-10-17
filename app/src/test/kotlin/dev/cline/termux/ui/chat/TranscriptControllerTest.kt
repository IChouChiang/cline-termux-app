package dev.cline.termux.ui.chat

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.CheckpointEntry
import dev.cline.termux.data.session.LiveProjection
import dev.cline.termux.data.session.Liveness
import dev.cline.termux.data.session.PendingApproval
import dev.cline.termux.data.session.PendingQuestion
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.UsageTotals
import dev.cline.termux.ui.chat.rows.ChatTransportState
import dev.cline.termux.ui.chat.rows.MessageActionsState
import dev.cline.termux.ui.chat.rows.TranscriptGap
import dev.cline.termux.ui.chat.rows.TranscriptRowType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptControllerTest {
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
        reasonText = mapOf(SessionError.Reason.MissingResumeConfig to "Choose a model first."),
    )

    private fun msg(id: String, role: Role, content: String, at: Long = t0, meta: Meta = Meta()) =
        ChatMessage(id = id, sessionId = sid, role = role, content = content, createdAt = at, meta = meta)

    private fun runningTool(id: String) = msg(
        id, Role.Tool, """{"toolName":"run_commands","input":{"commands":["sleep 60"]}}""",
        meta = Meta(toolCallId = "call_$id", toolName = "run_commands", hookEventName = "tool_call_start"),
    )

    private fun doneTool(id: String) = msg(
        id, Role.Tool,
        """{"toolName":"run_commands","input":{"commands":["echo hi"]},"result":[{"query":"echo hi","result":"hi\n","success":true}]}""",
        meta = Meta(toolCallId = "call_$id", toolName = "run_commands"),
    )

    private fun session(
        canonical: List<ChatMessage> = emptyList(),
        live: List<ChatMessage> = emptyList(),
        status: SessionStatus = SessionStatus.Completed,
        phase: RunPhase = RunPhase.Idle,
        seed: SeedState = SeedState.Seeded(10),
        streaming: String? = null,
        approvals: Int = 0,
        questions: Int = 0,
        error: SessionError? = null,
        creating: Boolean = false,
        turns: List<TurnRecord> = emptyList(),
        usage: UsageTotals? = null,
        metadata: JsonObject = JsonObject(emptyMap()),
        checkpoints: List<CheckpointEntry> = emptyList(),
    ) = SessionState(
        sessionId = sid,
        facts = SessionFacts(
            sessionId = sid,
            status = status,
            runPhase = phase,
            approvals = (1..approvals).associate { "ap$it" to PendingApproval("ap$it", "call_$it", "run_commands", null, 1, null, null, null, t0, null) },
            questions = (1..questions).associate { "q$it" to PendingQuestion("q$it", "Pick one", listOf("A", "B"), null, 1, t0) },
            aggregateUsage = usage,
            metadata = metadata,
            checkpointHistory = checkpoints,
        ),
        canonical = canonical,
        canonicalCount = canonical.size,
        projection = LiveProjection(messages = live, streamingMessageId = streaming),
        turns = turns,
        seed = seed,
        live_ = true,
        creating = creating,
        errorBanner = error,
    )

    private fun reduce(state: SessionState?, kernel: KernelLine = KernelLine.Connected, actions: MessageActionsState = MessageActionsState(), hostError: SessionError? = null) =
        TranscriptController(strings).reduce(TranscriptInput(sid, state, kernel, actions, hostError))

    // ---------------------------------------------------------------- switching / missing

    @Test
    fun `selected session without state shows Loading when connected, nothing when the kernel is down`() {
        assertEquals(Switching.Loading, reduce(null).switching)
        assertEquals(Switching.None, reduce(null, KernelLine.TermuxAsleep).switching)
    }

    @Test
    fun `seeding with cached rows is Switching, without rows Loading, seeded None`() {
        val rows = listOf(msg("u1", Role.User, "hi"))
        assertEquals(Switching.Switching, reduce(session(canonical = rows, seed = SeedState.Seeding)).switching)
        assertEquals(Switching.Loading, reduce(session(seed = SeedState.Seeding)).switching)
        assertEquals(Switching.None, reduce(session(canonical = rows)).switching)
        assertEquals(Switching.None, reduce(session(seed = SeedState.Failed("boom"))).switching)
    }

    @Test
    fun `not seeded waits only while connected so kernel-down shows cached rows`() {
        val rows = listOf(msg("u1", Role.User, "hi"))
        assertEquals(Switching.Switching, reduce(session(canonical = rows, seed = SeedState.NotSeeded)).switching)
        assertEquals(Switching.None, reduce(session(canonical = rows, seed = SeedState.NotSeeded), KernelLine.Reconnecting).switching)
    }

    @Test
    fun `missing seed is the missing state without indicator`() {
        val ui = reduce(session(seed = SeedState.Missing, status = SessionStatus.Running, phase = RunPhase.Running))
        assertTrue(ui.missing)
        assertNull(ui.indicator)
        assertEquals(Liveness.Missing, ui.liveness)
    }

    // ---------------------------------------------------------------- indicator (desktop isAwaitingFirstOutput)

    @Test
    fun `starting shows Thinking at the block gap under a user message`() {
        val ui = reduce(session(live = listOf(msg("local:r1", Role.User, "go")), phase = RunPhase.Starting))
        assertEquals(ChatStatus.Starting, ui.status)
        assertEquals(ThinkingIndicator(TranscriptGap.Block), ui.indicator)
    }

    @Test
    fun `creating also counts as starting`() {
        val ui = reduce(session(creating = true))
        assertEquals(ChatStatus.Starting, ui.status)
        assertEquals(ThinkingIndicator(TranscriptGap.None), ui.indicator)
    }

    @Test
    fun `running with nothing streaming shows Thinking tight under working rows`() {
        val live = listOf(msg("u1", Role.User, "go"), doneTool("t1"))
        val ui = reduce(session(live = live, status = SessionStatus.Running, phase = RunPhase.Running))
        assertEquals(ThinkingIndicator(TranscriptGap.Tight), ui.indicator)
    }

    @Test
    fun `running hides Thinking while streaming, while a tool runs, or while input is pending`() {
        val base = listOf(msg("u1", Role.User, "go"))
        val streaming = reduce(session(live = base + msg("a1", Role.Assistant, "par"), status = SessionStatus.Running, phase = RunPhase.Running, streaming = "a1"))
        assertNull(streaming.indicator)
        val tool = reduce(session(live = base + runningTool("t1"), status = SessionStatus.Running, phase = RunPhase.Running))
        assertNull(tool.indicator)
        val approval = reduce(session(live = base, status = SessionStatus.Running, phase = RunPhase.Running, approvals = 1))
        assertNull(approval.indicator)
        assertEquals(1, approval.pendingApprovals)
        val question = reduce(session(live = base, status = SessionStatus.Running, phase = RunPhase.Running, questions = 1))
        assertNull(question.indicator)
        assertEquals(1, question.pendingQuestions)
    }

    @Test
    fun `status rows are ignored when finding the last conversation row`() {
        val live = listOf(msg("u1", Role.User, "go"), runningTool("t1"), msg("x:9", Role.Status, "started"))
        assertTrue(TranscriptController.lastToolInProgress(live))
    }

    @Test
    fun `no indicator while switching or when idle`() {
        val rows = listOf(msg("u1", Role.User, "go"))
        assertNull(reduce(session(canonical = rows, seed = SeedState.Seeding, phase = RunPhase.Starting)).indicator)
        assertNull(reduce(session(canonical = rows)).indicator)
    }

    // ---------------------------------------------------------------- banner and transport

    @Test
    fun `history error banner carries Retry and hides the transport line`() {
        val ui = reduce(session(seed = SeedState.Failed("unavailable"), error = SessionError(SessionError.Kind.History, "unavailable")), KernelLine.Reconnecting)
        assertNotNull(ui.banner)
        val banner = ui.banner!!
        assertEquals("Couldn't load this session: unavailable", banner.text)
        assertTrue(banner.showText)
        assertEquals(TranscriptBannerAction.Retry(SessionError.Kind.History), banner.action)
        assertEquals(ChatTransportState.Connected, ui.transport)
    }

    @Test
    fun `transport line follows the kernel line when no banner shows`() {
        val rows = listOf(msg("u1", Role.User, "go"))
        assertEquals(ChatTransportState.Reconnecting, reduce(session(canonical = rows), KernelLine.Reconnecting).transport)
        assertEquals(ChatTransportState.TermuxAsleep, reduce(session(canonical = rows), KernelLine.TermuxAsleep).transport)
        assertEquals(ChatTransportState.Connected, reduce(session(canonical = rows), KernelLine.Blocked).transport)
    }

    @Test
    fun `credential failure banner offers the credential action instead of Retry`() {
        val auth = ProviderAuthInfo("anthropic", listOf("api-key"))
        val error = SessionError(SessionError.Kind.Resume, "401 Unauthorized", providerId = "anthropic", providerAuth = auth)
        val banner = reduce(session(error = error)).banner!!
        assertEquals(TranscriptBannerAction.Credential(CredentialAction.Providers), banner.action)
        assertEquals("Couldn't resume this session: 401 Unauthorized", banner.text)
    }

    @Test
    fun `credential failure without a resolvable action and an identical error row shows nothing`() {
        val error = SessionError(SessionError.Kind.Resume, "401 Unauthorized", providerId = "mystery", providerAuth = null)
        val row = msg("e:r1", Role.Error, "401 Unauthorized", meta = Meta(providerId = "mystery"))
        assertNull(reduce(session(live = listOf(row), error = error)).banner)
    }

    @Test
    fun `banner text is hidden when the newest error row says the same thing`() {
        val error = SessionError(SessionError.Kind.Resume, "maximum context tokens exceeded")
        val row = msg("e:r1", Role.Error, "maximum context tokens exceeded")
        val banner = reduce(session(live = listOf(row), error = error)).banner!!
        assertFalse(banner.showText)
        assertEquals(TranscriptBannerAction.Retry(SessionError.Kind.Resume), banner.action)
    }

    @Test
    fun `app-side reason replaces the empty kernel text`() {
        val error = SessionError(SessionError.Kind.Resume, "", reason = SessionError.Reason.MissingResumeConfig)
        assertEquals("Couldn't resume this session: Choose a model first.", reduce(session(error = error)).banner!!.text)
    }

    @Test
    fun `home shows only a host start error`() {
        val controller = TranscriptController(strings)
        val home = controller.reduce(TranscriptInput(null, null, KernelLine.TermuxAsleep, hostError = SessionError(SessionError.Kind.Start, "no provider")))
        assertNull(home.sessionId)
        assertTrue(home.isEmpty)
        assertEquals("Couldn't start the session: no provider", home.banner!!.text)
        assertEquals(ChatTransportState.Connected, home.transport)
    }

    // ---------------------------------------------------------------- actions, resend, usage

    @Test
    fun `derived action flags merge with host pending state`() {
        val host = MessageActionsState(editPendingId = "u1", versioningPending = true)
        val checkpoint = CheckpointEntry("ref", t0, 1)
        val ui = reduce(session(live = listOf(msg("u1", Role.User, "go")), status = SessionStatus.Running, phase = RunPhase.Running, checkpoints = listOf(checkpoint)), actions = host)
        assertTrue(ui.actions.busy)
        assertFalse(ui.actions.switching)
        assertEquals(setOf(1), ui.actions.checkpointRuns.keys)
        assertEquals("u1", ui.actions.editPendingId)
        assertTrue(ui.actions.versioningDisabled)
    }

    @Test
    fun `interrupted rows are resendable from the turn prompt or the previous user row until a newer user message`() {
        val turn = TurnRecord(requestId = "r2", sessionId = sid, prompt = "sleep 60; echo done", sentAt = t0)
        val interruptedWithTurn = msg("i:run2", Role.Error, "", meta = Meta(reason = REASON_INTERRUPTED, requestId = "r2"))
        val s1 = session(live = listOf(msg("u1", Role.User, "first"), interruptedWithTurn), turns = listOf(turn))
        assertEquals(setOf("i:run2"), TranscriptController.resendableIds(s1))

        val noTurn = msg("i:run3", Role.Error, "", meta = Meta(reason = REASON_INTERRUPTED, requestId = "r3"))
        val s2 = session(live = listOf(msg("u1", Role.User, "<user_input mode=\"act\">first</user_input>"), noTurn))
        assertEquals(setOf("i:run3"), TranscriptController.resendableIds(s2))

        // Live error rows are placed by time among the transcript rows (Reconciler.merge).
        val s3 = session(
            live = listOf(msg("u1", Role.User, "first"), interruptedWithTurn.copy(createdAt = t0 + 10), msg("u2", Role.User, "next", at = t0 + 20)),
            turns = listOf(turn),
        )
        assertTrue(TranscriptController.resendableIds(s3).isEmpty())

        val s4 = session(live = listOf(noTurn))
        assertTrue(TranscriptController.resendableIds(s4).isEmpty())
    }

    @Test
    fun `usage prefers aggregate totals and metadata cost`() {
        val usage = UsageTotals(inputTokens = 9_000, outputTokens = 3_500, totalCost = 0.0042)
        val ui = reduce(session(usage = usage))
        // 12 500 tokens: toFixed(0) of 12.5 rounds half up, as the desktop does.
        assertEquals("13k", ui.usage!!.tokens)
        assertEquals("$0.0042", ui.usage!!.cost)
        val withMetadata = reduce(session(usage = usage, metadata = JsonObject(mapOf("totalCost" to JsonPrimitive(1.234)))))
        assertEquals("$1.23", withMetadata.usage!!.cost)
        assertNull(reduce(session()).usage)
    }

    @Test
    fun `user message count and last texts feed scrolling and announcements`() {
        val live = listOf(
            msg("u1", Role.User, "a"),
            msg("a1", Role.Assistant, "answer one"),
            msg("u2", Role.User, "b"),
            msg("e:r2", Role.Error, "boom"),
        )
        val ui = reduce(session(live = live, status = SessionStatus.Failed))
        assertEquals(2, ui.userMessageCount)
        assertEquals("answer one", ui.lastAssistantText)
        assertEquals("boom", ui.lastErrorDetail)
    }

    @Test
    fun `the render model is memoized while the rows and status are unchanged`() {
        val controller = TranscriptController(strings)
        val rows = listOf(msg("u1", Role.User, "a"), doneTool("t1"), msg("a1", Role.Assistant, "done"))
        val state = session(canonical = rows)
        val first = controller.reduce(TranscriptInput(sid, state))
        val second = controller.reduce(TranscriptInput(sid, state.copy(touchedAt = 99)))
        assertSame(first.model, second.model)
        val third = controller.reduce(TranscriptInput(sid, state.copy(canonical = rows + msg("u2", Role.User, "b"))))
        assertTrue(third.model !== first.model)
        assertEquals(TranscriptRowType.User, third.model.rows.last().type)
    }

    // ---------------------------------------------------------------- announcements

    private fun ui(status: ChatStatus, assistant: String? = null, error: String? = null, switching: Switching = Switching.None, id: String? = sid) =
        TranscriptUiState.Empty.copy(sessionId = id, status = status, lastAssistantText = assistant, lastErrorDetail = error, switching = switching)

    /** One fresh announcer fed [previous] (when given) then [next]. */
    private fun transcriptAnnouncement(previous: TranscriptUiState?, next: TranscriptUiState, strings: TranscriptStrings): TranscriptAnnouncement? {
        val announcer = TranscriptAnnouncer(strings)
        previous?.let { announcer.next(it) }
        return announcer.next(next)
    }

    @Test
    fun `completion is announced for a run seen in progress even through the hub's idle update`() {
        val announcer = TranscriptAnnouncer(strings)
        assertNull(announcer.next(ui(ChatStatus.Idle)))
        assertEquals(TranscriptAnnouncement.Working, announcer.next(ui(ChatStatus.Running)))
        assertNull(announcer.next(ui(ChatStatus.Idle, assistant = "done")))
        assertEquals(TranscriptAnnouncement.Completed("done"), announcer.next(ui(ChatStatus.Completed, assistant = "done")))
        // Reopening an idle completed session later says nothing.
        assertNull(announcer.next(ui(ChatStatus.Idle, assistant = "done")))
        assertNull(announcer.next(ui(ChatStatus.Completed, assistant = "done")))
    }

    @Test
    fun `cancelled runs end the run without a completion announcement`() {
        val announcer = TranscriptAnnouncer(strings)
        announcer.next(ui(ChatStatus.Starting))
        announcer.next(ui(ChatStatus.Running))
        assertNull(announcer.next(ui(ChatStatus.Cancelled, assistant = "partial")))
        assertNull(announcer.next(ui(ChatStatus.Completed, assistant = "partial")))
    }

    @Test
    fun `run started announces working once`() {
        assertEquals(TranscriptAnnouncement.Working, transcriptAnnouncement(ui(ChatStatus.Starting), ui(ChatStatus.Running), strings))
        assertEquals(TranscriptAnnouncement.Working, transcriptAnnouncement(ui(ChatStatus.Completed), ui(ChatStatus.Running), strings))
        assertNull(transcriptAnnouncement(ui(ChatStatus.Running), ui(ChatStatus.Running), strings))
        assertNull(transcriptAnnouncement(ui(ChatStatus.Stopping), ui(ChatStatus.Running), strings))
    }

    @Test
    fun `completion announces the first 200 characters of the answer`() {
        val long = "x".repeat(250)
        val announcement = transcriptAnnouncement(ui(ChatStatus.Running), ui(ChatStatus.Completed, assistant = long), strings)
        assertEquals(TranscriptAnnouncement.Completed("x".repeat(200)), announcement)
        assertNull(transcriptAnnouncement(ui(ChatStatus.Running), ui(ChatStatus.Completed, assistant = "  "), strings))
        assertNull(transcriptAnnouncement(ui(ChatStatus.Idle), ui(ChatStatus.Completed, assistant = "a"), strings))
    }

    @Test
    fun `failure announces the error detail or the empty sentence`() {
        assertEquals(TranscriptAnnouncement.Failed("boom"), transcriptAnnouncement(ui(ChatStatus.Running), ui(ChatStatus.Failed, error = "boom"), strings))
        assertEquals(
            TranscriptAnnouncement.Failed("The run failed before a response was produced."),
            transcriptAnnouncement(ui(ChatStatus.Running), ui(ChatStatus.Failed, error = ""), strings),
        )
    }

    @Test
    fun `opening or switching sessions announces nothing`() {
        assertNull(transcriptAnnouncement(null, ui(ChatStatus.Running), strings))
        assertNull(transcriptAnnouncement(ui(ChatStatus.Idle, id = "other"), ui(ChatStatus.Running), strings))
        assertNull(transcriptAnnouncement(ui(ChatStatus.Idle, switching = Switching.Switching), ui(ChatStatus.Running), strings))
    }

    @Test
    fun `excerpt never splits a surrogate pair`() {
        val text = "a".repeat(199) + "😀" + "tail"
        assertEquals("a".repeat(199), excerptOf(text))
        assertEquals("short", excerptOf("short"))
    }

    @Test
    fun `excerpt never splits a ZWJ emoji, a flag or a combining sequence`() {
        val family = "👩‍👩‍👧" // 8 units
        assertEquals("a".repeat(196), excerptOf("a".repeat(196) + family + "tail"))
        val flag = "🇨🇳" // regional indicators, 4 units
        assertEquals("a".repeat(198), excerptOf("a".repeat(198) + flag + "tail"))
        assertEquals("a".repeat(199), excerptOf("a".repeat(199) + "é" + "tail"))
    }
}
