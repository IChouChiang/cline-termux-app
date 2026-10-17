package dev.cline.termux.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** DS §4.1 #4 / §8.2.6: a collapsed chevron points to the end in both layout directions. */
class DisclosureChevronTest {

    @Test
    fun `end to down points right in LTR and left in RTL when collapsed, down when expanded`() {
        assertEquals(-90f, disclosureChevronAngle(expanded = false, rtl = false), 0f)
        assertEquals(90f, disclosureChevronAngle(expanded = false, rtl = true), 0f)
        assertEquals(0f, disclosureChevronAngle(expanded = true, rtl = false), 0f)
        assertEquals(0f, disclosureChevronAngle(expanded = true, rtl = true), 0f)
    }

    @Test
    fun `end to up keeps turning the same way as the desktop work row`() {
        assertEquals(-90f, disclosureChevronAngle(false, rtl = false, turn = ChevronTurn.EndToUp), 0f)
        assertEquals(-180f, disclosureChevronAngle(true, rtl = false, turn = ChevronTurn.EndToUp), 0f)
        assertEquals(90f, disclosureChevronAngle(false, rtl = true, turn = ChevronTurn.EndToUp), 0f)
        assertEquals(180f, disclosureChevronAngle(true, rtl = true, turn = ChevronTurn.EndToUp), 0f)
    }

    @Test
    fun `down to up ignores layout direction`() {
        for (rtl in listOf(false, true)) {
            assertEquals(0f, disclosureChevronAngle(false, rtl, ChevronTurn.DownToUp), 0f)
            assertEquals(180f, disclosureChevronAngle(true, rtl, ChevronTurn.DownToUp), 0f)
        }
    }
}
