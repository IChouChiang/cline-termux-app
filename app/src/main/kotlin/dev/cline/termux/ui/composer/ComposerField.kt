package dev.cline.termux.ui.composer

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** The placeholder per C1.10 precedence (voice, kernel, busy, variant), as copy data (unit-tested). */
fun composerPlaceholderText(state: ComposerUiState, fieldEmpty: Boolean): UiText = when {
    state.voice == VoiceState.Processing -> UiText.Res(R.string.composer_placeholder_transcribing)
    state.voice is VoiceState.Listening && fieldEmpty -> UiText.Res(R.string.composer_placeholder_listening)
    state.kernel.placeholder != null -> state.kernel.placeholder
    state.isBusy && state.variant == ComposerVariant.Conversation && state.queueSize > 0 && state.hardwareKeyboard ->
        UiText.Res(R.string.composer_placeholder_busy_queue)
    state.isBusy && state.variant == ComposerVariant.Conversation -> UiText.Res(R.string.composer_placeholder_busy)
    state.variant == ComposerVariant.Welcome -> UiText.Res(R.string.composer_placeholder_welcome)
    else -> UiText.Res(R.string.composer_placeholder_conversation)
}

/** [composerPlaceholderText] resolved against the current configuration. */
@Composable
fun composerPlaceholder(state: ComposerUiState, fieldEmpty: Boolean): String = composerPlaceholderText(state, fieldEmpty).resolve()

/** Hardware keys the field handles (C1.8); the composer maps them to controller calls. */
class FieldKeys(
    /** Slash/mention panel keys first; true = consumed. */
    val onPanelKey: ((KeyEvent) -> Boolean)?,
    val onEnter: () -> Boolean,
    val onEscape: () -> Boolean,
)

/** B9: the expand icon shows once the text's layout passes three lines. */
internal fun showsExpandEditor(lineCount: Int): Boolean = lineCount > EXPAND_EDITOR_AFTER_LINES

private const val EXPAND_EDITOR_AFTER_LINES = 3

/** The prompt-history hooks of the field row (A11.6). */
class FieldHistory(
    /** A fling or a TalkBack action stepped (the controller checks the field again). */
    val onStep: (HistoryStep) -> Unit,
    /** A gesture's first touch in an eligible field: refresh the cached list. */
    val onTouch: () -> Unit,
) {
    companion object {
        val None = FieldHistory(onStep = {}, onTouch = {})
    }
}

/**
 * C1.3 FieldRow: the state-based `BasicTextField` (1→2→5 lines, 3 in height-compact
 * windows; `readOnly` for kernel down, resuming and voice; `enabled = false` only while the
 * session loads) and the mic slot. Images pasted from the clipboard or a keyboard go to
 * [onImages] instead of the text.
 *
 * A11.6: while [ComposerUiState.historyGestures] holds, a quick horizontal fling inside the
 * field steps the recent prompts ([HistoryFlingRules]: left to right recalls the previous,
 * older prompt and right to left the next, newer one, A18) and TalkBack gets "Previous prompt" /
 * "Next prompt". B9: past three lines an expand icon at the field's end calls [onExpand].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerFieldRow(
    state: ComposerUiState,
    draft: TextFieldState,
    focused: Boolean,
    heightCompact: Boolean,
    keys: FieldKeys,
    onImages: (List<Uri>) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onMic: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    suggestionCount: Int? = null,
    imeOpen: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    history: FieldHistory = FieldHistory.None,
    onExpand: (() -> Unit)? = null,
) {
    val maxLines = composerMaxLines(heightCompact, imeOpen)
    val minLines = composerMinLines(state.variant == ComposerVariant.Welcome, focused, heightCompact, imeOpen).coerceAtMost(maxLines)
    val placeholder = composerPlaceholder(state, draft.text.isEmpty())
    val images by rememberUpdatedState(onImages)
    val receiver = remember {
        ReceiveContentListener { content: TransferableContent ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            val uris = ArrayList<Uri>()
            val rest = content.consume { item -> item.uri?.let { uris += it; true } ?: false }
            if (uris.isNotEmpty()) images(uris)
            rest
        }
    }
    val listening = stringResource(R.string.voice_state_listening)
    val transcribing = stringResource(R.string.composer_state_transcribing)
    val suggestions = suggestionCount?.let { copyPluralStringResource(R.plurals.composer_suggestions_state, it, it) }
    val fieldState = when {
        state.voice is VoiceState.Listening -> listening
        state.voice == VoiceState.Processing -> transcribing
        else -> suggestions
    }
    val historyActions = historyAccessibilityActions(state, history.onStep)
    val fling = rememberHistoryFlingInput(state.historyGestures, history)
    var lineCount by remember { mutableIntStateOf(0) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            state = draft,
            modifier = Modifier
                .weight(1f)
                // Outside the padding: a fling may start anywhere on the field's 56dp row.
                .historyFling(fling)
                .padding(vertical = Spacing.l)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .contentReceiver(receiver)
                .onPreviewKeyEvent { event -> handleComposerKey(event, draft, keys) }
                .semantics {
                    traversalIndex = -1f
                    if (fieldState != null) stateDescription = fieldState
                    if (historyActions.isNotEmpty()) customActions = historyActions
                },
            onTextLayout = { result -> lineCount = result()?.lineCount ?: 0 },
            enabled = !state.loading,
            readOnly = state.readOnly,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            decorator = { inner ->
                Box {
                    if (draft.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Wraps up to the field's own height limit so the busy/queue copy survives
                            // font scale 2.0 (DS §8.1.8); the field grows with it (minHeightInLines).
                            maxLines = maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (onExpand != null && showsExpandEditor(lineCount)) {
            val cd = stringResource(R.string.composer_editor_cd_expand)
            PlainTooltipBox(text = cd, modifier = Modifier.align(Alignment.Top).padding(top = Spacing.xs)) {
                IconButton(onClick = onExpand, colors = composerIconButtonColors(), modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.OpenInFull, contentDescription = cd, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (state.voice != VoiceState.Unavailable && !state.kernel.readOnly) {
            MicSlot(state.voice, onMic)
        }
        trailing?.invoke()
    }
}

/**
 * A11.6's accessible path: "Previous prompt" (older) while an older prompt may follow, "Next
 * prompt" (newer, leaving browsing from the newest) while browsing.
 */
@Composable
private fun historyAccessibilityActions(state: ComposerUiState, onStep: (HistoryStep) -> Unit): List<CustomAccessibilityAction> {
    val previous = stringResource(R.string.composer_history_action_previous)
    val next = stringResource(R.string.composer_history_action_next)
    val step by rememberUpdatedState(onStep)
    return buildList {
        if (state.historyOlderAvailable) add(CustomAccessibilityAction(previous) { step(HistoryStep.Older); true })
        if (state.historyGestures && state.history != null) add(CustomAccessibilityAction(next) { step(HistoryStep.Newer); true })
    }
}

/**
 * What the history fling reads at event time: written by the field row after each composition
 * (a `SideEffect`), so the pointer handler never restarts when the state changes.
 */
private class HistoryFlingInput {
    var enabled = false
    var rtl = false
    var windowWidthPx = 0
    var onTouch: () -> Unit = {}
    var onStep: (HistoryStep) -> Unit = {}

    /** Where the field sits in the window, for the edge exclusion. */
    var coordinates: LayoutCoordinates? = null
}

@Composable
private fun rememberHistoryFlingInput(enabled: Boolean, history: FieldHistory): HistoryFlingInput {
    val input = remember { HistoryFlingInput() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    SideEffect {
        input.enabled = enabled
        input.rtl = rtl
        input.windowWidthPx = windowWidthPx
        input.onTouch = history.onTouch
        input.onStep = history.onStep
    }
    return input
}

/**
 * A11.6: the history fling inside the field ([HistoryFlingRules]). The Initial pass is watched
 * without consuming, so taps, long-press selection and vertical scrolling stay the field's; a
 * gesture that locks horizontal while [HistoryFlingInput.enabled] consumes the rest, so neither
 * the drawer nor the field moves with it. A touch that stays still past the long-press timeout
 * is the field's.
 */
private fun Modifier.historyFling(input: HistoryFlingInput): Modifier = this
    .onGloballyPositioned { input.coordinates = it }
    .pointerInput(input) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!input.enabled) return@awaitEachGesture
            val field = input.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val windowX = field.localToWindow(down.position).x
            if (!HistoryFlingRules.startAllowed(windowX.toDp().value, input.windowWidthPx.toDp().value)) return@awaitEachGesture
            val rtl = input.rtl
            val step = input.onStep
            input.onTouch()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var locked = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A second finger (pinch, two-finger scroll) is never a history fling.
                if (!locked && event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - down.position
                if (!locked) {
                    when (HistoryFlingRules.lock(delta.x, delta.y, viewConfiguration.touchSlop)) {
                        HistoryDragLock.Undecided ->
                            if (change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                        HistoryDragLock.Other -> return@awaitEachGesture
                        HistoryDragLock.Horizontal -> locked = true
                    }
                }
                if (locked) change.consume()
                if (!change.pressed) {
                    if (locked) {
                        val velocity = tracker.calculateVelocity()
                        HistoryFlingRules.classify(delta.x.toDp().value, delta.y.toDp().value, velocity.x.toDp().value, rtl)?.let(step)
                    }
                    return@awaitEachGesture
                }
            }
        }
    }

/** C1.8: only physical keys, never during an IME composition (the composer field and the B9 editor). */
internal fun handleComposerKey(event: KeyEvent, draft: TextFieldState, keys: FieldKeys): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val native = event.nativeKeyEvent
    val device = native.device
    if (device == null || device.isVirtual) return false
    if (draft.composition != null) return false
    keys.onPanelKey?.let { if (it(event)) return true }
    return when (native.keyCode) {
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> when {
            event.isShiftPressed -> false
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> false
            native.repeatCount > 0 -> true
            else -> keys.onEnter()
        }
        AndroidKeyEvent.KEYCODE_ESCAPE -> keys.onEscape()
        else -> false
    }
}

/** C7.4 mic slot: Idle mic, Listening stop over an RMS-scaled ring, Processing spinner. */
@Composable
private fun MicSlot(voice: VoiceState, onClick: () -> Unit) {
    when (voice) {
        VoiceState.Unavailable -> Unit
        VoiceState.Idle -> {
            val cd = stringResource(R.string.voice_cd_record_speech)
            PlainTooltipBox(text = cd) {
                IconButton(onClick = onClick, colors = composerIconButtonColors(), modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.Mic, contentDescription = cd)
                }
            }
        }
        is VoiceState.Listening -> {
            val cd = stringResource(R.string.voice_cd_stop_recording)
            val listening = stringResource(R.string.voice_state_listening)
            val reduced = ClineTheme.reducedMotion
            val level by animateFloatAsState(if (reduced) 0f else voice.level, tween(100), label = "rms")
            PlainTooltipBox(text = cd) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .scale(1f + 0.35f * level)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.3f), CircleShape),
                    )
                    FilledIconButton(
                        onClick = onClick,
                        modifier = Modifier.size(40.dp).focusRing(CircleShape).semantics {
                            role = Role.Button
                            contentDescription = cd
                            stateDescription = listening
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Icon(ClineIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp)) }
                }
            }
        }
        VoiceState.Processing -> {
            val cd = stringResource(R.string.voice_cd_transcribing)
            Box(Modifier.size(48.dp).semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
