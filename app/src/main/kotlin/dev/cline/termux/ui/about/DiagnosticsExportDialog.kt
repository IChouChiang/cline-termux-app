package dev.cline.termux.ui.about

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder.DiagnosticSession
import dev.cline.termux.data.session.StatusGlyph
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.toUiText
import dev.cline.termux.ui.components.AdaptiveDialog
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.tint
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.navigation.EditorDialogWindow
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The `about/diagnostics` editor destination (customize-and-rest.md §9.2): full screen on
 * compact/medium, the dialog surface inside the `dialog()` destination on expanded. "Saved to
 * Files" is posted to the app-wide snackbar host after this screen closes, from the app scope,
 * so it is never hidden behind a dialog window.
 */
@Composable
fun ExportDiagnosticsRoute(graph: AppGraph, asDialog: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ExportDiagnosticsViewModel = viewModel(factory = ExportDiagnosticsViewModel.factory(context, graph))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val resources = LocalResources.current
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        viewModel.saveTo(uri?.toString())
    }
    val activity = LocalActivity.current
    val savedToFiles by rememberUpdatedState(state.savedToFiles)
    val savedPosted = remember { mutableStateOf(false) }
    val postSaved = {
        if (savedToFiles && !savedPosted.value) {
            savedPosted.value = true
            val text = resources.getString(R.string.diagnostics_saved)
            graph.appScope.launch(Dispatchers.Main) { snackbar.showReplacing(text) }
        }
    }
    val close = {
        postSaved()
        onClose()
    }
    // Back is consumed while exporting or saving (desktop `disabled={busy}` on Cancel; "Done" is
    // disabled while saving; DS §5.8: enabled only while it must intercept). Otherwise the dialog
    // window closes through `close` (its only Back path, EditorDialogProperties), and the
    // full-screen route leaves Back to the NavHost so the pop stays seekable (DS §5.8 #9).
    val back = diagnosticsBack(state, asDialog)
    BackHandler(enabled = back == DiagnosticsBack.Consume) { }
    BackHandler(enabled = back == DiagnosticsBack.Close) { close() }
    // Posts "Saved to Files" for exits that bypass `close`: the NavHost pop on the full-screen
    // route, and the activity finishing under the editor.
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) postSaved() }
    }
    ExportDiagnosticsScreen(
        state = state,
        asDialog = asDialog,
        onClose = close,
        onToggle = viewModel::toggle,
        onExport = viewModel::export,
        onShare = { file ->
            val intent = ShareFiles.shareIntent(Uri.parse(file.contentUri), "text/plain", file.name)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No share target: the file stays available through "Save to Files".
            }
        },
        onSave = { file -> runCatching { createDocument.launch(file.name) } },
    )
}

/** C12 surface, stateless. */
@Composable
fun ExportDiagnosticsScreen(
    state: ExportDiagnosticsUiState,
    asDialog: Boolean,
    onClose: () -> Unit,
    onToggle: (String) -> Unit,
    onExport: () -> Unit,
    onShare: (DiagnosticsFile) -> Unit,
    onSave: (DiagnosticsFile) -> Unit,
) {
    val title = stringResource(R.string.diagnostics_title)
    val done = state.result != null
    val confirmLabel = stringResource(if (done) R.string.diagnostics_done else R.string.diagnostics_export)
    val onConfirm = if (done) onClose else onExport
    val confirmEnabled = if (done) !state.saving else state.exportEnabled
    if (asDialog) {
        // An outside tap is a Cancel: ignored while exporting or saving (DS §4.1 #16).
        EditorDialogWindow(onTapOutside = { if (diagnosticsCloseAllowed(state)) onClose() }) {
            Column(Modifier.padding(Spacing.xxl)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.l))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { DiagnosticsBody(state, onToggle, onShare, onSave) }
                Spacer(Modifier.height(Spacing.xxl))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!done) {
                        TextButton(onClick = onClose, enabled = !state.exporting) { Text(stringResource(R.string.components_cancel)) }
                        Spacer(Modifier.width(Spacing.s))
                    }
                    ConfirmButton(confirmLabel, onConfirm, confirmEnabled, busy = state.exporting)
                }
            }
        }
    } else {
        AdaptiveDialog(
            expanded = false,
            title = title,
            onDismissRequest = onClose,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            confirmEnabled = confirmEnabled,
            busy = state.exporting,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = Spacing.pageMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                ) { DiagnosticsBody(state, onToggle, onShare, onSave) }
            }
        }
    }
}

/** Back, Cancel and an outside tap close the export dialog only while nothing is in flight. */
internal fun diagnosticsCloseAllowed(state: ExportDiagnosticsUiState): Boolean = !state.exporting && !state.saving

/** Who handles Back on the export editor (DS §5.8: no always-on handler). */
internal enum class DiagnosticsBack {
    /** Swallowed: an export or a save is in flight. */
    Consume,

    /** The dialog window closes through `close`. */
    Close,

    /** Not intercepted: the NavHost pops the full-screen route (seekable). */
    NavHost,
}

internal fun diagnosticsBack(state: ExportDiagnosticsUiState, asDialog: Boolean): DiagnosticsBack = when {
    !diagnosticsCloseAllowed(state) -> DiagnosticsBack.Consume
    asDialog -> DiagnosticsBack.Close
    else -> DiagnosticsBack.NavHost
}

@Composable
private fun ConfirmButton(label: String, onClick: () -> Unit, enabled: Boolean, busy: Boolean) {
    TextButton(onClick = onClick, enabled = enabled && !busy) {
        if (busy) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
        }
        Text(label)
    }
}

@Composable
private fun DiagnosticsBody(
    state: ExportDiagnosticsUiState,
    onToggle: (String) -> Unit,
    onShare: (DiagnosticsFile) -> Unit,
    onSave: (DiagnosticsFile) -> Unit,
) {
    val result = state.result
    if (result != null) {
        DoneState(result, state, onShare, onSave)
        return
    }
    Text(stringResource(R.string.diagnostics_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Spacing.l))
    Text(stringResource(R.string.diagnostics_sessions), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.s))
    when (val sessions = state.sessions) {
        DiagnosticSessions.Loading -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.s))
            Text(stringResource(R.string.diagnostics_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DiagnosticSessions.HubDown -> Notice(stringResource(R.string.diagnostics_hub_down))
        is DiagnosticSessions.Failed -> ErrorLine(sessions.failure.toUiText().resolve())
        is DiagnosticSessions.Ready -> if (sessions.sessions.isEmpty()) {
            Notice(stringResource(R.string.diagnostics_empty))
        } else {
            SessionList(sessions.sessions, state.selected, enabled = !state.exporting, onToggle = onToggle)
        }
    }
    state.error?.let {
        Spacer(Modifier.height(Spacing.s))
        ErrorLine(it.text())
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(ClineIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
private fun SessionList(sessions: List<DiagnosticSession>, selected: Set<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    val now = System.currentTimeMillis()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val checked = session.sessionId in selected
                val time = session.updatedAt?.let {
                    DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                }
                val subtitle = when {
                    session.workspaceName != null && time != null -> stringResource(R.string.diagnostics_session_subtitle, session.workspaceName, time)
                    else -> session.workspaceName ?: time
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .focusRing(RectangleShape, inset = true)
                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle(session.sessionId) }
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(Spacing.s))
                    Column(Modifier.weight(1f)) {
                        Text(session.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneState(file: DiagnosticsFile, state: ExportDiagnosticsUiState, onShare: (DiagnosticsFile) -> Unit, onSave: (DiagnosticsFile) -> Unit) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ClineIcons.CheckCircle, contentDescription = null, tint = StatusGlyph.Completed.tint(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.diagnostics_done_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(Spacing.s))
        SelectionContainer { LtrText(file.name, style = ClineMono.small) }
        Spacer(Modifier.height(Spacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onShare(file) }, enabled = !state.saving) {
                Icon(ClineIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.diagnostics_share))
            }
            OutlinedButton(onClick = { onSave(file) }, enabled = !state.saving) {
                if (state.saving) {
                    InlineSpinner()
                } else {
                    Icon(ClineIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.diagnostics_save))
            }
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.s))
            ErrorLine(it.text())
        }
    }
}

@Composable
private fun DiagnosticsError.text(): String = when (this) {
    is DiagnosticsError.WriteFailed -> stringResource(R.string.diagnostics_write_failed, detail)
    is DiagnosticsError.SaveFailed -> stringResource(R.string.diagnostics_save_failed, detail)
}

// ---- previews --------------------------------------------------------------------------------

private val previewSessions = listOf(
    DiagnosticSession("s1", "Fix the login redirect", "app", System.currentTimeMillis() - 5 * 60_000),
    DiagnosticSession("s2", "Summarize changes daily", "home", System.currentTimeMillis() - 26 * 3_600_000),
)

@Composable
private fun DiagnosticsPreview(state: ExportDiagnosticsUiState, asDialog: Boolean = false) {
    ComponentPreview(padding = false) {
        ExportDiagnosticsScreen(state, asDialog, onClose = {}, onToggle = {}, onExport = {}, onShare = {}, onSave = {})
    }
}

@LightDarkPreviews
@Composable
private fun DiagnosticsPopulatedPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState(sessions = DiagnosticSessions.Ready(previewSessions), selected = setOf("s1")))
}

@LightDarkPreviews
@Composable
private fun DiagnosticsLoadingPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState())
}

@LightDarkPreviews
@Composable
private fun DiagnosticsNoSessionsPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState(sessions = DiagnosticSessions.Ready(emptyList())))
}

@LightDarkPreviews
@Composable
private fun DiagnosticsHubDownPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState(sessions = DiagnosticSessions.HubDown))
}

@LightDarkPreviews
@Composable
private fun DiagnosticsExportingPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState(sessions = DiagnosticSessions.Ready(previewSessions), selected = setOf("s1"), exporting = true))
}

@LightDarkPreviews
@Composable
private fun DiagnosticsErrorPreview() {
    DiagnosticsPreview(
        ExportDiagnosticsUiState(
            sessions = DiagnosticSessions.Failed(RepoFailure.Hub("command_failed", "session store unavailable")),
            error = DiagnosticsError.WriteFailed("No space left on device"),
        ),
    )
}

@LightDarkPreviews
@Composable
private fun DiagnosticsDonePreview() {
    DiagnosticsPreview(
        ExportDiagnosticsUiState(
            sessions = DiagnosticSessions.Ready(previewSessions),
            result = DiagnosticsFile("cline-diagnostics-0.1.0-20261003-171205.txt", "/cache/diagnostics/x.txt", "content://dev.cline.termux.files/diagnostics/x.txt"),
        ),
    )
}

@LightDarkPreviews
@Composable
private fun DiagnosticsExpandedPreview() {
    DiagnosticsPreview(ExportDiagnosticsUiState(sessions = DiagnosticSessions.Ready(previewSessions), selected = setOf("s1")), asDialog = true)
}
