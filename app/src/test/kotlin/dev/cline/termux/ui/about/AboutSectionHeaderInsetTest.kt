package dev.cline.termux.ui.about

import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test

/** DS §3.3 / §4.1 #12: About's section headers line up with rows inset by the shell gutter. */
class AboutSectionHeaderInsetTest {

    @Test
    fun compactGutterAddsNothingBecauseSectionHeaderAlreadyPadsSpacingL() {
        assertEquals(0.dp, sectionHeaderInset(Spacing.l))
    }

    @Test
    fun widerGutterAddsOnlyTheExcess() {
        assertEquals(8.dp, sectionHeaderInset(Spacing.l + 8.dp))
    }

    @Test
    fun narrowerGutterNeverGoesNegative() {
        assertEquals(0.dp, sectionHeaderInset(Spacing.s))
    }
}
