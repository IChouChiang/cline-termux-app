package dev.cline.termux.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** C1.4 rules 3 and 4 on the measured lane ([chooseModelChipForm]). */
class ModelChipFormTest {
    private val min = 200

    private fun choose(
        compact: Boolean,
        budget: Int = 0,
        shortFits: Boolean = false,
        busy: Boolean = false,
        previous: ModelChipForm? = null,
    ) = chooseModelChipForm(compact, busy, previous, budget, min) { shortFits }

    @Test fun `a wide medium or expanded lane shows provider and model`() {
        assertEquals(ModelChipForm.Full, choose(compact = false, budget = 480))
        assertEquals(ModelChipForm.Full, choose(compact = false, budget = min))
    }

    @Test fun `an expanded window's narrow pane falls back to the compact forms`() {
        // ≈ 376dp list-detail pane: too little room for the full chip.
        assertEquals(ModelChipForm.Short, choose(compact = false, budget = 60, shortFits = true))
        assertEquals(ModelChipForm.Icon, choose(compact = false, budget = 60, shortFits = false))
        assertEquals(ModelChipForm.Icon, choose(compact = false, budget = -40))
    }

    @Test fun `a compact window never shows the full chip`() {
        assertEquals(ModelChipForm.Short, choose(compact = true, budget = 900, shortFits = true))
        assertEquals(ModelChipForm.Icon, choose(compact = true, budget = 900, shortFits = false))
    }

    @Test fun `the short probe runs only when the full chip does not fit`() {
        var probed = false
        chooseModelChipForm(compact = false, busy = false, previous = null, fullBudgetPx = 500, fullMinPx = min) { probed = true; true }
        assertFalse(probed)
    }

    @Test fun `while busy the chip keeps its form`() {
        assertEquals(ModelChipForm.Icon, choose(compact = false, budget = 900, busy = true, previous = ModelChipForm.Icon))
        assertEquals(ModelChipForm.Full, choose(compact = false, budget = 10, busy = true, previous = ModelChipForm.Full))
        assertEquals(ModelChipForm.Short, choose(compact = true, shortFits = false, busy = true, previous = ModelChipForm.Short))
    }

    @Test fun `busy without a remembered form measures`() {
        assertEquals(ModelChipForm.Short, choose(compact = true, shortFits = true, busy = true))
    }

    @Test fun `a window that became compact while busy drops the full chip`() {
        assertEquals(ModelChipForm.Icon, choose(compact = true, shortFits = false, busy = true, previous = ModelChipForm.Full))
    }
}
