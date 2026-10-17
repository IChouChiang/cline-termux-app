package dev.cline.termux.data.about

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WhatsNewRepositoryTest {
    private class Store(var seen: String? = null, var onboardedAt: Long? = 1L, var failRead: Boolean = false, var failWrite: Boolean = false) : WhatsNewRepository.Store {
        override suspend fun seenId(): String? = if (failRead) throw IOException("unreadable") else seen
        override suspend fun setSeenId(id: String) {
            if (failWrite) throw IOException("read-only")
            seen = id
        }
        override suspend fun onboardingCompletedAt(): Long? = onboardedAt
    }

    private val latest = WHATS_NEW_RELEASES[0]

    // Ported from lib/whats-new.test.ts.
    @Test
    fun `returns the latest catch-up for users who have not seen it`() {
        val repo = WhatsNewRepository(Store())
        assertSame(latest, repo.pending(null))
        assertSame(latest, repo.pending("2020-01-older"))
    }

    @Test
    fun `returns nothing once the latest catch-up was seen`() {
        assertNull(WhatsNewRepository(Store()).pending(latest.id))
    }

    @Test
    fun `keeps catch-up ids unique so seen-tracking cannot collide`() {
        val ids = WHATS_NEW_RELEASES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the shell shows it once to onboarded users and closing marks it seen`() = runTest {
        val store = Store()
        val repo = WhatsNewRepository(store)
        assertSame(latest, repo.pendingForShell())
        repo.markSeen(latest.id)
        assertNull(repo.pendingForShell())

        val fresh = Store(onboardedAt = null)
        assertNull("never over onboarding", WhatsNewRepository(fresh).pendingForShell())
        WhatsNewRepository(fresh).markCurrentSeen()
        assertEquals(latest.id, fresh.seen)
    }

    @Test
    fun `an unreadable store counts as seen and a failed write is swallowed`() = runTest {
        val repo = WhatsNewRepository(Store(failRead = true, failWrite = true))
        assertEquals(latest.id, repo.seenId())
        assertNull(repo.pendingForShell())
        repo.markSeen("x")
    }

    @Test
    fun `the first android entry has four highlights with icons`() {
        assertEquals("android-0.1", latest.id)
        assertEquals(listOf(WhatsNewIcon.Chat, WhatsNewIcon.TaskAlt, WhatsNewIcon.Schedule, WhatsNewIcon.Extension), latest.highlights.map { it.icon })
    }
}
