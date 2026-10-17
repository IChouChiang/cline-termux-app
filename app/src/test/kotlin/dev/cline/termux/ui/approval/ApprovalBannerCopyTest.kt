package dev.cline.termux.ui.approval

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
 * The banner's single-request sentence is chosen in code; every plural item carries the count,
 * so a locale whose `one` form also covers 21 or 0 never announces a count-less sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class ApprovalBannerCopyTest {
    private val resources = quantityResources(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test
    fun `one pending request reads without a count`() {
        assertEquals("Tool approval required, Review", approvalBannerDescription(resources, 1))
    }

    @Test
    fun `several pending requests carry the count`() {
        assertEquals("Tool approval required, 2 requests, Review", approvalBannerDescription(resources, 2))
        assertEquals("Tool approval required, 21 requests, Review", approvalBannerDescription(resources, 21))
    }

    @Test
    fun `every plural item carries the count`() {
        assertEquals("Tool approval required, 1 request, Review", resources.getQuantityString(R.plurals.approval_banner_cd, 1, 1))
        assertEquals("Tool approval required, 0 requests, Review", resources.getQuantityString(R.plurals.approval_banner_cd, 0, 0))
    }
}
