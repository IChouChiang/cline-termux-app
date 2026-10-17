package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.tools.AggregateNoun
import dev.cline.termux.data.tools.ToolCountPhrase
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.rememberCopyQuantityResources
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/*
 * Previews of the typed tool labels (DS §8.2): a code argument, a data argument, aggregate
 * counts in both tenses, counted team phrases, a failure label, an interrupted call (A21) and
 * a grouped label; plus
 * tool rows for team and MCP tools, light and dark.
 */

private val previewLabels = listOf(
    ToolLabel.Phrase(ToolPhrase.ReadFileNamed, "LoginViewModel.kt (10–80)"),
    ToolLabel.Phrase(ToolPhrase.SpawningAgentTask, "Fix the login crash"),
    ToolLabel.Aggregate(AggregateNoun.ReadFiles, 1, inProgress = false),
    ToolLabel.Aggregate(AggregateNoun.Searches, 3, inProgress = true),
    ToolLabel.Count(ToolCountPhrase.BroadcastToTeammates, 4),
    ToolLabel.Phrase(ToolPhrase.FailedSpawnTeammate),
    ToolLabel.Text("Linear create issue"),
    ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "echo hook-test")),
)

@LightDarkPreviews
@Composable
private fun ToolLabelKindsPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val mono = ClineMono.label
            val code = remember(mono) { monoSpan(mono) }
            previewLabels.forEach { label ->
                Text(toolLabelText(label, code), style = MaterialTheme.typography.bodyMedium)
            }
            val strings = LocalResources.current
            val quantity = rememberCopyQuantityResources()
            val grouped = remember(strings, quantity) {
                resolveGroupedToolLabel(
                    listOf(
                        ToolLabel.Aggregate(AggregateNoun.ReadFiles, 3, inProgress = false),
                        ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "./gradlew test"),
                    ),
                    strings,
                    code,
                    quantity,
                )
            }
            Text(grouped, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun previewTool(id: String, toolName: String, json: String) = ChatMessage(
    id = id,
    sessionId = "preview",
    role = Role.Tool,
    content = json,
    createdAt = 1_759_480_000_000L,
    meta = Meta(toolCallId = "call_$id", toolName = toolName),
)

@LightDarkPreviews
@Composable
private fun ToolRowTeamAndMcpLabelsPreview() {
    val rows = listOf(
        previewTool("p1", "team_spawn_teammate", """{"toolName":"team_spawn_teammate","input":{},"result":{"agentId":"researcher-1"}}"""),
        previewTool("p2", "team_list_runs", """{"toolName":"team_list_runs","input":{},"result":[{"agentId":"a","status":"done"},{"agentId":"b","status":"running"}]}"""),
        previewTool("p3", "team_broadcast", """{"toolName":"team_broadcast","input":{"subject":"Status"},"result":{"error":"no teammates"},"isError":true}"""),
        previewTool("p4", "mcp_linear_create_issue", """{"toolName":"mcp_linear_create_issue","input":{"title":"Crash"},"result":"Created LIN-42"}"""),
    )
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            rows.forEach { ToolCallRow(toolRowFacts(it, isRunActive = false), callbacks = TranscriptCallbacks()) }
        }
    }
}
