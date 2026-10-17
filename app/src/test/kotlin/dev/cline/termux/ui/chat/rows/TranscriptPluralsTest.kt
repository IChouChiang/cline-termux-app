package dev.cline.termux.ui.chat.rows

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import dev.cline.termux.ui.kernel.quantityResources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's phone runs zh-CN, whose plural rules have one form ("other"). The transcript's
 * English plurals must still read "1 tool call" (home-chat.md §3.16 "made 1 tool call") and the
 * approval banner "1 request": the form is picked by the copy's language (copyPluralStringResource).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class TranscriptPluralsTest {
    private val resources = quantityResources(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test
    fun `work row tool calls use English plural rules on a zh-CN device`() {
        assertEquals("1 tool call", resources.getQuantityString(R.plurals.work_tool_calls, 1, 1))
        assertEquals("14 tool calls", resources.getQuantityString(R.plurals.work_tool_calls, 14, 14))
    }

    @Test
    fun `agents tally uses English plural rules on a zh-CN device`() {
        assertEquals("1 agent", resources.getQuantityString(R.plurals.agents_count, 1, 1))
        assertEquals("3 agents", resources.getQuantityString(R.plurals.agents_count, 3, 3))
    }
}
