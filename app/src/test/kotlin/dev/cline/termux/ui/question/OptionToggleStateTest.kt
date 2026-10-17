package dev.cline.termux.ui.question

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionToggleStateTest {

    @Test
    fun `multiple-choice option exposes checked state`() {
        assertEquals(ToggleableState.On, optionToggleState(multiple = true, selected = true))
    }

    @Test
    fun `multiple-choice option exposes unchecked state`() {
        assertEquals(ToggleableState.Off, optionToggleState(multiple = true, selected = false))
    }

    @Test
    fun `single-choice option is a button without toggle state`() {
        assertNull(optionToggleState(multiple = false, selected = true))
        assertNull(optionToggleState(multiple = false, selected = false))
    }
}
