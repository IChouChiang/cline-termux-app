package dev.cline.termux.ui.composer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ellipsizeGraphemes
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.session.DotTone
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.StatusDot
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.ComposerKernelButton
import dev.cline.termux.ui.kernel.ComposerKernelRow
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.Spacing
import kotlin.math.max

/** The settings row's callbacks (C1.7). */
class SettingsRowActions(
    val onAttach: () -> Unit,
    val onModeChange: (PromptMode) -> Unit,
    val onModelChip: () -> Unit,
    val onReasoningChip: () -> Unit,
    val onRing: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    /** Long-press Send while a run is active: steer (A11 row 5). */
    val onSteer: () -> Unit = {},
)

/**
 * The settings row's sheet triggers (attach, ModelChip, usage ring): each sheet they open
 * hands focus back to its requester when it closes (DS §8.1.7). The host that opens the model
 * picker passes [model] to it; the composer passes [attach] and [ring] to its own sheets.
 */
@Stable
class ComposerTriggerFocus {
    val attach = FocusRequester()
    val model = FocusRequester()
    val ring = FocusRequester()
}

/** The ModelChip's three forms (C1.4 rules 3 and 4). */
internal enum class ModelChipForm {
    /** "<provider> / <model>", at most 240dp, the model part shrinking first (rule 4). */
    Full,

    /** The model name cut at 16 graphemes with "…" (rule 3, DS #7). */
    Short,

    /** The `memory` icon alone; the TalkBack label and the tooltip keep "Provider / Model". */
    Icon,
}

/**
 * The narrowest lane share the [ModelChipForm.Full] chip takes: room for the 96dp provider
 * cap, the separator and a readable part of the model name. Below it the lane falls back to
 * the compact forms.
 */
internal val ModelChipFullMinWidth = 200.dp

/**
 * C1.4's choice of the ModelChip form from the **measured lane**, on every window class (an
 * expanded window's list-detail pane can be narrower than a phone, so the window class alone
 * cannot decide). Medium and expanded windows show [ModelChipForm.Full] when the lane leaves
 * it at least [fullMinPx] ([fullBudgetPx] = lane width minus everything else in the labelled
 * lane); otherwise, and always on compact windows, the compact rule applies: the 16-grapheme
 * [ModelChipForm.Short] label when that labelled lane fits ([shortFits]), else
 * [ModelChipForm.Icon]. While [busy] the chip keeps its [previous] form (rule 3: changing form
 * on every send would reflow the row), except that a compact window never keeps
 * [ModelChipForm.Full].
 */
internal fun chooseModelChipForm(
    compact: Boolean,
    busy: Boolean,
    previous: ModelChipForm?,
    fullBudgetPx: Int,
    fullMinPx: Int,
    shortFits: () -> Boolean,
): ModelChipForm {
    if (busy && previous != null && !(compact && previous == ModelChipForm.Full)) return previous
    return when {
        !compact && fullBudgetPx >= fullMinPx -> ModelChipForm.Full
        shortFits() -> ModelChipForm.Short
        else -> ModelChipForm.Icon
    }
}

/** "None", "Low", … or "Reasoning" (desktop `effortLabel`). */
@Composable
fun ReasoningChipLabel.text(): String = when (this) {
    ReasoningChipLabel.Generic -> stringResource(R.string.composer_effort_unknown)
    is ReasoningChipLabel.Level -> stringResource(
        when (effort) {
            ReasoningEffort.None -> R.string.composer_effort_none
            ReasoningEffort.Low -> R.string.composer_effort_low
            ReasoningEffort.Medium -> R.string.composer_effort_medium
            ReasoningEffort.High -> R.string.composer_effort_high
            ReasoningEffort.XHigh -> R.string.composer_effort_extra
        },
    )
}

/**
 * C1.3 SettingsRow: the lane (attach · Plan/Act · ModelChip · ReasoningChip) and the trailing
 * group (ring · Stop · Send/Starting) measured per C1.4: the trailing group never scrolls or
 * truncates; the ModelChip's form follows the measured lane width on every window class
 * ([chooseModelChipForm]: "Provider / Model" where it fits, else the 16-grapheme label, else
 * the icon, and kept while busy), and the ReasoningChip is labelled only beside the full
 * ModelChip. Only a lane that overflows even then (height-compact windows, the largest font
 * scales) scrolls, with a 16dp end fade.
 */
@Composable
fun ComposerSettingsRow(
    state: ComposerUiState,
    compact: Boolean,
    ringOpen: Boolean,
    actions: SettingsRowActions,
    modifier: Modifier = Modifier,
    reasoningMenu: @Composable () -> Unit = {},
    triggers: ComposerTriggerFocus? = null,
) {
    val frozenForm = remember { FormMemory() }
    val scroll = rememberScrollState()
    SubcomposeLayout(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.xs)) { constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val trailing = subcompose(RowSlot.Trailing) { TrailingGroup(state, ringOpen, actions, triggers) }.map { it.measure(loose) }
        val trailingWidth = trailing.sumOf { it.width }
        val laneMax = (constraints.maxWidth - trailingWidth).coerceAtLeast(0)
        val unbounded = Constraints(maxHeight = constraints.maxHeight)
        // Medium and expanded: what the labelled lane needs besides the ModelChip.
        val rest = if (compact) {
            0
        } else {
            subcompose(RowSlot.ProbeRest) {
                Lane(state, ModelChipForm.Full, Dp.Unspecified, actions, reasoningMenu = {}, triggers = null, omitModel = true)
            }.sumOf { it.measure(unbounded).width }
        }
        val fullBudget = laneMax - rest
        val fullMin = ModelChipFullMinWidth.roundToPx()
        val form = chooseModelChipForm(compact, state.isBusy, frozenForm.form, fullBudget, fullMin) {
            subcompose(RowSlot.ProbeShort) {
                Lane(state, ModelChipForm.Short, Dp.Unspecified, actions, reasoningMenu = {}, triggers = null)
            }.sumOf { it.measure(unbounded).width } <= laneMax
        }.also { frozenForm.form = it }
        // The full chip takes the lane's remainder, so its model part ellipsizes instead of overflowing.
        val chipMax = if (form == ModelChipForm.Full) max(fullBudget, fullMin).toDp() else Dp.Unspecified
        val lane = subcompose(RowSlot.Lane) {
            FadingScrollRow(scroll) { Lane(state, form, chipMax, actions, reasoningMenu, triggers) }
        }.map { it.measure(Constraints(maxWidth = laneMax, maxHeight = constraints.maxHeight)) }
        // A lane taller than the 48dp row (font scale 2 grows the Plan/Act pill past it, C1.15 item 16)
        // keeps a 4dp margin above and below: the row is the composer's last child, and touching the
        // surface's rounded bottom edge clipped the pill's outline.
        val laneHeight = lane.maxOfOrNull { it.height } ?: 0
        val laneNeeds = if (laneHeight > constraints.minHeight) laneHeight + 2 * Spacing.xs.roundToPx() else laneHeight
        val height = max(max(laneNeeds, trailing.maxOfOrNull { it.height } ?: 0), constraints.minHeight)
        layout(constraints.maxWidth, height) {
            lane.forEach { it.placeRelative(0, (height - it.height) / 2) }
            var x = constraints.maxWidth - trailingWidth
            trailing.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}

/**
 * The composer's standard icon-button colours: `onSurfaceVariant` content that M3 dims to its
 * disabled content colour (`onSurface` @ 38 %) when the control is disabled, the desktop's
 * `disabled:opacity-50` (DS §9). Icons inside must not set their own `tint`.
 */
@Composable
internal fun composerIconButtonColors() =
    IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)

private enum class RowSlot { Trailing, ProbeRest, ProbeShort, Lane }

/** Attaches [focus] when the control is a sheet trigger; the probe and previews pass null. */
private fun Modifier.triggerFocus(focus: FocusRequester?): Modifier = if (focus != null) focusRequester(focus) else this

/** The ModelChip's last chosen form, kept while busy (C1.4 step 3). */
private class FormMemory {
    var form: ModelChipForm? = null
}

/** A horizontally scrolling row with a 16dp fade toward its end edge while more content follows. */
@Composable
private fun FadingScrollRow(scroll: ScrollState, content: @Composable () -> Unit) {
    val fade = MaterialTheme.colorScheme.surfaceContainerHigh
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            // Drawn in viewport coordinates (before the scroll), so the fade stays on the edge.
            .drawWithContent {
                drawContent()
                if (scroll.canScrollForward) {
                    val w = 16.dp.toPx()
                    val x = if (rtl) 0f else size.width - w
                    val colors = if (rtl) listOf(fade, Color.Transparent) else listOf(Color.Transparent, fade)
                    drawRect(Brush.horizontalGradient(colors, startX = x, endX = x + w), topLeft = Offset(x, 0f), size = Size(w, size.height))
                }
            }
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Lane(
    state: ComposerUiState,
    form: ModelChipForm,
    /** The full chip's share of the lane; unspecified for the other forms and in the probes. */
    chipMaxWidth: Dp,
    actions: SettingsRowActions,
    reasoningMenu: @Composable () -> Unit,
    // Null in the measuring probes, so each requester is attached to the placed control only.
    triggers: ComposerTriggerFocus?,
    /** The probe that measures everything but the ModelChip. */
    omitModel: Boolean = false,
) {
    val enabled = !state.loading
    Row(verticalAlignment = Alignment.CenterVertically) {
        AttachButton(state, enabled, actions.onAttach, triggers?.attach)
        PlanActToggle(state.mode, enabled, actions.onModeChange)
        Spacer(Modifier.width(Spacing.xs))
        if (!omitModel) ModelChip(state, form, chipMaxWidth, actions.onModelChip, triggers?.model)
        Box {
            // Labelled only beside the full ModelChip (C1.4 rule 4); icon-only wherever the compact rule applies (rule 3).
            ReasoningChip(state, iconOnly = form != ModelChipForm.Full, onClick = actions.onReasoningChip)
            reasoningMenu()
        }
    }
}

@Composable
private fun AttachButton(state: ComposerUiState, enabled: Boolean, onClick: () -> Unit, focus: FocusRequester?) {
    val noImages = state.model.supportsImages == false
    val cd = stringResource(R.string.composer_cd_attach)
    PlainTooltipBox(text = stringResource(if (noImages) R.string.composer_tt_attach_no_images else R.string.composer_tt_attach)) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !state.kernel.readOnly,
            colors = composerIconButtonColors(),
            modifier = Modifier.triggerFocus(focus).focusRing(CircleShape),
        ) {
            Icon(ClineIcons.AttachFile, contentDescription = cd, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PlanActToggle(mode: PromptMode, enabled: Boolean, onChange: (PromptMode) -> Unit) {
    val group = stringResource(R.string.composer_cd_mode_group)
    SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = group }) {
        listOf(PromptMode.Plan to R.string.composer_mode_plan, PromptMode.Act to R.string.composer_mode_act).forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                enabled = enabled,
                icon = {},
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ModelChip(state: ComposerUiState, form: ModelChipForm, maxWidth: Dp, onClick: () -> Unit, focus: FocusRequester?) {
    val model = state.model
    val provider = model.providerName ?: model.providerId.orEmpty()
    val name = model.modelName ?: model.modelId.orEmpty()
    val cd = if (model.resolved) stringResource(R.string.composer_cd_model_chip, provider, name) else stringResource(R.string.composer_cd_model_chip_empty)
    val busyText = stringResource(R.string.composer_tt_model_busy)
    val tooltip = when {
        !model.enabled -> busyText
        model.resolved -> stringResource(R.string.composer_tt_model_chip, provider, name)
        else -> stringResource(R.string.composer_model_chip_placeholder)
    }
    val enabled = model.enabled && !state.loading
    val semantics = Modifier.semantics {
        contentDescription = cd
        if (!model.enabled) stateDescription = busyText
    }
    PlainTooltipBox(text = tooltip) {
        if (form == ModelChipForm.Icon) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.triggerFocus(focus).focusRing(CircleShape).then(semantics),
                colors = composerIconButtonColors(),
            ) {
                Icon(ClineIcons.Memory, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        } else {
            AssistChip(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .padding(horizontal = Spacing.xs)
                    .triggerFocus(focus)
                    .focusRing(MaterialTheme.shapes.small)
                    .then(semantics),
                leadingIcon = { Icon(ClineIcons.Memory, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                label = {
                    when {
                        !model.resolved -> Text(stringResource(R.string.composer_model_chip_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        form == ModelChipForm.Short -> Text(ellipsizeGraphemes(name), maxLines = 1)
                        else -> Row(Modifier.widthIn(max = 240.dp)) {
                            // C1.4 rule 4: the provider takes at most 96dp and the model shrinks first. The
                            // separator is its own Text so an ellipsized provider still reads "Provider… / model".
                            Text(provider, modifier = Modifier.widthIn(max = 96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.composer_model_chip_separator), maxLines = 1)
                            Text(name, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ReasoningChip(state: ComposerUiState, iconOnly: Boolean, onClick: () -> Unit) {
    val reasoning = state.reasoning
    val value = reasoning.label.text()
    val cd = stringResource(R.string.composer_cd_thinking_level_value, value)
    val tooltip = if (reasoning.unsupportedReason) stringResource(R.string.composer_tt_reasoning_unsupported) else stringResource(R.string.composer_cd_thinking_level)
    val enabled = reasoning.enabled && !state.loading
    val semantics = Modifier.semantics {
        contentDescription = cd
        if (reasoning.unsupportedReason) stateDescription = tooltip
    }
    PlainTooltipBox(text = tooltip) {
        if (iconOnly) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.focusRing(CircleShape).then(semantics), colors = composerIconButtonColors()) {
                Icon(ClineIcons.Neurology, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        } else {
            AssistChip(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .padding(horizontal = Spacing.xs)
                    .focusRing(MaterialTheme.shapes.small)
                    .then(semantics),
                leadingIcon = { Icon(ClineIcons.Neurology, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text(value, maxLines = 1) },
            )
        }
    }
}

/** ring · Stop · Send or the Starting slot; Send ⇄ Stop morph per DS §6. */
@Composable
private fun TrailingGroup(state: ComposerUiState, ringOpen: Boolean, actions: SettingsRowActions, triggers: ComposerTriggerFocus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        state.usage?.let { UsageRing(it, ringOpen, actions.onRing, focus = triggers?.ring) }
        SendStopGroup(state, actions.onSend, actions.onStop, actions.onSteer)
    }
}

/**
 * Stop · Send or the Starting slot, without the ring: the settings row's trailing group, and
 * the field row's end while the IME hides the settings row on a height-compact window (D18),
 * so a draft can still be sent or a run stopped by touch.
 */
@Composable
internal fun SendStopGroup(state: ComposerUiState, onSend: () -> Unit, onStop: () -> Unit, onSteer: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = state.canAbort,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), 0.8f),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), 0.8f),
        ) { StopButton(state.stopping, onStop) }
        val slot = when {
            state.showStartingSlot -> TrailingSlot.Starting
            state.showSend -> TrailingSlot.Send
            else -> TrailingSlot.None
        }
        AnimatedContent(
            targetState = slot,
            transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(150), 0.8f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), 0.8f)) },
            label = "send",
        ) { target ->
            when (target) {
                TrailingSlot.Send -> SendButton(state.canSend, busy = state.isBusy, onClick = onSend, onLongClick = onSteer)
                TrailingSlot.Starting -> StartingSlot()
                TrailingSlot.None -> Unit
            }
        }
    }
}

private enum class TrailingSlot { Send, Starting, None }

/**
 * Send. While a run is active and the field has text ([busy] and [enabled]) a tap queues and a
 * long-press steers ([onLongClick], "Send now (steer)", A11 row 5) with one `LongPress` haptic,
 * the one `combinedClickable` performs (trial row 12: a second, explicit one made it buzz
 * twice); the tooltip wraps the button only when idle, so the two long-presses never compete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cd = stringResource(R.string.composer_cd_send)
    if (busy && enabled) {
        val steerLabel = stringResource(R.string.session_config_steer_label)
        // The whole 48dp slot is the target, as FilledIconButton's minimum interactive size gives the idle button.
        Box(
            modifier = Modifier
                .size(48.dp)
                .focusRing(CircleShape)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = steerLabel,
                    onLongClick = onLongClick,
                    hapticFeedbackEnabled = true,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(ClineIcons.ArrowUpward, contentDescription = cd, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
        return
    }
    PlainTooltipBox(text = stringResource(R.string.composer_tt_send)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.focusRing(CircleShape).size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Icon(ClineIcons.ArrowUpward, contentDescription = cd, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun StopButton(stopping: Boolean, onClick: () -> Unit) {
    val cd = stringResource(R.string.composer_cd_stop)
    PlainTooltipBox(text = stringResource(R.string.composer_tt_stop)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onClick,
                enabled = !stopping,
                modifier = Modifier.focusRing(CircleShape).size(40.dp).semantics { contentDescription = cd },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (stopping) {
                    InlineSpinner()
                } else {
                    Icon(ClineIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** C1.3 Starting slot: a 20dp spinner centred in 48dp. */
@Composable
private fun StartingSlot() {
    val cd = stringResource(R.string.composer_cd_starting)
    Box(Modifier.size(48.dp).semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The kernel's replacement for the settings row (F.3, C1.6): busy = a 16dp spinner + chip
 * line 1; down = the `outline` dot + line 1 + the end-aligned F.2 primary (none when
 * blocked), whose own call shows "Starting kernel..." while it runs.
 */
@Composable
fun ComposerKernelRowContent(row: ComposerKernelRow, button: ComposerKernelButton?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.s, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        when (row) {
            ComposerKernelRow.Normal -> Unit
            is ComposerKernelRow.Busy -> {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(row.line1.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            is ComposerKernelRow.Down -> {
                StatusDot(DotTone.Outline)
                Text(
                    row.line1.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (button != null) {
                    TextButton(onClick = onAction, enabled = button.enabled, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                        if (!button.enabled) {
                            InlineSpinner()
                            Spacer(Modifier.width(Spacing.s))
                        }
                        Text(button.label.resolve())
                    }
                }
            }
        }
    }
}
