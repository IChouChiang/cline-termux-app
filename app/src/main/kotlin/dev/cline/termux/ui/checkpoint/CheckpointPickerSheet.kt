package dev.cline.termux.ui.checkpoint

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.checkpoint.GitDiagnosis
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.theme.Spacing

/*
 * C3 "Restore to Checkpoint" (the CLI's `/undo` picker, CT C.5, C.8, C3.5): a ClineSheet with
 * the restorable messages oldest first, scrolled to and focused on the newest. A row opens
 * C2 in picker mode; the sheet closes while C2 is up and returns on Cancel.
 */

/** Commands the C3.5 empty state copies (shell commands, not translatable copy). */
internal const val GIT_INIT_COMMAND = "git init"
internal const val PKG_INSTALL_GIT_COMMAND = "pkg install git"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointPickerSheet(
    picker: CheckpointPickerUi,
    gate: KernelGate,
    onPick: (CheckpointPickerRow) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onKernelAction: (KernelAction) -> Unit,
    returnFocus: FocusRequester? = null,
) {
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.checkpoint_picker_title),
        returnFocus = returnFocus,
    ) {
        PickerBody(picker, gate, onPick, onRetry, onKernelAction)
    }
}

@Composable
private fun PickerBody(
    picker: CheckpointPickerUi,
    gate: KernelGate,
    onPick: (CheckpointPickerRow) -> Unit,
    onRetry: () -> Unit,
    onKernelAction: (KernelAction) -> Unit,
) {
    val gutter = Modifier.padding(horizontal = Spacing.sheetGutter)
    if (picker.kernel != CheckpointKernelLine.Connected) {
        KernelBannerHost(gate = gate, hasComposer = false, onAction = onKernelAction, modifier = gutter.padding(vertical = Spacing.s))
    }
    if (picker.refreshing || picker.content == PickerContent.Loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        Spacer(Modifier.height(Spacing.xs))
    }
    when (val content = picker.content) {
        PickerContent.Loading -> Spacer(Modifier.height(Spacing.huge))
        is PickerContent.LoadError -> PickerMessage(
            icon = ClineIcons.Error,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.checkpoint_picker_load_error),
            body = content.message,
            bodySmall = true,
            action = { FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.components_retry)) } },
        )
        is PickerContent.NoHistory -> NoHistory(content.diagnosis)
        PickerContent.NoRows -> PickerMessage(
            icon = ClineIcons.History,
            title = stringResource(R.string.checkpoint_picker_no_rows),
            body = stringResource(R.string.checkpoint_picker_no_rows_body),
        )
        is PickerContent.Rows -> {
            Text(
                stringResource(if (picker.busy) R.string.checkpoint_picker_busy else R.string.checkpoint_picker_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = gutter.padding(bottom = Spacing.s),
            )
            PickerRows(content.rows, picker.rowsEnabled, onPick)
        }
    }
}

@Composable
private fun NoHistory(diagnosis: GitDiagnosis?) {
    val copier = rememberPlainCopier()
    val label = stringResource(R.string.checkpoint_picker_command_label)
    val command = when (diagnosis) {
        GitDiagnosis.NotARepository -> GIT_INIT_COMMAND
        GitDiagnosis.NoGit -> PKG_INSTALL_GIT_COMMAND
        else -> null
    }
    PickerMessage(
        icon = ClineIcons.History,
        title = stringResource(R.string.checkpoint_picker_empty),
        body = diagnosis?.let { stringResource(diagnosisText(it)) },
        loading = diagnosis == null,
        action = command?.let {
            {
                TextButton(onClick = { copier.copy(label, it) }) {
                    Icon(ClineIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.checkpoint_picker_copy_command))
                }
            }
        },
    )
}

/** C3.5 body per diagnosis. */
internal fun diagnosisText(diagnosis: GitDiagnosis): Int = when (diagnosis) {
    GitDiagnosis.NoGit -> R.string.checkpoint_picker_no_git
    GitDiagnosis.NotARepository -> R.string.checkpoint_picker_not_repo
    GitDiagnosis.RepositoryOk -> R.string.checkpoint_picker_repo_ok
    GitDiagnosis.Unknown -> R.string.checkpoint_picker_unknown
}

/** EmptyState inside the sheet, with a tintable icon (the load error's icon is `error`). */
@Composable
private fun PickerMessage(
    icon: ImageVector,
    title: String,
    body: String?,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bodySmall: Boolean = false,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(Spacing.l))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (loading) {
                Spacer(Modifier.height(Spacing.m))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (body != null) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    body,
                    style = if (bodySmall) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(Spacing.l))
                action()
            }
        }
    }
}

@Composable
private fun PickerRows(rows: List<CheckpointPickerRow>, enabled: Boolean, onPick: (CheckpointPickerRow) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = rows.lastIndex.coerceAtLeast(0))
    val newest = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    val actionLabel = stringResource(R.string.checkpoint_picker_row_action)
    LazyColumn(state = listState) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val relative = DateUtils.getRelativeTimeSpanString(row.time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
            val time = if (row.planMode) stringResource(R.string.checkpoint_picker_row_time_plan, relative) else relative
            val description = stringResource(R.string.checkpoint_picker_row_cd, row.preview, time)
            val pick = { onPick(row) }
            ListItem(
                headlineContent = { Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text(time) },
                leadingContent = { Icon(ClineIcons.Undo, contentDescription = null, modifier = Modifier.size(24.dp)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .then(if (index == rows.lastIndex) Modifier.focusRequester(newest) else Modifier)
                    .focusRing(RectangleShape, inset = true)
                    .clickable(enabled = enabled, onClick = pick)
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .padding(horizontal = Spacing.s)
                    .clearAndSetSemantics {
                        contentDescription = description
                        if (enabled) {
                            onClick { pick(); true }
                            customActions = listOf(CustomAccessibilityAction(actionLabel) { pick(); true })
                        } else {
                            disabled()
                        }
                    },
            )
        }
    }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex)
            runCatching { newest.requestFocus() }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

// ---- previews ---------------------------------------------------------------------------------

private val PreviewRows = listOf(
    CheckpointPickerRow("m1", "m1", 1, "Create hello.py that prints hello", System.currentTimeMillis() - 12 * 60_000, planMode = false),
    CheckpointPickerRow("m3", "m3", 2, "Change it to print hi", System.currentTimeMillis() - 8 * 60_000, planMode = true),
    CheckpointPickerRow("m5", "m5", 3, "Add a dark mode toggle to settings…", System.currentTimeMillis() - 60_000, planMode = false),
)

@Composable
private fun SheetPreview(content: PickerContent, busy: Boolean = false, kernel: CheckpointKernelLine = CheckpointKernelLine.Connected, refreshing: Boolean = false) {
    ComponentPreview(padding = false) {
        Column {
            PickerBody(
                picker = CheckpointPickerUi(content, refreshing = refreshing, busy = busy, kernel = kernel),
                gate = if (kernel == CheckpointKernelLine.Connected) KernelGate.Ready else KernelGate.Down(KernelGate.DownVariant.Stopped, null),
                onPick = {},
                onRetry = {},
                onKernelAction = {},
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun PickerPopulatedPreview() = SheetPreview(PickerContent.Rows(PreviewRows))

@LightDarkPreviews
@Composable
private fun PickerLoadingPreview() = SheetPreview(PickerContent.Loading, refreshing = true)

@LightDarkPreviews
@Composable
private fun PickerBusyPreview() = SheetPreview(PickerContent.Rows(PreviewRows), busy = true)

@LightDarkPreviews
@Composable
private fun PickerKernelDownPreview() = SheetPreview(PickerContent.Rows(PreviewRows), kernel = CheckpointKernelLine.NotConnected)

@LightDarkPreviews
@Composable
private fun PickerLoadErrorPreview() = SheetPreview(PickerContent.LoadError("Unknown session: 4b1c"))

@LightDarkPreviews
@Composable
private fun PickerNoRowsPreview() = SheetPreview(PickerContent.NoRows)

@LightDarkPreviews
@Composable
private fun PickerDiagnosingPreview() = SheetPreview(PickerContent.NoHistory(null))

@LightDarkPreviews
@Composable
private fun PickerNotRepoPreview() = SheetPreview(PickerContent.NoHistory(GitDiagnosis.NotARepository))

@LightDarkPreviews
@Composable
private fun PickerNoGitPreview() = SheetPreview(PickerContent.NoHistory(GitDiagnosis.NoGit))

@LightDarkPreviews
@Composable
private fun PickerRepoOkPreview() = SheetPreview(PickerContent.NoHistory(GitDiagnosis.RepositoryOk))

@LightDarkPreviews
@Composable
private fun PickerUnknownPreview() = SheetPreview(PickerContent.NoHistory(GitDiagnosis.Unknown))
