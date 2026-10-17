package dev.cline.termux.data.chat

import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsKind
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SessionSignal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1.7 checks and failure table, C5.7 slash resolution (desktop + CLI semantics). */
class SendPipelineTest {

    private val reads = mutableListOf<String>()
    private val files = mutableMapOf<String, String>()

    /** `global-settings.json` `disabledTools` as the pipeline reads it; [settingsReads] counts the reads. */
    private val disabled = mutableSetOf<String>()
    private var settingsReads = 0
    private val pipeline = SendPipeline(
        disabledTools = {
            settingsReads++
            disabled.toSet()
        },
    ) { path ->
        reads += path
        files[path]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("exit 1"))
    }

    /**
     * A recorded core 0.0.91 `settings.list` reply (docs/08 fixture 01, `settings-list-003`): the
     * built-in tools are not in `snapshot.tools`, which holds plugin tools only.
     */
    private val recordedReply = Json.parseToJsonElement(
        """{"snapshot":{"workflows":[],"rules":[],"plugins":[],"skills":[],"tools":[],"mcp":[]}}""",
    ).jsonObject

    private fun item(name: String, kind: SettingsKind, path: String = "/p/$name", enabled: Boolean? = true) =
        SettingsItem(id = "$kind:$name", name = name, path = path, kind = kind, source = "workspace", enabled = enabled)

    private fun snapshot(
        skills: List<SettingsItem> = emptyList(),
        workflows: List<SettingsItem> = emptyList(),
        tools: List<SettingsItem> = emptyList(),
    ) = SettingsSnapshot(skills = skills, workflows = workflows, tools = tools)

    // ---- precheck (C1.7 steps 1–4) ---------------------------------------------------------

    private fun check(
        canSend: Boolean = true,
        text: String = "hi",
        attachments: Int = 0,
        imagesUnsupported: Boolean = false,
        configured: Boolean? = true,
    ) = SendPipeline.precheck(SendCheckInput(canSend, text, attachments, imagesUnsupported, configured))

    @Test fun precheckPassesAPlainPrompt() = assertNull(check())

    @Test fun precheckOrder() {
        assertEquals(SendBlock.NotAllowed, check(canSend = false, imagesUnsupported = true))
        assertEquals(SendBlock.ImagesUnsupported, check(text = "", attachments = 1, imagesUnsupported = true, configured = false))
        assertEquals(SendBlock.NeedsText, check(text = "  ", attachments = 2, configured = false))
        assertEquals(SendBlock.ConnectModel, check(configured = false))
    }

    @Test fun unknownCatalogNeverBlocks() = assertNull(check(configured = null))

    @Test fun blankWithoutAttachmentsIsNotAllowed() = assertEquals(SendBlock.NotAllowed, check(text = "   "))

    // ---- slash resolution (C5.7) ---------------------------------------------------------------

    @Test fun forkIsNoBuiltinAndGoesAsTyped() = runTest {
        // A19: the fork lives in the chat three-dot only, so `/fork` is plain text here.
        assertEquals(SlashResolution.Send("/fork"), pipeline.resolve("/fork", PromptMode.Act, null))
        assertEquals(SlashResolution.Send("/FORK"), pipeline.resolve("  /FORK ", PromptMode.Act, null))
        assertEquals(SlashResolution.Send("/fork now"), pipeline.resolve("/fork now", PromptMode.Act, null))
        assertEquals(setOf("team"), SendPipeline.BUILTIN_NAMES)
    }

    @Test fun teamRewritesTheTask() = runTest {
        val result = pipeline.resolve("/team write a script", PromptMode.Act, snapshot())
        assertEquals(
            SlashResolution.Send("<user_command slash=\"team\">spawn a team of agents for the following task: write a script</user_command>"),
            result,
        )
    }

    @Test fun teamWithoutTaskShowsUsage() = runTest {
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), pipeline.resolve("/team", PromptMode.Act, null))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), pipeline.resolve("/team   ", PromptMode.Act, null))
    }

    @Test fun teamInPlanModeIsRefused() = runTest {
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamPlanMode), pipeline.resolve("/team write a script", PromptMode.Plan, null))
    }

    @Test fun teamWithTeamsDisabledInGlobalSettingsIsRefused() = runTest {
        // V-TEAM (2026-10-08): the owner's disabledTools lists `teams`, and the real settings.list
        // reply has no `teams` entry at all. The task must be refused before anything is sent.
        disabled += setOf("teams", "spawn_agent", "team_status")
        val snap = SettingsSnapshot.fromReply(recordedReply)
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamDisabled), pipeline.resolve("/team list the files in this folder and say done", PromptMode.Act, snap))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamDisabled), pipeline.resolve("/team write a script", PromptMode.Act, null))
        // Desktop order: the disabled tool before the mode.
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamDisabled), pipeline.resolve("/team write a script", PromptMode.Plan, snap))
    }

    @Test fun bareTeamShowsUsageEvenWithTeamsDisabled() = runTest {
        // Desktop `rewriteDesktopTeamPrompt` checks usage first, then the disabled tool.
        disabled += "teams"
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), pipeline.resolve("/team", PromptMode.Act, SettingsSnapshot.fromReply(recordedReply)))
        assertEquals(SlashResolution.Refused(SlashRefusal.TeamUsage), pipeline.resolve("/team  ", PromptMode.Plan, null))
        assertEquals("usage needs no settings read", 0, settingsReads)
    }

    @Test fun teamsEnabledWhenOtherToolsAreDisabled() = runTest {
        disabled += setOf("tasks", "web_search")
        assertEquals(
            SlashResolution.Send("<user_command slash=\"team\">spawn a team of agents for the following task: go</user_command>"),
            pipeline.resolve("/team go", PromptMode.Act, SettingsSnapshot.fromReply(recordedReply)),
        )
    }

    @Test fun aPluginToolNamedTeamsInSettingsListIsNotTheBuiltin() = runTest {
        // snapshot.tools is plugin tools; only disabledTools speaks for the built-in.
        val snap = snapshot(tools = listOf(item("teams", SettingsKind.Tool, enabled = false), item("skills", SettingsKind.Tool, enabled = false)))
        assertTrue(pipeline.resolve("/team go", PromptMode.Act, snap) is SlashResolution.Send)
    }

    @Test fun plainTextAndUnknownWordsReadNoSettings() = runTest {
        pipeline.resolve("hello", PromptMode.Act, null)
        pipeline.resolve("/nope x", PromptMode.Act, snapshot())
        assertEquals(0, settingsReads)
    }

    @Test fun teamPrefixOfAnotherWordIsNotTeam() = runTest {
        assertEquals(SlashResolution.Send("/teams list"), pipeline.resolve("/teams list", PromptMode.Act, snapshot()))
    }

    @Test fun workflowIsExpandedWithoutFrontMatter() = runTest {
        files["/p/release-notes"] = "---\nname: release-notes\ndescription: x\n---\n\nWrite the notes.\n"
        val result = pipeline.resolve("/release-notes for 1.2", PromptMode.Act, snapshot(workflows = listOf(item("release-notes", SettingsKind.Workflow))))
        assertEquals(SlashResolution.Send("<user_command slash=\"release-notes\">Write the notes.</user_command> for 1.2"), result)
        assertEquals(listOf("/p/release-notes"), reads)
    }

    @Test fun workflowReadFailureKeepsTheDraft() = runTest {
        val result = pipeline.resolve("/deploy", PromptMode.Act, snapshot(workflows = listOf(item("deploy", SettingsKind.Workflow))))
        assertEquals(SlashResolution.Refused(SlashRefusal.WorkflowReadFailed("deploy")), result)
    }

    @Test fun skillGoesAsTypedWhileTheSkillsToolIsAvailable() = runTest {
        val result = pipeline.resolve("/code-review the diff", PromptMode.Plan, snapshot(skills = listOf(item("code-review", SettingsKind.Skill))))
        assertEquals(SlashResolution.Send("/code-review the diff"), result)
        assertTrue(reads.isEmpty())
    }

    @Test fun skillIsExpandedWhenTheSkillsToolIsOff() = runTest {
        files["/s/SKILL.md"] = "---\nname: Code Review\n---\nReview for correctness."
        disabled += "skills"
        val snap = snapshot(skills = listOf(item("Code Review", SettingsKind.Skill, path = "/s/SKILL.md")))
        assertEquals(
            SlashResolution.Send("<user_command slash=\"code-review\">Review for correctness.</user_command>"),
            pipeline.resolve("/code-review", PromptMode.Act, snap),
        )
    }

    @Test fun skillReadFailure() = runTest {
        disabled += "skills"
        val snap = snapshot(skills = listOf(item("lint", SettingsKind.Skill)))
        assertEquals(SlashResolution.Refused(SlashRefusal.SkillReadFailed("lint")), pipeline.resolve("/lint", PromptMode.Act, snap))
    }

    @Test fun noSnapshotSendsSlashWordsAsTyped() = runTest {
        // No snapshot means no skills either: unknown words go as typed.
        assertEquals(SlashResolution.Send("/lint"), pipeline.resolve("/lint", PromptMode.Act, null))
    }

    @Test fun unknownCommandsAndPlainTextGoAsTyped() = runTest {
        assertEquals(SlashResolution.Send("/nope x"), pipeline.resolve("/nope x", PromptMode.Act, snapshot()))
        assertEquals(SlashResolution.Send("hello /world"), pipeline.resolve(" hello /world ", PromptMode.Act, snapshot()))
    }

    @Test fun disabledItemsAreSkipped() = runTest {
        files["/p/deploy"] = "Deploy."
        val snap = snapshot(workflows = listOf(item("deploy", SettingsKind.Workflow, enabled = false)))
        assertEquals(SlashResolution.Send("/deploy"), pipeline.resolve("/deploy", PromptMode.Act, snap))
    }

    @Test fun userCommandsInBuildOrderWithCollisions() {
        val snap = snapshot(
            skills = listOf(item("Zeta Skill", SettingsKind.Skill), item("shared", SettingsKind.Skill), item("fork", SettingsKind.Skill)),
            workflows = listOf(item("alpha", SettingsKind.Workflow), item("shared", SettingsKind.Workflow), item("team", SettingsKind.Workflow)),
        )
        val commands = SendPipeline.userCommands(snap)
        // Only `team` is reserved: without a `/fork` built-in (A19) the owner's `fork` skill is a command.
        assertEquals(listOf("zeta-skill", "shared", "fork", "alpha"), commands.map { it.name })
        assertEquals(UserSlashCommand.Kind.Skill, commands.first { it.name == "shared" }.kind)
    }

    @Test fun normalizeRuntimeCommandName() {
        assertEquals("code-review", SendPipeline.commandName("  Code Review "))
        assertEquals("a-b", SendPipeline.commandName("a!!!b"))
        assertEquals("发布", SendPipeline.commandName("发布"))
        assertEquals("v1.2_x", SendPipeline.commandName("v1.2_x"))
        assertEquals("x", SendPipeline.commandName("--x--"))
    }

    @Test fun frontMatterOnlyAtTheStart() {
        assertEquals("Body", SendPipeline.stripFrontMatter("---\na: 1\n---\nBody"))
        assertEquals("Body\n---\nx\n---", SendPipeline.stripFrontMatter("Body\n---\nx\n---"))
        assertEquals("Body", SendPipeline.stripFrontMatter("﻿---\r\na: 1\r\n---\r\nBody"))
    }

    // ---- failure table (C1.7) -------------------------------------------------------------------

    @Test fun outcomes() {
        assertEquals(SendResult.Accepted(direct = true), SendPipeline.resultOf(SendOutcome.Sent("r")))
        assertEquals(SendResult.Accepted(direct = false), SendPipeline.resultOf(SendOutcome.Queued("r")))
        assertEquals(SendResult.Accepted(direct = true), SendPipeline.resultOf(SendOutcome.Held))
        assertEquals(SendResult.Disconnected, SendPipeline.resultOf(SendOutcome.AlreadyHeld))
        assertEquals(SendResult.Disconnected, SendPipeline.resultOf(SendOutcome.NotConnected))
        assertEquals(SendResult.ResumeFailed, SendPipeline.resultOf(SendOutcome.ResumeFailed("x")))
    }

    @Test fun signals() {
        assertEquals(SendResult.ResumeFailed, SendPipeline.resultOf(SessionSignal.NotSent("s", "p", emptyList())))
        assertEquals(SendResult.KernelDown, SendPipeline.resultOf(SessionSignal.NotSent("s", "p", emptyList(), kernelDown = true)))
        assertEquals(SendResult.RestoreOnly, SendPipeline.resultOf(SessionSignal.SendFailed("s", "invalid_session_input", "bad")))
        assertEquals(SendResult.Disconnected, SendPipeline.resultOf(SessionSignal.SendFailed("s", "hub_connection_closed", "closed")))
        assertEquals(SendResult.Failed("quota"), SendPipeline.resultOf(SessionSignal.SendFailed("s", "command_failed", "quota")))
        assertNull(SendPipeline.resultOf(SessionSignal.Interrupted("s", "r")))
    }
}
