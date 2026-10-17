package dev.cline.termux.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * team.ts `teamSummary` through `buildToolSummary`: the desktop's tool-summary.test.ts team
 * cases plus every team label in both tenses and every failure label, asserted against the
 * desktop's English (team.ts literals) through the resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamSummaryTest {
    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun team(
        toolName: String,
        input: String? = null,
        result: String? = null,
        inProgress: Boolean = false,
        isError: Boolean = false,
    ) = buildToolSummary(ToolSummaryPayload(toolName, input?.let(::j), result?.let(::j), isError, inProgress))

    private fun labels(toolName: String, input: String? = null, result: String? = null) =
        team(toolName, input, result, inProgress = true).english to team(toolName, input, result).english

    // desktop tool-summary.test.ts "team summaries"

    @Test fun `labels team_spawn_teammate with the agent id detail`() {
        val result = team("team_spawn_teammate", result = """{"agentId":"researcher-1"}""")
        assertEquals("Spawned 1 teammate", result.english)
        assertEquals(listOf("researcher-1"), result.details)
    }

    @Test fun `uses failure labels for errored team tools`() {
        assertEquals("Failed to broadcast message to teammates", team("team_broadcast", isError = true).english)
    }

    // every team label

    @Test fun `aggregated team labels use the desktop verbs and nouns`() {
        assertEquals("Spawning 1 teammate" to "Spawned 1 teammate", labels("team_spawn_teammate"))
        assertEquals("Assigning 1 team task" to "Assigned 1 team task", labels("team_run_task"))
        assertEquals("Stopping 1 teammate" to "Stopped 1 teammate", labels("team_shutdown_teammate"))
        assertEquals("Sending 1 message" to "Sent 1 message", labels("team_send_message"))
        assertEquals("Creating 1 team task" to "Created 1 team task", labels("team_task", """{"action":"create"}"""))
        assertEquals("Claiming 1 team task" to "Claimed 1 team task", labels("team_task", """{"action":"claim"}"""))
        assertEquals("Completing 1 team task" to "Completed 1 team task", labels("team_task", """{"action":"complete"}"""))
        assertEquals("Blocking 1 team task" to "Blocked 1 team task", labels("team_task", """{"action":"block"}"""))
        assertEquals("Updating 1 team task" to "Updated 1 team task", labels("team_task", """{"action":"rename"}"""))
        assertEquals("Updating 1 team task" to "Updated 1 team task", labels("team_task"))
    }

    @Test fun `team_task list counts the listed tasks`() {
        val listed = team("team_task", """{"action":"list"}""", """{"tasks":[{"id":"t1","title":"A","status":"open"},{"id":"t2"},{"id":"t3"}]}""")
        assertEquals("Listed 3 team tasks", listed.english)
        assertEquals(listOf("t1 A open", "t2", "t3"), listed.details)
        assertEquals(ToolAggregate("team-task-list", 3, AggregateNoun.TeamTaskList), listed.aggregate)
        assertEquals("Listed 0 team tasks", team("team_task", """{"action":"list"}""", """{"tasks":[]}""").english)
        assertEquals("Listing 1 team task", team("team_task", """{"action":"list"}""", """{"tasks":[{"id":"t1"}]}""", inProgress = true).english)
    }

    @Test fun `team_run_task details carry mode, agent and status`() {
        assertEquals(listOf("sync researcher-1 assigned"), team("team_run_task", """{"agentId":"researcher-1"}""").details)
        assertEquals(listOf("async r2 assigning"), team("team_run_task", """{"agentId":"r2","runMode":"async"}""", inProgress = true).details)
    }

    @Test fun `fixed team labels in both tenses`() {
        assertEquals("Waiting for teammates" to "Waited for teammates", labels("team_await_runs"))
        assertEquals("Checking team status" to "Checked team status", labels("team_status"))
        assertEquals("Cancelling teammate run" to "Cancelled teammate run", labels("team_cancel_run"))
        assertEquals("Updating mission log" to "Updated mission log", labels("team_mission_log"))
        assertEquals("Cleaning up team" to "Cleaned up team", labels("team_cleanup"))
        assertEquals("Creating team outcome" to "Created team outcome", labels("team_create_outcome"))
        assertEquals("Attaching outcome fragment" to "Attached outcome fragment", labels("team_attach_outcome_fragment"))
        assertEquals("Reviewing outcome fragment" to "Reviewed outcome fragment", labels("team_review_outcome_fragment"))
        assertEquals("Finalizing team outcome" to "Finalized team outcome", labels("team_finalize_outcome"))
    }

    @Test fun `counted team labels pluralize like team_ts`() {
        assertEquals("Listing teammate runs" to "Listed 2 teammate runs", labels("team_list_runs", result = """[{"agentId":"a"},{"agentId":"b"}]"""))
        assertEquals("Listed 1 teammate run", team("team_list_runs", result = """[{"agentId":"a","status":"done"}]""").english)
        assertEquals("Listed 0 teammate runs", team("team_list_runs").english)
        assertEquals(
            "Broadcasting message to teammates" to "Broadcast message to 3 teammates",
            labels("team_broadcast", """{"subject":"Status"}""", """{"delivered":3}"""),
        )
        assertEquals("Broadcast message to 1 teammate", team("team_broadcast", result = """{"delivered":1}""").english)
        assertEquals("Broadcast message to 0 teammates", team("team_broadcast").english)
        assertEquals("Reading team mailbox" to "Read 2 team messages", labels("team_read_mailbox", result = """[{"fromAgentId":"a"},{"fromAgentId":"b"}]"""))
        assertEquals("Read 1 team message", team("team_read_mailbox", result = """{"fromAgentId":"a","subject":"Hi"}""").english)
        assertEquals("Listing team outcomes" to "Listed 1 team outcome", labels("team_list_outcomes", result = """[{"title":"Plan"}]"""))
        assertEquals("Listed 2 team outcomes", team("team_list_outcomes", result = """[{"id":"o1"},{"id":"o2"}]""").english)
    }

    @Test fun `every team failure label matches team_ts`() {
        val expected = mapOf(
            "team_attach_outcome_fragment" to "Failed to attach outcome fragment",
            "team_await_runs" to "Failed while waiting for teammates",
            "team_broadcast" to "Failed to broadcast message to teammates",
            "team_cancel_run" to "Failed to cancel teammate run",
            "team_cleanup" to "Failed to clean up team",
            "team_create_outcome" to "Failed to create team outcome",
            "team_finalize_outcome" to "Failed to finalize team outcome",
            "team_list_outcomes" to "Failed to list team outcomes",
            "team_list_runs" to "Failed to list teammate runs",
            "team_mission_log" to "Failed to update mission log",
            "team_read_mailbox" to "Failed to read team mailbox",
            "team_review_outcome_fragment" to "Failed to review outcome fragment",
            "team_run_task" to "Failed to assign team task",
            "team_send_message" to "Failed to send message",
            "team_shutdown_teammate" to "Failed to stop teammate",
            "team_spawn_teammate" to "Failed to spawn teammate",
            "team_status" to "Failed to check team status",
            "team_task" to "Failed to update team task",
        )
        expected.forEach { (tool, label) -> assertEquals(tool, label, team(tool, isError = true).english) }
        // An unknown team tool keeps its raw name as data.
        assertEquals("Failed team_reticulate", team("team_reticulate", isError = true).english)
        assertEquals(ToolLabel.Phrase(ToolPhrase.FailedTool, "team_reticulate"), team("team_reticulate", isError = true).label)
    }

    @Test fun `an unknown team tool without an error falls back to its humanized name`() {
        assertNull(teamSummary("team_reticulate", null, null, inProgress = false, isError = false))
        assertEquals("Team reticulate", team("team_reticulate").english)
    }

    @Test fun `team aggregates merge per key in grouped labels`() {
        val spawns = List(2) { team("team_spawn_teammate", result = """{"agentId":"r$it"}""") }
        val message = team("team_send_message", """{"toAgentId":"r0","subject":"Go"}""")
        val inputs = (spawns + message).map { GroupedToolLabelInput(it.label, it.aggregate) }
        assertEquals("Spawned 2 teammates · Sent 1 message", EnglishToolLabels.group(buildGroupedToolLabel(inputs)))
        // Different team_task actions keep separate keys.
        val create = team("team_task", """{"action":"create"}""")
        val claim = team("team_task", """{"action":"claim"}""")
        val tasks = listOf(create, create, claim).map { GroupedToolLabelInput(it.label, it.aggregate) }
        assertEquals("Created 2 team tasks · Claimed 1 team task", EnglishToolLabels.group(buildGroupedToolLabel(tasks)))
    }
}
