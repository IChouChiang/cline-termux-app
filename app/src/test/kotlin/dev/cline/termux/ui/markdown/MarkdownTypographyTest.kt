package dev.cline.termux.ui.markdown

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.clineMono
import dev.cline.termux.ui.theme.clineTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §2.1 heading sizes against the DS §2.4 font size setting, and DS §2.2 inline code. */
class MarkdownTypographyTest {

    private val fontSizes = listOf(FontSizeSetting.MIN, FontSizeSetting.DEFAULT, FontSizeSetting.MAX)

    private fun body(fontSize: Int) = clineTypography(FontSizeSetting.scale(fontSize)).bodyLarge

    @Test
    fun `headings are never smaller than the body at font sizes 12, 15 and 20`() {
        for (n in fontSizes) {
            val body = body(n)
            val sizes = (1..4).map { markdownHeadingStyle(body, it).fontSize.value }
            val bodySize = body.fontSize.value
            // h1 > h2 > h3 > h4, and h3 already larger than the paragraph.
            for (i in 0 until 3) assertTrue("font size $n: h${i + 1} > h${i + 2}", sizes[i] > sizes[i + 1])
            assertTrue("font size $n: h3 > body", sizes[2] > bodySize)
            // DS §2.1 h4 is 16/24 beside the 16/24 body: the same size, set apart by weight.
            assertTrue("font size $n: h4 >= body", sizes[3] >= bodySize - 0.001f)
            assertTrue("font size $n: h4 line >= body line", markdownHeadingStyle(body, 4).lineHeight.value >= body.lineHeight.value - 0.001f)
        }
    }

    @Test
    fun `at the default font size the headings are the DS sizes`() {
        val body = body(FontSizeSetting.DEFAULT)
        val expected = listOf(22f to 28f, 19f to 26f, 17f to 24f, 16f to 24f)
        expected.forEachIndexed { i, (size, line) ->
            val style = markdownHeadingStyle(body, i + 1)
            assertEquals(size, style.fontSize.value, 0.001f)
            assertEquals(line, style.lineHeight.value, 0.001f)
            assertEquals(FontWeight.SemiBold, style.fontWeight)
            assertEquals(0f, style.letterSpacing.value, 0f)
        }
    }

    @Test
    fun `headings scale with the font size setting`() {
        val small = markdownHeadingStyle(body(FontSizeSetting.MIN), 1)
        val large = markdownHeadingStyle(body(FontSizeSetting.MAX), 1)
        // n / 15f: 22sp × 0.8 and 22sp × 4/3.
        assertEquals(22f * 12f / 15f, small.fontSize.value, 0.01f)
        assertEquals(22f * 20f / 15f, large.fontSize.value, 0.01f)
        assertEquals(28f * 20f / 15f, large.lineHeight.value, 0.01f)
        // At 20 the old fixed 19sp h2 sat below the 21.3sp body; now it is above it.
        assertTrue(markdownHeadingStyle(body(FontSizeSetting.MAX), 2).fontSize.value > body(FontSizeSetting.MAX).fontSize.value)
    }

    @Test
    fun `deeper heading levels use h4 and a smaller body keeps the proportions`() {
        val body = body(FontSizeSetting.DEFAULT)
        assertEquals(markdownHeadingStyle(body, 4), markdownHeadingStyle(body, 6))
        assertEquals(markdownHeadingStyle(body, 1), markdownHeadingStyle(body, 0))
        val medium = body.copy(fontSize = 14.sp, lineHeight = 20.sp)
        assertEquals(22f * 14f / 16f, markdownHeadingStyle(medium, 1).fontSize.value, 0.01f)
    }

    @Test
    fun `inline code uses ClineMono with ligatures off and leaves the weight alone`() {
        val mono = clineMono().body
        assertEquals("calt 0", CodeSpan.fontFeatureSettings)
        assertEquals(mono.fontFeatureSettings, CodeSpan.fontFeatureSettings)
        assertEquals(mono.fontFamily, CodeSpan.fontFamily)
        assertEquals(0f, CodeSpan.letterSpacing.value, 0f)
        assertEquals(0.85.em, CodeSpan.fontSize)
        // `**`code`**` keeps the strong weight from the enclosing span.
        assertNull(CodeSpan.fontWeight)
    }

    @Test
    fun `parsed inline code carries the no-ligature feature setting`() {
        val paragraph = MarkdownParser.document("Use `a -> b` here").blocks.single() as MdBlock.Paragraph
        val code = paragraph.inline.text.spanStyles.single { it.item.fontFeatureSettings != null }
        assertEquals("calt 0", code.item.fontFeatureSettings)
        assertEquals("a -> b", paragraph.inline.text.text.substring(code.start, code.end))
    }
}
