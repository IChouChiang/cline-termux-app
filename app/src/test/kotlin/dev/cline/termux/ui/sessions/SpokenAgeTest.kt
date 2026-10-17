package dev.cline.termux.ui.sessions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TalkBack's row age matches the visible compact age: "now" under a minute, never "0 minutes ago". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpokenAgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_791_300_000_000L

    @Test
    fun `under a minute reads now`() {
        assertEquals("now", context.spokenAge(now, now))
        assertEquals("now", context.spokenAge(now - 59_000L, now))
    }

    @Test
    fun `a record stamped ahead of the clock reads now`() {
        assertEquals("now", context.spokenAge(now + 30_000L, now))
    }

    @Test
    fun `minutes read unabbreviated`() {
        assertEquals("5 minutes ago", context.spokenAge(now - 5 * 60_000L, now))
    }
}
