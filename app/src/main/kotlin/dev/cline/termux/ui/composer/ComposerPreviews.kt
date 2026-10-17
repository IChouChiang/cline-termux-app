package dev.cline.termux.ui.composer

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.ComposerGate
import dev.cline.termux.ui.kernel.ComposerKernelButton
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.theme.ThemeMode
import java.io.File

// Every C1.6 / C7.4 state, light and dark (DS §11), plus the four C1.14 large-font previews.

private val sonnet = ModelChipState(
    providerId = "anthropic",
    providerName = "Anthropic",
    modelId = "claude-sonnet-4-6",
    modelName = "Claude Sonnet 4.6",
    enabled = true,
    supportsImages = true,
    supportsReasoning = true,
    contextWindow = 200_000,
)

private val idle = ComposerUiState.initial(ComposerVariant.Conversation, "s1").copy(
    phase = RunPhase.Idle,
    model = sonnet,
    reasoning = ReasoningChipState(ReasoningChipLabel.Level(ReasoningEffort.Low), enabled = true, unsupportedReason = false),
    usage = previewUsage(12_034, 311, 8_192, 200_000, 0.0421),
    voice = VoiceState.Idle,
    providerConfigured = true,
)

private fun image(name: String) = DraftAttachment.Image(name, name, File(name), "image/png", 120_000, thumbnail = null)

@Composable
private fun PreviewHost(
    layout: ShellLayout = ShellLayout.PhonePortrait,
    dark: Boolean? = null,
    fontSize: Int? = null,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        CompositionLocalProvider(LocalShellLayout provides layout) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.width(layout.widthDp.dp).padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) { content() }
            }
        }
    }
    if (fontSize != null) {
        dev.cline.termux.ui.theme.ClineTheme(appearance = AppearancePrefs(themeMode = ThemeMode.Light, dynamicColor = false, fontSize = fontSize), content = body)
    } else if (dark != null) {
        ClinePreviewTheme(dark = dark, content = body)
    } else {
        ClinePreviewTheme(content = body)
    }
}

@Composable
private fun Sample(
    state: ComposerUiState,
    text: String = "",
    row: dev.cline.termux.ui.kernel.ComposerKernelRow = state.kernel.row,
    button: ComposerKernelButton? = null,
    suggestion: (@Composable () -> Unit)? = null,
    queue: (@Composable () -> Unit)? = null,
    imeOpen: Boolean = false,
) {
    val draft = remember(text) { TextFieldState(text) }
    ComposerSurface(state, draft, row, button, ComposerCallbacks.None, ringOpen = false, suggestionPanel = suggestion, queueStrip = queue, imeOpen = imeOpen)
}

/** Stands in for the C5 slash panel in previews (its own lane draws the real one). */
@Composable
private fun SuggestionPanelStandIn() {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        listOf("/compact", "/newtask").forEach { command ->
            Text(
                command,
                style = ClineMono.body,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
        }
    }
}

/** Stands in for the C2 strip in previews (its own lane draws the real one). */
@Composable
private fun QueueStripStandIn() {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        Icon(ClineIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("2", modifier = Modifier.padding(start = Spacing.s), color = ClineTheme.colors.info.color)
    }
}

@LightDarkPreviews
@Composable
private fun ComposerIdlePreview() = PreviewHost { Sample(idle) }

@LightDarkPreviews
@Composable
private fun ComposerWelcomePreview() = PreviewHost {
    WelcomeSetupNotice(onConnectModel = {}, onModelSettings = {})
    Sample(ComposerUiState.initial(ComposerVariant.Welcome, null).copy(model = ModelChipState.Empty, voice = VoiceState.Idle, providerConfigured = false))
}

@LightDarkPreviews
@Composable
private fun ComposerTypingAttachmentsPreview() = PreviewHost {
    Sample(
        idle.copy(
            hasDraft = true,
            canSend = true,
            attachments = listOf(image("screenshot-1.png"), image("IMG_20261003_120000_HDR.jpg"), DraftAttachment.TextFile("f", "build.log", null, FileAttachState.Writing)),
        ),
        text = "Fix the layout shown in these two screenshots",
    )
}

/** Wide-script names: chipLabel's 24-grapheme cut, then the width cap middle-ellipsizes (DS §2.3). */
private val cjkAttachments = listOf(
    image("二〇二六年十月三日の設計レビュー議事録と画面キャプチャ一覧.png"),
    DraftAttachment.TextFile("cjk", "构建日志与崩溃报告完整输出记录.log", "/tmp/cjk.log", FileAttachState.Ready),
)

@LightDarkPreviews
@Composable
private fun ComposerCjkAttachmentsPreview() = PreviewHost {
    Sample(idle.copy(hasDraft = true, canSend = true, attachments = cjkAttachments), text = "これらの画面を確認してください")
}

@Preview(name = "CJK attachment chips, font scale 2 × size 20", fontScale = 2f, widthDp = 412)
@Composable
private fun ComposerCjkAttachmentsLargeFontPreview() = PreviewHost(fontSize = 20) {
    Sample(idle.copy(hasDraft = true, canSend = true, attachments = cjkAttachments), text = "これらの画面を確認してください")
}

@LightDarkPreviews
@Composable
private fun ComposerRunningQueuePreview()= PreviewHost {
    Sample(idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, queueSize = 2), queue = { QueueStripStandIn() })
}

@LightDarkPreviews
@Composable
private fun ComposerRunningDraftPreview() = PreviewHost {
    Sample(idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, hasDraft = true, canSend = true, model = sonnet.copy(enabled = false)), text = "Then run the tests too")
}

@LightDarkPreviews
@Composable
private fun ComposerStartingPreview() = PreviewHost {
    Sample(idle.copy(phase = RunPhase.Starting, isBusy = true, model = sonnet.copy(enabled = false)))
    Sample(idle.copy(phase = RunPhase.Stopping, isBusy = true, canAbort = true, model = sonnet.copy(enabled = false)))
}

/** Home's first send before `session.create` replies: the conversation variant with no session id yet. */
@LightDarkPreviews
@Composable
private fun ComposerFirstSendStartingPreview() = PreviewHost {
    Sample(
        ComposerUiState.initial(ComposerVariant.Conversation, null).copy(
            phase = RunPhase.Starting,
            isBusy = true,
            model = sonnet.copy(enabled = false),
            voice = VoiceState.Idle,
            providerConfigured = true,
        ),
    )
}

/** The parent turn finished while spawned agents still run: Stop stays (desktop `hasRunningAgents`). */
@LightDarkPreviews
@Composable
private fun ComposerAgentsRunningPreview() = PreviewHost {
    Sample(idle.copy(phase = RunPhase.Idle, isBusy = false, canAbort = true))
}

@LightDarkPreviews
@Composable
private fun ComposerKernelDownPreview() = PreviewHost {
    val down = ComposerGate.of(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null)
    Sample(idle.copy(kernel = down), text = "Draft kept while Termux sleeps", button = ComposerKernelButton(KernelAction.StartKernel, inFlight = false))
    Sample(idle.copy(kernel = down), button = ComposerKernelButton(KernelAction.StartKernel, inFlight = true))
    Sample(idle.copy(kernel = ComposerGate.of(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), 25463)))
}

@LightDarkPreviews
@Composable
private fun ComposerUnsupportedImagesPreview() = PreviewHost {
    Sample(
        idle.copy(attachments = listOf(image("photo.jpg")), imagesUnsupported = true, hasDraft = true, canSend = true, model = sonnet.copy(supportsImages = false)),
        text = "What is wrong in this photo?",
    )
}

@LightDarkPreviews
@Composable
private fun ComposerVoicePreview() = PreviewHost {
    Sample(idle.copy(voice = VoiceState.Listening(0.6f, "fix the parser")), text = "Fix the parser so that empty lines are skipped")
    Sample(idle.copy(voice = VoiceState.Processing))
}

@LightDarkPreviews
@Composable
private fun ComposerHoldPreview() = PreviewHost {
    Sample(idle.copy(holdReason = HoldReason.HubDraining, hasDraft = false))
    Sample(idle.copy(holdReason = HoldReason.ResumingSession), text = "Continue from where we stopped")
}

/** A11.6 browsing with A18's compact chip (no outline, no icon), then the same chip while the kernel sleeps. */
private val browsing = idle.copy(history = HistoryBrowse(2, 20), historyGestures = true, historyOlderAvailable = true, hasDraft = true, canSend = true)

@LightDarkPreviews
@Composable
private fun ComposerHistoryBrowsingPreview() = PreviewHost {
    Sample(browsing, text = "Fix the flaky parser test")
    Sample(
        browsing.copy(kernel = ComposerGate.of(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null)),
        text = "Fix the flaky parser test",
        button = ComposerKernelButton(KernelAction.StartKernel, inFlight = false),
    )
}

/** The compact chip's label ellipsizes before the × slot leaves the chip (320dp, 2.0 × 20). */
@Preview(name = "History chip, font scale 2 × size 20, 320dp", fontScale = 2f, widthDp = 320)
@Composable
private fun ComposerHistoryBrowsingLargeFontPreview() = PreviewHost(layout = ShellLayout(320, 640), fontSize = 20) {
    Sample(browsing.copy(history = HistoryBrowse(20, 20), historyOlderAvailable = false), text = "Fix the flaky parser test")
}

@LightDarkPreviews
@Composable
private fun ComposerSessionDeletedPreview() = PreviewHost { SessionDeletedNotice(onNewSession = {}) }

@Preview(name = "Tablet expanded", widthDp = 840)
@Composable
private fun ComposerTabletPreview() = PreviewHost(layout = ShellLayout(1138, 712)) {
    Sample(idle.copy(model = sonnet.copy(modelName = "Claude Sonnet 4.6 (1M context, extended thinking)")))
}

@Preview(name = "Height-compact", widthDp = 915, heightDp = 412)
@Composable
private fun ComposerLandscapePreview() = PreviewHost(layout = ShellLayout(915, 412)) {
    Sample(idle.copy(hasDraft = true, canSend = true), text = "One\nTwo\nThree\nFour\nFive")
}

/** C1.4 step 6: with the IME open, the queue strip is dropped and the suggestion panel stays. */
@Preview(name = "Height-compact, IME open, slash panel + queued prompts", widthDp = 915, heightDp = 412)
@Composable
private fun ComposerLandscapeImeSuggestionPreview() = PreviewHost(layout = ShellLayout(915, 412)) {
    Sample(
        idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, hasDraft = true, canSend = true, queueSize = 2),
        text = "/",
        suggestion = { SuggestionPanelStandIn() },
        queue = { QueueStripStandIn() },
        imeOpen = true,
    )
}

/**
 * DS §9 disabled icon-only controls on a narrow row, each dimmed to M3's disabled content colour:
 * the model chip while busy, the reasoning chip without model support, attach while read-only.
 */
@LightDarkPreviews
@Composable
private fun ComposerDisabledIconControlsPreview() = PreviewHost(layout = ShellLayout(320, 640)) {
    val actions = SettingsRowActions({}, {}, {}, {}, {}, {}, {})
    ComposerSettingsRow(
        state = idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, model = sonnet.copy(enabled = false)),
        compact = true,
        ringOpen = false,
        actions = actions,
    )
    ComposerSettingsRow(
        state = idle.copy(
            model = sonnet.copy(supportsReasoning = false),
            reasoning = ReasoningChipState(ReasoningChipLabel.Generic, enabled = false, unsupportedReason = true),
        ),
        compact = true,
        ringOpen = false,
        actions = actions,
    )
    ComposerSettingsRow(
        state = idle.copy(kernel = ComposerGate.of(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null)),
        compact = true,
        ringOpen = false,
        actions = actions,
    )
}

/**
 * C1.4 on the measured lane, not the window class: an expanded window's list-detail chat pane
 * (≈ 376dp at 840dp: icon-only chips), a 480dp pane and a wide pane ("Provider / Model" and the
 * effort label). The ModelChip never scrolls out of view under the send button.
 */
@Preview(name = "Settings row by pane width, light", widthDp = 840)
@Preview(name = "Settings row by pane width, dark", widthDp = 840, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun ComposerSettingsRowPaneWidthsPreview() = PreviewHost(layout = ShellLayout(840, 712)) {
    val actions = SettingsRowActions({}, {}, {}, {}, {}, {}, {})
    listOf(376, 480, 760).forEach { width ->
        Box(Modifier.width(width.dp)) {
            ComposerSettingsRow(state = idle.copy(hasDraft = true, canSend = true), compact = false, ringOpen = false, actions = actions)
        }
    }
}

@Preview(name = "Settings row, font scale 2 × size 20", fontScale = 2f, widthDp = 412)
@Composable
private fun ComposerSettingsRowLargeFontPreview() = PreviewHost(fontSize = 20) {
    ComposerSettingsRow(
        state = idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, hasDraft = true, canSend = true, mode = PromptMode.Plan),
        compact = true,
        ringOpen = false,
        actions = SettingsRowActions({}, {}, {}, {}, {}, {}, {}),
    )
}

@Preview(name = "Composer, font scale 2 × size 20", fontScale = 2f, widthDp = 412)
@Composable
private fun ComposerLargeFontPreview() = PreviewHost(fontSize = 20) {
    Sample(idle.copy(hasDraft = true, canSend = true), text = "Large text")
    WelcomeSetupNotice(onConnectModel = {}, onModelSettings = {})
}

/** DS §8.1.8: the busy and queue placeholders wrap instead of clipping at 2.0 × 20. */
@Preview(name = "Composer busy placeholder, font scale 2 × size 20", fontScale = 2f, widthDp = 412)
@Composable
private fun ComposerLargeFontBusyPreview() = PreviewHost(fontSize = 20) {
    Sample(idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, model = sonnet.copy(enabled = false)))
    Sample(idle.copy(phase = RunPhase.Running, isBusy = true, canAbort = true, queueSize = 2, hardwareKeyboard = true, model = sonnet.copy(enabled = false)))
}
