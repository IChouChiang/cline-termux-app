package dev.cline.termux.ui.chat.rows

import android.content.Context
import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.tools.AggregateNoun
import dev.cline.termux.data.tools.ToolCountPhrase
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typed tool labels → resource text (DS §8.2): spans, plurals, the group separator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolLabelTextTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val code = monoSpan(TextStyle(fontFamily = FontFamily.Monospace))

    private fun english(label: ToolLabel) = resolveToolLabel(label, resources, code = null).text

    /** team.ts `pluralize`. */
    private fun pluralize(count: Int, singular: String, plural: String = "${singular}s") = "$count ${if (count == 1) singular else plural}"

    @Test fun `code arguments become mono spans inside the phrase`() {
        val label = resolveToolLabel(ToolLabel.Phrase(ToolPhrase.RanCommandNamed, "bun test"), resources, code)
        // DS §8.2.6: the code argument is LTR-isolated (U+2066 … U+2069), outside the mono span.
        assertEquals("Ran command \u2066bun test\u2069", label.text)
        assertEquals(1, label.spanStyles.size)
        assertEquals(13, label.spanStyles[0].start)
        assertEquals(21, label.spanStyles[0].end)
        assertEquals("bun test", label.text.substring(label.spanStyles[0].start, label.spanStyles[0].end))
        assertEquals(FontFamily.Monospace, label.spanStyles[0].item.fontFamily)
    }

    @Test fun `grouped labels isolate every code argument and leave prose alone`() {
        val labels = listOf(
            ToolLabel.Phrase(ToolPhrase.ReadFileNamed, "-n 5 src/a.ts"),
            ToolLabel.Phrase(ToolPhrase.SpawnedAgentTask, "Fix it"),
        )
        val styled = resolveGroupedToolLabel(labels, resources, code)
        assertEquals("Read file \u2066-n 5 src/a.ts\u2069 · Spawned agent: Fix it", styled.text)
        assertEquals(1, styled.spanStyles.size)
        // Plain text (TalkBack, copy) never carries the isolates.
        assertEquals("Read file -n 5 src/a.ts · Spawned agent: Fix it", resolveGroupedToolLabel(labels, resources, code = null).text)
        assertEquals("Read file ", resolveToolLabel(ToolLabel.Phrase(ToolPhrase.ReadFileNamed, ""), resources, code).text)
    }

    @Test fun `data arguments stay plain text`() {
        val task = resolveToolLabel(ToolLabel.Phrase(ToolPhrase.SpawnedAgentTask, "Fix the login bug"), resources, code)
        assertEquals("Spawned agent: Fix the login bug", task.text)
        assertTrue(task.spanStyles.isEmpty())
        val question = resolveToolLabel(ToolLabel.Phrase(ToolPhrase.AskingQuestionText, "Which env?"), resources, code)
        assertEquals("Asking \"Which env?\"", question.text)
        assertTrue(question.spanStyles.isEmpty())
        assertEquals("Running Custom tool", english(ToolLabel.Phrase(ToolPhrase.RunningTool, "Custom tool")))
        assertEquals("Custom tool", english(ToolLabel.Text("Custom tool")))
    }

    @Test fun `an interrupted call names its specific and keeps code spans (A21)`() {
        val command = resolveToolLabel(
            ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "echo hook-test")),
            resources,
            code,
        )
        assertEquals("Interrupted: \u2066echo hook-test\u2069", command.text)
        assertEquals(1, command.spanStyles.size)
        assertEquals("echo hook-test", command.text.substring(command.spanStyles[0].start, command.spanStyles[0].end))
        assertEquals("Interrupted: echo hook-test", english(ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "echo hook-test"))))
        // Data arguments stay plain.
        val tool = resolveToolLabel(ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningTool, "Custom tool")), resources, code)
        assertEquals("Interrupted: Custom tool", tool.text)
        assertTrue(tool.spanStyles.isEmpty())
        assertEquals("Interrupted: Linear create issue", english(ToolLabel.Interrupted(ToolLabel.Text("Linear create issue"))))
        // Nothing specific to name: the word alone, never the in-progress verb.
        assertEquals("Interrupted", english(ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommand))))
        assertEquals("Interrupted", english(ToolLabel.Interrupted(ToolLabel.Aggregate(AggregateNoun.Commands, 3, inProgress = true))))
        assertEquals("Interrupted", english(ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.ReadFileNamed, ""))))
    }

    @Test fun `a null code style leaves code arguments unstyled`() {
        val label = resolveToolLabel(ToolLabel.Phrase(ToolPhrase.ReadFileNamed, "a.ts"), resources, code = null)
        assertEquals("Read file a.ts", label.text)
        assertTrue(label.spanStyles.isEmpty())
    }

    @Test fun `every phrase resolves without leftover placeholders`() {
        ToolPhrase.entries.forEach { phrase ->
            val text = english(ToolLabel.Phrase(phrase, "ARG"))
            assertTrue(phrase.name, text.isNotBlank())
            assertFalse(phrase.name, text.contains('%'))
            assertFalse(phrase.name, text.any { it in ''..'' })
        }
    }

    @Test fun `aggregates pluralize with the desktop verbs and nouns`() {
        data class Row(val noun: AggregateNoun, val completed: String, val progress: String, val singular: String, val plural: String? = null)
        val rows = listOf(
            Row(AggregateNoun.ReadFiles, "Read", "Reading", "file"),
            Row(AggregateNoun.EditedFiles, "Edited", "Editing", "file"),
            Row(AggregateNoun.Commands, "Ran", "Running", "command"),
            Row(AggregateNoun.Searches, "Explored", "Exploring", "search", "searches"),
            Row(AggregateNoun.Links, "Explored", "Exploring", "link"),
            Row(AggregateNoun.SpawnedAgents, "Spawned", "Spawning", "agent"),
            Row(AggregateNoun.TeamSpawn, "Spawned", "Spawning", "teammate"),
            Row(AggregateNoun.TeamRunTask, "Assigned", "Assigning", "team task", "team tasks"),
            Row(AggregateNoun.TeamShutdown, "Stopped", "Stopping", "teammate"),
            Row(AggregateNoun.TeamTaskCreate, "Created", "Creating", "team task"),
            Row(AggregateNoun.TeamTaskList, "Listed", "Listing", "team task"),
            Row(AggregateNoun.TeamTaskClaim, "Claimed", "Claiming", "team task"),
            Row(AggregateNoun.TeamTaskComplete, "Completed", "Completing", "team task"),
            Row(AggregateNoun.TeamTaskBlock, "Blocked", "Blocking", "team task"),
            Row(AggregateNoun.TeamTaskUpdate, "Updated", "Updating", "team task"),
            Row(AggregateNoun.TeamSendMessage, "Sent", "Sending", "message"),
        )
        assertEquals(AggregateNoun.entries.toSet(), rows.map { it.noun }.toSet())
        for (row in rows) {
            for (count in listOf(0, 1, 2, 12)) {
                val counted = row.plural?.let { pluralize(count, row.singular, it) } ?: pluralize(count, row.singular)
                assertEquals("${row.completed} $counted", english(ToolLabel.Aggregate(row.noun, count, inProgress = false)))
                assertEquals("${row.progress} $counted", english(ToolLabel.Aggregate(row.noun, count, inProgress = true)))
            }
        }
    }

    @Test fun `counted team phrases pluralize like team_ts`() {
        for (count in listOf(0, 1, 3)) {
            assertEquals("Listed ${pluralize(count, "teammate run")}", english(ToolLabel.Count(ToolCountPhrase.ListedTeammateRuns, count)))
            assertEquals("Broadcast message to ${pluralize(count, "teammate")}", english(ToolLabel.Count(ToolCountPhrase.BroadcastToTeammates, count)))
            assertEquals("Read ${pluralize(count, "team message")}", english(ToolLabel.Count(ToolCountPhrase.ReadTeamMessages, count)))
            assertEquals("Listed ${pluralize(count, "team outcome")}", english(ToolLabel.Count(ToolCountPhrase.ListedTeamOutcomes, count)))
        }
    }

    @Test fun `grouped segments join with the separator and keep their spans`() {
        val labels = listOf(
            ToolLabel.Aggregate(AggregateNoun.ReadFiles, 2, inProgress = false),
            ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "ls"),
            ToolLabel.Text("Custom tool"),
        )
        val joined = resolveGroupedToolLabel(labels, resources, code)
        assertEquals("Read 2 files · Running command \u2066ls\u2069 · Custom tool", joined.text)
        assertEquals(1, joined.spanStyles.size)
        val span = joined.spanStyles[0]
        assertEquals("ls", joined.text.substring(span.start, span.end))
        assertEquals("", resolveGroupedToolLabel(emptyList(), resources, code).text)
        assertEquals("Read 1 file", resolveGroupedToolLabel(listOf(ToolLabel.Aggregate(AggregateNoun.ReadFiles, 1, false)), resources, code).text)
    }

    @Test fun `the monospace span copies the mono style's typography only`() {
        val span: SpanStyle = monoSpan(TextStyle(fontFamily = FontFamily.Monospace))
        assertEquals(FontFamily.Monospace, span.fontFamily)
        assertEquals(Color.Unspecified, span.color)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `English plurals keep their one form on a Chinese device`() {
        assertEquals("Read 1 file", english(ToolLabel.Aggregate(AggregateNoun.ReadFiles, 1, inProgress = false)))
        assertEquals("Explored 1 search", english(ToolLabel.Aggregate(AggregateNoun.Searches, 1, inProgress = false)))
        assertEquals("Listed 1 teammate run", english(ToolLabel.Count(ToolCountPhrase.ListedTeammateRuns, 1)))
    }
}
