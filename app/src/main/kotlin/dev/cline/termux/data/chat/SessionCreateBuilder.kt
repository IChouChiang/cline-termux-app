package dev.cline.termux.data.chat

import android.content.res.Resources
import dev.cline.termux.R
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.NewSessionSpec
import dev.cline.termux.data.session.UserInputFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The connection a `session.create` asks for (home-chat.md §1.3.6 `SessionConfigInput`):
 * workspace, provider, model, mode and flags. Never an API key (the daemon merges the stored
 * credential, FM) and never `configExtensions`.
 */
data class SessionConfigInput(
    val workspaceRoot: String,
    val cwd: String = workspaceRoot,
    val providerId: String,
    val modelId: String,
    val mode: String = "act",
    val autoApprove: Boolean = false,
    val thinking: Boolean? = null,
    val reasoningEffort: String? = null,
    val enableSpawnAgent: Boolean = true,
    val enableAgentTeams: Boolean = true,
    /** `sessionConfig.compaction` from the global compaction mode (`compactionConfig`); null = `{enabled:true}`. */
    val compaction: JsonObject? = null,
)

/** A built `session.create`: [spec] becomes `sessionConfig`, [metadata] the record's metadata. */
data class SessionCreateRequest(val spec: NewSessionSpec, val metadata: JsonObject) {
    /** The exact wire payload (`HubPayloads.sessionCreate`), with the askQuestion contribution. */
    fun payload(): JsonObject = HubPayloads.sessionCreate(spec, metadata)
}

/** "Fork" on a session without history (CLI "Cannot fork an empty session."). */
class EmptySessionForkException : IllegalStateException("Cannot fork an empty session.")

/**
 * `buildSessionCreate` (home-chat.md §1.3.6, changes-tools-checkpoints.md §1.5.2): every
 * `session.create` the app sends — first send, resume, auto-resume, Resend, fork — is built
 * here so no path loses the askQuestion contribution, `checkpoint.enabled = true` (D4),
 * `compaction` (G1) or the metadata rules of docs/07 §14.
 */
object SessionCreateBuilder {

    /**
     * The metadata key of a first send's per-create nonce. A lost `session.create` reply is
     * recovered by finding the session that carries it (docs/07 §9); the create is never resent.
     */
    const val CLIENT_CREATE_ID = "clientCreateId"

    /**
     * First send from Home (§2.7): `{source:"android", interactive:true, provider, model}`, plus
     * [clientCreateId] as `metadata.clientCreateId` when given.
     */
    fun firstSend(config: SessionConfigInput, title: String? = null, clientCreateId: String? = null): SessionCreateRequest {
        val spec = config.toSpec().copy(title = title)
        val metadata = HubPayloads.createMetadata(spec)
        return SessionCreateRequest(
            spec,
            if (clientCreateId == null) metadata else JsonObject(metadata + (CLIENT_CREATE_ID to JsonPrimitive(clientCreateId))),
        )
    }

    /**
     * Resume / auto-resume on `session_not_found` / Resend (§3.6, §3.12, §3.4.7): the old id plus
     * the `session.messages` result as `initialMessages`. The persisted `metadata.source` is kept,
     * so a TUI session stays `cli` and its tool set does not change mid-history (Q13). A
     * run-boundary restart (decided A1) adds the runtime's [initialCompactionState].
     */
    fun resume(
        config: SessionConfigInput,
        sessionId: String,
        messages: JsonArray,
        persistedMetadata: JsonObject?,
        initialCompactionState: JsonObject? = null,
    ): SessionCreateRequest {
        val source = (persistedMetadata?.get("source") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: HubPayloads.SOURCE
        val spec = config.toSpec().copy(
            resumeSessionId = sessionId,
            initialMessages = messages,
            source = source,
            initialCompactionState = initialCompactionState,
        )
        return SessionCreateRequest(spec, HubPayloads.createMetadata(spec))
    }

    /**
     * Fork (§3.4.2, sessions F4, CLI `forkCurrentSession`): the parent's metadata minus the
     * non-inherited keys (CT §1.5.2), re-stamped `source:"android"`, `interactive:true`, the
     * derived title and `fork {forkedFromSessionId, forkedAt, source:"android", checkpoints}`,
     * the CLI's and the desktop's whole-session fork (neither writes `beforeRunCount`).
     */
    fun fork(
        config: SessionConfigInput,
        sourceSessionId: String,
        sourceMetadata: JsonObject?,
        messages: JsonArray,
        nowIso: String,
        titleStrings: ForkTitleStrings,
    ): SessionCreateRequest {
        if (messages.isEmpty()) throw EmptySessionForkException()
        val title = ForkTitle.derive(
            sourceTitle = sourceMetadata?.stringOrNull("title"),
            sourcePrompt = sourceMetadata?.stringOrNull("prompt"),
            messages = messages,
            strings = titleStrings,
        )
        val base = HubPayloads.restoreMetadata(
            sourceMetadata = sourceMetadata,
            sourceSessionId = sourceSessionId,
            providerId = config.providerId,
            modelId = config.modelId,
            runCount = null,
            nowIso = nowIso,
            forkSource = HubPayloads.SOURCE,
        )
        val metadata = JsonObject(base + ("title" to JsonPrimitive(title)))
        val spec = config.toSpec().copy(initialMessages = messages, title = title)
        return SessionCreateRequest(spec, metadata)
    }

    fun SessionConfigInput.toSpec(): NewSessionSpec = NewSessionSpec(
        workspaceRoot = workspaceRoot,
        cwd = cwd,
        providerId = providerId,
        modelId = modelId,
        mode = mode,
        autoApprove = autoApprove,
        thinking = if (reasoningEffort != null) true else thinking,
        reasoningEffort = reasoningEffort,
        enableSpawnAgent = enableSpawnAgent,
        enableAgentTeams = enableAgentTeams,
        compaction = compaction,
    )

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** "Untitled" and " (fork)" from `strings_session.xml` (CLI `fork/title.ts` copy). */
data class ForkTitleStrings(val untitled: String, val suffix: String) {
    companion object {
        fun from(resources: Resources): ForkTitleStrings = ForkTitleStrings(
            untitled = resources.getString(R.string.fork_title_untitled),
            suffix = resources.getString(R.string.msg_fork_title_suffix),
        )
    }
}

/** 1:1 port of the CLI's `deriveForkSessionTitle` (`apps/cli/src/runtime/interactive/fork/title.ts`). */
object ForkTitle {
    const val MAX_FORK_TITLE_LENGTH = 120

    fun derive(sourceTitle: String?, sourcePrompt: String?, messages: JsonArray, strings: ForkTitleStrings): String {
        val base = normalize(sourceTitle) ?: normalize(sourcePrompt) ?: inferFromMessages(messages) ?: strings.untitled
        val maxBase = MAX_FORK_TITLE_LENGTH - strings.suffix.length
        // DS §8.2.7: the cut backs off to a grapheme boundary, never inside a surrogate pair.
        val truncated = if (base.length <= maxBase) base else takeGraphemePrefix(base, maxBase).trimEnd()
        return truncated + strings.suffix
    }

    private fun normalize(value: String?): String? =
        UserInputFormat.normalizeUserInput(UserInputFormat.formatDisplayUserInput(value))
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun extractMessageText(message: JsonObject): String? = when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") (obj["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("\n").trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun inferFromMessages(messages: JsonArray): String? {
        for (role in listOf("user", "assistant")) {
            for (element: JsonElement in messages) {
                val message = element as? JsonObject ?: continue
                if ((message["role"] as? JsonPrimitive)?.contentOrNull != role) continue
                normalize(extractMessageText(message))?.let { return it }
            }
        }
        return null
    }
}
