package dev.cline.termux.ui.composer

import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ContextUsageCalculator
import dev.cline.termux.data.chat.ContextUsageData
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.model.ModelChoice
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.model.modelCapabilities
import dev.cline.termux.data.model.reasoningChipLabel
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.ComposerMode
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.QueuedPrompt
import dev.cline.termux.data.session.RunRef
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.UserInputFormat
import java.time.Clock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * The composer's seams (docs/07 §0 rule 8: controllers talk to stores, never the socket).
 * Each port has the app's adapter here; tests pass fakes.
 */

/** The parts of [SessionStore] the composer uses. */
interface ComposerSessions {
    fun state(sessionId: String): StateFlow<SessionState>
    val signals: Flow<SessionSignal>
    suspend fun send(request: SendRequest): SendOutcome
    suspend fun abort(sessionId: String): Result<Unit>

    /** [SessionStore.takeReturnedPrompt]: a prompt handed back while no composer had the session bound. */
    fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent?
}

fun SessionStore.asComposerSessions(): ComposerSessions = object : ComposerSessions {
    override fun state(sessionId: String): StateFlow<SessionState> = this@asComposerSessions.state(sessionId)
    override val signals: Flow<SessionSignal> get() = this@asComposerSessions.signals
    override suspend fun send(request: SendRequest): SendOutcome = this@asComposerSessions.send(request)
    override suspend fun abort(sessionId: String): Result<Unit> = this@asComposerSessions.abort(sessionId)
    override fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent? = this@asComposerSessions.takeReturnedPrompt(sessionId)
}

/** DataStore `composer.draft.<key>` and `composer.mode.*` (docs/07 §7.2). */
interface ComposerPrefs {
    suspend fun draft(key: String): String?

    /** Never called with secret text; null or empty removes the key. */
    suspend fun setDraft(key: String, text: String?)

    /** `composer.mode.<sessionId>` falling back to `composer.mode.default` (Home: the default). */
    suspend fun mode(sessionId: String?): ComposerMode

    /** True when `composer.mode.<sessionId>` itself holds a value different from the default. */
    suspend fun hasSessionMode(sessionId: String): Boolean
    suspend fun setMode(mode: ComposerMode, sessionId: String?)

    companion object {
        fun of(prefs: AppPreferences): ComposerPrefs = object : ComposerPrefs {
            override suspend fun draft(key: String): String? = prefs.composerDraft(key)
            override suspend fun setDraft(key: String, text: String?) {
                prefs.setComposerDraft(key, text)
            }
            override suspend fun mode(sessionId: String?): ComposerMode = prefs.composerModeNow(sessionId)
            override suspend fun hasSessionMode(sessionId: String): Boolean =
                prefs.composerModeNow(sessionId) != prefs.composerModeNow(null)

            // A choice on Home (null) is `composer.mode.default`, the start of every new session (C1.7).
            override suspend fun setMode(mode: ComposerMode, sessionId: String?) {
                prefs.setComposerMode(mode, sessionId)
            }
        }
    }
}

/**
 * The cached `settings.list` snapshot the send pipeline resolves slash commands against
 * (C5.6). The slash panel's source refreshes it on open and on `settings.changed`.
 */
fun interface SlashSnapshotSource {
    suspend fun snapshot(workspaceRoot: String): SettingsSnapshot?
}

/**
 * The default [SlashSnapshotSource]: `settings.list {workspaceRoot, cwd}` through the Hub,
 * kept per workspace for [ttlMs]; a failed read keeps the previous snapshot (desktop "keep
 * built-in commands on error").
 */
class HubSlashSnapshots(
    private val api: HubSessionApi,
    private val clock: Clock,
    private val ttlMs: Long = 60_000L,
) : SlashSnapshotSource {
    private data class Entry(val snapshot: SettingsSnapshot, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override suspend fun snapshot(workspaceRoot: String): SettingsSnapshot? {
        val cached = cache[workspaceRoot]
        if (cached != null && clock.millis() - cached.at < ttlMs) return cached.snapshot
        val fresh = runCatching { SettingsSnapshot.fromReply(api.settingsList(workspaceRoot)) }.getOrNull()
        if (fresh != null) cache[workspaceRoot] = Entry(fresh, clock.millis())
        return fresh ?: cached?.snapshot
    }

    /** Drop every cached snapshot (after `settings.changed`). */
    fun invalidate() = cache.clear()
}

/** The latest request's token counts and its own cost (C1.11 ring). */
data class LatestUsage(
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheReadTokens: Long,
    val rowCost: Double?,
)

/**
 * What the composer derives from a [SessionState] (P4): a small value that most Hub events
 * (`assistant.delta`, tool chunks, heartbeats) leave equal, so `distinctUntilChanged` drops them
 * before the composer's derivation runs.
 */
data class ComposerSessionInputs(
    val sessionId: String,
    val seed: SeedState,
    /** `messages.isEmpty()`. */
    val empty: Boolean,
    val runPhase: RunPhase,
    val providerId: String?,
    val modelId: String?,
    val thinking: Boolean?,
    val reasoningEffort: String?,
    val pendingPrompts: List<QueuedPrompt>,
    val latestUsage: LatestUsage?,
    val sessionTotalCost: Double?,
    /** Only once seeded (C1.7: a history session starts in its last user message's mode). */
    val lastUserMode: PromptMode?,
    /** The app's `session.create` for this session is in flight (`SessionState.creating`: a resume or restart). */
    val creating: Boolean = false,
    /** `SessionFacts.activeRun`: which run a failed Stop was about (trial row 11, [StopFailure][dev.cline.termux.data.session.StopFailure]). */
    val activeRun: RunRef? = null,
)

/** Pure projections from store facts and the catalog onto the composer's chips and ring. */
object ComposerProjection {

    /** The provider/model the chip shows: the session's own, or Home's resolved default. */
    fun choice(facts: SessionFacts?, home: ModelChoice?): ModelChoice? = choice(facts?.providerId, facts?.modelId, home)

    /** [choice] from the session's provider/model ([providerId] null: Home's default). */
    fun choice(providerId: String?, modelId: String?, home: ModelChoice?): ModelChoice? =
        if (providerId == null) home else ModelChoice(providerId, modelId)

    /** C1.11 `ModelChipState`. [busy] disables it; the first run keeps it enabled (C1.2). */
    fun modelChip(choice: ModelChoice?, catalog: CatalogState, busy: Boolean): ModelChipState {
        if (choice == null) return ModelChipState.Empty.copy(enabled = true)
        val caps = modelCapabilities(catalog, choice.providerId, choice.modelId)
        val providerName = catalog.provider(choice.providerId)?.name ?: choice.providerId
        val modelName = choice.modelId?.let { id ->
            catalog.modelsByProvider[choice.providerId]?.firstOrNull { it.id == id }?.name ?: id
        }
        return ModelChipState(
            providerId = choice.providerId,
            providerName = providerName,
            modelId = choice.modelId,
            modelName = modelName,
            enabled = !busy,
            supportsImages = caps.supportsImages,
            supportsReasoning = caps.supportsReasoning,
            contextWindow = caps.contextWindow,
        )
    }

    /**
     * The explicit effort: a session's `thinking:false` is None, its `reasoningEffort` the
     * level; Home uses the remembered level of the provider (SP S8.4).
     */
    fun explicitEffort(facts: SessionFacts?, providerId: String?, remembered: Map<String, ReasoningEffort>): ReasoningEffort? =
        explicitEffort(facts?.thinking, facts?.reasoningEffort, providerId, remembered)

    /** [explicitEffort] from the session's `thinking` / `reasoningEffort` (both null: the remembered level). */
    fun explicitEffort(thinking: Boolean?, reasoningEffort: String?, providerId: String?, remembered: Map<String, ReasoningEffort>): ReasoningEffort? {
        if (thinking != null || reasoningEffort != null) {
            if (thinking == false) return ReasoningEffort.None
            return ReasoningEffort.parse(reasoningEffort)
        }
        return providerId?.let(remembered::get)
    }

    /** C1.7 ReasoningChip: disabled unless `supportsReasoning == true`. */
    fun reasoningChip(explicit: ReasoningEffort?, supportsReasoning: Boolean?): ReasoningChipState = ReasoningChipState(
        label = reasoningChipLabel(explicit, supportsReasoning),
        enabled = supportsReasoning == true,
        unsupportedReason = supportsReasoning != true,
    )

    /**
     * C1.11 usage: the latest request of the lead agent (the newest assistant row that
     * carries token counts) against the model's context window; cost is the session total.
     */
    fun usage(state: SessionState?, contextWindow: Long?, locale: Locale = Locale.getDefault()): ComposerUsage? {
        if (state == null) return null
        return usage(latestUsage(state), state.facts.usage?.totalCost, contextWindow, locale)
    }

    /** [usage] from the pre-extracted [latest] request and the session's [sessionTotalCost]. */
    fun usage(latest: LatestUsage?, sessionTotalCost: Double?, contextWindow: Long?, locale: Locale = Locale.getDefault()): ComposerUsage? {
        if (latest == null) return null
        val data = ContextUsageData(
            tokensIn = latest.tokensIn,
            tokensOut = latest.tokensOut,
            cacheReadTokens = latest.cacheReadTokens,
            contextWindow = contextWindow,
        )
        val usage = ContextUsageCalculator.compute(data, locale) ?: return null
        val cost = sessionTotalCost?.takeIf { it > 0 } ?: latest.rowCost?.takeIf { it > 0 }
        return ComposerUsage(usage, cost)
    }

    /** The newest assistant row that carries token counts, as [LatestUsage]. */
    fun latestUsage(state: SessionState): LatestUsage? {
        val row = lastMerged(state) { it.role == Role.Assistant && (it.meta.inputTokens != null || it.meta.outputTokens != null) } ?: return null
        return LatestUsage(
            tokensIn = (row.meta.inputTokens ?: 0).toLong(),
            tokensOut = (row.meta.outputTokens ?: 0).toLong(),
            cacheReadTokens = (row.meta.cacheReadTokens ?: 0).toLong(),
            rowCost = row.meta.totalCost,
        )
    }

    /** The mode of the last user message (`<user_input mode="…">`), for a session opened from history. */
    fun lastUserMode(state: SessionState?): PromptMode? {
        val raw = state?.let { s -> lastMerged(s) { it.role == Role.User } }?.content ?: return null
        return UserInputFormat.parseUserInputMode(raw)?.let(PromptMode::parse)
    }

    /**
     * `state.messages.lastOrNull(predicate)` without forcing the canonical ∪ live merge on every
     * Hub event (P4). [Reconciler.merge][dev.cline.termux.data.session.Reconciler.merge] keeps
     * canonical order, swaps in a live row that shares a canonical id in place, appends every other
     * live transcript row (user/assistant/tool) after all canonical rows, and slots live-only rows
     * (error, status, system) in by `createdAt`. So, when no live-only row matches: the last live
     * match is the answer if it is an appended transcript row; with no live match it is the last
     * canonical match that no live row replaces. Anything else takes the exact merged path.
     */
    internal fun lastMerged(state: SessionState, predicate: (ChatMessage) -> Boolean): ChatMessage? {
        val live = state.live
        val canonical = state.canonical
        if (live.isEmpty()) return canonical.lastOrNull(predicate)
        if (live.any { it.role !in TRANSCRIPT_ROLES && predicate(it) }) return state.messages.lastOrNull(predicate)
        val hit = live.lastOrNull(predicate)
        if (hit == null) {
            val liveIds = live.mapTo(HashSet(live.size)) { it.id }
            return canonical.lastOrNull { it.id !in liveIds && predicate(it) }
        }
        if (canonical.none { it.id == hit.id }) return hit
        return state.messages.lastOrNull(predicate)
    }

    /** The composer's slice of one session state, extracted off the main thread (P4). */
    fun inputs(state: SessionState): ComposerSessionInputs {
        val facts = state.facts
        return ComposerSessionInputs(
            sessionId = state.sessionId,
            seed = state.seed,
            empty = state.canonical.isEmpty() && state.live.isEmpty(),
            runPhase = facts.runPhase,
            providerId = facts.providerId,
            modelId = facts.modelId,
            thinking = facts.thinking,
            reasoningEffort = facts.reasoningEffort,
            pendingPrompts = facts.pendingPrompts,
            latestUsage = latestUsage(state),
            sessionTotalCost = facts.usage?.totalCost,
            lastUserMode = if (state.seed is SeedState.Seeded) lastUserMode(state) else null,
            creating = state.creating,
            activeRun = facts.activeRun,
        )
    }

    private val TRANSCRIPT_ROLES = setOf(Role.User, Role.Assistant, Role.Tool)

    /** C1.6 first run: false only once the catalog (or the fallback presence) has been read. */
    fun providerConfigured(catalog: CatalogState): Boolean? {
        if (!catalog.initialized) return null
        if (catalog.anyConnected) return true
        if (catalog.source == CatalogSource.Fallback && catalog.presence == null) return null
        return false
    }
}

fun PromptMode.toComposerMode(): ComposerMode = if (this == PromptMode.Plan) ComposerMode.Plan else ComposerMode.Act

fun ComposerMode.toPromptMode(): PromptMode = if (this == ComposerMode.Plan) PromptMode.Plan else PromptMode.Act
