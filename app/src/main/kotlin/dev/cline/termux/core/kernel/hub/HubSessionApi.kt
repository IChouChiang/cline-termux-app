package dev.cline.termux.core.kernel.hub

import android.util.Log
import dev.cline.termux.BuildConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Team membership passed through `session.create.team` (upstream `HubTeamMembership`). */
data class TeamMembership(val teamId: String, val role: String? = null)

/**
 * What a new or resumed session needs; becomes `sessionConfig` + `metadata` on
 * `session.create` (home-chat.md §1.3.6, changes-tools-checkpoints.md §1.5.2).
 */
data class NewSessionSpec(
    val workspaceRoot: String,
    val cwd: String = workspaceRoot,
    val providerId: String,
    val modelId: String,
    val mode: String = "act",
    val autoApprove: Boolean = false,
    val thinking: Boolean? = null,
    val reasoningEffort: String? = null,
    val title: String? = null,
    /** Resume: the persisted session id plus its `session.messages` result. */
    val resumeSessionId: String? = null,
    val initialMessages: JsonArray? = null,
    /**
     * A run-boundary restart (decided A1): the runtime's `session.compaction.get` state, sent as
     * `initialCompactionState` like the terminal's `restartWithCurrentMessages`; opaque to the app.
     */
    val initialCompactionState: JsonObject? = null,
    /**
     * Whether `session.create` carries `runtimeOptions`, that is the client contributions
     * together (askQuestion, mistakeLimit, and switch_to_act_mode in a plan session:
     * [HubPayloads.runtimeOptions], HC §1.3.6), not only askQuestion as the historical name says.
     * The app always sends them; false only for tests of the bare shape.
     */
    val contributeAskQuestion: Boolean = true,
    val enableSpawnAgent: Boolean = true,
    val enableAgentTeams: Boolean = true,
    /** `metadata.source`; a resume keeps the persisted value so a TUI session stays `cli` (HC §1.3.6, Q13). */
    val source: String = HubPayloads.SOURCE,
    val team: TeamMembership? = null,
    /** `sessionConfig.compaction` (G1): the global compaction mode; null sends core's default `{enabled:true}`. */
    val compaction: JsonObject? = null,
)

/**
 * The CLI's `switch_to_act_mode` tool (`apps/cli/src/runtime/interactive/mode.ts`): its name
 * and its description verbatim. Model-facing protocol text, not UI copy.
 */
object SwitchToActModeTool {
    const val NAME = "switch_to_act_mode"
    const val DESCRIPTION =
        "Switch from plan mode to act mode. Switching to act mode immediately starts executing the plan, so only call this " +
            "after the user has explicitly approved the plan in a message sent AFTER you presented it (e.g. 'looks good', " +
            "'go ahead', 'switch to act mode'). Never call this in the same turn you present a plan, never call it " +
            "proactively, and never treat the original task request as approval."
}

/**
 * Payload builders for every Hub command the app sends. Pure functions so their shapes
 * are unit-tested against the recorded traffic and the desktop client's builders;
 * [HubSessionApi] sends them with the timeouts of docs/07 §3.1.
 */
object HubPayloads {
    const val SOURCE = "android"

    /** `sessionConfig.mode` of a plan session: the only mode whose runtime gets `switch_to_act_mode`. */
    const val PLAN_MODE = "plan"

    /** Metadata keys never carried over to a fork/restore child (`restoreMetadata`, CT §1.5.2). */
    val NON_INHERITED_METADATA_KEYS = setOf(
        "checkpoint", "fork", "restoredFromSessionId", "restoredCheckpointRunCount", "pid", "messagesPath", "usage", "totalCost",
    )

    // ---- session.create ------------------------------------------------------------------

    /** `sessionConfig` (CT §1.5.2): checkpoints always on (D4); `apiKey` is never sent, the daemon merges the stored credential. */
    fun sessionConfig(spec: NewSessionSpec): JsonObject = buildJsonObject {
        spec.resumeSessionId?.let { put("sessionId", it) }
        put("providerId", spec.providerId)
        put("modelId", spec.modelId)
        put("cwd", spec.cwd)
        put("workspaceRoot", spec.workspaceRoot)
        put("mode", spec.mode)
        put("enableTools", true)
        put("enableSpawnAgent", spec.enableSpawnAgent)
        put("enableAgentTeams", spec.enableAgentTeams)
        putJsonObject("checkpoint") { put("enabled", true) }
        put("compaction", compaction(spec))
        spec.thinking?.let { put("thinking", it) }
        spec.reasoningEffort?.let { put("reasoningEffort", it) }
    }

    /**
     * `sessionConfig.compaction` (G1, A11 row 2): the spec's value from the global compaction mode,
     * else `{enabled:true}` like the desktop (`chat-session.ts`) and the terminal (`main.ts`). Core
     * auto-compaction is opt-in, so no create path may leave it out.
     */
    fun compaction(spec: NewSessionSpec): JsonObject = spec.compaction ?: DEFAULT_COMPACTION

    /** `{enabled:true}`: compaction on with core's default strategy (agentic). */
    val DEFAULT_COMPACTION: JsonObject = buildJsonObject { put("enabled", true) }

    /** `metadata` of a brand-new or resumed session (HC §1.3.6 first two rows). */
    fun createMetadata(spec: NewSessionSpec): JsonObject = buildJsonObject {
        put("source", spec.source)
        put("interactive", true)
        put("provider", spec.providerId)
        put("model", spec.modelId)
        spec.title?.let { put("title", it) }
    }

    /**
     * The terminal's read-only tools that still run without asking in "Ask each time"
     * (`apps/cli/src/runtime/tool-policies.ts` `SAFE_AUTO_APPROVE_TOOL_NAMES`).
     */
    val SAFE_AUTO_APPROVE_TOOLS: List<String> = listOf(
        "ask_followup_question",
        "ask_question",
        "fetch_web_content",
        "read_files",
        "search_codebase",
        "skills",
        "submit_and_exit",
    )

    /**
     * `toolPolicies` as the terminal builds them (`applyInteractiveAutoApproveOverride`):
     * Auto-approve all is `{"*":{autoApprove:true}}`; Ask each time is `{"*":{autoApprove:false}}`
     * plus `{autoApprove:true}` for each of [SAFE_AUTO_APPROVE_TOOLS].
     */
    fun toolPolicies(autoApprove: Boolean): JsonObject = buildJsonObject {
        putJsonObject("*") { put("autoApprove", autoApprove) }
        if (!autoApprove) SAFE_AUTO_APPROVE_TOOLS.forEach { name -> putJsonObject(name) { put("autoApprove", true) } }
    }

    /**
     * The client contributions every create path registers for a session whose runtime runs in
     * [mode] (`sessionConfig.mode`; HC §1.3.6, batch-1 brief §8.1): the askQuestion executor,
     * the `mistakeLimit` decision (G2) and, in a plan session only, the `switch_to_act_mode` tool
     * (A11 row 4; the CLI's `applyInteractiveModeConfig` gives it to plan mode alone, so the
     * rebuild into act drops it and a rebuild back into plan adds it). `ClientContributions`
     * answers them for this client. Never `configExtensions` and never a `hook` contribution:
     * the Hub already runs the user's hook files for this session (brief F1), and a hook proxy
     * would make every tool call wait for the phone (TODO A3).
     */
    fun runtimeOptions(mode: String): JsonObject = buildJsonObject {
        put("clientContributions", clientContributionArray(mode))
    }

    fun clientContributionArray(mode: String): JsonArray = buildJsonArray {
        askQuestionContributionArray().forEach(::add)
        add(mistakeLimitContribution())
        if (mode == PLAN_MODE) add(switchToActModeContribution())
    }

    fun askQuestionContributionArray(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("kind", "toolExecutor")
            put("executor", "askQuestion")
            put("capabilityName", HubCapabilities.ASK_QUESTION)
        })
    }

    /** `{kind:"mistakeLimit"}`: the Hub asks this client before a run stops at the consecutive-mistake limit. */
    fun mistakeLimitContribution(): JsonObject = buildJsonObject {
        put("kind", "mistakeLimit")
        put("capabilityName", HubCapabilities.MISTAKE_LIMIT)
    }

    /**
     * The CLI's `switch_to_act_mode` tool (`apps/cli/src/runtime/interactive/mode.ts`) as a
     * client tool: same name, description and empty input schema, and `completesRun` so the
     * run ends right after a successful switch; the app then rebuilds the session in act mode
     * (the act-mode tools only exist in a runtime created with that mode) and continues the plan.
     * Contributed to plan sessions only ([runtimeOptions]).
     */
    fun switchToActModeContribution(): JsonObject = buildJsonObject {
        put("kind", "tool")
        put("capabilityName", HubCapabilities.SWITCH_TO_ACT_MODE)
        put("name", SwitchToActModeTool.NAME)
        put("description", SwitchToActModeTool.DESCRIPTION)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {}
        }
        putJsonObject("lifecycle") { put("completesRun", true) }
    }

    /** `session.create` (HC §1.3.6 `buildSessionCreate`): every create, resume, fork and resend goes through here. */
    fun sessionCreate(
        spec: NewSessionSpec,
        metadata: JsonObject = createMetadata(spec),
        toolPolicies: JsonObject = toolPolicies(spec.autoApprove),
    ): JsonObject = buildJsonObject {
        put("workspaceRoot", spec.workspaceRoot)
        put("cwd", spec.cwd)
        put("sessionConfig", sessionConfig(spec))
        put("metadata", metadata)
        put("toolPolicies", toolPolicies)
        spec.initialMessages?.let { put("initialMessages", it) }
        spec.initialCompactionState?.let { put("initialCompactionState", it) }
        if (spec.contributeAskQuestion) put("runtimeOptions", runtimeOptions(spec.mode))
        spec.team?.let { team ->
            putJsonObject("team") {
                put("teamId", team.teamId)
                team.role?.let { put("role", it) }
            }
        }
    }

    // ---- fork / restore (CT §1.5, HC §3.4.2) --------------------------------------------------

    /**
     * Metadata for a forked or restored session (the sidecar's `forkMetadata` /
     * `createRestoredCheckpointMetadata`, CT §1.5.2): the parent's metadata minus
     * [NON_INHERITED_METADATA_KEYS], re-stamped for this client, plus `fork`. [runCount] is the
     * edit's run (`fork.beforeRunCount`, as the desktop's edit-fork writes it); null for the
     * whole-session fork, which carries no `beforeRunCount` (CLI and desktop). The split
     * restore path ([checkpointRunCount] non-null) adds `restoredFromSessionId`,
     * `restoredCheckpointRunCount` and the checkpoint history up to that run.
     */
    fun restoreMetadata(
        sourceMetadata: JsonObject?,
        sourceSessionId: String,
        providerId: String,
        modelId: String,
        runCount: Int?,
        nowIso: String,
        checkpointRunCount: Int? = null,
        source: String = SOURCE,
        forkSource: String? = null,
    ): JsonObject {
        val inherited = LinkedHashMap<String, JsonElement>()
        sourceMetadata?.forEach { (k, v) -> if (k !in NON_INHERITED_METADATA_KEYS) inherited[k] = v }
        inherited["source"] = JsonPrimitive(source)
        inherited["interactive"] = JsonPrimitive(true)
        inherited["provider"] = JsonPrimitive(providerId)
        inherited["model"] = JsonPrimitive(modelId)
        val checkpoints = sourceMetadata?.get("checkpoint") as? JsonObject
        val fork = buildJsonObject {
            put("forkedFromSessionId", sourceSessionId)
            put("forkedAt", nowIso)
            put("source", forkSource ?: sourceMetadata?.get("source")?.jsonPrimitive?.contentOrNull ?: SOURCE)
            runCount?.let { put("beforeRunCount", it) }
            checkpoints?.let { put("checkpoints", it) }
        }
        inherited["fork"] = fork
        if (checkpointRunCount != null) {
            inherited["restoredFromSessionId"] = JsonPrimitive(sourceSessionId)
            inherited["restoredCheckpointRunCount"] = JsonPrimitive(checkpointRunCount)
            filteredCheckpoints(checkpoints, checkpointRunCount)?.let { inherited["checkpoint"] = it }
        }
        return JsonObject(inherited)
    }

    /** C1 revert: the desktop passes no session metadata, so the reverted session is untitled. */
    fun revertMetadata(providerId: String, modelId: String, source: String = SOURCE): JsonObject = buildJsonObject {
        put("source", source)
        put("interactive", true)
        put("provider", providerId)
        put("model", modelId)
    }

    /** `checkpoint = {latest, history}` with only the entries up to [maxRunCount] (`createRestoredCheckpointMetadata`). */
    fun filteredCheckpoints(checkpoint: JsonObject?, maxRunCount: Int): JsonObject? {
        if (checkpoint == null) return null
        val history = (checkpoint["history"] as? JsonArray)?.mapNotNull { normalizeCheckpoint(it) }?.filter { it.runCount <= maxRunCount }.orEmpty()
        val latest = normalizeCheckpoint(checkpoint["latest"])?.takeIf { it.runCount <= maxRunCount } ?: history.maxByOrNull { it.runCount }
        if (latest == null && history.isEmpty()) return null
        return buildJsonObject {
            latest?.let { put("latest", it.json) }
            put("history", JsonArray(history.map { it.json }))
        }
    }

    private class CheckpointEntry(val runCount: Int, val json: JsonObject)

    private fun normalizeCheckpoint(element: JsonElement?): CheckpointEntry? {
        val obj = element as? JsonObject ?: return null
        val ref = obj["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        // A number, as core's createRestoredCheckpointMetadata writes it (`Number(entry.createdAt)`);
        // a numeric string from an older app build is accepted and normalised.
        val createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            ?: return null
        val runCount = obj["runCount"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "stash" || it == "commit" }
        return CheckpointEntry(runCount, buildJsonObject {
            put("ref", ref)
            put("createdAt", createdAt)
            put("runCount", runCount)
            kind?.let { put("kind", it) }
        })
    }

    /**
     * `session.restore`, atomic path (CT §1.5.2): a new session with the transcript split at
     * [checkpointRunCount] and the files restored. The Hub mints the new id, so [spec] carries
     * no `resumeSessionId`.
     */
    fun sessionRestore(
        sourceSessionId: String,
        checkpointRunCount: Int,
        restoreWorkspace: Boolean,
        omitCheckpointMessage: Boolean,
        spec: NewSessionSpec,
        metadata: JsonObject,
    ): JsonObject = buildJsonObject {
        put("sessionId", sourceSessionId)
        put("checkpointRunCount", checkpointRunCount)
        putJsonObject("restore") {
            put("messages", true)
            put("workspace", restoreWorkspace)
            if (omitCheckpointMessage) put("omitCheckpointMessageFromSession", true)
        }
        put("workspaceRoot", spec.workspaceRoot)
        put("cwd", spec.cwd)
        put("sessionConfig", sessionConfig(spec.copy(resumeSessionId = null)))
        put("metadata", metadata)
        put("toolPolicies", toolPolicies(spec.autoApprove))
        put("runtimeOptions", runtimeOptions(spec.mode))
    }

    /** Split path, step 1: files only; no new session, no transcript split. */
    fun sessionRestoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject = buildJsonObject {
        put("sessionId", sourceSessionId)
        put("checkpointRunCount", checkpointRunCount)
        putJsonObject("restore") {
            put("messages", false)
            put("workspace", true)
        }
    }

    /** Split path step 2, edit without a checkpoint, and fork: a plain create seeded with [messages] and [metadata]. */
    fun sessionCreateRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject =
        sessionCreate(spec.copy(initialMessages = messages, resumeSessionId = null), metadata = metadata)

    // ---- turns --------------------------------------------------------------------------

    /**
     * `session.send_input` (CA C1.9): `attachments.userImages` are data URLs,
     * `attachments.userFiles` are Termux paths the attachment pipeline wrote.
     */
    fun sendInput(
        prompt: String,
        mode: String? = null,
        delivery: String? = null,
        imageDataUrls: List<String> = emptyList(),
        userFiles: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("prompt", prompt)
        mode?.let { put("mode", it) }
        delivery?.let { put("delivery", it) }
        if (imageDataUrls.isNotEmpty() || userFiles.isNotEmpty()) {
            putJsonObject("attachments") {
                if (imageDataUrls.isNotEmpty()) put("userImages", JsonArray(imageDataUrls.map(::JsonPrimitive)))
                if (userFiles.isNotEmpty()) put("userFiles", JsonArray(userFiles.map(::JsonPrimitive)))
            }
        }
    }

    fun runAbort(sessionId: String, reason: String = "User pressed Stop"): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("reason", reason)
    }

    fun proceedWhileRunning(sessionId: String, toolCallId: String? = null): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        toolCallId?.let { put("toolCallId", it) }
    }

    fun approvalRespond(approvalId: String, approved: Boolean, reason: String? = null): JsonObject = buildJsonObject {
        put("approvalId", approvalId)
        put("approved", approved)
        reason?.let { put("reason", it) }
    }

    fun capabilityRespond(requestId: String, ok: Boolean, result: JsonElement?, error: String? = null): JsonObject = buildJsonObject {
        put("requestId", requestId)
        put("ok", ok)
        if (result != null) putJsonObject("payload") { put("result", result) }
        error?.let { put("error", it) }
    }

    // ---- pending prompts (CA C2.6) -----------------------------------------------------------

    fun pendingPrompts(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun steerFirstPendingPrompt(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun updatePendingPrompt(sessionId: String, promptId: String, prompt: String? = null, delivery: String? = null): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("promptId", promptId)
        prompt?.let { put("prompt", it) }
        delivery?.let { put("delivery", it) }
    }

    fun removePendingPrompt(sessionId: String, promptId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("promptId", promptId)
    }

    // ---- session bookkeeping -----------------------------------------------------------------

    fun sessionList(limit: Int = 200, rootOnly: Boolean = true, workspaceRoot: String? = null): JsonObject = buildJsonObject {
        put("limit", limit)
        put("rootOnly", rootOnly)
        workspaceRoot?.let { put("workspaceRoot", it) }
    }

    fun sessionGet(sessionId: String, includeSnapshot: Boolean = true): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("includeSnapshot", includeSnapshot)
    }

    fun sessionMessages(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun sessionSearch(query: String, limit: Int = 50, workspaceRoot: String? = null): JsonObject = buildJsonObject {
        put("query", query)
        put("limit", limit)
        workspaceRoot?.let { put("workspaceRoot", it) }
    }

    /**
     * `session.update` replaces metadata wholesale (docs/08): the current metadata is spread
     * and the changed keys overlaid. A rename alone sends `title` only.
     */
    fun sessionUpdate(
        sessionId: String,
        currentMetadata: JsonObject?,
        title: String? = null,
        pinned: Boolean? = null,
        metadataOverlay: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        title?.let { put("title", it) }
        if (pinned != null || metadataOverlay.isNotEmpty()) {
            val merged = LinkedHashMap<String, JsonElement>(currentMetadata ?: emptyMap())
            merged.putAll(metadataOverlay)
            pinned?.let { merged["pinned"] = JsonPrimitive(it) }
            title?.let { merged["title"] = JsonPrimitive(it) }
            put("metadata", JsonObject(merged))
        }
    }

    fun sessionDelete(sessionId: String, deleteCheckpointRefs: Boolean = false): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("deleteCheckpointRefs", deleteCheckpointRefs)
    }

    fun sessionAttach(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun sessionDetach(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun compactionGet(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun updateConnection(
        sessionId: String,
        providerId: String? = null,
        modelId: String? = null,
        thinking: Boolean? = null,
        reasoningEffort: String? = null,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putJsonObject("updates") {
            providerId?.let { put("providerId", it) }
            modelId?.let { put("modelId", it) }
            thinking?.let { put("thinking", it) }
            reasoningEffort?.let { put("reasoningEffort", it) }
        }
    }

    // ---- settings ----------------------------------------------------------------------

    fun settingsList(workspaceRoot: String, cwd: String = workspaceRoot, includePluginTools: Boolean = true): JsonObject = buildJsonObject {
        put("workspaceRoot", workspaceRoot)
        put("cwd", cwd)
        put("includePluginTools", includePluginTools)
    }

    fun settingsToggle(type: String, id: String? = null, path: String? = null, name: String? = null, enabled: Boolean? = null): JsonObject = buildJsonObject {
        put("type", type)
        id?.let { put("id", it) }
        path?.let { put("path", it) }
        name?.let { put("name", it) }
        enabled?.let { put("enabled", it) }
    }

    // ---- schedules (CR §4.8) ----------------------------------------------------------------

    fun scheduleList(limit: Int = 200): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        put("limit", limit)
    }

    fun scheduleActive(): JsonObject = buildJsonObject { put("allWorkspaces", true) }

    fun scheduleUpcoming(limit: Int = 30): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        put("limit", limit)
    }

    fun scheduleListExecutions(scheduleId: String, limit: Int = 50): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        put("scheduleId", scheduleId)
        put("limit", limit)
    }

    fun scheduleGet(scheduleId: String): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        put("scheduleId", scheduleId)
    }

    fun scheduleEnable(scheduleId: String): JsonObject = scheduleGet(scheduleId)

    fun scheduleDisable(scheduleId: String): JsonObject = scheduleGet(scheduleId)

    fun scheduleTrigger(scheduleId: String, wait: Boolean = false): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        put("scheduleId", scheduleId)
        put("wait", wait)
    }

    fun scheduleDelete(scheduleId: String): JsonObject = scheduleGet(scheduleId)

    fun hubStatus(): JsonObject = HubClient.EMPTY_PAYLOAD

    fun clientList(): JsonObject = HubClient.EMPTY_PAYLOAD

    // ---- reply readers ---------------------------------------------------------------------

    /** `session.list` reply → session rows (`sessions[]`). */
    fun sessionsOf(reply: JsonObject): List<JsonObject> =
        (reply["sessions"] as? JsonArray)?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()

    /** `session.messages` reply → `messages[]`. */
    fun messagesOf(reply: JsonObject): JsonArray = (reply["messages"] as? JsonArray) ?: JsonArray(emptyList())

    /** `session.get` / `session.create` reply → the session record's `metadata` (falls back to the snapshot's). */
    fun metadataOf(reply: JsonObject): JsonObject? =
        (reply["session"] as? JsonObject)?.get("metadata") as? JsonObject
            ?: (reply["snapshot"] as? JsonObject)?.get("metadata") as? JsonObject
            ?: reply["metadata"] as? JsonObject

    fun sessionIdOf(reply: JsonObject): String? =
        reply["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: (reply["session"] as? JsonObject)?.get("sessionId")?.jsonPrimitive?.contentOrNull
            ?: (reply["snapshot"] as? JsonObject)?.jsonObject?.get("sessionId")?.jsonPrimitive?.contentOrNull
}

/**
 * Typed facade over [HubClient] for session work; replies stay JSON, timeouts come from the §3.1 table.
 * [configLog] receives one line per `sessionConfig` sent (debug builds only, tag [CONFIG_LOG_TAG]):
 * the command and its `compaction`, plus the session id when there is one; never prompts or tokens.
 */
class HubSessionApi(
    private val client: HubClient,
    private val configLog: ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> debugConfigLog(line) } else null,
) {
    suspend fun create(spec: NewSessionSpec, metadata: JsonObject = HubPayloads.createMetadata(spec)): JsonObject {
        logConfig(if (spec.resumeSessionId != null) "resume" else "create", spec, spec.resumeSessionId)
        return client.withDrainRetry { client.command(HubCommands.SESSION_CREATE, HubPayloads.sessionCreate(spec, metadata)) }
    }

    /** Fork, edit-and-restart without a checkpoint, and the split restore path's second step. */
    suspend fun createRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject {
        logConfig("createRestored", spec, null)
        return client.withDrainRetry { client.command(HubCommands.SESSION_CREATE, HubPayloads.sessionCreateRestored(spec, messages, metadata)) }
    }

    suspend fun restore(
        sourceSessionId: String,
        checkpointRunCount: Int,
        restoreWorkspace: Boolean,
        omitCheckpointMessage: Boolean,
        spec: NewSessionSpec,
        metadata: JsonObject,
    ): JsonObject {
        logConfig("restore", spec, sourceSessionId)
        return client.withDrainRetry {
            client.command(
                HubCommands.SESSION_RESTORE,
                HubPayloads.sessionRestore(sourceSessionId, checkpointRunCount, restoreWorkspace, omitCheckpointMessage, spec, metadata),
                sessionId = sourceSessionId,
            )
        }
    }

    suspend fun restoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject = client.withDrainRetry {
        client.command(HubCommands.SESSION_RESTORE, HubPayloads.sessionRestoreWorkspaceOnly(sourceSessionId, checkpointRunCount), sessionId = sourceSessionId)
    }

    /** Fire-and-forget: the reply blocks for the whole turn; `run.*` events are the truth. */
    fun sendInput(
        sessionId: String,
        prompt: String,
        mode: String? = null,
        delivery: String? = null,
        imageDataUrls: List<String> = emptyList(),
        userFiles: List<String> = emptyList(),
    ): HubPendingCommand =
        client.send(HubCommands.SESSION_SEND_INPUT, HubPayloads.sendInput(prompt, mode, delivery, imageDataUrls, userFiles), sessionId)

    suspend fun respondToApproval(approvalId: String, approved: Boolean, reason: String? = null): JsonObject =
        client.command(HubCommands.APPROVAL_RESPOND, HubPayloads.approvalRespond(approvalId, approved, reason))

    suspend fun respondToCapability(requestId: String, ok: Boolean, result: JsonElement?, error: String? = null): JsonObject =
        client.command(HubCommands.CAPABILITY_RESPOND, HubPayloads.capabilityRespond(requestId, ok, result, error))

    suspend fun abort(sessionId: String, reason: String = "User pressed Stop"): JsonObject =
        client.command(HubCommands.RUN_ABORT, HubPayloads.runAbort(sessionId, reason), sessionId)

    suspend fun proceedWhileRunning(sessionId: String, toolCallId: String? = null): JsonObject =
        client.command(HubCommands.RUN_PROCEED_WHILE_RUNNING, HubPayloads.proceedWhileRunning(sessionId, toolCallId), sessionId)

    suspend fun list(limit: Int = 200, rootOnly: Boolean = true, workspaceRoot: String? = null): JsonObject =
        client.command(HubCommands.SESSION_LIST, HubPayloads.sessionList(limit, rootOnly, workspaceRoot))

    suspend fun get(sessionId: String, includeSnapshot: Boolean = true): JsonObject =
        client.command(HubCommands.SESSION_GET, HubPayloads.sessionGet(sessionId, includeSnapshot), sessionId)

    suspend fun messages(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_MESSAGES, HubPayloads.sessionMessages(sessionId), sessionId)

    suspend fun search(query: String, limit: Int = 50, workspaceRoot: String? = null): JsonObject =
        client.command(HubCommands.SESSION_SEARCH, HubPayloads.sessionSearch(query, limit, workspaceRoot))

    suspend fun update(
        sessionId: String,
        currentMetadata: JsonObject?,
        title: String? = null,
        pinned: Boolean? = null,
        metadataOverlay: Map<String, JsonElement> = emptyMap(),
    ): JsonObject =
        client.command(HubCommands.SESSION_UPDATE, HubPayloads.sessionUpdate(sessionId, currentMetadata, title, pinned, metadataOverlay), sessionId)

    suspend fun delete(sessionId: String, deleteCheckpointRefs: Boolean = false): JsonObject =
        client.command(HubCommands.SESSION_DELETE, HubPayloads.sessionDelete(sessionId, deleteCheckpointRefs), sessionId)

    suspend fun attach(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_ATTACH, HubPayloads.sessionAttach(sessionId), sessionId)

    suspend fun detach(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_DETACH, HubPayloads.sessionDetach(sessionId), sessionId)

    suspend fun compaction(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_COMPACTION_GET, HubPayloads.compactionGet(sessionId), sessionId)

    suspend fun updateConnection(
        sessionId: String,
        providerId: String? = null,
        modelId: String? = null,
        thinking: Boolean? = null,
        reasoningEffort: String? = null,
    ): JsonObject =
        client.command(HubCommands.SESSION_UPDATE_CONNECTION, HubPayloads.updateConnection(sessionId, providerId, modelId, thinking, reasoningEffort), sessionId)

    suspend fun pendingPrompts(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_PENDING_PROMPTS, HubPayloads.pendingPrompts(sessionId), sessionId)

    suspend fun steerFirstPendingPrompt(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_STEER_FIRST_PENDING_PROMPT, HubPayloads.steerFirstPendingPrompt(sessionId), sessionId)

    suspend fun updatePendingPrompt(sessionId: String, promptId: String, prompt: String? = null, delivery: String? = null): JsonObject =
        client.command(HubCommands.SESSION_UPDATE_PENDING_PROMPT, HubPayloads.updatePendingPrompt(sessionId, promptId, prompt, delivery), sessionId)

    suspend fun removePendingPrompt(sessionId: String, promptId: String): JsonObject =
        client.command(HubCommands.SESSION_REMOVE_PENDING_PROMPT, HubPayloads.removePendingPrompt(sessionId, promptId), sessionId)

    suspend fun settingsList(workspaceRoot: String, cwd: String = workspaceRoot, includePluginTools: Boolean = true): JsonObject =
        client.command(HubCommands.SETTINGS_LIST, HubPayloads.settingsList(workspaceRoot, cwd, includePluginTools))

    suspend fun settingsToggle(type: String, id: String? = null, path: String? = null, name: String? = null, enabled: Boolean? = null): JsonObject =
        client.command(HubCommands.SETTINGS_TOGGLE, HubPayloads.settingsToggle(type, id, path, name, enabled))

    suspend fun scheduleList(limit: Int = 200): JsonObject = client.command(HubCommands.SCHEDULE_LIST, HubPayloads.scheduleList(limit))
    suspend fun scheduleActive(): JsonObject = client.command(HubCommands.SCHEDULE_ACTIVE, HubPayloads.scheduleActive())
    suspend fun scheduleUpcoming(limit: Int = 30): JsonObject = client.command(HubCommands.SCHEDULE_UPCOMING, HubPayloads.scheduleUpcoming(limit))
    suspend fun scheduleListExecutions(scheduleId: String, limit: Int = 50): JsonObject =
        client.command(HubCommands.SCHEDULE_LIST_EXECUTIONS, HubPayloads.scheduleListExecutions(scheduleId, limit))
    suspend fun scheduleGet(scheduleId: String): JsonObject = client.command(HubCommands.SCHEDULE_GET, HubPayloads.scheduleGet(scheduleId))
    suspend fun scheduleEnable(scheduleId: String): JsonObject = client.command(HubCommands.SCHEDULE_ENABLE, HubPayloads.scheduleEnable(scheduleId))
    suspend fun scheduleDisable(scheduleId: String): JsonObject = client.command(HubCommands.SCHEDULE_DISABLE, HubPayloads.scheduleDisable(scheduleId))
    suspend fun scheduleTrigger(scheduleId: String, wait: Boolean = false): JsonObject =
        client.command(HubCommands.SCHEDULE_TRIGGER, HubPayloads.scheduleTrigger(scheduleId, wait))
    suspend fun scheduleDelete(scheduleId: String): JsonObject = client.command(HubCommands.SCHEDULE_DELETE, HubPayloads.scheduleDelete(scheduleId))

    suspend fun hubStatus(): JsonObject = client.command(HubCommands.HUB_STATUS, HubPayloads.hubStatus())

    suspend fun clientList(): JsonObject = client.command(HubCommands.CLIENT_LIST, HubPayloads.clientList())

    private fun logConfig(command: String, spec: NewSessionSpec, sessionId: String?) {
        val log = configLog ?: return
        log(configLine(command, spec, sessionId))
    }

    companion object {
        const val CONFIG_LOG_TAG = "ClineSessionCfg"

        /** `"<command> compaction=<json>"`, then ` session=<id>` for a resume or restore. */
        fun configLine(command: String, spec: NewSessionSpec, sessionId: String?): String = buildString {
            append(command).append(" compaction=").append(HubPayloads.compaction(spec).toString())
            if (sessionId != null) append(" session=").append(sessionId)
        }

        private fun debugConfigLog(line: String) {
            // runCatching: android.util.Log is a stub in JVM unit tests.
            runCatching { Log.d(CONFIG_LOG_TAG, line) }
        }
    }
}
