package dev.cline.termux.core.kernel.hub

import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.InMemoryCursorStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HubErrorsTest {
    @Test
    fun `error classification by code and message`() {
        assertTrue(HubCommandException("session.get", HubErrorCodes.SESSION_NOT_FOUND, "x").isSessionGone())
        assertTrue(HubCommandException("run.start", HubErrorCodes.COMMAND_FAILED, "Session not found: s1").isSessionGone())
        assertTrue(HubCommandException("run.start", HubErrorCodes.COMMAND_FAILED, "Unknown session: s1").isSessionGone())
        assertFalse(HubCommandException("run.start", HubErrorCodes.COMMAND_FAILED, "provider exploded").isSessionGone())
        assertTrue(HubCommandException("session.create", HubErrorCodes.HUB_CONNECTION_CLOSED, "closed").isLostReply())
        assertTrue(HubCommandException("session.create", HubErrorCodes.CLIENT_TIMEOUT, "t").isLostReply())
        assertTrue(HubCommandException("session.create", HubErrorCodes.HUB_COMMAND_TIMEOUT, "t").isLostReply())
        assertFalse(HubCommandException("session.create", HubErrorCodes.HUB_DRAINING, "d").isLostReply())
        assertTrue(HubCommandException("session.create", HubErrorCodes.HUB_DRAINING, "d").isDraining())
        assertTrue(HubCommandException("x", HubErrorCodes.CLIENT_AUTHORITY_MISMATCH, "a").isAuthorityLost())
        assertTrue(HubCommandException("x", HubErrorCodes.CLIENT_NOT_FOUND, "a").isAuthorityLost())
        assertTrue(HubCommandException("x", HubErrorCodes.CLIENT_ALREADY_REGISTERED, "a").isAuthorityLost())
        assertTrue(HubCommandException("x", HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE, "a").isPendingPromptsUnavailable())
        val e = HubCommandException("session.get", "session_not_found", "gone")
        assertEquals("gone", e.hubMessage)
        assertEquals("session.get failed: session_not_found: gone", e.message)
    }

    @Test
    fun `drain retry re-sends exactly once and gives up on a second refusal`() = runTest {
        val client = HubClient(
            transport = FakeHubTransport(),
            identity = HubClientIdentity("android-t", appVersion = "0"),
            scope = this,
            cursors = InMemoryCursorStore(),
            credentials = { null },
            random = Random(1),
        )
        var calls = 0
        val refused = runCatching {
            client.withDrainRetry(waitMs = 1_000) {
                calls++
                throw HubCommandException("session.create", HubErrorCodes.HUB_DRAINING, "draining")
            }
        }.exceptionOrNull() as HubCommandException
        assertEquals(2, calls)
        assertTrue(refused.isDraining())

        calls = 0
        val other = runCatching {
            client.withDrainRetry<Unit>(waitMs = 1_000) {
                calls++
                throw HubCommandException("session.create", HubErrorCodes.COMMAND_FAILED, "no")
            }
        }.exceptionOrNull() as HubCommandException
        assertEquals("a non-drain error is never retried", 1, calls)
        assertEquals(HubErrorCodes.COMMAND_FAILED, other.code)

        var once = 0
        val ok = client.withDrainRetry(waitMs = 1_000) {
            once++
            if (once == 1) throw HubCommandException("session.create", HubErrorCodes.HUB_DRAINING, "draining") else "created"
        }
        assertEquals("created", ok)
    }
}
