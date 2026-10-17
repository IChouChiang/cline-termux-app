package dev.cline.termux.ui.sessions

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
 * TalkBack copy assembled from resources: the localised list join keeps its space (aapt2 trims an
 * unquoted trailing one), and count strings use plurals picked by the copy's language, so a zh-CN
 * phone (one plural form) still hears "1 addition" (DS §8.2.2, §8.2.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class A11yCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = quantityResources(context.resources)

    @Test
    fun `joinA11y separates parts with comma and space and skips blanks`() {
        assertEquals(
            "github, MCP server, Verified, Installed, Tagline",
            context.joinA11y(listOf("github", "MCP server", "Verified", "", "Installed", "Tagline")),
        )
        assertEquals("only", context.joinA11y(listOf("only")))
        assertEquals("", context.joinA11y(emptyList()))
    }

    @Test
    fun `diff chip description uses plural counts`() {
        fun cd(additions: Int, deletions: Int) = context.getString(
            R.string.header_diff_cd,
            resources.getQuantityString(R.plurals.header_diff_additions_cd, additions, additions),
            resources.getQuantityString(R.plurals.header_diff_deletions_cd, deletions, deletions),
        )
        assertEquals("Open diff: 1 addition, 1 deletion", cd(1, 1))
        assertEquals("Open diff: 12 additions, 0 deletions", cd(12, 0))
    }

    @Test
    fun `search showing line is keyed on the total`() {
        assertEquals("Showing 1 of 1 result", resources.getQuantityString(R.plurals.search_showing, 1, 1, 1))
        assertEquals("Showing 50 of 120 results", resources.getQuantityString(R.plurals.search_showing, 120, 50, 120))
    }
}
