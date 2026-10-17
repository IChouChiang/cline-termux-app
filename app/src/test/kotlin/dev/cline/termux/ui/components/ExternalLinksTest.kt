package dev.cline.termux.ui.components

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalLinksTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a web url opens in a Custom Tab`() {
        assertEquals(OpenExternalResult.Opened, openExternal(app, "https://cline.bot/docs"))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://cline.bot/docs"), started.data)
        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `mailto and tel skip the Custom Tab and go straight to ACTION_VIEW in a new task`() {
        for (url in listOf("mailto:support@cline.bot", "tel:+15551234567")) {
            assertEquals(OpenExternalResult.Opened, openExternal(app, url, copyOnFailure = false))
            val started = shadowOf(app).nextStartedActivity
            assertNotNull(started)
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals(Uri.parse(url), started.data)
            assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
            assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test
    fun `a scheme nothing handles reports no browser and copies only when asked`() {
        shadowOf(app).checkActivities(true)
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        assertEquals(OpenExternalResult.NoBrowser, openExternal(app, "mailto:nobody@example.com", copyOnFailure = false))
        assertFalse(clipboard.hasPrimaryClip())
        assertEquals(OpenExternalResult.NoBrowser, openExternal(app, "tel:123"))
        assertEquals("tel:123", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(app.getString(dev.cline.termux.R.string.components_clip_url), clipboard.primaryClip?.description?.label?.toString())
    }
}
