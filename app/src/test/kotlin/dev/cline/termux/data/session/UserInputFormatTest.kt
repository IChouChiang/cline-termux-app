package dev.cline.termux.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the cases of upstream `sdk/packages/shared/src/prompt/format.test.ts` for the helpers
 * [UserInputFormat] carries, plus the app's wrapper-parsing case from the former
 * `SessionReducerFixtureTest`.
 */
class UserInputFormatTest {

    // ---- the app's case (formerly SessionReducerFixtureTest) ---------------------------------

    @Test
    fun `user input wrapper parsing`() {
        val parsed = UserInputFormat.parse(
            """<mode_notice>now in plan</mode_notice><user_input mode="plan">Hello
there</user_input>""",
        )
        assertEquals("Hello\nthere", parsed.text)
        assertEquals(PromptMode.Plan, parsed.mode)
        assertEquals("plain", UserInputFormat.display("plain"))
    }

    // ---- format.test.ts: "prompt format helpers" -----------------------------------------------

    @Test
    fun `parses a user command wrapper`() {
        assertEquals(
            UserInputFormat.UserCommandEnvelope(
                slash = "team",
                content = "spawn a team of agents for the following task: inspect rpc startup",
            ),
            UserInputFormat.parseUserCommandEnvelope(
                """<user_command slash="team">spawn a team of agents for the following task: inspect rpc startup</user_command>""",
            ),
        )
    }

    @Test
    fun `normalizes wrapped user command content for model input`() {
        assertEquals(
            "spawn a team of agents for the following task: inspect rpc startup",
            UserInputFormat.normalizeUserInput(
                """<user_command slash="team">spawn a team of agents for the following task: inspect rpc startup</user_command>""",
            ),
        )
    }

    @Test
    fun `formats wrapped team commands for display`() {
        val wrapped = UserInputFormat.formatUserCommandBlock(
            "spawn a team of agents for the following task: inspect rpc startup",
            "team",
        )
        assertEquals("/team inspect rpc startup", UserInputFormat.formatDisplayUserInput(wrapped))
    }

    @Test
    fun `formats other slash command envelopes for display`() {
        assertEquals(
            "/review the diff",
            UserInputFormat.formatDisplayUserInput(UserInputFormat.formatUserCommandBlock("the diff", "review")),
        )
        assertEquals("/compact", UserInputFormat.formatDisplayUserInput(UserInputFormat.formatUserCommandBlock("", "compact")))
        assertEquals("/team", UserInputFormat.formatDisplayUserInput(UserInputFormat.formatUserCommandBlock("spawn a team of agents for the following task:", "team")))
    }

    @Test
    fun `parses the mode attribute from a user_input wrapper`() {
        assertEquals("plan", UserInputFormat.parseUserInputMode(UserInputFormat.formatUserInputBlock("hello", PromptMode.Plan)))
        assertEquals("act", UserInputFormat.parseUserInputMode(UserInputFormat.formatUserInputBlock("hello", PromptMode.Act)))
    }

    @Test
    fun `parses the mode when a mode notice precedes the wrapper`() {
        val input = UserInputFormat.formatModeSwitchNotice(PromptMode.Act, PromptMode.Plan) +
            UserInputFormat.formatUserInputBlock("hello", PromptMode.Plan)
        assertEquals("plan", UserInputFormat.parseUserInputMode(input))
    }

    @Test
    fun `returns undefined for unwrapped or unknown-mode input`() {
        assertNull(UserInputFormat.parseUserInputMode("plain text"))
        assertNull(UserInputFormat.parseUserInputMode(null))
        assertNull(UserInputFormat.parseUserInputMode("""<user_input mode="warp">hello</user_input>"""))
    }

    @Test
    fun `formats a mode switch notice`() {
        assertEquals(
            "<mode_notice>The user switched from plan mode to act mode before sending this message.</mode_notice>",
            UserInputFormat.formatModeSwitchNotice(PromptMode.Plan, PromptMode.Act),
        )
    }

    @Test
    fun `hides mode switch notices from displayed user input`() {
        val wrapped = UserInputFormat.formatUserInputBlock(
            "${UserInputFormat.formatModeSwitchNotice(PromptMode.Act, PromptMode.Plan)}\nhow should we refactor this?",
            PromptMode.Plan,
        )
        assertEquals("how should we refactor this?", UserInputFormat.formatDisplayUserInput(wrapped))
    }

    @Test
    fun `keeps mode switch notices when normalizing outbound prompts`() {
        val prompt = "${UserInputFormat.formatModeSwitchNotice(PromptMode.Plan, PromptMode.Act)}\ndo it"
        assertEquals(prompt, UserInputFormat.normalizeUserInput(prompt))
    }

    @Test
    fun `removes every mode notice and leaves unclosed ones intact`() {
        assertEquals(
            "hello there",
            UserInputFormat.stripModeNotices("<mode_notice>a</mode_notice>hello<mode_notice>b</mode_notice> there"),
        )
        assertEquals("<mode_notice>dangling", UserInputFormat.stripModeNotices("<mode_notice>dangling"))
    }

    @Test
    fun `strips adversarial repeated open tags in linear time`() {
        val hostile = "<mode_notice>".repeat(50_000)
        val started = System.nanoTime()
        val result = UserInputFormat.stripModeNotices(hostile)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertEquals(hostile, result)
    }
}
