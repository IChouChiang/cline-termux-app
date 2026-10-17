package dev.cline.termux.ui.sessions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.cline.termux.R
import dev.cline.termux.data.sessions.RowUsage
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.SessionsSnapshot
import dev.cline.termux.data.sessions.formatCostUsd
import dev.cline.termux.data.sessions.formatTokenCount
import dev.cline.termux.data.sessions.getSessionSourceLabel
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KeyValueRow
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing

/** The F6 rows, in order (desktop `getSessionOverviewItems`, then the Android-only Session ID, HC §6.5). */
enum class DetailField { Schedule, Run, Workspace, Branch, Provider, Model, Tokens, Cost, Source, SessionId }

/** One row: the displayed value, and what a long-press copies (the full path for Workspace). */
data class DetailItem(val field: DetailField, val value: String, val copyValue: String = value)

object SessionDetails {
    /**
     * `getSessionOverviewItems` (sessions.md F6): rows with an empty value are omitted (a run
     * number of 0 is falsy there, so it gets no Run row); Tokens via `formatTokenCount`, Cost via `formatCostUsd`, Source via `getSessionSourceLabel`;
     * the session id closes the list *(Android, HC §6.5)*.
     * [workspace] is the rendered workspace label (§1.4); [usage] wins over the record's.
     */
    fun items(summary: SessionSummary, workspace: String, usage: RowUsage? = summary.usage): List<DetailItem> = buildList {
        summary.schedule?.scheduleName?.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.Schedule, it)) }
        summary.schedule?.runNumber?.takeIf { it != 0 }?.let { add(DetailItem(DetailField.Run, it.toString())) }
        if (summary.workspaceRoot.isNotBlank()) add(DetailItem(DetailField.Workspace, workspace, summary.workspaceRoot))
        summary.gitBranch?.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.Branch, it)) }
        summary.provider.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.Provider, it)) }
        summary.model.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.Model, it)) }
        formatTokenCount(usage?.inputTokens, usage?.outputTokens)?.let { add(DetailItem(DetailField.Tokens, it)) }
        formatCostUsd(usage?.totalCostUsd)?.let { add(DetailItem(DetailField.Cost, it)) }
        summary.source.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.Source, getSessionSourceLabel(it))) }
        summary.id.takeIf { it.isNotBlank() }?.let { add(DetailItem(DetailField.SessionId, it)) }
    }

    /** F6 "Loading": only a live session without usage is read (§1.5 step 2). */
    fun needsLiveUsage(summary: SessionSummary): Boolean = summary.usage == null && summary.isLive
}

@Composable
private fun DetailField.label(): String = stringResource(
    when (this) {
        DetailField.Schedule -> R.string.session_details_schedule
        DetailField.Run -> R.string.session_details_run
        DetailField.Workspace -> R.string.session_details_workspace
        DetailField.Branch -> R.string.session_details_branch
        DetailField.Provider -> R.string.session_details_provider
        DetailField.Model -> R.string.session_details_model
        DetailField.Tokens -> R.string.session_details_tokens
        DetailField.Cost -> R.string.session_details_cost
        DetailField.Source -> R.string.session_details_source
        DetailField.SessionId -> R.string.session_details_session_id
    },
)

/**
 * F6 Session details (DS §4.1 #13): title = the session title's first line, then the
 * two-column label / mono value grid; long-press copies a value (the full workspace path for
 * Workspace). [loading] shows a `LinearProgressIndicator` under the title while a live
 * session's usage is read.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailsSheet(summary: SessionSummary, usage: RowUsage?, loading: Boolean, onDismiss: () -> Unit, returnFocus: FocusRequester? = null) {
    val workspace = workspaceText(summary.workspaceRoot)
    val items = SessionDetails.items(summary, workspace, usage)
    val loadingLabel = stringResource(R.string.session_details_loading)
    // Nine rows outgrow half the window: open full, and one predictive back closes it (DS §5.8).
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = summary.displayTitle(),
        returnFocus = returnFocus,
    ) {
        if (loading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs)
                    .semantics { contentDescription = loadingLabel },
            )
        }
        val copyPath = stringResource(R.string.details_copy_path)
        items.forEach { item ->
            val label = item.field.label()
            // HC §6.5: the Workspace row copies the full path, and TalkBack says so.
            KeyValueRow(
                label = label,
                value = item.value,
                copyValue = item.copyValue,
                copyActionLabel = if (item.copyValue != item.value) copyPath else stringResource(R.string.components_copy_value, label),
            )
        }
    }
}

/**
 * Hosts F6 for [sessionId] from the sessions snapshot: reads live usage once when needed and
 * closes when the row disappears (deleted elsewhere). Kernel down: the cached values only.
 */
@Composable
fun SessionDetailsSheetHost(sessionId: String, port: SessionsPort, onDismiss: () -> Unit, returnFocus: FocusRequester? = null) {
    val snapshot by port.snapshot.collectAsState(initial = SessionsSnapshot())
    val row = snapshot.row(sessionId)
    var liveUsage by remember(sessionId) { mutableStateOf<RowUsage?>(null) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    val summary = row?.summary
    val wantsUsage = summary != null && snapshot.connected && SessionDetails.needsLiveUsage(summary)
    LaunchedEffect(sessionId, wantsUsage) {
        if (!wantsUsage) return@LaunchedEffect
        loading = true
        liveUsage = runCatching { port.liveUsage(sessionId) }.getOrNull()
        loading = false
    }
    if (summary == null) {
        LaunchedEffect(sessionId, snapshot.rows.isEmpty()) { if (snapshot.rows.isNotEmpty()) onDismiss() }
        return
    }
    SessionDetailsSheet(summary, liveUsage ?: summary.usage, loading, onDismiss, returnFocus)
}

@LightDarkPreviews
@Composable
private fun SessionDetailsSheetPreview() {
    ComponentPreview {
        val summary = SessionsPreviewData.rows[0].summary
        SessionDetailsSheet(summary, summary.usage, loading = false, onDismiss = {})
    }
}

@LightDarkPreviews
@Composable
private fun SessionDetailsSheetLoadingPreview() {
    ComponentPreview {
        val summary = SessionsPreviewData.rows[3].summary
        SessionDetailsSheet(summary, null, loading = true, onDismiss = {})
    }
}
