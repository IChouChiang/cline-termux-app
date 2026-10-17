package dev.cline.termux.data.session

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.ChatStatusDerivation

/** Whether the canonical rows were loaded (`session.get` + `session.messages`). */
sealed interface SeedState {
    data object NotSeeded : SeedState
    data object Seeding : SeedState

    /** Seeded when the store had applied events up to [atSequence]. */
    data class Seeded(val atSequence: Long) : SeedState

    /** `session.messages` / `session.get` failed (not `session_not_found`): HC §3.5 "History load error". */
    data class Failed(val message: String) : SeedState

    /** `session.get` → `session_not_found` ("Unknown session: <id>"): HC §3.5 "Missing". */
    data object Missing : SeedState
}

/**
 * `metadata.autoApproveTools`: the policy the Hub stamps on a session's record at
 * `session.create` (`resolveSessionAutoApproveTools`, the create's `toolPolicies["*"]`).
 */
const val AUTO_APPROVE_TOOLS_KEY = "autoApproveTools"

/**
 * `metadata.prompt`: the session's first prompt as the kernel stored it (wrapped, docs/08), set
 * when a brand-new session is persisted at that prompt; the title it derived comes from it.
 */
const val PROMPT_METADATA_KEY = "prompt"

/** HC §1.2 `ChatUiState.liveness`. */
enum class Liveness {
    /** Attached on the current socket (`session.attach` succeeded, or we created / resumed it) and not hinted persisted. */
    Live,

    /**
     * Known only from storage (the not-live hint is set, or not attached): a send must resume it
     * first (or recover on `session_not_found`). Attach succeeds on a persisted session without
     * making it live (FX 05), so the hint wins over [SessionState.live_].
     */
    Persisted,

    /** The Hub does not know the session at all. */
    Missing,
}

/**
 * One session as the app knows it (docs/07 §4.1). App-scoped in `SessionStore`; screens map
 * it to their UiState. [messages] is the rendered transcript (`Reconciler.merge`), memoized.
 */
@Immutable
data class SessionState(
    val sessionId: String,
    val facts: SessionFacts = SessionFacts(sessionId),
    /** From `session.messages` (HistoryMapper); empty until seeded. */
    val canonical: List<ChatMessage> = emptyList(),
    /** The raw message count of the last `session.messages` reply. */
    val canonicalCount: Int = 0,
    /** Rows and cursor state built from events since the seed (ChatProjector). */
    val projection: LiveProjection = LiveProjection(),
    /** Open and recently closed turns of this session (persisted in `chat.turns`). */
    val turns: List<TurnRecord> = emptyList(),
    val seed: SeedState = SeedState.NotSeeded,
    /** Attached on this connection (SP S8.3 liveness): true only after a successful `session.attach` on the current socket. */
    val live_: Boolean = false,
    /**
     * The not-live hint (HC §3.5): the session is not in this Hub lifetime's live set (no
     * `session.create`, resume or `run.started` seen for it) and `session.get` reported it
     * completed/aborted, so the first send resumes up front. `session_not_found` is authoritative.
     */
    val persistedHint: Boolean = false,
    /** The replay began above this session's last known sequence: events may be missing. */
    val replayGap: Boolean = false,
    /** The app's `session.create` for this session is in flight (new, resume, Resend). */
    val creating: Boolean = false,
    /** A resume or history error to show in the error banner (HC §3.10), kernel text. */
    val errorBanner: SessionError? = null,
    /** Last time anything touched this state (eviction, docs/07 §4.1). */
    val touchedAt: Long = 0,
    /**
     * The runtime's tool policy as last seen in a record ([AUTO_APPROVE_TOOLS_KEY]; decided A1),
     * kept when a later record drops the key. The Hub strips it from every `session.update` that
     * carries metadata (a pin, for one: `stripServerOwnedSessionMetadata`) and stores the rest
     * wholesale, while the live runtime keeps the policy it was created with. `SessionStore`
     * records it on every write; null when no record of this process had it.
     */
    val runtimeAutoApprove: Boolean? = null,
    /**
     * The tool policy of the live runtime's latest `session.create` (decided A1): the
     * `toolPolicies["*"].autoApprove` of this app's own create (new, resume, restart, Resend,
     * move), or the `metadata.autoApproveTools` of a `session.created` event (any client's
     * create). It outranks every later record until the next create, this app's own detach or a
     * new Hub lifetime: core 0.0.91 stamps a resume's policy on the runtime's record only in
     * memory, so the `session.updated` / `run.*` records at a run's end and `session.get` after
     * it carry the session's creation-time value again while the runtime keeps the new one.
     */
    val createdAutoApprove: Boolean? = null,
) {
    /**
     * Whether the session's runtime auto-approves every tool call: [createdAutoApprove], else the
     * record's `metadata.autoApproveTools`, else [runtimeAutoApprove]; null when unknown.
     */
    val autoApproveTools: Boolean? get() = createdAutoApprove ?: facts.metadata.bool(AUTO_APPROVE_TOOLS_KEY) ?: runtimeAutoApprove

    /** Live rows (docs/07 §4.1 `live`). */
    val live: List<ChatMessage> get() = projection.messages

    val messages: List<ChatMessage> by lazy(LazyThreadSafetyMode.PUBLICATION) { Reconciler.merge(canonical, projection.messages) }

    val openTurn: TurnRecord? get() = turns.lastOrNull { it.open }

    /**
     * A turn cut short by a Hub restart leaves the session Idle (HC §1.3.4), although the
     * restarted Hub reports that run as failed, cancelled or completed in its session record.
     */
    val chatStatus: ChatStatus
        get() {
            val derived = ChatStatusDerivation.of(facts, creating)
            val interrupted = openTurn == null && turns.lastOrNull()?.outcome == TurnOutcome.Interrupted
            return if (interrupted && derived in ENDED) ChatStatus.Idle else derived
        }

    val liveness: Liveness
        get() = when {
            seed == SeedState.Missing -> Liveness.Missing
            live_ && !persistedHint -> Liveness.Live
            else -> Liveness.Persisted
        }

    val streamingMessageId: String? get() = projection.streamingMessageId

    private companion object {
        val ENDED = setOf(ChatStatus.Completed, ChatStatus.Cancelled, ChatStatus.Failed)
    }
}

/** A banner-level failure (HC §3.10); the screen picks the sentence by [kind]. */
@Immutable
data class SessionError(
    val kind: Kind,
    /** Kernel text (`error.message`), shown verbatim; empty when [reason] carries an app-side cause. */
    val message: String,
    /** The attempted config's provider and auth facts (credential action, §3.10). */
    val providerId: String? = null,
    val providerAuth: dev.cline.termux.data.chat.ProviderAuthInfo? = null,
    /** An app-side cause without kernel text; the banner shows [Reason.text] in place of [message]. */
    val reason: Reason? = null,
) {
    /** App-side causes, each with its own copy (strings_session.xml). */
    enum class Reason(@param:StringRes val text: Int) {
        /** Resume without a composer config, and the session record has no workspace or model. */
        MissingResumeConfig(R.string.chat_resume_missing_config),
    }

    enum class Kind {
        /** "Couldn't start the session: %1$s". */
        Start,

        /** "Couldn't resume this session: %1$s". */
        Resume,

        /** "Couldn't load this session: %1$s". */
        History,
    }
}
