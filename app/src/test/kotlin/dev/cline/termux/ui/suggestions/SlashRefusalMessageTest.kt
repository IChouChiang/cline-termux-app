package dev.cline.termux.ui.suggestions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.chat.SlashRefusal
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C5.5 refusal copy as the prompt queue's C2.5 error text shows it (desktop chat-session.ts verbatim). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlashRefusalMessageTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `team refusals use the desktop sentences`() {
        assertEquals(
            "Usage: /team <task description>. Starts a team of agents for the given task.",
            SlashRefusal.TeamUsage.message(resources),
        )
        assertEquals("Agent teams are not available in plan mode.", SlashRefusal.TeamPlanMode.message(resources))
        assertEquals(
            "Agent teams are disabled. Enable the Teams tool in Customize → Tools.",
            SlashRefusal.TeamDisabled.message(resources),
        )
    }

    @Test
    fun `read failures name the command`() {
        assertEquals("Could not read the skill lint.", SlashRefusal.SkillReadFailed("lint").message(resources))
        assertEquals("Could not read the workflow deploy.", SlashRefusal.WorkflowReadFailed("deploy").message(resources))
    }
}
