package dev.cline.termux.ui.terminal

import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.tools.CommandOutputCap
import dev.cline.termux.data.tools.TerminalLine
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.LocalReducedMotion
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sizes and thresholds of the terminal (CT B.4, DS #CommandOutput). */
object TerminalDefaults {
    /** Inline command output in the chat: max height, then it scrolls (desktop `max-h-64`). */
    val InlineMaxHeight: Dp = 256.dp

    /** Follow-tail stays pinned within this distance of the bottom (desktop 24px). */
    val PinThreshold: Dp = 24.dp

    /** "Scroll to latest output" shows beyond this distance (DS ScrollToLatest). */
    val ScrollButtonThreshold: Dp = 120.dp

    val Padding: Dp = 12.dp
    val CursorWidth: Dp = 6.dp
    val CursorHeight: Dp = 12.dp

    /** Desktop `ml-0.5` before the cursor block. */
    val CursorGap: Dp = 2.dp

    /** Blink half-period: alpha 1 → 0 → 1 over 1 s. */
    const val CursorBlinkHalfMillis = 500

    const val MinTextScale = 0.75f
    const val MaxTextScale = 2.0f
}

/**
 * Scroll position and follow-tail pin of one terminal. Hoist one per command execution to
 * keep each command's offset when switching between them (CT B.4).
 */
@Stable
class TerminalScrollState internal constructor(
    val listState: LazyListState,
    private val pinnedState: MutableState<Boolean>,
    private val scaleState: MutableState<Float>,
) {
    /** True while the view follows new output. A user drag away from the bottom releases it. */
    var pinned: Boolean
        get() = pinnedState.value
        set(value) {
            pinnedState.value = value
        }

    /** Pinch text scale, 0.75–2.0 (detail screen only). */
    var textScale: Float
        get() = scaleState.value
        set(value) {
            scaleState.value = value.coerceIn(TerminalDefaults.MinTextScale, TerminalDefaults.MaxTextScale)
        }

    /** Set while the view scrolls itself, so its own scrolls never release the pin. */
    internal var autoScrolling: Boolean = false
}

@Composable
fun rememberTerminalScrollState(): TerminalScrollState {
    val list = rememberLazyListState()
    val pinned = rememberSaveable { mutableStateOf(true) }
    val scale = rememberSaveable { mutableStateOf(1f) }
    return remember(list, pinned, scale) { TerminalScrollState(list, pinned, scale) }
}

/**
 * Raw output (with ANSI escapes) as a [TerminalSnapshot]. One [IncrementalTerminalParser] per
 * call site (one chat row, one terminal) is fed on `Dispatchers.Default` (docs/07 §8) and
 * appends only what each new [output] adds, so a streaming command costs its new characters
 * per tick, not a reparse of the whole 48 000-character window. Versions that arrive while
 * one is being applied are conflated to the latest; the previous snapshot stays on screen
 * meanwhile. Only the first frame of a small output (a few KB) is parsed in composition, so
 * a short finished command does not flash empty.
 */
@Composable
fun rememberTerminalSnapshot(
    output: String,
    capChars: Int = CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS,
): TerminalSnapshot {
    val parser = remember(capChars) { IncrementalTerminalParser(capChars) }
    val initial = remember(parser) {
        if (output.length <= SYNC_PARSE_CHARS) parser.update(output) else TerminalSnapshot.EMPTY
    }
    val latestOutput = rememberUpdatedState(output)
    val parsed by produceState(initial, parser) {
        // One sequential pipeline owns the parser: the composition-time update above runs
        // before this coroutine starts, and conflation never runs two updates at once.
        snapshotFlow { latestOutput.value }
            .conflate()
            .map { parser.update(it) }
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }
    return parsed
}

/**
 * Desktop `AgentCommandOutput` (DS #CommandOutput): the "Output" overline above an inline
 * [TerminalView] capped at 256dp, auto-scroll pinned within 24dp of the bottom and a
 * blinking cursor while running. [output] is the raw (capped) text with ANSI escapes, e.g.
 * from [CommandOutputCap.appendCapped].
 */
@Composable
fun CommandOutput(
    output: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    onCopyOutput: ((String) -> Unit)? = null,
) {
    val snapshot = rememberTerminalSnapshot(output)
    Column(modifier.padding(top = Spacing.s)) {
        Text(
            text = stringResource(R.string.tool_overline_output).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalView(
            snapshot = snapshot,
            running = isRunning,
            inline = true,
            onCopyOutput = onCopyOutput,
            modifier = Modifier
                .padding(top = Spacing.xs)
                .fillMaxWidth(),
        )
    }
}

/**
 * The terminal (CT B.4): `terminalBackground` / `terminalForeground` in both themes,
 * `ClineMono.body`, ANSI colours ([AnsiParser]), lines in an internal `LazyColumn`.
 *
 * - [inline]: the chat's tool row, at most 256dp tall, no scroll button, no pinch.
 *   Otherwise the caller sizes it (the detail screen gives it `weight(1f)`), the
 *   "Scroll to latest output" button appears beyond 120dp from the bottom and two-finger
 *   pinch scales the text 0.75×–2.0× (off while TalkBack or Switch Access runs).
 * - Follow-tail: pinned while within 24dp of the bottom; a user drag up releases it,
 *   reaching the bottom again restores it.
 * - [wrap] on: lines wrap (desktop `whitespace-pre-wrap break-words`). Off: one shared
 *   horizontal scroll, width = longest line in cells (wide characters count 2, capped at
 *   2,000) × the measured cell width + 24dp.
 * - Cursor: a 6×12dp block after the last character while [running], blinking 1 s;
 *   static under reduced motion; absent when finished.
 * - Notice rows (truncation, skipped-output, detach and leftover-children markers) render
 *   in `labelMedium`, ANSI colour 8, and are left out of copied output.
 * - Always LTR; each line is selectable; "Copy output" is a TalkBack custom action and
 *   copies the whole buffer without escapes ([onCopyOutput] overrides the default plain
 *   clipboard write).
 *
 * [contentPadding] is added inside the list (the detail screen passes `bottomSafe`);
 * [scrollButtonPadding] lifts the scroll button (e.g. above a `BottomAppBar`).
 */
@Composable
fun TerminalView(
    snapshot: TerminalSnapshot,
    running: Boolean,
    modifier: Modifier = Modifier,
    inline: Boolean = true,
    wrap: Boolean = true,
    state: TerminalScrollState = rememberTerminalScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    scrollButtonPadding: PaddingValues = PaddingValues(),
    onCopyOutput: ((String) -> Unit)? = null,
) {
    val colors = ClineTheme.colors
    val palette = remember(colors) { TerminalPalette.from(colors) }
    val reducedMotion = LocalReducedMotion.current
    val zoomAllowed = !inline && !rememberAssistiveServiceOn()
    val scale = if (inline) 1f else state.textScale

    val base = ClineMono.body
    val textStyle = remember(base, scale, palette, wrap) {
        base.copy(
            color = palette.foreground,
            fontSize = base.fontSize * scale,
            lineHeight = base.lineHeight * scale,
            textDirection = TextDirection.Ltr,
            lineBreak = LineBreak.Simple,
        )
    }
    val labelStyle = MaterialTheme.typography.labelMedium
    val noticeStyle = remember(labelStyle, palette) { labelStyle.copy(color = palette.ansi[NOTICE_COLOR], textDirection = TextDirection.Ltr) }

    val outputLabel = stringResource(R.string.tool_output_cd)
    val copyLabel = stringResource(R.string.tool_copy_output)
    val headNotice = stringResource(R.string.tool_output_truncated_head)
    val copier = rememberPlainCopier()
    val latestSnapshot by rememberUpdatedState(snapshot)
    val copy: () -> Boolean = {
        val text = latestSnapshot.copyText()
        if (onCopyOutput != null) onCopyOutput(text) else copier.copy(outputLabel, text)
        true
    }

    val blinkAlpha: State<Float> = if (running && !reducedMotion) {
        rememberInfiniteTransition(label = "terminal-cursor").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(TerminalDefaults.CursorBlinkHalfMillis, easing = ClineMotion.Pulse),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "terminal-cursor-alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    // O(1) per snapshot: the rows are snapshot.lines themselves, nothing is rebuilt per tick.
    val layout = terminalLayout(snapshot, running)
    val listState = state.listState
    val density = LocalDensity.current
    val pinPx = with(density) { TerminalDefaults.PinThreshold.roundToPx() }
    val buttonPx = with(density) { TerminalDefaults.ScrollButtonThreshold.roundToPx() }
    // A Boolean, so a scroll recomposes this body only when the button's visibility flips.
    val showScrollButton by remember(listState, buttonPx) { derivedStateOf { listState.distanceToBottom() > buttonPx } }

    // A user scroll decides the pin; the view's own scrolls never do.
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.isScrollInProgress to (listState.distanceToBottom() <= pinPx) }
            .collect { (scrolling, nearBottom) ->
                if (scrolling && !state.autoScrolling) state.pinned = nearBottom
            }
    }
    // Follow new output while pinned: keyed on the snapshot (value equality, cheap: see
    // TerminalSnapshot.equals), so a chunk that only lengthens the last line scrolls too,
    // whatever the snapshot's version.
    LaunchedEffect(snapshot, running, state.pinned) {
        if (state.pinned && layout.itemCount > 0) state.scrollToEnd(layout.itemCount)
    }

    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current

    LtrBlock {
        Surface(
            color = palette.background,
            contentColor = palette.foreground,
            shape = MaterialTheme.shapes.small,
            modifier = modifier
                .then(if (inline) Modifier.heightIn(max = TerminalDefaults.InlineMaxHeight) else Modifier)
                .semantics {
                    contentDescription = outputLabel
                    customActions = listOf(CustomAccessibilityAction(copyLabel) { copy() })
                }
                .then(
                    if (zoomAllowed) {
                        Modifier.pinchToScale(
                            onZoom = { factor -> state.textScale = state.textScale * factor },
                            onReset = { state.textScale = 1f },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            BoxWithConstraints(if (inline) Modifier else Modifier.fillMaxSize()) {
                val padding = PaddingValues(
                    start = TerminalDefaults.Padding + contentPadding.calculateStartPadding(layoutDirection),
                    end = TerminalDefaults.Padding + contentPadding.calculateEndPadding(layoutDirection),
                    top = TerminalDefaults.Padding + contentPadding.calculateTopPadding(),
                    bottom = TerminalDefaults.Padding + contentPadding.calculateBottomPadding(),
                )
                // One selection region over the whole list (DS §8.1.11, desktop `user-select:
                // text`): a drag selects across lines, as in the desktop's output pane. Notice
                // rows opt out, and the cursor is an overlay, so neither lands in a copy.
                val lines: @Composable (Modifier) -> Unit = { listModifier ->
                    val snapshotLines = snapshot.lines
                    SelectionContainer {
                        LazyColumn(state = listState, contentPadding = padding, modifier = listModifier) {
                            if (layout.headNotice) {
                                item(key = TerminalLayout.HEAD_NOTICE_KEY, contentType = TerminalRowType.Notice) {
                                    TerminalNoticeText(text = headNotice, style = noticeStyle, wrap = wrap)
                                }
                            }
                            // Keyed on the absolute line index, so a head drop keeps every visible
                            // line on its composition (and its parsed text) instead of shifting all.
                            items(
                                count = snapshotLines.size,
                                key = { index -> snapshot.lineKey(index) },
                                contentType = { index -> if (snapshotLines[index].isNotice) TerminalRowType.Notice else TerminalRowType.Line },
                            ) { index ->
                                val line = snapshotLines[index]
                                if (line.isNotice) {
                                    TerminalNoticeText(text = line.text, style = noticeStyle, wrap = wrap)
                                } else {
                                    TerminalLineText(
                                        line = line,
                                        cursor = index == layout.cursorLine,
                                        palette = palette,
                                        style = textStyle,
                                        wrap = wrap,
                                        cursorColor = colors.terminalCursor,
                                        cursorAlpha = blinkAlpha,
                                    )
                                }
                            }
                            if (layout.cursorRow) {
                                item(key = TerminalLayout.CURSOR_ROW_KEY, contentType = TerminalRowType.Line) {
                                    TerminalLineText(
                                        line = EMPTY_LINE,
                                        cursor = true,
                                        palette = palette,
                                        style = textStyle,
                                        wrap = wrap,
                                        cursorColor = colors.terminalCursor,
                                        cursorAlpha = blinkAlpha,
                                    )
                                }
                            }
                        }
                    }
                }
                if (wrap) {
                    lines(if (inline) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                } else {
                    val measurer = rememberTextMeasurer()
                    val cellWidthPx = remember(textStyle) { measurer.measure("0", textStyle).size.width }
                    val columns = minOf(snapshot.maxColumns, AnsiParser.MAX_UNWRAPPED_COLUMNS) + 2
                    val contentWidth = with(density) { (columns * cellWidthPx).toDp() } +
                        padding.calculateStartPadding(layoutDirection) + padding.calculateEndPadding(layoutDirection)
                    val viewportWidth = maxWidth
                    val viewportHeight = maxHeight
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        lines(
                            Modifier
                                .width(maxOf(contentWidth, viewportWidth))
                                .then(if (inline) Modifier else Modifier.height(viewportHeight)),
                        )
                    }
                }

                if (!inline) {
                    TerminalScrollButton(
                        visible = showScrollButton,
                        onClick = {
                            state.pinned = true
                            scope.launch { state.scrollToEnd(layout.itemCount, animate = true) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(scrollButtonPadding)
                            .padding(end = Spacing.l, bottom = Spacing.l + contentPadding.calculateBottomPadding()),
                    )
                }
            }
        }
    }
}

/** Convenience: raw output text instead of a snapshot (history, finished commands). */
@Composable
fun TerminalView(
    output: String,
    running: Boolean,
    modifier: Modifier = Modifier,
    inline: Boolean = true,
    wrap: Boolean = true,
    capChars: Int = if (inline) CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS else CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS,
    state: TerminalScrollState = rememberTerminalScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    scrollButtonPadding: PaddingValues = PaddingValues(),
    onCopyOutput: ((String) -> Unit)? = null,
) {
    TerminalView(
        snapshot = rememberTerminalSnapshot(output, capChars),
        running = running,
        modifier = modifier,
        inline = inline,
        wrap = wrap,
        state = state,
        contentPadding = contentPadding,
        scrollButtonPadding = scrollButtonPadding,
        onCopyOutput = onCopyOutput,
    )
}

/** The two row kinds of the terminal list (its `contentType`): notice rows and text lines. */
internal enum class TerminalRowType { Notice, Line }

/**
 * The shape of the terminal list for one snapshot, computed in O(1) so nothing is rebuilt per
 * line on the main thread: the head-truncation notice row first when the buffer dropped its
 * head ([headNotice]), then `snapshot.lines` (notice lines as notice rows, keyed by
 * [TerminalSnapshot.lineKey]), and the cursor after the last character while running: on the
 * last line ([cursorLine]) when it is an unfinished text line, otherwise on its own row
 * ([cursorRow]) after a newline, a notice line or no output yet.
 */
@Immutable
internal data class TerminalLayout(
    val headNotice: Boolean,
    val lineCount: Int,
    /** Index into `snapshot.lines` of the line that carries the cursor, or -1. */
    val cursorLine: Int,
    val cursorRow: Boolean,
) {
    /** All rows: the head notice, the lines and the cursor row. */
    val itemCount: Int get() = (if (headNotice) 1 else 0) + lineCount + (if (cursorRow) 1 else 0)

    companion object {
        /** Item keys of the two synthetic rows; line rows use their `Long` absolute index. */
        const val HEAD_NOTICE_KEY = "terminal-head-notice"
        const val CURSOR_ROW_KEY = "terminal-cursor-row"
    }
}

internal fun terminalLayout(snapshot: TerminalSnapshot, running: Boolean): TerminalLayout {
    val lines = snapshot.lines
    val last = lines.lastOrNull()
    val cursorOnLast = running && last != null && !snapshot.endsWithNewline && !last.isNotice
    return TerminalLayout(
        headNotice = snapshot.headTruncated,
        lineCount = lines.size,
        cursorLine = if (cursorOnLast) lines.lastIndex else -1,
        cursorRow = running && !cursorOnLast,
    )
}

@Composable
private fun TerminalNoticeText(text: String, style: TextStyle, wrap: Boolean) {
    // Truncation notices are the view's own copy, not command output: a drag across the
    // output skips them, as "Copy output" does.
    DisableSelection {
        Text(
            text = text,
            style = style,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = Modifier.padding(vertical = Spacing.xxs),
        )
    }
}

@Composable
private fun TerminalLineText(
    line: TerminalLine,
    cursor: Boolean,
    palette: TerminalPalette,
    style: TextStyle,
    wrap: Boolean,
    cursorColor: Color,
    cursorAlpha: State<Float>,
) {
    val text = remember(line, palette, wrap) {
        AnsiParser.toAnnotatedString(line, palette, if (wrap) null else AnsiParser.MAX_UNWRAPPED_COLUMNS)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        // Selectable through the list-wide SelectionContainer, so a selection crosses lines.
        Text(
            text = text,
            style = style,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            onTextLayout = { layout = it },
        )
        // The cursor is drawn over the line, not part of its text: TalkBack never reads it
        // and a selection never copies it (desktop: aria-hidden, outside the output).
        val measured = layout
        if (cursor && measured != null) {
            Box(
                Modifier
                    .offset {
                        val end = measured.getCursorRect(measured.layoutInput.text.length)
                        IntOffset(
                            x = (end.left + TerminalDefaults.CursorGap.toPx()).roundToInt(),
                            y = (end.center.y - TerminalDefaults.CursorHeight.toPx() / 2).roundToInt(),
                        )
                    }
                    .size(TerminalDefaults.CursorWidth, TerminalDefaults.CursorHeight)
                    .graphicsLayer { alpha = cursorAlpha.value }
                    .background(cursorColor)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun TerminalScrollButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tool_scroll_latest)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(tween(ClineMotion.Short3), initialScale = 0.8f) + fadeIn(tween(ClineMotion.Short3)),
        exit = scaleOut(tween(ClineMotion.Short2), targetScale = 0.8f) + fadeOut(tween(ClineMotion.Short2)),
    ) {
        PlainTooltipBox(text = label) {
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(ClineIcons.ArrowDownward, contentDescription = label)
            }
        }
    }
}

/** Pixels between the end of the content and the bottom of the viewport (0 when at the end). */
internal fun LazyListState.distanceToBottom(): Int {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < total - 1) return Int.MAX_VALUE
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - viewportEnd).coerceAtLeast(0)
}

private suspend fun TerminalScrollState.scrollToEnd(itemCount: Int, animate: Boolean = false) {
    if (itemCount == 0) return
    autoScrolling = true
    try {
        if (animate) listState.animateScrollToItem(itemCount - 1) else listState.scrollToItem(itemCount - 1)
        // The last item may be taller than the viewport (a long wrapped line): go to its end.
        listState.scrollBy(END_SCROLL_PX)
    } finally {
        autoScrolling = false
    }
}

/**
 * Two-finger pinch scales the text; a two-finger double tap resets it. One-finger input is
 * never consumed, so the list scrolls and lines stay selectable.
 */
private fun Modifier.pinchToScale(onZoom: (Float) -> Unit, onReset: () -> Unit): Modifier = pointerInput(Unit) {
    var lastTwoFingerTap = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startedAt = down.uptimeMillis
        var multiTouch = false
        var zoomed = false
        var endedAt = startedAt
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            endedAt = event.changes.maxOfOrNull { it.uptimeMillis } ?: endedAt
            if (event.changes.count { it.pressed } >= 2) {
                multiTouch = true
                val zoom = event.calculateZoom()
                if (abs(zoom - 1f) > ZOOM_EPSILON) {
                    zoomed = true
                    onZoom(zoom)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (multiTouch && !zoomed && endedAt - startedAt < TWO_FINGER_TAP_MILLIS) {
            if (endedAt - lastTwoFingerTap < DOUBLE_TAP_MILLIS) {
                onReset()
                lastTwoFingerTap = 0L
            } else {
                lastTwoFingerTap = endedAt
            }
        }
    }
}

/** True while TalkBack, Switch Access or another spoken/generic feedback service runs. */
@Composable
private fun rememberAssistiveServiceOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager?.assistiveServiceOn() ?: false) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on = manager?.assistiveServiceOn() ?: false }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return on
}

private fun AccessibilityManager.assistiveServiceOn(): Boolean =
    isEnabled && (
        isTouchExplorationEnabled ||
            getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_GENERIC,
            ).isNotEmpty()
        )

private const val NOTICE_COLOR = 8
/** Outputs up to this many characters are parsed in composition, for the first frame only. */
private const val SYNC_PARSE_CHARS = 4_096
private const val END_SCROLL_PX = 1_000_000f
private const val ZOOM_EPSILON = 0.01f
private const val TWO_FINGER_TAP_MILLIS = 300L
private const val DOUBLE_TAP_MILLIS = 400L
private val EMPTY_LINE = TerminalLine("")

// region previews

private const val PREVIEW_OUTPUT =
    "\u001b[1mbun test\u001b[0m v1.4.2\n" +
        "\u001b[32m✓\u001b[0m parser > parses SGR \u001b[2m[2.1ms]\u001b[0m\n" +
        "\u001b[31m✗\u001b[0m diff > counts lines\n" +
        "  \u001b[38;5;214mexpected\u001b[0m 3, \u001b[38;2;120;180;255mreceived\u001b[0m 2\n" +
        "\u001b[7m inverse \u001b[0m \u001b[4munderline\u001b[0m \u001b[9mstrike\u001b[0m \u001b[3mitalic\u001b[0m\n" +
        "\u001b[1;31mbold red\u001b[0m \u001b[1;32mbold green\u001b[0m \u001b[44m blue bg \u001b[0m\n" +
        "Progress 10%\rProgress 100%\n" +
        "[3120 command-output characters skipped while streaming]\n" +
        " 12 pass  \u001b[31m1 fail\u001b[0m"

private const val PREVIEW_LONG =
    "const bundle = \"" +
        "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789" +
        "\";\nshort line\n" +
        "error: /data/data/com.termux/files/home/projects/cline-app/app/src/main/kotlin/dev/cline/termux/ui/terminal/TerminalView.kt:42:17 unresolved reference\n"

private const val PREVIEW_CJK =
    "构建成功：3 个文件已更新\n" +
        "\u001b[33m警告\u001b[0m：未找到配置文件，使用默认设置\n" +
        "テスト完了 ✓ 全角ＡＢＣ 반각 테스트\n" +
        "混合 mixed 文本 with ASCII\n"

@LightDarkPreviews
@Composable
private fun CommandOutputRunningPreview() {
    ComponentPreview {
        CommandOutput(output = PREVIEW_OUTPUT, isRunning = true)
    }
}

@LightDarkPreviews
@Composable
private fun CommandOutputFinishedPreview() {
    ComponentPreview {
        CommandOutput(output = PREVIEW_OUTPUT, isRunning = false)
    }
}

@LightDarkPreviews
@Composable
private fun CommandOutputWaitingPreview() {
    ComponentPreview {
        CommandOutput(output = "", isRunning = true)
    }
}

@LightDarkPreviews
@Composable
private fun TerminalTruncatedPreview() {
    ComponentPreview {
        TerminalView(
            output = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER + "…tail of a long build\nBUILD SUCCESSFUL in 41s\n" +
                "[Command is still running. Output will continue in /data/data/com.termux/files/usr/tmp/cline-cmd-1.log]",
            running = false,
        )
    }
}

@LightDarkPreviews
@Composable
private fun TerminalLongLinesWrappedPreview() {
    ComponentPreview {
        TerminalView(output = PREVIEW_LONG, running = false)
    }
}

@LightDarkPreviews
@Composable
private fun TerminalLongLinesUnwrappedPreview() {
    ComponentPreview {
        TerminalView(output = PREVIEW_LONG, running = true, wrap = false)
    }
}

@LightDarkPreviews
@Composable
private fun TerminalCjkPreview() {
    ComponentPreview {
        TerminalView(output = PREVIEW_CJK, running = false)
    }
}

@Preview(name = "Detail", heightDp = 360)
@Composable
private fun TerminalDetailPreview() {
    ComponentPreview(padding = false) {
        TerminalView(
            output = (1..60).joinToString("\n") { "\u001b[36mline $it\u001b[0m output" },
            running = true,
            inline = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Detail dark", heightDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TerminalDetailDarkPreview() {
    ComponentPreview(padding = false) {
        TerminalView(output = PREVIEW_CJK + PREVIEW_OUTPUT, running = false, inline = false, wrap = false, modifier = Modifier.fillMaxSize())
    }
}

// endregion
