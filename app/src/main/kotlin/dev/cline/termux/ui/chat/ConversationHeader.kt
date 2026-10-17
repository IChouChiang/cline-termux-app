package dev.cline.termux.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.ui.agents.AgentsChip
import dev.cline.termux.ui.agents.AgentsChipModel
import dev.cline.termux.ui.agents.describeAgentActivity
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.MenuNavIcon
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.inlineEditKeys
import dev.cline.termux.ui.components.rememberInlineEditExit
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.SessionStatusDot
import dev.cline.termux.ui.components.label
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** What the header's navigation icon does (home-chat.md §6.2). */
enum class HeaderNavigation {
    /** Compact: opens the sessions drawer. */
    Drawer,

    /** A pushed subagent entry: pops it. */
    Back,

    /** The rail and the list pane carry navigation. */
    None,
}

/**
 * The header's callbacks (§6.6); one object so the chat screen passes them once. The chat
 * screen remembers one instance, so the header skips recomposition while the transcript streams.
 */
@Immutable
class HeaderActions(
    val onNavigation: () -> Unit = {},
    val onToggleListPane: () -> Unit = {},
    val onAgents: () -> Unit = {},
    val onDiff: () -> Unit = {},
    val onParent: (String) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onRename: (title: String) -> Unit = {},
    val onTogglePin: () -> Unit = {},
    val onFork: () -> Unit = {},
    val onDetails: () -> Unit = {},
    val onRestoreCheckpoint: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * The header's sheet triggers (DS §8.1.7): the title (details), the agents chip (roster) and
 * the overflow button (every sheet its menu opens). [opener] is the one that opened the last
 * sheet; the chat screen hands it to that sheet as its `returnFocus`.
 */
@Stable
class HeaderFocus {
    val title = FocusRequester()
    val agents = FocusRequester()
    val overflow = FocusRequester()

    var opener: FocusRequester = overflow
        private set

    /** Records [requester] as the opener and runs [action]. */
    fun open(requester: FocusRequester, action: () -> Unit) {
        opener = requester
        action()
    }
}

/**
 * The conversation header (home-chat.md §6): navigation, the status dot and title (tap opens
 * the details sheet; inline rename replaces the title while [renaming]), the agents and diff
 * chips (moved into the overflow when the title would drop below its minimum width, §6.4),
 * the subagent back chip, "New session" and the overflow menu (§6.6). [listPaneExpanded] is
 * null when no list pane is shown. [scrolled] draws the hairline under the bar (§6.1).
 * [approvalControl] is the tool approvals button (decided A1, placement A′ confirmed in A15):
 * it follows the navigation icons, before the status dot and the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHeader(
    state: HeaderState,
    navigation: HeaderNavigation,
    actions: HeaderActions,
    renaming: Boolean,
    onRenamingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listPaneExpanded: Boolean? = null,
    scrolled: Boolean = false,
    focus: HeaderFocus = remember { HeaderFocus() },
    approvalControl: (@Composable () -> Unit)? = null,
) {
    var placement by remember { mutableStateOf(HeaderWidthRule.Placement(agentsInBar = true, diffInBar = true)) }
    Column(modifier) {
        TopAppBar(
            navigationIcon = {
                Row {
                    when (navigation) {
                        HeaderNavigation.Drawer -> MenuNavIcon(actions.onNavigation)
                        HeaderNavigation.Back -> BackNavIcon(actions.onNavigation)
                        HeaderNavigation.None -> Unit
                    }
                    if (listPaneExpanded != null) ListPaneToggle(listPaneExpanded, actions.onToggleListPane)
                    approvalControl?.invoke()
                }
            },
            title = {
                if (renaming) {
                    RenameField(state.title.orEmpty(), onCommit = { actions.onRename(it); onRenamingChange(false) }, onCancel = { onRenamingChange(false) })
                } else {
                    TitleBar(state, actions, focus, onRename = { onRenamingChange(true) }, onPlacement = { if (it != placement) placement = it })
                }
            },
            actions = {
                if (!renaming) {
                    // §6.4 / §6.8: the subagent back chip replaces New session (it lives in the title
                    // slot so the width rule measures it); a missing session keeps only New session.
                    if (!state.parentChipShown) NewSessionButton(actions.onNewSession)
                    if (!state.missing) HeaderOverflow(state, placement, actions, focus, onRename = { onRenamingChange(true) })
                }
            },
        )
        // DS §3.2 level 2: the hairline fades in over 150 ms; it is always laid out, so the
        // header's height never changes while content passes under it.
        val hairline by animateFloatAsState(
            targetValue = if (scrolled) 1f else 0f,
            animationSpec = tween(if (ClineTheme.reducedMotion) 0 else ClineMotion.Short3),
            label = "headerHairline",
        )
        HorizontalDivider(Modifier.graphicsLayer { alpha = hairline }, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The subagent back chip shows instead of New session (§6.4); never on a missing session (§6.8). */
private val HeaderState.parentChipShown: Boolean get() = parent != null && !missing

/**
 * The title with its chips and, on a subagent session, the back chip at the slot's end; the
 * width rule (§6.3) decides which chips stay in the bar and whether the back chip keeps its label.
 */
@Composable
private fun TitleBar(state: HeaderState, actions: HeaderActions, focus: HeaderFocus, onRename: () -> Unit, onPlacement: (HeaderWidthRule.Placement) -> Unit) {
    val agentsVisible = state.agents.visible && !state.missing
    val diff = state.diff?.takeIf { state.diffVisible && !state.missing }
    val parent = state.parent?.takeIf { state.parentChipShown }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val gap = Spacing.s.roundToPx()
        val agentsPlaceables = if (agentsVisible) subcompose("agents") {
                AgentsChip(
                    state.agents,
                    onClick = { focus.open(focus.agents, actions.onAgents) },
                    modifier = Modifier.focusRequester(focus.agents).focusRing(MaterialTheme.shapes.small),
                )
            }.map { it.measure(loose) } else emptyList()
        val diffPlaceables = diff?.let { d -> subcompose("diff") { DiffChip(d, actions.onDiff) }.map { it.measure(loose) } }.orEmpty()
        // Both forms of the back chip are measured; only the one the rule picks is placed.
        val parentFull = parent?.let { p -> subcompose("parentFull") { ParentChip(p, actions.onParent) }.map { it.measure(loose) } }.orEmpty()
        val parentCompact = parent?.let { p -> subcompose("parentCompact") { ParentIconButton(p, actions.onParent) }.map { it.measure(loose) } }.orEmpty()
        val agentsPx = agentsPlaceables.maxOfOrNull { it.width }
        val diffPx = diffPlaceables.maxOfOrNull { it.width }
        val parentPx = parentFull.maxOfOrNull { it.width }
        val parentCompactPx = parentCompact.maxOfOrNull { it.width }
        val result = HeaderWidthRule.place(
            availablePx = constraints.maxWidth,
            titleMinPx = TitleMinWidth.roundToPx(),
            agentsPx = agentsPx,
            diffPx = diffPx,
            gapPx = gap,
            parentPx = parentPx,
            parentCompactPx = parentCompactPx,
        )
        onPlacement(result)
        val shownAgents = if (result.agentsInBar) agentsPlaceables else emptyList()
        val shownDiff = if (result.diffInBar) diffPlaceables else emptyList()
        val shownParent = if (result.parentLabel) parentFull else parentCompact
        val reserved = HeaderWidthRule.reservedPx(result, agentsPx, diffPx, gap, parentPx, parentCompactPx)
        val titleWidth = (constraints.maxWidth - reserved).coerceAtLeast(0)
        val title = subcompose("title") { TitleRow(state, actions, focus, onRename) }
            .map { it.measure(Constraints(maxWidth = titleWidth, maxHeight = constraints.maxHeight)) }
        val height = (title + shownAgents + shownDiff + shownParent).maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            fun place(list: List<androidx.compose.ui.layout.Placeable>) {
                list.forEach { p ->
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
            place(title)
            place(shownAgents)
            place(shownDiff)
            // The back chip sits at the end of the slot, where New session would be.
            shownParent.forEach { p -> p.placeRelative(constraints.maxWidth - p.width, (height - p.height) / 2) }
        }
    }
}

/**
 * §6.3 / §6.10: dot + title, one line, ellipsized; a heading that is one 48 dp target. Tap
 * opens the details sheet, long-press enters rename mode, and TalkBack gets the custom actions
 * "Rename", "Session details", "Pin"/"Unpin", "Fork" and "Delete session", each enabled by the
 * same rule as its overflow item. A missing session shows "New Session" with no actions (§6.8).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleRow(state: HeaderState, actions: HeaderActions, focus: HeaderFocus, onRename: () -> Unit) {
    val openDetails = { focus.open(focus.title, actions.onDetails) }
    val fallback = stringResource(R.string.header_title_fallback)
    val title = if (state.missing) fallback else state.title?.takeIf { it.isNotBlank() } ?: fallback
    val statusLabel = state.visual.label()
    val cd = stringResource(R.string.header_status_cd, title, statusLabel)
    val detailsLabel = stringResource(R.string.header_details)
    val renameLabel = stringResource(R.string.header_rename)
    val pinLabel = stringResource(if (state.pinned) R.string.header_unpin else R.string.header_pin)
    val forkLabel = stringResource(R.string.header_fork)
    val deleteLabel = stringResource(R.string.header_delete)
    val detailsEnabled = state.sessionId != null && !state.missing
    val renameEnabled = state.commandsEnabled
    Row(
        Modifier
            .heightIn(min = TitleMinHeight)
            .focusRequester(focus.title)
            .focusRing(MaterialTheme.shapes.small)
            .combinedClickable(
                enabled = detailsEnabled,
                onClickLabel = detailsLabel,
                role = Role.Button,
                onLongClickLabel = renameLabel,
                onLongClick = if (renameEnabled) onRename else null,
                onClick = openDetails,
            )
            .padding(vertical = Spacing.xs)
            .clearAndSetSemantics {
                contentDescription = cd
                heading()
                if (detailsEnabled) {
                    role = Role.Button
                    onClick(label = detailsLabel) { openDetails(); true }
                }
                if (renameEnabled) onLongClick(label = renameLabel) { onRename(); true }
                customActions = buildList {
                    if (renameEnabled) add(CustomAccessibilityAction(renameLabel) { onRename(); true })
                    if (detailsEnabled) add(CustomAccessibilityAction(detailsLabel) { openDetails(); true })
                    if (state.commandsEnabled) add(CustomAccessibilityAction(pinLabel) { actions.onTogglePin(); true })
                    if (state.forkEnabled) add(CustomAccessibilityAction(forkLabel) { actions.onFork(); true })
                    if (state.commandsEnabled) add(CustomAccessibilityAction(deleteLabel) { actions.onDelete(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionStatusDot(state.visual, standalone = false)
        Spacer(Modifier.width(Spacing.xs))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

/** §6.4 diff chip: `+N` in success and `-N` in error, `ClineMono.small`; opens Changes. */
@Composable
private fun DiffChip(diff: SessionDiffSummary, onClick: () -> Unit) {
    val cd = stringResource(
        R.string.header_diff_cd,
        copyPluralStringResource(R.plurals.header_diff_additions_cd, diff.additions, diff.additions),
        copyPluralStringResource(R.plurals.header_diff_deletions_cd, diff.deletions, diff.deletions),
    )
    AssistChip(
        onClick = onClick,
        label = {
            // DS §8.2.6: diff counts stay "+N -N" (LTR) while the header around the chip mirrors.
            LtrBlock {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.header_diff_added, diff.additions), style = ClineMono.small, color = ClineTheme.colors.success.color)
                    Text(stringResource(R.string.header_diff_removed, diff.deletions), style = ClineMono.small, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        modifier = Modifier
            .focusRing(MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    )
}

/** "Back to the main agent session: <title>" (or without the title): the back chip's CD and tooltip. */
@Composable
private fun parentHint(parent: ParentLink): String {
    // An untitled parent reads "Session <last6>" from resources, as the drawer shows it (DS §8.2.1).
    val title = parent.title?.takeIf { it.isNotBlank() }
        ?: if (parent.untitled) stringResource(R.string.sessions_title_fallback, parent.sessionId.takeLast(6)) else null
    return title?.let { stringResource(R.string.header_main_agent_hint_title, it) } ?: stringResource(R.string.header_main_agent_hint)
}

/**
 * §6.4 subagent back chip (replaces New session): `u_turn_left` and `smart_toy` 16 dp, 4 dp
 * apart (desktop `CornerUpLeft` + `Bot`), label capped at 160 dp; back to the main agent session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentChip(parent: ParentLink, onParent: (String) -> Unit) {
    val hint = parentHint(parent)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(hint) } },
        state = rememberTooltipState(),
    ) {
        AssistChip(
            onClick = { onParent(parent.sessionId) },
            label = {
                Text(
                    stringResource(R.string.header_main_agent),
                    Modifier.widthIn(max = ParentLabelMaxWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { ParentIcons() },
            modifier = Modifier
                .focusRing(MaterialTheme.shapes.small)
                .semantics { contentDescription = hint },
        )
    }
}

/**
 * The back chip when the title would keep less than 96 dp with its label (§6.3): the same two
 * icons in a 48 dp icon button, the same CD and tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentIconButton(parent: ParentLink, onParent: (String) -> Unit) {
    val hint = parentHint(parent)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(hint) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { onParent(parent.sessionId) },
            modifier = Modifier
                .focusRing(CircleShape)
                .semantics { contentDescription = hint },
        ) {
            ParentIcons()
        }
    }
}

/** `u_turn_left` then `smart_toy`, 16 dp, 4 dp apart; decorative (the control carries the CD). */
@Composable
private fun ParentIcons() {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(ClineIcons.UTurnLeft, contentDescription = null, Modifier.size(ChipIconSize))
        Icon(ClineIcons.SmartToy, contentDescription = null, Modifier.size(ChipIconSize))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(stringResource(R.string.header_new_tooltip)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.focusRing(CircleShape)) {
            Icon(ClineIcons.Chrome.NewSession, contentDescription = stringResource(R.string.header_new_cd))
        }
    }
}

/**
 * The expanded-window list-pane toggle ("Collapse sidebar" / "Expand sidebar"), shared by the
 * conversation header and home's bar, with its long-press tooltip (DS §4.1 #14).
 */
@Composable
internal fun ListPaneToggle(expanded: Boolean, onToggle: () -> Unit) {
    TooltipIconButton(
        icon = if (expanded) ClineIcons.Chrome.ListPaneClose else ClineIcons.Chrome.ListPaneOpen,
        contentDescription = stringResource(if (expanded) R.string.header_sidebar_collapse else R.string.header_sidebar_expand),
        onClick = onToggle,
    )
}

/** §6.6 overflow: pending labels while an action runs; chips the width rule dropped come first. */
@Composable
private fun HeaderOverflow(state: HeaderState, placement: HeaderWidthRule.Placement, actions: HeaderActions, focus: HeaderFocus, onRename: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ReportBackLevel(BackLevel.Menu, open)
    Box {
        val label = stringResource(R.string.header_actions_cd)
        PlainTooltipBox(label) {
            IconButton(
                onClick = { open = true },
                enabled = state.sessionId != null,
                modifier = Modifier.focusRequester(focus.overflow).focusRing(CircleShape),
            ) {
                Icon(ClineIcons.Chrome.SessionActions, contentDescription = label)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = MenuMinWidth)) {
            fun run(action: () -> Unit) {
                open = false
                focus.open(focus.overflow, action)
            }
            if (!placement.agentsInBar && state.agents.visible) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.header_agents_overflow, describeAgentActivity(state.agents.activity))) },
                    leadingIcon = { Icon(ClineIcons.SmartToy, contentDescription = null) },
                    onClick = { run(actions.onAgents) },
                )
            }
            val diff = state.diff
            if (!placement.diffInBar && diff != null && state.diffVisible) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.header_changes_overflow, diff.additions, diff.deletions)) },
                    leadingIcon = { Icon(ClineIcons.Difference, contentDescription = null) },
                    onClick = { run(actions.onDiff) },
                )
            }
            val pending = state.pending
            OverflowItem(
                label = stringResource(if (pending == HeaderAction.Rename) R.string.header_renaming else R.string.header_rename),
                icon = { Icon(ClineIcons.Edit, contentDescription = null) },
                enabled = state.commandsEnabled,
                pending = pending == HeaderAction.Rename,
                onClick = { run(onRename) },
            )
            OverflowItem(
                label = stringResource(if (state.pinned) R.string.header_unpin else R.string.header_pin),
                icon = { Icon(if (state.pinned) ClineIcons.PushPinFilled else ClineIcons.PushPin, contentDescription = null) },
                enabled = state.commandsEnabled,
                pending = pending == HeaderAction.Pin,
                onClick = { run(actions.onTogglePin) },
            )
            OverflowItem(
                label = stringResource(if (pending == HeaderAction.Fork) R.string.header_forking else R.string.header_fork),
                icon = { Icon(ClineIcons.ForkRight, contentDescription = null) },
                enabled = state.forkEnabled,
                pending = pending == HeaderAction.Fork,
                onClick = { run(actions.onFork) },
            )
            OverflowItem(
                label = stringResource(R.string.checkpoint_header_menu),
                icon = { Icon(ClineIcons.History, contentDescription = null) },
                enabled = state.commandsEnabled && !state.busy,
                pending = false,
                onClick = { run(actions.onRestoreCheckpoint) },
            )
            OverflowItem(
                label = stringResource(R.string.header_details_item),
                icon = { Icon(ClineIcons.Info, contentDescription = null) },
                enabled = state.sessionId != null,
                pending = false,
                onClick = { run(actions.onDetails) },
            )
            OverflowItem(
                label = stringResource(R.string.header_export),
                icon = { Icon(ClineIcons.Share, contentDescription = null) },
                enabled = state.commandsEnabled,
                pending = pending == HeaderAction.Export,
                onClick = { run(actions.onExport) },
            )
            HorizontalDivider()
            OverflowItem(
                label = stringResource(if (pending == HeaderAction.Delete) R.string.header_deleting else R.string.header_delete),
                icon = { Icon(ClineIcons.Delete, contentDescription = null) },
                enabled = state.commandsEnabled,
                pending = pending == HeaderAction.Delete,
                destructive = true,
                onClick = { run(actions.onDelete) },
            )
            if (!state.kernelReady && state.sessionId != null) {
                Text(
                    stringResource(R.string.header_kernel_disabled),
                    Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OverflowItem(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    // DS §4.1 #15: destructive items take the error colours through the item colours, so a
    // disabled item (kernel down, another action pending) falls back to the disabled colours.
    val colors = if (destructive) {
        MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error)
    } else {
        MenuDefaults.itemColors()
    }
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = if (pending) {
            { CircularProgressIndicator(Modifier.size(PendingSpinnerSize), strokeWidth = 2.dp) }
        } else {
            icon
        },
        enabled = enabled,
        onClick = onClick,
        colors = colors,
    )
}

/**
 * §6.7 inline rename: the field takes focus with the text selected; Enter, "Save title" or
 * focus loss (desktop `onBlur`) commits, Esc / back / "Cancel rename" restores the title
 * (back level 4; a back *key* is taken on the field itself, before it can move focus out).
 * One exit per field: whichever comes first wins.
 */
@Composable
private fun RenameField(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    // Shared with the drawer rename: Enter / Esc / back-key handling and the single exit.
    val exit = rememberInlineEditExit(key = Unit, onCommit = { onCommit(text.text) }, onCancel = onCancel)
    OrderedBackHandler(BackLevel.InlineEdit, enabled = true, onBack = exit::cancel)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                val next = it.text.replace("\n", " ")
                if (next != text.text) exit.rearm()
                text = it.copy(text = next)
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .inlineEditKeys(exit, commitOnBlur = true),
            label = { Text(stringResource(R.string.rename_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { exit.commit() }),
        )
        TooltipIconButton(ClineIcons.Check, contentDescription = stringResource(R.string.rename_save), onClick = exit::commit)
        TooltipIconButton(ClineIcons.Close, contentDescription = stringResource(R.string.rename_cancel), onClick = exit::cancel)
    }
}

private val TitleMinWidth = 96.dp
private val TitleMinHeight = 48.dp
private val ParentLabelMaxWidth = 160.dp
private val ChipIconSize = 16.dp

/** DS §4.1 #15: menus are at least 200 dp wide; a pending item's spinner is 16 dp. */
private val MenuMinWidth = 200.dp
private val PendingSpinnerSize = 16.dp

private fun previewHeader(
    title: String?,
    agents: AgentsChipModel = AgentsChipModel.Hidden,
    parent: ParentLink? = null,
    sessionId: String? = "s1",
    status: ChatStatus = ChatStatus.Running,
    creating: Boolean = false,
    kernelReady: Boolean = true,
    missing: Boolean = false,
) = HeaderState(
    sessionId = sessionId,
    title = title,
    visual = HeaderState.visualOf(status, null, creating = creating, pending = null),
    agents = agents,
    diff = SessionDiffSummary(additions = 12, deletions = 3),
    parent = parent,
    pinned = false,
    busy = status == ChatStatus.Running || status == ChatStatus.Starting,
    pending = null,
    kernelReady = kernelReady,
    missing = missing,
)

@LightDarkPreviews
@Composable
private fun ConversationHeaderStartingPreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(
            previewHeader("Fix the login crash", sessionId = null, status = ChatStatus.Starting, creating = true),
            HeaderNavigation.Drawer,
            HeaderActions(),
            renaming = false,
            onRenamingChange = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ConversationHeaderKernelDownPreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(
            previewHeader("Refactor the hub client", status = ChatStatus.Idle, kernelReady = false),
            HeaderNavigation.Drawer,
            HeaderActions(),
            renaming = false,
            onRenamingChange = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ConversationHeaderMissingPreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(
            previewHeader("Refactor the hub client", status = ChatStatus.Idle, missing = true),
            HeaderNavigation.Drawer,
            HeaderActions(),
            renaming = false,
            onRenamingChange = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ConversationHeaderNarrowPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.width(NarrowPreviewWidth)) {
            ConversationHeader(
                previewHeader(
                    "Investigate the flaky acceptance gate",
                    agents = AgentsChipModel(SessionAgentActivity(total = 3, running = 1, completed = 2)),
                ),
                HeaderNavigation.Drawer,
                HeaderActions(),
                renaming = false,
                onRenamingChange = {},
            )
        }
    }
}

private val NarrowPreviewWidth = 320.dp

@LightDarkPreviews
@Composable
private fun ConversationHeaderPreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(previewHeader("Refactor the hub client"), HeaderNavigation.Drawer, HeaderActions(), renaming = false, onRenamingChange = {}, scrolled = true)
    }
}

/** RTL: the bar mirrors; the diff chip keeps the desktop "+N -N" order (DS §8.2.6). */
@LightDarkPreviews
@Composable
private fun ConversationHeaderRtlPreview() {
    ComponentPreview(padding = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ConversationHeader(previewHeader("Refactor the hub client"), HeaderNavigation.Drawer, HeaderActions(), renaming = false, onRenamingChange = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun ConversationHeaderChildPreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(
            previewHeader("Search the codebase", parent = ParentLink("p1", "Refactor the hub client")),
            HeaderNavigation.Back,
            HeaderActions(),
            renaming = false,
            onRenamingChange = {},
        )
    }
}

/** DP-1 / §6.3 at S25U width: a long child title with agents and diff chips keeps ≥ 96 dp; the back chip goes icon-only. */
@LightDarkPreviews
@Composable
private fun ConversationHeaderChildCompactPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.width(CompactPreviewWidth)) {
            ConversationHeader(
                previewHeader(
                    "List the files under the release tooling and summarise each one",
                    agents = AgentsChipModel(SessionAgentActivity(total = 2, running = 1, completed = 1)),
                    parent = ParentLink("p1", "Refactor the hub client"),
                ),
                HeaderNavigation.Back,
                HeaderActions(),
                renaming = false,
                onRenamingChange = {},
            )
        }
    }
}

private val CompactPreviewWidth = 412.dp

@LightDarkPreviews
@Composable
private fun ConversationHeaderRenamePreview() {
    ComponentPreview(padding = false) {
        ConversationHeader(previewHeader("Refactor the hub client"), HeaderNavigation.None, HeaderActions(), renaming = true, onRenamingChange = {}, listPaneExpanded = true)
    }
}
