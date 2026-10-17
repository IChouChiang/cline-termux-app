package dev.cline.termux.ui.components

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.core.kernel.termux.TermuxPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The shared app-details intent, the safe start and the activity lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidIntentsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `app details intent targets the package's App info page`() {
        val intent = appDetailsIntent("com.termux")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.termux", intent.data.toString())
    }

    @Test
    fun `openAppDetails defaults to this app and starts a new task`() {
        assertTrue(app.openAppDetails())
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.data.toString())
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `startActivitySafely reports a page the ROM does not export`() {
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw SecurityException("not exported")
        }
        assertFalse(context.startActivitySafely(Intent(Settings.ACTION_SETTINGS)))
        assertFalse(context.openAppDetails("com.termux"))
    }

    @Test
    fun `startActivitySafely reports a missing page`() {
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw android.content.ActivityNotFoundException()
        }
        assertFalse(context.startActivitySafely(Intent(Settings.ACTION_SETTINGS)))
    }

    @Test
    fun `openTermux prefers Termux's launcher intent in a new task`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TermuxPaths.PACKAGE)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            launcher,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = TermuxPaths.PACKAGE
                    name = "com.termux.HomeActivity"
                }
            },
        )
        assertTrue(openTermux(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(TermuxPaths.PACKAGE, started.component!!.packageName)
        assertEquals("com.termux.HomeActivity", started.component!!.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `openTermux falls back to TermuxActivity when there is no launcher intent`() {
        assertTrue(openTermux(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(TermuxPaths.PACKAGE, started.component!!.packageName)
        assertEquals(TermuxPaths.ACTIVITY, started.component!!.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `openTermux reports a missing Termux instead of crashing`() {
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw android.content.ActivityNotFoundException()
        }
        assertFalse(openTermux(context))
    }

    @Test
    fun `findActivity unwraps nested wrappers and is null outside an activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertSame(activity, wrapped.findActivity())
        assertSame(activity, activity.findActivity())
        assertNull(app.findActivity())
    }
}
