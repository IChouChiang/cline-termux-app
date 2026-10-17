package dev.cline.termux.data.checkpoint

import dev.cline.termux.BuildInfo
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.core.kernel.hub.NewSessionSpec
import dev.cline.termux.core.kernel.hub.isDraining
import dev.cline.termux.core.kernel.hub.isLostReply
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.PortRelease
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.epochMillis
import dev.cline.termux.data.session.obj
import dev.cline.termux.data.session.str
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** C1 revert or C2 edit-and-restart (also from the C3 picker). */
enum class RestoreKind { Revert, Edit }

/** How the restore reaches the kernel (CT §1.5.1). */
enum class RestorePath {
    /** One `session.restore` (kernel release ≥ `MIN_PORT_RELEASE_RESTORE`). */
    Atomic,

    /** Workspace-only `session.restore`, then `session.create` with the trimmed transcript (CT §1.5.3). */
    Split,

    /** Edit without a checkpoint: a client-side fork, always a plain `session.create` (CT C.6). */
    ClientFork,
}

/** What the confirm asked for (CT C.6). Provider/model/mode/workspace come from the source record. */
data class RestoreRequest(
    val kind: RestoreKind,
    val sourceSessionId: String,
    /** The message's run count (`CheckpointIndex` / `UserRunEntry.runCount`). */
    val runCount: Int,
    /** The radio choice; ignored (false) for [RestorePath.ClientFork]. */
    val restoreWorkspace: Boolean,
    /** Edit only: `checkpointAtOrBefore(runCount) != null`. False → client-side fork, chat only. */
    val hasCheckpoint: Boolean = true,
    /** The app's stored per-session auto-approve value. */
    val autoApprove: Boolean = false,
    /** Local thinking / effort selection; null keeps the source session's. */
    val thinking: Boolean? = null,
    val reasoningEffort: String? = null,
    /** `sessionConfig.compaction` from the global mode (G1); null = `{enabled:true}`. */
    val compaction: JsonObject? = null,
)

/**
 * The in-flight request kept in `SavedStateHandle` (CT §1.6, C.6 step 2) so the lost-reply
 * recovery also runs after process death. Store [encode]; read back with [decode].
 */
@Serializable
data class PendingRestore(
    val kind: RestoreKind,
    val sourceSessionId: String,
    val runCount: Int,
    val restoreWorkspace: Boolean,
    val path: RestorePath,
    /** ISO time stamped into `metadata.fork.forkedAt` (edit only). */
    val forkedAt: String? = null,
    val requestSentAt: Long,
    /** Split path: step 1 reset the workspace. */
    val workspaceRestored: Boolean = false,
) {
    fun encode(): String = HubJson.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String?): PendingRestore? =
            raw?.let { runCatching { HubJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Progress lines under the message row (CT C.5 row states). */
sealed interface RestoreProgress {
    /** The request is about to be sent; persist [pending] (C.6 step 2). */
    data class Started(val pending: PendingRestore) : RestoreProgress

    /** `hub_draining`: "Cline Hub is restarting. Waiting..." (CT §1.6), up to 30 s. */
    data object Draining : RestoreProgress

    /** Split path step 1 succeeded (the workspace is reset); keep [pending] updated. */
    data class WorkspaceRestored(val pending: PendingRestore) : RestoreProgress
}

/** Why a restore failed, for the error line under the row (CT §1.5.4, C.7). */
enum class RestoreFailure {
    /** `session_not_found`: disable that message's actions until the session list refreshes. */
    SourceGone,

    /** `session_messages_not_found`. */
    EmptyHistory,

    /** "No checkpoint found…" / "Could not find user message…": refresh the checkpoint index. */
    HistoryChanged,

    /** A compacted run: hide that message's restore/edit actions from then on (C.4). */
    FoldedRun,

    /** `invalid_restore` and the like: an app bug; logged as `restore_invalid`. */
    InvalidRequest,

    /** "cwd or workspaceRoot is required to restore a checkpoint"; logged as `restore_no_cwd`. */
    NoCwd,

    /** The source record has no provider or model to start the new session with. */
    MissingConfig,

    /** The Hub kept draining past the 30 s wait (the flow's fallback error). */
    Draining,

    /** No socket: nothing was sent. */
    NotConnected,

    /** Any other kernel error. */
    Kernel,
}

sealed interface RestoreOutcome {
    /**
     * The new session started (C.6 step 7). [messages] is the atomic reply's transcript (null on
     * the split and fork paths: seed from `session.messages`); [hasMessages] decides the
     * initial status (desktop: completed when there are messages, else idle).
     */
    data class Success(
        val newSessionId: String,
        val messages: JsonArray?,
        val pending: PendingRestore,
        /** Found by the lost-reply recovery rather than from a reply. */
        val recovered: Boolean = false,
    ) : RestoreOutcome {
        val hasMessages: Boolean get() = messages?.isNotEmpty() ?: true
    }

    /**
     * The kernel refused to touch the workspace (C.6 step 6): reopen the dialog with [message]
     * verbatim and "Restore chat only" selected; [workspaceOptionAvailable] is false after
     * "is not a git repository" (the option is hidden for the dialog's life).
     */
    data class WorkspaceRefused(val message: String, val workspaceOptionAvailable: Boolean) : RestoreOutcome

    /**
     * [message] is kernel text shown verbatim, or null for the flow's fallback copy (C.7).
     * [workspaceRestored]: the split path reset the workspace before the session failed to
     * start ("The workspace was restored, but the new session could not start: %1$s").
     */
    data class Failed(val reason: RestoreFailure, val message: String?, val workspaceRestored: Boolean = false) : RestoreOutcome

    /** Lost reply with no matching session: "Restore status unknown" (CT §1.6). Never resend. */
    data class StatusUnknown(val pending: PendingRestore) : RestoreOutcome

    /** Another versioning action is pending: "Checkpoint restore already in progress" (CLI). */
    data object AlreadyInProgress : RestoreOutcome
}

/** C3.5 empty-state diagnosis. */
enum class GitDiagnosis { NoGit, NotARepository, RepositoryOk, Unknown }

/** The Hub commands the checkpoint flows send. Production: [HubCheckpointHub]; tests: a fake. */
interface CheckpointHub {
    suspend fun get(sessionId: String): JsonObject
    suspend fun messages(sessionId: String): JsonObject

    /** One attempt, no drain retry (the flow reports the wait itself). */
    suspend fun restore(
        sourceSessionId: String,
        checkpointRunCount: Int,
        restoreWorkspace: Boolean,
        omitCheckpointMessage: Boolean,
        spec: NewSessionSpec,
        metadata: JsonObject,
    ): JsonObject

    suspend fun restoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject
    suspend fun createRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject
    suspend fun detach(sessionId: String): JsonObject
    suspend fun list(limit: Int): JsonObject
    suspend fun awaitNotDraining(timeoutMs: Long): Boolean

    /** True once the socket is connected (immediately when it already is), false after [timeoutMs]. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean
}

/**
 * [CheckpointHub] over the process's `HubClient`. `session.restore` goes out with
 * `envelope.timeoutMs = SESSION_RESTORE_TIMEOUT_MS` and the client timer
 * `SESSION_RESTORE_CLIENT_TIMEOUT_MS` (the client's defaults for that command).
 */
class HubCheckpointHub(private val client: HubClient, private val api: HubSessionApi) : CheckpointHub {
    override suspend fun get(sessionId: String): JsonObject = api.get(sessionId, includeSnapshot = true)
    override suspend fun messages(sessionId: String): JsonObject = api.messages(sessionId)

    override suspend fun restore(
        sourceSessionId: String,
        checkpointRunCount: Int,
        restoreWorkspace: Boolean,
        omitCheckpointMessage: Boolean,
        spec: NewSessionSpec,
        metadata: JsonObject,
    ): JsonObject = client.command(
        HubCommands.SESSION_RESTORE,
        HubPayloads.sessionRestore(sourceSessionId, checkpointRunCount, restoreWorkspace, omitCheckpointMessage, spec, metadata),
        sessionId = sourceSessionId,
        envelopeTimeoutMs = HubClient.SESSION_RESTORE_TIMEOUT_MS,
        clientTimeoutMs = HubClient.SESSION_RESTORE_CLIENT_TIMEOUT_MS,
    )

    override suspend fun restoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject = client.command(
        HubCommands.SESSION_RESTORE,
        HubPayloads.sessionRestoreWorkspaceOnly(sourceSessionId, checkpointRunCount),
        sessionId = sourceSessionId,
        envelopeTimeoutMs = HubClient.SESSION_RESTORE_TIMEOUT_MS,
        clientTimeoutMs = HubClient.SESSION_RESTORE_CLIENT_TIMEOUT_MS,
    )

    override suspend fun createRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject =
        client.command(HubCommands.SESSION_CREATE, HubPayloads.sessionCreateRestored(spec, messages, metadata))

    override suspend fun detach(sessionId: String): JsonObject = api.detach(sessionId)
    override suspend fun list(limit: Int): JsonObject = api.list(limit = limit, rootOnly = true)
    override suspend fun awaitNotDraining(timeoutMs: Long): Boolean = client.awaitNotDraining(timeoutMs)

    override suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { client.state.first { it is HubConnectionState.Connected } } != null
}

/**
 * Revert / edit-and-restart orchestration (CT C.6, §1.5, §1.6). A plain class the checkpoint
 * ViewModel owns (docs/07 §6.5); it holds no UI state. Navigation, the composer prefill,
 * clearing the old session's approvals in the UI and `SavedStateHandle` belong to the caller:
 * it persists [RestoreProgress.Started] / [RestoreProgress.WorkspaceRestored] and clears the
 * pending request on every terminal [RestoreOutcome].
 */
class CheckpointActions(
    private val hub: CheckpointHub,
    private val termux: TermuxExecutor,
    private val clock: Clock,
    /** `release=` of the installed port (`KernelCoordinator.portVersion`), or null when unknown. */
    private val kernelRelease: () -> String?,
    private val minRestoreRelease: String? = BuildInfo.MIN_PORT_RELEASE_RESTORE,
    private val config: Config = Config(),
) {
    data class Config(
        val drainWaitMs: Long = HubClient.DRAIN_WAIT_MS,
        /** How long a lost reply waits for the socket before the recovery gives up as "unknown". */
        val reconnectWaitMs: Long = 30_000L,
        /** `createdAt ≥ requestSentAt − 5 s` (CT §1.6). */
        val recoverySlackMs: Long = 5_000L,
        val recoveryListLimit: Int = 20,
        val gitProbeTimeoutMs: Long = 10_000L,
    )

    private val inFlight = AtomicBoolean(false)

    /** Another versioning action of this instance is pending (busy rule, C.3). */
    val pending: Boolean get() = inFlight.get()

    /** Which path a restore takes on the connected kernel (CT §1.5.1). */
    fun pathFor(kind: RestoreKind, hasCheckpoint: Boolean): RestorePath = when {
        kind == RestoreKind.Edit && !hasCheckpoint -> RestorePath.ClientFork
        minRestoreRelease != null && PortRelease.satisfies(kernelRelease(), minRestoreRelease) -> RestorePath.Atomic
        else -> RestorePath.Split
    }

    /** Runs one confirm end to end (C.6 steps 2-7). Never resends after a lost reply. */
    suspend fun restore(request: RestoreRequest, onProgress: (RestoreProgress) -> Unit = {}): RestoreOutcome {
        if (!inFlight.compareAndSet(false, true)) return RestoreOutcome.AlreadyInProgress
        try {
            return runRestore(request, onProgress)
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * Lost-reply recovery (CT §1.6): after (re)connect, `session.list {limit:20, rootOnly:true}`
     * and match the session this request would have created. Also run for a [PendingRestore]
     * read back from `SavedStateHandle` after process death.
     */
    suspend fun recover(pending: PendingRestore): RestoreOutcome {
        if (!hub.awaitConnected(config.reconnectWaitMs)) return RestoreOutcome.StatusUnknown(pending)
        val rows = try {
            HubPayloads.sessionsOf(hub.list(config.recoveryListLimit))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return RestoreOutcome.StatusUnknown(pending)
        }
        val match = rows.firstOrNull { matchesPending(it, pending, config.recoverySlackMs) }
        val newId = match?.str("sessionId") ?: return RestoreOutcome.StatusUnknown(pending)
        detachQuietly(pending.sourceSessionId)
        return RestoreOutcome.Success(newId, messages = null, pending = pending, recovered = true)
    }

    /** C3.5: `git -C <cwd> rev-parse --is-inside-work-tree`, 10 s, never prompting for the permission. */
    suspend fun diagnoseGit(cwd: String): GitDiagnosis {
        if (!termux.hasPermission()) return GitDiagnosis.Unknown
        val result = try {
            termux.run(ClineCommands.gitIsWorkTree(cwd), config.gitProbeTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TermuxUnavailable) {
            return GitDiagnosis.Unknown
        }
        return gitDiagnosis(result)
    }

    // ---- the flow ----------------------------------------------------------------------------

    private suspend fun runRestore(request: RestoreRequest, onProgress: (RestoreProgress) -> Unit): RestoreOutcome {
        val source = request.sourceSessionId
        val record = try {
            hub.get(source)
        } catch (e: HubCommandException) {
            // The read-only preamble lost its reply: nothing was sent that could restore.
            if (e.isLostReply()) return RestoreOutcome.Failed(RestoreFailure.NotConnected, null)
            return restoreOutcomeFor(e, workspaceRestored = false)
        } catch (_: HubConnectionClosedException) {
            return RestoreOutcome.Failed(RestoreFailure.NotConnected, null)
        }
        val session = record.obj("session")
        val snapshot = record.obj("snapshot")
        val facts = SessionFactsReducer.fromRecord(source, session, snapshot)
        val spec = specFor(facts, request) ?: return if (facts.workspaceRoot == null && facts.cwd == null) {
            RestoreOutcome.Failed(RestoreFailure.NoCwd, NO_CWD_MESSAGE)
        } else {
            RestoreOutcome.Failed(RestoreFailure.MissingConfig, null)
        }
        val path = pathFor(request.kind, request.hasCheckpoint)
        var pending = PendingRestore(
            kind = request.kind,
            sourceSessionId = source,
            runCount = request.runCount,
            restoreWorkspace = request.restoreWorkspace && path != RestorePath.ClientFork,
            path = path,
            forkedAt = if (request.kind == RestoreKind.Edit) Instant.ofEpochMilli(clock.millis()).toString() else null,
            requestSentAt = clock.millis(),
        )
        onProgress(RestoreProgress.Started(pending))
        val sourceMetadata = facts.metadata
        val checkpoint = sourceMetadata.obj("checkpoint") ?: snapshot?.obj("checkpoint")
        val reply = try {
            when (path) {
                RestorePath.Atomic -> drainRetry(onProgress) {
                    hub.restore(
                        sourceSessionId = source,
                        checkpointRunCount = request.runCount,
                        restoreWorkspace = pending.restoreWorkspace,
                        omitCheckpointMessage = request.kind == RestoreKind.Edit,
                        spec = spec,
                        metadata = metadataFor(request.kind, facts, spec, request.runCount, pending.forkedAt, checkpoint, splitPath = false),
                    )
                }
                RestorePath.Split, RestorePath.ClientFork -> {
                    if (pending.restoreWorkspace) {
                        drainRetry(onProgress) { hub.restoreWorkspaceOnly(source, request.runCount) }
                        pending = pending.copy(workspaceRestored = true)
                        onProgress(RestoreProgress.WorkspaceRestored(pending))
                    }
                    val history = HubPayloads.messagesOf(hub.messages(source)).mapNotNull { it as? JsonObject }
                    val trimmed = try {
                        trimForRestore(request.kind, history, request.runCount)
                    } catch (e: CheckpointRunException) {
                        return RestoreOutcome.Failed(runFailure(e.message.orEmpty()), e.message, pending.workspaceRestored)
                    }
                    val metadata = metadataFor(request.kind, facts, spec, request.runCount, pending.forkedAt, checkpoint, splitPath = path == RestorePath.Split)
                    drainRetry(onProgress) { hub.createRestored(spec, JsonArray(trimmed), metadata) }
                }
            }
        } catch (_: DrainTimeout) {
            return RestoreOutcome.Failed(RestoreFailure.Draining, null, pending.workspaceRestored)
        } catch (e: HubCommandException) {
            if (e.isLostReply()) return recover(pending)
            return restoreOutcomeFor(e, pending.workspaceRestored)
        } catch (_: HubConnectionClosedException) {
            return RestoreOutcome.Failed(RestoreFailure.NotConnected, null, pending.workspaceRestored)
        }
        val newId = HubPayloads.sessionIdOf(reply)
            ?: return RestoreOutcome.Failed(RestoreFailure.Kernel, NO_SESSION_MESSAGE, pending.workspaceRestored)
        detachQuietly(source)
        return RestoreOutcome.Success(newId, messages = reply["messages"] as? JsonArray, pending = pending)
    }

    /** One more attempt after `hub_draining` once the drain ends (≤ 30 s); a second refusal is final. */
    private suspend fun drainRetry(onProgress: (RestoreProgress) -> Unit, block: suspend () -> JsonObject): JsonObject {
        try {
            return block()
        } catch (e: HubCommandException) {
            if (!e.isDraining()) throw e
        }
        onProgress(RestoreProgress.Draining)
        if (!hub.awaitNotDraining(config.drainWaitMs)) throw DrainTimeout()
        try {
            return block()
        } catch (e: HubCommandException) {
            if (e.isDraining()) throw DrainTimeout()
            throw e
        }
    }

    private class DrainTimeout : Exception()

    private suspend fun detachQuietly(sessionId: String) {
        try {
            hub.detach(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort (CT §1.6): a source that was not live answers session_not_found.
        }
    }

    private fun specFor(facts: SessionFacts, request: RestoreRequest): NewSessionSpec? {
        val workspaceRoot = facts.workspaceRoot ?: facts.cwd ?: return null
        val providerId = facts.providerId?.takeIf { it.isNotBlank() } ?: return null
        val modelId = facts.modelId?.takeIf { it.isNotBlank() } ?: return null
        return NewSessionSpec(
            workspaceRoot = workspaceRoot,
            cwd = facts.cwd ?: workspaceRoot,
            providerId = providerId,
            modelId = modelId,
            mode = facts.mode,
            autoApprove = request.autoApprove,
            thinking = request.thinking ?: facts.thinking,
            reasoningEffort = request.reasoningEffort ?: facts.reasoningEffort,
            contributeAskQuestion = true,
            compaction = request.compaction,
        )
    }

    companion object {
        /** The kernel's own text (`checkpoint-restore.ts`), used when the record has no cwd at all. */
        const val NO_CWD_MESSAGE = "cwd or workspaceRoot is required to restore a checkpoint"

        /** `handleSessionRestore`'s text for a reply without a session. */
        const val NO_SESSION_MESSAGE = "Checkpoint restore did not start a session"

        /** The production wiring: the process's Hub client, Termux bridge and port release. */
        fun of(client: HubClient, api: HubSessionApi, termux: TermuxExecutor, clock: Clock, kernel: KernelCoordinator): CheckpointActions =
            CheckpointActions(HubCheckpointHub(client, api), termux, clock, kernelRelease = { kernel.portVersion.value?.release })

        /** The busy rule of C.3: starting, running, pending or stopping, an abort pending, or switching. */
        fun isBusy(facts: SessionFacts, abortPending: Boolean = false, switching: Boolean = false, versioningPending: Boolean = false): Boolean =
            abortPending || switching || versioningPending ||
                facts.status == SessionStatus.Starting || facts.status == SessionStatus.Running ||
                facts.status == SessionStatus.Pending || facts.status == SessionStatus.Stopping ||
                facts.runPhase == RunPhase.Starting || facts.runPhase == RunPhase.Running ||
                facts.runPhase == RunPhase.AwaitingUser || facts.runPhase == RunPhase.Stopping
    }
}

// ---- pure parts (unit-tested) -------------------------------------------------------------------

/** C3.5: `err == 150` or "No such file" → no git; exit 128 → not a repo; stdout `true` → ok; else unknown. */
fun gitDiagnosis(result: TermuxResult): GitDiagnosis = when {
    result.err == TermuxResult.ERR_EXECUTABLE_FAILED || result.errmsg?.contains("No such file") == true -> GitDiagnosis.NoGit
    !result.delivered -> GitDiagnosis.Unknown
    result.exitCode == 128 -> GitDiagnosis.NotARepository
    result.exitCode == 0 && result.stdout.trim() == "true" -> GitDiagnosis.RepositoryOk
    else -> GitDiagnosis.Unknown
}

/** The split / fork trim (CT §1.5.3 step 2): C1 keeps run N's user message, C2 stops before it. */
fun trimForRestore(kind: RestoreKind, messages: List<JsonObject>, runCount: Int): List<JsonObject> {
    val span: (JsonObject) -> Int = { getUserRunSpan(RunMessage.fromJson(it)) }
    return when (kind) {
        RestoreKind.Revert -> trimMessagesToCheckpoint(messages, runCount, span)
        RestoreKind.Edit -> trimMessagesBeforeUserRun(messages, runCount, span)
    }
}

/**
 * The new session's metadata (CT §1.5.2 `restoreMetadata`). C1: source/interactive/provider/model
 * only (the reverted session is untitled). C2: the source metadata spread plus `fork`. The split
 * path adds `restoredFromSessionId`, `restoredCheckpointRunCount` and the filtered history
 * (the Hub adds those itself on the atomic path).
 */
fun metadataFor(
    kind: RestoreKind,
    facts: SessionFacts,
    spec: NewSessionSpec,
    runCount: Int,
    forkedAt: String?,
    checkpoint: JsonObject?,
    splitPath: Boolean,
): JsonObject = when (kind) {
    RestoreKind.Revert -> {
        val base = HubPayloads.revertMetadata(spec.providerId, spec.modelId)
        if (!splitPath) {
            base
        } else {
            val out = LinkedHashMap<String, JsonElement>(base)
            out["restoredFromSessionId"] = JsonPrimitive(facts.sessionId)
            out["restoredCheckpointRunCount"] = JsonPrimitive(runCount)
            HubPayloads.filteredCheckpoints(checkpoint, runCount)?.let { out["checkpoint"] = it }
            JsonObject(out)
        }
    }
    RestoreKind.Edit -> {
        val sourceMetadata = if (checkpoint != null && facts.metadata["checkpoint"] == null) {
            JsonObject(facts.metadata + ("checkpoint" to checkpoint))
        } else facts.metadata
        HubPayloads.restoreMetadata(
            sourceMetadata = sourceMetadata,
            sourceSessionId = facts.sessionId,
            providerId = spec.providerId,
            modelId = spec.modelId,
            runCount = runCount,
            nowIso = forkedAt ?: "",
            checkpointRunCount = if (splitPath) runCount else null,
        )
    }
}

/** CT §1.5.4: the reply → outcome table. */
fun restoreOutcomeFor(e: HubCommandException, workspaceRestored: Boolean): RestoreOutcome {
    val message = e.hubMessage
    if (e.code == HubErrorCodes.RESTORE_FAILED || e.code == HubErrorCodes.COMMAND_FAILED) {
        if (message.startsWith("Cannot restore the workspace:")) return RestoreOutcome.WorkspaceRefused(message, workspaceOptionAvailable = true)
        if (isNotARepository(message)) return RestoreOutcome.WorkspaceRefused(message, workspaceOptionAvailable = false)
    }
    val reason = when {
        e.isSessionGone() -> RestoreFailure.SourceGone
        e.code == HubErrorCodes.SESSION_MESSAGES_NOT_FOUND -> RestoreFailure.EmptyHistory
        e.code == HubErrorCodes.INVALID_RESTORE -> RestoreFailure.InvalidRequest
        message.startsWith("checkpointRunCount must be") -> RestoreFailure.InvalidRequest
        message == NO_CWD_TEXT -> RestoreFailure.NoCwd
        e.isDraining() -> RestoreFailure.Draining
        else -> runFailure(message)
    }
    return RestoreOutcome.Failed(reason, message.takeIf { it.isNotBlank() }, workspaceRestored)
}

private const val NO_CWD_TEXT = CheckpointActions.NO_CWD_MESSAGE

/**
 * The kernel's repository check (`checkpoint-restore.ts`): "<cwd> is not a git repository" when
 * `rev-parse` prints false, but with `.git` gone `rev-parse` itself exits 128 first and the reply
 * is the exec error "Command failed: git -C <cwd> rev-parse --is-inside-work-tree\nfatal: not a
 * git repository …". Both mean the workspace option cannot work.
 */
private fun isNotARepository(message: String): Boolean =
    message.trimEnd().endsWith("is not a git repository") ||
        (message.startsWith("Command failed: git") && message.contains("rev-parse --is-inside-work-tree"))

/** Run-mapping texts (kernel and the Kotlin trim ports share them). */
internal fun runFailure(message: String): RestoreFailure = when {
    message.contains("folded into a compacted message") -> RestoreFailure.FoldedRun
    message.startsWith("No checkpoint found at or before run") || message.startsWith("Could not find user message for run") -> RestoreFailure.HistoryChanged
    else -> RestoreFailure.Kernel
}

/** CT §1.6 match: restored-from + run count (atomic / split) or fork source + forkedAt (client fork), created after the send − slack. */
fun matchesPending(row: JsonObject, pending: PendingRestore, slackMs: Long = 5_000L): Boolean {
    val metadata = row.obj("metadata") ?: return false
    val createdAt = row["createdAt"].epochMillis() ?: return false
    if (createdAt < pending.requestSentAt - slackMs) return false
    return if (pending.path == RestorePath.ClientFork) {
        val fork = metadata.obj("fork") ?: return false
        fork.str("forkedFromSessionId") == pending.sourceSessionId && fork.str("forkedAt") == pending.forkedAt
    } else {
        metadata.str("restoredFromSessionId") == pending.sourceSessionId &&
            (metadata["restoredCheckpointRunCount"] as? JsonPrimitive)?.intOrNull == pending.runCount
    }
}

