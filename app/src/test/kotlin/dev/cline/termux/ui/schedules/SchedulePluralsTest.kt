package dev.cline.termux.ui.schedules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import dev.cline.termux.ui.kernel.quantityResources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Show all {n} runs" (routine-view.tsx) is a plural, with forms picked by the copy's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class SchedulePluralsTest {
    private val resources = quantityResources(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test
    fun `show all runs keeps the desktop copy as the other form`() {
        assertEquals("Show all 12 runs", resources.getQuantityString(R.plurals.schedule_details_show_all, 12, 12))
        assertEquals("Show all 1 run", resources.getQuantityString(R.plurals.schedule_details_show_all, 1, 1))
    }
}
