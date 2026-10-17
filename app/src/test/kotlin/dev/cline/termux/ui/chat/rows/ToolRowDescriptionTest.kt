package dev.cline.termux.ui.chat.rows

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.ui.kernel.quantityResources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DS §8.1.5 "full label + status" and §8.1.3 (colour is never the only signal): the tool row's
 * TalkBack label carries the `+N` / `-N` counts the header shows in success/error colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRowDescriptionTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `no counts reads label and status`() {
        assertEquals("Ran command bun test, running", toolRowDescription(res, "Ran command bun test", "running", null, null))
    }

    @Test
    fun `both counts are appended with plurals`() {
        assertEquals(
            "Edited file app.tsx, completed, 12 additions, 1 deletion",
            toolRowDescription(res, "Edited file app.tsx", "completed", 12, 1),
        )
        assertEquals(
            "Edited file app.tsx, completed, 1 addition, 0 deletions",
            toolRowDescription(res, "Edited file app.tsx", "completed", 1, 0),
        )
    }

    /**
     * The owner's zh-CN phone: Chinese plural rules have only "other", so the device-locale
     * resources would read "1 additions". The copy-language resources keep "1 addition".
     */
    @Test
    @Config(qualifiers = "zh-rCN")
    fun `counts use the copy language plural rules on a zh-CN device`() {
        val quantity = quantityResources(res)
        assertEquals(
            "Edited file app.tsx, completed, 1 addition, 0 deletions",
            toolRowDescription(res, "Edited file app.tsx", "completed", 1, 0, quantity = quantity),
        )
        assertEquals(
            "Deleted file a.ts, error, 1 deletion",
            toolRowDescription(res, "Deleted file a.ts", "error", null, 1, quantity = quantity),
        )
        // The default resolves the same copy-language resources.
        assertEquals(
            "Created file a.ts, completed, 1 addition",
            toolRowDescription(res, "Created file a.ts", "completed", 1, null),
        )
    }

    @Test
    fun `a single count is appended alone`() {
        assertEquals("Created file a.ts, completed, 3 additions", toolRowDescription(res, "Created file a.ts", "completed", 3, null))
        assertEquals("Deleted file a.ts, error, 7 deletions", toolRowDescription(res, "Deleted file a.ts", "error", null, 7))
    }
}
