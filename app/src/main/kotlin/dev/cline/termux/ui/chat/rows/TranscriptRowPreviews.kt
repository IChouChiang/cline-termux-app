package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.data.chat.MediaSource
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.StopNotice
import dev.cline.termux.data.session.StopNoticeKind
import dev.cline.termux.data.session.StopNotices
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing

/*
 * Previews for every row state DS §4.1 #2–#5 / §4.2 and HC §3.4 list, light and dark
 * (`@LightDarkPreviews`), plus CJK variants (DS §2.3).
 */

private const val SESSION = "sess_preview"
private const val T0 = 1_759_480_000_000L

private fun msg(
    id: String,
    role: Role,
    content: String,
    at: Long = T0,
    meta: Meta = Meta(),
    reasoning: String? = null,
    redacted: Boolean = false,
    images: List<ChatImage> = emptyList(),
    media: List<GeneratedMedia> = emptyList(),
) = ChatMessage(
    id = id, sessionId = SESSION, role = role, content = content, reasoning = reasoning,
    reasoningRedacted = redacted, images = images, media = media, createdAt = at, meta = meta,
)

private fun tool(id: String, json: String, meta: Meta = Meta(toolCallId = "call_$id")) = msg(id, Role.Tool, json, meta = meta)

private val commandRunning = tool(
    "t1",
    """{"toolName":"run_commands","input":{"commands":["./gradlew test"]}}""",
    Meta(toolCallId = "call_t1", toolName = "run_commands", toolOutput = "> Task :app:testDebugUnitTest\n\u001B[32mBUILD SUCCESSFUL\u001B[0m in 41s", toolDetachable = true),
)
private val commandDone = tool(
    "t2",
    """{"toolName":"run_commands","input":{"commands":["echo hello-from-mock"]},"result":[{"query":"echo hello-from-mock","result":"hello-from-mock\n"}]}""",
)
private val commandError = tool(
    "t3",
    """{"toolName":"run_commands","input":{"commands":["bun test"]},"result":{"error":"exit code 1"},"isError":true}""",
)
private val editDone = tool(
    "t4",
    """{"toolName":"editor","input":{"path":"app/src/LoginViewModel.kt","old_text":"val s = session\nreturn s.user","new_text":"val s = session ?: return null\nreturn s.user"},"result":"ok"}""",
)
private val readDone = tool("t5", """{"toolName":"read_files","input":{"file_paths":["app.tsx"]},"result":"file text"}""")
private val notExpandable = tool("t6", """{"toolName":"plugins","input":{},"result":""}""")
private val cjkTool = tool("t7", """{"toolName":"search_codebase","input":{"queries":["登录崩溃"]},"result":"找到 3 个结果"}""")

private val noCallbacks = TranscriptCallbacks()
private val allCallbacks = TranscriptCallbacks(
    onEditMessage = { _, _, _ -> },
    onRestoreCheckpoint = { _, _ -> },
    onResend = {},
    onCredentialAction = {},
    onExpandImage = { _, _ -> },
    onProceedWhileRunning = { _, _ -> ProceedOutcome.Detached(1) },
    onOpenToolDetail = { _, _ -> },
)

/** A 1×1 PNG, enough to exercise the thumbnail path. */
private const val PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

@Composable
private fun PreviewColumn(content: @Composable () -> Unit) {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) { content() }
    }
}

@LightDarkPreviews
@Composable
private fun ToolRowCollapsedStatesPreview() {
    PreviewColumn {
        ToolCallRow(toolRowFacts(commandRunning, isRunActive = true), callbacks = noCallbacks)
        ToolCallRow(toolRowFacts(commandDone, isRunActive = false), callbacks = noCallbacks)
        ToolCallRow(toolRowFacts(commandError, isRunActive = false), callbacks = noCallbacks)
        ToolCallRow(toolRowFacts(editDone, isRunActive = false), callbacks = noCallbacks)
        ToolCallRow(toolRowFacts(readDone, isRunActive = false), callbacks = noCallbacks)
        ToolCallRow(toolRowFacts(notExpandable, isRunActive = false), callbacks = noCallbacks)
    }
}

@LightDarkPreviews
@Composable
private fun ToolRowRunningExpandedPreview() {
    PreviewColumn { ToolCallRow(toolRowFacts(commandRunning, isRunActive = true), callbacks = allCallbacks, initiallyOpen = true) }
}

@LightDarkPreviews
@Composable
private fun ToolRowErrorExpandedPreview() {
    PreviewColumn { ToolCallRow(toolRowFacts(commandError, isRunActive = false), callbacks = allCallbacks, initiallyOpen = true) }
}

@LightDarkPreviews
@Composable
private fun ToolRowEditDiffPreview() {
    PreviewColumn { ToolCallRow(toolRowFacts(editDone, isRunActive = false), callbacks = allCallbacks, initiallyOpen = true) }
}

@LightDarkPreviews
@Composable
private fun ToolRowCjkPreview() {
    PreviewColumn { ToolCallRow(toolRowFacts(cjkTool, isRunActive = false), callbacks = noCallbacks, initiallyOpen = true) }
}

@LightDarkPreviews
@Composable
private fun ReasoningStatesPreview() {
    PreviewColumn {
        ReasoningBlock(ReasoningContent("Checking the session guard…", false), streaming = true, durationMilliseconds = null, stateKey = "r1")
        ReasoningBlock(ReasoningContent("The crash comes from a null `session`.", false), streaming = false, durationMilliseconds = 3_200, stateKey = "r2")
        ReasoningBlock(ReasoningContent("", true), streaming = false, durationMilliseconds = 0, stateKey = "r3", initiallyOpen = true)
        ReasoningBlock(
            ReasoningContent("先检查登录流程，再看 `LoginViewModel` 的空指针。\n\n- 第一步\n- 第二步", false),
            streaming = false,
            durationMilliseconds = 12_000,
            stateKey = "r4",
            initiallyOpen = true,
        )
    }
}

@LightDarkPreviews
@Composable
private fun WorkBlockPreview() {
    PreviewColumn {
        WorkBlock(id = "w1", durationMilliseconds = 252_000, toolCallCount = 14) {}
        WorkBlock(id = "w2", durationMilliseconds = 3_000, toolCallCount = 1, initiallyOpen = true) {
            ToolCallRow(toolRowFacts(commandDone, isRunActive = false), callbacks = noCallbacks)
            ToolCallRow(toolRowFacts(readDone, isRunActive = false), callbacks = noCallbacks)
        }
        WorkBlock(id = "w3", durationMilliseconds = null, toolCallCount = 0) {}
    }
}

@LightDarkPreviews
@Composable
private fun RunGroupPreview() {
    PreviewColumn {
        RunGroup {
            ToolCallRow(toolRowFacts(readDone, isRunActive = true), callbacks = noCallbacks)
            ToolCallRow(toolRowFacts(commandRunning, isRunActive = true), callbacks = noCallbacks)
        }
    }
}

private val userMessage = msg(
    "u1", Role.User, "The login button crashes on tap, fix it", T0,
    Meta(runCount = 1, checkpoint = CheckpointRef("stash@{0}", T0, 1)),
)
private val cjkUser = msg("u2", Role.User, "登录按钮点击后崩溃了，请修复。顺便检查一下 `app/src/main` 下的日志。", T0)
private val imageUser = msg("u3", Role.User, "Look at this", T0, images = listOf(ChatImage("img1", "image/png", PIXEL_PNG)))
private val assistantDone = msg("a1", Role.Assistant, "The crash came from a null `session` in `LoginViewModel`. I added a guard:\n\n```kotlin\nif (session == null) return\n```", T0 + 60_000)
private val assistantStreaming = msg("a2", Role.Assistant, "Looking at the stack trace", T0 + 1_000)
private val assistantWithReasoning = msg("a3", Role.Assistant, "Done.", T0 + 5_000, reasoning = "Plan: guard, then test.")

private fun modelFor(vararg messages: ChatMessage, streaming: String? = null, status: ChatStatus = ChatStatus.Completed) =
    buildTranscriptModel(messages.toList(), status, streaming)

@LightDarkPreviews
@Composable
private fun UserBubblePreview() {
    val model = modelFor(userMessage)
    PreviewColumn {
        UserMessage(userMessage, model.factsOf(userMessage.id), MessageActionsState(), allCallbacks, TranscriptSelection(), initiallyRevealed = true)
        UserMessage(cjkUser, modelFor(cjkUser).factsOf(cjkUser.id), MessageActionsState(), allCallbacks, TranscriptSelection())
    }
}

@LightDarkPreviews
@Composable
private fun UserBubblePendingAndErrorPreview() {
    val model = modelFor(userMessage)
    PreviewColumn {
        UserMessage(userMessage, model.factsOf(userMessage.id), MessageActionsState(restorePendingId = "u1"), allCallbacks, TranscriptSelection())
        UserMessage(
            userMessage, model.factsOf(userMessage.id),
            MessageActionsState(restorePendingId = "u1", drainingMessageId = "u1"),
            allCallbacks, TranscriptSelection(),
        )
        UserMessage(
            userMessage, model.factsOf(userMessage.id),
            MessageActionsState(editErrors = mapOf("u1" to ActionError())),
            allCallbacks, TranscriptSelection(),
        )
    }
}

@LightDarkPreviews
@Composable
private fun UserBubbleEditPendingPreview() {
    PreviewColumn {
        UserMessage(
            userMessage, modelFor(userMessage).factsOf(userMessage.id),
            MessageActionsState(editPendingId = "u1"),
            allCallbacks, TranscriptSelection(), initiallyRevealed = true,
        )
    }
}

/** DS §4.1 #5: the copy button shows the check icon for 2 s after a copy. */
@LightDarkPreviews
@Composable
private fun ActionRowsCopiedPreview() {
    val state = MessageActionsState()
    PreviewColumn {
        UserActionRow(
            message = userMessage,
            displayText = userMessage.content,
            runCount = 1,
            actions = userActionSet(userMessage, userMessage.content, 1, state, canEdit = true, canRestore = true),
            copied = true,
            onCopy = {},
            callbacks = allCallbacks,
        )
        AssistantActionRow(message = assistantDone, copied = true, onCopy = {})
    }
}

/** HC §3.14 "Select text" mode on a user bubble and on an assistant answer. */
@LightDarkPreviews
@Composable
private fun SelectTextModePreview() {
    val userSelection = TranscriptSelection().apply { select("u1") }
    val assistantSelection = TranscriptSelection().apply { select("a1") }
    val item = ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.Assistant, assistantDone, emptyList())
    PreviewColumn {
        UserMessage(userMessage, modelFor(userMessage).factsOf(userMessage.id), MessageActionsState(), allCallbacks, userSelection)
        AssistantMessage(item, modelFor(userMessage, assistantDone).factsOf(assistantDone.id), allCallbacks, assistantSelection, isRunActive = false)
    }
}

/** HC §3.4.4 "Proceed while running": pending and error. */
@LightDarkPreviews
@Composable
private fun ProceedWhileRunningStatesPreview() {
    val model = dev.cline.termux.data.tools.buildToolRowModel(commandRunning, isRunActive = true)
    PreviewColumn {
        ProceedWhileRunning(model, allCallbacks, initiallyPending = true)
        ProceedWhileRunning(model, allCallbacks, initialError = stringResource(R.string.chat_tool_proceed_error))
    }
}

@LightDarkPreviews
@Composable
private fun UserBubbleImagePreview() {
    PreviewColumn { UserMessage(imageUser, modelFor(imageUser).factsOf(imageUser.id), MessageActionsState(), allCallbacks, TranscriptSelection()) }
}

@LightDarkPreviews
@Composable
private fun AssistantStatesPreview() {
    val done = modelFor(userMessage, assistantDone)
    val streaming = modelFor(userMessage, assistantStreaming, streaming = "a2", status = ChatStatus.Running)
    PreviewColumn {
        AssistantMessage(ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.Assistant, assistantStreaming, emptyList()), streaming.factsOf(assistantStreaming.id), allCallbacks, TranscriptSelection(), isRunActive = true)
        AssistantMessage(ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.Assistant, assistantDone, emptyList()), done.factsOf(assistantDone.id), allCallbacks, TranscriptSelection(), isRunActive = false)
    }
}

@LightDarkPreviews
@Composable
private fun AssistantReasoningAndMediaPreview() {
    val media = listOf(
        GeneratedMedia("m1", MediaModality.Image, "image/png", MediaSource.Base64(PIXEL_PNG), name = "chart.png"),
        GeneratedMedia("m2", MediaModality.Audio, "audio/mpeg", MediaSource.Base64("AAAA"), name = "summary.mp3"),
        GeneratedMedia("m3", MediaModality.Video, "video/mp4", MediaSource.Url("https://example.com/v.mp4")),
        GeneratedMedia("m4", MediaModality.File, "application/pdf", MediaSource.Artifact("artifact_42")),
        GeneratedMedia("m5", MediaModality.Image, "image/bmp", MediaSource.Base64("AAAA")),
    )
    val message = assistantWithReasoning.copy(media = media, images = listOf(ChatImage("g1", "image/png", PIXEL_PNG), ChatImage("g2", "image/png", PIXEL_PNG)))
    val model = modelFor(userMessage, message)
    PreviewColumn {
        AssistantMessage(ChatRenderItem.Message(dev.cline.termux.data.chat.AgentMessageRole.Assistant, message, listOf(message)), model.factsOf(message.id), allCallbacks, TranscriptSelection(), isRunActive = false)
    }
}

private val credentialError = msg(
    "e1", Role.Error, "401 Unauthorized", T0,
    Meta(reason = REASON_CREDENTIALS, providerId = "anthropic", providerAuth = ProviderAuthInfo("anthropic", listOf("api-key"))),
)
private val clineError = msg("e2", Role.Error, "", T0, Meta(reason = REASON_CREDENTIALS, providerId = "cline"))
private val interruptedError = msg("e3", Role.Error, "", T0, Meta(reason = REASON_INTERRUPTED))
private val plainError = msg("e4", Role.Error, "maximum context tokens exceeded", T0)

@LightDarkPreviews
@Composable
private fun ErrorMessagePreview() {
    val strings = previewRunErrorStrings()
    val model = modelFor(credentialError, clineError, interruptedError, plainError)
    PreviewColumn {
        ErrorMessage(credentialError, model.factsOf(credentialError.id), MessageActionsState(), allCallbacks, strings)
        ErrorMessage(clineError, model.factsOf(clineError.id), MessageActionsState(), allCallbacks, strings)
        ErrorMessage(interruptedError, model.factsOf(interruptedError.id), MessageActionsState(resendableIds = setOf("e3")), allCallbacks, strings)
        ErrorMessage(interruptedError, model.factsOf(interruptedError.id), MessageActionsState(resendableIds = setOf("e3"), resendPendingId = "e3"), allCallbacks, strings)
        ErrorMessage(plainError, model.factsOf(plainError.id), MessageActionsState(), allCallbacks, strings)
    }
}

@Composable
private fun previewRunErrorStrings() =
    dev.cline.termux.data.chat.RunErrorStrings.from(androidx.compose.ui.platform.LocalContext.current.resources)

@LightDarkPreviews
@Composable
private fun NoticeAndSystemPreview() {
    PreviewColumn {
        SessionNoticeRow(msg("n1", Role.Status, "Session resumed from history", meta = Meta(noticeType = "status")))
        SessionNoticeRow(msg("n2", Role.Status, "Compacted the conversation to fit the context window", meta = Meta(noticeType = "recovery", reason = "auto_compaction")))
        SessionNoticeRow(msg("n3", Role.Status, "Stopped by the user", meta = Meta(noticeType = "stop")))
        SessionNoticeRow(msg("n4", Role.Status, "会话已从历史记录恢复", meta = Meta(noticeType = "status")))
        // A17: the app's own stop notices after the mistake-limit question.
        SessionNoticeRow(
            StopNotices.row(
                StopNotice("preview", "m1", StopNoticeKind.Stopped, detail = "Tool no_such_tool does not exist", at = 0L),
            ),
        )
        SessionNoticeRow(StopNotices.row(StopNotice("preview", "m2", StopNoticeKind.TimedOut, at = 0L)))
        SystemMessage(msg("s1", Role.System, "Mode switched to Plan"))
    }
}

@LightDarkPreviews
@Composable
private fun TransportLinePreview() {
    PreviewColumn {
        ChatTransportState.entries.filter { it != ChatTransportState.Connected }.forEach { TransportLine(it) }
    }
}

@LightDarkPreviews
@Composable
private fun ErrorBannerPreview() {
    PreviewColumn {
        ErrorBanner("Couldn't load this session: request timed out", action = ErrorBannerAction.Retry {})
        ErrorBanner("Couldn't start the session: 401 Unauthorized", action = ErrorBannerAction.Credential(CredentialAction.Providers) {})
        ErrorBanner("Couldn't resume this session: hub_draining")
        ErrorBanner("same as the error row", showText = false, action = ErrorBannerAction.Retry {})
    }
}

@LightDarkPreviews
@Composable
private fun TranscriptItemsPreview() {
    val messages = listOf(
        userMessage,
        commandDone.copy(createdAt = T0 + 2_000),
        readDone.copy(createdAt = T0 + 3_000),
        assistantDone.copy(createdAt = T0 + 72_000),
    )
    val model = buildTranscriptModel(messages, ChatStatus.Completed)
    PreviewColumn {
        model.rows.forEach { row ->
            TranscriptItem(row, MessageActionsState(), allCallbacks, TranscriptSelection())
        }
    }
}

@Preview(name = "Font scale 2", fontScale = 2f, widthDp = 412)
@Composable
private fun ToolRowLargeFontPreview() {
    PreviewColumn {
        ToolCallRow(toolRowFacts(commandDone, isRunActive = false), callbacks = noCallbacks, initiallyOpen = true)
        WorkBlock(id = "w9", durationMilliseconds = 252_000, toolCallCount = 14) {}
    }
}

@Preview(name = "RTL", locale = "ar", widthDp = 412)
@Composable
private fun RtlPreview() {
    PreviewColumn {
        WorkBlock(id = "w8", durationMilliseconds = 3_000, toolCallCount = 2) {}
        ToolCallRow(toolRowFacts(commandDone, isRunActive = false), callbacks = noCallbacks, initiallyOpen = true)
        // The edit row's "+N -N" counts keep the desktop order while the row mirrors (DS §8.2.6).
        ToolCallRow(toolRowFacts(editDone, isRunActive = false), callbacks = noCallbacks)
        UserMessage(userMessage, modelFor(userMessage).factsOf(userMessage.id), MessageActionsState(), noCallbacks, TranscriptSelection(), modifier = Modifier, initiallyRevealed = true)
    }
}

