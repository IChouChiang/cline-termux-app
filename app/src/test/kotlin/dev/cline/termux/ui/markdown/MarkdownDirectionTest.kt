package dev.cline.termux.ui.markdown

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply's own base direction (desktop `dir="auto"`), independent of the app's layout direction. */
class MarkdownDirectionTest {

    private fun rtl(markdown: String) = MarkdownParser.document(markdown).isRtl

    @Test
    fun `an English reply is left-to-right`() {
        assertFalse(rtl("Restarted the tunnel.\n\n- one\n- two"))
    }

    @Test
    fun `an English table stays left-to-right`() {
        assertFalse(rtl("| Field | Value |\n|---|---|\n| phone | 10.0.0.2 |"))
    }

    @Test
    fun `CJK replies are left-to-right`() {
        assertFalse(rtl("运行测试，全部通过。"))
        assertFalse(rtl("テストを実行しました。"))
        assertFalse(rtl("테스트를 실행했습니다."))
    }

    @Test
    fun `Arabic and Hebrew replies are right-to-left`() {
        assertTrue(rtl("أعدت تشغيل النفق بنجاح."))
        assertTrue(rtl("הפעלתי מחדש את המנהרה."))
        assertTrue(rtl("| الجهاز | العنوان |\n|---|---|\n| الهاتف | 10.0.0.2 |"))
    }

    @Test
    fun `the first strong character decides, not the majority`() {
        assertFalse(rtl("OK: أعدت تشغيل النفق بنجاح وكل شيء يعمل الآن."))
        assertTrue(rtl("تم. Restarted the tunnel and everything works now."))
    }

    @Test
    fun `numbers, punctuation and list markers are not strong`() {
        assertTrue(rtl("1. 42 — الهاتف\n2. الحاسوب"))
        assertTrue(rtl("- 10.0.0.2: الهاتف"))
        assertTrue(rtl("## 3) الخطوات"))
    }

    @Test
    fun `code blocks are skipped`() {
        assertTrue(rtl("```bash\nwg-quick up wg0\n```\n\nأعدت تشغيل النفق."))
        assertTrue(rtl("    indented code\n\nأعدت تشغيل النفق."))
    }

    @Test
    fun `inline code is skipped`() {
        assertTrue(rtl("`wg0` أعيد تشغيله."))
        assertFalse(rtl("`الهاتف` is the phone."))
    }

    @Test
    fun `quotes and nested lists are read in order`() {
        assertTrue(rtl("> ملاحظة: قد ينقطع النفق.\n\nAfter the quote."))
        assertTrue(rtl("- 42\n  - ملاحظة"))
    }

    @Test
    fun `a reply with no strong character is left-to-right`() {
        assertFalse(rtl("```kotlin\nval x = 1\n```\n\n---\n\n1. 42"))
        assertFalse(rtl(""))
        assertFalse(MdDocument.Empty.isRtl)
    }

    @Test
    fun `a streaming reply takes its direction from the text so far`() {
        val incremental = IncrementalMarkdown(MdImageLabels("Image", "Image: %1\$s"))
        assertFalse(incremental.document("```bash\nwg-quick").isRtl)
        assertTrue(incremental.document("```bash\nwg-quick up wg0\n```\n\nأعدت").isRtl)
    }

    @Test
    fun `text runs report the first strong direction and skip the given ranges`() {
        assertNull(firstStrongIsRtl("123 - 456 !"))
        assertEquals(false, firstStrongIsRtl("abc الهاتف"))
        assertEquals(true, firstStrongIsRtl("abc الهاتف", listOf(TextRange(0, 3))))
        // A reversed range covers the same characters.
        assertEquals(true, firstStrongIsRtl("abc الهاتف", listOf(TextRange(3, 0))))
        // Supplementary-plane letters are read as one code point.
        assertEquals(false, firstStrongIsRtl("𝐀"))
        assertEquals(true, firstStrongIsRtl("𐤀"))
    }
}
