package dev.cline.termux.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.session.CompactionMode
import dev.cline.termux.data.session.CompactionSaveError
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/*
 * Settings → Context compaction (decision A11 row 2): LLM / Truncation / Off, written to the
 * kernel's global-settings.json (seam S1) and so shared with Cline in the terminal; every
 * `session.create` after that carries it (G1, `CompactionModeSource`).
 */

/** The Models group's compaction row (S1). */
@Immutable
data class CompactionRowState(
    /** The stored choice, or the one being saved; null while unknown (not read yet, or unreadable). */
    val mode: CompactionMode? = null,
    /** The write runs: spinner, taps ignored. */
    val saving: Boolean = false,
    /** Termux commands are unavailable: disabled with "Start kernel to change this." */
    val needsKernel: Boolean = false,
) {
    val enabled: Boolean get() = !needsKernel && !saving

    companion object {
        /**
         * [stored] from `CompactionModeSource`, [pending] the choice being saved. The row needs
         * RUN_COMMAND and the installed kernel (the merge runs the kernel's Bun): Termux missing
         * or not permitted ([termuxBlock]), the gate blocked on a prerequisite other than the
         * Hub, or connected without commands.
         */
        fun of(stored: CompactionMode?, pending: CompactionMode?, gate: KernelGate, termuxBlock: KernelBlock?): CompactionRowState =
            CompactionRowState(
                mode = pending ?: stored,
                saving = pending != null,
                needsKernel = termuxBlock != null || gate == KernelGate.DegradedNoCommands || (gate is KernelGate.Blocked && gate.focus != CheckId.Hub),
            )
    }
}

/** One-shot effects of Settings home (snackbars). */
sealed interface SettingsHomeEvent {
    /** "Couldn't save the compaction setting: <reason>"; the row already rolled back. */
    data class CompactionSaveFailed(val error: CompactionSaveError) : SettingsHomeEvent
}

@Composable
fun compactionLabel(mode: CompactionMode): String = stringResource(
    when (mode) {
        CompactionMode.Llm -> R.string.session_config_compaction_llm
        CompactionMode.Truncation -> R.string.session_config_compaction_truncation
        CompactionMode.Off -> R.string.session_config_compaction_off
    },
)

/** The snackbar sentence for a failed save. */
fun Context.compactionSaveFailedText(error: CompactionSaveError): String {
    val reason = when (error) {
        CompactionSaveError.FileUnreadable -> getString(R.string.session_config_compaction_reason_unreadable)
        CompactionSaveError.TermuxUnavailable -> getString(R.string.session_config_compaction_reason_termux)
        is CompactionSaveError.Failed -> error.detail.trim().ifEmpty { getString(R.string.session_config_compaction_reason_failed) }
    }
    return getString(R.string.session_config_compaction_save_failed, reason)
}

/**
 * The row: "Context compaction" with the current label (or "Start kernel to change this.") and
 * a saving spinner; a tap opens the single-choice dialog, whose pick goes to [onSelect].
 */
@Composable
internal fun CompactionRow(state: CompactionRowState, onSelect: (CompactionMode) -> Unit) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val supporting = when {
        state.needsKernel -> stringResource(R.string.session_config_compaction_needs_kernel)
        state.mode != null -> compactionLabel(state.mode)
        else -> null
    }
    val savingText = stringResource(R.string.session_config_compaction_saving)
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_config_compaction)) },
        supportingContent = supporting?.let { { Text(it, maxLines = 2) } },
        leadingContent = { Icon(ClineIcons.Layers, contentDescription = null, modifier = Modifier.size(24.dp)) },
        trailingContent = if (state.saving) {
            { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
        colors = rowColors(selected = false, enabled = !state.needsKernel),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(enabled = state.enabled, role = Role.Button) { dialogOpen = true }
            .semantics(mergeDescendants = true) { if (state.saving) stateDescription = savingText },
    )
    if (dialogOpen) {
        CompactionDialog(
            current = state.mode,
            onSelect = { mode ->
                dialogOpen = false
                if (mode != state.mode) onSelect(mode)
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

/** LLM / Truncation / Off and the shared-with-the-terminal line; "Save" applies the pick. */
@Composable
private fun CompactionDialog(current: CompactionMode?, onSelect: (CompactionMode) -> Unit, onDismiss: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf(current) }
    ConfirmDialog(
        title = stringResource(R.string.session_config_compaction),
        text = stringResource(R.string.session_config_compaction_note),
        confirmLabel = stringResource(R.string.components_save),
        onConfirm = { choice?.let(onSelect) ?: onDismiss() },
        onDismiss = onDismiss,
        content = {
            Column(Modifier.selectableGroup()) {
                Spacer(Modifier.size(Spacing.s))
                CompactionMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .focusRing(RectangleShape, inset = true)
                            .selectable(selected = choice == mode, role = Role.RadioButton, onClick = { choice = mode }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == mode, onClick = null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(compactionLabel(mode), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}

