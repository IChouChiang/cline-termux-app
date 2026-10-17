package dev.cline.termux.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.sessions.SessionRow
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.SessionStatusDot
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.inlineEditKeys
import dev.cline.termux.ui.components.rememberInlineEditExit
import dev.cline.termux.ui.components.label
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.toCustomActions
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.theme.Spacing

/** Disabled-content alpha of a row whose action is pending (§2.7). */
private const val PENDING_ALPHA = 0.38f

/** Shift+F10 or the Menu key opens a focused row's menu (sessions.md §2.8 hardware keyboard). */
fun Modifier.menuKey(onMenu: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val menu = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
    if (event.type == KeyEventType.KeyDown && menu) {
        onMenu()
        true
    } else {
        false
    }
}

/** The TalkBack status part of a row: the DS §1.6 label, omitted for Idle and for neutral states without a dot. */
@Composable
fun SessionVisual.rowStatusLabel(): String? = if (dot == null) null else label()

/**
 * S1 session row (DS §4.1 #1, sessions.md §2.4): 48dp pill, label, status dot, pin, age;
 * long-press opens the menu at the finger with a haptic and keeps the row highlighted; TalkBack
 * reads one merged description and offers the menu as custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawerRow(
    item: DrawerRow.Session,
    selected: Boolean,
    now: Long,
    actionsEnabled: Boolean,
    menuItems: List<ContextMenuItem>,
    pendingItemId: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    nested: Boolean = false,
) {
    val summary = item.summary
    val context = LocalContext.current
    val title = summary.displayTitle()
    val runLabel = item.runLabel?.text()
    val label = runLabel ?: title
    val age = ageText(summary.activityAt, now)
    val pending = pendingItemId != null
    val statusLabel = item.visual.rowStatusLabel()
    val pinnedWord = stringResource(R.string.sessions_a11y_pinned)
    // KS §5 drawer: while the kernel is down the cached row stays tappable but says so.
    val unavailable = stringResource(R.string.list_unavailable_state).takeUnless { actionsEnabled }
    val description = context.joinA11y(
        listOfNotNull(
            item.groupLabel.takeIf { runLabel != null },
            label,
            statusLabel,
            pinnedWord.takeIf { summary.pinned },
            context.spokenAge(summary.activityAt, now),
        ),
    )
    var menuOpen by remember { mutableStateOf(false) }
    val press = remember { PressPoint() }
    val haptic = rememberLongPressHaptic()
    val container = when {
        menuOpen -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f)
    }
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val openLabel = stringResource(R.string.sessions_click_open)
    val menuLabel = stringResource(R.string.sessions_long_click_label)
    val customActions = if (pending || !actionsEnabled && menuItems.none { it.enabled }) emptyList() else menuItems.toCustomActions(pendingItemId)

    Row(modifier.padding(start = if (nested) Spacing.l else 0.dp)) {
        if (nested) NestedRail()
        Box(Modifier.weight(1f).padding(start = if (nested) Spacing.l - 1.dp else 0.dp)) {
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .trackPress(press)
                .menuKey { menuOpen = true }
                .focusRing(CircleShape)
                .combinedClickable(
                    enabled = !pending,
                    onClickLabel = openLabel,
                    onLongClickLabel = menuLabel,
                    onClick = onOpen,
                    onLongClick = {
                        haptic()
                        menuOpen = true
                    },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    this.selected = selected
                    this.customActions = customActions
                    unavailable?.let { stateDescription = it }
                },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(start = Spacing.l, end = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (summary.isScheduled && !nested) {
                    Icon(ClineIcons.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alpha(if (pending) PENDING_ALPHA else 1f),
                )
                Spacer(Modifier.width(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    if (item.visual.dot != null) SessionStatusDot(item.visual, standalone = false)
                    if (summary.pinned) {
                        Icon(ClineIcons.PushPinFilled, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        age,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.alpha(if (pending) PENDING_ALPHA else 1f),
                    )
                }
            }
        }
        SessionRowMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            items = menuItems,
            offset = press.offset,
            pendingItemId = pendingItemId,
        )
        }
    }
}

/** The 1dp `outlineVariant` rail nested rows draw at their start (desktop `border-l ml-4`). */
@Composable
private fun NestedRail() {
    Box(Modifier.width(1.dp).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Desktop `ScheduleGroupRow` (sessions.md §2.4): tap toggles; no menu. */
@Composable
fun ScheduleGroupRow(group: DrawerRow.ScheduleGroup, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runs = runCountText(group.runCount)
    val dot = when {
        group.running -> SessionVisual.Running
        group.unread -> SessionVisual.Unread
        else -> null
    }
    val runningLabel = stringResource(R.string.components_status_running)
    val unreadLabel = stringResource(R.string.components_status_unread)
    val description = context.joinA11y(
        listOfNotNull(
            group.label,
            stringResource(R.string.sessions_a11y_scheduled),
            runs,
            runningLabel.takeIf { group.running },
            unreadLabel.takeIf { group.unread },
        ),
    )
    val state = stringResource(if (group.expanded) R.string.sessions_state_expanded else R.string.sessions_state_collapsed)
    val selected = group.containsActive && !group.expanded
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = state
                role = Role.Button
            },
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = Spacing.l, end = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            DisclosureChevron(group.expanded, size = DisclosureChevronDefaults.MetaRowSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.xs))
            Icon(ClineIcons.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.s))
            Text(group.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.xs))
            if (dot != null) SessionStatusDot(dot, standalone = false)
            Spacer(Modifier.width(Spacing.s))
            Text(runs, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Desktop `CategorySection` / `ProjectSection` header (sessions.md §2.4). */
@Composable
fun SectionToggleHeader(label: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val state = stringResource(if (expanded) R.string.sessions_state_expanded else R.string.sessions_state_collapsed)
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
        shape = CircleShape,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(CircleShape)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = state
                role = Role.Button
            },
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            DisclosureChevron(expanded, size = DisclosureChevronDefaults.MetaRowSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.s))
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count != null) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The dense rename field (F2 step 1): a `BasicTextField` in the outlined decoration so it fits
 * a 48dp row and grows with font scale; prefilled with the cursor at the start, focused with
 * the IME up, one line (a pasted newline becomes a space), Done / Enter commits, Escape and
 * Back cancel (a Back key too, taken on the field before it can become a focus exit), with
 * one exit per interaction ([dev.cline.termux.ui.components.InlineEditExit], shared with the
 * header rename). With [commitOnBlur] (the drawer) losing focus commits, as the desktop's onBlur.
 * While [pending] the field is disabled with a trailing 16dp spinner. The field asks to be
 * brought into view once the IME is up, so a lower row is never covered.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RenameField(
    draft: RenameDraft,
    pending: Boolean,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    commitOnBlur: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit = {},
) {
    var value by rememberSaveable(draft.sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft.initialTitle, selection = TextRange(0)))
    }
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    // One exit per interaction (Enter, Done, Back, Escape or the blur), shared with the header rename.
    val exit = rememberInlineEditExit(key = draft.sessionId, onCommit = { onCommit(value.text) }, onCancel = onCancel)
    val interaction = remember { MutableInteractionSource() }
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(draft.sessionId) { focus.requestFocus() }
    LaunchedEffect(imeVisible) { if (imeVisible) bring.bringIntoView() }
    LaunchedEffect(value.text) { onTextChange(value.text) }
    OrderedBackHandler(BackLevel.InlineEdit, enabled = true, onBack = exit::cancel)

    val colors = OutlinedTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (!pending) {
                val text = next.text.replace('\n', ' ').replace('\r', ' ')
                if (text != value.text) exit.rearm()
                value = next.copy(text = text)
            }
        },
        enabled = !pending,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { exit.commit() }),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .bringIntoViewRequester(bring)
            .focusRequester(focus)
            .inlineEditKeys(exit, commitOnBlur = commitOnBlur && !pending)
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = inner,
                enabled = !pending,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                trailingIcon = if (pending) {
                    { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = !pending,
                        isError = false,
                        interactionSource = interaction,
                        colors = colors,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                },
            )
        },
    )
}

// ------------------------------------------------------------------------- previews

@LightDarkPreviews
@Composable
private fun SessionDrawerRowPreview() {
    ComponentPreview(padding = false) {
        val rows = SessionsPreviewData.rows
        androidx.compose.foundation.layout.Column {
            rows.take(4).forEachIndexed { index, row ->
                SessionDrawerRow(
                    item = DrawerRow.Session(row, row.visual),
                    selected = index == 0,
                    now = SessionsPreviewData.NOW,
                    actionsEnabled = true,
                    menuItems = emptyList(),
                    pendingItemId = null,
                    onOpen = {},
                )
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun SessionDrawerRowPendingPreview() {
    ComponentPreview(padding = false) {
        val row = SessionsPreviewData.pendingRow
        SessionDrawerRow(
            item = DrawerRow.Session(row, row.visual),
            selected = false,
            now = SessionsPreviewData.NOW,
            actionsEnabled = true,
            menuItems = emptyList(),
            pendingItemId = SessionMenuIds.RENAME,
            onOpen = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleGroupRowPreview() {
    ComponentPreview(padding = false) {
        androidx.compose.foundation.layout.Column {
            SectionToggleHeader("Scheduled", expanded = true, onToggle = {}, count = 1)
            ScheduleGroupRow(SessionsPreviewData.group(expanded = false), onToggle = {})
            ScheduleGroupRow(SessionsPreviewData.group(expanded = true), onToggle = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun RenameFieldPreview() {
    ComponentPreview {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RenameField(RenameDraft("a", "Fix login redirect"), pending = false, onCommit = {}, onCancel = {}, commitOnBlur = true, contentDescription = "Rename Fix login redirect")
            RenameField(RenameDraft("b", "Port OpenTUI 0.4.4"), pending = true, onCommit = {}, onCancel = {}, commitOnBlur = true, contentDescription = "Rename Port OpenTUI 0.4.4")
        }
    }
}

/** For callers that render a [SessionRow] outside the drawer body types. */
fun SessionRow.asDrawerRow(): DrawerRow.Session = DrawerRow.Session(this, visual)
