package dev.cline.termux.data.sessions

import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.data.chat.EmptySessionForkException
import dev.cline.termux.data.chat.ForkTitleStrings
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateBuilder
import dev.cline.termux.data.chat.SessionCreateRequest
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionCreateDisconnectedException
import dev.cline.termux.data.session.obj
import dev.cline.termux.data.session.str
import dev.cline.termux.data.tools.jsBoolean
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a sessions action did not happen; the screens pick the sentence (SS §5, §9). */
sealed interface ActionFailure {
    /** The Hub refused; [message] is kernel text (blank → the flow's default sentence). */
    data class Kernel(val message: String) : ActionFailure

    /** No socket, or it dropped mid-command: "Cline Hub is not connected." (SS §6). */
    data object NotConnected : ActionFailure

    /** The Hub answered without applying it (`updated:false`, or `deleted:false` and the session still exists). */
    data object NotApplied : ActionFailure

    /** `session_not_found` while reading the session: "This session no longer exists." (F1 step 3); the row is removed. */
    data object Gone : ActionFailure

    /** F4 step 2: "Cannot fork an empty session." */
    data object EmptySession : ActionFailure

    /** The record carries no provider/model to fork with. */
    data object MissingModel : ActionFailure

    /** Another action already runs on this row (§1.10; the row is disabled, so only a race reaches this). */
    data object Busy : ActionFailure

    /**
     * The app's own reply deadline passed (`client_timeout`): no kernel text to show, so the
     * snackbar uses the flow's default sentence instead of the client's "no reply within N ms".
     */
    data object TimedOut : ActionFailure
}

sealed interface ActionOutcome {
    data object Done : ActionOutcome

    /** F2 step 3: nothing to send (same title, or blank without a custom title). */
    data object Unchanged : ActionOutcome

    /** F4 step 6: open this session. */
    data class Forked(val sessionId: String) : ActionOutcome

    data class Failed(val failure: ActionFailure) : ActionOutcome
}

/**
 * `session.update` payloads (SS F2, F3). Metadata is replaced wholesale by the Hub (docs/08,
 * docs/07 §14), so every payload carries the full current metadata [current] with one key
 * set or removed. `HubPayloads.sessionUpdate` can only overlay keys; clearing a title and
 * unpinning need a removal, so these builders exist beside it.
 */
object SessionUpdatePayloads {
    /** F2 step 4: `{sessionId, title: T, metadata: M + {title: T}}`; blank [title] clears it: `{title: "", metadata: M - title}`. */
    fun rename(sessionId: String, current: JsonObject?, title: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("title", title)
        val metadata = LinkedHashMap<String, JsonElement>(current ?: emptyMap())
        if (title.isEmpty()) metadata.remove("title") else metadata["title"] = JsonPrimitive(title)
        put("metadata", JsonObject(metadata))
    }

    /** F3 step 2: `M + {pinned: true}` to pin, `M - pinned` to unpin. */
    fun pin(sessionId: String, current: JsonObject?, pinned: Boolean): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        val metadata = LinkedHashMap<String, JsonElement>(current ?: emptyMap())
        if (pinned) metadata[PINNED_METADATA_KEY] = JsonPrimitive(true) else metadata.remove(PINNED_METADATA_KEY)
        put("metadata", JsonObject(metadata))
    }
}

/** F4 step 4: keys a fork never inherits beyond docs/07 §14's (it is a fresh task: not pinned, not a schedule run). */
val FORK_DROPPED_METADATA_KEYS: Set<String> = setOf(
    "title", "usage", "aggregateUsage", "totalCost", "aggregatedAgentsCost", "messagesPath", "pid", "prompt",
    "sessionHistoryOrigin", "scheduleId", "scheduleName", "scheduleExecutionId", "scheduleRunNumber", PINNED_METADATA_KEY,
)

/**
 * F1-F5 for the sessions surfaces (design/screens/sessions.md §5, §1.10): rename, pin, fork,
 * delete and resume. One pending action per row ([SessionsRepository.beginAction]); pin is
 * optimistic and never pending. Every metadata write reads the current record first
 * (`session.get`) and spreads its metadata. Results are typed; the screens map them to
 * their snackbar copy.
 */
class SessionActions(
    private val hub: SessionsHub,
    private val repository: SessionsRepository,
    /** `SessionStore.create` (seeds the new session; drain retry inside `HubSessionApi.create`). */
    private val createSession: suspend (SessionCreateRequest) -> Result<String>,
    /** `SessionStore.resume` (F1 step 5). */
    private val resumeSession: suspend (String, SessionConfigInput?) -> Result<Unit>,
    /** The kernel's global auto-approve (`ApprovalModeRepository.autoApproveForNewSession`, decided A1): the fork's tool policies. */
    private val autoApproveDefault: suspend () -> Boolean,
    /** "Untitled" / " (fork)" from resources; supplied by the graph so this layer holds no resource lookups. */
    private val forkTitleStrings: () -> ForkTitleStrings,
    private val clock: Clock,
    /** A delete was confirmed gone (CR §11.3/§11.4): KnownSessions and the notifier forget the session. */
    private val onDeleted: suspend (String) -> Unit = {},
    /** G3: `WorktreeCommands.remove` through Termux for a deleted session's task worktree (best effort). */
    private val removeWorktree: suspend (path: String) -> Unit = {},
    /** The global compaction mode for the fork's `sessionConfig.compaction` (G1); null = `{enabled:true}`. */
    private val compaction: suspend () -> JsonObject? = { null },
) {
    // ---- F2 rename ---------------------------------------------------------------------------------

    /**
     * F2 steps 3-6. [input] is the editor text; it is normalised (`normalizeTitle(input).trim()`).
     * Blank clears the custom title (S1); the S2 editor disables Save for blank itself.
     */
    suspend fun rename(sessionId: String, input: String): ActionOutcome {
        val row = repository.summary(sessionId)
        val title = normalizeTitle(input.replace('\n', ' ')).trim()
        if (row != null) {
            if (title.isEmpty() && !row.hasCustomTitle) return ActionOutcome.Unchanged
            if (title.isNotEmpty() && title == row.title) return ActionOutcome.Unchanged
        }
        if (!repository.isConnected) return ActionOutcome.Failed(ActionFailure.NotConnected)
        if (!repository.beginAction(sessionId, PendingKind.Rename)) return ActionOutcome.Failed(ActionFailure.Busy)
        repository.armAnnotationOverride(sessionId)
        return try {
            val current = currentMetadata(sessionId)
            val reply = hub.update(SessionUpdatePayloads.rename(sessionId, current, title))
            applied(reply)
        } catch (e: Exception) {
            failure(sessionId, e)
        } finally {
            repository.endAction(sessionId)
        }
    }

    // ---- F3 pin ------------------------------------------------------------------------------------

    /** F3: optimistic flip, read-modify-write, refresh after 50 ms; rolls back on failure. */
    suspend fun setPinned(sessionId: String, pinned: Boolean): ActionOutcome {
        if (!repository.isConnected) return ActionOutcome.Failed(ActionFailure.NotConnected)
        repository.armAnnotationOverride(sessionId)
        repository.setPinnedOverride(sessionId, pinned)
        return try {
            val current = currentMetadata(sessionId)
            val reply = hub.update(SessionUpdatePayloads.pin(sessionId, current, pinned))
            val outcome = applied(reply)
            if (outcome == ActionOutcome.Done) repository.settlePinnedOverride(sessionId) else repository.setPinnedOverride(sessionId, null)
            repository.scheduleRefresh(ACTION_REFRESH_DELAY_MS)
            outcome
        } catch (e: Exception) {
            repository.setPinnedOverride(sessionId, null)
            failure(sessionId, e)
        }
    }

    // ---- F4 fork -----------------------------------------------------------------------------------

    /**
     * F4 steps 1-7: `session.get` + `session.messages` → `session.create {initialMessages}`, the
     * whole session. The app's only fork (decided A16 and A19): the chat's three-dot menu and
     * the drawer's row menu; message rows and the slash popup have none. It never cuts the
     * history and writes no `fork.beforeRunCount` (CLI `forkCurrentSession`, desktop
     * `handleForkUnlocked` without a cutoff). The user row's Edit is a different function, the
     * checkpoint flow (CT C.6), which carries the edited run as the desktop's edit-fork does.
     */
    suspend fun fork(sessionId: String): ActionOutcome {
        if (!repository.isConnected) return ActionOutcome.Failed(ActionFailure.NotConnected)
        if (!repository.beginAction(sessionId, PendingKind.Fork)) return ActionOutcome.Failed(ActionFailure.Busy)
        return try {
            val reply = hub.get(sessionId)
            val record = reply.obj("session") ?: reply.obj("snapshot") ?: reply
            val metadata = HubPayloads.metadataOf(reply) ?: JsonObject(emptyMap())
            val messages = HubPayloads.messagesOf(hub.messages(sessionId))
            if (messages.isEmpty()) return ActionOutcome.Failed(ActionFailure.EmptySession)
            val request = forkRequest(
                sourceSessionId = sessionId,
                record = record,
                metadata = metadata,
                messages = messages,
                autoApprove = autoApproveDefault(),
                compaction = compaction(),
            ) ?: return ActionOutcome.Failed(ActionFailure.MissingModel)
            val created = createSession(request)
            val newId = created.getOrElse { return failure(sessionId, it) }
            repository.noteCreated(newId)
            repository.scheduleRefresh(ACTION_REFRESH_DELAY_MS)
            ActionOutcome.Forked(newId)
        } catch (e: Exception) {
            failure(sessionId, e)
        } finally {
            repository.endAction(sessionId)
        }
    }

    /**
     * The fork's `session.create` (F4 steps 3-5): `SessionCreateBuilder.fork` (docs/07 §14 strip
     * list, `fork {forkedFromSessionId, forkedAt, source}` plus the source's checkpoint history as
     * `fork.checkpoints`, derived title) minus [FORK_DROPPED_METADATA_KEYS], workspace and model
     * from the source record, [compaction] from the global mode (G1). Null without a provider/model.
     */
    fun forkRequest(
        sourceSessionId: String,
        record: JsonObject,
        metadata: JsonObject,
        messages: JsonArray,
        autoApprove: Boolean,
        compaction: JsonObject? = null,
    ): SessionCreateRequest? {
        val provider = metadata.str("provider")?.trim().orEmpty()
        val model = metadata.str("model")?.trim().orEmpty()
        if (!isKnownModelField(provider) || !isKnownModelField(model)) return null
        val cwd = record.str("cwd").orEmpty()
        val root = record.str("workspaceRoot").orEmpty().ifBlank { cwd }
        val config = SessionConfigInput(
            workspaceRoot = root,
            cwd = cwd.ifBlank { root },
            providerId = provider,
            modelId = model,
            mode = metadata.str("mode")?.takeIf { it.isNotBlank() } ?: "act",
            autoApprove = autoApprove,
            compaction = compaction,
        )
        val base = SessionCreateBuilder.fork(
            config = config,
            sourceSessionId = sourceSessionId,
            sourceMetadata = metadata,
            messages = messages,
            nowIso = Instant.now(clock).toString(),
            titleStrings = forkTitleStrings(),
        )
        val title = base.metadata["title"]
        val cleaned = LinkedHashMap<String, JsonElement>()
        base.metadata.forEach { (k, v) -> if (k !in FORK_DROPPED_METADATA_KEYS) cleaned[k] = v }
        title?.let { cleaned["title"] = it }
        return SessionCreateRequest(base.spec, JsonObject(cleaned))
    }

    // ---- F5 delete ---------------------------------------------------------------------------------

    /**
     * F5 steps 2-5: `session.delete`; `deleted:false` is verified with `session.get`
     * (`session_not_found` = success). G3: a session that ran in a task worktree takes the
     * worktree with it once it is gone, unless another session still lives in (or under) it.
     */
    suspend fun delete(sessionId: String): ActionOutcome {
        if (!repository.isConnected) return ActionOutcome.Failed(ActionFailure.NotConnected)
        if (!repository.beginAction(sessionId, PendingKind.Delete)) return ActionOutcome.Failed(ActionFailure.Busy)
        return try {
            val worktree = taskWorktreeOf(sessionId)
            val reply = hub.delete(sessionId)
            val gone = reply["deleted"].jsBoolean() == true || verifyGone(sessionId)
            if (gone) {
                runCatching { onDeleted(sessionId) }
                repository.forget(sessionId)
                repository.refreshNow()
                if (worktree != null && !worktreeStillUsed(worktree, sessionId)) runCatching { removeWorktree(worktree) }
                ActionOutcome.Done
            } else {
                ActionOutcome.Failed(ActionFailure.NotApplied)
            }
        } catch (e: Exception) {
            ActionOutcome.Failed(e.toActionFailure())
        } finally {
            repository.endAction(sessionId)
        }
    }

    /**
     * The session's cwd when it is a task worktree (desktop sidecar `delete_chat_session`: the
     * row's `cwd`, read before the delete). A session missing from the list is read with `session.get`.
     */
    private suspend fun taskWorktreeOf(sessionId: String): String? {
        val summary = repository.summary(sessionId)
            ?: runCatching { hub.get(sessionId).let { reply -> SessionSummary.fromRecord(reply.obj("session") ?: reply) } }.getOrNull()
            ?: return null
        val cwd = summary.cwd.trim().ifEmpty { summary.workspaceRoot.trim() }.trimEnd('/')
        return cwd.takeIf(WorktreeCommands::isTaskWorktreePath)
    }

    /** Another listed session's cwd is [path] or under it (desktop: a second thread started there). */
    private fun worktreeStillUsed(path: String, deletedId: String): Boolean =
        repository.state.value.rows.any { row ->
            val cwd = row.summary.cwd.trim()
            row.summary.id != deletedId && (cwd == path || cwd.startsWith("$path/"))
        }

    private suspend fun verifyGone(sessionId: String): Boolean = try {
        hub.get(sessionId)
        false
    } catch (e: HubCommandException) {
        if (e.isSessionGone()) true else throw e
    }

    // ---- F1 resume ---------------------------------------------------------------------------------

    /**
     * F1 step 5 / "Restore": resumes a persisted session through `SessionStore` (which owns the
     * transcript and the error banner); the row shows Starting meanwhile.
     */
    suspend fun resume(sessionId: String, config: SessionConfigInput? = null): ActionOutcome {
        if (!repository.isConnected) return ActionOutcome.Failed(ActionFailure.NotConnected)
        repository.setStarting(sessionId, true)
        return try {
            resumeSession(sessionId, config).fold(
                onSuccess = { ActionOutcome.Done },
                onFailure = { ActionOutcome.Failed(it.toActionFailure()) },
            )
        } finally {
            repository.setStarting(sessionId, false)
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** `session.get {sessionId}` → `session.metadata` (the wholesale base for `session.update`). */
    private suspend fun currentMetadata(sessionId: String): JsonObject =
        HubPayloads.metadataOf(hub.get(sessionId)) ?: JsonObject(emptyMap())

    private suspend fun applied(reply: JsonObject): ActionOutcome {
        reply.obj("session")?.let { repository.applyRecord(it) }
        val updated = reply["updated"].jsBoolean()
        return if (updated == false) ActionOutcome.Failed(ActionFailure.NotApplied) else ActionOutcome.Done
    }

    private suspend fun failure(sessionId: String, error: Throwable): ActionOutcome {
        val failure = error.toActionFailure()
        if (failure == ActionFailure.Gone) repository.forget(sessionId)
        return ActionOutcome.Failed(failure)
    }

    companion object {
        /** F3 step 3, F4 step 6: desktop `scheduleRefresh` after a mutation. */
        const val ACTION_REFRESH_DELAY_MS = 50L
    }
}

/** docs/07 §9 → [ActionFailure]. */
fun Throwable.toActionFailure(): ActionFailure = when (this) {
    is HubCommandException -> when {
        code == HubErrorCodes.HUB_CONNECTION_CLOSED -> ActionFailure.NotConnected
        code == HubErrorCodes.CLIENT_TIMEOUT -> ActionFailure.TimedOut
        isSessionGone() -> ActionFailure.Gone
        else -> ActionFailure.Kernel(hubMessage)
    }
    is HubConnectionClosedException -> ActionFailure.NotConnected
    is SessionCreateDisconnectedException -> ActionFailure.NotConnected
    is EmptySessionForkException -> ActionFailure.EmptySession
    else -> ActionFailure.Kernel(message.orEmpty())
}
