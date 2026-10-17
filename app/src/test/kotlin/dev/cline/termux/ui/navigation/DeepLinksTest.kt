package dev.cline.termux.ui.navigation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class DeepLinksTest {
    private class MapExtras(vararg pairs: Pair<String, String>) : DeepLinks.Extras {
        val map = mutableMapOf(*pairs)
        override fun string(key: String): String? = map[key]
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `a session id opens that chat with an optional sheet`() {
        assertEquals(DeepLink.Session("s-1"), DeepLinks.parse("s-1", null))
        assertEquals(DeepLink.Session("s-1", ChatSheet.Approval), DeepLinks.parse("s-1", "approval"))
        assertEquals(DeepLink.Session("s-1", ChatSheet.Question), DeepLinks.parse(" s-1 ", " question "))
        assertEquals(DeepLink.Session("s-1", ChatSheet.Queue), DeepLinks.parse("s-1", "queue"))
        // An unknown sheet still opens the chat.
        assertEquals(DeepLink.Session("s-1"), DeepLinks.parse("s-1", "bogus"))
    }

    @Test
    fun `kernel status needs no session`() {
        assertEquals(DeepLink.KernelStatus, DeepLinks.parse(null, DeepLinks.SHEET_KERNEL_STATUS))
        assertEquals(DeepLink.KernelStatus, DeepLinks.parse("  ", DeepLinks.SHEET_KERNEL_STATUS))
    }

    @Test
    fun `nothing usable is no link`() {
        assertNull(DeepLinks.parse(null, null))
        assertNull(DeepLinks.parse("", "approval"))
        assertNull(DeepLinks.parse(null, "approval"))
    }

    @Test
    fun `the session link names the chat route`() {
        assertEquals(Chat("s-1", "approval"), DeepLink.Session("s-1", ChatSheet.Approval).route)
        assertEquals(Chat("s-1", null), DeepLink.Session("s-1").route)
    }

    @Test
    fun `consume reads and always removes both extras`() {
        val extras = MapExtras(DeepLinks.EXTRA_SESSION_ID to "s-1", DeepLinks.EXTRA_OPEN_SHEET to "approval", "other" to "kept")
        assertEquals(DeepLink.Session("s-1", ChatSheet.Approval), DeepLinks.consume(extras))
        assertEquals(mapOf("other" to "kept"), extras.map)
        // A second read (rotation, recreation) finds nothing.
        assertNull(DeepLinks.consume(extras))

        val unusable = MapExtras(DeepLinks.EXTRA_OPEN_SHEET to "approval")
        assertNull(DeepLinks.consume(unusable))
        assertEquals(emptyMap<String, String>(), unusable.map)
    }

    @Test
    fun `extras round trip through parse`() {
        val links = listOf(DeepLink.Session("s 1/2"), DeepLink.Session("s", ChatSheet.Queue), DeepLink.KernelStatus)
        for (link in links) {
            val extras = DeepLinks.extrasOf(link)
            assertEquals(link, DeepLinks.parse(extras[DeepLinks.EXTRA_SESSION_ID], extras[DeepLinks.EXTRA_OPEN_SHEET]))
        }
        assertFalse(DeepLinks.EXTRA_OPEN_SHEET in DeepLinks.extrasOf(DeepLink.Session("s")))
    }

    @Test
    fun `saved state encoding round trips and tolerates junk`() {
        for (link in listOf(DeepLink.Session("s", ChatSheet.Approval), DeepLink.Session("t"), DeepLink.KernelStatus)) {
            assertEquals(link, DeepLinks.decode(DeepLinks.encode(link)))
        }
        assertNull(DeepLinks.decode(null))
        assertNull(DeepLinks.decode("{not json"))
    }
}

/** The `Intent` path: [DeepLinks.fillIn] for the notification router, [DeepLinks.consume] in `MainActivity`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinksIntentTest {
    @Test
    fun `fill in then consume strips the extras from the intent`() {
        val intent = DeepLinks.fillIn(Intent(Intent.ACTION_MAIN).putExtra("keep", "yes"), DeepLink.Session("s-9", ChatSheet.Question))
        assertEquals("s-9", intent.getStringExtra(DeepLinks.EXTRA_SESSION_ID))
        assertEquals(DeepLink.Session("s-9", ChatSheet.Question), DeepLinks.consume(intent))
        assertNull(intent.getStringExtra(DeepLinks.EXTRA_SESSION_ID))
        assertNull(intent.getStringExtra(DeepLinks.EXTRA_OPEN_SHEET))
        assertEquals("yes", intent.getStringExtra("keep"))
        assertNull(DeepLinks.consume(intent))
        assertNull(DeepLinks.consume(null as Intent?))
    }

    @Test
    fun `kernel status intent`() {
        val intent = DeepLinks.fillIn(Intent(), DeepLink.KernelStatus)
        assertEquals(DeepLink.KernelStatus, DeepLinks.consume(intent))
    }
}
