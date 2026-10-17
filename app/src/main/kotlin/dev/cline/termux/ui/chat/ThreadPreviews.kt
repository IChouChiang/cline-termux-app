package dev.cline.termux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.ui.chat.rows.ChatTransportState
import dev.cline.termux.ui.chat.rows.MessageActionsState
import dev.cline.termux.ui.chat.rows.ProceedOutcome
import dev.cline.termux.ui.chat.rows.TranscriptCallbacks
import dev.cline.termux.ui.chat.rows.buildTranscriptModel
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.ClinePreviewTheme

/*
 * Previews of the whole transcript (light and dark) with transcripts derived from the recorded
 * Hub traffic (docs/08 fixture 02: "Run echo hello-from-mock and summarize", its run_commands
 * call and the summary answer), stretched to a long session, a CJK session, a streaming turn,
 * error and kernel-down states, loading, switching and the missing session.
 */

private const val SESSION = "1791064689782_kck3p"
private const val T0 = 1_791_064_689_782L

internal object ThreadPreviewData {
    fun message(
        id: String,
        role: Role,
        content: String,
        at: Long,
        meta: Meta = Meta(),
        reasoning: String? = null,
        images: List<ChatImage> = emptyList(),
    ) = ChatMessage(id = id, sessionId = SESSION, role = role, content = content, reasoning = reasoning, images = images, createdAt = at, meta = meta)

    private const val SUMMARY = "The command ran successfully. Here is the summary.\n\n```bash\n\$ echo hello-from-mock\nhello-from-mock\n```\n\n" +
        "| Field | Value |\n| --- | --- |\n| Command | `echo hello-from-mock` |\n| Exit code | 0 |\n| Output lines | 1 |\n\n" +
        "- stdout contained exactly `hello-from-mock`\n- stderr was empty\n- nothing else was executed\n"

    /** One fixture-02 turn: prompt, narration, the approved command, the summary. */
    fun fixtureTurn(n: Int, at: Long, done: Boolean = true): List<ChatMessage> {
        val tool = if (done) {
            """{"toolName":"run_commands","input":{"commands":["echo hello-from-mock"]},"result":[{"query":"echo hello-from-mock","result":"hello-from-mock\n","success":true}]}"""
        } else {
            """{"toolName":"run_commands","input":{"commands":["echo hello-from-mock"]}}"""
        }
        val toolMeta = Meta(
            toolCallId = "call_mock_run_$n",
            toolName = "run_commands",
            toolOutput = if (done) null else "hello-from-mock\n",
            hookEventName = if (done) null else "tool_call_start",
        )
        return listOfNotNull(
            message("u$n", Role.User, "<user_input mode=\"act\">Run echo hello-from-mock and summarize</user_input>", at),
            message("a$n:1", Role.Assistant, "I'll run the command now.", at + 300, reasoning = "The user wants the output summarized.", meta = Meta(thoughtEndedAt = at + 3_300)),
            message("t$n", Role.Tool, tool, at + 4_000, toolMeta),
            if (done) message("a$n:2", Role.Assistant, SUMMARY, at + 9_000) else null,
        )
    }

    fun long(): List<ChatMessage> = (1..10).flatMap { fixtureTurn(it, T0 + it * 120_000L) }

    fun cjk(): List<ChatMessage> = listOf(
        message("cu1", Role.User, "登录按钮点击后崩溃，请修复。", T0),
        message(
            "ct1", Role.Tool,
            """{"toolName":"search_codebase","input":{"queries":["登录崩溃"]},"result":"找到 3 个结果"}""",
            T0 + 2_000, Meta(toolCallId = "call_cjk_1", toolName = "search_codebase"),
        ),
        message(
            "ca1", Role.Assistant,
            "崩溃来自 `LoginViewModel` 中为空的 `session`。我添加了空值检查和一个测试：\n\n```kotlin\nif (session == null) return\n```\n\n日本語の説明：セッションが空のときに戻ります。",
            T0 + 8_000,
        ),
    )

    fun streaming(): List<ChatMessage> = fixtureTurn(1, T0) + listOf(
        message("u2", Role.User, "Now run the tests", T0 + 60_000),
        message("a2:1", Role.Assistant, "Running the unit tests. The first part of the answer is arriving while", T0 + 61_000),
    )

    fun running(): List<ChatMessage> = fixtureTurn(1, T0) + fixtureTurn(2, T0 + 60_000, done = false)

    fun failed(): List<ChatMessage> = fixtureTurn(1, T0) + listOf(
        message("u2", Role.User, "Summarize the logs", T0 + 60_000),
        message(
            "e:req2", Role.Error, "401 Unauthorized", T0 + 61_000,
            Meta(reason = REASON_CREDENTIALS, providerId = "anthropic", providerAuth = ProviderAuthInfo("anthropic", listOf("api-key"))),
        ),
    )

    fun interrupted(): List<ChatMessage> = fixtureTurn(1, T0) + listOf(
        message("u2", Role.User, "Run sleep 60; echo done", T0 + 60_000),
        message("i:run2", Role.Error, "", T0 + 75_000, Meta(reason = REASON_INTERRUPTED, requestId = "req2")),
    )

    fun state(
        messages: List<ChatMessage>,
        status: ChatStatus = ChatStatus.Completed,
        streamingId: String? = null,
        kernel: KernelLine = KernelLine.Connected,
        banner: TranscriptBanner? = null,
        switching: Switching = Switching.None,
        missing: Boolean = false,
        actions: MessageActionsState = MessageActionsState(),
    ): TranscriptUiState {
        val model = buildTranscriptModel(messages, status, streamingId)
        val indicator = if (switching == Switching.None && !missing) TranscriptController.indicatorOf(model, messages, status, streamingId, 0, 0) else null
        return TranscriptUiState.Empty.copy(
            sessionId = SESSION,
            model = model,
            status = status,
            kernel = kernel,
            transport = if (banner == null) kernel.transport else ChatTransportState.Connected,
            indicator = indicator,
            banner = banner,
            switching = switching,
            missing = missing,
            actions = actions.copy(busy = status.isBusy, switching = switching != Switching.None),
            userMessageCount = messages.count { it.role == Role.User },
            lastAssistantText = messages.lastOrNull { it.role == Role.Assistant }?.content,
        )
    }

    val callbacks = TranscriptCallbacks(
        onResend = {},
        onCredentialAction = {},
        onExpandImage = { _, _ -> },
        onProceedWhileRunning = { _, _ -> ProceedOutcome.Detached(1) },
        onOpenToolDetail = { _, _ -> },
    )
}

@Composable
private fun TranscriptPreview(state: TranscriptUiState) {
    ClinePreviewTheme {
        Surface {
            Box(Modifier.fillMaxSize()) {
                ChatTranscript(state = state, callbacks = remember { ThreadPreviewData.callbacks })
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun TranscriptLongPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.long()))
}

@LightDarkPreviews
@Composable
private fun TranscriptCjkPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.cjk()))
}

/** DS §8.2.6: in an RTL layout the tool labels' commands and paths stay LTR-isolated. */
@LightDarkPreviews
@Composable
private fun TranscriptRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.long()))
    }
}

@LightDarkPreviews
@Composable
private fun TranscriptStreamingPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.streaming(), ChatStatus.Running, streamingId = "a2:1"))
}

@LightDarkPreviews
@Composable
private fun TranscriptRunningToolPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.running(), ChatStatus.Running))
}

@LightDarkPreviews
@Composable
private fun TranscriptThinkingPreview() {
    val messages = ThreadPreviewData.fixtureTurn(1, T0) + ThreadPreviewData.message("u2", Role.User, "Now run the tests", T0 + 60_000)
    TranscriptPreview(ThreadPreviewData.state(messages, ChatStatus.Starting))
}

@LightDarkPreviews
@Composable
private fun TranscriptFailedPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.failed(), ChatStatus.Failed))
}

@LightDarkPreviews
@Composable
private fun TranscriptInterruptedPreview() {
    TranscriptPreview(
        ThreadPreviewData.state(ThreadPreviewData.interrupted(), ChatStatus.Idle, actions = MessageActionsState(resendableIds = setOf("i:run2"))),
    )
}

@LightDarkPreviews
@Composable
private fun TranscriptResumeErrorBannerPreview() {
    val banner = TranscriptBanner(
        error = SessionError(SessionError.Kind.Resume, "Hub refused the session config"),
        text = "Couldn't resume this session: Hub refused the session config",
        showText = true,
        action = TranscriptBannerAction.Retry(SessionError.Kind.Resume),
    )
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.fixtureTurn(1, T0), kernel = KernelLine.Reconnecting, banner = banner))
}

@LightDarkPreviews
@Composable
private fun TranscriptKernelAsleepPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.fixtureTurn(1, T0), kernel = KernelLine.TermuxAsleep))
}

@LightDarkPreviews
@Composable
private fun TranscriptReconnectingPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.fixtureTurn(1, T0), kernel = KernelLine.Reconnecting))
}

@LightDarkPreviews
@Composable
private fun TranscriptLoadingPreview() {
    TranscriptPreview(ThreadPreviewData.state(emptyList(), ChatStatus.Idle, switching = Switching.Loading))
}

@LightDarkPreviews
@Composable
private fun TranscriptSwitchingPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.long(), switching = Switching.Switching))
}

@LightDarkPreviews
@Composable
private fun TranscriptMissingPreview() {
    TranscriptPreview(ThreadPreviewData.state(emptyList(), ChatStatus.Idle, missing = true))
}

@Preview(name = "Tablet, expanded column", widthDp = 1280, heightDp = 800)
@Composable
private fun TranscriptTabletPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.long()))
}

@Preview(name = "Font scale 2", fontScale = 2f, widthDp = 412, heightDp = 900)
@Composable
private fun TranscriptLargeFontPreview() {
    TranscriptPreview(ThreadPreviewData.state(ThreadPreviewData.streaming(), ChatStatus.Running, streamingId = "a2:1"))
}
