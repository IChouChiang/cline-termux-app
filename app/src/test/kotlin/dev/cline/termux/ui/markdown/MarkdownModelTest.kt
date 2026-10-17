package dev.cline.termux.ui.markdown

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {

    private fun blocks(markdown: String) = MarkdownParser.document(markdown).blocks

    @Test
    fun `paragraphs, headings and rules are read in order`() {
        val out = blocks("# Title\n\nBody text.\n\n---\n\n## Sub")
        assertEquals(4, out.size)
        assertEquals(1, (out[0] as MdBlock.Heading).level)
        assertEquals("Body text.", (out[1] as MdBlock.Paragraph).inline.text.text)
        assertTrue(out[2] is MdBlock.Rule)
        assertEquals(2, (out[3] as MdBlock.Heading).level)
    }

    @Test
    fun `inline code is recorded as chip ranges and strong is SemiBold`() {
        val paragraph = blocks("Run `bun test` **now**").single() as MdBlock.Paragraph
        // DS §8.2.6: inline code is LTR-isolated (U+2066 … U+2069); the isolates sit outside the chip.
        assertEquals("Run \u2066bun test\u2069 now", paragraph.inline.text.text)
        assertEquals(1, paragraph.inline.code.size)
        assertEquals("bun test", paragraph.inline.text.text.substring(paragraph.inline.code[0].start, paragraph.inline.code[0].end))
        val strong = paragraph.inline.text.spanStyles.first { it.item.fontWeight != null }
        assertEquals(FontWeight.SemiBold, strong.item.fontWeight)
    }

    @Test
    fun `inline code paths are isolated and a code link label is checked without the isolates`() {
        val paragraph = blocks("Ran `-n 5 src/a.ts` now").single() as MdBlock.Paragraph
        assertEquals("Ran \u2066-n 5 src/a.ts\u2069 now", paragraph.inline.text.text)
        val linked = blocks("Open [`github.com`](https://github.com/x) and [`github.com`](https://evil.example)").single() as MdBlock.Paragraph
        assertEquals(listOf(false, true), linked.inline.links.map { it.item.deceptive })
        assertEquals(2, linked.inline.code.size)
    }

    @Test
    fun `isolates around code do not change the CJK soft-break rule`() {
        val mixed = blocks("运行 `测试`\n这一行").single() as MdBlock.Paragraph
        assertEquals("运行 \u2066测试\u2069这一行", mixed.inline.text.text)
    }

    @Test
    fun `only openable schemes become links and relative paths stay text`() {
        val paragraph = blocks("See [docs](https://example.com/a) and [file](src/Main.kt) and <mailto:x@y.z>").single() as MdBlock.Paragraph
        assertEquals(listOf("https://example.com/a", "mailto:x@y.z"), paragraph.inline.links.map { it.item.url })
    }

    @Test
    fun `a link whose label names another host is deceptive`() {
        val paragraph = blocks("[https://github.com](https://evil.example) [github.com](https://github.com/cline) [docs](https://evil.example)")
            .single() as MdBlock.Paragraph
        assertEquals(listOf(true, false, false), paragraph.inline.links.map { it.item.deceptive })
    }

    @Test
    fun `deceptive link rules follow the desktop`() {
        assertTrue(isDeceptiveLink("github.com", "https://github.com.evil.example"))
        assertTrue(isDeceptiveLink("https://github.com:8443", "https://github.com"))
        assertTrue(isDeceptiveLink("http://github.com", "https://github.com"))
        assertTrue(isDeceptiveLink("example.com", "mailto:a@example.com"))
        assertFalse(isDeceptiveLink("www.github.com.", "https://github.com/x"))
        assertFalse(isDeceptiveLink("github.com", "https://github.com:443/x"))
        assertFalse(isDeceptiveLink("//github.com/a", "https://github.com/b"))
        assertFalse(isDeceptiveLink("the docs", "https://evil.example"))
    }

    @Test
    fun `images are never loaded and read as the blocked notice`() {
        val labels = MdImageLabels("Blocked", "Blocked: %1${'$'}s")
        val withAlt = MarkdownParser.document("![diagram](https://x.example/a.png)", labels).blocks.single() as MdBlock.Paragraph
        assertEquals("Blocked: diagram", withAlt.inline.text.text)
        assertTrue(withAlt.inline.links.isEmpty())
        val bare = MarkdownParser.document("![](https://x.example/a.png)", labels).blocks.single() as MdBlock.Paragraph
        assertEquals("Blocked", bare.inline.text.text)
        // The notification reading (no labels) keeps the alt text.
        val plain = blocks("![diagram](https://x.example/a.png)").single() as MdBlock.Paragraph
        assertEquals("diagram", plain.inline.text.text)
    }

    @Test
    fun `single tildes stay literal and double tildes strike`() {
        val plain = blocks("about ~2s and ~5 files").single() as MdBlock.Paragraph
        assertEquals("about ~2s and ~5 files", plain.inline.text.text)
        val struck = blocks("old ~~wg0.conf~~ gone").single() as MdBlock.Paragraph
        assertEquals("old wg0.conf gone", struck.inline.text.text)
    }

    @Test
    fun `task items become markers in bullet lists and glyphs in ordered lists`() {
        val bullets = blocks("- [x] done\n- [ ] todo\n- plain").single() as MdBlock.ListBlock
        assertEquals(listOf(MdTask.Done, MdTask.Open, null), bullets.items.map { it.task })
        assertEquals("done", (bullets.items[0].blocks.single() as MdBlock.Paragraph).inline.text.text)
        val ordered = blocks("1. [x] backed up\n2. [ ] notify").single() as MdBlock.ListBlock
        assertEquals(listOf("1.", "2."), ordered.items.map { it.marker })
        assertTrue((ordered.items[0].blocks.single() as MdBlock.Paragraph).inline.text.text.startsWith("☑"))
    }

    @Test
    fun `ordered lists keep their start number and nested bullets change glyph`() {
        val list = blocks("8. eight\n9. nine\n10. ten").single() as MdBlock.ListBlock
        assertEquals(listOf("8.", "9.", "10."), list.items.map { it.marker })
        val nested = blocks("- a\n  - b\n    - c").single() as MdBlock.ListBlock
        val second = nested.items[0].blocks[1] as MdBlock.ListBlock
        val third = second.items[0].blocks[1] as MdBlock.ListBlock
        assertEquals("•", nested.items[0].marker)
        assertEquals("◦", second.items[0].marker)
        assertEquals("▪", third.items[0].marker)
    }

    @Test
    fun `code blocks keep the source and expand tabs for display`() {
        val code = blocks("```go\nfunc main() {\n\tif x {\n\t\ty()\n\t}\n}\n```").single() as MdBlock.Code
        assertEquals("go", code.language)
        assertTrue(code.code.contains("\tif x"))
        assertEquals("func main() {\n    if x {\n        y()\n    }\n}", code.display)
        val indented = blocks("    indented\n    second").single() as MdBlock.Code
        assertEquals("", indented.language)
        assertEquals("indented\nsecond", indented.code)
    }

    @Test
    fun `tables are padded to the widest row and keep alignments`() {
        val table = blocks("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 |").single() as MdBlock.Table
        assertEquals(3, table.columns)
        assertTrue(table.hasHeader)
        assertEquals(listOf(MdAlign.Start, MdAlign.Center, MdAlign.End), table.alignments)
        assertEquals(3, table.rows[1].size)
        assertTrue(table.rows[1][2].isEmpty)
    }

    @Test
    fun `html comments vanish and other raw html shows as text`() {
        assertTrue(blocks("<!-- hidden -->").isEmpty())
        val shown = blocks("<details>raw</details>").single() as MdBlock.Paragraph
        assertEquals("<details>raw</details>", shown.inline.text.text)
    }

    @Test
    fun `soft breaks add a space between Latin words but not between Hanzi`() {
        assertTrue(spaceBetween('a', 'b'))
        assertFalse(spaceBetween('中', '文'))
        assertFalse(spaceBetween('。', 'a'))
        assertTrue(spaceBetween('中', 'a'))
        val mixed = blocks("这一行\n不会多出空格").single() as MdBlock.Paragraph
        assertEquals("这一行不会多出空格", mixed.inline.text.text)
        val latin = blocks("line one\nline two").single() as MdBlock.Paragraph
        assertEquals("line one line two", latin.inline.text.text)
    }

    @Test
    fun `the parser cache returns the same document for the same text`() {
        val first = MarkdownParser.document("same text")
        val second = MarkdownParser.document("same text")
        assertTrue(first === second)
    }
}
