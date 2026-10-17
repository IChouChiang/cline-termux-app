package dev.cline.termux.ui.chat.rows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.resolveCredentialFailureAction
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.AnchoredContextMenu
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.markdown.MarkdownText
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/*
 * Message rows (HC §3.4.1, §3.4.2, §3.4.7; DS §4.1 #5; desktop `message-bubble.tsx` and
 * `agent-chat.css` roles): the end-aligned user bubble, the full-width assistant markdown, the
 * error surface with its credential or resend action, and plain system rows. Hover actions
 * become a tap-to-reveal action row, a long-press menu at the finger and TalkBack custom
 * actions (DS §9).
 */

/** User bubbles take 85 % of the column on compact windows and 60 % from medium up (DS §4.1 #5). */
@Composable
internal fun userBubbleFraction(): Float {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    return if (wide) 0.6f else 0.85f
}

/** No reasoning on this answer (the desktop `ReasoningBlock` renders nothing). */
private val EmptyReasoning = ReasoningContent(text = "", redacted = false)

/** Bubble padding (voice `ChatRows`): 16dp × 10dp. */
private val BubblePaddingHorizontal = 16.dp
private val BubblePaddingVertical = 10.dp

/**
 * The text of a message in "Select text" mode (HC §3.14): a read-only field with the whole
 * text selected and focused. The state-based field draws the selection handles for a
 * programmatic selection, but it only raises the system text toolbar after a gesture, so the
 * toolbar (Copy / Select all) is shown here once the field is placed. Dragging a handle hands
 * the toolbar back to the field. Back, a tap on the message, or leaving composition ends the mode.
 */
@Composable
private fun SelectableMessageText(
    messageId: String,
    text: String,
    selection: TranscriptSelection,
    style: androidx.compose.ui.text.TextStyle,
    onCopy: (String) -> Unit,
) {
    val state = remember(text) { TextFieldState(text, TextRange(0, text.length)) }
    val focus = remember { FocusRequester() }
    val toolbar = LocalTextToolbar.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(bounds != null) {
        val rect = bounds ?: return@LaunchedEffect
        toolbar.showMenu(
            rect = rect,
            onCopyRequested = {
                val range = state.selection
                onCopy(state.text.substring(range.min, range.max).ifEmpty { text })
                toolbar.hide()
            },
            onSelectAllRequested = { state.edit { selectAll() } },
        )
    }
    BackHandler { selection.clear() }
    DisposableEffect(messageId) {
        onDispose {
            if (toolbar.status == TextToolbarStatus.Shown) toolbar.hide()
            if (selection.isSelecting(messageId)) selection.clear()
        }
    }
    BasicTextField(
        state = state,
        readOnly = true,
        textStyle = style,
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onGloballyPositioned { if (bounds == null) bounds = it.boundsInRoot() },
    )
}

/** The user turn (HC §3.4.1). Steering notes never reach here (the model filters them). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(
    message: ChatMessage,
    facts: MessageFacts,
    actionsState: MessageActionsState,
    callbacks: TranscriptCallbacks,
    selection: TranscriptSelection,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false,
) {
    val displayText = remember(message.content) { displayContentOf(message) }
    val runCount = facts.runCount
    val actions = userActionSet(
        message = message,
        displayText = displayText,
        runCount = runCount,
        state = actionsState,
        canEdit = callbacks.onEditMessage != null,
        canRestore = callbacks.onRestoreCheckpoint != null,
    )
    var revealed by rememberSaveable(message.renderKey) { mutableStateOf(initiallyRevealed) }
    val copiedFlag = rememberCopiedFlag()
    val copy = rememberMessageCopy(copiedFlag)
    val copyLabel = stringResource(R.string.msg_copy_user)
    val selecting = selection.isSelecting(message.id)
    val haptic = rememberLongPressHaptic()
    val press = remember { PressPoint() }
    var menuOpen by remember { mutableStateOf(false) }

    val doCopy = { copy(copyLabel, displayText) }
    val doEdit = callbacks.onEditMessage?.takeIf { actions.edit && actions.editEnabled && runCount != null }
        ?.let { edit -> { edit(message.id, displayText, runCount!!) } }
    val checkpoint = actions.checkpoint
    val doRestore = callbacks.onRestoreCheckpoint?.takeIf { actions.restore && actions.restoreEnabled && checkpoint != null }
        ?.let { restore -> { restore(message.id, checkpoint!!.runCount) } }
    val doSelect = { selection.select(message.id) }

    val menuItems = userMenuActions(canEdit = doEdit != null, canRestore = doRestore != null).map { action ->
        val onClick = when (action) {
            UserMenuAction.Copy -> doCopy
            UserMenuAction.Edit -> doEdit!!
            UserMenuAction.Restore -> doRestore!!
            UserMenuAction.Select -> doSelect
        }
        ContextMenuItem(action.id, stringResource(action.label), onClick)
    }
    val customActions = listOfNotNull(
        CustomAccessibilityAction(copyLabel) { doCopy(); true },
        doEdit?.let { action -> CustomAccessibilityAction(stringResource(R.string.msg_edit_user)) { action(); true } },
        doRestore?.let { action -> CustomAccessibilityAction(stringResource(R.string.msg_restore)) { action(); true } },
        CustomAccessibilityAction(stringResource(R.string.msg_select_text)) { doSelect(); true },
    )
    val fraction = userBubbleFraction()
    val showActions = stringResource(R.string.msg_show_actions)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            val maxBubble: Dp = maxWidth * fraction
            Box(Modifier.trackPress(press)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .widthIn(max = maxBubble)
                        .focusRing(MaterialTheme.shapes.large, inset = true)
                        .combinedClickable(
                            onClickLabel = showActions,
                            onClick = { if (selecting) selection.clear() else revealed = !revealed },
                            onLongClick = {
                                haptic()
                                menuOpen = true
                            },
                        )
                        .semantics { this.customActions = customActions },
                ) {
                    Column(
                        Modifier.padding(horizontal = BubblePaddingHorizontal, vertical = BubblePaddingVertical),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        AttachmentImages(message.id, message.images, callbacks.onExpandImage)
                        if (displayText.isNotEmpty()) {
                            val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                            if (selecting) {
                                SelectableMessageText(message.id, displayText, selection, style, onCopy = { copy(copyLabel, it) })
                            } else {
                                Text(displayText, style = style)
                            }
                        }
                    }
                }
                AnchoredContextMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    items = menuItems,
                    offset = press.offset,
                )
            }
        }
        if (revealed || actions.keepVisible) {
            UserActionRow(
                message = message,
                displayText = displayText,
                runCount = runCount,
                actions = actions,
                copied = copiedFlag.on,
                onCopy = doCopy,
                callbacks = callbacks,
            )
        }
        if (actions.draining) DrainingWaitLine()
    }
}

/**
 * An assistant answer (HC §3.4.2): reasoning row, generated images, media, then markdown at
 * full width. [isRunActive] lets a live, unfinished thinking row keep its "Thinking" label. The
 * row offers copy and select text, no fork (decided A16: the session menus fork).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantMessage(
    item: ChatRenderItem.Message,
    facts: MessageFacts,
    callbacks: TranscriptCallbacks,
    selection: TranscriptSelection,
    isRunActive: Boolean,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false,
) {
    val message = item.message
    val streaming = facts.isStreaming
    val actions = assistantActionSet(message = message, isStreaming = streaming, isLastAssistantMessage = facts.isLastAssistant)
    var revealed by rememberSaveable(message.renderKey) { mutableStateOf(initiallyRevealed) }
    val copiedFlag = rememberCopiedFlag()
    val copy = rememberMessageCopy(copiedFlag)
    val copyLabel = stringResource(R.string.msg_copy_assistant)
    val selecting = selection.isSelecting(message.id)
    val haptic = rememberLongPressHaptic()
    val press = remember { PressPoint() }
    var menuOpen by remember { mutableStateOf(false) }

    // Joined text and duration come with the row's facts (built off Main with the model).
    val reasoning = facts.reasoning ?: EmptyReasoning
    val duration = facts.reasoningDurationMs
    val lastReasoning = item.reasoningMessages.lastOrNull()
    val reasoningStreaming = streaming || (
        isRunActive && message.content.isBlank() && lastReasoning != null &&
            lastReasoning.meta.thoughtEndedAt == null && lastReasoning.meta.requestId != null
        )

    val doCopy = { copy(copyLabel, message.content) }
    // The markdown body only becomes selectable (no select-all API), so say how to begin.
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val selectHint = stringResource(R.string.msg_select_text_hint)
    val doSelect = {
        selection.select(message.id)
        scope.launch { snackbar.showReplacing(selectHint) }
        Unit
    }
    val interactive = actions.visibleAtAll
    val menuItems = if (!interactive) emptyList() else listOf(
        ContextMenuItem("copy", stringResource(R.string.msg_menu_copy), doCopy),
        ContextMenuItem("select", stringResource(R.string.msg_select_text), doSelect),
    )
    val customActions = if (!interactive) emptyList() else listOf(
        CustomAccessibilityAction(copyLabel) { doCopy(); true },
        CustomAccessibilityAction(stringResource(R.string.msg_select_text)) { doSelect(); true },
    )
    val showActions = stringResource(R.string.msg_show_actions)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (!reasoning.isEmpty) {
            ReasoningBlock(
                content = reasoning,
                streaming = reasoningStreaming,
                durationMilliseconds = duration,
                stateKey = "${message.renderKey}:reasoning",
            )
        }
        if (message.images.isNotEmpty()) {
            GeneratedImages(cacheKeyPrefix = message.id, images = message.images, onExpand = callbacks.onExpandImage)
        }
        if (facts.media.isNotEmpty()) {
            GeneratedMediaPresentations(presentations = facts.media, cacheKeyPrefix = message.id, onExpandImage = callbacks.onExpandImage)
        }
        if (message.content.isNotEmpty()) {
            Box(Modifier.trackPress(press)) {
                // While selecting, the selection gestures own the body; back or a tap outside ends it.
                val bodyModifier = if (interactive && !selecting) {
                    Modifier
                        .combinedClickable(
                            indication = null,
                            interactionSource = null,
                            onClickLabel = showActions,
                            onClick = { if (selecting) selection.clear() else revealed = !revealed },
                            onLongClick = {
                                haptic()
                                menuOpen = true
                            },
                        )
                        .semantics { this.customActions = customActions }
                } else {
                    Modifier
                }
                MarkdownText(
                    markdown = message.content,
                    modifier = Modifier.fillMaxWidth().then(bodyModifier),
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    selectable = selecting,
                    streaming = streaming,
                )
                if (selecting) {
                    BackHandler { selection.clear() }
                    DisposableEffect(message.id) { onDispose { if (selection.isSelecting(message.id)) selection.clear() } }
                }
                AnchoredContextMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    items = menuItems,
                    offset = press.offset,
                )
            }
        }
        if (actions.visibleAtAll && (actions.pinned || revealed)) {
            AssistantActionRow(
                message = message,
                copied = copiedFlag.on,
                onCopy = doCopy,
                // A11.9: Verbose only (the facts carry no stats in Brief).
                stats = facts.turnStats,
            )
        }
    }
}

/**
 * The error surface (HC §3.4.7, DS §1.7): `errorContainer`, `shapes.medium`, 12dp padding,
 * selectable text from `formatRunError` (or the interrupted copy), then the credential action
 * (`meta.reason == "credentials"`) or "Resend" for an interrupted turn.
 */
@Composable
fun ErrorMessage(
    message: ChatMessage,
    facts: MessageFacts,
    actionsState: MessageActionsState,
    callbacks: TranscriptCallbacks,
    runErrorStrings: RunErrorStrings,
    modifier: Modifier = Modifier,
) {
    val interrupted = stringResource(R.string.run_interrupted)
    val text = remember(message, runErrorStrings, interrupted) { errorRowText(message, runErrorStrings, interrupted) }
    val credentialAction = remember(message) {
        message.meta.providerId?.takeIf { message.meta.reason == REASON_CREDENTIALS }
            ?.let { resolveCredentialFailureAction(it, message.meta.providerAuth) }
    }
    val resend = callbacks.onResend?.takeIf { showsResend(message, facts.superseded, actionsState) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // DS §8.1.3: the error icon as well as the colour; the text stays selectable.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ErrorRowIcon()
                MarkdownText(
                    markdown = text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onErrorContainer),
                    selectable = true,
                )
            }
            val onCredential = callbacks.onCredentialAction
            if (credentialAction != null && onCredential != null) {
                OutlinedButton(onClick = { onCredential(credentialAction) }) {
                    Text(stringResource(credentialAction.labelRes))
                }
            }
            if (resend != null) {
                val pending = actionsState.resendPendingId == message.id
                OutlinedButton(onClick = { resend(message.id) }, enabled = !pending) {
                    if (pending) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            InlineSpinner()
                            Text(stringResource(R.string.run_resend))
                        }
                    } else {
                        Text(stringResource(R.string.run_resend))
                    }
                }
            }
        }
    }
}

/** System rows: `bodyMedium` `onSurfaceVariant` (DS §4.1 #5 "System/status"). */
@Composable
fun SystemMessage(message: ChatMessage, modifier: Modifier = Modifier) {
    Text(
        text = message.content,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
