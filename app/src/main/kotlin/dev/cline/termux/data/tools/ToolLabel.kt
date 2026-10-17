package dev.cline.termux.data.tools

/*
 * Typed tool-row labels (DS §8.2.1-§8.2.3). The desktop's `buildToolSummary` and
 * `teamSummary` build English strings; this port keeps their decisions (which phrase,
 * which verb tense, which count) and leaves the wording to string resources, mapped in
 * ui/chat/rows/ToolLabelText.kt. Kernel-supplied text (paths, commands, queries, tasks,
 * humanized MCP/unknown tool names) travels as data (§8.2.8).
 */

/** One row label: a phrase, a counted phrase, an aggregate count, or data shown as is. */
sealed interface ToolLabel {
    /** A [ToolPhrase] resource; [arg] fills its `%1$s` (in `ClineMono` when [ToolPhrase.codeArg]). */
    data class Phrase(val phrase: ToolPhrase, val arg: String? = null) : ToolLabel

    /** A counted team phrase ("Listed 3 teammate runs"), a `<plurals>` resource. */
    data class Count(val phrase: ToolCountPhrase, val count: Int) : ToolLabel

    /** An aggregate count ("Read 3 files", "Exploring 2 searches"), a `<plurals>` resource per tense. */
    data class Aggregate(val noun: AggregateNoun, val count: Int, val inProgress: Boolean) : ToolLabel

    /** Kernel data shown untranslated: a humanized tool name, a result's query. */
    data class Text(val text: String) : ToolLabel

    /**
     * A21 (trial row 9, app addition): a call that still has no result once its turn has
     * finished, "Interrupted" instead of [base], the in-progress label the desktop keeps
     * showing. The row names [base]'s specific (a command, path, query, URL, skill, task,
     * question or tool name) when it has one.
     */
    data class Interrupted(val base: ToolLabel) : ToolLabel
}

/**
 * Every fixed tool-label phrase of tool-summary/index.ts, team.ts and tool-summaries.ts.
 * [codeArg]: the `%1$s` argument is code (a path, command, query, URL or skill name).
 */
enum class ToolPhrase(val codeArg: Boolean = false) {
    // Input-less fallbacks by kind
    ReadingFile, ReadFile,
    EditingFile, EditedFile,
    RunningCommand, RanCommand,
    Searching, Searched,
    FetchingWebContent, FetchedWebContent,
    SpawningAgent, SpawnedAgent,
    UsingSkill, UsedSkill,
    AskingAQuestion, AskedAQuestion,
    CallingMcpTool, CalledMcpTool,
    /** "Running %1$s" with a humanized tool name (data, not code). */
    RunningTool,

    // Single-item labels with their specifics inline
    ReadingFileNamed(codeArg = true), ReadFileNamed(codeArg = true),
    RunningCommandNamed(codeArg = true), RanCommandNamed(codeArg = true),
    SearchingNamed(codeArg = true), SearchedNamed(codeArg = true),
    FetchingNamed(codeArg = true), FetchedNamed(codeArg = true),
    ApplyingPatch, AppliedPatch,
    CreatingFileNamed(codeArg = true), CreatedFileNamed(codeArg = true),
    DeletingFileNamed(codeArg = true), DeletedFileNamed(codeArg = true),
    EditingFileNamed(codeArg = true), EditedFileNamed(codeArg = true),
    InsertingIntoNamed(codeArg = true), InsertedIntoNamed(codeArg = true),
    SpawningAgentTask, SpawnedAgentTask,
    UsingSkillNamed(codeArg = true), UsedSkillNamed(codeArg = true),
    AskingQuestionText, AskedQuestionText,

    // team.ts
    WaitingForTeammates, WaitedForTeammates,
    CheckingTeamStatus, CheckedTeamStatus,
    ListingTeammateRuns,
    CancellingTeammateRun, CancelledTeammateRun,
    BroadcastingToTeammates,
    ReadingTeamMailbox,
    UpdatingMissionLog, UpdatedMissionLog,
    CleaningUpTeam, CleanedUpTeam,
    CreatingTeamOutcome, CreatedTeamOutcome,
    AttachingOutcomeFragment, AttachedOutcomeFragment,
    ReviewingOutcomeFragment, ReviewedOutcomeFragment,
    FinalizingTeamOutcome, FinalizedTeamOutcome,
    ListingTeamOutcomes,

    // team.ts failure labels
    FailedAttachOutcomeFragment,
    FailedAwaitRuns,
    FailedBroadcast,
    FailedCancelRun,
    FailedCleanup,
    FailedCreateOutcome,
    FailedFinalizeOutcome,
    FailedListOutcomes,
    FailedListRuns,
    FailedMissionLog,
    FailedReadMailbox,
    FailedReviewOutcomeFragment,
    FailedRunTask,
    FailedSendMessage,
    FailedShutdownTeammate,
    FailedSpawnTeammate,
    FailedTeamStatus,
    FailedTeamTask,
    /** "Failed %1$s" with the raw tool name (data). */
    FailedTool,

    // tool-message-block.tsx `submit_and_exit` milestone
    CompletingScheduledTask, ScheduledTaskFailed, ScheduledTaskCompleted,

    /** The app's `switch_to_act_mode` client tool (A11 row 4): an action, like its approval card. */
    SwitchToActMode,
}

/** The counted team phrases (team.ts `pluralize` inside a fixed sentence). */
enum class ToolCountPhrase { ListedTeammateRuns, BroadcastToTeammates, ReadTeamMessages, ListedTeamOutcomes }

/**
 * The noun-and-verb pair of an aggregate (`ToolAggregate.noun`, `completedVerb`,
 * `progressVerb`): one `<plurals>` resource per tense.
 */
enum class AggregateNoun {
    ReadFiles,
    EditedFiles,
    Commands,
    Searches,
    Links,
    SpawnedAgents,
    TeamSpawn,
    TeamRunTask,
    TeamShutdown,
    TeamTaskCreate,
    TeamTaskList,
    TeamTaskClaim,
    TeamTaskComplete,
    TeamTaskBlock,
    TeamTaskUpdate,
    TeamSendMessage,
}
