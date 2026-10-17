package dev.cline.termux.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.agents.SessionAgentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The resource copy of `describeAgentActivity` keeps the desktop wording; the cases are the
 * desktop `lib/session-agents.test.ts` ones plus the bucket edges.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentsCopyTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `resources match the desktop wording`() {
        val cases = listOf(
            SessionAgentActivity(total = 1) to "1 agent",
            SessionAgentActivity(total = 1, running = 1) to "1 agent: 1 running",
            SessionAgentActivity(total = 3, running = 1, completed = 2) to "3 agents: 1 running, 2 completed",
            SessionAgentActivity(total = 5, completed = 1, failed = 1, cancelled = 1, unresolved = 2) to
                "5 agents: 1 completed, 1 failed, 1 cancelled, 2 with no recorded outcome",
            SessionAgentActivity(total = 2, unresolved = 2) to "2 agents: 2 with no recorded outcome",
        )
        cases.forEach { (activity, expected) -> assertEquals(expected, describeAgentActivity(resources, activity)) }
    }

    @Test
    fun `describes a single running agent`() {
        assertEquals("1 agent: 1 running", describeAgentActivity(resources, SessionAgentActivity(total = 1, running = 1)))
    }

    @Test
    fun `lists every non-empty bucket`() {
        assertEquals(
            "5 agents: 1 running, 2 completed, 1 failed, 1 cancelled",
            describeAgentActivity(resources, SessionAgentActivity(total = 5, running = 1, completed = 2, failed = 1, cancelled = 1)),
        )
    }

    @Test
    fun `describes runs with no recorded outcome`() {
        assertEquals(
            "2 agents: 1 completed, 1 with no recorded outcome",
            describeAgentActivity(resources, SessionAgentActivity(total = 2, completed = 1, unresolved = 1)),
        )
        assertEquals("0 agents", describeAgentActivity(resources, SessionAgentActivity.EMPTY))
    }

    @Test
    fun `banner sentences join through the resource`() {
        assertEquals("Cline is not running. Start it from Termux.", joinBannerLines(resources, "Cline is not running.", "Start it from Termux."))
        assertEquals("Cline is not running.", joinBannerLines(resources, "Cline is not running.", null))
    }
}
