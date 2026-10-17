package dev.cline.termux.data.chat

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin port of the desktop's chat schema (apps/examples/desktop-app/webview/lib/chat-schema.ts)
 * as design/screens/home-chat.md §1.3 specifies it. Pure data: the reducer (chat.state), the
 * render pipeline (chat.model) and the rows (ui.transcript) all share these types.
 */

/** `ChatMessageRoleSchema`. */
enum class Role { User, Assistant, Tool, System, Status, Error }

/** `ChatMessageImageSchema`: an attached or generated image, base64 in [data]. */
@Immutable
data class ChatImage(
    val id: String,
    /** One of image/png, image/jpeg, image/gif, image/webp. */
    val mediaType: String,
    val data: String,
)

/** `GeneratedMediaModalitySchema` (sdk/packages/shared/src/llms/media.ts). */
enum class MediaModality { Image, Audio, Video, File }

/** `GeneratedMediaSourceSchema`. */
@Immutable
sealed interface MediaSource {
    @Immutable data class Base64(val data: String) : MediaSource
    @Immutable data class Url(val url: String) : MediaSource
    @Immutable data class Artifact(val artifactId: String) : MediaSource
}

/** `GeneratedMediaSchema`: media the model produced during a turn. */
@Immutable
data class GeneratedMedia(
    val id: String,
    val modality: MediaModality,
    val mediaType: String,
    val source: MediaSource,
    val name: String? = null,
    val sizeBytes: Long? = null,
)

/** `ProviderAuthInfo.localCli` (sdk/packages/shared/src/provider-auth.ts). */
@Immutable
@Serializable
data class LocalCli(val command: String, val docsUrl: String? = null)

/** Auth facts stamped on every error row so the credential action can be resolved (§1.3.5). */
@Immutable
@Serializable
data class ProviderAuthInfo(
    val providerId: String,
    val capabilities: List<String> = emptyList(),
    val localCli: LocalCli? = null,
)

/** `meta.checkpoint`: set only once the app registers the `checkpoint.create` contribution. */
@Immutable
data class CheckpointRef(
    val ref: String,
    val createdAt: Long,
    val runCount: Int,
    /** "stash" or "commit" when known. */
    val kind: String? = null,
)

/** `ChatMessageSchema.meta`: the desktop's optional fields plus the app's turn bookkeeping. */
@Immutable
data class Meta(
    val stream: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolOutput: String? = null,
    val toolOutputTruncated: Boolean = false,
    val toolDetachable: Boolean = false,
    val iteration: Int? = null,
    val agentId: String? = null,
    val conversationId: String? = null,
    val hookEventName: String? = null,
    val messageKind: String? = null,
    val displayRole: String? = null,
    /** "credentials" | "interrupted" | a `session.notice` reason. */
    val reason: String? = null,
    /** recovery | stop | status (notice rows). */
    val noticeType: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val totalCost: Double? = null,
    /** Set on every Error row: the turn's provider (§1.3.4 TurnRecord). */
    val providerId: String? = null,
    /** §1.3.5; null means the auth facts are unknown. */
    val providerAuth: ProviderAuthInfo? = null,
    val modelId: String? = null,
    /** The turn this live row belongs to (live rows only). */
    val requestId: String? = null,
    val userRunSpan: Int? = null,
    val runCount: Int? = null,
    val checkpoint: CheckpointRef? = null,
    /** Live `reasoning.finished` time, for "Thought for Ns". */
    val thoughtEndedAt: Long? = null,
    /** A17: the app's mistake-limit stop notice row, the request's `consecutiveMistakes` (`StopNotices.row`). */
    val consecutiveMistakes: Int? = null,
)

/** One transcript row. [renderKey] is the LazyColumn / saveable-state key and survives reconciliation (§1.3.3). */
@Immutable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    /** User/assistant text, tool payload JSON `{toolName,input,result?,isError?}`, or error detail. */
    val content: String,
    val reasoning: String? = null,
    val reasoningRedacted: Boolean = false,
    val images: List<ChatImage> = emptyList(),
    val media: List<GeneratedMedia> = emptyList(),
    /** Epoch milliseconds. */
    val createdAt: Long,
    val renderKey: String = id,
    val meta: Meta = Meta(),
)

/**
 * `run.failed` payloads differ by publisher (session-event-projector.ts and run-handlers.ts):
 * `error` is a plain string, never an object, and may be absent; the `run.start` success path
 * adds `result`. Detail = `error ?: text ?: ""`.
 */
@Serializable
data class RunFailedPayload(
    val reason: String = "failed",
    val error: String? = null,
    val text: String? = null,
    val result: JsonObject? = null,
    val snapshot: JsonObject? = null,
) {
    val detail: String get() = error ?: text ?: ""
}

/** Port of the desktop `ChatSessionStatus` (§1.4); the visual mapping is only `SessionVisual.of`. */
enum class ChatStatus {
    Idle, Starting, Running, Stopping, Completed, Cancelled, Failed;

    /** Desktop `BUSY_STATUSES`. */
    val isBusy: Boolean get() = this == Starting || this == Running || this == Stopping
}
