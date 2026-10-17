// Adapted from cline-termux-voice (same owner): android/app/src/main/kotlin/dev/cline/voice/ui/markdown/Markdown.kt
package dev.cline.termux.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.OpenExternalResult
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.openExternal
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineLightScheme
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.LocalIsDarkTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders Cline's Markdown as a readable document, the way the desktop app shows an answer:
 * full-width text with headings, lists, quotes, inline code, code blocks with a copy button,
 * tables and links, all styled from the Material 3 scheme so it follows the wallpaper and
 * light/dark like the rest of the app (design-system.md §2.1 "Markdown inside messages").
 *
 * A finished text is parsed once and cached across recompositions and lazy-list reuse; a
 * text over [SYNC_PARSE_CHARS] that is not cached yet is parsed on `Dispatchers.Default`
 * (docs/07 §8) while the previous document stays on screen. With [streaming] (the reply is
 * still arriving, every 50ms) only the last block is parsed again (DS §6) and nothing enters
 * the cache. The blocks are laid out in a plain Column, so the caller should give each reply
 * its own lazy-list item rather than nest this in another scrolling container.
 *
 * With [selectable] (the default) the whole reply sits in one SelectionContainer so any
 * part of it can be long-pressed and copied; pass false when the caller wants long-press for
 * its own menu. Code blocks are selectable either way (HC §3.14, DS §8.1.11), and links and
 * the code-block copy button stay clickable either way.
 *
 * Bullets, table columns and the quote rule are laid out in the reply's own direction
 * ([MdDocument.isRtl], the desktop's `dir="auto"`), whatever the app's layout direction.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    selectable: Boolean = true,
    streaming: Boolean = false,
) {
    val images = MdImageLabels(
        blocked = stringResource(R.string.md_image_blocked),
        blockedWithAlt = stringResource(R.string.md_image_blocked_alt),
    )
    val document = rememberMarkdownDocument(markdown, images, streaming)
    // A deceptive link (HC §3) asks before it opens; the dialog shows the real address.
    var confirmUrl by remember { mutableStateOf<String?>(null) }
    val openLink = rememberOpenMarkdownLink()
    val styles = rememberMdStyles(style, selectable, openLink, onDeceptive = { confirmUrl = it })
    // Text colour flows through LocalContentColor so a quote can tone its text down.
    val color = style.color.takeOrElse { LocalContentColor.current }
    // The blocks follow the reply's own direction (desktop `dir="auto"`, DS §8.2.6/8), not the
    // app's: an English table keeps its column order and bullets in an RTL app, and an Arabic
    // answer stays right-to-left in an LTR one. [modifier] stays outside, in the app's direction.
    val direction = if (document.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalContentColor provides color) {
        if (selectable) {
            SelectionContainer(modifier) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) { Blocks(document.blocks, styles) }
            }
        } else {
            Box(modifier, propagateMinConstraints = true) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) { Blocks(document.blocks, styles) }
            }
        }
    }
    confirmUrl?.let { url ->
        LinkSafetyDialog(
            url = url,
            onOpen = {
                confirmUrl = null
                openLink(url)
            },
            onDismiss = { confirmUrl = null },
        )
    }
}

/**
 * Desktop `MarkdownLinkSafetyModal` (HC §3): "Open external link?", the body, the real URL in a
 * mono block (`surfaceContainer`, at most 128dp, scrolls) and Cancel / Open link.
 */
@Composable
internal fun LinkSafetyDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.md_link_title),
        text = stringResource(R.string.md_link_body),
        confirmLabel = stringResource(R.string.md_link_open),
        dismissLabel = stringResource(R.string.md_link_cancel),
        onConfirm = onOpen,
        onDismiss = onDismiss,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .padding(top = Spacing.m)
                .fillMaxWidth()
                .heightIn(max = 128.dp),
        ) {
            Text(
                url,
                style = ClineMono.body.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.m),
            )
        }
    }
}

/** Texts up to this length are parsed in composition when they are not cached; longer ones off the main thread. */
internal const val SYNC_PARSE_CHARS = 4_096

/**
 * The document for [markdown]. Whatever is available without parsing a long text on the main
 * thread is returned at once: a cached finished document, or a parse of a short text (the
 * incremental one while [streaming]). Anything else is parsed on `Dispatchers.Default`, and
 * until it arrives the previous document stays on screen (empty only for a long reply seen
 * for the first time).
 */
@Composable
private fun rememberMarkdownDocument(markdown: String, images: MdImageLabels, streaming: Boolean): MdDocument {
    val incremental = remember(images) { IncrementalMarkdown(images) }
    val immediate = remember(markdown, incremental, streaming) {
        when {
            streaming -> if (markdown.length <= SYNC_PARSE_CHARS) incremental.document(markdown) else null
            else -> MarkdownParser.cachedDocument(markdown, images)
                ?: if (markdown.length <= SYNC_PARSE_CHARS) MarkdownParser.document(markdown, images) else null
        }
    }
    val latest by produceState(immediate ?: MdDocument.Empty, markdown, incremental, streaming) {
        value = immediate ?: withContext(Dispatchers.Default) {
            if (streaming) incremental.document(markdown) else MarkdownParser.document(markdown, images)
        }
    }
    return immediate ?: latest
}

/**
 * Opens a Markdown link (HC §3) through the one [openExternal] helper (docs/07 D8): http(s)
 * in a Custom Tab, mailto/tel through `ACTION_VIEW`; when nothing on the phone takes it, the
 * snackbar "No app can open this link." and nothing is copied.
 */
@Composable
private fun rememberOpenMarkdownLink(): (String) -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val surface = MaterialTheme.colorScheme.surface
    val dark = LocalIsDarkTheme.current
    val noApp = stringResource(R.string.link_no_app)
    return remember(context, snackbar, scope, surface, dark, noApp) {
        { url: String ->
            val light = if (dark) ClineLightScheme.surface else surface
            val night = if (dark) surface else ClineDarkScheme.surface
            val result = openExternal(context, url, lightSurface = light, darkSurface = night, copyOnFailure = false)
            if (result != OpenExternalResult.Opened) scope.launch { snackbar.showReplacing(noApp) }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Styles

/** Everything the block renderers need from the theme, resolved once per MarkdownText. */
@Immutable
private class MdStyles(
    val body: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val code: TextStyle,
    val table: TextStyle,
    val tableHeader: TextStyle,
    val links: TextLinkStyles,
    val linkListener: LinkInteractionListener,
    val muted: Color,
    val rule: Color,
    val inlineCode: Color,
    val codeContainer: Color,
    val tableHeaderContainer: Color,
    /**
     * The enclosing MarkdownText is not selectable, so each code block brings its own
     * SelectionContainer: code is always selectable (HC §3.14, DS §8.1.11).
     */
    val codeOwnsSelection: Boolean,
) {
    fun heading(level: Int): TextStyle = when (level) {
        1 -> h1
        2 -> h2
        3 -> h3
        else -> h4
    }
}

@Composable
private fun rememberMdStyles(
    style: TextStyle,
    selectable: Boolean,
    openLink: (String) -> Unit,
    onDeceptive: (String) -> Unit,
): MdStyles {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val mono = ClineMono.body
    // The listener stays one instance: styled paragraphs are keyed on these styles.
    val open by rememberUpdatedState(openLink)
    val confirm by rememberUpdatedState(onDeceptive)
    // Keyed on the colours actually used, not the scheme instance: the app theme builds a new
    // (equal) scheme whenever it recomposes, and every styled paragraph is keyed on these styles.
    val primary = colors.primary
    val muted = colors.onSurfaceVariant
    val rule = colors.outlineVariant
    val container = colors.surfaceContainer
    val containerHigh = colors.surfaceContainerHigh
    return remember(style, selectable, type, mono, primary, muted, rule, container, containerHigh) {
        val listener = LinkInteractionListener { link ->
            when (link) {
                is LinkAnnotation.Url -> open(link.url)
                // Deceptive links carry their URL as the tag and ask first.
                is LinkAnnotation.Clickable -> confirm(link.tag)
                else -> Unit
            }
        }
        val link = SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
        // Prose wraps with the paragraph strategy (§2.3); the colour comes from LocalContentColor.
        val body = style.copy(color = Color.Unspecified, lineBreak = LineBreak.Paragraph)
        MdStyles(
            body = body,
            h1 = markdownHeadingStyle(body, 1),
            h2 = markdownHeadingStyle(body, 2),
            h3 = markdownHeadingStyle(body, 3),
            h4 = markdownHeadingStyle(body, 4),
            // Code is always left-to-right, whatever the UI language (§8.2 rule 6).
            code = mono.copy(textDirection = TextDirection.Ltr),
            table = type.bodyMedium,
            tableHeader = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            links = TextLinkStyles(
                style = link,
                pressedStyle = link.copy(background = primary.copy(alpha = 0.12f)),
            ),
            linkListener = listener,
            muted = muted,
            rule = rule,
            inlineCode = containerHigh,
            codeContainer = container,
            tableHeaderContainer = containerHigh,
            codeOwnsSelection = !selectable,
        )
    }
}

/** The body size the DS §2.1 heading sizes are given against: `bodyLarge` at font size 15. */
private const val HEADING_REFERENCE_BODY_SP = 16f

/** DS §2.1 heading size / line height (sp) for h1–h4 against a [HEADING_REFERENCE_BODY_SP] body. */
private val HeadingMetrics = listOf(22 to 28, 19 to 26, 17 to 24, 16 to 24)

/**
 * Heading [level] (1–4, deeper levels use h4) for a reply set in [body]: the body face, larger
 * and SemiBold, a document's own hierarchy rather than the app's title styles (DS §2.1).
 *
 * The DS sizes (h1 22/28 … h4 16/24) are relative to a 16sp body, so they scale with the body
 * the caller passes. That body already carries the font size setting (DS §2.4, `n / 15f`), so
 * a heading never drops below the paragraph text at font size 20 nor towers over it at 12, and
 * a reply set in `bodyMedium` keeps the same proportions.
 */
internal fun markdownHeadingStyle(body: TextStyle, level: Int): TextStyle {
    val (size, line) = HeadingMetrics[(level.coerceIn(1, HeadingMetrics.size)) - 1]
    val ratio = if (body.fontSize.isSp) body.fontSize.value / HEADING_REFERENCE_BODY_SP else 1f
    return body.copy(
        fontSize = (size * ratio).sp,
        lineHeight = (line * ratio).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )
}

/** An inline run with its theme-dependent part (links) laid on. */
@Composable
private fun styledText(inline: MdInline, styles: MdStyles): AnnotatedString {
    if (inline.links.isEmpty()) return inline.text
    return remember(inline, styles) {
        buildAnnotatedString {
            append(inline.text)
            for (range in inline.links) {
                val link = range.item
                if (link.deceptive) {
                    addLink(LinkAnnotation.Clickable(link.url, styles.links, styles.linkListener), range.start, range.end)
                } else {
                    addLink(LinkAnnotation.Url(link.url, styles.links, styles.linkListener), range.start, range.end)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Inline text

private val ChipPaddingH = 3.dp
private val ChipRadius = 4.dp

// Chip edges relative to the line's baseline, in multiples of the run's font size (the
// default body size when a style leaves it unset).
private const val ChipAscent = 0.95f
private const val ChipDescent = 0.3f
private val DefaultChipFont = 16.sp

/**
 * One block's inline run: a paragraph, a heading or a table cell. Inline code gets a padded,
 * rounded chip behind its letters, like Cline Desktop's `code`, one per line the span covers:
 * a wrapped path reads as pieces of text rather than a slab, and stays selectable with the
 * rest of the run.
 */
@Composable
private fun InlineText(
    inline: MdInline,
    styles: MdStyles,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val text = styledText(inline, styles)
    if (inline.code.isEmpty()) {
        Text(text, modifier, style = style, textAlign = textAlign)
    } else {
        val chipColor = styles.inlineCode
        // Written by onTextLayout, which runs only when the layout changes, and read only in
        // draw: the chips land in the same frame as the text, without a recomposition.
        var chips by remember { mutableStateOf(emptyList<Rect>()) }
        Text(
            text,
            modifier.drawBehind {
                val rects = chips
                val radius = CornerRadius(ChipRadius.toPx())
                for (i in rects.indices) {
                    val rect = rects[i]
                    drawRoundRect(chipColor, rect.topLeft, rect.size, radius)
                }
            },
            style = style,
            textAlign = textAlign,
            onTextLayout = { chips = chipRects(it, inline.code) },
        )
    }
}

/**
 * Where the chips of [ranges] go in [layout]: one per line fragment, trimmed to the letters
 * (the space a span wraps at gets none), padded sideways and sized from the run's font, so
 * every chip in a paragraph is the same height whatever the glyphs.
 */
private fun chipRects(layout: TextLayoutResult, ranges: List<TextRange>): List<Rect> {
    val input = layout.layoutInput
    val fontSize = input.style.fontSize
    val fontPx = with(input.density) { if (fontSize.isSp) fontSize.toPx() else DefaultChipFont.toPx() }
    val padPx = with(input.density) { ChipPaddingH.toPx() }
    val length = input.text.length
    val rects = ArrayList<Rect>(ranges.size)
    for (range in ranges) {
        val end = minOf(range.end, length)
        if (range.start >= end) continue
        for (line in layout.getLineForOffset(range.start)..layout.getLineForOffset(end - 1)) {
            val from = maxOf(range.start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            if (to <= from) continue
            val start = layout.getHorizontalPosition(from, usePrimaryDirection = true)
            val stop = layout.getBoundingBox(to - 1).right
            val baseline = layout.getLineBaseline(line)
            rects += Rect(
                left = minOf(start, stop) - padPx,
                top = baseline - ChipAscent * fontPx,
                right = maxOf(start, stop) + padPx,
                bottom = baseline + ChipDescent * fontPx,
            )
        }
    }
    return rects
}

// ---------------------------------------------------------------------------------------
// Blocks

private val QuoteRule = 3.dp
private val QuoteGap = 12.dp
private val Hairline = 1.dp

/**
 * A run of blocks. Spacing follows the desktop rhythm scaled to a 16sp base: 12dp between
 * blocks, 16dp around code, tables and rules, headings with more room above (20-24dp) than
 * below (8dp). [compact] is the inside of a tight list item, where blocks sit close.
 */
@Composable
private fun Blocks(
    blocks: List<MdBlock>,
    styles: MdStyles,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        var previous: MdBlock? = null
        for (block in blocks) {
            val gap = if (previous == null) 0.dp else gapBetween(previous, block, compact)
            Block(block, styles, if (gap > 0.dp) Modifier.padding(top = gap) else Modifier)
            previous = block
        }
    }
}

private fun MdBlock.isBoxed(): Boolean = this is MdBlock.Code || this is MdBlock.Table || this is MdBlock.Rule

private fun gapBetween(previous: MdBlock, next: MdBlock, compact: Boolean): Dp = when {
    next is MdBlock.Heading -> when {
        compact -> 8.dp
        next.level <= 2 -> 24.dp
        else -> 20.dp
    }
    previous is MdBlock.Heading -> 8.dp
    compact -> if (next.isBoxed() || previous.isBoxed()) 8.dp else 4.dp
    next.isBoxed() || previous.isBoxed() -> 16.dp
    else -> 12.dp
}

@Composable
private fun Block(block: MdBlock, styles: MdStyles, modifier: Modifier) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inline, styles, styles.body, modifier)
        is MdBlock.Heading -> InlineText(
            block.inline,
            styles,
            styles.heading(block.level),
            modifier.semantics { heading() },
        )
        is MdBlock.ListBlock -> ListBlockView(block, styles, modifier)
        is MdBlock.Quote -> QuoteView(block, styles, modifier)
        is MdBlock.Code -> CodeBlockView(block, styles, modifier)
        is MdBlock.Table -> TableView(block, styles, modifier)
        MdBlock.Rule -> HorizontalDivider(modifier, thickness = Hairline, color = styles.rule)
    }
}

/** A quote: a 3dp rule on the start edge and toned-down text. */
@Composable
private fun QuoteView(block: MdBlock.Quote, styles: MdStyles, modifier: Modifier) {
    val rule = styles.rule
    CompositionLocalProvider(LocalContentColor provides styles.muted) {
        Blocks(
            block.blocks,
            styles,
            modifier
                .drawBehind {
                    val width = QuoteRule.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRect(rule, topLeft = Offset(x, 0f), size = Size(width, size.height))
                }
                .padding(start = QuoteRule + QuoteGap),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Lists

private val MinGutter = 20.dp
private val MarkerGap = 6.dp

/**
 * A list with hanging indents: markers in a gutter as wide as the widest marker of this
 * list (so "9." and "10." line up on the dot), item bodies beside it, so wrapped lines and
 * nested blocks align with the item's text rather than with the bullet.
 */
@Composable
private fun ListBlockView(block: MdBlock.ListBlock, styles: MdStyles, modifier: Modifier) {
    val itemGap = if (block.tight) 4.dp else 12.dp
    Layout(
        content = {
            for (item in block.items) {
                Text(item.marker, style = styles.body, color = styles.muted, maxLines = 1, softWrap = false)
                Blocks(item.blocks, styles, compact = block.tight)
            }
        },
        modifier = modifier,
        measurePolicy = remember(itemGap) { HangingListPolicy(itemGap) },
    )
}

/** Children alternate marker, body. Each marker sits on its body's first baseline. */
private class HangingListPolicy(private val itemGap: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val count = measurables.size / 2
        val gap = itemGap.roundToPx()
        val markerGap = MarkerGap.roundToPx()
        val markers = Array(count) { measurables[2 * it].measure(Constraints()) }
        var widest = 0
        for (marker in markers) widest = maxOf(widest, marker.width)
        val gutter = maxOf(MinGutter.roundToPx(), widest + markerGap)
        val bodyMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - gutter).coerceAtLeast(0) else Constraints.Infinity
        val bodyConstraints = Constraints(maxWidth = bodyMax)
        val bodies = Array(count) { measurables[2 * it + 1].measure(bodyConstraints) }

        val markerY = IntArray(count)
        val bodyY = IntArray(count)
        var y = 0
        var widestBody = 0
        for (i in 0 until count) {
            if (i > 0) y += gap
            val markerBaseline = markers[i][FirstBaseline]
            val bodyBaseline = bodies[i][FirstBaseline]
            val shift = if (markerBaseline == AlignmentLine.Unspecified || bodyBaseline == AlignmentLine.Unspecified) {
                0
            } else {
                bodyBaseline - markerBaseline
            }
            markerY[i] = y + maxOf(shift, 0)
            bodyY[i] = y + maxOf(-shift, 0)
            y = maxOf(markerY[i] + markers[i].height, bodyY[i] + bodies[i].height)
            widestBody = maxOf(widestBody, bodies[i].width)
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else gutter + widestBody
        return layout(width.coerceAtLeast(constraints.minWidth), y.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            for (i in 0 until count) {
                markers[i].placeRelative(gutter - markerGap - markers[i].width, markerY[i])
                bodies[i].placeRelative(gutter, bodyY[i])
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Code blocks

// Shell sessions, logs and prose read better wrapped; source code keeps its lines and scrolls.
private val WrappedLanguages = setOf(
    "", "text", "txt", "plain", "plaintext",
    "bash", "sh", "shell", "zsh", "console", "output", "log",
)

// Fence labels that name no language, so the copy button says just "Copy code".
private val UnnamedLanguages = setOf("", "text", "txt", "plain", "plaintext")

/**
 * A code block the way Cline Desktop shows one: a tonal container with the code in
 * monospace and a copy button floating in its corner, no header. Shell and plain text wrap;
 * source code keeps its lines and scrolls sideways, clipped at a clean inner edge. The code
 * is always selectable (its own SelectionContainer when the reply is not) and TalkBack reads
 * it as written, tabs included (DS §8.1.12).
 */
@Composable
private fun CodeBlockView(block: MdBlock.Code, styles: MdStyles, modifier: Modifier) {
    val language = block.language.lowercase()
    val wrap = language in WrappedLanguages
    val scroll = rememberScrollState()
    val label = if (language in UnnamedLanguages) {
        stringResource(R.string.markdown_copy_code)
    } else {
        stringResource(R.string.markdown_copy_code_language, block.language)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = styles.codeContainer,
    ) {
        // Code is laid out left-to-right even in an RTL UI (§8.2 rule 6).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box {
                val code = @Composable {
                    Text(
                        block.display,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Outside the scroll, so clipped lines stop at the inset; the end inset
                            // keeps every line clear of the copy button.
                            .padding(start = Spacing.l, top = Spacing.m, end = Spacing.xxxl + Spacing.s, bottom = Spacing.m)
                            .then(if (wrap) Modifier else Modifier.horizontalScroll(scroll))
                            .semantics { contentDescription = block.code },
                        style = styles.code,
                        softWrap = wrap,
                    )
                }
                if (styles.codeOwnsSelection) SelectionContainer(Modifier.fillMaxWidth()) { code() } else code()
                CopyButton(
                    block.code,
                    label = label,
                    tint = styles.muted,
                    // Centred on the first line of code.
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = Spacing.xs, end = Spacing.xs),
                )
            }
        }
    }
}

/**
 * Copies [code] to the clipboard and briefly turns into a check. Android 13+ confirms a
 * copy itself; below that a snackbar does. It takes a 36dp slot but keeps the 48dp touch
 * target, and stays out of text selection.
 */
@Composable
private fun CopyButton(code: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    val copied = stringResource(R.string.markdown_copied)
    val copier = rememberCopier(legacyConfirmation = copied)
    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(1500)
            showCopied = false
        }
    }
    DisableSelection {
        IconButton(
            onClick = { copier.copy(label, code) { ok -> if (ok) showCopied = true } },
            modifier = modifier
                .focusRing(CircleShape)
                .size(36.dp)
                .wrapContentSize(unbounded = true),
        ) {
            Icon(
                if (showCopied) ClineIcons.Chrome.Check else ClineIcons.Chrome.ContentCopy,
                contentDescription = if (showCopied) copied else label,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Tables

private val MaxColumnWidth = 240.dp
private val CellPaddingH = 12.dp
private val CellPaddingV = 8.dp

private val MdAlign.textAlign: TextAlign
    get() = when (this) {
        MdAlign.Start -> TextAlign.Start
        MdAlign.Center -> TextAlign.Center
        MdAlign.End -> TextAlign.End
    }

/**
 * A table in a hairline-bordered box that scrolls sideways when it is wider than the
 * screen. Each column is as wide as its widest cell up to [MaxColumnWidth] (longer cells
 * wrap); a table narrower than the screen stretches its columns to fill it.
 */
@Composable
private fun TableView(block: MdBlock.Table, styles: MdStyles, modifier: Modifier) {
    val shape = MaterialTheme.shapes.small
    Layout(
        content = {
            if (block.hasHeader) Box(Modifier.background(styles.tableHeaderContainer))
            block.rows.forEachIndexed { r, row ->
                val header = block.hasHeader && r == 0
                row.forEachIndexed { c, cell ->
                    InlineText(
                        cell,
                        styles,
                        style = if (header) styles.tableHeader else styles.table,
                        modifier = Modifier.padding(horizontal = CellPaddingH, vertical = CellPaddingV),
                        textAlign = block.alignments[c].textAlign,
                    )
                }
            }
            repeat(block.rows.size - 1) { Box(Modifier.background(styles.rule)) }
        },
        // The cells scroll inside the hairline, so the border never cuts through them.
        modifier = modifier
            .fillMaxWidth()
            .border(Hairline, styles.rule, shape)
            .padding(Hairline)
            .clip(shape)
            .horizontalScroll(rememberScrollState()),
        measurePolicy = remember(block.columns, block.rows.size, block.hasHeader) {
            TablePolicy(block.columns, block.rows.size, block.hasHeader)
        },
    )
}

/**
 * Children: the header background (when there is a header row), the cells row by row, then
 * one divider per row boundary. Inside horizontalScroll the max width is unbounded but the
 * min width is still the viewport's (from fillMaxWidth), which is what the table stretches to.
 */
private class TablePolicy(
    private val columns: Int,
    private val rows: Int,
    private val hasHeader: Boolean,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val first = if (hasHeader) 1 else 0
        val cellCount = rows * columns
        val maxColumn = MaxColumnWidth.roundToPx()

        val widths = IntArray(columns)
        for (i in 0 until cellCount) {
            val c = i % columns
            val natural = measurables[first + i].maxIntrinsicWidth(Constraints.Infinity)
            widths[c] = maxOf(widths[c], minOf(natural, maxColumn))
        }
        var total = widths.sum()
        if (total in 1 until constraints.minWidth) {
            val extra = constraints.minWidth - total
            var given = 0
            for (c in 0 until columns) {
                val share = if (c == columns - 1) extra - given else (extra.toLong() * widths[c] / total).toInt()
                widths[c] += share
                given += share
            }
            total = constraints.minWidth
        }

        val cells = arrayOfNulls<Placeable>(cellCount)
        val heights = IntArray(rows)
        for (i in 0 until cellCount) {
            val placeable = measurables[first + i].measure(Constraints.fixedWidth(widths[i % columns]))
            cells[i] = placeable
            heights[i / columns] = maxOf(heights[i / columns], placeable.height)
        }
        val line = maxOf(1, Hairline.roundToPx())
        val headerBackground = if (hasHeader && rows > 0) measurables[0].measure(Constraints.fixed(total, heights[0])) else null
        val dividers = Array(maxOf(rows - 1, 0)) { measurables[first + cellCount + it].measure(Constraints.fixed(total, line)) }
        val height = heights.sum() + line * dividers.size

        return layout(total, height) {
            headerBackground?.place(0, 0)
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    cells[r * columns + c]!!.placeRelative(x, y)
                    x += widths[c]
                }
                y += heights[r]
                if (r < dividers.size) {
                    dividers[r].place(0, y)
                    y += line
                }
            }
        }
    }
}
