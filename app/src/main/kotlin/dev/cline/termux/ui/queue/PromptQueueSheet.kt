package dev.cline.termux.ui.queue

import android.view.KeyCharacterMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.suggestions.message
import dev.cline.termux.ui.theme.Spacing

/** The row actions a sheet row offers; every action is disabled while one is pending. */
class PromptQueueActions(
    val onSteer: (String) -> Unit,
    val onStartEdit: (String) -> Unit,
    val onEditTextChange: (String) -> Unit,
    val onSaveEdit: () -> Unit,
    val onCancelEdit: () -> Unit,
    val onRemove: (String) -> Unit,
    val onDismiss: () -> Unit,
) {
    companion object {
        fun of(controller: PromptQueueController) = PromptQueueActions(
            onSteer = controller::steer,
            onStartEdit = controller::startEdit,
            onEditTextChange = controller::onEditTextChange,
            onSaveEdit = controller::saveEdit,
            onCancelEdit = controller::cancelEdit,
            onRemove = controller::remove,
            onDismiss = controller::closeSheet,
        )
    }
}

/**
 * Hosts the queue's outcome snackbars (strip Steer failures) and the sheet while it is
 * open. Composed once by the chat screen next to the composer; [returnFocus] is the strip's
 * requester, which gets focus back when the sheet closes (DS §8.1.7).
 */
@Composable
fun PromptQueueHost(controller: PromptQueueController, returnFocus: FocusRequester? = null) {
    val state by controller.state.collectAsState()
    val snackbar = LocalSnackbarHost.current
    val resources = LocalContext.current.resources
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is QueueEvent.StripSteerFailed -> {
                    val detail = queueFailureText(resources, event.failure, QueueAction.Steer)
                    snackbar.showReplacing(resources.getString(R.string.queue_steer_failed, detail))
                }
            }
        }
    }
    if (state.sheetOpen) {
        val actions = remember(controller) { PromptQueueActions.of(controller) }
        PromptQueueSheet(state, actions, returnFocus)
    }
}

/**
 * The Prompt queue sheet (composer-approvals.md C2.3): every queued prompt with Steer
 * (non-steering rows), Edit and Remove; the steering row is tinted `primary` at 5 % and
 * badged "Next turn". Back cancels an edit before it collapses the sheet (C2.8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptQueueSheet(state: PromptQueueState, actions: PromptQueueActions, returnFocus: FocusRequester? = null) {
    ClineSheet(
        onDismissRequest = actions.onDismiss,
        returnFocus = returnFocus,
        // No half-height anchor, so M3's predictive collapse hides the sheet in one back (DS §5.8 item 2).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = copyPluralStringResource(R.plurals.queue_count, state.items.size, state.items.size),
    ) {
        // Inside the sheet's own window: ModalBottomSheet has its own back dispatcher, and this
        // later-registered callback wins over its dismiss while an edit is open.
        BackHandler(enabled = state.editingId != null) { actions.onCancelEdit() }
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.link == QueueLink.Reconnecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs))
            }
            state.items.forEach { item ->
                PromptQueueRow(item, state, actions)
            }
        }
    }
}

@Composable
private fun PromptQueueRow(item: QueuedPromptItem, state: PromptQueueState, actions: PromptQueueActions) {
    val editing = state.editingId == item.id
    val pending = state.pendingId == item.id
    val enabled = state.actionsEnabled
    val resources = LocalContext.current.resources
    val nextTurn = stringResource(R.string.queue_next_turn)
    val attachments = if (item.attachmentCount > 0) {
        copyPluralStringResource(R.plurals.queue_attachments, item.attachmentCount, item.attachmentCount)
    } else {
        null
    }
    val summary = listOfNotNull(item.displayPrompt, nextTurn.takeIf { item.steer }, attachments)
        .reduce { acc, part -> resources.getString(R.string.queue_row_summary, acc, part) }
    val updating = stringResource(R.string.queue_state_updating)
    val steerLabel = stringResource(R.string.cd_queue_steer)
    val editLabel = stringResource(R.string.cd_queue_edit)
    val removeLabel = stringResource(R.string.cd_queue_remove)
    val error = state.error?.takeIf { it.promptId == item.id }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.steer) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)) else Modifier)
            .padding(start = Spacing.sheetGutter, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        QueueStatusIcon(item.steer, Modifier.padding(top = if (editing) Spacing.l else Spacing.xxs))
        Box(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            if (editing) {
                QueueEditField(state.editingText, enabled = !pending, actions = actions, label = editLabel)
            } else {
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = summary
                        if (pending) stateDescription = updating
                        if (enabled) {
                            customActions = buildList {
                                if (!item.steer) add(CustomAccessibilityAction(steerLabel) { actions.onSteer(item.id); true })
                                add(CustomAccessibilityAction(editLabel) { actions.onStartEdit(item.id); true })
                                add(CustomAccessibilityAction(removeLabel) { actions.onRemove(item.id); true })
                            }
                        }
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.displayPrompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (item.steer) {
                            Box(Modifier.width(Spacing.s))
                            TagBadge(nextTurn)
                        }
                    }
                    if (attachments != null) {
                        Text(attachments, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (error != null) {
                Text(
                    text = queueFailureText(resources, error.failure, error.action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (editing) {
                    QueueActionButton(
                        pending = pending && state.pendingAction == QueueAction.Save,
                        icon = { TooltipIconButton(ClineIcons.States.Check, stringResource(R.string.cd_queue_save), actions.onSaveEdit, enabled = enabled && state.editingText.isNotBlank(), iconSize = 20.dp) },
                    )
                    TooltipIconButton(ClineIcons.Chrome.Close, stringResource(R.string.cd_queue_cancel_edit), actions.onCancelEdit, enabled = !pending, iconSize = 20.dp)
                } else {
                    if (!item.steer) {
                        QueueActionButton(
                            pending = pending && state.pendingAction == QueueAction.Steer,
                            icon = { TooltipIconButton(ClineIcons.Chrome.SteerQueuedPrompt, steerLabel, { actions.onSteer(item.id) }, tooltip = stringResource(R.string.tt_queue_steer), enabled = enabled, iconSize = 20.dp) },
                        )
                    }
                    TooltipIconButton(ClineIcons.Chrome.EditMessage, editLabel, { actions.onStartEdit(item.id) }, enabled = enabled, iconSize = 20.dp)
                    QueueActionButton(
                        pending = pending && state.pendingAction == QueueAction.Remove,
                        icon = { TooltipIconButton(ClineIcons.Chrome.Delete, removeLabel, { actions.onRemove(item.id) }, enabled = enabled, iconSize = 20.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueActionButton(pending: Boolean, icon: @Composable () -> Unit) {
    if (pending) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    } else {
        icon()
    }
}

/** C2.7 edit field: 1..4 lines, soft Enter = newline; hardware Enter saves, Shift+Enter newline, Esc cancels. */
@Composable
private fun QueueEditField(text: String, enabled: Boolean, actions: PromptQueueActions, label: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The field owns its TextFieldValue so the IME composition is visible to the key handler;
    // the controller only sees the text. It is seeded once: the field exists only while this
    // row is being edited and nothing else rewrites the draft meanwhile (start, cancel, save and
    // reconcile all mount or unmount it), and resyncing from the collected state, which lags a
    // frame behind typing, would drop keystrokes and break an open composition.
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val changed = next.text != value.text
            value = next
            if (changed) actions.onEditTextChange(next.text)
        },
        enabled = enabled,
        label = { Text(label) },
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val key = queueEditKey(
                    keyDown = event.type == KeyEventType.KeyDown,
                    hardware = event.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD,
                    key = event.key,
                    shift = event.isShiftPressed,
                    composing = value.composition != null,
                )
                when (key) {
                    QueueEditKey.Save -> actions.onSaveEdit()
                    QueueEditKey.Cancel -> actions.onCancelEdit()
                    QueueEditKey.PassThrough -> Unit
                }
                key != QueueEditKey.PassThrough
            },
    )
}

/** What a key does in the C2.7 edit field; [PassThrough] leaves it to the text field (and the IME). */
internal enum class QueueEditKey { Save, Cancel, PassThrough }

/**
 * The C2.7 key map, ported from the desktop editor (agent-prompt-queue.tsx): only physical
 * key-downs act; Escape cancels; Enter (or numpad Enter) without Shift saves, except while an
 * IME composition is open (`!event.nativeEvent.isComposing`), where the Enter belongs to the IME
 * so half-typed pinyin or kana is never saved.
 */
internal fun queueEditKey(keyDown: Boolean, hardware: Boolean, key: Key, shift: Boolean, composing: Boolean): QueueEditKey = when {
    !hardware || !keyDown -> QueueEditKey.PassThrough
    key == Key.Escape -> QueueEditKey.Cancel
    (key == Key.Enter || key == Key.NumPadEnter) && !shift && !composing -> QueueEditKey.Save
    else -> QueueEditKey.PassThrough
}

/** C2.5 error text: kernel text verbatim, else the action's fallback; refusals use the C5.5 copy. */
internal fun queueFailureText(resources: android.content.res.Resources, failure: QueueFailure, action: QueueAction): String = when (failure) {
    is QueueFailure.Kernel -> failure.message.ifBlank { resources.getString(fallbackFor(action)) }
    QueueFailure.NotConnected -> resources.getString(R.string.hub_not_connected)
    is QueueFailure.Refused -> failure.refusal.message(resources)
}

private fun fallbackFor(action: QueueAction): Int = when (action) {
    QueueAction.Steer -> R.string.queue_err_steer
    QueueAction.Save -> R.string.queue_err_update
    QueueAction.Remove -> R.string.queue_err_remove
}

private val noActions = PromptQueueActions({}, {}, {}, {}, {}, {}, {})

@LightDarkPreviews
@Composable
private fun PromptQueueRowsPreview() {
    ComponentPreview(padding = false) {
        Column {
            val state = PromptQueueState(items = previewItems, loaded = true, sheetOpen = true)
            previewItems.forEach { PromptQueueRow(it, state, noActions) }
        }
    }
}

@LightDarkPreviews
@Composable
private fun PromptQueueRowsEditingPreview() {
    ComponentPreview(padding = false) {
        Column {
            val state = PromptQueueState(
                items = previewItems,
                loaded = true,
                sheetOpen = true,
                editingId = "q2",
                editingText = "Also add tests for the parser",
                error = QueueError("q2", QueueAction.Save, QueueFailure.Kernel("")),
            )
            previewItems.forEach { PromptQueueRow(it, state, noActions) }
        }
    }
}

@LightDarkPreviews
@Composable
private fun PromptQueueRowsPendingPreview() {
    ComponentPreview(padding = false) {
        Column {
            val state = PromptQueueState(items = previewItems, loaded = true, sheetOpen = true, pendingId = "q3", pendingAction = QueueAction.Remove)
            previewItems.forEach { PromptQueueRow(it, state, noActions) }
            val reconnecting = state.copy(pendingId = null, pendingAction = null, link = QueueLink.Reconnecting)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            previewItems.take(1).forEach { PromptQueueRow(it, reconnecting, noActions) }
        }
    }
}
