package dev.cline.termux.ui.sessions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cline.termux.R
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing
import java.text.BreakIterator

/** F5 step 1: the quoted title is the first line, cut at [MAX_GRAPHEMES] grapheme clusters with "…". */
object DeleteDialogText {
    const val MAX_GRAPHEMES = 120

    fun quotedTitle(title: String): String {
        val line = title.lineSequence().firstOrNull().orEmpty()
        val it = BreakIterator.getCharacterInstance()
        it.setText(line)
        var count = 0
        var end = it.first()
        while (true) {
            val next = it.next()
            if (next == BreakIterator.DONE) return line
            count += 1
            if (count > MAX_GRAPHEMES) return line.substring(0, end) + "…"
            end = next
        }
    }

    /** "The running task will be stopped." applies to Running and Pending sessions (the daemon stops them first). */
    fun stopsRunningTask(status: KernelSessionStatus): Boolean =
        status == KernelSessionStatus.Running || status == KernelSessionStatus.Pending

    /**
     * G3: the session's workspace is a task worktree (desktop sessions-view.tsx:
     * `isTaskWorktreePath(workspacePath)`), so the dialog adds TASK_WORKTREE_DELETE_WARNING.
     */
    fun ranInTaskWorktree(summary: SessionSummary): Boolean = WorktreeCommands.isTaskWorktreePath(summary.workspaceRoot)
}

/**
 * F5 Delete session? (DS §4.1 #16, voice `DeleteSessionDialog` mechanics): "Cancel" takes the
 * initial focus, "Delete" is a `TextButton` in `error`; while [deleting] the confirm shows a
 * spinner and "Deleting...", both buttons are disabled and back / outside taps are ignored
 * (desktop blocks dismissal while deleting). Never auto-confirms.
 */
@Composable
fun DeleteSessionDialog(
    summary: SessionSummary,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val title = DeleteDialogText.quotedTitle(summary.displayTitle())
    val body = stringResource(R.string.sessions_delete_body, title)
    val withRunning = if (DeleteDialogText.stopsRunningTask(summary.kernelStatus)) {
        stringResource(R.string.sessions_a11y_join_sentence, body, stringResource(R.string.sessions_delete_body_running))
    } else {
        body
    }
    val text = if (DeleteDialogText.ranInTaskWorktree(summary)) {
        stringResource(R.string.sessions_a11y_join_sentence, withRunning, stringResource(R.string.worktree_delete_warning))
    } else {
        withRunning
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
        title = { Text(stringResource(R.string.sessions_delete_title)) },
        text = { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (deleting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineSpinner()
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.sessions_deleting))
                    }
                } else {
                    Text(stringResource(R.string.sessions_delete_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.components_cancel))
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

@LightDarkPreviews
@Composable
private fun DeleteSessionDialogPreview() {
    ComponentPreview {
        DeleteSessionDialog(SessionsPreviewData.rows[1].summary, deleting = false, onConfirm = {}, onDismiss = {})
    }
}

@LightDarkPreviews
@Composable
private fun DeleteSessionDialogRunningPreview() {
    ComponentPreview {
        DeleteSessionDialog(SessionsPreviewData.rows[0].summary, deleting = false, onConfirm = {}, onDismiss = {})
    }
}

@LightDarkPreviews
@Composable
private fun DeleteSessionDialogDeletingPreview() {
    ComponentPreview {
        DeleteSessionDialog(SessionsPreviewData.rows[1].summary, deleting = true, onConfirm = {}, onDismiss = {})
    }
}
