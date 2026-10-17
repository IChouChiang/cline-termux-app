package dev.cline.termux.ui.components

import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.assertEquals
import org.junit.Test

/** DS §2.3: single-line LtrText labels ellipsize by default; wrapping text still clips. */
class LtrTextOverflowTest {

    @Test
    fun `a single-line label ellipsizes by default`() {
        assertEquals(TextOverflow.Ellipsis, defaultLtrOverflow(1))
    }

    @Test
    fun `unbounded text clips by default`() {
        assertEquals(TextOverflow.Clip, defaultLtrOverflow(Int.MAX_VALUE))
    }

    @Test
    fun `multi-line bounded text clips by default`() {
        assertEquals(TextOverflow.Clip, defaultLtrOverflow(2))
        assertEquals(TextOverflow.Clip, defaultLtrOverflow(6))
    }
}
