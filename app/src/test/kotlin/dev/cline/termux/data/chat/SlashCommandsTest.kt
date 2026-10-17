package dev.cline.termux.data.chat

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.fakes.FakeTermuxExecutor
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the desktop slash helpers (chat-input-bar.tsx / .test.tsx) and core runtime-commands.test.ts cases. */
class SlashCommandsTest {

    // ---- getActiveSlash -----------------------------------------------------------------------

    @Test fun `slash at the start opens with its query`() {
        assertEquals(ActiveSlash(0, ""), SlashCommands.getActiveSlash("/", 1))
        assertEquals(ActiveSlash(0, "te"), SlashCommands.getActiveSlash("/te", 3))
    }

    @Test fun `slash after whitespace opens, inside a word does not`() {
        assertEquals(ActiveSlash(6, "fo"), SlashCommands.getActiveSlash("hello /fo", 9))
        assertNull(SlashCommands.getActiveSlash("a/b", 3))
        assertNull(SlashCommands.getActiveSlash("src/main", 8))
    }

    @Test fun `whitespace in the query commits the command`() {
        assertNull(SlashCommands.getActiveSlash("/team build", 11))
        assertNull(SlashCommands.getActiveSlash("/team ", 6))
    }

    @Test fun `no second command after a completed one`() {
        assertNull(SlashCommands.getActiveSlash("/team do /x", 11))
    }

    @Test fun `cursor outside the input closes`() {
        assertNull(SlashCommands.getActiveSlash("/a", 3))
        assertNull(SlashCommands.getActiveSlash("/a", -1))
        assertNull(SlashCommands.getActiveSlash("no slash", 3))
    }

    @Test fun `the query is only what is left of the cursor`() {
        assertEquals(ActiveSlash(0, "t"), SlashCommands.getActiveSlash("/team", 2))
    }

    // ---- insertSlashCommandItem ---------------------------------------------------------------

    @Test fun `insert replaces the typed query and adds a separator`() {
        val edit = SlashCommands.insertSlashCommandItem("/te", 3, ActiveSlash(0, "te"), "team")
        assertEquals(TextEdit("/team ", 6), edit)
    }

    @Test fun `insert keeps the text after the cursor without a double space`() {
        // C5.11 step 3: "/" typed before " world" → "/name world".
        val edit = SlashCommands.insertSlashCommandItem("/ world", 1, ActiveSlash(0, ""), "review")
        assertEquals("/review world", edit.text)
        assertEquals(8, edit.cursor)
    }

    @Test fun `insert before a word adds the separator`() {
        val edit = SlashCommands.insertSlashCommandItem("say /wor", 6, ActiveSlash(4, "w"), "workflow")
        assertEquals("say /workflow or", edit.text)
        assertEquals(14, edit.cursor)
    }

    // ---- normalizeRuntimeCommandName (runtime-commands.test.ts) -------------------------------

    @Test fun `names normalize into resolvable tokens`() {
        assertEquals("publish-ui", SlashCommands.normalizeRuntimeCommandName("  Publish UI  "))
        assertEquals("ship-review", SlashCommands.normalizeRuntimeCommandName(" Ship & Review 🚀 "))
        assertEquals("ship-review", SlashCommands.normalizeRuntimeCommandName("SHIP-&-REVIEW"))
        assertEquals("发布", SlashCommands.normalizeRuntimeCommandName("发布"))
        assertEquals("a.b_c:d@e", SlashCommands.normalizeRuntimeCommandName("a.b_c:d@e"))
        assertEquals("", SlashCommands.normalizeRuntimeCommandName(" 🚀 "))
    }

    @Test fun `stable suffix is slug plus 12 hex chars`() {
        val suffix = SlashCommands.stableRuntimeCommandSuffix("skill:🚀")
        assertTrue(suffix, Regex("^skill-[0-9a-f]{12}$").matches(suffix))
        assertEquals(suffix, SlashCommands.stableRuntimeCommandSuffix("skill:🚀"))
    }

    @Test fun `truncateSplit keeps short text and cuts long text at the first sentence`() {
        assertEquals("Ship it", SlashCommands.truncateSplit("Ship it"))
        val long = "First sentence. " + "x".repeat(120)
        assertEquals("First sentence…", SlashCommands.truncateSplit(long))
    }

    @Test fun `truncateSplit never cuts a grapheme in either cut`() {
        val family = "👩‍👩‍👧" // 8 units
        // Prefix cut: one sentence longer than 99 units, the emoji across the limit.
        assertEquals("技".repeat(95) + "…", SlashCommands.truncateSplit("技".repeat(95) + family + "x".repeat(20)))
        // Fallback cut: the text starts with the separator, so the first sentence is empty.
        assertEquals("." + "a".repeat(94) + "…", SlashCommands.truncateSplit("." + "a".repeat(94) + family + "b".repeat(20)))
    }

    // ---- buildUserInstructionSlashCommands (chat-input-bar.test.tsx) --------------------------

    @Test fun `builds slash commands from both workflows and skills`() {
        val snapshot = snapshot(
            skills = listOf(item("skill:publish-ui", "publish-ui-skill", "skill"), item("skill:fork", "fork", "skill")),
            workflows = listOf(item("workflow:release", "release", "workflow", description = "Ship it")),
        )
        val commands = SlashCommands.userCommands(snapshot)
        // The desktop's test drops the `fork` skill behind its `/fork` built-in; the app has none (A19).
        assertEquals(listOf("publish-ui-skill", "fork", "release"), commands.map { it.name })
        assertNull(commands[0].description)
        assertEquals(SlashKind.Skill, commands[0].kind)
        assertEquals(SlashKind.Skill, commands[1].kind)
        assertEquals("Ship it", commands[2].description)
        assertEquals(SlashKind.Workflow, commands[2].kind)
    }

    @Test fun `skills own collisions with workflows, disabled items are skipped, team is reserved`() {
        val snapshot = snapshot(
            skills = listOf(item("s1", "review", "skill"), item("s2", "off", "skill", enabled = false), item("s3", "team", "skill")),
            workflows = listOf(item("w1", "Review", "workflow"), item("w2", "deploy", "workflow")),
        )
        val commands = SlashCommands.userCommands(snapshot)
        assertEquals(listOf("review", "deploy"), commands.map { it.name })
        assertEquals(SlashKind.Skill, commands[0].kind)
    }

    @Test fun `catalog keeps build order`() {
        val snapshot = snapshot(skills = listOf(item("s1", "zeta", "skill"), item("s2", "alpha", "skill")))
        assertEquals(listOf("team", "zeta", "alpha"), SlashCommands.catalog(snapshot).commands.map { it.name })
    }

    // ---- filteredSlashCommands ----------------------------------------------------------------

    @Test fun `empty query takes the first ten in build order`() {
        val commands = (1..12).map { SlashCommand("c$it", null, SlashKind.Skill, null) }
        assertEquals((1..10).map { "c$it" }, SlashCommands.filter(commands, "").map { it.name })
    }

    @Test fun `query filters by contains with prefix matches first, then locale order`() {
        val commands = SlashCommands.BUILTINS + listOf(
            SlashCommand("steam", null, SlashKind.Skill, null),
            SlashCommand("teardown", null, SlashKind.Workflow, null),
            SlashCommand("review", null, SlashKind.Skill, null),
        )
        val collator = Collator.getInstance(Locale.ENGLISH)
        assertEquals(listOf("team", "teardown", "steam"), SlashCommands.filter(commands, "te", collator).map { it.name })
        assertEquals(listOf("team", "steam"), SlashCommands.filter(commands, "TEAM", collator).map { it.name })
        assertTrue(SlashCommands.filter(commands, "zz", collator).isEmpty())
    }

    // ---- front matter, expansion, /team -------------------------------------------------------

    @Test fun `front matter is stripped and the body trimmed`() {
        assertEquals("Do the release.", SlashCommands.stripFrontMatter("---\nname: release\ndescription: x\n---\n\nDo the release.\n"))
        assertEquals("No front matter.", SlashCommands.stripFrontMatter("  No front matter.  "))
        assertEquals("Body", SlashCommands.stripFrontMatter("---\r\nname: a\r\n---\r\nBody"))
        assertEquals("--- not front matter", SlashCommands.stripFrontMatter("--- not front matter"))
    }

    @Test fun `expansion wraps instructions and appends the rest`() {
        assertEquals("<user_command slash=\"release\">Do it.</user_command> now", SlashCommands.expandUserCommand("Do it.", "release", " now "))
        assertEquals("<user_command slash=\"release\">Do it.</user_command>", SlashCommands.expandUserCommand("Do it.", "release", "  "))
    }

    @Test fun `team task needs whitespace or the end after the command`() {
        assertEquals("", SlashCommands.teamTask("/team"))
        assertEquals("write a script", SlashCommands.teamTask("/TEAM   write a script"))
        assertNull(SlashCommands.teamTask("/teams go"))
        assertNull(SlashCommands.teamTask("please /team go"))
    }

    @Test fun `leading command splits name and rest`() {
        assertEquals("release" to " now", SlashCommands.leadingCommand("/release now"))
        assertNull(SlashCommands.leadingCommand("/"))
        assertNull(SlashCommands.leadingCommand("hello /x"))
    }

    // ---- SlashCommandSource.resolve (C5.7) ----------------------------------------------------

    private val root = "/data/data/com.termux/files/home/proj"

    private fun source(termux: FakeTermuxExecutor = FakeTermuxExecutor(), reply: () -> JsonObject = { settingsReply() }): SlashCommandSource =
        SlashCommandSource(settingsList = { reply() }, termux = termux)

    /** A Termux whose `global-settings.json` holds [json]. */
    private fun termuxWithSettings(json: String): FakeTermuxExecutor =
        FakeTermuxExecutor().apply { on(ClineCommands.Labels.READ_GLOBAL_SETTINGS, TermuxResult.success(json)) }

    private fun settingsReply(
        skills: List<JsonObject> = listOf(item("s1", "code-review", "skill", path = "/skills/code-review/SKILL.md")),
        workflows: List<JsonObject> = listOf(item("w1", "release-notes", "workflow", path = "/wf/release-notes.md")),
        tools: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("snapshot", buildJsonObject {
            put("skills", JsonArray(skills))
            put("workflows", JsonArray(workflows))
            put("tools", JsonArray(tools))
        })
    }

    @Test fun `fork is no built-in, so it goes as typed (A19)`() = runTest {
        assertEquals(SlashResolution.Send("/FORK"), source().resolve("  /FORK ", root, PromptMode.Act))
        assertEquals(SlashResolution.Send("/fork now"), source().resolve("/fork now", root, PromptMode.Act))
        assertEquals(listOf(SlashCommands.TEAM), SlashCommands.BUILTINS.map { it.name })
    }

    @Test fun `team usage, disabled, plan mode and rewrite`() = runTest {
        // V-TEAM: teams disabled in global-settings.json, settings.list with no built-in tools (core 0.0.91).
        val s = source(termuxWithSettings("""{"disabledTools":["teams","spawn_agent"]}"""))
        // C5.7 step 2, desktop order: usage, then the disabled tool, then the mode.
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), s.resolve("/team", root, PromptMode.Act))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamDisabled), s.resolve("/team write a script", root, PromptMode.Plan))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamDisabled), s.resolve("/team write a script", root, PromptMode.Act))
        // A plugin tool called `teams` in settings.list does not speak for the built-in.
        val pluginNamedTeams = source(reply = { settingsReply(tools = listOf(item("teams", "teams", "tool", enabled = false))) })
        assertTrue(pluginNamedTeams.resolve("/team write a script", root, PromptMode.Act) is SlashResolution.Send)
        val enabled = source()
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), enabled.resolve("/team", root, PromptMode.Plan))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamPlanMode), enabled.resolve("/team write a script", root, PromptMode.Plan))
        assertEquals(
            SlashResolution.Send("<user_command slash=\"team\">spawn a team of agents for the following task: write a script</user_command>"),
            enabled.resolve("/team write a script", root, PromptMode.Act),
        )
    }

    @Test fun `workflow expands from its file`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(ClineCommands.Labels.READ_TEXT_FILE, TermuxResult.success("---\ndescription: notes\n---\nWrite the release notes."))
        val resolution = source(termux).resolve("/release-notes for 1.2", root, PromptMode.Act)
        assertEquals(SlashResolution.Send("<user_command slash=\"release-notes\">Write the release notes.</user_command> for 1.2"), resolution)
        assertEquals(listOf("/wf/release-notes.md"), termux.commands.single().args)
    }

    @Test fun `workflow read failure refuses with its name`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(ClineCommands.Labels.READ_TEXT_FILE, TermuxResult(stdout = "", stderr = "No such file", exitCode = 1, err = TermuxResult.ERR_NONE, errmsg = null))
        assertEquals(
            SlashResolution.Refused(SlashRefusal.WorkflowReadFailed("release-notes")),
            source(termux).resolve("/release-notes", root, PromptMode.Act),
        )
        termux.throwing(ClineCommands.Labels.READ_TEXT_FILE, TermuxUnavailable.TimedOut(ClineCommands.Labels.READ_TEXT_FILE, 60_000))
        assertEquals(
            SlashResolution.Refused(SlashRefusal.WorkflowReadFailed("release-notes")),
            source(termux).resolve("/release-notes", root, PromptMode.Act),
        )
    }

    @Test fun `skill goes out as typed while the skills tool is available, else expands`() = runTest {
        val termux = FakeTermuxExecutor()
        assertEquals(SlashResolution.Send("/code-review the parser"), source(termux).resolve("/code-review the parser", root, PromptMode.Act))
        assertFalse(termux.ran(ClineCommands.Labels.READ_TEXT_FILE))
        val offTermux = termuxWithSettings("""{"disabledTools":["skills"]}""")
        offTermux.on(ClineCommands.Labels.READ_TEXT_FILE, TermuxResult.success("---\nname: code-review\n---\nReview carefully."))
        val off = source(offTermux)
        assertEquals(
            SlashResolution.Send("<user_command slash=\"code-review\">Review carefully.</user_command> the parser"),
            off.resolve("/code-review the parser", root, PromptMode.Act),
        )
    }

    @Test fun `unknown commands and plain text are sent unchanged`() = runTest {
        assertEquals(SlashResolution.Send("/unknown thing"), source().resolve("/unknown thing", root, PromptMode.Act))
        assertEquals(SlashResolution.Send("hello world"), source().resolve("  hello world ", root, PromptMode.Act))
    }

    @Test fun `refresh failure keeps the previous catalog`() = runTest {
        var fail = false
        val s = SlashCommandSource(settingsList = { if (fail) error("settings_list_failed") else settingsReply() }, termux = FakeTermuxExecutor())
        assertTrue(s.refresh(root).isSuccess)
        fail = true
        assertTrue(s.refresh(root).isFailure)
        assertEquals(listOf("team", "code-review", "release-notes"), s.cached(root)!!.commands.map { it.name })
        assertEquals(1, s.catalogs.value.size)
    }

    @Test fun `invalidate bumps the counter`() {
        val s = source()
        s.invalidate()
        assertEquals(1, s.invalidations.value)
    }

    private fun snapshot(
        skills: List<JsonObject> = emptyList(),
        workflows: List<JsonObject> = emptyList(),
        tools: List<JsonObject> = emptyList(),
    ): SettingsSnapshot = SettingsSnapshot.parse(buildJsonObject {
        put("skills", JsonArray(skills))
        put("workflows", JsonArray(workflows))
        put("tools", JsonArray(tools))
        put("rules", buildJsonArray { })
    })

    private fun item(id: String, name: String, kind: String, enabled: Boolean? = null, description: String? = null, path: String = "/p/$id"): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("path", path)
            put("kind", kind)
            put("source", "global")
            enabled?.let { put("enabled", it) }
            description?.let { put("description", it) }
        }
}
