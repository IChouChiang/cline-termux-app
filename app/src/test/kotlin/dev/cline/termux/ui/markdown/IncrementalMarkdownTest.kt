package dev.cline.termux.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalMarkdownTest {

    private val labels = MdImageLabels("blocked", "blocked: %1\$s")

    /** A structural reading of blocks: kinds, list shape, code and the plain text of every run. */
    private fun dump(blocks: List<MdBlock>): String = buildString { dumpTo(blocks, this, "") }

    private fun dumpTo(blocks: List<MdBlock>, out: StringBuilder, indent: String) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> out.append(indent).append("P ").append(block.inline.text.text)
                    .append(" code=").append(block.inline.code).append(" links=").append(block.inline.links.map { it.item })
                is MdBlock.Heading -> out.append(indent).append("H").append(block.level).append(' ').append(block.inline.text.text)
                is MdBlock.ListBlock -> {
                    out.append(indent).append("L ordered=").append(block.ordered).append(" tight=").append(block.tight).append('\n')
                    for (item in block.items) {
                        out.append(indent).append("  * ").append(item.marker).append(' ').append(item.task).append('\n')
                        dumpTo(item.blocks, out, "$indent    ")
                    }
                    continue
                }
                is MdBlock.Quote -> {
                    out.append(indent).append("Q\n")
                    dumpTo(block.blocks, out, "$indent  ")
                    continue
                }
                is MdBlock.Code -> out.append(indent).append("C[").append(block.language).append("] ").append(block.code)
                is MdBlock.Table -> out.append(indent).append("T ").append(block.columns).append(' ').append(block.hasHeader)
                    .append(' ').append(block.alignments).append(' ').append(block.rows.map { row -> row.map { it.text.text } })
                MdBlock.Rule -> out.append(indent).append("R")
            }
            out.append('\n')
        }
    }

    private fun full(markdown: String) = dump(MarkdownParser.document(markdown, labels, cache = false).blocks)

    private val replies = listOf(
        """
        # Deployment finished

        I wrote the **WireGuard** config to `S25-WireGuard` and restarted the *tunnel*. See the
        [quick start](https://www.wireguard.com/quickstart/) or https://example.com/status.

        ## What changed

        1. Generated a new key pair
        2. Updated the peer list:
           - `phone` → 10.0.0.2

             with a second paragraph
           - `laptop` → 10.0.0.3
        3. Restarted the service

        - [x] Generate keys
        - [ ] Open 51820/udp

        A loose list:

        - First item

        - Second item

        Done.

        > **Note:** the tunnel may drop.
        >
        > Set Termux to Unrestricted.

        > A second quote.

        ```bash
        wg-quick down wg0

        wg-quick up wg0
        ```

        ~~~
        tilde fence

        ```
        not a closer
        ~~~

            indented code

            after a blank line

        | Device | Address |
        |:-------|--------:|
        | phone | 10.0.0.2 |

        ---

        ![topology](https://example.com/topology.png)
        Last line.
        """.trimIndent(),
        "Plain one paragraph.\n\nTwo.\n\n* * *\n\nThree\n===\n\n10) ten\n11) eleven\n\n12) twelve\n\nEnd",
        "Intro:\n\n1. step\n\n   ```kotlin\n   val x = 1\n\n   val y = 2\n   ```\n\n2. next\n\nOutro\r\n\r\nWindows lines\r\n",
    )

    @Test
    fun `every streamed prefix renders the same as parsing it whole`() {
        for (reply in replies) {
            val incremental = IncrementalMarkdown(labels)
            for (end in 1..reply.length) {
                val prefix = reply.substring(0, end)
                assertEquals("prefix of length $end", full(prefix), dump(incremental.document(prefix).blocks))
            }
            assertTrue("a boundary was found", incremental.stableLength > 0)
        }
    }

    @Test
    fun `the stable prefix only grows and stops at the last boundary`() {
        val incremental = IncrementalMarkdown(labels)
        incremental.document("First paragraph.\n\nSecond")
        assertEquals(0, incremental.stableLength)
        incremental.document("First paragraph.\n\nSecond paragraph.\n")
        assertEquals("First paragraph.\n\n".length, incremental.stableLength)
        incremental.document("First paragraph.\n\nSecond paragraph.\n\nThird.\n")
        assertEquals("First paragraph.\n\nSecond paragraph.\n\n".length, incremental.stableLength)
    }

    @Test
    fun `the same text returns the same document and a replaced text starts over`() {
        val incremental = IncrementalMarkdown(labels)
        val first = incremental.document("One.\n\nTwo.\n")
        assertSame(first, incremental.document("One.\n\nTwo.\n"))
        val replaced = incremental.document("Other.\n\nText.\n")
        assertNotSame(first, replaced)
        assertEquals(full("Other.\n\nText.\n"), dump(replaced.blocks))
        val shorter = incremental.document("Oth")
        assertEquals(full("Oth"), dump(shorter.blocks))
        assertEquals(0, incremental.stableLength)
    }

    @Test
    fun `streaming never fills the parser cache`() {
        val text = "Streamed only.\n\nNever cached ${System.nanoTime()}.\n"
        IncrementalMarkdown(labels).document(text)
        assertNull(MarkdownParser.cachedDocument(text, labels))
    }

    @Test
    fun `a boundary needs a blank line, column 0, no list marker and no open fence`() {
        assertEquals(0, stableBoundary("one line\n"))
        assertEquals(0, stableBoundary("para\ncontinued\n"))
        assertEquals("para\n\n".length, stableBoundary("para\n\nnext\n"))
        // The line after the blank must be complete.
        assertEquals(0, stableBoundary("para\n\nnex"))
        // Indented lines and list items may continue the block above.
        assertEquals(0, stableBoundary("- item\n\n  more\n"))
        assertEquals(0, stableBoundary("- a\n\n- b\n"))
        assertEquals(0, stableBoundary("1. a\n\n2) b\n"))
        assertEquals(0, stableBoundary("    code\n\n    code\n"))
        assertEquals("- a\n\n".length, stableBoundary("- a\n\nafter the list\n"))
        // Inside a fence a blank line is content.
        assertEquals(0, stableBoundary("```\ncode\n\nmore\n"))
        assertEquals("```\ncode\n\nmore\n```\n\n".length, stableBoundary("```\ncode\n\nmore\n```\n\nafter\n"))
        // A shorter run or another character does not close a fence; a backtick info string is no fence.
        assertEquals(0, stableBoundary("````\n```\n\ninside\n"))
        assertEquals(0, stableBoundary("~~~\n```\n\ninside\n"))
        assertEquals("``` a`b\n\n".length, stableBoundary("``` a`b\n\nafter\n"))
        // A line of digits without a delimiter or a dash without a space is not a list item.
        assertEquals("p\n\n".length, stableBoundary("p\n\n2026 was a year\n"))
        assertEquals("p\n\n".length, stableBoundary("p\n\n---\n"))
    }

    @Test
    fun `the document cache is bounded by characters and evicts the least recently used`() {
        val overhead = DocumentCache.ENTRY_OVERHEAD
        val cache = DocumentCache(maxChars = 3 * (100 + overhead))
        val a = "a".repeat(100)
        val b = "b".repeat(100)
        val c = "c".repeat(100)
        val d = "d".repeat(100)
        cache.put(a, null, MdDocument.Empty)
        cache.put(b, null, MdDocument.Empty)
        cache.put(c, null, MdDocument.Empty)
        assertEquals(3, cache.size)
        // Touch a, so b is now the eldest.
        assertSame(MdDocument.Empty, cache.get(a, null))
        cache.put(d, null, MdDocument.Empty)
        assertEquals(3, cache.size)
        assertNull(cache.get(b, null))
        assertEquals(3 * (100 + overhead), cache.chars)
        // Storing the same key again costs nothing extra.
        cache.put(d, null, MdDocument.Empty)
        assertEquals(3 * (100 + overhead), cache.chars)
        // One long text evicts several short ones, eldest first: c, then a; d was used last.
        cache.put("e".repeat(250), null, MdDocument.Empty)
        assertEquals(2, cache.size)
        assertNull(cache.get(c, null))
        assertNull(cache.get(a, null))
        assertSame(MdDocument.Empty, cache.get(d, null))
        assertEquals(100 + overhead + 250 + overhead, cache.chars)
        // A text larger than the whole budget is never stored.
        cache.put("f".repeat(10_000), null, MdDocument.Empty)
        assertNull(cache.get("f".repeat(10_000), null))
        assertEquals(2, cache.size)
    }

    @Test
    fun `many short replies outlive one long reply in the parser cache`() {
        val shorts = (0 until 200).map { "Short reply $it ${System.nanoTime()}." }
        shorts.forEach { MarkdownParser.document(it, labels) }
        shorts.forEach { assertTrue(MarkdownParser.cachedDocument(it, labels) != null) }
    }

    @Test
    fun `an uncached parse is not stored`() {
        val text = "uncached ${System.nanoTime()}"
        MarkdownParser.document(text, labels, cache = false)
        assertNull(MarkdownParser.cachedDocument(text, labels))
        val cached = MarkdownParser.document(text, labels)
        assertSame(cached, MarkdownParser.cachedDocument(text, labels))
        assertFalse(cached.blocks.isEmpty())
    }
}
