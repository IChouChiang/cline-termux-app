// Adapted from cline-termux-voice (same owner): android/app/src/main/kotlin/dev/cline/voice/ui/markdown/MarkdownModel.kt
package dev.cline.termux.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import dev.cline.termux.ui.theme.clineMono
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/*
 * The parsed form of one reply. CommonMark's node tree is mutable and walking it in
 * composition would redo work on every recomposition, so it is read once into these
 * immutable classes and thrown away. Nothing here depends on the theme: inline text
 * carries only weight, slant, decoration and the monospace face; the inline-code chips and
 * link colours are laid on at render time, so a light/dark switch never re-parses.
 */

/** A whole reply: its top-level blocks in reading order. */
@Immutable
internal class MdDocument(val blocks: List<MdBlock>) {
    /**
     * The reply's own base direction, like the desktop's `dir="auto"` on the Markdown
     * container: right-to-left when the first strong character of its text is Hebrew or
     * Arabic, otherwise left-to-right (also when it has no strong character at all). Model
     * output is data (DS §8.2.8), so an English or Chinese answer keeps its left-to-right
     * tables, bullets and quote rules inside an RTL app, and only the app chrome mirrors
     * (DS §8.2.6). Code is skipped: it is laid out left-to-right whatever the reply is in.
     */
    val isRtl: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { firstStrongIsRtl(blocks) == true }

    companion object {
        val Empty = MdDocument(emptyList())
    }
}

/**
 * A link destination. [deceptive] (desktop `isDeceptiveLink`, HC §3): the visible text reads as
 * a URL whose host, port or stated scheme differs from [url], so a tap asks first.
 */
@Immutable
internal data class MdLink(val url: String, val deceptive: Boolean)

/**
 * What a Markdown image reads as. Images are never loaded (HC §3, desktop `MarkdownImage`: no
 * hidden requests from model-authored Markdown); the run shows [blocked], or [blockedWithAlt]
 * (a `%1$s` format) when the image has alt text.
 */
@Immutable
internal data class MdImageLabels(val blocked: String, val blockedWithAlt: String) {
    fun of(alt: String): String = alt.trim().let { if (it.isEmpty()) blocked else blockedWithAlt.format(it) }
}

/**
 * A run of inline Markdown already flattened to styled text. [code] marks inline code spans
 * (drawn as rounded chips when rendered) and [links] the ranges that open a URL.
 * `text.text` is the plain reading of the run: markers gone, links reduced to their text.
 */
@Immutable
internal class MdInline(
    val text: AnnotatedString,
    val code: List<TextRange>,
    val links: List<AnnotatedString.Range<MdLink>>,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val Empty = MdInline(AnnotatedString(""), emptyList(), emptyList())
        fun plain(text: String) = MdInline(AnnotatedString(text), emptyList(), emptyList())
    }
}

@Immutable
internal sealed interface MdBlock {
    @Immutable
    class Paragraph(val inline: MdInline) : MdBlock

    @Immutable
    class Heading(val level: Int, val inline: MdInline) : MdBlock

    /** A bullet or ordered list. [tight] lists keep their items close, like lines of one paragraph. */
    @Immutable
    class ListBlock(val ordered: Boolean, val tight: Boolean, val items: List<MdListItem>) : MdBlock

    @Immutable
    class Quote(val blocks: List<MdBlock>) : MdBlock

    /**
     * A fenced or indented code block. [language] is the first word of the fence info, or
     * empty. [code] is the source as written (what the copy button copies); [display] is the
     * same with tabs expanded, because Android lays a tab out as 20 pixels, which on a phone
     * screen is narrower than one character and flattens tab-indented code (Go, Makefiles).
     */
    @Immutable
    class Code(val language: String, val code: String) : MdBlock {
        val display: String = expandTabs(code)
    }

    /**
     * A GFM table, every row padded to [columns] cells. When [hasHeader] is set the first row
     * is the header row.
     */
    @Immutable
    class Table(
        val columns: Int,
        val alignments: List<MdAlign>,
        val rows: List<List<MdInline>>,
        val hasHeader: Boolean,
    ) : MdBlock

    data object Rule : MdBlock
}

/**
 * One list item. [marker] is what sits in the hanging-indent gutter: a bullet chosen by
 * nesting depth, the item's number with the list's own delimiter, or (in a bullet list) a
 * task box. A numbered task item keeps its number and carries the box in its text.
 */
@Immutable
internal class MdListItem(val marker: String, val task: MdTask?, val blocks: List<MdBlock>)

internal enum class MdTask { Open, Done }

internal enum class MdAlign { Start, Center, End }

/**
 * The direction of the first strong character in [blocks], in reading order: true for
 * right-to-left (Hebrew, Arabic and the other R/AL scripts), false for left-to-right (Latin,
 * CJK, Cyrillic...), null when there is none. Code blocks and inline code are skipped, the
 * way an element with its own `dir` is skipped by the browser's `dir="auto"`; list markers are
 * bullets and numbers, which are never strong.
 */
internal fun firstStrongIsRtl(blocks: List<MdBlock>): Boolean? {
    for (block in blocks) {
        val found = when (block) {
            is MdBlock.Paragraph -> block.inline.firstStrongIsRtl()
            is MdBlock.Heading -> block.inline.firstStrongIsRtl()
            is MdBlock.ListBlock -> block.items.firstNotNullOfOrNull { firstStrongIsRtl(it.blocks) }
            is MdBlock.Quote -> firstStrongIsRtl(block.blocks)
            is MdBlock.Table -> block.rows.firstNotNullOfOrNull { row -> row.firstNotNullOfOrNull { it.firstStrongIsRtl() } }
            is MdBlock.Code, MdBlock.Rule -> null
        }
        if (found != null) return found
    }
    return null
}

private fun MdInline.firstStrongIsRtl(): Boolean? = firstStrongIsRtl(text.text, code)

/** [firstStrongIsRtl] for one run of text, leaving out the [skip] ranges (inline code). */
internal fun firstStrongIsRtl(text: String, skip: List<TextRange> = emptyList()): Boolean? {
    var i = 0
    while (i < text.length) {
        val skipped = skip.firstOrNull { i >= it.min && i < it.max }
        if (skipped != null) {
            i = skipped.max
            continue
        }
        val codePoint = text.codePointAt(i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return true
        }
        i += Character.charCount(codePoint)
    }
    return null
}

/**
 * Parsing entry point. The CommonMark parser is thread-safe and built once; finished replies
 * are kept in a [DocumentCache] so a reply that scrolls out of a lazy list and back, or that
 * feeds both the chat and a notification, is parsed once. A reply that is still streaming
 * goes through [IncrementalMarkdown] instead and never enters the cache, so its prefixes
 * cannot push the finished replies out (DS §6 "Streaming text").
 */
internal object MarkdownParser {
    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    // Single tildes stay literal: Cline writes "~2s" and "~5 files" as "about",
                    // and GFM's single-tilde form would strike the text between them.
                    StrikethroughExtension.builder().requireTwoTildes(true).build(),
                    AutolinkExtension.create(),
                ),
            )
            .build()
    }

    /** About 400k characters of source: dozens of ordinary replies, or ten very long ones. */
    const val CACHE_MAX_CHARS = 400_000

    private val documents = DocumentCache(maxChars = CACHE_MAX_CHARS)

    /**
     * The parsed [markdown]. With [images] every image reads as its blocked-image notice; without
     * (the notification plain text) an image reads as its alt text. With [cache] false the result
     * is neither looked up nor stored.
     */
    fun document(markdown: String, images: MdImageLabels? = null, cache: Boolean = true): MdDocument {
        if (!cache) return MdDocument(blocks(markdown, images))
        documents.get(markdown, images)?.let { return it }
        val document = MdDocument(blocks(markdown, images))
        documents.put(markdown, images, document)
        return document
    }

    /** The cached document for [markdown], or null, without parsing (cheap enough for composition). */
    fun cachedDocument(markdown: String, images: MdImageLabels? = null): MdDocument? = documents.get(markdown, images)

    /** The top-level blocks of [markdown], uncached. */
    fun blocks(markdown: String, images: MdImageLabels?): List<MdBlock> =
        BlockReader(images).blocks(parser.parse(markdown), bulletDepth = 0)
}

/**
 * An access-ordered LRU of parsed documents bounded by the total length of their sources, not
 * by entry count, so a few very long replies cannot hold megabytes of parsed blocks while many
 * short ones still fit. Each entry costs its length plus [ENTRY_OVERHEAD]; a source longer
 * than [maxChars] on its own is never stored. A plain LinkedHashMap rather than
 * android.util.LruCache so it runs in JVM unit tests; access is serialised because
 * notifications parse off the main thread.
 */
internal class DocumentCache(private val maxChars: Int) {
    private val entries = LinkedHashMap<Pair<String, MdImageLabels?>, MdDocument>(16, 0.75f, true)

    /** The summed cost of every stored entry. */
    var chars: Int = 0
        @Synchronized get
        private set

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun get(markdown: String, images: MdImageLabels?): MdDocument? = entries[markdown to images]

    @Synchronized
    fun put(markdown: String, images: MdImageLabels?, document: MdDocument) {
        val cost = cost(markdown)
        if (cost > maxChars) return
        if (entries.put(markdown to images, document) != null) chars -= cost
        chars += cost
        val iterator = entries.entries.iterator()
        while (chars > maxChars && iterator.hasNext()) {
            chars -= cost(iterator.next().key.first)
            iterator.remove()
        }
    }

    private fun cost(markdown: String): Int = markdown.length + ENTRY_OVERHEAD

    companion object {
        const val ENTRY_OVERHEAD = 64
    }
}

/**
 * Parses one streaming reply a block at a time (DS §6 "Streaming text": Markdown re-parses
 * only the last block). Each call takes the reply so far; the part before the last top-level
 * block boundary ([stableBoundary]) is parsed once, segment by segment as boundaries appear,
 * and only the text after it is parsed again. When the text does not extend what was scanned
 * before (a replaced message), the state starts over. One instance per reply on screen; it
 * never touches the [MarkdownParser] cache. Calls are serialised, so it may be fed from the
 * main thread and from `Dispatchers.Default`. Two rare constructs settle only on the finished
 * reply's full parse: a link reference definition below the boundary that a link above it
 * uses, and raw HTML (`<pre>`, comments) that spans a blank line.
 */
internal class IncrementalMarkdown(private val images: MdImageLabels?) {
    private var last = ""
    private var lastDocument: MdDocument? = null
    private val scan = BoundaryScan()
    private var stableEnd = 0
    private var stableBlocks: List<MdBlock> = emptyList()

    /** How many leading characters are parsed and kept. */
    val stableLength: Int
        @Synchronized get() = stableEnd

    @Synchronized
    fun document(markdown: String): MdDocument {
        lastDocument?.takeIf { markdown == last }?.let { return it }
        if (markdown.length < scan.scanned || !markdown.regionMatches(0, last, 0, scan.scanned)) {
            scan.reset()
            stableEnd = 0
            stableBlocks = emptyList()
        }
        last = markdown
        val boundary = scan.advance(markdown)
        if (boundary > stableEnd) {
            stableBlocks = stableBlocks + MarkdownParser.blocks(markdown.substring(stableEnd, boundary), images)
            stableEnd = boundary
        }
        val tail = if (stableEnd < markdown.length) MarkdownParser.blocks(markdown.substring(stableEnd), images) else emptyList()
        return MdDocument(if (tail.isEmpty()) stableBlocks else stableBlocks + tail).also { lastDocument = it }
    }
}

/**
 * The offset of the last top-level block boundary in [markdown], or 0 when there is none yet:
 * the text before it parses the same on its own as it does as part of the whole, whatever is
 * appended later. [IncrementalMarkdown] applies the same rules incrementally.
 */
internal fun stableBoundary(markdown: String): Int = BoundaryScan().advance(markdown)

/**
 * Line scanner behind [stableBoundary]. A boundary is the start of a complete line that
 * follows a blank line outside any code fence, begins in column 0 and is not a list item: an
 * indented line could continue the block above (a list item, indented code), and a list item
 * could join the list above and make it loose. Only complete lines are scanned, so a line
 * still being streamed never decides anything.
 */
private class BoundaryScan {
    /** Start of the first line not yet scanned. */
    var scanned = 0
        private set
    private var boundary = 0
    private var previousBlank = false
    private var fenceChar = '`'
    private var fenceLength = 0
    private var fenceIndent = 0

    fun reset() {
        scanned = 0
        boundary = 0
        previousBlank = false
        fenceLength = 0
    }

    fun advance(markdown: String): Int {
        var start = scanned
        while (true) {
            val end = markdown.indexOf('\n', start)
            if (end < 0) break
            line(markdown, start, end)
            start = end + 1
        }
        scanned = start
        return boundary
    }

    private fun line(text: String, start: Int, end: Int) {
        var indent = 0
        var at = start
        while (at < end && (text[at] == ' ' || text[at] == '\t')) {
            indent += if (text[at] == '\t') 4 - indent % 4 else 1
            at++
        }
        val blank = at == end || (at == end - 1 && text[at] == '\r')
        if (fenceLength > 0) {
            if (!blank && closesFence(text, at, end, indent)) fenceLength = 0
            previousBlank = false
            return
        }
        if (blank) {
            previousBlank = true
            return
        }
        if (previousBlank && indent == 0 && !isListMarker(text, at, end)) boundary = start
        previousBlank = false
        opensFence(text, at, end, indent)
    }

    private fun runLength(text: String, from: Int, end: Int, char: Char): Int {
        var i = from
        while (i < end && text[i] == char) i++
        return i - from
    }

    // Any indentation opens a fence here (fences inside list items are indented); that errs
    // towards fewer boundaries, never towards a split inside a fence.
    private fun opensFence(text: String, at: Int, end: Int, indent: Int) {
        val char = text[at]
        if (char != '`' && char != '~') return
        val length = runLength(text, at, end, char)
        if (length < 3) return
        // A backtick fence's info string cannot contain a backtick (CommonMark 4.5).
        if (char == '`' && text.indexOf('`', at + length) in (at + length) until end) return
        fenceChar = char
        fenceLength = length
        fenceIndent = indent
    }

    private fun closesFence(text: String, at: Int, end: Int, indent: Int): Boolean {
        if (text[at] != fenceChar || indent >= fenceIndent + 4) return false
        val length = runLength(text, at, end, fenceChar)
        if (length < fenceLength) return false
        for (k in at + length until end) if (!text[k].isWhitespace()) return false
        return true
    }

    private fun isListMarker(text: String, at: Int, end: Int): Boolean {
        val first = text[at]
        val after = when {
            first == '-' || first == '+' || first == '*' -> at + 1
            first.isDigit() -> {
                var k = at
                while (k < end && text[k].isDigit()) k++
                if (k - at > 9 || k >= end || (text[k] != '.' && text[k] != ')')) return false
                k + 1
            }
            else -> return false
        }
        return after >= end || text[after] == ' ' || text[after] == '\t' || text[after] == '\r'
    }
}

private val Bullets = arrayOf("•", "◦", "▪")
private const val TaskOpen = "☐"

// U+FE0E asks for the text presentation, so the box stays a monochrome glyph instead of
// the colour emoji some Android fonts map U+2611 to.
private const val TaskDone = "☑︎"

private val TaskPrefix = Regex("""^\[([ xX])](?:[ \t]+|$)""")

private class BlockReader(private val images: MdImageLabels?) {
    fun blocks(parent: Node, bulletDepth: Int): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var node = parent.firstChild
        while (node != null) {
            read(node, bulletDepth)?.let(out::add)
            node = node.next
        }
        return out
    }

    private fun read(node: Node, bulletDepth: Int): MdBlock? = when (node) {
        is Paragraph -> InlineReader(images).read(node).takeUnless { it.isEmpty }?.let { MdBlock.Paragraph(it) }
        is Heading -> InlineReader(images).read(node).takeUnless { it.isEmpty }
            ?.let { MdBlock.Heading(node.level.coerceIn(1, 6), it) }
        is ListBlock -> list(node, bulletDepth)
        is BlockQuote -> blocks(node, bulletDepth).takeIf { it.isNotEmpty() }?.let { MdBlock.Quote(it) }
        is FencedCodeBlock -> MdBlock.Code(
            language = node.info.orEmpty().trim().takeWhile { !it.isWhitespace() },
            code = node.literal.orEmpty().trimEnd('\n'),
        )
        is IndentedCodeBlock -> MdBlock.Code(language = "", code = node.literal.orEmpty().trimEnd('\n'))
        is ThematicBreak -> MdBlock.Rule
        // Raw HTML is shown as the literal source, except comments, which no renderer shows.
        is HtmlBlock -> node.literal.orEmpty().trimEnd('\n').takeUnless { it.isBlank() || it.isHtmlComment() }
            ?.let { MdBlock.Paragraph(MdInline.plain(it)) }
        is TableBlock -> table(node)
        // Link reference definitions and anything unknown render nothing.
        else -> null
    }

    private fun list(node: ListBlock, bulletDepth: Int): MdBlock? {
        val ordered = node is OrderedList
        var number = (node as? OrderedList)?.markerStartNumber ?: 1
        val delimiter = (node as? OrderedList)?.markerDelimiter ?: "."
        val bullet = Bullets[bulletDepth % Bullets.size]
        val childDepth = if (ordered) bulletDepth else bulletDepth + 1
        val items = ArrayList<MdListItem>()
        var child = node.firstChild
        while (child != null) {
            if (child is ListItem) {
                val task = takeTaskMarker(child, boxInText = ordered)
                val marker = when {
                    ordered -> "$number$delimiter"
                    task == MdTask.Done -> TaskDone
                    task == MdTask.Open -> TaskOpen
                    else -> bullet
                }
                items += MdListItem(marker, task, blocks(child, childDepth))
                number++
            }
            child = child.next
        }
        return if (items.isEmpty()) null else MdBlock.ListBlock(ordered, node.isTight, items)
    }

    /**
     * GFM task items ("- [ ] todo", "- [x] done") without the task-list extension: the box is
     * the start of the item's first paragraph. Strips it from the text and reports its state.
     * A numbered task keeps its number as the marker, so with [boxInText] the box glyph
     * replaces the brackets at the start of the text instead.
     */
    private fun takeTaskMarker(item: ListItem, boxInText: Boolean): MdTask? {
        val paragraph = item.firstChild as? Paragraph ?: return null
        val first = paragraph.firstChild as? Text ?: return null
        // The inline parser may leave "[" and " ] todo" as sibling text nodes; join them first.
        while (true) {
            val next = first.next as? Text ?: break
            first.literal = first.literal + next.literal
            next.unlink()
        }
        val match = TaskPrefix.find(first.literal) ?: return null
        val task = if (match.groupValues[1] == " ") MdTask.Open else MdTask.Done
        val rest = first.literal.substring(match.value.length)
        first.literal = if (boxInText) "${if (task == MdTask.Done) TaskDone else TaskOpen} $rest".trimEnd() else rest
        return task
    }

    private fun table(node: TableBlock): MdBlock? {
        val rows = ArrayList<List<MdInline>>()
        var alignments: List<MdAlign> = emptyList()
        var hasHeader = false
        // TableBlock > TableHead | TableBody > TableRow > TableCell
        var section = node.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                val cells = ArrayList<MdInline>()
                val aligns = ArrayList<MdAlign>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        if (rows.isEmpty() && cell.isHeader) hasHeader = true
                        cells += InlineReader(images).read(cell)
                        aligns += when (cell.alignment) {
                            TableCell.Alignment.CENTER -> MdAlign.Center
                            TableCell.Alignment.RIGHT -> MdAlign.End
                            else -> MdAlign.Start
                        }
                    }
                    cell = cell.next
                }
                if (rows.isEmpty()) alignments = aligns
                rows += cells
                row = row.next
            }
            section = section.next
        }
        val columns = rows.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return null
        val padded = rows.map { cells ->
            if (cells.size == columns) cells else cells + List(columns - cells.size) { MdInline.Empty }
        }
        val aligns = alignments + List((columns - alignments.size).coerceAtLeast(0)) { MdAlign.Start }
        return MdBlock.Table(columns, aligns, padded, hasHeader)
    }
}

// No colour and no background: the renderer draws inline code as a rounded chip behind the
// letters, which a span background cannot do. The face, tracking and "calt 0" (no ligatures,
// DS §2.2, §8.1.12) are read from ClineMono so they cannot drift from it; the size stays relative
// to the paragraph, and the weight is left out so `**`code`**` stays SemiBold.
internal val CodeSpan: SpanStyle = clineMono().body.let { mono ->
    SpanStyle(
        fontFamily = mono.fontFamily,
        fontSize = 0.85.em,
        letterSpacing = mono.letterSpacing,
        fontFeatureSettings = mono.fontFeatureSettings,
    )
}
private val Italic = SpanStyle(fontStyle = FontStyle.Italic)
private val Strong = SpanStyle(fontWeight = FontWeight.SemiBold)
private val Strike = SpanStyle(textDecoration = TextDecoration.LineThrough)

/** U+2066 LEFT-TO-RIGHT ISOLATE / U+2069 POP DIRECTIONAL ISOLATE around inline code. */
private const val LRI = '\u2066'
private const val PDI = '\u2069'

/** Flattens one block's inline children into an [MdInline]. One instance per block. */
private class InlineReader(private val images: MdImageLabels?) {
    private val out = AnnotatedString.Builder()
    private val code = ArrayList<TextRange>(0)
    private val links = ArrayList<AnnotatedString.Range<MdLink>>(0)
    private var inLink = false
    private var last: Char? = null

    fun read(node: Node): MdInline {
        children(node)
        return MdInline(out.toAnnotatedString(), code, links)
    }

    private fun children(node: Node) {
        var child = node.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
    }

    private fun put(text: String) {
        if (text.isEmpty()) return
        out.append(text)
        last = text.last()
    }

    private fun styled(style: SpanStyle, node: Node) {
        val index = out.pushStyle(style)
        children(node)
        out.pop(index)
    }

    private fun visit(node: Node) {
        when (node) {
            is Text -> put(node.literal)
            is Code -> if (node.literal.isNotEmpty()) {
                // DS §8.2.6: code is forced LTR, so an RTL paragraph never reorders the spaces,
                // dashes, slashes and dots inside it. The isolates sit outside the chip range and
                // do not count as the last character for the CJK soft-break rule.
                out.append(LRI)
                val start = out.length
                val index = out.pushStyle(CodeSpan)
                put(node.literal)
                out.pop(index)
                code += TextRange(start, out.length)
                out.append(PDI)
            }
            is Emphasis -> styled(Italic, node)
            is StrongEmphasis -> styled(Strong, node)
            is Strikethrough -> styled(Strike, node)
            is Link -> link(node.destination, node)
            is Image -> if (images == null) link(node.destination, node) else blockedImage(node, images)
            is SoftLineBreak -> if (spaceBetween(last, firstChar(node.next))) put(" ")
            is HardLineBreak -> put("\n")
            is HtmlInline -> if (!node.literal.isHtmlComment()) put(node.literal)
            else -> children(node)
        }
    }

    /**
     * A link's (or an image's alt) text, clickable when the destination is something the
     * phone can open. An empty label shows the destination itself. Links cannot nest, so
     * an image inside a link keeps the outer link's target.
     */
    private fun link(destination: String?, node: Node) {
        val start = out.length
        val outer = inLink
        inLink = true
        children(node)
        inLink = outer
        if (out.length == start) put(destination.orEmpty())
        val url = openableUrl(destination)
        if (url != null && !outer && out.length > start) {
            val label = out.toAnnotatedString().text.substring(start, out.length).filterNot { it == LRI || it == PDI }
            links += AnnotatedString.Range(MdLink(url, isDeceptiveLink(label, url)), start, out.length)
        }
    }

    /** An image is never fetched: it reads as the blocked-image notice, in italics (HC §3). */
    private fun blockedImage(node: Image, labels: MdImageLabels) {
        val index = out.pushStyle(Italic)
        put(labels.of(plainText(node)))
        out.pop(index)
    }
}

/** The literal text under [node] (an image's alt text), markers dropped. */
private fun plainText(node: Node): String {
    val out = StringBuilder()
    fun walk(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                is SoftLineBreak, is HardLineBreak -> out.append(' ')
                else -> walk(child)
            }
            child = child.next
        }
    }
    walk(node)
    return out.toString()
}

// Desktop `urlLikeTextPattern`: a label that reads as a URL (one trailing dot after the TLD and a
// protocol-relative "//" prefix tolerated).
private val UrlLikeText = Regex("""^(?:https?://|//)?(?:[\w-]+\.)+[a-z]{2,}\.?(?:[/:?#]\S*)?$""", RegexOption.IGNORE_CASE)

private class LinkParts(val scheme: String, val host: String, val port: Int, val explicitScheme: Boolean)

/** Desktop `parseLinkParts`: scheme, host (lower case, trailing dots and `www.` dropped) and port. */
private fun linkParts(value: String): LinkParts? {
    val explicit = Scheme.containsMatchIn(value)
    val withScheme = when {
        explicit -> value
        value.startsWith("//") -> "https:$value"
        else -> "https://$value"
    }
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: return null
    // A URL parser reports the scheme's default port as no port at all.
    val port = when {
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    return LinkParts(scheme, host, port, explicit)
}

/**
 * Desktop `isDeceptiveLink`: the [label] reads as a URL that does not match [url] on host and
 * port, or on scheme when the label states one. Ordinary links (any other label) open directly.
 */
internal fun isDeceptiveLink(label: String, url: String): Boolean {
    val text = label.trim()
    if (text.isEmpty() || !UrlLikeText.matches(text)) return false
    val textParts = linkParts(text) ?: return false
    val urlParts = linkParts(url) ?: return true
    return textParts.host != urlParts.host ||
        textParts.port != urlParts.port ||
        (textParts.explicitScheme && textParts.scheme != urlParts.scheme)
}

private val Scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
private val OpenableSchemes = setOf("http", "https", "mailto", "tel")

/**
 * The URL to open for a link destination, or null when the phone has nothing to open it
 * with. Cline often links repository paths ("src/Main.kt"); those read as plain text rather
 * than as a link that does nothing.
 */
private fun openableUrl(destination: String?): String? {
    // A protocol-relative destination is pinned to https, as the desktop does.
    val url = destination?.trim().orEmpty().let { if (it.startsWith("//")) "https:$it" else it }
    val scheme = Scheme.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
    return url.takeIf { scheme in OpenableSchemes }
}

private fun String.isHtmlComment(): Boolean {
    val text = trim()
    return text.startsWith("<!--") && text.endsWith("-->")
}

/** The first character a node would print, looking through inline containers. */
private fun firstChar(node: Node?): Char? {
    var current = node
    while (current != null) {
        val char = when (current) {
            is Text -> current.literal.firstOrNull()
            is Code -> current.literal.firstOrNull()
            is HtmlInline -> current.literal.firstOrNull()
            is SoftLineBreak, is HardLineBreak -> return null
            else -> firstChar(current.firstChild)
        }
        if (char != null) return char
        current = current.next
    }
    return null
}

/**
 * Whether a soft line break prints as a space. CommonMark says it does, which is right for
 * English but puts a stray gap into Chinese that was wrapped mid-sentence. No space when
 * both sides are ideographs or kana, or when either side is full-width punctuation.
 */
internal fun spaceBetween(before: Char?, after: Char?): Boolean {
    if (before == null || after == null) return false
    if (before.isFullWidthPunctuation() || after.isFullWidthPunctuation()) return false
    return !(before.isCjkLetter() && after.isCjkLetter())
}

private fun Char.isCjkLetter(): Boolean = when (Character.UnicodeScript.of(code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> false
}

private fun Char.isFullWidthPunctuation(): Boolean =
    code in 0x3000..0x303F || code in 0xFF00..0xFF65 || code in 0xFE30..0xFE4F

private const val TabWidth = 4

/** [code] with each tab widened to the next multiple of [TabWidth] columns on its line. */
private fun expandTabs(code: String): String {
    if ('\t' !in code) return code
    val out = StringBuilder(code.length + 16)
    var column = 0
    for (char in code) {
        when (char) {
            '\t' -> {
                val spaces = TabWidth - column % TabWidth
                repeat(spaces) { out.append(' ') }
                column += spaces
            }
            '\n' -> {
                out.append(char)
                column = 0
            }
            else -> {
                out.append(char)
                column++
            }
        }
    }
    return out.toString()
}
