package dev.cline.termux.ui.approval

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.theme.ClineMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.runtime.CompositionLocalProvider
import androidx.window.core.layout.WindowSizeClass
import dev.cline.termux.R
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.core.kernel.hub.SwitchToActModeTool
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.showReplacingUntil
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberIs24HourClock

/** Height cap of the detail box before it scrolls (desktop `max-h-44`). */
private val DetailMaxHeight = 176.dp

/** Geist Mono advance width (600/1000 em): a marker of N characters indents continuations by N × this. */
private const val MONO_CHAR_EM = 0.6f

/**
 * The tool approval sheet (composer-approvals.md C3, DS #9): a `ModalBottomSheet` over the
 * chat with one `OutlinedCard` per request and "Stop agent" at the end. Dismissing it (swipe,
 * scrim, back, Esc) calls [onCollapse], which shows the banner; it never rejects. With one
 * request it opens fully expanded, with more it opens half-expanded. Host it in the chat
 * screen and disable the drawer edge-swipe while [ApprovalUiState.sheet] is open (C3.9).
 * [returnFocus] gets focus once the sheet has closed: [PendingInputFocus.approvalReturn].
 * [onApproveAndAutoApprove] adds the Approve split button's menu (auto-approve-control.md §3,
 * decided A1); null (already Auto-approve all, or no Termux commands) keeps the plain Approve.
 */
@Composable
fun ApprovalSheet(
    state: ApprovalUiState,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onToggleRaw: (String) -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    returnFocus: FocusRequester? = null,
    onApproveAndAutoApprove: ((String) -> Unit)? = null,
) {
    PendingInputSheet(
        visibility = state.sheet,
        hasContent = state.cards.isNotEmpty(),
        onCollapse = onCollapse,
        titleContent = { ApprovalHeader() },
        modifier = modifier,
        paneTitle = stringResource(R.string.approval_title),
        returnFocus = returnFocus,
    ) {
        ApprovalSheetBody(state, onApprove, onReject, onToggleRaw, onStop, onApproveAndAutoApprove)
    }
}

/** Shield icon in `warning.color` + "Tool approval required" (`titleMedium`). */
@Composable
private fun RowScope.ApprovalHeader() {
    Icon(ClineIcons.GppMaybe, contentDescription = null, tint = ClineTheme.colors.warning.color, modifier = Modifier.size(24.dp))
    Spacer(Modifier.width(Spacing.s))
    Text(
        stringResource(R.string.approval_title),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

/** Everything under the title row; separate so previews render it without the modal window. */
@Composable
private fun ApprovalSheetBody(
    state: ApprovalUiState,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onToggleRaw: (String) -> Unit,
    onStop: () -> Unit,
    onApproveAndAutoApprove: ((String) -> Unit)? = null,
) {
    val reducedMotion = ClineTheme.reducedMotion
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(if (state.kernelDown) R.string.hub_not_connected else R.string.approval_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sheetGutter),
        )
        if (state.readOnly) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.sheetGutter, end = Spacing.sheetGutter, top = Spacing.m, bottom = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(state.cards, key = { it.approvalId }) { card ->
                ApprovalCardView(
                    card = card,
                    enabled = !state.readOnly,
                    onApprove = { onApprove(card.approvalId) },
                    onReject = { onReject(card.approvalId) },
                    onToggleRaw = { onToggleRaw(card.approvalId) },
                    modifier = if (reducedMotion) Modifier else Modifier.animateItem(),
                    onApproveAndAutoApprove = onApproveAndAutoApprove?.let { approveAndAuto -> { approveAndAuto(card.approvalId) } },
                )
            }
            item(key = "stop") {
                // Animated too: otherwise it jumps under a card that is still fading out (DS §6).
                Row(Modifier.fillMaxWidth().then(if (reducedMotion) Modifier else Modifier.animateItem()), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge),
                        enabled = !state.readOnly && !state.stopping,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.approval_stop)) }
                }
            }
        }
    }
}

/** One request (DS #9): title + time, "Request <id> · Iteration <n>", detail, error, Reject then Approve end-aligned. */
@Composable
fun ApprovalCardView(
    card: ApprovalCard,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onToggleRaw: () -> Unit,
    modifier: Modifier = Modifier,
    onApproveAndAutoApprove: (() -> Unit)? = null,
) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val is24Hour = rememberIs24HourClock()
    val time = formatApprovalTimestamp(card.requestedAtMs, is24Hour = is24Hour) ?: stringResource(R.string.approval_pending_now)
    val meta = card.iteration?.let { stringResource(R.string.approval_request_meta, card.approvalId, it) }
        ?: stringResource(R.string.approval_request_meta_no_iteration, card.approvalId)
    val strings = previewStrings()
    val firstLine = when (val p = card.preview) {
        is ApprovalPreview.Lines -> p.lines.firstOrNull()?.plainText(strings.createFile, strings.insertAt, strings.more)
        ApprovalPreview.Raw -> null
    } ?: card.rawInput.lineSequence().firstOrNull().orEmpty()
    // The app's own client tool reads as an action (A11 row 4); every other tool keeps its kernel name.
    val toolTitle = if (card.toolName == SwitchToActModeTool.NAME) stringResource(R.string.act_switch_tool_label) else card.toolName
    val cardDescription = stringResource(R.string.cd_approval_card, toolTitle, firstLine)
    val busyText = when (card.action) {
        ApprovalAction.Approving -> stringResource(R.string.approval_approving)
        ApprovalAction.Rejecting -> stringResource(R.string.approval_rejecting)
        null -> null
    }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { if (busyText != null) stateDescription = busyText },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, ClineTheme.colors.warning.color.copy(alpha = 0.4f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(if (wide) Spacing.l else Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    toolTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            heading()
                            contentDescription = cardDescription
                        },
                )
                Spacer(Modifier.width(Spacing.s))
                Icon(ClineIcons.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.xs))
                Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Spacer(Modifier.size(Spacing.s))
            ApprovalDetail(card, strings)
            if (card.hasPreview) {
                TextButton(onClick = onToggleRaw, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(if (card.showRaw) R.string.approval_hide_raw else R.string.approval_show_raw))
                }
            }
            card.error?.let { error ->
                Text(
                    error.message ?: stringResource(R.string.approval_error_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val buttonsEnabled = enabled && !card.busy
                OutlinedButton(onClick = onReject, enabled = buttonsEnabled, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    ButtonLabel(stringResource(R.string.approval_reject), busy = card.action == ApprovalAction.Rejecting, busyText = busyText)
                }
                if (onApproveAndAutoApprove != null && !card.busy) {
                    ApproveSplitButton(enabled = buttonsEnabled, onApprove = onApprove, onApproveAndAutoApprove = onApproveAndAutoApprove)
                } else {
                    Button(onClick = onApprove, enabled = buttonsEnabled, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                        ButtonLabel(stringResource(R.string.approval_approve), busy = card.action == ApprovalAction.Approving, busyText = busyText)
                    }
                }
            }
        }
    }
}

/**
 * §3 "Approve ▾": the M3 split button. The leading half approves; the 48 dp trailing half
 * opens one item, "Approve and auto-approve from now on" (approve first, then the write).
 * 2 dp gap, `extraSmall` inner corners, full outer corners; the chevron turns while open.
 * While the card is busy the plain Approve with its spinner shows instead.
 */
@Composable
private fun ApproveSplitButton(enabled: Boolean, onApprove: () -> Unit, onApproveAndAutoApprove: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ReportBackLevel(BackLevel.Menu, open)
    val inner = MaterialTheme.shapes.extraSmall.topStart
    val outer = CornerSize(percent = 50)
    val reduced = ClineTheme.reducedMotion
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = if (reduced) snap() else tween(ClineMotion.Short4, easing = ClineMotion.Standard),
        label = "approveChevron",
    )
    val moreCd = stringResource(R.string.approval_approve_more_cd)
    Row(horizontalArrangement = Arrangement.spacedBy(SplitGap), verticalAlignment = Alignment.CenterVertically) {
        val leading = RoundedCornerShape(topStart = outer, topEnd = inner, bottomEnd = inner, bottomStart = outer)
        Button(onClick = onApprove, enabled = enabled, shape = leading, modifier = Modifier.focusRing(leading)) {
            Text(stringResource(R.string.approval_approve))
        }
        Box {
            val trailing = RoundedCornerShape(topStart = inner, topEnd = outer, bottomEnd = outer, bottomStart = inner)
            Button(
                onClick = { open = true },
                enabled = enabled,
                shape = trailing,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .width(SplitTrailingWidth)
                    .focusRing(trailing)
                    .semantics { contentDescription = moreCd },
            ) {
                Icon(ClineIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = chevron })
            }
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.approval_approve_and_auto), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.approval_approve_and_auto_supporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(ClineIcons.Approval.Auto, contentDescription = null) },
                    onClick = {
                        open = false
                        onApproveAndAutoApprove()
                    },
                )
            }
        }
    }
}

private val SplitGap = 2.dp
private val SplitTrailingWidth = 48.dp

@Composable
private fun ButtonLabel(label: String, busy: Boolean, busyText: String?) {
    if (busy && busyText != null) {
        InlineSpinner()
        Spacer(Modifier.width(Spacing.s))
        Text(busyText)
    } else {
        Text(label)
    }
}

/** The *(Android)* strings a preview line may need. */
private class PreviewStrings(val createFile: String, val insertAt: (Int) -> String, val more: String)

@Composable
private fun previewStrings(): PreviewStrings {
    val resources = LocalResources.current
    val createFile = stringResource(R.string.approval_create_file)
    val more = stringResource(R.string.approval_preview_more)
    return remember(resources, createFile, more) {
        PreviewStrings(createFile, { n -> resources.getString(R.string.approval_insert_at, n) }, more)
    }
}

/**
 * The detail box (C3.5): `surfaceContainer`, `shapes.small`, 12dp padding, max 176dp then
 * scrolls (nested-scroll aware), selectable, LTR, soft-wrapped with no horizontal scroll.
 * Each preview line is its own paragraph; continuations indent by the marker width.
 */
@Composable
private fun ApprovalDetail(card: ApprovalCard, strings: PreviewStrings) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier
                    .heightIn(max = DetailMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.m),
            ) {
                SelectionContainer {
                    Column {
                        val preview = card.preview
                        if (preview is ApprovalPreview.Lines && !card.showRaw) {
                            preview.lines.forEach { line -> PreviewLineText(line, strings) }
                        } else {
                            Text(
                                card.rawInput,
                                style = detailStyle(ClineMono.body),
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = card.rawInput },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun detailStyle(base: TextStyle, markerChars: Int = 0): TextStyle = base.copy(
    lineBreak = LineBreak.Simple,
    textDirection = TextDirection.Ltr,
    textIndent = if (markerChars > 0) TextIndent(restLine = (markerChars * MONO_CHAR_EM).em) else TextIndent.None,
)

@Composable
private fun PreviewLineText(line: PreviewLine, strings: PreviewStrings) {
    val colors = ClineTheme.colors
    val scheme = MaterialTheme.colorScheme
    val text = line.plainText(strings.createFile, strings.insertAt, strings.more)
    val (style, color, background) = when (line) {
        is PreviewLine.Command -> LineLook(detailStyle(ClineMono.body, markerChars = 2), scheme.onSurface)
        is PreviewLine.Path -> LineLook(detailStyle(ClineMono.small), scheme.onSurface)
        is PreviewLine.FileRead -> LineLook(detailStyle(ClineMono.body), scheme.onSurface)
        is PreviewLine.Added -> LineLook(detailStyle(ClineMono.body, if (line.verbatim) 1 else 2), colors.success.color, colors.diffAddedLine)
        is PreviewLine.Removed -> LineLook(detailStyle(ClineMono.body, if (line.verbatim) 1 else 2), scheme.error, colors.diffRemovedLine)
        is PreviewLine.Header -> LineLook(detailStyle(ClineMono.body), scheme.onSurfaceVariant)
        is PreviewLine.Context -> LineLook(detailStyle(ClineMono.body), scheme.onSurface)
        is PreviewLine.Url -> LineLook(detailStyle(ClineMono.body), scheme.primary)
        is PreviewLine.Prompt -> LineLook(detailStyle(MaterialTheme.typography.bodySmall), scheme.onSurfaceVariant)
        is PreviewLine.Query -> LineLook(detailStyle(ClineMono.body), scheme.onSurface)
        PreviewLine.CreateFile, is PreviewLine.InsertAt, PreviewLine.More -> LineLook(MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
    }
    Text(
        text = text,
        style = style,
        color = color,
        softWrap = true,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (background != Color.Unspecified) Modifier.background(background) else Modifier)
            .semantics { contentDescription = text },
    )
}

private data class LineLook(val style: TextStyle, val color: Color, val background: Color = Color.Unspecified)

/**
 * Turns [ApprovalController.effects] into the snackbar, haptic and live-region output (C3.4,
 * C3.8, C3.10). [liveRegion] is the conversation's polite region; [onReview] reopens the
 * sheet from the "Review" snackbar action (the host navigates back to the chat first when
 * another screen is on top). A failed Stop offers "Restart kernel" through the shell (A4) until
 * [awaitStopFailureEnd] returns ([ApprovalController.awaitStopFailureEnd], trial row 11).
 */
@Composable
fun ApprovalEffectsHandler(
    effects: Flow<ApprovalEffect>,
    liveRegion: LiveRegionState?,
    onReview: () -> Unit,
    awaitStopFailureEnd: suspend () -> Unit,
) {
    val snackbar = LocalSnackbarHost.current
    val haptics = LocalHapticFeedback.current
    val alreadyAnswered = stringResource(R.string.request_already_answered)
    val title = stringResource(R.string.approval_banner_title)
    val review = stringResource(R.string.approval_banner_action)
    val stopFailed = stringResource(R.string.approval_stop_failed)
    val restartKernel = stringResource(R.string.kernel_restart)
    val announce = stringResource(R.string.approval_title)
    val resources = LocalResources.current
    val reviewAction by rememberUpdatedState(onReview)
    val stopFailureEnd by rememberUpdatedState(awaitStopFailureEnd)
    val kernel by rememberUpdatedState(LocalShellKernelUi.current)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                ApprovalEffect.AlreadyAnswered -> launch { snackbar.showReplacing(alreadyAnswered) }
                // §3: the request was approved; only the switch to Auto-approve all did not take.
                is ApprovalEffect.AutoApproveFailed -> launch { snackbar.showReplacing(approvalWriteFailureText(resources, turningOn = true, failure = effect.failure)) }
                is ApprovalEffect.Decided -> haptics.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackType.Confirm else HapticFeedbackType.LongPress,
                )
                ApprovalEffect.Opened -> liveRegion?.announce(announce)
                ApprovalEffect.ArrivedWhileAway -> launch {
                    val result = snackbar.showReplacing(title, actionLabel = review, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) reviewAction()
                }
                // A4: the run outlived the Hub's command timeout; the way out is the kernel restart.
                // Trial row 11: the message goes once the run is over.
                ApprovalEffect.StopFailed -> launch {
                    val result = snackbar.showReplacingUntil(stopFailed, actionLabel = restartKernel) { stopFailureEnd() }
                    if (result == SnackbarResult.ActionPerformed) kernel?.onAction?.invoke(ApprovalEffect.StopFailed.action)
                }
            }
        }
    }
}

// ---- previews ----------------------------------------------------------------------------------

private fun previewCard(
    id: String,
    tool: String,
    input: String,
    action: ApprovalAction? = null,
    error: CardError? = null,
    showRaw: Boolean = false,
    iteration: Int? = 1,
): ApprovalCard {
    val json: JsonElement = Json.parseToJsonElement(input)
    return ApprovalCard(
        approvalId = id,
        toolCallId = "call_$id",
        toolName = tool,
        iteration = iteration,
        requestedAtMs = 1_791_064_690_209,
        preview = ApprovalPreviews.build(tool, json),
        rawInput = formatApprovalInput(json),
        showRaw = showRaw,
        action = action,
        error = error,
    )
}

private val PreviewCommand = previewCard("approval_1791064690209_v5gc4", "run_commands", """{"commands":["bun test --filter parser","git status"]}""")
private val PreviewEditor = previewCard(
    "approval_1791064690412_k2x8a", "editor",
    """{"path":"src/parser.ts","old_text":"const x = parse(a)","new_text":"const x = parse(a, { strict })"}""", iteration = 3,
)
private val PreviewLongCommand = previewCard(
    "approval_1791064690500_l0ng1", "run_commands",
    """{"commands":["curl -sS -H 'Accept: application/json' https://api.example.com/v1/projects/cline/releases/latest/download/assets?include=checksums,signatures&format=json | jq '.assets[] | {name, size, sha256}'"]}""",
)

@Composable
private fun SheetPreview(state: ApprovalUiState) {
    ComponentPreview(padding = false) {
        Column(Modifier.padding(vertical = Spacing.l)) {
            Row(Modifier.padding(horizontal = Spacing.sheetGutter).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { ApprovalHeader() }
            ApprovalSheetBody(state, {}, {}, {}, {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun ApprovalSheetOneRequestPreview() = SheetPreview(ApprovalUiState("s", listOf(PreviewCommand), SheetVisibility.Expanded))

@LightDarkPreviews
@Composable
private fun ApprovalSheetEditorPreview() = SheetPreview(ApprovalUiState("s", listOf(PreviewEditor), SheetVisibility.Expanded))

@LightDarkPreviews
@Composable
private fun ApprovalSheetTwoRequestsPreview() = SheetPreview(
    ApprovalUiState(
        "s",
        listOf(
            previewCard("approval_a", "read_files", """{"files":[{"path":"/w/app.tsx","start_line":10,"end_line":80},{"path":"/w/README.md"}]}"""),
            previewCard("approval_b", "fetch_web_content", """{"requests":[{"url":"https://docs.cline.bot","prompt":"Summarize the install steps"}]}"""),
        ),
        SheetVisibility.PartiallyExpanded,
    ),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetApprovingPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand.copy(action = ApprovalAction.Approving)), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetRejectingPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand.copy(action = ApprovalAction.Rejecting)), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetErrorPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand.copy(error = CardError(null))), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetRawPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand.copy(showRaw = true)), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetMcpRawOnlyPreview() = SheetPreview(
    ApprovalUiState("s", listOf(previewCard("approval_m", "mcp_github_create_issue", """{"title":"Crash on start","labels":["bug"]}""", iteration = null)), SheetVisibility.Expanded),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetLongCommandPreview() = SheetPreview(ApprovalUiState("s", listOf(PreviewLongCommand), SheetVisibility.Expanded))

@LightDarkPreviews
@Composable
private fun ApprovalSheetReconnectingPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand), SheetVisibility.Expanded, reconnecting = true),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetKernelDownPreview() = SheetPreview(
    ApprovalUiState("s", listOf(PreviewCommand), SheetVisibility.Expanded, kernelDown = true),
)

@LightDarkPreviews
@Composable
private fun ApprovalSheetPatchPreview() = SheetPreview(
    ApprovalUiState(
        "s",
        listOf(previewCard("approval_p", "apply_patch", """{"input":"*** Begin Patch\n*** Update File: src/a.ts\n@@\n-const a = 1\n+const a = 2\n*** End Patch"}""")),
        SheetVisibility.Expanded,
    ),
)
