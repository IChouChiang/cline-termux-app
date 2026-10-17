package dev.cline.termux.ui.question

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import dev.cline.termux.R
import dev.cline.termux.data.session.MistakeLimitContext
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.approval.PendingInputFocus
import dev.cline.termux.ui.approval.PendingInputSheet
import dev.cline.termux.ui.approval.SheetVisibility
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The intents the sheet sends to [AskQuestionController]. */
class QuestionActions(
    val onSelect: (requestId: String, option: String) -> Unit,
    val onCustomChange: (requestId: String, text: String) -> Unit,
    val onSubmit: (requestId: String) -> Unit,
    val onCollapse: () -> Unit,
) {
    companion object {
        fun of(controller: AskQuestionController) = QuestionActions(
            onSelect = controller::selectOption,
            onCustomChange = controller::setCustomAnswer,
            onSubmit = controller::submit,
            onCollapse = controller::collapse,
        )

        internal val None = QuestionActions({ _, _ -> }, { _, _ -> }, {}, {})
    }
}

/**
 * The ask-question sheet (composer-approvals.md C4, DS #10): the question as the heading,
 * lettered options (a tap answers a single-choice question at once), and a footer with the
 * custom answer field + Submit pinned above the IME. Collapsing sends nothing and shows the
 * banner. With several open questions (rare) each carries its own footer inline.
 * [returnFocus] gets focus once the sheet has closed: [PendingInputFocus.questionReturn].
 */
@Composable
fun AskQuestionSheet(
    state: QuestionUiState,
    actions: QuestionActions,
    modifier: Modifier = Modifier,
    returnFocus: FocusRequester? = null,
) {
    // Once answered the items empty out while the sheet is still animating away; keep drawing the last
    // question so the content does not swap to the pane title mid-exit.
    var held by remember { mutableStateOf(state) }
    if (state.items.isNotEmpty() && held != state) held = state
    val shown = if (state.items.isEmpty()) held else state
    val single = shown.items.singleOrNull()
    val footer: (@Composable () -> Unit)? = single?.let { item -> { QuestionFooter(item, shown.reconnecting, actions) } }
    PendingInputSheet(
        visibility = state.sheet,
        hasContent = state.items.isNotEmpty(),
        onCollapse = actions.onCollapse,
        titleContent = {
            if (single != null) QuestionTitle(questionTitle(single), spoken = questionText(single)) else QuestionTitle(stringResource(R.string.question_pane_title))
        },
        modifier = modifier,
        paneTitle = stringResource(R.string.question_pane_title),
        footer = footer,
        footerRidesIme = true,
        returnFocus = returnFocus,
    ) {
        QuestionBody(shown, actions)
    }
}

/** The sheet's heading; [spoken] is what TalkBack reads for it when it says more than [text] (the whole question). */
@Composable
private fun RowScope.QuestionTitle(text: String, spoken: String = text) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .weight(1f)
            .padding(vertical = Spacing.s)
            .then(if (spoken != text) Modifier.semantics { contentDescription = spoken } else Modifier),
    )
}

/**
 * The visible heading of [item]: the agent's question, or for a mistake limit (G2) the desktop's
 * first and last lines only. The "Latest:" detail (up to 600 characters) sits at the top of the
 * scrolling body ([MistakeDetail]): the heading is outside the scroll and has no line limit, so
 * a long detail there pushed the options and the answer field off the screen with the IME up.
 */
@Composable
internal fun questionTitle(item: QuestionItem): String = when (item.kind) {
    QuestionKind.AskQuestion -> item.question
    QuestionKind.MistakeLimit -> MistakeLimitPrompt.question(
        intro = stringResource(R.string.mistake_limit_question_intro),
        latest = null,
        ask = stringResource(R.string.mistake_limit_question_ask),
    )
}

/**
 * The whole question of [item]: the agent's question, or for a mistake limit (G2) the desktop's
 * three lines, "Latest:" only when the request carried a detail (`createDesktopMistakeLimitPrompt`).
 * TalkBack reads this for the heading, so the detail is heard as part of the question.
 */
@Composable
internal fun questionText(item: QuestionItem): String = when (item.kind) {
    QuestionKind.AskQuestion -> item.question
    QuestionKind.MistakeLimit -> MistakeLimitPrompt.question(
        intro = stringResource(R.string.mistake_limit_question_intro),
        latest = item.mistakeDetail?.let { stringResource(R.string.mistake_limit_question_latest, it) },
        ask = stringResource(R.string.mistake_limit_question_ask),
    )
}

/**
 * G2: the "Latest:" detail at the top of the scrolling body, kernel text. Hidden from TalkBack,
 * which hears it in the heading ([questionText]) and so never reads it twice.
 */
@Composable
private fun MistakeDetail(item: QuestionItem) {
    val detail = item.mistakeDetail ?: return
    Text(
        stringResource(R.string.mistake_limit_question_latest, detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs)
            .clearAndSetSemantics { },
    )
}

/** An option's label: the agent's text, or for a mistake limit the resource for its answer. */
@Composable
internal fun optionText(item: QuestionItem, option: String): String = when {
    item.kind != QuestionKind.MistakeLimit -> option
    option == MistakeLimitPrompt.CONTINUE_OPTION -> stringResource(R.string.mistake_limit_option_continue)
    option == MistakeLimitPrompt.STOP_OPTION -> stringResource(R.string.mistake_limit_option_stop)
    else -> option
}

@Composable
private fun QuestionBody(state: QuestionUiState, actions: QuestionActions) {
    val single = state.items.size == 1
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        state.items.forEachIndexed { index, item ->
            if (!single) {
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                val title = questionTitle(item)
                val spoken = questionText(item)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.sheetGutter).semantics {
                        heading()
                        if (spoken != title) contentDescription = spoken
                    },
                )
            }
            MistakeDetail(item)
            if (item.multiple) {
                Text(
                    stringResource(R.string.question_multiple_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.sheetGutter),
                )
            }
            if (state.reconnecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s))
            }
            QuestionOptions(item, enabled = !state.reconnecting, onSelect = { actions.onSelect(item.requestId, it) })
            if (!single) QuestionFooter(item, state.reconnecting, actions)
        }
    }
}

/**
 * The options (DS #10): `Surface(onClick)` rows, min 56dp, letter key + label. Hardware keys
 * are scoped to this column like the desktop's `<fieldset onKeyDown>` (C4.7): letters pick,
 * arrows move with wrap, Enter/Space activate; only while an option has focus, never while
 * sending, never with Alt/Ctrl/Meta.
 */
@Composable
private fun QuestionOptions(item: QuestionItem, enabled: Boolean, onSelect: (String) -> Unit) {
    val requesters = remember(item.options) { List(item.options.size) { FocusRequester() } }
    var focused by remember { mutableIntStateOf(-1) }
    val select by rememberUpdatedState(onSelect)
    val interactive = enabled && !item.sending
    val optionsLabel = stringResource(R.string.question_options_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics { contentDescription = optionsLabel }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || focused !in item.options.indices || !interactive) return@onPreviewKeyEvent false
                if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                val count = item.options.size
                when (event.key) {
                    Key.DirectionDown, Key.DirectionRight -> {
                        runCatching { requesters[(focused + 1) % count].requestFocus() }
                        true
                    }
                    Key.DirectionUp, Key.DirectionLeft -> {
                        runCatching { requesters[(focused - 1 + count) % count].requestFocus() }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        select(item.options[focused])
                        true
                    }
                    else -> {
                        val code = event.key.nativeKeyCode
                        if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                            val index = code - KeyEvent.KEYCODE_A
                            val option = item.options.getOrNull(index) ?: return@onPreviewKeyEvent false
                            select(option)
                            runCatching { requesters[index].requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        item.options.forEachIndexed { index, option ->
            OptionRow(
                letter = if (index < 26) optionLabel(index) else null,
                label = optionText(item, option),
                multiple = item.multiple,
                selected = if (item.multiple) option in item.selected else item.pendingAnswer == option,
                sending = !item.multiple && item.pendingAnswer == option,
                enabled = interactive,
                onClick = { select(option) },
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .onFocusChanged { if (it.isFocused) focused = index else if (focused == index) focused = -1 },
            )
        }
    }
}

@Composable
private fun OptionRow(
    letter: String?,
    label: String,
    multiple: Boolean,
    selected: Boolean,
    sending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusVisible: Boolean = false,
) {
    val description = stringResource(R.string.cd_question_option, letter ?: "", label)
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // DS §8.1.7: the options are the keyboard-driven list (C4.7); the selected container alone
            // does not tell the focused row apart, so keyboard focus draws the 2dp primary ring.
            .focusRing(MaterialTheme.shapes.medium, forceVisible = focusVisible, inset = true)
            .semantics {
                contentDescription = description
                role = if (multiple) Role.Checkbox else Role.Button
                optionToggleState(multiple, selected)?.let { toggleableState = it }
            },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (letter != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = Color.Transparent,
                    // DS §8.1.8: a minimum, not a fixed size, so the key grows with the font instead of clipping.
                    modifier = Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).clearAndSetSemantics { },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(letter, style = ClineMono.tiny, modifier = Modifier.padding(horizontal = Spacing.xxs))
                    }
                }
                Spacer(Modifier.width(Spacing.m))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            when {
                sending -> {
                    Spacer(Modifier.width(Spacing.s))
                    InlineSpinner()
                }
                multiple && selected -> {
                    Spacer(Modifier.width(Spacing.s))
                    Icon(ClineIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Checked state a multiple-choice option exposes to accessibility services (C4.9, DS §8.1.3):
 * a Role.Checkbox node is only checkable when it carries a toggleable state, so TalkBack can say
 * "checked" / "not checked". Single-choice options are buttons and carry none.
 */
internal fun optionToggleState(multiple: Boolean, selected: Boolean): ToggleableState? =
    if (multiple) ToggleableState(selected) else null

/** Error line, custom answer field (single choice only) and Submit (DS #10, C4.4). */
@Composable
private fun QuestionFooter(item: QuestionItem, reconnecting: Boolean, actions: QuestionActions) {
    val enabled = !reconnecting && !item.sending
    var text by remember(item.requestId) { mutableStateOf(item.customAnswer) }
    LaunchedEffect(item.customAnswer) { if (item.customAnswer != text) text = item.customAnswer }
    val submit = { if (enabled && item.canSubmit) actions.onSubmit(item.requestId) }
    val customLabel = stringResource(R.string.cd_question_custom)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        item.error?.let { error ->
            Text(
                error.message ?: stringResource(R.string.question_error_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = Spacing.sheetGutter, end = Spacing.sheetGutter, top = Spacing.s)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!item.multiple) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        actions.onCustomChange(item.requestId, it)
                    },
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.question_custom_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = customLabel }
                        .onPreviewKeyEvent { event ->
                            // Every key goes to the field; only Enter without Shift submits (desktop input onKeyDown).
                            val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
            FilledTonalButton(onClick = submit, enabled = enabled && item.canSubmit) {
                if (item.sending) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.question_sending))
                } else {
                    Text(stringResource(R.string.question_submit))
                }
            }
        }
    }
}

/**
 * Turns [AskQuestionController.effects] into snackbars and live-region output (C4.4, C4.9).
 * [onAnswer] reopens the sheet from the "Answer" snackbar action.
 */
@Composable
fun QuestionEffectsHandler(effects: Flow<QuestionEffect>, liveRegion: LiveRegionState?, onAnswer: () -> Unit) {
    val snackbar = LocalSnackbarHost.current
    val cancelled = stringResource(R.string.question_cancelled)
    val alreadyAnswered = stringResource(R.string.request_already_answered)
    val title = stringResource(R.string.question_banner_title)
    val answer = stringResource(R.string.question_banner_action)
    val answerAction by rememberUpdatedState(onAnswer)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                QuestionEffect.Cancelled -> launch { snackbar.showReplacing(cancelled) }
                QuestionEffect.AlreadyAnswered -> launch { snackbar.showReplacing(alreadyAnswered) }
                QuestionEffect.Opened -> liveRegion?.announce(title)
                QuestionEffect.ArrivedWhileAway -> launch {
                    val result = snackbar.showReplacing(title, actionLabel = answer, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) answerAction()
                }
            }
        }
    }
}

// ---- previews ----------------------------------------------------------------------------------

private val PreviewItem = QuestionItem(
    requestId = "capreq_1",
    question = "Which database should the new service use?",
    options = listOf("PostgreSQL", "SQLite", "Keep the current JSON files"),
)

@Composable
private fun QuestionPreview(state: QuestionUiState) {
    ComponentPreview(padding = false) {
        Column(Modifier.padding(vertical = Spacing.l)) {
            Row(Modifier.padding(horizontal = Spacing.sheetGutter).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val single = state.items.singleOrNull()
                if (single != null) QuestionTitle(questionTitle(single), spoken = questionText(single)) else QuestionTitle(stringResource(R.string.question_pane_title))
            }
            QuestionBody(state, QuestionActions.None)
            state.items.singleOrNull()?.let { QuestionFooter(it, state.reconnecting, QuestionActions.None) }
        }
    }
}

@LightDarkPreviews
@Composable
private fun AskQuestionIdlePreview() = QuestionPreview(QuestionUiState("s", listOf(PreviewItem), SheetVisibility.Expanded))

@LightDarkPreviews
@Composable
private fun AskQuestionCustomTypedPreview() = QuestionPreview(
    QuestionUiState("s", listOf(PreviewItem.copy(customAnswer = "MySQL 8")), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun AskQuestionSendingOptionPreview() = QuestionPreview(
    QuestionUiState("s", listOf(PreviewItem.copy(pendingAnswer = "PostgreSQL")), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun AskQuestionSendingCustomPreview() = QuestionPreview(
    QuestionUiState("s", listOf(PreviewItem.copy(customAnswer = "MySQL 8", pendingAnswer = "MySQL 8")), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun AskQuestionErrorPreview() = QuestionPreview(
    QuestionUiState("s", listOf(PreviewItem.copy(error = dev.cline.termux.ui.approval.CardError(null))), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun AskQuestionMultiplePreview() = QuestionPreview(
    QuestionUiState(
        "s",
        listOf(PreviewItem.copy(question = "Which platforms should the build target?", options = listOf("Android", "iOS", "Web"), multiple = true, selected = setOf("Android", "Web"))),
        SheetVisibility.Expanded,
    ),
)

/** G2: the mistake-limit prompt (desktop question, two options, free text allowed). */
@LightDarkPreviews
@Composable
private fun AskQuestionMistakeLimitPreview() = QuestionPreview(
    QuestionUiState(
        "s",
        listOf(
            QuestionItem(
                requestId = "capreq_2",
                question = "",
                options = MistakeLimitPrompt.OPTIONS,
                kind = QuestionKind.MistakeLimit,
                mistakeLimit = MistakeLimitContext(
                    iteration = 6,
                    consecutiveMistakes = 6,
                    maxConsecutiveMistakes = 6,
                    reason = "invalid_tool_call",
                    details = "Tool no_such_tool not found.",
                ),
            ),
        ),
        SheetVisibility.Expanded,
    ),
)

/** G2 with the longest detail (cut at 600 characters): it scrolls with the options, the heading stays short. */
@LightDarkPreviews
@Composable
private fun AskQuestionMistakeLimitLongDetailPreview() = QuestionPreview(
    QuestionUiState(
        "s",
        listOf(
            QuestionItem(
                requestId = "capreq_3",
                question = "",
                options = MistakeLimitPrompt.OPTIONS,
                kind = QuestionKind.MistakeLimit,
                mistakeLimit = MistakeLimitContext(
                    iteration = 9,
                    consecutiveMistakes = 3,
                    maxConsecutiveMistakes = 3,
                    reason = "tool_execution_failed",
                    details = "Error: ENOENT: no such file or directory, open 'src/main/kotlin/dev/cline/termux/Missing.kt' ".repeat(8),
                ),
            ),
        ),
        SheetVisibility.Expanded,
    ),
)

@LightDarkPreviews
@Composable
private fun AskQuestionReconnectingPreview() = QuestionPreview(
    QuestionUiState("s", listOf(PreviewItem), SheetVisibility.Expanded, reconnecting = true),
)

/** DS §8.1.7: keyboard focus on an option draws the inset primary ring, also on the selected row. */
@LightDarkPreviews
@Composable
private fun AskQuestionKeyboardFocusPreview() {
    ComponentPreview(padding = false) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            OptionRow(letter = "A", label = "PostgreSQL", multiple = false, selected = false, sending = false, enabled = true, onClick = {}, focusVisible = true)
            OptionRow(letter = "B", label = "SQLite", multiple = false, selected = false, sending = false, enabled = true, onClick = {})
            HorizontalDivider(Modifier.padding(vertical = Spacing.s))
            OptionRow(letter = "A", label = "Android", multiple = true, selected = true, sending = false, enabled = true, onClick = {}, focusVisible = true)
        }
    }
}

/** DS §8.1.8: at system 2.0 × in-app size 20 the letter keys grow with the text instead of clipping it. */
@Preview(name = "Font scale 2 × size 20, light", fontScale = 2f, widthDp = 412)
@Preview(
    name = "Font scale 2 × size 20, dark", fontScale = 2f, widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun AskQuestionLargeFontPreview() {
    ClineTheme(appearance = AppearancePrefs(dynamicColor = false, fontSize = 20)) {
        Surface {
            Column(Modifier.padding(vertical = Spacing.l)) {
                QuestionBody(QuestionUiState("s", listOf(PreviewItem), SheetVisibility.Expanded), QuestionActions.None)
                QuestionFooter(PreviewItem, reconnecting = false, actions = QuestionActions.None)
            }
        }
    }
}
