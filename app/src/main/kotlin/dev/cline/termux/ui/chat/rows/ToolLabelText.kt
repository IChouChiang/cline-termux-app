package dev.cline.termux.ui.chat.rows

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.cline.termux.R
import dev.cline.termux.data.tools.AggregateNoun
import dev.cline.termux.data.tools.ToolCountPhrase
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import dev.cline.termux.ui.components.rememberCopyQuantityResources
import dev.cline.termux.ui.kernel.quantityResources

/*
 * Typed tool labels ([ToolLabel], data/tools/ToolLabel.kt) → text (DS §8.2.1-§8.2.3). Every
 * phrase is a strings_transcript.xml entry, every count a `<plurals>` resolved with the
 * copy language's plural rules ([quantityResources]), and grouped segments are joined with
 * the `chat_tool_label_group` format, never concatenated. A code argument (file name,
 * command, query, URL, skill) is spliced into its phrase as a `ClineMono` span inside an
 * LTR isolate (U+2066 … U+2069).
 */

/** The code span of a mono text style (family, size, features, tracking; colour stays the row's). */
internal fun monoSpan(mono: TextStyle): SpanStyle = SpanStyle(
    fontFamily = mono.fontFamily,
    fontSize = mono.fontSize,
    fontFeatureSettings = mono.fontFeatureSettings,
    letterSpacing = mono.letterSpacing,
)

/** U+2066 LEFT-TO-RIGHT ISOLATE / U+2069 POP DIRECTIONAL ISOLATE. */
private const val LRI = '\u2066'
private const val PDI = '\u2069'

/** Private-use placeholders the format arguments are spliced back into (one char per argument). */
private const val ARG_MARK = ''

private fun mark(index: Int): String = (ARG_MARK + index).toString()

/** Replaces each [mark] in [formatted] with its argument, keeping the arguments' spans. */
private fun splice(formatted: String, args: List<AnnotatedString>): AnnotatedString = buildAnnotatedString {
    formatted.forEach { char ->
        val index = char - ARG_MARK
        if (index in args.indices) append(args[index]) else append(char)
    }
}

/**
 * [label] as text: [strings] resolves phrases, [quantity] the plurals (pass
 * `quantityResources(strings)`, so English copy keeps its "one" form on a CJK device).
 * [code] styles code arguments; null leaves them plain (accessibility text, copy).
 */
fun resolveToolLabel(
    label: ToolLabel,
    strings: Resources,
    code: SpanStyle?,
    quantity: Resources = quantityResources(strings),
): AnnotatedString = when (label) {
    is ToolLabel.Text -> AnnotatedString(label.text)
    is ToolLabel.Count -> AnnotatedString(plural(quantity, label.phrase.pluralRes(), label.count))
    is ToolLabel.Aggregate -> AnnotatedString(plural(quantity, label.noun.pluralRes(label.inProgress), label.count))
    is ToolLabel.Phrase -> {
        val res = label.phrase.stringRes()
        val arg = label.arg
        if (arg == null) {
            AnnotatedString(strings.getString(res))
        } else {
            splice(strings.getString(res, mark(0)), listOf(argument(arg, label.phrase.codeArg, code)))
        }
    }
    is ToolLabel.Interrupted -> {
        // A21: "Interrupted: <the call's specific>", or "Interrupted" when the label has none.
        val base = label.base
        val subject = when (base) {
            is ToolLabel.Phrase -> base.arg?.takeIf { it.isNotEmpty() }?.let { argument(it, base.phrase.codeArg, code) }
            is ToolLabel.Text -> base.text.takeIf { it.isNotEmpty() }?.let(::AnnotatedString)
            is ToolLabel.Interrupted, is ToolLabel.Count, is ToolLabel.Aggregate -> null
        }
        if (subject == null) {
            AnnotatedString(strings.getString(R.string.chat_tool_label_interrupted))
        } else {
            splice(strings.getString(R.string.chat_tool_label_interrupted_named, mark(0)), listOf(subject))
        }
    }
}

/**
 * A phrase argument as it is spliced in. DS §8.2.6: code arguments (commands, paths) are
 * LTR-isolated so an RTL paragraph never reorders their spaces, dashes, slashes and dots; the
 * isolates sit outside the mono span. Plain text (code == null: TalkBack, copy) has none.
 */
private fun argument(arg: String, codeArg: Boolean, code: SpanStyle?): AnnotatedString =
    if (codeArg && code != null) {
        buildAnnotatedString {
            if (arg.isEmpty()) return@buildAnnotatedString
            append(LRI)
            withStyle(code) { append(arg) }
            append(PDI)
        }
    } else {
        AnnotatedString(arg)
    }

/** `buildGroupedToolLabel` segments joined pairwise with the "%1$s · %2$s" separator. */
fun resolveGroupedToolLabel(
    labels: List<ToolLabel>,
    strings: Resources,
    code: SpanStyle?,
    quantity: Resources = quantityResources(strings),
): AnnotatedString {
    if (labels.isEmpty()) return AnnotatedString("")
    val separator = strings.getString(R.string.chat_tool_label_group, mark(0), mark(1))
    return labels
        .map { resolveToolLabel(it, strings, code, quantity) }
        .reduce { joined, next -> splice(separator, listOf(joined, next)) }
}

/** [resolveToolLabel] for the current configuration, code arguments in [code]. */
@Composable
fun toolLabelText(label: ToolLabel, code: SpanStyle): AnnotatedString {
    val strings = LocalResources.current
    val configuration = LocalConfiguration.current
    val quantity = rememberCopyQuantityResources()
    return remember(label, code, strings, configuration, quantity) { resolveToolLabel(label, strings, code, quantity) }
}

private fun plural(quantity: Resources, @PluralsRes id: Int, count: Int): String = quantity.getQuantityString(id, count, count)

// ---------------------------------------------------------------- resource mapping

@StringRes
internal fun ToolPhrase.stringRes(): Int = when (this) {
    ToolPhrase.ReadingFile -> R.string.chat_tool_label_reading_file
    ToolPhrase.ReadFile -> R.string.chat_tool_label_read_file
    ToolPhrase.EditingFile -> R.string.chat_tool_label_editing_file
    ToolPhrase.EditedFile -> R.string.chat_tool_label_edited_file
    ToolPhrase.RunningCommand -> R.string.chat_tool_label_running_command
    ToolPhrase.RanCommand -> R.string.chat_tool_label_ran_command
    ToolPhrase.Searching -> R.string.chat_tool_label_searching
    ToolPhrase.Searched -> R.string.chat_tool_label_searched
    ToolPhrase.FetchingWebContent -> R.string.chat_tool_label_fetching_web_content
    ToolPhrase.FetchedWebContent -> R.string.chat_tool_label_fetched_web_content
    ToolPhrase.SpawningAgent -> R.string.chat_tool_label_spawning_agent
    ToolPhrase.SpawnedAgent -> R.string.chat_tool_label_spawned_agent
    ToolPhrase.UsingSkill -> R.string.chat_tool_label_using_skill
    ToolPhrase.UsedSkill -> R.string.chat_tool_label_used_skill
    ToolPhrase.AskingAQuestion -> R.string.chat_tool_label_asking_a_question
    ToolPhrase.AskedAQuestion -> R.string.chat_tool_label_asked_a_question
    ToolPhrase.CallingMcpTool -> R.string.chat_tool_label_calling_mcp_tool
    ToolPhrase.CalledMcpTool -> R.string.chat_tool_label_called_mcp_tool
    ToolPhrase.RunningTool -> R.string.chat_tool_label_running_tool
    ToolPhrase.ReadingFileNamed -> R.string.chat_tool_label_reading_file_named
    ToolPhrase.ReadFileNamed -> R.string.chat_tool_label_read_file_named
    ToolPhrase.RunningCommandNamed -> R.string.chat_tool_label_running_command_named
    ToolPhrase.RanCommandNamed -> R.string.chat_tool_label_ran_command_named
    ToolPhrase.SearchingNamed -> R.string.chat_tool_label_searching_named
    ToolPhrase.SearchedNamed -> R.string.chat_tool_label_searched_named
    ToolPhrase.FetchingNamed -> R.string.chat_tool_label_fetching_named
    ToolPhrase.FetchedNamed -> R.string.chat_tool_label_fetched_named
    ToolPhrase.ApplyingPatch -> R.string.chat_tool_label_applying_patch
    ToolPhrase.AppliedPatch -> R.string.chat_tool_label_applied_patch
    ToolPhrase.CreatingFileNamed -> R.string.chat_tool_label_creating_file_named
    ToolPhrase.CreatedFileNamed -> R.string.chat_tool_label_created_file_named
    ToolPhrase.DeletingFileNamed -> R.string.chat_tool_label_deleting_file_named
    ToolPhrase.DeletedFileNamed -> R.string.chat_tool_label_deleted_file_named
    ToolPhrase.EditingFileNamed -> R.string.chat_tool_label_editing_file_named
    ToolPhrase.EditedFileNamed -> R.string.chat_tool_label_edited_file_named
    ToolPhrase.InsertingIntoNamed -> R.string.chat_tool_label_inserting_into_named
    ToolPhrase.InsertedIntoNamed -> R.string.chat_tool_label_inserted_into_named
    ToolPhrase.SpawningAgentTask -> R.string.chat_tool_label_spawning_agent_task
    ToolPhrase.SpawnedAgentTask -> R.string.chat_tool_label_spawned_agent_task
    ToolPhrase.UsingSkillNamed -> R.string.chat_tool_label_using_skill_named
    ToolPhrase.UsedSkillNamed -> R.string.chat_tool_label_used_skill_named
    ToolPhrase.AskingQuestionText -> R.string.chat_tool_label_asking_question_text
    ToolPhrase.AskedQuestionText -> R.string.chat_tool_label_asked_question_text
    ToolPhrase.WaitingForTeammates -> R.string.chat_tool_label_waiting_for_teammates
    ToolPhrase.WaitedForTeammates -> R.string.chat_tool_label_waited_for_teammates
    ToolPhrase.CheckingTeamStatus -> R.string.chat_tool_label_checking_team_status
    ToolPhrase.CheckedTeamStatus -> R.string.chat_tool_label_checked_team_status
    ToolPhrase.ListingTeammateRuns -> R.string.chat_tool_label_listing_teammate_runs
    ToolPhrase.CancellingTeammateRun -> R.string.chat_tool_label_cancelling_teammate_run
    ToolPhrase.CancelledTeammateRun -> R.string.chat_tool_label_cancelled_teammate_run
    ToolPhrase.BroadcastingToTeammates -> R.string.chat_tool_label_broadcasting_to_teammates
    ToolPhrase.ReadingTeamMailbox -> R.string.chat_tool_label_reading_team_mailbox
    ToolPhrase.UpdatingMissionLog -> R.string.chat_tool_label_updating_mission_log
    ToolPhrase.UpdatedMissionLog -> R.string.chat_tool_label_updated_mission_log
    ToolPhrase.CleaningUpTeam -> R.string.chat_tool_label_cleaning_up_team
    ToolPhrase.CleanedUpTeam -> R.string.chat_tool_label_cleaned_up_team
    ToolPhrase.CreatingTeamOutcome -> R.string.chat_tool_label_creating_team_outcome
    ToolPhrase.CreatedTeamOutcome -> R.string.chat_tool_label_created_team_outcome
    ToolPhrase.AttachingOutcomeFragment -> R.string.chat_tool_label_attaching_outcome_fragment
    ToolPhrase.AttachedOutcomeFragment -> R.string.chat_tool_label_attached_outcome_fragment
    ToolPhrase.ReviewingOutcomeFragment -> R.string.chat_tool_label_reviewing_outcome_fragment
    ToolPhrase.ReviewedOutcomeFragment -> R.string.chat_tool_label_reviewed_outcome_fragment
    ToolPhrase.FinalizingTeamOutcome -> R.string.chat_tool_label_finalizing_team_outcome
    ToolPhrase.FinalizedTeamOutcome -> R.string.chat_tool_label_finalized_team_outcome
    ToolPhrase.ListingTeamOutcomes -> R.string.chat_tool_label_listing_team_outcomes
    ToolPhrase.FailedAttachOutcomeFragment -> R.string.chat_tool_label_failed_attach_outcome_fragment
    ToolPhrase.FailedAwaitRuns -> R.string.chat_tool_label_failed_await_runs
    ToolPhrase.FailedBroadcast -> R.string.chat_tool_label_failed_broadcast
    ToolPhrase.FailedCancelRun -> R.string.chat_tool_label_failed_cancel_run
    ToolPhrase.FailedCleanup -> R.string.chat_tool_label_failed_cleanup
    ToolPhrase.FailedCreateOutcome -> R.string.chat_tool_label_failed_create_outcome
    ToolPhrase.FailedFinalizeOutcome -> R.string.chat_tool_label_failed_finalize_outcome
    ToolPhrase.FailedListOutcomes -> R.string.chat_tool_label_failed_list_outcomes
    ToolPhrase.FailedListRuns -> R.string.chat_tool_label_failed_list_runs
    ToolPhrase.FailedMissionLog -> R.string.chat_tool_label_failed_mission_log
    ToolPhrase.FailedReadMailbox -> R.string.chat_tool_label_failed_read_mailbox
    ToolPhrase.FailedReviewOutcomeFragment -> R.string.chat_tool_label_failed_review_outcome_fragment
    ToolPhrase.FailedRunTask -> R.string.chat_tool_label_failed_run_task
    ToolPhrase.FailedSendMessage -> R.string.chat_tool_label_failed_send_message
    ToolPhrase.FailedShutdownTeammate -> R.string.chat_tool_label_failed_shutdown_teammate
    ToolPhrase.FailedSpawnTeammate -> R.string.chat_tool_label_failed_spawn_teammate
    ToolPhrase.FailedTeamStatus -> R.string.chat_tool_label_failed_team_status
    ToolPhrase.FailedTeamTask -> R.string.chat_tool_label_failed_team_task
    ToolPhrase.FailedTool -> R.string.chat_tool_label_failed_tool
    ToolPhrase.CompletingScheduledTask -> R.string.chat_tool_submit_running
    ToolPhrase.ScheduledTaskFailed -> R.string.chat_tool_submit_failed
    ToolPhrase.ScheduledTaskCompleted -> R.string.chat_tool_submit_done
    ToolPhrase.SwitchToActMode -> R.string.act_switch_tool_label
}

@PluralsRes
internal fun ToolCountPhrase.pluralRes(): Int = when (this) {
    ToolCountPhrase.ListedTeammateRuns -> R.plurals.chat_tool_count_listed_teammate_runs
    ToolCountPhrase.BroadcastToTeammates -> R.plurals.chat_tool_count_broadcast_to_teammates
    ToolCountPhrase.ReadTeamMessages -> R.plurals.chat_tool_count_read_team_messages
    ToolCountPhrase.ListedTeamOutcomes -> R.plurals.chat_tool_count_listed_team_outcomes
}

@PluralsRes
internal fun AggregateNoun.pluralRes(inProgress: Boolean): Int = when (this) {
    AggregateNoun.ReadFiles -> if (inProgress) R.plurals.chat_tool_agg_read_files_running else R.plurals.chat_tool_agg_read_files_done
    AggregateNoun.EditedFiles -> if (inProgress) R.plurals.chat_tool_agg_edited_files_running else R.plurals.chat_tool_agg_edited_files_done
    AggregateNoun.Commands -> if (inProgress) R.plurals.chat_tool_agg_commands_running else R.plurals.chat_tool_agg_commands_done
    AggregateNoun.Searches -> if (inProgress) R.plurals.chat_tool_agg_searches_running else R.plurals.chat_tool_agg_searches_done
    AggregateNoun.Links -> if (inProgress) R.plurals.chat_tool_agg_links_running else R.plurals.chat_tool_agg_links_done
    AggregateNoun.SpawnedAgents -> if (inProgress) R.plurals.chat_tool_agg_spawned_agents_running else R.plurals.chat_tool_agg_spawned_agents_done
    AggregateNoun.TeamSpawn -> if (inProgress) R.plurals.chat_tool_agg_team_spawn_running else R.plurals.chat_tool_agg_team_spawn_done
    AggregateNoun.TeamRunTask -> if (inProgress) R.plurals.chat_tool_agg_team_run_task_running else R.plurals.chat_tool_agg_team_run_task_done
    AggregateNoun.TeamShutdown -> if (inProgress) R.plurals.chat_tool_agg_team_shutdown_running else R.plurals.chat_tool_agg_team_shutdown_done
    AggregateNoun.TeamTaskCreate -> if (inProgress) R.plurals.chat_tool_agg_team_task_create_running else R.plurals.chat_tool_agg_team_task_create_done
    AggregateNoun.TeamTaskList -> if (inProgress) R.plurals.chat_tool_agg_team_task_list_running else R.plurals.chat_tool_agg_team_task_list_done
    AggregateNoun.TeamTaskClaim -> if (inProgress) R.plurals.chat_tool_agg_team_task_claim_running else R.plurals.chat_tool_agg_team_task_claim_done
    AggregateNoun.TeamTaskComplete -> if (inProgress) R.plurals.chat_tool_agg_team_task_complete_running else R.plurals.chat_tool_agg_team_task_complete_done
    AggregateNoun.TeamTaskBlock -> if (inProgress) R.plurals.chat_tool_agg_team_task_block_running else R.plurals.chat_tool_agg_team_task_block_done
    AggregateNoun.TeamTaskUpdate -> if (inProgress) R.plurals.chat_tool_agg_team_task_update_running else R.plurals.chat_tool_agg_team_task_update_done
    AggregateNoun.TeamSendMessage -> if (inProgress) R.plurals.chat_tool_agg_team_send_message_running else R.plurals.chat_tool_agg_team_send_message_done
}
