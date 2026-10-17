package dev.cline.termux.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.agents.LastAction
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.agents.SessionAgentRunState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.session.StatusGlyph
import dev.cline.termux.ui.components.toUiText
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.components.tint
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * C9 Agents roster sheet (customize-and-rest.md §6.3, §6.6): a `ModalBottomSheet` over the
 * chat (`skipPartiallyExpanded = false`, half then full on drag, max 640dp). A row tap hides
 * the sheet first and then calls [onOpenAgent] with the child session id (the host pushes
 * `ChildChat(id)` through `ShellNavigator.openChildSession`), so system back from the child
 * returns to the parent chat, not to the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsRosterSheet(
    parentSessionId: String,
    graph: AppGraph,
    onDismiss: () -> Unit,
    onOpenAgent: (childSessionId: String) -> Unit,
    returnFocus: FocusRequester? = null,
) {
    val viewModel: AgentsRosterViewModel = viewModel(
        key = "agents-roster-$parentSessionId",
        factory = AgentsRosterViewModel.factory(graph, parentSessionId),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val summary = describeAgentActivity(state.activity)
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        paneTitle = stringResource(R.string.agents_title),
        returnFocus = returnFocus,
        titleContent = { AgentsHeader(summary, Modifier.weight(1f)) },
    ) {
        AgentsRosterContent(state) { childId ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismiss()
                onOpenAgent(childId)
            }
        }
    }
}

/** "Agents" + the `describeAgentActivity` line, a polite live region announced at most every 5 s (§6.9). */
@Composable
private fun AgentsHeader(summary: String, modifier: Modifier = Modifier) {
    val live = rememberLiveRegionState()
    val lastAnnounced = remember { mutableLongStateOf(0L) }
    LaunchedEffect(summary) {
        val wait = ANNOUNCE_INTERVAL_MS - (System.currentTimeMillis() - lastAnnounced.longValue)
        if (wait > 0) delay(wait)
        lastAnnounced.longValue = System.currentTimeMillis()
        live.announce(summary)
    }
    Column(modifier.padding(vertical = Spacing.s)) {
        Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LiveRegionAnnouncer(live)
    }
}

/** The sheet body (§6.4 states), stateless for previews and tests. */
@Composable
fun AgentsRosterContent(state: AgentsRosterUiState, onOpen: (String) -> Unit) {
    val resources = LocalResources.current
    val errorText = when {
        state.kernelDown -> KernelCopy.banner(state.kernel, hasComposer = false)?.let { banner ->
            joinBannerLines(resources, banner.line1.resolve(resources), banner.line2?.resolve(resources))
        }
        else -> state.error?.toUiText()?.resolve(resources)
    }
    if (state.agents.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.l)) {
            when {
                state.loading && state.error == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.agents_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.runningInTally > 0 -> EmptyLine(stringResource(R.string.agents_waiting))
                else -> EmptyLine(stringResource(R.string.agents_none))
            }
            if (!state.loading && errorText != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = Spacing.s)) {
        itemsIndexed(state.agents, key = { _, row -> row.sessionId }) { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AgentRow(row, onOpen)
        }
        if (state.stale) {
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (errorText != null) stringResource(R.string.agents_stale_with_error, errorText) else stringResource(R.string.agents_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AgentRow(row: AgentRowUi, onOpen: (String) -> Unit) {
    val task = row.task ?: stringResource(R.string.agents_untitled)
    val stateWord = stringResource(row.state.word())
    val action = when (val last = row.lastAction) {
        is LastAction.Tool -> stringResource(R.string.agents_running_tool, ltrIsolate(last.name))
        is LastAction.Text -> last.text
        null -> if (row.state == SessionAgentRunState.Running) {
            stringResource(R.string.agents_starting_up)
        } else {
            stringResource(R.string.agents_no_activity, stateWord)
        }
    }
    val team = if (row.kind == SessionAgentEntry.Kind.Teamtask) {
        row.teamName?.let { stringResource(R.string.agents_team_named, it) } ?: stringResource(R.string.agents_team)
    } else {
        null
    }
    val spoken = if (team != null) {
        stringResource(R.string.agents_row_team_a11y, stateWord, task, action, team)
    } else {
        stringResource(R.string.agents_row_a11y, stateWord, task, action)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(onClickLabel = stringResource(R.string.agents_open_session), role = Role.Button) { onOpen(row.sessionId) }
            .semantics { contentDescription = spoken }
            .padding(start = Spacing.sheetGutter, end = Spacing.l, top = Spacing.m, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { StateIcon(row.state) }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            team?.let {
                Text(
                    it.uppercase(LocalConfiguration.current.locales[0]),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(task, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                action,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (row.lastAction == null) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        Icon(ClineIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** §6.4 state icons: running spinner, completed `check` (success), failed `error`, else `schedule`. */
@Composable
private fun StateIcon(state: SessionAgentRunState) {
    when (state) {
        SessionAgentRunState.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        SessionAgentRunState.Completed -> Icon(ClineIcons.States.CompletedRoster, contentDescription = null, tint = StatusGlyph.Completed.tint(), modifier = Modifier.size(20.dp))
        SessionAgentRunState.Failed -> Icon(ClineIcons.Error, contentDescription = null, tint = StatusGlyph.Failed.tint(), modifier = Modifier.size(20.dp))
        SessionAgentRunState.Cancelled, SessionAgentRunState.Unresolved ->
            Icon(ClineIcons.States.CancelledRoster, contentDescription = null, tint = StatusGlyph.Cancelled.tint(), modifier = Modifier.size(20.dp))
    }
}

private const val ANNOUNCE_INTERVAL_MS = 5_000L

/**
 * Kernel-supplied tool names are data (DS §8.2.8) and read left to right under any locale
 * (DS §8.2.6): inside a translated sentence they sit in a Unicode LTR isolate (LRI … PDI).
 */
internal fun ltrIsolate(value: String): String = "\u2066$value\u2069"

// ---- previews --------------------------------------------------------------------------------

private val previewRows = listOf(
    AgentRowUi("p__a1", SessionAgentEntry.Kind.Subagent, SessionAgentRunState.Running, "Audit the auth module for injection bugs and report file:line findings", LastAction.Tool("read_files"), null, true),
    AgentRowUi("p__teamtask__1", SessionAgentEntry.Kind.Teamtask, SessionAgentRunState.Completed, "Review the migration for data loss", LastAction.Text("The migration is safe; one index is missing on sessions.updated_at."), "reviewers", true),
    AgentRowUi("p__a3", SessionAgentEntry.Kind.Subagent, SessionAgentRunState.Unresolved, null, null, null, false),
    AgentRowUi("p__a4", SessionAgentEntry.Kind.Subagent, SessionAgentRunState.Failed, "Run the test suite", null, null, true),
)

@Composable
private fun RosterPreview(state: AgentsRosterUiState) {
    ComponentPreview(padding = false) {
        Column {
            Box(Modifier.padding(horizontal = Spacing.sheetGutter)) { AgentsHeader(describeAgentActivity(state.activity)) }
            AgentsRosterContent(state) {}
        }
    }
}

@LightDarkPreviews
@Composable
private fun AgentsRosterPopulatedPreview() {
    RosterPreview(AgentsRosterUiState(activity = SessionAgentActivity(total = 4, running = 1, completed = 1, failed = 1, unresolved = 1), loading = false, agents = previewRows, runningInTally = 1))
}

@LightDarkPreviews
@Composable
private fun AgentsRosterLoadingPreview() {
    RosterPreview(AgentsRosterUiState(activity = SessionAgentActivity(total = 1, running = 1), loading = true))
}

@LightDarkPreviews
@Composable
private fun AgentsRosterWaitingPreview() {
    RosterPreview(AgentsRosterUiState(activity = SessionAgentActivity(total = 2, running = 2), loading = false, runningInTally = 2))
}

@LightDarkPreviews
@Composable
private fun AgentsRosterNoneRecordedPreview() {
    RosterPreview(AgentsRosterUiState(activity = SessionAgentActivity(total = 1, unresolved = 1), loading = false))
}

@LightDarkPreviews
@Composable
private fun AgentsRosterRefreshFailedPreview() {
    RosterPreview(
        AgentsRosterUiState(
            activity = SessionAgentActivity(total = 2, running = 1, completed = 1),
            loading = false,
            error = RepoFailure.HubTimeout,
            agents = previewRows.take(2),
            runningInTally = 1,
        ),
    )
}

@LightDarkPreviews
@Composable
private fun AgentsRosterKernelDownPreview() {
    RosterPreview(
        AgentsRosterUiState(
            activity = SessionAgentActivity(total = 2, running = 1, completed = 1),
            loading = false,
            agents = previewRows.take(2),
            runningInTally = 1,
            kernel = KernelGate.Down(KernelGate.DownVariant.Asleep, null),
        ),
    )
}
