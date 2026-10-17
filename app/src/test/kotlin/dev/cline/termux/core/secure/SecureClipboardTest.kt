package dev.cline.termux.core.secure

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SP §0.6: sensitive flag and the 60 s clear, resumed or at the next resume. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureClipboardTest {
    private lateinit var app: Application
    private lateinit var clipboard: ClipboardManager

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clipboard = app.getSystemService(ClipboardManager::class.java)
    }

    private fun TestScope.clipboardUnderTest(owner: Owner?) =
        SecureClipboard(app, backgroundScope, owner?.registry, clearAfterMs = 60_000, elapsedMs = { testScheduler.currentTime })

    private fun resumedOwner() = Owner().apply { registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }

    private fun primaryText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `copySecret flags the clip as sensitive`() = runTest {
        val secure = clipboardUnderTest(resumedOwner())
        secure.copySecret("API key", "sk-secret")
        assertEquals("sk-secret", primaryText())
        assertEquals("API key", clipboard.primaryClip!!.description.label.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertTrue(secure.holdsSecret)
    }

    @Test
    fun `the clip is cleared 60 s later while resumed`() = runTest {
        val secure = clipboardUnderTest(resumedOwner())
        secure.copySecret("API key", "sk-secret")
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals("sk-secret", primaryText())
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(clipboard.hasPrimaryClip())
        assertFalse(secure.holdsSecret)
    }

    @Test
    fun `in the background the clear waits for the next resume after 60 s`() = runTest {
        val owner = resumedOwner()
        val secure = clipboardUnderTest(owner)
        secure.copySecret("API key", "sk-secret")
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("sk-secret", primaryText())
        assertTrue(secure.holdsSecret)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse(clipboard.hasPrimaryClip())
        assertFalse(secure.holdsSecret)
    }

    @Test
    fun `a resume before 60 s keeps the clip and the timer`() = runTest {
        val owner = resumedOwner()
        val secure = clipboardUnderTest(owner)
        secure.copySecret("API key", "sk-secret")
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        advanceTimeBy(10_000)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals("sk-secret", primaryText())
        advanceTimeBy(50_001)
        runCurrent()
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `a second secret restarts the timer`() = runTest {
        val secure = clipboardUnderTest(resumedOwner())
        secure.copySecret("API key", "first")
        advanceTimeBy(40_000)
        secure.copySecret("API key", "second")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("second", primaryText())
        advanceTimeBy(30_001)
        runCurrent()
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `clearNow is a no-op without a held secret and plain clips are untouched`() = runTest {
        val secure = clipboardUnderTest(resumedOwner())
        clipboard.setPrimaryClip(ClipData.newPlainText("path", "/sdcard/x"))
        secure.clearNow()
        assertEquals("/sdcard/x", primaryText())
        assertFalse(secure.holdsSecret)
    }

    @Test
    fun `without a lifecycle the clear fires on the timer`() = runTest {
        val secure = clipboardUnderTest(null)
        secure.copySecret("API key", "sk-secret")
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals("sk-secret", primaryText()) // not resumed: waits for a resume that never comes here
        secure.clearNow()
        assertNull(primaryText())
    }
}
