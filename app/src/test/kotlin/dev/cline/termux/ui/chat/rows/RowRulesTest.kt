package dev.cline.termux.ui.chat.rows

import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.data.chat.MediaSource
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.UNDATED
import dev.cline.termux.data.chat.formatWorkActivityLabel
import dev.cline.termux.data.tools.ToolActivityStatus
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import dev.cline.termux.data.tools.buildToolRowModel
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowRulesTest {
    private val t0 = 1_759_480_000_000L

    private fun msg(
        id: String,
        role: Role,
        content: String = "text $id",
        at: Long = t0,
        meta: Meta = Meta(),
        reasoning: String? = null,
        redacted: Boolean = false,
    ) = ChatMessage(id = id, sessionId = "s1", role = role, content = content, reasoning = reasoning, reasoningRedacted = redacted, createdAt = at, meta = meta)

    private fun tool(id: String, at: Long = t0, result: Boolean = true) = msg(
        id,
        Role.Tool,
        if (result) """{"toolName":"read_files","input":{"file_paths":["a.kt"]},"result":"ok"}"""
        else """{"toolName":"run_commands","input":{"commands":["ls"]}}""",
        at,
        Meta(toolCallId = "call_$id"),
    )

    private val strings = RunErrorStrings(
        runFailedFormat = "The run failed: %1\$s",
        runFailedEmpty = "The run failed before a response was produced.",
        hintLocalCliFormat = "Sign in again with the `%1\$s` CLI in a terminal, then try again.",
        hintCline = "Sign in to Cline again in Settings → Account, then try again.",
        hintUnknownAuth = "Sign in again using your provider's authentication method, then try again.",
        hintProviders = "Check your model connection in Settings → Providers (or sign in with Cline), then try again.",
    )

    // ------------------------------------------------------------ transcript model

    @Test
    fun `rows carry desktop gaps, keys and traversal order`() {
        val messages = listOf(
            msg("u1", Role.User, at = t0),
            tool("t1", at = t0 + 1_000),
            msg("a1", Role.Assistant, "answer", at = t0 + 2_000),
            msg("u2", Role.User, at = t0 + 3_000),
            msg("a2", Role.Assistant, "plain answer", at = t0 + 4_000),
        )
        val model = buildTranscriptModel(messages, ChatStatus.Completed)
        val types = model.rows.map { it.type }
        assertEquals(
            listOf(TranscriptRowType.User, TranscriptRowType.Work, TranscriptRowType.Assistant, TranscriptRowType.User, TranscriptRowType.Assistant),
            types,
        )
        assertEquals(
            listOf(TranscriptGap.None, TranscriptGap.Block, TranscriptGap.Tight, TranscriptGap.Turn, TranscriptGap.Block),
            model.rows.map { it.gapAbove },
        )
        assertEquals(listOf("u1", "t1", "a1", "u2", "a2"), model.rows.map { it.key })
        assertEquals(listOf(-4f, -3f, -2f, -1f, 0f), model.rows.map { it.traversalIndex })
        assertTrue(model.rows[2].followsWorkingRows)
        assertFalse(model.rows[4].followsWorkingRows)
    }

    @Test
    fun `steering notes are not rows`() {
        val steering = msg("x1", Role.User, "[SYSTEM] finish with submit_and_exit", meta = Meta(userRunSpan = 0))
        val model = buildTranscriptModel(listOf(msg("u1", Role.User), msg("a1", Role.Assistant, "hi"), steering), ChatStatus.Running)
        assertTrue(model.rows.none { it.key == "x1" })
        assertFalse(isRenderedWorkingChild(ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.User, steering, emptyList())))
    }

    @Test
    fun `run is active only after the last user message while busy`() {
        val messages = listOf(
            msg("u1", Role.User),
            tool("t1", result = false),
            msg("u2", Role.User),
            tool("t2", result = false),
        )
        val running = buildTranscriptModel(messages, ChatStatus.Running)
        assertEquals(listOf(false, false, false, true), running.rows.map { it.isRunActive })
        val idle = buildTranscriptModel(messages, ChatStatus.Idle)
        assertTrue(idle.rows.none { it.isRunActive })
    }

    @Test
    fun `a call without a result is Running while its run is live and Interrupted once the turn has finished (A21)`() {
        val messages = listOf(
            msg("u1", Role.User),
            tool("t1", result = false),
            msg("u2", Role.User),
            tool("t2", result = false),
        )
        fun statusOf(model: TranscriptModel, id: String) = model.factsOf(id).tool!!.model.status

        // Live: the open turn's call runs; the earlier turn's call is over.
        val live = buildTranscriptModel(messages, ChatStatus.Running, showInputPreview = false)
        assertEquals(ToolActivityStatus.Interrupted, statusOf(live, "t1"))
        assertEquals(ToolActivityStatus.Running, statusOf(live, "t2"))
        assertEquals(ToolActivityStatus.Running, statusOf(buildTranscriptModel(messages, ChatStatus.Stopping, showInputPreview = false), "t2"))
        assertEquals(ToolActivityStatus.Running, statusOf(buildTranscriptModel(messages, ChatStatus.Starting, showInputPreview = false), "t2"))

        // Finished, from history or after run.completed / run.aborted / a failure.
        for (ended in listOf(ChatStatus.Idle, ChatStatus.Completed, ChatStatus.Cancelled, ChatStatus.Failed)) {
            val model = buildTranscriptModel(messages, ended, showInputPreview = false)
            assertEquals(ended.name, ToolActivityStatus.Interrupted, statusOf(model, "t1"))
            assertEquals(ended.name, ToolActivityStatus.Interrupted, statusOf(model, "t2"))
            assertEquals(ended.name, ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "ls")), model.factsOf("t2").tool!!.model.label)
        }

        // A call with its result stays a finished call in every state.
        val answered = listOf(msg("u1", Role.User), tool("t1"))
        assertEquals(ToolActivityStatus.Success, statusOf(buildTranscriptModel(answered, ChatStatus.Completed, showInputPreview = false), "t1"))
        assertEquals(ToolActivityStatus.Success, statusOf(buildTranscriptModel(answered, ChatStatus.Running, showInputPreview = false), "t1"))
    }

    @Test
    fun `the run ending rebuilds a cached in-progress row as Interrupted`() {
        val cache = RowFactsCache()
        val messages = listOf(msg("u1", Role.User), tool("t1", result = false))
        val running = buildTranscriptModel(messages, ChatStatus.Running, cache = cache, showInputPreview = false)
        assertEquals(ToolActivityStatus.Running, running.factsOf("t1").tool!!.model.status)
        val ended = buildTranscriptModel(messages, ChatStatus.Cancelled, cache = cache, showInputPreview = false)
        assertEquals(ToolActivityStatus.Interrupted, ended.factsOf("t1").tool!!.model.status)
        assertEquals(2, cache.toolBuilds)
    }

    @Test
    fun `last conversation message ignores status rows and error rows are tracked`() {
        val messages = listOf(
            msg("u1", Role.User),
            msg("e1", Role.Error, "boom"),
            msg("a1", Role.Assistant, "answer"),
            msg("n1", Role.Status, "notice"),
        )
        val model = buildTranscriptModel(messages, ChatStatus.Completed, streamingMessageId = "a1")
        assertEquals("a1", model.lastConversationMessageId)
        assertEquals("e1", model.lastErrorMessage?.id)
        assertTrue(model.isLastAssistantMessage(messages[2]))
        assertTrue(model.isStreaming(messages[2]))
        assertFalse(model.isLastAssistantMessage(messages[0]))
    }

    @Test
    fun `superseded ids are everything before the newest genuine user message`() {
        val messages = listOf(msg("u1", Role.User), msg("e1", Role.Error, "", meta = Meta(reason = REASON_INTERRUPTED)), msg("u2", Role.User), msg("e2", Role.Error))
        val model = buildTranscriptModel(messages, ChatStatus.Failed)
        assertEquals(setOf("u1", "e1"), model.supersededMessageIds)
        assertEquals(mapOf("u1" to 1, "u2" to 2), model.runCountByMessageId)
    }

    @Test
    fun `row type maps every role`() {
        fun typeOf(role: Role) = rowTypeOf(ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.of(role), msg("m", role), emptyList()))
        assertEquals(TranscriptRowType.User, typeOf(Role.User))
        assertEquals(TranscriptRowType.Assistant, typeOf(Role.Assistant))
        assertEquals(TranscriptRowType.Error, typeOf(Role.Error))
        assertEquals(TranscriptRowType.Notice, typeOf(Role.Status))
        assertEquals(TranscriptRowType.System, typeOf(Role.System))
    }

    // ------------------------------------------------------------ reasoning

    @Test
    fun `reasoning content joins trimmed parts and keeps redaction`() {
        val content = reasoningContentOf(listOf(msg("r1", Role.Assistant, "", reasoning = " one "), msg("r2", Role.Assistant, "", reasoning = "  "), msg("r3", Role.Assistant, "", reasoning = "two")))
        assertEquals("one\n\ntwo", content.text)
        assertFalse(content.redacted)
        val redacted = reasoningContentOf(listOf(msg("r1", Role.Assistant, "", redacted = true)))
        assertTrue(redacted.showsRedactedPlaceholder)
        assertFalse(redacted.isEmpty)
        assertTrue(reasoningContentOf(emptyList()).isEmpty)
    }

    @Test
    fun `reasoning duration prefers the live finish time`() {
        val live = msg("r1", Role.Assistant, "", at = t0 + 1_000, meta = Meta(thoughtEndedAt = t0 + 4_000), reasoning = "x")
        assertEquals(3_000L, reasoningDurationMillis(listOf(live), previousTimestamp = t0))
        val history = msg("r2", Role.Assistant, "", at = t0 + 5_000, reasoning = "x")
        assertEquals(5_000L, reasoningDurationMillis(listOf(history), previousTimestamp = t0))
        assertNull(reasoningDurationMillis(listOf(history), previousTimestamp = null))
        assertNull(reasoningDurationMillis(listOf(history.copy(createdAt = UNDATED)), previousTimestamp = t0))
        assertNull(reasoningDurationMillis(emptyList(), t0))
    }

    @Test
    fun `thought label follows formatThoughtLabel`() {
        assertEquals(ThoughtLabel.Thinking, thoughtLabel(streaming = true, durationMilliseconds = 5_000))
        assertEquals(ThoughtLabel.Thinking, thoughtLabel(streaming = false, durationMilliseconds = null))
        assertEquals(ThoughtLabel.ThoughtFor(0), thoughtLabel(false, 0))
        assertEquals(ThoughtLabel.ThoughtFor(1), thoughtLabel(false, 1))
        assertEquals(ThoughtLabel.ThoughtFor(1), thoughtLabel(false, 1_499))
        assertEquals(ThoughtLabel.ThoughtFor(2), thoughtLabel(false, 1_500))
        assertEquals(ThoughtLabel.ThoughtFor(42), thoughtLabel(false, 42_000))
    }

    // ------------------------------------------------------------ work label

    @Test
    fun `work label agrees with formatWorkActivityLabel`() {
        val cases = listOf<Pair<Long?, Int?>>(252_000L to 14, 3_000L to null, null to 1, null to null, 3_000L to 0, -5L to 2, 3_780_000L to 3)
        for ((duration, calls) in cases) {
            val rendered = when (val label = workLabel(duration, calls)) {
                is WorkLabel.Both -> "Worked for ${label.duration} and made ${label.toolCalls} ${if (label.toolCalls == 1) "tool call" else "tool calls"}"
                is WorkLabel.Duration -> "Worked for ${label.duration}"
                is WorkLabel.Calls -> "Made ${label.toolCalls} ${if (label.toolCalls == 1) "tool call" else "tool calls"}"
                WorkLabel.Worked -> "Worked"
            }
            assertEquals("duration=$duration calls=$calls", formatWorkActivityLabel(duration, calls), rendered)
        }
        assertEquals(WorkLabel.Both("4m 12s", 14), workLabel(252_000, 14))
    }

    // ------------------------------------------------------------ actions

    @Test
    fun `user edit needs a run count, text and a handler, not a checkpoint`() {
        val message = msg("u1", Role.User, meta = Meta(checkpoint = CheckpointRef("ref", t0, 1)))
        val on = MessageActionsState()
        assertTrue(userActionSet(message, "fix it", 1, on, canEdit = true, canRestore = true).edit)
        // CT C.3/C.6: a session without checkpoints (no Git) still offers Edit, as a chat-only fork.
        assertTrue(userActionSet(msg("u2", Role.User), "fix it", 1, on, canEdit = true, canRestore = true).edit)
        assertFalse(userActionSet(message, "fix it", null, on, canEdit = true, canRestore = true).edit)
        assertFalse(userActionSet(message, "fix it", 0, on, canEdit = true, canRestore = true).edit)
        assertFalse(userActionSet(message, "  ", 1, on, canEdit = true, canRestore = true).edit)
        assertFalse(userActionSet(message, "fix it", 1, on, canEdit = false, canRestore = true).edit)
        assertTrue(userActionSet(message, "fix it", 1, on, canEdit = true, canRestore = true).restore)
        assertFalse(userActionSet(msg("u2", Role.User), "x", 1, on, canEdit = true, canRestore = true).restore)
    }

    @Test
    fun `the user long-press menu names the restart and lists edit and restore only when they run`() {
        // CT DS-A6: the menu item says what Edit does, the desktop's "Edit and restart from here".
        assertEquals(R.string.checkpoint_menu_edit, UserMenuAction.Edit.label)
        assertEquals(R.string.msg_restore, UserMenuAction.Restore.label)
        assertEquals(
            listOf(UserMenuAction.Copy, UserMenuAction.Edit, UserMenuAction.Restore, UserMenuAction.Select),
            userMenuActions(canEdit = true, canRestore = true),
        )
        assertEquals(listOf("copy", "select"), userMenuActions(canEdit = false, canRestore = false).map { it.id })
        assertEquals(listOf(UserMenuAction.Copy, UserMenuAction.Edit, UserMenuAction.Select), userMenuActions(canEdit = true, canRestore = false))
    }

    @Test
    fun `restore takes the exact checkpoint of the message's run from the session history`() {
        val message = msg("u2", Role.User)
        val history = MessageActionsState(
            checkpointRuns = mapOf(1 to CheckpointRef("a", t0, 1), 2 to CheckpointRef("b", t0, 2)),
        )
        val second = userActionSet(message, "x", 2, history, canEdit = true, canRestore = true)
        assertTrue(second.restore)
        assertEquals("b", second.checkpoint?.ref)
        assertFalse(userActionSet(message, "x", 3, history, canEdit = true, canRestore = true).restore)
        assertFalse(userActionSet(message, "x", null, history, canEdit = true, canRestore = true).restore)
        assertNull(userActionSet(message, "x", 2, history, canEdit = true, canRestore = false).checkpoint)
    }

    @Test
    fun `unavailable user actions carry the reason their icons explain`() {
        val message = msg("u1", Role.User, meta = Meta(checkpoint = CheckpointRef("ref", t0, 1)))
        fun blocked(state: MessageActionsState) = userActionSet(message, "x", 1, state, true, true).blocked
        assertEquals(VersioningBlock.Busy, blocked(MessageActionsState(busy = true)))
        assertEquals(VersioningBlock.Busy, blocked(MessageActionsState(switching = true, versioningPending = true)))
        assertEquals(VersioningBlock.InProgress, blocked(MessageActionsState(versioningPending = true)))
        assertNull(blocked(MessageActionsState()))
    }

    @Test
    fun `user actions disable while busy and stay visible while pending or errored`() {
        val message = msg("u1", Role.User, meta = Meta(checkpoint = CheckpointRef("ref", t0, 1)))
        val busy = userActionSet(message, "x", 1, MessageActionsState(busy = true), true, true)
        assertFalse(busy.editEnabled)
        assertFalse(busy.restoreEnabled)
        assertFalse(busy.keepVisible)
        val switching = userActionSet(message, "x", 1, MessageActionsState(switching = true), true, true)
        assertFalse(switching.restoreEnabled)
        val pending = userActionSet(message, "x", 1, MessageActionsState(restorePendingId = "u1"), true, true)
        assertTrue(pending.restorePending)
        assertTrue(pending.keepVisible)
        val errored = userActionSet(message, "x", 1, MessageActionsState(editErrors = mapOf("u1" to ActionError())), true, true)
        assertTrue(errored.keepVisible)
        val other = userActionSet(message, "x", 1, MessageActionsState(editErrors = mapOf("u9" to ActionError())), true, true)
        assertFalse(other.keepVisible)
    }

    @Test
    fun `only the row waiting for a Hub drain shows the wait line`() {
        val message = msg("u1", Role.User, meta = Meta(checkpoint = CheckpointRef("ref", t0, 1)))
        val state = MessageActionsState(restorePendingId = "u1", drainingMessageId = "u1")
        val waiting = userActionSet(message, "x", 1, state, true, true)
        assertTrue(waiting.draining)
        assertTrue(waiting.restorePending)
        assertTrue(waiting.keepVisible)
        val other = userActionSet(msg("u2", Role.User), "x", 2, state, true, true)
        assertFalse(other.draining)
        assertFalse(userActionSet(message, "x", 1, MessageActionsState(restorePendingId = "u1"), true, true).draining)
    }

    @Test
    fun `assistant actions follow the desktop visibility rules`() {
        val answer = msg("a1", Role.Assistant, "answer")
        assertTrue(assistantActionSet(answer, isStreaming = false, isLastAssistantMessage = false).visibleAtAll)
        assertFalse(assistantActionSet(answer, isStreaming = true, isLastAssistantMessage = true).visibleAtAll)
        assertFalse(assistantActionSet(answer.copy(content = " "), false, true).visibleAtAll)
        assertFalse(assistantActionSet(answer.copy(role = Role.Error), false, true).visibleAtAll)
        assertTrue(assistantActionSet(answer, false, true).pinned)
        assertFalse(assistantActionSet(answer, false, false).pinned)
        // A16: the answer row has no fork; the whole-session fork lives in the chat's three-dot.
        assertEquals(AssistantActionSet(visibleAtAll = true, pinned = false), assistantActionSet(answer, false, false))
    }

    @Test
    fun `resend shows for recoverable interrupted rows without a newer user turn`() {
        val interrupted = msg("e1", Role.Error, "", meta = Meta(reason = REASON_INTERRUPTED))
        val before = buildTranscriptModel(listOf(msg("u1", Role.User), interrupted), ChatStatus.Failed)
        val state = MessageActionsState(resendableIds = setOf("e1"))
        assertTrue(showsResend(interrupted, before, state))
        assertFalse(showsResend(interrupted, before, MessageActionsState()))
        val after = buildTranscriptModel(listOf(msg("u1", Role.User), interrupted, msg("u2", Role.User)), ChatStatus.Running)
        assertFalse(showsResend(interrupted, after, state))
        assertFalse(showsResend(interrupted.copy(meta = Meta()), before, state))
    }

    @Test
    fun `error row text is the interrupted copy or formatRunError`() {
        val interrupted = msg("e1", Role.Error, "", meta = Meta(reason = REASON_INTERRUPTED))
        assertEquals("Hub restarted", errorRowText(interrupted, strings, "Hub restarted"))
        val plain = msg("e2", Role.Error, "maximum context tokens exceeded")
        assertEquals("The run failed: maximum context tokens exceeded", errorRowText(plain, strings, "x"))
        val empty = msg("e3", Role.Error, "", meta = Meta(providerId = "cline"))
        assertEquals("The run failed before a response was produced. ${strings.hintCline}", errorRowText(empty, strings, "x"))
    }

    // ------------------------------------------------------------ notices

    @Test
    fun `notice icons by type and reason`() {
        assertEquals(NoticeIcon.Info, noticeIconOf("status", null))
        assertEquals(NoticeIcon.Info, noticeIconOf(null, null))
        assertEquals(NoticeIcon.Recovery, noticeIconOf("recovery", null))
        assertEquals(NoticeIcon.Recovery, noticeIconOf("status", "auto_compaction"))
        assertEquals(NoticeIcon.Recovery, noticeIconOf(null, "manual_compaction"))
        assertEquals(NoticeIcon.Recovery, noticeIconOf(null, "compaction_budget_emergency"))
        assertEquals(NoticeIcon.Stop, noticeIconOf("stop", "user"))
    }

    @Test
    fun `recoverable mid-run errors are muted error rows (A11_9 Verbose)`() {
        for (reason in listOf("invalid_tool_call", "tool_execution_failed", "completion_without_submit", "mistake_limit")) {
            assertEquals(reason, NoticeIcon.RecoverableError, noticeIconOf("recovery", reason))
        }
        assertEquals(NoticeIcon.Recovery, noticeIconOf("recovery", "auto_compaction"))
        assertEquals(NoticeIcon.Info, noticeIconOf("status", "mistake_limit"))
    }

    // ------------------------------------------------------------ media

    @Test
    fun `canonical base64 and decoded size`() {
        assertTrue(isCanonicalBase64("AAAA"))
        assertTrue(isCanonicalBase64("AAA="))
        assertTrue(isCanonicalBase64("AA=="))
        assertTrue(isCanonicalBase64(""))
        assertFalse(isCanonicalBase64("AAA"))
        assertFalse(isCanonicalBase64("AA AA"))
        assertFalse(isCanonicalBase64("A==="))
        assertEquals(3L, base64DecodedSize("AAAA"))
        assertEquals(2L, base64DecodedSize("AAA="))
        assertEquals(1L, base64DecodedSize("AA=="))
        assertEquals(0L, base64DecodedSize(""))
    }

    @Test
    fun `renderable media types per modality`() {
        assertEquals("image/png", renderableMediaType(MediaModality.Image, " IMAGE/PNG "))
        assertNull(renderableMediaType(MediaModality.Image, "image/bmp"))
        assertEquals("audio/mpeg", renderableMediaType(MediaModality.Audio, "audio/mpeg"))
        assertNull(renderableMediaType(MediaModality.Audio, "video/mp4"))
        assertEquals("video/mp4", renderableMediaType(MediaModality.Video, "video/mp4"))
        assertEquals("application/pdf", renderableMediaType(MediaModality.File, "application/pdf"))
        assertNull(renderableMediaType(MediaModality.File, "not a type"))
    }

    @Test
    fun `generated media classification`() {
        fun media(id: String, modality: MediaModality, type: String, source: MediaSource) = GeneratedMedia(id, modality, type, source)
        val result = presentGeneratedMedia(
            listOf(
                media("1", MediaModality.Image, "image/png", MediaSource.Base64("AAAA")),
                media("2", MediaModality.Audio, "audio/ogg", MediaSource.Base64("AAAA")),
                media("3", MediaModality.Video, "video/mp4", MediaSource.Url("https://x")),
                media("4", MediaModality.File, "application/pdf", MediaSource.Artifact("art_1")),
                media("5", MediaModality.Image, "image/bmp", MediaSource.Base64("AAAA")),
                media("6", MediaModality.Image, "image/png", MediaSource.Base64("not base64!")),
            ),
        )
        assertTrue(result[0] is MediaPresentation.Image)
        assertTrue(result[1] is MediaPresentation.Openable)
        assertTrue(result[2] is MediaPresentation.Remote)
        assertEquals("art_1", (result[3] as MediaPresentation.StoredArtifact).artifactId)
        assertTrue(result[4] is MediaPresentation.Unrenderable)
        assertTrue(result[5] is MediaPresentation.Unrenderable)
    }

    @Test
    fun `every modality has its own fallback sentence for each presentation`() {
        // DS §8.2.3: one whole sentence per modality and presentation, no spliced-in word.
        val ids = MediaModality.entries.flatMap { listOf(it.remoteRes(), it.artifactRes(), it.unrenderableRes()) }
        assertEquals(MediaModality.entries.size * 3, ids.toSet().size)
        assertEquals(dev.cline.termux.R.string.media_remote_image, MediaModality.Image.remoteRes())
        assertEquals(dev.cline.termux.R.string.media_artifact_audio, MediaModality.Audio.artifactRes())
        assertEquals(dev.cline.termux.R.string.media_unrenderable_video, MediaModality.Video.unrenderableRes())
        assertEquals(dev.cline.termux.R.string.media_unrenderable_file, MediaModality.File.unrenderableRes())
    }

    @Test
    fun `image over the decoded limit and the message total are unrenderable`() {
        val sixMiBPlus = "A".repeat(((MAX_IMAGE_DECODED_BYTES / 3 + 1) * 4).toInt())
        val big = presentGeneratedMedia(listOf(GeneratedMedia("1", MediaModality.Image, "image/png", MediaSource.Base64(sixMiBPlus))))
        assertTrue(big.single() is MediaPresentation.Unrenderable)
        val fiveMiB = "A".repeat((5L * 1024 * 1024 / 3 * 4).toInt())
        val pair = presentGeneratedMedia(
            listOf(
                GeneratedMedia("1", MediaModality.Audio, "audio/ogg", MediaSource.Base64(fiveMiB)),
                GeneratedMedia("2", MediaModality.Audio, "audio/ogg", MediaSource.Base64(fiveMiB)),
            ),
        )
        assertTrue(pair[0] is MediaPresentation.Openable)
        assertTrue(pair[1] is MediaPresentation.Unrenderable)
    }

    @Test
    fun `media file names never escape the directory`() {
        assertEquals("chart.png", MediaOpener.safeFileName(GeneratedMedia("m1", MediaModality.Image, "image/png", MediaSource.Base64(""), name = "chart.png")))
        assertEquals("_etc_passwd", MediaOpener.safeFileName(GeneratedMedia("m1", MediaModality.File, "text/plain", MediaSource.Base64(""), name = "../etc/passwd")))
        assertEquals("m_1", MediaOpener.safeFileName(GeneratedMedia("m/1", MediaModality.File, "text/plain", MediaSource.Base64(""))))
        assertEquals("media", MediaOpener.safeFileName(GeneratedMedia("..", MediaModality.File, "text/plain", MediaSource.Base64(""))))
    }

    @Test
    fun `media file names keep CJK and emoji and cut whole graphemes`() {
        fun named(name: String) = MediaOpener.safeFileName(GeneratedMedia("m1", MediaModality.Image, "image/png", MediaSource.Base64(""), name = name))
        assertEquals("架构图.png", named("架构图.png"))
        assertEquals("报告_草稿 😀.pdf", named("报告:草稿 😀.pdf"))
        // 95 units then a surrogate pair across the 96-unit limit: the pair is dropped whole.
        assertEquals("a".repeat(95), named("a".repeat(95) + "😀.png"))
        // 96 CJK characters are 288 UTF-8 bytes; the name stays within the filesystem's byte limit.
        val long = named("图".repeat(120))
        assertEquals("图".repeat(80), long)
    }

    @Test
    fun `thumbnail sample size halves down to the target`() {
        assertEquals(1, sampleSizeFor(200, 100, 300))
        assertEquals(1, sampleSizeFor(500, 100, 300))
        assertEquals(2, sampleSizeFor(1200, 800, 600))
        assertEquals(4, sampleSizeFor(4000, 3000, 900))
        assertEquals(1, sampleSizeFor(0, 0, 300))
    }

    // ------------------------------------------------------------ transport line and banner

    @Test
    fun `transport line hides when connected or a banner shows`() {
        assertFalse(transportLineVisible(ChatTransportState.Connected, errorBannerShown = false))
        assertTrue(transportLineVisible(ChatTransportState.Reconnecting, errorBannerShown = false))
        assertFalse(transportLineVisible(ChatTransportState.Unavailable, errorBannerShown = true))
    }

    @Test
    fun `error banner follows shouldShowErrorBanner`() {
        assertFalse(shouldShowErrorBanner(null, hasAction = true, lastErrorMessage = null, strings = strings))
        assertFalse(shouldShowErrorBanner("", hasAction = true, lastErrorMessage = null, strings = strings))
        assertTrue(shouldShowErrorBanner("boom", hasAction = false, lastErrorMessage = null, strings = strings))
        val row = msg("e1", Role.Error, "boom")
        assertFalse(shouldShowErrorBanner("boom", hasAction = false, lastErrorMessage = row, strings = strings))
        assertFalse(shouldShowErrorBanner("The run failed: boom", hasAction = false, lastErrorMessage = row, strings = strings))
        assertTrue(shouldShowErrorBanner("other", hasAction = false, lastErrorMessage = row, strings = strings))
        assertTrue(shouldShowErrorBanner("boom", hasAction = true, lastErrorMessage = row, strings = strings))
        assertFalse(errorBannerShowsText("boom", row))
        assertTrue(errorBannerShowsText("other", row))
        assertTrue(errorBannerShowsText("boom", null))
    }

    // ------------------------------------------------------------ text helpers

    @Test
    fun `user display text strips the input envelope`() {
        val wrapped = msg("u1", Role.User, "<user_input mode=\"act\">fix the crash</user_input>")
        assertEquals("fix the crash", displayContentOf(wrapped))
        assertEquals("raw **markdown**", displayContentOf(msg("a1", Role.Assistant, "raw **markdown**")))
    }

    @Test
    fun `message time is localized short time and absent for undated rows`() {
        assertNull(formatMessageTime(UNDATED))
        assertNull(formatMessageTime(0))
        val time = formatMessageTime(t0, ZoneOffset.UTC)
        assertTrue(time != null && time.isNotEmpty())
    }

    @Test
    fun `copy output strips ANSI escapes`() {
        val message = msg(
            "t1",
            Role.Tool,
            """{"toolName":"run_commands","input":{"commands":["ls"]}}""",
            meta = Meta(toolCallId = "c1", toolOutput = "\u001B[32mok\u001B[0m\n"),
        )
        val model = buildToolRowModel(message, isRunActive = true)
        assertEquals("ok\n", toolOutputForCopy(model).substringAfterLast("truncated]\n"))
    }

    // ------------------------------------------------------------ row facts (built off Main, DS §6)

    private fun runningCommand(output: String) = msg(
        "t9",
        Role.Tool,
        """{"toolName":"run_commands","input":{"commands":["./gradlew test"]}}""",
        meta = Meta(toolCallId = "call_t9", toolOutput = output),
    )

    @Test
    fun `every message a row renders has its facts on that row`() {
        val messages = listOf(
            msg("u1", Role.User, at = t0),
            tool("t1", at = t0 + 1_000),
            msg("a1", Role.Assistant, "answer", at = t0 + 2_000, reasoning = "plan"),
        )
        val model = buildTranscriptModel(messages, ChatStatus.Completed, showInputPreview = false)
        val user = model.rows.first()
        assertEquals(1, user.factsOf("u1").runCount)
        val toolRow = model.rows.single { "t1" in it.facts }
        assertTrue(toolRow.factsOf("t1").tool != null)
        val assistant = model.factsOf("a1")
        assertTrue(assistant.isLastAssistant)
        assertEquals("plan", assistant.reasoning?.text)
        assertEquals(MessageFacts.NONE, user.factsOf("missing"))
    }

    @Test
    fun `a live output tick rebuilds only the running tool row and keeps the others equal`() {
        val cache = RowFactsCache()
        val done = tool("t1", at = t0 + 1_000)
        val first = listOf(msg("u1", Role.User, at = t0), done, runningCommand("line 1").copy(createdAt = t0 + 2_000))
        val before = buildTranscriptModel(first, ChatStatus.Running, cache = cache, showInputPreview = false)
        assertEquals(2, cache.toolBuilds)

        val second = first.dropLast(1) + runningCommand("line 1\nline 2").copy(createdAt = t0 + 2_000)
        val after = buildTranscriptModel(second, ChatStatus.Running, cache = cache, showInputPreview = false)
        assertEquals(3, cache.toolBuilds)
        assertTrue(before.factsOf("t1").tool === after.factsOf("t1").tool)
        assertFalse(before.factsOf("t9").tool == after.factsOf("t9").tool)
        assertEquals(before.rows.first(), after.rows.first())

        buildTranscriptModel(second, ChatStatus.Running, cache = cache, showInputPreview = false)
        assertEquals(3, cache.toolBuilds)
    }

    @Test
    fun `a host without proceed gets the row without Proceed while running`() {
        val detachable = runningCommand("working").copy(meta = Meta(toolCallId = "call_t9", toolOutput = "working", toolDetachable = true))
        val facts = toolRowFacts(detachable, isRunActive = true, showInputPreview = false)
        assertTrue(facts.modelFor(proceedAvailable = true).canProceed)
        assertFalse(facts.modelFor(proceedAvailable = false).canProceed)

        val plain = toolRowFacts(runningCommand("working"), isRunActive = true, showInputPreview = false)
        assertNull(plain.withoutProceed)
        assertTrue(plain.modelFor(proceedAvailable = false) === plain.model)
    }

    @Test
    fun `assistant media is validated with the model, not in composition`() {
        val media = listOf(GeneratedMedia("m1", MediaModality.Audio, "audio/mpeg", MediaSource.Base64("AAAA"), name = "a.mp3"))
        val messages = listOf(msg("u1", Role.User), msg("a1", Role.Assistant, "here", at = t0 + 1).copy(media = media))
        val model = buildTranscriptModel(messages, ChatStatus.Completed, showInputPreview = false)
        assertEquals(presentGeneratedMedia(media), model.factsOf("a1").media)
    }

    @Test
    fun `the shimmer sweeps from past the end to before the start`() {
        assertEquals(150f, shimmerCenter(100f, 0f))
        assertEquals(50f, shimmerCenter(100f, 0.5f))
        assertEquals(-50f, shimmerCenter(100f, 1f))
    }

    @Test
    fun `image caches scale with the heap within fixed bounds`() {
        val mib = 1024L * 1024L
        assertEquals(16 * mib, ImageCacheBudget.thumbnailBytes(128 * mib).toLong())
        assertEquals(32 * mib, ImageCacheBudget.thumbnailBytes(512 * mib).toLong())
        assertEquals(48 * mib, ImageCacheBudget.thumbnailBytes(4096 * mib).toLong())
        assertEquals(40 * mib, ImageCacheBudget.lightboxBytes(128 * mib).toLong())
        assertEquals(96 * mib, ImageCacheBudget.lightboxBytes(4096 * mib).toLong())
    }
}
