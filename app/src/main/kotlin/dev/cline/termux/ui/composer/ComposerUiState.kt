package dev.cline.termux.ui.composer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.cline.termux.data.chat.ContextUsage
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.ui.kernel.ComposerGate
import java.io.File

/** C1.1: Home (no session yet) or a thread. */
enum class ComposerVariant { Welcome, Conversation }

/** Why the trailing slot shows the Starting spinner while the field keeps its text (C1.6). */
enum class HoldReason {
    /** `session_not_found` / not-live session: "Resuming session...". */
    ResumingSession,

    /** `hub_draining`: the held-for-Hub note; resent on `hub.drain_changed {draining:false}`. */
    HubDraining,

    /** Reading a workflow or skill file for a slash command (C5.7 step 3/4: spinner in the Send slot). */
    ReadingCommand,

    /**
     * A direct send waits for the run-boundary check (decided A1): the kernel's auto-approve
     * setting is re-read through Termux, and an idle session whose tool policies differ restarts
     * first. Spinner in the Send slot; shown as [ResumingSession] once that restart is under way.
     */
    PreparingSend,
}

/** Text attachment write progress (C1.9 text step 3). */
enum class FileAttachState { Writing, Ready, Failed }

/** One chip above the field (C1.7 "Attachment chip"). */
@Immutable
sealed interface DraftAttachment {
    val id: String
    val displayName: String

    @Immutable
    data class Image(
        override val id: String,
        override val displayName: String,
        val cacheFile: File,
        val mime: String,
        /** Data URL payload size (`4 * ceil(bytes / 3)`), summed for the 6 MB rule. */
        val base64Bytes: Long,
        val thumbnail: ImageBitmap?,
    ) : DraftAttachment

    @Immutable
    data class TextFile(
        override val id: String,
        override val displayName: String,
        /** `<dir>/<name>` inside Termux once written. */
        val termuxPath: String?,
        val state: FileAttachState,
    ) : DraftAttachment
}

/** The ModelChip (C1.11; DS #7). Names are kernel data, shown untranslated. */
@Immutable
data class ModelChipState(
    val providerId: String?,
    val providerName: String?,
    val modelId: String?,
    val modelName: String?,
    /** `!isBusy` (desktop `disabled={isBusy}`); the first-run chip stays enabled (C1.2). */
    val enabled: Boolean,
    /** null = unknown → images allowed. */
    val supportsImages: Boolean?,
    val supportsReasoning: Boolean?,
    val contextWindow: Long?,
) {
    /** No provider/model resolved: the label is "Model" (desktop placeholder). */
    val resolved: Boolean get() = providerId != null && modelId != null

    companion object {
        val Empty = ModelChipState(null, null, null, null, enabled = true, supportsImages = null, supportsReasoning = null, contextWindow = null)
    }
}

/** The ReasoningChip (C1.7; desktop `effortLabel`). */
@Immutable
data class ReasoningChipState(
    val label: ReasoningChipLabel,
    /** `supportsReasoning == true`. */
    val enabled: Boolean,
    /** Disabled because the model lacks support (tooltip "The selected model does not report reasoning support"). */
    val unsupportedReason: Boolean,
) {
    companion object {
        val Unknown = ReasoningChipState(ReasoningChipLabel.Generic, enabled = false, unsupportedReason = false)
    }
}

/** The usage ring and sheet data (C1.11 "Usage derivation"). */
@Immutable
data class ComposerUsage(val usage: ContextUsage, val totalCostUsd: Double?)

/** The voice slot (C7.6). */
@Immutable
sealed interface VoiceState {
    /** No recogniser: the mic is not composed. */
    data object Unavailable : VoiceState
    data object Idle : VoiceState

    /** [level] 0..1 from the RMS mapping; [partial] is the live text of this utterance. */
    data class Listening(val level: Float, val partial: String) : VoiceState

    /** Stop pressed, waiting for the final result (≤ 3 s). */
    data object Processing : VoiceState

    val active: Boolean get() = this is Listening || this == Processing
}

@Immutable
data class ComposerUiState(
    val variant: ComposerVariant,
    /** null on Home. */
    val sessionId: String?,
    val phase: RunPhase,
    /** The kernel's say over the composer (ComposerGate.of(KernelGate), D12). */
    val kernel: ComposerGate,
    /** Text or attachments. */
    val hasDraft: Boolean,
    val attachments: List<DraftAttachment>,
    /** `supportsImages == false` and at least one image chip (C1.6 "Unsupported images"). */
    val imagesUnsupported: Boolean,
    val mode: PromptMode,
    val model: ModelChipState,
    val reasoning: ReasoningChipState,
    /** null = ring hidden (always on the welcome variant). */
    val usage: ComposerUsage?,
    val queueSize: Int,
    /** The first queued prompt is already steering (C1.8 empty-Enter rule). */
    val firstQueuedSteering: Boolean,
    val voice: VoiceState,
    val holdReason: HoldReason?,
    val canSend: Boolean,
    val isBusy: Boolean,
    val canAbort: Boolean,
    val hardwareKeyboard: Boolean,
    /** Session switching: the only state where the field is disabled (C1.6 "Loading"). */
    val loading: Boolean,
    /** The open session was deleted elsewhere: the NoticeBar replaces the composer. */
    val sessionDeleted: Boolean,
    /** No configured provider (C1.6 "First run"); null while the catalog is not read. */
    val providerConfigured: Boolean?,
    /** No terminal event within the Hub's command timeout after Stop (A4): Stop is offered again (C1.7). */
    val stopTimedOut: Boolean = false,
    /**
     * Trial row 11: "The agent did not stop. Try again." stands ([ComposerMessage.StopFailed]): a Stop
     * failed and the run it was about is still going. False once that run is over, once a later Stop's
     * `run.abort` is accepted, and on any other session; its snackbar goes with it.
     */
    val stopFailed: Boolean = false,
    /**
     * The bound session's own state has arrived and is not loading, so the field stays enabled:
     * a focus request waits for it (a disabled field drops focus). Always true on Home.
     */
    val settled: Boolean = true,
    /** A11.6: the recalled prompt the field shows unchanged (the "Recent prompt n of N" chip); null when not browsing. */
    val history: HistoryBrowse? = null,
    /** A11.6: the field is empty or shows [history] unchanged and is editable, so a fling steps the recent prompts. */
    val historyGestures: Boolean = false,
    /** A11.6: an older prompt may follow ("Previous prompt"); true before the file was read. */
    val historyOlderAvailable: Boolean = false,
) {
    /** Field read-only: kernel down, resuming, or voice active (C1.3). */
    val readOnly: Boolean get() = kernel.readOnly || holdReason == HoldReason.ResumingSession || voice.active

    /** Send visible (desktop: hidden while busy unless there is a draft). */
    val showSend: Boolean get() = !isBusy || hasDraft

    /** The trailing Starting spinner (C1.3 "Starting slot", hold states). */
    val showStartingSlot: Boolean get() = (phase == RunPhase.Starting && !hasDraft) || holdReason != null

    /** C8: Home only, the catalog says no provider, and the kernel is usable. */
    val showSetupNotice: Boolean
        get() = variant == ComposerVariant.Welcome && providerConfigured == false && !kernel.readOnly

    /** Stop shows the spinner and is disabled. */
    val stopping: Boolean get() = phase == RunPhase.Stopping

    companion object {
        fun initial(variant: ComposerVariant, sessionId: String?) = ComposerUiState(
            variant = variant,
            sessionId = sessionId,
            phase = if (sessionId == null) RunPhase.NoSession else RunPhase.Idle,
            kernel = ComposerGate.Usable,
            hasDraft = false,
            attachments = emptyList(),
            imagesUnsupported = false,
            mode = PromptMode.Act,
            model = ModelChipState.Empty,
            reasoning = ReasoningChipState.Unknown,
            usage = null,
            queueSize = 0,
            firstQueuedSteering = false,
            voice = VoiceState.Unavailable,
            holdReason = null,
            canSend = false,
            isBusy = false,
            canAbort = false,
            hardwareKeyboard = false,
            loading = false,
            sessionDeleted = false,
            providerConfigured = null,
        )
    }
}

/** A11.6 browsing: the field shows recent prompt [index] (1 = the newest) of [total]. */
@Immutable
data class HistoryBrowse(val index: Int, val total: Int) {
    val hasOlder: Boolean get() = index < total
}

/** What the snackbar says after a composer action; the UI maps each to its string (strings_composer.xml). */
sealed interface ComposerMessage {
    val action: ComposerMessageAction? get() = null

    data object ImagesUnsupported : ComposerMessage
    data object NeedsText : ComposerMessage
    data object ConnectModel : ComposerMessage {
        override val action get() = ComposerMessageAction.ConnectModel
    }
    data object TooManyImages : ComposerMessage
    data object ImageTooLarge : ComposerMessage
    data object TotalTooLarge : ComposerMessage
    data object ImageUnreadable : ComposerMessage
    data object UnsupportedImageType : ComposerMessage
    data class FileTooLarge(val name: String) : ComposerMessage
    data class FileNotText(val name: String) : ComposerMessage
    data class FileFailed(val name: String) : ComposerMessage
    data object ResumeFailed : ComposerMessage {
        override val action get() = ComposerMessageAction.Retry
    }
    data class SendFailed(val message: String) : ComposerMessage {
        override val action get() = ComposerMessageAction.Retry
    }
    data object SendFailedDisconnected : ComposerMessage {
        override val action get() = ComposerMessageAction.Retry
    }

    /** HC §3.12 step 6: no Retry, the kernel has to be started first (the composer shows Start kernel). */
    data object SendFailedKernelDown : ComposerMessage
    data object RunInterrupted : ComposerMessage {
        override val action get() = ComposerMessageAction.Resend
    }
    /**
     * Stop did not take: its `run.abort` failed, or the run did not end within the Hub's command
     * timeout (A4). "Restart kernel" is the way out, as on the approval sheet's Stop. Shown while
     * [ComposerUiState.stopFailed] holds (trial row 11).
     */
    data object StopFailed : ComposerMessage {
        override val action get() = ComposerMessageAction.RestartKernel
    }
    data object TeamDisabled : ComposerMessage {
        override val action get() = ComposerMessageAction.OpenTools
    }
    data object TeamUsage : ComposerMessage
    data object TeamPlanMode : ComposerMessage
    data class WorkflowReadFailed(val name: String) : ComposerMessage
    data class SkillReadFailed(val name: String) : ComposerMessage
    data class Voice(val error: VoiceError) : ComposerMessage {
        override val action get() = if (error == VoiceError.PermissionPermanentlyDenied) ComposerMessageAction.Settings else null
    }

    /** Long-press Send while busy (A11 row 5): "Sent to the running agent". */
    data object SteerSent : ComposerMessage
}

enum class ComposerMessageAction {
    Retry, Resend, ConnectModel, OpenTools, Settings,

    /** `KernelAction.RestartKernel` through the shell (A4). */
    RestartKernel,
}

/** C7.6 error mapping. */
sealed interface VoiceError {
    /** `ERROR_INSUFFICIENT_PERMISSIONS` or a denied request. */
    data object Permission : VoiceError

    /** Denied with "Don't ask again": the snackbar adds "Settings". */
    data object PermissionPermanentlyDenied : VoiceError
    data object Network : VoiceError
    data object Busy : VoiceError
    data object Audio : VoiceError
    data object Unavailable : VoiceError

    /** Any other recogniser error after one recreate; [name] is the `ERROR_*` constant name. */
    data class Generic(val name: String) : VoiceError
}

/** One-shot effects for the composable (keyboard, dialogs). */
sealed interface ComposerEffect {
    /** Close the IME after a busy send (DS §5.7.4); an idle send keeps it open. */
    data object HideKeyboard : ComposerEffect

    /** Ask the field for focus (Home "New session", C1.13). */
    data object RequestFocus : ComposerEffect

    /** A11.6: a fling or TalkBack action stepped the recent prompts (haptic tick; [browse] null = left browsing). */
    data class HistoryStepped(val browse: HistoryBrowse?) : ComposerEffect
}
