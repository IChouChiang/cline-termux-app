package dev.cline.termux.ui.checkpoint

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * C1 "Revert to this checkpoint?", C2 "Edit and restart from here?" and the lost-reply
 * "Restore status unknown" dialog (CT C.5, C.7, C.8, §1.6), plus the seams the chat screen
 * uses for the message rows. Desktop `chat-messages.tsx` AlertDialogs; the chat-only /
 * chat-and-workspace choice and the git warning come from the CLI's checkpoint-confirm.tsx.
 */

/** Stateless callbacks of [CheckpointDialogs]. */
@Immutable
data class CheckpointDialogActions(
    val onSelectWorkspace: (Boolean) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onCheckpointLost: () -> Unit,
    val onLostReplyOpenSessions: () -> Unit,
    val onLostReplyClose: () -> Unit,
)

/**
 * Everything the chat screen hosts for checkpoints: C1/C2, the lost-reply dialog, the C3
 * sheet and the snackbars. Collects [CheckpointViewModel.eventFlow]; [onSessionReplaced]
 * performs C.6 step 7 (navigate with `popUpTo(chat) { inclusive = true }`, composer prefill)
 * and [onOpenSessions] opens the sessions screen.
 */
@Composable
fun CheckpointHost(
    viewModel: CheckpointViewModel,
    onSessionReplaced: (CheckpointEvent.SessionReplaced) -> Unit,
    onOpenSessions: () -> Unit,
    onKernelAction: (KernelAction) -> Unit,
    pickerReturnFocus: FocusRequester? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val replaced by rememberUpdatedState(onSessionReplaced)
    val openSessions by rememberUpdatedState(onOpenSessions)
    val statusJob = remember { arrayOfNulls<Job>(1) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is CheckpointEvent.Snackbar -> {
                    val snack = event.snack
                    val text = snackText(resources, snack)
                    val duration = when (snack) {
                        is CheckpointSnack.Status -> SnackbarDuration.Indefinite
                        is CheckpointSnack.Failed -> SnackbarDuration.Long
                        else -> SnackbarDuration.Short
                    }
                    val job = scope.launch { snackbar.showReplacing(text, duration = duration) }
                    if (snack is CheckpointSnack.Status) statusJob[0] = job
                }
                CheckpointEvent.DismissStatus -> {
                    statusJob[0]?.cancel()
                    statusJob[0] = null
                }
                is CheckpointEvent.SessionReplaced -> replaced(event)
                CheckpointEvent.OpenSessions -> openSessions()
            }
        }
    }

    CheckpointDialogs(
        state = state,
        actions = remember(viewModel) {
            CheckpointDialogActions(
                onSelectWorkspace = viewModel::selectWorkspace,
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::dismissDialog,
                onCheckpointLost = viewModel::onCheckpointLost,
                onLostReplyOpenSessions = viewModel::openSessionsFromLostReply,
                onLostReplyClose = viewModel::dismissLostReply,
            )
        },
    )
    state.picker?.let { picker ->
        CheckpointPickerSheet(
            picker = picker,
            gate = gate,
            onPick = viewModel::pickRow,
            onRetry = viewModel::retryPicker,
            onDismiss = viewModel::dismissPicker,
            onKernelAction = onKernelAction,
            returnFocus = pickerReturnFocus,
        )
    }
}

/** C1 / C2 and the lost-reply dialog for [state]. */
@Composable
fun CheckpointDialogs(state: CheckpointUiState, actions: CheckpointDialogActions) {
    state.dialog?.let { dialog ->
        LaunchedEffect(dialog.checkpointLost) { if (dialog.checkpointLost) actions.onCheckpointLost() }
        if (!dialog.checkpointLost) CheckpointConfirmDialog(dialog, actions)
    }
    if (state.lostReply) {
        AlertDialog(
            onDismissRequest = actions.onLostReplyClose,
            icon = { Icon(ClineIcons.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.checkpoint_lost_title)) },
            text = { Text(stringResource(R.string.checkpoint_lost_text)) },
            confirmButton = { TextButton(onClick = actions.onLostReplyOpenSessions) { Text(stringResource(R.string.checkpoint_open_sessions)) } },
            dismissButton = { TextButton(onClick = actions.onLostReplyClose) { Text(stringResource(R.string.components_close)) } },
        )
    }
}

@Composable
private fun CheckpointConfirmDialog(dialog: CheckpointDialogUi, actions: CheckpointDialogActions) {
    val revert = dialog.kind == RestoreKind.Revert
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        icon = { Icon(if (revert) ClineIcons.Undo else ClineIcons.Edit, contentDescription = null) },
        title = { Text(stringResource(if (revert) R.string.checkpoint_revert_title else R.string.checkpoint_edit_title)) },
        text = { DialogBody(dialog, actions.onSelectWorkspace) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirm,
                enabled = dialog.confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (revert) R.string.checkpoint_revert_confirm else R.string.checkpoint_edit_confirm))
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.components_cancel)) } },
    )
}

@Composable
private fun DialogBody(dialog: CheckpointDialogUi, onSelectWorkspace: (Boolean) -> Unit) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (dialog.preview.isNotEmpty()) {
            Text(
                stringResource(R.string.checkpoint_restore_to, dialog.preview),
                style = MaterialTheme.typography.bodyMedium,
                color = variant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (dialog.showOptions) {
            val focus = remember { FocusRequester() }
            Column(Modifier.selectableGroup()) {
                if (dialog.workspaceOptionAvailable) {
                    OptionRow(
                        label = stringResource(R.string.checkpoint_opt_workspace),
                        detail = stringResource(R.string.checkpoint_opt_workspace_detail),
                        selected = dialog.workspaceSelected,
                        onClick = { onSelectWorkspace(true) },
                        modifier = if (dialog.workspaceSelected) Modifier.focusRequester(focus) else Modifier,
                    )
                }
                OptionRow(
                    label = stringResource(R.string.checkpoint_opt_chat),
                    detail = stringResource(R.string.checkpoint_opt_chat_detail),
                    selected = !dialog.workspaceSelected,
                    onClick = { onSelectWorkspace(false) },
                    modifier = if (!dialog.workspaceSelected) Modifier.focusRequester(focus) else Modifier,
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
        Text(
            stringResource(consequenceText(dialog)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (dialog.workspaceSelected) GitWarning()
        Text(stringResource(R.string.checkpoint_history_note), style = MaterialTheme.typography.bodySmall, color = variant)
        ErrorLines(dialog)
    }
}

/** The consequence text for the current choice (C.7). */
internal fun consequenceText(dialog: CheckpointDialogUi): Int = when {
    dialog.kind == RestoreKind.Revert && dialog.workspaceSelected -> R.string.checkpoint_revert_text_ws
    dialog.kind == RestoreKind.Revert -> R.string.checkpoint_revert_text_chat
    !dialog.hasCheckpoint -> R.string.checkpoint_edit_text_none
    dialog.workspaceSelected -> R.string.checkpoint_edit_text_ws
    else -> R.string.checkpoint_edit_text_chat
}

@Composable
private fun OptionRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.padding(vertical = Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GitWarning() {
    val warning = ClineTheme.colors.warning.color
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            ClineIcons.Warning,
            contentDescription = stringResource(R.string.checkpoint_warning_cd),
            tint = warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.checkpoint_git_warning), style = MaterialTheme.typography.bodySmall, color = warning)
    }
}

@Composable
private fun ErrorLines(dialog: CheckpointDialogUi) {
    val error = MaterialTheme.colorScheme.error
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    dialog.refusal?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = error, modifier = polite) }
    when {
        dialog.kernel == CheckpointKernelLine.NotConnected ->
            Text(stringResource(R.string.hub_not_connected), style = MaterialTheme.typography.bodySmall, color = error, modifier = polite)
        dialog.kernel == CheckpointKernelLine.Reconnecting ->
            Text(
                stringResource(R.string.kernel_status_reconnecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = polite,
            )
        dialog.busy -> Text(stringResource(R.string.checkpoint_busy), style = MaterialTheme.typography.bodySmall, color = error, modifier = polite)
    }
}

// ---- message-row seams ------------------------------------------------------------------------

/*
 * The rows read the checkpoint state through `CheckpointRowActions.of` (ChatUiState.kt), the one
 * mapping into [dev.cline.termux.ui.chat.rows.MessageActionsState]; the drain line under the
 * originating row is drawn by the user bubble (`UserMessage`).
 */

/** The C.5 error sentence for [error]. */
internal fun rowErrorText(resources: Resources, error: RowError): String {
    val fallback = when {
        error.notConnected -> resources.getString(R.string.hub_not_connected)
        error.kind == RestoreKind.Revert -> resources.getString(R.string.checkpoint_restore_error)
        else -> resources.getString(R.string.checkpoint_edit_error)
    }
    val base = if (error.workspaceRestored) {
        resources.getString(R.string.checkpoint_split_partial, error.message ?: fallback)
    } else {
        error.message
    }
    return when {
        error.pickerMode && error.noResult && !error.workspaceRestored -> resources.getString(R.string.checkpoint_failed_no_result)
        error.pickerMode -> resources.getString(R.string.checkpoint_failed, base ?: fallback)
        else -> base ?: fallback
    }
}

internal fun snackText(resources: Resources, snack: CheckpointSnack): String = when (snack) {
    CheckpointSnack.Restored -> resources.getString(R.string.checkpoint_restored)
    CheckpointSnack.InProgress -> resources.getString(R.string.checkpoint_in_progress)
    CheckpointSnack.Busy -> resources.getString(R.string.checkpoint_busy)
    CheckpointSnack.RestoreError -> resources.getString(R.string.checkpoint_restore_error)
    is CheckpointSnack.Status -> resources.getString(if (snack.workspace) R.string.checkpoint_status_ws else R.string.checkpoint_status_chat)
    is CheckpointSnack.Failed -> rowErrorText(resources, snack.error)
}

// ---- previews ---------------------------------------------------------------------------------

private val PreviewActions = CheckpointDialogActions({}, {}, {}, {}, {}, {})

private fun previewDialog(
    kind: RestoreKind = RestoreKind.Revert,
    hasCheckpoint: Boolean = true,
    workspace: Boolean = true,
    workspaceAvailable: Boolean = true,
    refusal: String? = null,
    kernel: CheckpointKernelLine = CheckpointKernelLine.Connected,
    busy: Boolean = false,
) = CheckpointDialogUi(
    kind = kind,
    messageId = "m1",
    runCount = 2,
    preview = "Add a dark mode toggle to settings…",
    pickerMode = false,
    hasCheckpoint = hasCheckpoint,
    restoreWorkspace = workspace,
    workspaceOptionAvailable = workspaceAvailable,
    refusal = refusal,
    kernel = kernel,
    busy = busy,
)

@Composable
private fun DialogPreview(dialog: CheckpointDialogUi) {
    ComponentPreview(padding = false) { CheckpointDialogs(CheckpointUiState(dialog = dialog), PreviewActions) }
}

@LightDarkPreviews
@Composable
private fun RevertWorkspacePreview() = DialogPreview(previewDialog())

@LightDarkPreviews
@Composable
private fun RevertChatOnlyPreview() = DialogPreview(previewDialog(workspace = false))

@LightDarkPreviews
@Composable
private fun EditWorkspacePreview() = DialogPreview(previewDialog(kind = RestoreKind.Edit))

@LightDarkPreviews
@Composable
private fun EditNoCheckpointPreview() = DialogPreview(previewDialog(kind = RestoreKind.Edit, hasCheckpoint = false))

@LightDarkPreviews
@Composable
private fun KernelDownPreview() = DialogPreview(previewDialog(kernel = CheckpointKernelLine.NotConnected))

@LightDarkPreviews
@Composable
private fun ReconnectingPreview() = DialogPreview(previewDialog(kernel = CheckpointKernelLine.Reconnecting))

@LightDarkPreviews
@Composable
private fun BusyPreview() = DialogPreview(previewDialog(busy = true))

@LightDarkPreviews
@Composable
private fun RefusedCommitsPreview() = DialogPreview(
    previewDialog(
        workspace = false,
        refusal = "Cannot restore the workspace: 1 commit was added to the current branch after this checkpoint and would be removed by the restore. " +
            "Restore the chat only, or move the branch back to 3f2a1c9 manually (e.g. with git reset) before restoring the workspace.",
    ),
)

@LightDarkPreviews
@Composable
private fun RefusedNotRepoPreview() = DialogPreview(
    previewDialog(workspace = false, workspaceAvailable = false, refusal = "/data/data/com.termux/files/home/demo is not a git repository"),
)

@LightDarkPreviews
@Composable
private fun LostReplyPreview() {
    ComponentPreview(padding = false) { CheckpointDialogs(CheckpointUiState(lostReply = true), PreviewActions) }
}

@LightDarkPreviews
@Composable
private fun RowErrorPreview() {
    ComponentPreview {
        Column {
            Text(
                rowErrorText(LocalContext.current.resources, RowError(RestoreKind.Edit, null, workspaceRestored = true)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
