package dev.cline.termux.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The long-press menu offset (DS §4.1 #15): M3 measures `offset.y` from the anchor's bottom
 * edge and, in RTL, `offset.x` from its end edge, so the press point has to be translated.
 */
class PressMenuOffsetTest {

    private val density = Density(density = 2f)

    private fun assertOffset(expected: DpOffset, actual: DpOffset) {
        assertEquals(expected.x.value, actual.x.value, 0.001f)
        assertEquals(expected.y.value, actual.y.value, 0.001f)
    }

    @Test
    fun `ltr keeps x from the left and measures y up from the bottom edge`() {
        // A 48dp row (96px), pressed 20dp from the left and 10dp from the top.
        val offset = pressMenuOffset(Offset(40f, 20f), IntSize(800, 96), LayoutDirection.Ltr, density)
        assertOffset(DpOffset(20.dp, (-38).dp), offset)
    }

    @Test
    fun `rtl measures x from the end edge`() {
        // 400dp wide (800px), pressed 20dp from the left = 380dp from the right (start) edge.
        val offset = pressMenuOffset(Offset(40f, 20f), IntSize(800, 96), LayoutDirection.Rtl, density)
        assertOffset(DpOffset(380.dp, (-38).dp), offset)
    }

    @Test
    fun `a tall anchor puts the menu at the finger, not a bubble height below it`() {
        // A 600dp assistant answer (1200px) pressed 100dp from the top: 500dp above its bottom.
        val offset = pressMenuOffset(Offset(200f, 200f), IntSize(720, 1200), LayoutDirection.Ltr, density)
        assertOffset(DpOffset(100.dp, (-500).dp), offset)
    }

    @Test
    fun `a press on the bottom edge needs no vertical offset`() {
        val offset = pressMenuOffset(Offset(0f, 96f), IntSize(800, 96), LayoutDirection.Ltr, density)
        assertOffset(DpOffset(0.dp, 0.dp), offset)
    }

    @Test
    fun `rtl tall anchor combines both translations`() {
        val offset = pressMenuOffset(Offset(600f, 1000f), IntSize(720, 1200), LayoutDirection.Rtl, density)
        assertOffset(DpOffset(60.dp, (-100).dp), offset)
    }
}
