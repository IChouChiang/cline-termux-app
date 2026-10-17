package dev.cline.termux.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §8.2.7: [takeGraphemePrefix] keeps UTF-16 limits but never splits a grapheme cluster. */
class GraphemeTextTest {
    private val grin = "😀" // U+1F600, one surrogate pair
    private val family = "👩‍👩‍👧" // ZWJ sequence, 8 units
    private val eAcute = "é" // e + combining acute

    @Test fun `text that fits is returned unchanged`() {
        val text = "ab$grin"
        assertSame(text, takeGraphemePrefix(text, 4))
        assertSame(text, takeGraphemePrefix(text, 100))
        assertEquals("", takeGraphemePrefix("", 0))
    }

    @Test fun `firstGrapheme keeps whole clusters`() {
        assertEquals("", firstGrapheme(""))
        assertEquals("A", firstGrapheme("Acme"))
        assertEquals(grin, firstGrapheme("$grin Team"))
        assertEquals("𠀋", firstGrapheme("𠀋組")) // CJK Ext. B, a surrogate pair
        assertEquals(family, firstGrapheme("${family}x"))
        assertEquals(eAcute, firstGrapheme("${eAcute}cole"))
        assertEquals(grin, firstGrapheme(grin))
    }

    @Test fun `a non-positive limit yields the empty string`() {
        assertEquals("", takeGraphemePrefix("abc", 0))
        assertEquals("", takeGraphemePrefix("abc", -3))
    }

    @Test fun `ascii is cut at the exact limit`() {
        assertEquals("abc", takeGraphemePrefix("abcdef", 3))
    }

    @Test fun `a surrogate pair on the boundary is dropped whole`() {
        // Limit 3 would end between the high and the low surrogate of the emoji.
        assertEquals("ab", takeGraphemePrefix("ab${grin}cd", 3))
        // Limit 4 ends right after the pair, which is kept.
        assertEquals("ab$grin", takeGraphemePrefix("ab${grin}cd", 4))
        // A pair at the very start and a limit of 1 leaves nothing.
        assertEquals("", takeGraphemePrefix("${grin}x", 1))
    }

    @Test fun `combining sequences and ZWJ emoji stay whole`() {
        assertEquals("ab", takeGraphemePrefix("ab${eAcute}z", 3))
        assertEquals("ab$eAcute", takeGraphemePrefix("ab${eAcute}z", 4))
        for (limit in 2 until 2 + family.length) {
            assertEquals("limit $limit", "ab", takeGraphemePrefix("ab${family}z", limit))
        }
        assertEquals("ab$family", takeGraphemePrefix("ab${family}z", 2 + family.length))
    }

    @Test fun `a supplementary CJK ideograph is a surrogate pair and stays whole`() {
        val kanji = "𠮷" // 𠮷 U+20BB7
        assertEquals("abcd", takeGraphemePrefix("abcd${kanji}z", 5))
        assertEquals("abcd$kanji", takeGraphemePrefix("abcd${kanji}z", 6))
        assertEquals("", takeGraphemePrefix(kanji, 1))
    }

    // ---- ellipsizeGraphemes (DS #7: the compact ModelChip label) ----------------------------

    @Test fun `exactly sixteen graphemes are kept without an ellipsis`() {
        val sixteen = "abcdefghijklmnop"
        assertSame(sixteen, ellipsizeGraphemes(sixteen))
        assertEquals("", ellipsizeGraphemes(""))
    }

    @Test fun `seventeen graphemes are cut to sixteen plus an ellipsis`() {
        assertEquals("abcdefghijklmnop…", ellipsizeGraphemes("abcdefghijklmnopq"))
        assertEquals("ab…", ellipsizeGraphemes("abc", maxGraphemes = 2))
    }

    @Test fun `a ZWJ emoji counts as one grapheme at the boundary`() {
        // 15 letters + the 8-unit family emoji = 16 graphemes: kept whole.
        val fifteen = "abcdefghijklmno"
        assertEquals("$fifteen$family", ellipsizeGraphemes("$fifteen$family"))
        // One more grapheme after it: the emoji stays, the cut falls after it.
        assertEquals("$fifteen$family…", ellipsizeGraphemes("$fifteen${family}z"))
        // The emoji as the 17th grapheme is dropped whole, never split.
        assertEquals("${fifteen}x…", ellipsizeGraphemes("${fifteen}x$family"))
    }

    @Test fun `CJK titles count ideographs, not UTF-16 units`() {
        val title = "通义千问三二五六七八九十一二三四五" // 17 BMP ideographs
        assertEquals("通义千问三二五六七八九十一二三四…", ellipsizeGraphemes(title))
        val kanji = "𠮷" // 𠮷: two units, one grapheme
        val sixteenPairs = kanji.repeat(16)
        assertSame(sixteenPairs, ellipsizeGraphemes(sixteenPairs))
        assertEquals("$sixteenPairs…", ellipsizeGraphemes(sixteenPairs + kanji))
        assertEquals("ab$eAcute…", ellipsizeGraphemes("ab${eAcute}z", maxGraphemes = 3))
    }

    @Test fun `no cut ever leaves a dangling surrogate or exceeds the limit`() {
        val text = "a$grin$eAcute$family b${grin}c$grin$grin"
        for (limit in 0..text.length + 1) {
            val cut = takeGraphemePrefix(text, limit)
            assertTrue("limit $limit", cut.length <= limit || cut == text)
            assertTrue(text.startsWith(cut))
            if (cut.isNotEmpty()) assertFalse("limit $limit", Character.isHighSurrogate(cut.last()))
        }
    }
}
