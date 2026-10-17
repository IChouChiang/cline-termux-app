package dev.cline.termux.ui.composer

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import dev.cline.termux.R
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.openAppDetails
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.showReplacingUntil
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** The snackbar text of [message] (strings_composer.xml); kernel text is passed through as data. */
fun ComposerMessage.text(context: Context): String {
    val r = context.resources
    return when (this) {
        ComposerMessage.ImagesUnsupported -> r.getString(R.string.composer_images_unsupported)
        ComposerMessage.NeedsText -> r.getString(R.string.composer_needs_text)
        ComposerMessage.ConnectModel -> r.getString(R.string.composer_connect_model)
        ComposerMessage.TooManyImages -> r.getString(R.string.attach_too_many_images)
        ComposerMessage.ImageTooLarge -> r.getString(R.string.attach_image_too_large)
        ComposerMessage.TotalTooLarge -> r.getString(R.string.attach_total_too_large)
        ComposerMessage.ImageUnreadable -> r.getString(R.string.attach_image_unreadable)
        ComposerMessage.UnsupportedImageType -> r.getString(R.string.attach_unsupported_image_type)
        is ComposerMessage.FileTooLarge -> r.getString(R.string.attach_file_too_large, name)
        is ComposerMessage.FileNotText -> r.getString(R.string.attach_file_not_text, name)
        is ComposerMessage.FileFailed -> r.getString(R.string.attach_file_failed, name)
        ComposerMessage.ResumeFailed -> r.getString(R.string.composer_resume_failed)
        is ComposerMessage.SendFailed -> r.getString(R.string.composer_send_failed, message)
        ComposerMessage.SendFailedDisconnected -> r.getString(R.string.composer_send_failed_disconnected)
        ComposerMessage.SendFailedKernelDown -> r.getString(R.string.chat_not_sent)
        ComposerMessage.RunInterrupted -> r.getString(R.string.composer_run_interrupted)
        ComposerMessage.StopFailed -> r.getString(R.string.composer_stop_failed)
        ComposerMessage.TeamDisabled -> r.getString(R.string.composer_team_disabled)
        ComposerMessage.TeamUsage -> r.getString(R.string.composer_team_usage)
        ComposerMessage.TeamPlanMode -> r.getString(R.string.composer_team_unavailable_mode)
        is ComposerMessage.WorkflowReadFailed -> r.getString(R.string.composer_workflow_read_failed, name)
        is ComposerMessage.SkillReadFailed -> r.getString(R.string.composer_skill_read_failed, name)
        is ComposerMessage.Voice -> when (val e = error) {
            VoiceError.Permission, VoiceError.PermissionPermanentlyDenied -> r.getString(R.string.voice_failed_permission)
            VoiceError.Network -> r.getString(R.string.voice_err_network)
            VoiceError.Busy -> r.getString(R.string.voice_err_busy)
            VoiceError.Audio -> r.getString(R.string.voice_err_audio)
            VoiceError.Unavailable -> r.getString(R.string.voice_err_unavailable)
            is VoiceError.Generic -> r.getString(R.string.voice_failed_generic, e.name)
        }
        ComposerMessage.SteerSent -> r.getString(R.string.session_config_steer_sent)
    }
}

/** The action label of [action]. */
fun ComposerMessageAction.label(context: Context): String = context.getString(
    when (this) {
        ComposerMessageAction.Retry -> R.string.composer_action_retry
        ComposerMessageAction.Resend -> R.string.composer_action_resend
        ComposerMessageAction.ConnectModel -> R.string.composer_action_connect_model
        ComposerMessageAction.OpenTools -> R.string.composer_action_open_tools
        ComposerMessageAction.Settings -> R.string.voice_action_settings
        ComposerMessageAction.RestartKernel -> R.string.kernel_restart
    },
)

/**
 * Shows the controller's messages on the shell's snackbar host, one at a time (DS #17), and
 * runs their actions: Retry/Resend resend the field, Settings opens the app details,
 * "Connect a model" and "Open Tools" go to the host, "Restart kernel" goes to the shell's
 * kernel action (A4: the Kernel status sheet's restart and its confirm). A message raised for
 * one session is dismissed when the composer moves to another ([sessionId]): its Retry or
 * Resend would otherwise send the new session's field. "The agent did not stop" is also
 * dismissed once it no longer stands ([ComposerController.awaitStopFailureEnd], trial row 11).
 */
@Composable
fun ComposerMessagesEffect(
    controller: ComposerController,
    sessionId: String?,
    onConnectModel: () -> Unit,
    onOpenTools: () -> Unit,
) {
    val host = LocalSnackbarHost.current
    val context = LocalContext.current
    val connect by rememberUpdatedState(onConnectModel)
    val tools by rememberUpdatedState(onOpenTools)
    val currentSession by rememberUpdatedState(sessionId)
    val kernel by rememberUpdatedState(LocalShellKernelUi.current)
    LaunchedEffect(controller, host) {
        controller.messages.collect { message ->
            val action = message.action
            val origin = currentSession
            val result = host.showReplacingUntil(
                message = message.text(context),
                actionLabel = action?.label(context),
                duration = if (action == null) SnackbarDuration.Long else SnackbarDuration.Indefinite,
            ) {
                // A first send from Home (null -> new id) keeps the message; a switch drops it.
                val left = snapshotFlow { currentSession }.filter { origin != null && it != origin }.map { }
                val settled = if (message == ComposerMessage.StopFailed) flow { controller.awaitStopFailureEnd(); emit(Unit) } else emptyFlow()
                merge(left, settled).first()
            }
            if (result != SnackbarResult.ActionPerformed || currentSession != origin && origin != null) return@collect
            when (action) {
                ComposerMessageAction.Retry, ComposerMessageAction.Resend -> controller.send()
                ComposerMessageAction.ConnectModel -> connect()
                ComposerMessageAction.OpenTools -> tools()
                // The microphone permission lives on the app details page (C7.4).
                ComposerMessageAction.Settings -> context.openAppDetails()
                ComposerMessageAction.RestartKernel -> kernel?.onAction?.invoke(KernelAction.RestartKernel)
                null -> Unit
            }
        }
    }
}
