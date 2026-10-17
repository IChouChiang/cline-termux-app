package dev.cline.termux.data.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.cline.termux.core.kernel.hub.HubCursorStore
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.ThemeMode
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Non-secret app state (docs/07 §7): the `app` DataStore (§7.2) and the `sessions`
 * DataStore (§7.3). The `kernel` file stays HubTokenStore's. Every value here is a
 * cursor, a preference, a cache the Hub can rebuild or an id needed to route
 * notifications; nothing is required for correctness after a reinstall (§0 rule 1) and
 * nothing is a secret (§3.7).
 *
 * Legacy keys are migrated once, by DataStore migrations attached to the files:
 * `onboarding.done` → `onboarding.completedAt`, `kernel.keepRunning` → `kernel.keepAwake`,
 * `model.provider`/`model.id` → `model.selection`, `workspace.last` → `workspace.active` +
 * `workspace.recents`, and the per-session `hub.cursor.*` → one `sessions.globalCursor`
 * (D2), after which the legacy keys are deleted.
 */

/** `model.selection` (SP S8.3; mirrors desktop `cline.code.model-selection.v1`). */
@Serializable
data class ModelSelection(
    val lastProvider: String? = null,
    val lastModelByProvider: Map<String, String> = emptyMap(),
) {
    fun modelFor(providerId: String?): String? = providerId?.let(lastModelByProvider::get)
    fun withPick(providerId: String, modelId: String): ModelSelection =
        copy(lastProvider = providerId, lastModelByProvider = lastModelByProvider + (providerId to modelId))
}

/** `workspace.active` (SP S9): a folder in Termux, or none ([Chat]). */
@Serializable
sealed interface WorkspaceChoice {
    @Serializable
    data class Folder(val path: String) : WorkspaceChoice

    /**
     * No folder chosen ("Just chat", the default): a first send starts in the Termux home (decided
     * A22) and Home's chip reads "Home". The name stays: it is the stored JSON's type tag.
     */
    @Serializable
    data object Chat : WorkspaceChoice

    val pathOrNull: String? get() = (this as? Folder)?.path
}

/** `signin.pending` (SP S7.3). */
@Serializable
data class SignInPending(val providerId: String, val startedAtMs: Long)

/** `composer.mode.*` values (CA C1). */
enum class ComposerMode(val storageKey: String) {
    Act("act"),
    Plan("plan"),
    ;

    companion object {
        fun fromStorage(value: String?): ComposerMode = entries.firstOrNull { it.storageKey == value } ?: Act
    }
}

/** `sessions.sidebarSort` (SS §1.11). */
enum class SidebarSortMode(val storageKey: String) {
    Time("time"),
    Project("project"),
    ;

    companion object {
        fun fromStorage(value: String?): SidebarSortMode = entries.firstOrNull { it.storageKey == value } ?: Time
    }
}

/** `transcript.messageDetails` (A11.9): the terminal's Brief / Verbose setting applied to the transcript. */
enum class MessageDetails(val storageKey: String) {
    /** Copy, fork and time under each answer, as the desktop: no per-turn stats (the default). */
    Brief("brief"),

    /**
     * Also each turn's tokens, cost and duration on its final answer. The terminal's verbose
     * mode also shows recoverable mid-run errors; no Hub sends those yet (see
     * `ChatProjector.forDetails`), so this setting promises only the stats.
     */
    Verbose("verbose"),
    ;

    companion object {
        fun fromStorage(value: String?): MessageDetails = entries.firstOrNull { it.storageKey == value } ?: Brief
    }
}

class AppPreferences(
    private val app: DataStore<Preferences>,
    private val sessions: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The production wiring: both files under `filesDir/datastore`, migrations attached. */
    constructor(context: Context, scope: CoroutineScope) : this(
        app = Stores.app(context),
        sessions = Stores.sessions(context),
        scope = scope,
    )

    val data: Flow<Preferences> get() = app.data
    val sessionsData: Flow<Preferences> get() = sessions.data

    // ---------------------------------------------------------------- identity

    /** Stable per-install Hub client id (`android-<uuid>`), minted once and never rotated. */
    suspend fun clientId(): String {
        app.data.first()[Keys.CLIENT_ID]?.let { return it }
        var minted: String? = null
        app.edit { p ->
            minted = p[Keys.CLIENT_ID] ?: "android-${UUID.randomUUID()}".also { p[Keys.CLIENT_ID] = it }
        }
        return minted ?: app.data.first()[Keys.CLIENT_ID]!!
    }

    /** Kept for the current `AppGraph`; the blocking reads move behind the splash (docs/07 §8). */
    fun clientIdBlocking(): String = runBlocking { clientId() }

    // -------------------------------------------------------------- onboarding

    val onboardingCompletedAt: Flow<Long?> get() = app.data.map { it[Keys.ONBOARDING_COMPLETED_AT] }.distinctUntilChanged()
    suspend fun onboardingCompletedAt(): Long? = app.data.first()[Keys.ONBOARDING_COMPLETED_AT]
    suspend fun setOnboardingCompleted(atMs: Long? = clock.millis()) = app.edit { it.setOrRemove(Keys.ONBOARDING_COMPLETED_AT, atMs) }

    suspend fun onboardingSignInStartedAt(): Long? = app.data.first()[Keys.ONBOARDING_SIGNIN_STARTED_AT]
    suspend fun setOnboardingSignInStartedAt(atMs: Long?) = app.edit { it.setOrRemove(Keys.ONBOARDING_SIGNIN_STARTED_AT, atMs) }

    suspend fun onboardingReliabilityShownAt(): Long? = app.data.first()[Keys.ONBOARDING_RELIABILITY_SHOWN_AT]
    suspend fun setOnboardingReliabilityShownAt(atMs: Long? = clock.millis()) =
        app.edit { it.setOrRemove(Keys.ONBOARDING_RELIABILITY_SHOWN_AT, atMs) }

    // ------------------------------------------------------------------ kernel

    val keepAwake: Flow<Boolean> get() = app.data.map { it[Keys.KERNEL_KEEP_AWAKE] ?: true }.distinctUntilChanged()
    suspend fun keepAwake(): Boolean = app.data.first()[Keys.KERNEL_KEEP_AWAKE] ?: true
    suspend fun setKeepAwake(enabled: Boolean) = app.edit { it[Keys.KERNEL_KEEP_AWAKE] = enabled }

    val userStopped: Flow<Boolean> get() = app.data.map { it[Keys.KERNEL_USER_STOPPED] ?: false }.distinctUntilChanged()
    suspend fun userStopped(): Boolean = app.data.first()[Keys.KERNEL_USER_STOPPED] ?: false
    suspend fun setUserStopped(stopped: Boolean) = app.edit { it[Keys.KERNEL_USER_STOPPED] = stopped }

    suspend fun wakeLockRequested(): Boolean = app.data.first()[Keys.KERNEL_WAKE_LOCK_REQUESTED] ?: false
    suspend fun setWakeLockRequested(requested: Boolean) = app.edit { it[Keys.KERNEL_WAKE_LOCK_REQUESTED] = requested }

    suspend fun hubLifetimeRelease(): String? = app.data.first()[Keys.KERNEL_HUB_LIFETIME_RELEASE]
    suspend fun setHubLifetimeRelease(release: String?) = app.edit { it.setOrRemove(Keys.KERNEL_HUB_LIFETIME_RELEASE, release) }

    // ------------------------------------------------------------- reliability

    /** KS D self-checks the app cannot verify itself. */
    enum class ReliabilityCheck(internal val key: Preferences.Key<Boolean>) {
        Battery(Keys.RELIABILITY_BATTERY),
        Sleeping(Keys.RELIABILITY_SLEEPING),
        Phantom(Keys.RELIABILITY_PHANTOM),
    }

    val reliabilityConfirmed: Flow<Set<ReliabilityCheck>>
        get() = app.data.map { p -> ReliabilityCheck.entries.filterTo(mutableSetOf()) { p[it.key] == true } }.distinctUntilChanged()

    suspend fun isReliabilityConfirmed(check: ReliabilityCheck): Boolean = app.data.first()[check.key] ?: false
    suspend fun setReliabilityConfirmed(check: ReliabilityCheck, confirmed: Boolean) = app.edit { it[check.key] = confirmed }

    // -------------------------------------------------------------- appearance

    /** Settings > Appearance (design-system.md §1.2, §2.4): theme, dynamic colour, font size. */
    val appearance: Flow<AppearancePrefs> get() = app.data.map { it.appearance() }.distinctUntilChanged()
    suspend fun setThemeMode(mode: ThemeMode) = app.edit { it[Keys.THEME_MODE] = mode.storageKey }
    suspend fun setDynamicColor(enabled: Boolean) = app.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setFontSize(size: Int) = app.edit { it[Keys.FONT_SIZE] = FontSizeSetting.clamp(size) }

    /** Which launcher alias is enabled (DS §10.6); the component switch itself is the caller's. */
    val appIcon: Flow<AppIcon> get() = app.data.map { AppIcon.fromStorage(it[Keys.APP_ICON]) }.distinctUntilChanged()
    suspend fun appIcon(): AppIcon = AppIcon.fromStorage(app.data.first()[Keys.APP_ICON])
    suspend fun setAppIcon(icon: AppIcon) = app.edit { it[Keys.APP_ICON] = icon.storageKey }

    /** The alias to disable once the new one is live (SP S2.2: finished by `ClineApp` on the next start). */
    suspend fun pendingIconDisable(): String? = app.data.first()[Keys.APPEARANCE_PENDING_ICON_DISABLE]
    suspend fun setPendingIconDisable(component: String?) = app.edit { it.setOrRemove(Keys.APPEARANCE_PENDING_ICON_DISABLE, component) }

    private fun Preferences.appearance() = AppearancePrefs(
        themeMode = ThemeMode.fromStorage(this[Keys.THEME_MODE]),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        fontSize = this[Keys.FONT_SIZE]?.let(FontSizeSetting::clamp) ?: FontSizeSetting.DEFAULT,
    )

    // ------------------------------------------------------------------- model

    val modelSelection: Flow<ModelSelection>
        get() = app.data.map { it.json<ModelSelection>(Keys.MODEL_SELECTION) ?: ModelSelection() }.distinctUntilChanged()
    suspend fun modelSelection(): ModelSelection = app.data.first().json<ModelSelection>(Keys.MODEL_SELECTION) ?: ModelSelection()

    /** Written only on an explicit pick (docs/07 §4.5). */
    suspend fun recordModelPick(providerId: String, modelId: String) = app.edit { p ->
        p[Keys.MODEL_SELECTION] = enc((p.json<ModelSelection>(Keys.MODEL_SELECTION) ?: ModelSelection()).withPick(providerId, modelId))
    }

    suspend fun setModelSelection(selection: ModelSelection) = app.edit { it[Keys.MODEL_SELECTION] = enc(selection) }

    /** `model.reasoning`: providerId → effort level. */
    val reasoningByProvider: Flow<Map<String, String>>
        get() = app.data.map { it.json<Map<String, String>>(Keys.MODEL_REASONING) ?: emptyMap() }.distinctUntilChanged()
    suspend fun reasoningByProvider(): Map<String, String> = app.data.first().json<Map<String, String>>(Keys.MODEL_REASONING) ?: emptyMap()
    suspend fun setReasoning(providerId: String, level: String?) = app.edit { p ->
        val current = p.json<Map<String, String>>(Keys.MODEL_REASONING) ?: emptyMap()
        p[Keys.MODEL_REASONING] = enc(if (level == null) current - providerId else current + (providerId to level))
    }

    // ------------------------------------------------------------------- tools
    // Auto-approve is the kernel's `toolAutoApprove` (ApprovalModeRepository, decided A1); the app
    // keeps no private copy, and the former `tools.autoApprove(.<id>)` keys are no longer read.

    // ---------------------------------------------------------------- composer

    /** `composer.mode.<sessionId>` falls back to `composer.mode.default`. */
    fun composerMode(sessionId: String? = null): Flow<ComposerMode> =
        app.data.map { it.composerMode(sessionId) }.distinctUntilChanged()

    suspend fun composerModeNow(sessionId: String? = null): ComposerMode = app.data.first().composerMode(sessionId)
    suspend fun setComposerMode(mode: ComposerMode, sessionId: String? = null) =
        app.edit { it[Keys.composerMode(sessionId)] = mode.storageKey }

    private fun Preferences.composerMode(sessionId: String?): ComposerMode =
        ComposerMode.fromStorage(sessionId?.let { this[Keys.composerMode(it)] } ?: this[Keys.composerMode(null)])

    /** `composer.draft.<key>`; the caller debounces (500 ms) and never stores secret text. */
    suspend fun composerDraft(key: String): String? = app.data.first()[Keys.composerDraft(key)]
    suspend fun setComposerDraft(key: String, text: String?) =
        app.edit { it.setOrRemove(Keys.composerDraft(key), text?.takeIf { t -> t.isNotEmpty() }) }

    // -------------------------------------------------------------------- chat

    /** `chat.turns`: the open `TurnRecord` list as JSON; the record type is owned by `data/session`. */
    val chatTurnsJson: Flow<String> get() = app.data.map { it[Keys.CHAT_TURNS] ?: "[]" }.distinctUntilChanged()
    suspend fun chatTurnsJson(): String = app.data.first()[Keys.CHAT_TURNS] ?: "[]"
    suspend fun setChatTurnsJson(json: String) = app.edit { it[Keys.CHAT_TURNS] = json }

    // --------------------------------------------------------------- workspace

    val workspace: Flow<WorkspaceChoice>
        get() = app.data.map { it.json<WorkspaceChoice>(Keys.WORKSPACE_ACTIVE) ?: WorkspaceChoice.Chat }.distinctUntilChanged()
    suspend fun workspace(): WorkspaceChoice = app.data.first().json<WorkspaceChoice>(Keys.WORKSPACE_ACTIVE) ?: WorkspaceChoice.Chat

    /** Sets the active choice and, for a folder, bumps it to the head of the recents (≤ [MAX_RECENTS]). */
    suspend fun setWorkspace(choice: WorkspaceChoice) = app.edit { p ->
        p[Keys.WORKSPACE_ACTIVE] = enc(choice)
        if (choice is WorkspaceChoice.Folder) {
            val recents = p.json<List<String>>(Keys.WORKSPACE_RECENTS) ?: emptyList()
            p[Keys.WORKSPACE_RECENTS] = enc(listOf(choice.path) + (recents - choice.path).take(MAX_RECENTS - 1))
        }
    }

    val workspaceRecents: Flow<List<String>>
        get() = app.data.map { it.json<List<String>>(Keys.WORKSPACE_RECENTS) ?: emptyList() }.distinctUntilChanged()
    suspend fun workspaceRecents(): List<String> = app.data.first().json<List<String>>(Keys.WORKSPACE_RECENTS) ?: emptyList()
    suspend fun removeWorkspaceRecent(path: String) = app.edit { p ->
        p[Keys.WORKSPACE_RECENTS] = enc((p.json<List<String>>(Keys.WORKSPACE_RECENTS) ?: emptyList()) - path)
    }

    /** The last real workspace or null; `AppGraph` passes it as the registration root. */
    suspend fun lastWorkspace(): String? = workspace().pathOrNull

    suspend fun customizeWorkspaceRoot(): String? = app.data.first()[Keys.CUSTOMIZE_WORKSPACE_ROOT]
    suspend fun setCustomizeWorkspaceRoot(path: String?) = app.edit { it.setOrRemove(Keys.CUSTOMIZE_WORKSPACE_ROOT, path) }

    // --------------------------------------------------------------- providers

    val favoriteModels: Flow<Map<String, List<String>>>
        get() = app.data.map { it.json<Map<String, List<String>>>(Keys.PROVIDERS_FAVORITES) ?: emptyMap() }.distinctUntilChanged()
    suspend fun favoriteModels(): Map<String, List<String>> = app.data.first().json<Map<String, List<String>>>(Keys.PROVIDERS_FAVORITES) ?: emptyMap()
    suspend fun setFavoriteModel(providerId: String, modelId: String, favorite: Boolean) = app.edit { p ->
        val all = p.json<Map<String, List<String>>>(Keys.PROVIDERS_FAVORITES) ?: emptyMap()
        val list = all[providerId] ?: emptyList()
        val next = if (favorite) (list - modelId) + modelId else list - modelId
        p[Keys.PROVIDERS_FAVORITES] = enc(if (next.isEmpty()) all - providerId else all + (providerId to next))
    }

    val helperUnsupported: Flow<Boolean> get() = app.data.map { it[Keys.HELPER_UNSUPPORTED] ?: false }.distinctUntilChanged()
    suspend fun helperUnsupported(): Boolean = app.data.first()[Keys.HELPER_UNSUPPORTED] ?: false
    suspend fun setHelperUnsupported(unsupported: Boolean) = app.edit { it[Keys.HELPER_UNSUPPORTED] = unsupported }

    val signInPending: Flow<SignInPending?> get() = app.data.map { it.json<SignInPending>(Keys.SIGNIN_PENDING) }.distinctUntilChanged()
    suspend fun signInPending(): SignInPending? = app.data.first().json<SignInPending>(Keys.SIGNIN_PENDING)
    suspend fun setSignInPending(pending: SignInPending?) =
        app.edit { it.setOrRemove(Keys.SIGNIN_PENDING, pending?.let { enc(it) }) }

    // ----------------------------------------------------------------- account

    /** Display name and scope as last seen; cleared on sign-out. */
    val account: Flow<Pair<String?, String?>>
        get() = app.data.map { it[Keys.ACCOUNT_DISPLAY_NAME] to it[Keys.ACCOUNT_SCOPE] }.distinctUntilChanged()
    suspend fun setAccount(displayName: String?, scope: String?) = app.edit {
        it.setOrRemove(Keys.ACCOUNT_DISPLAY_NAME, displayName)
        it.setOrRemove(Keys.ACCOUNT_SCOPE, scope)
    }

    // ----------------------------------------------------------- notifications

    /** `notifications.v1` JSON; the shape (and the "all enabled, sound off" default) is `NotificationPrefs`'. */
    val notificationsJson: Flow<String?> get() = app.data.map { it[Keys.NOTIFICATIONS_V1] }.distinctUntilChanged()
    suspend fun notificationsJson(): String? = app.data.first()[Keys.NOTIFICATIONS_V1]
    suspend fun setNotificationsJson(json: String) = app.edit { it[Keys.NOTIFICATIONS_V1] = json }

    val keepConnected: Flow<Boolean> get() = app.data.map { it[Keys.NOTIFICATIONS_KEEP_CONNECTED] ?: false }.distinctUntilChanged()
    suspend fun keepConnected(): Boolean = app.data.first()[Keys.NOTIFICATIONS_KEEP_CONNECTED] ?: false
    suspend fun setKeepConnected(enabled: Boolean) = app.edit { it[Keys.NOTIFICATIONS_KEEP_CONNECTED] = enabled }

    /** Dedupe memory for notifications (≤ [MAX_SEEN_REQUEST_IDS], oldest dropped). */
    suspend fun seenRequestIds(): List<String> = app.data.first().json<List<String>>(Keys.NOTIFICATIONS_SEEN_REQUEST_IDS) ?: emptyList()
    suspend fun addSeenRequestId(requestId: String) = app.edit { p ->
        p[Keys.NOTIFICATIONS_SEEN_REQUEST_IDS] =
            enc(lruAdd(p.json<List<String>>(Keys.NOTIFICATIONS_SEEN_REQUEST_IDS) ?: emptyList(), requestId, MAX_SEEN_REQUEST_IDS))
    }

    /** Sessions this app created, opened or was notified about (≤ [MAX_KNOWN_SESSIONS], LRU). */
    val knownSessionIds: Flow<List<String>>
        get() = app.data.map { it.json<List<String>>(Keys.NOTIFICATIONS_KNOWN_SESSION_IDS) ?: emptyList() }.distinctUntilChanged()
    suspend fun knownSessionIds(): List<String> = app.data.first().json<List<String>>(Keys.NOTIFICATIONS_KNOWN_SESSION_IDS) ?: emptyList()
    suspend fun addKnownSession(sessionId: String) = app.edit { p ->
        p[Keys.NOTIFICATIONS_KNOWN_SESSION_IDS] =
            enc(lruAdd(p.json<List<String>>(Keys.NOTIFICATIONS_KNOWN_SESSION_IDS) ?: emptyList(), sessionId, MAX_KNOWN_SESSIONS))
    }
    suspend fun removeKnownSession(sessionId: String) = app.edit { p ->
        p[Keys.NOTIFICATIONS_KNOWN_SESSION_IDS] = enc((p.json<List<String>>(Keys.NOTIFICATIONS_KNOWN_SESSION_IDS) ?: emptyList()) - sessionId)
    }

    // ------------------------------------------------------------- misc screens

    val toolDetailWrapLines: Flow<Boolean> get() = app.data.map { it[Keys.TOOL_DETAIL_WRAP_LINES] ?: true }.distinctUntilChanged()
    suspend fun setToolDetailWrapLines(wrap: Boolean) = app.edit { it[Keys.TOOL_DETAIL_WRAP_LINES] = wrap }

    val whatsNewSeenId: Flow<String?> get() = app.data.map { it[Keys.WHATS_NEW_SEEN_ID] }.distinctUntilChanged()
    suspend fun whatsNewSeenId(): String? = app.data.first()[Keys.WHATS_NEW_SEEN_ID]
    suspend fun setWhatsNewSeenId(id: String?) = app.edit { it.setOrRemove(Keys.WHATS_NEW_SEEN_ID, id) }

    suspend fun schedulesKeepAliveNoticeDismissedFor(): String? = app.data.first()[Keys.SCHEDULES_KEEP_ALIVE_DISMISSED_FOR]
    suspend fun setSchedulesKeepAliveNoticeDismissedFor(version: String?) =
        app.edit { it.setOrRemove(Keys.SCHEDULES_KEEP_ALIVE_DISMISSED_FOR, version) }

    // ------------------------------------------------------------------- home

    /** G3: Home's "Worktree" chip (desktop `cline.code.work-in.v1` = "worktree"); off by default. */
    val workInWorktree: Flow<Boolean> get() = app.data.map { it[Keys.HOME_WORK_IN_WORKTREE] ?: false }.distinctUntilChanged()
    suspend fun setWorkInWorktree(enabled: Boolean) = app.edit { it[Keys.HOME_WORK_IN_WORKTREE] = enabled }

    // -------------------------------------------------------------- transcript

    /** `transcript.messageDetails` (A11.9): Brief unless the user chose Verbose in Settings. */
    val messageDetails: Flow<MessageDetails>
        get() = app.data.map { MessageDetails.fromStorage(it[Keys.TRANSCRIPT_MESSAGE_DETAILS]) }.distinctUntilChanged()
    suspend fun messageDetails(): MessageDetails = MessageDetails.fromStorage(app.data.first()[Keys.TRANSCRIPT_MESSAGE_DETAILS])
    suspend fun setMessageDetails(mode: MessageDetails) = app.edit { it[Keys.TRANSCRIPT_MESSAGE_DETAILS] = mode.storageKey }

    // ------------------------------------------------- sessions DataStore (§7.3)

    /** The one replay cursor (D2). */
    suspend fun globalCursor(): Long? = sessions.data.first()[Keys.SESSIONS_GLOBAL_CURSOR]

    /** Monotonic: a lower value is ignored. */
    suspend fun setGlobalCursor(sequence: Long) = sessions.edit { p ->
        val current = p[Keys.SESSIONS_GLOBAL_CURSOR]
        if (current == null || current < sequence) p[Keys.SESSIONS_GLOBAL_CURSOR] = sequence
    }

    suspend fun lastDisconnectAt(): Long? = sessions.data.first()[Keys.SESSIONS_LAST_DISCONNECT_AT]
    suspend fun setLastDisconnectAt(atMs: Long? = clock.millis()) = sessions.edit { it.setOrRemove(Keys.SESSIONS_LAST_DISCONNECT_AT, atMs) }

    val unreadCounts: Flow<Map<String, Int>>
        get() = sessions.data.map { it.json<Map<String, Int>>(Keys.SESSIONS_UNREAD) ?: emptyMap() }.distinctUntilChanged()
    suspend fun unreadCounts(): Map<String, Int> = sessions.data.first().json<Map<String, Int>>(Keys.SESSIONS_UNREAD) ?: emptyMap()
    suspend fun setUnreadCounts(counts: Map<String, Int>) = sessions.edit { it[Keys.SESSIONS_UNREAD] = enc(counts.filterValues { c -> c > 0 }) }

    val lastSeenSequences: Flow<Map<String, Long>>
        get() = sessions.data.map { it.json<Map<String, Long>>(Keys.SESSIONS_LAST_SEEN) ?: emptyMap() }.distinctUntilChanged()
    suspend fun lastSeenSequences(): Map<String, Long> = sessions.data.first().json<Map<String, Long>>(Keys.SESSIONS_LAST_SEEN) ?: emptyMap()
    suspend fun setLastSeen(sessionId: String, sequence: Long) = sessions.edit { p ->
        val current = p.json<Map<String, Long>>(Keys.SESSIONS_LAST_SEEN) ?: emptyMap()
        if ((current[sessionId] ?: -1L) < sequence) p[Keys.SESSIONS_LAST_SEEN] = enc(current + (sessionId to sequence))
    }
    suspend fun forgetSession(sessionId: String) = sessions.edit { p ->
        p[Keys.SESSIONS_LAST_SEEN] = enc((p.json<Map<String, Long>>(Keys.SESSIONS_LAST_SEEN) ?: emptyMap()) - sessionId)
        p[Keys.SESSIONS_UNREAD] = enc((p.json<Map<String, Int>>(Keys.SESSIONS_UNREAD) ?: emptyMap()) - sessionId)
        p[Keys.SESSIONS_SUSPECT_INTERRUPTED] = enc((p.json<Set<String>>(Keys.SESSIONS_SUSPECT_INTERRUPTED) ?: emptySet()) - sessionId)
    }

    val suspectInterrupted: Flow<Set<String>>
        get() = sessions.data.map { it.json<Set<String>>(Keys.SESSIONS_SUSPECT_INTERRUPTED) ?: emptySet() }.distinctUntilChanged()
    suspend fun suspectInterrupted(): Set<String> = sessions.data.first().json<Set<String>>(Keys.SESSIONS_SUSPECT_INTERRUPTED) ?: emptySet()
    suspend fun setSuspectInterrupted(ids: Set<String>) = sessions.edit { it[Keys.SESSIONS_SUSPECT_INTERRUPTED] = enc(ids) }

    /** `sessions.stopNotices` (A17): the mistake-limit stop notices as JSON; the record type is owned by `data/session`. */
    suspend fun stopNoticesJson(): String = sessions.data.first()[Keys.SESSIONS_STOP_NOTICES] ?: "[]"
    suspend fun setStopNoticesJson(json: String) = sessions.edit { it[Keys.SESSIONS_STOP_NOTICES] = json }

    val sidebarSort: Flow<SidebarSortMode>
        get() = sessions.data.map { SidebarSortMode.fromStorage(it[Keys.SESSIONS_SIDEBAR_SORT]) }.distinctUntilChanged()
    suspend fun setSidebarSort(mode: SidebarSortMode) = sessions.edit { it[Keys.SESSIONS_SIDEBAR_SORT] = mode.storageKey }

    // ------------------------------------------------------------- cursor store

    /**
     * The `HubCursorStore` the `HubClient` consumes, backed by `sessions.globalCursor` (one
     * cursor per D2). Writes go straight through [setGlobalCursor] (monotonic); the client
     * coalesces them to one per second and flushes on `ON_STOP` / disconnect (docs/07 §3.1).
     */
    fun cursorStore(): HubCursorStore = cursorAdapter

    private val cursorAdapter: CursorAdapter by lazy { CursorAdapter() }

    private inner class CursorAdapter : HubCursorStore {
        private val writeLock = Mutex()
        override suspend fun get(): Long? = globalCursor()
        override suspend fun put(sequence: Long) {
            writeLock.withLock { setGlobalCursor(sequence) }
        }
    }

    companion object {
        const val MAX_RECENTS = 20
        const val MAX_SEEN_REQUEST_IDS = 100
        const val MAX_KNOWN_SESSIONS = 500
        const val CURSOR_WRITE_INTERVAL_MS = 1_000L

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Append-or-move-to-end LRU with a cap. */
        fun lruAdd(list: List<String>, item: String, cap: Int): List<String> = ((list - item) + item).takeLast(cap)
    }
}

// Json helpers, file level so `reified` works everywhere in this file.
private inline fun <reified T> enc(value: T): String = AppPreferences.json.encodeToString(value)

private inline fun <reified T> Preferences.json(key: Preferences.Key<String>): T? =
    this[key]?.let { raw -> runCatching { AppPreferences.json.decodeFromString<T>(raw) }.getOrNull() }

private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

/** Key catalog (docs/07 §7.2 and §7.3), plus the legacy names the migrations retire. */
internal object Keys {
    val CLIENT_ID = stringPreferencesKey("client.id")
    val ONBOARDING_COMPLETED_AT = longPreferencesKey("onboarding.completedAt")
    val ONBOARDING_SIGNIN_STARTED_AT = longPreferencesKey("onboarding.signIn.startedAt")
    val ONBOARDING_RELIABILITY_SHOWN_AT = longPreferencesKey("onboarding.reliabilityShownAt")
    val KERNEL_KEEP_AWAKE = booleanPreferencesKey("kernel.keepAwake")
    val KERNEL_USER_STOPPED = booleanPreferencesKey("kernel.userStopped")
    val KERNEL_WAKE_LOCK_REQUESTED = booleanPreferencesKey("kernel.wakeLockRequested")
    val KERNEL_HUB_LIFETIME_RELEASE = stringPreferencesKey("kernel.hubLifetimeRelease")
    val RELIABILITY_BATTERY = booleanPreferencesKey("reliability.batteryConfirmed")
    val RELIABILITY_SLEEPING = booleanPreferencesKey("reliability.sleepingConfirmed")
    val RELIABILITY_PHANTOM = booleanPreferencesKey("reliability.phantomConfirmed")
    val THEME_MODE = stringPreferencesKey("appearance.theme")
    val DYNAMIC_COLOR = booleanPreferencesKey("appearance.dynamicColor")
    val FONT_SIZE = intPreferencesKey("appearance.fontSize")
    val APP_ICON = stringPreferencesKey("appearance.appIcon")
    val APPEARANCE_PENDING_ICON_DISABLE = stringPreferencesKey("appearance.pendingIconDisable")
    val MODEL_SELECTION = stringPreferencesKey("model.selection")
    val MODEL_REASONING = stringPreferencesKey("model.reasoning")
    val CHAT_TURNS = stringPreferencesKey("chat.turns")
    val WORKSPACE_ACTIVE = stringPreferencesKey("workspace.active")
    val WORKSPACE_RECENTS = stringPreferencesKey("workspace.recents")
    val CUSTOMIZE_WORKSPACE_ROOT = stringPreferencesKey("customize.workspaceRoot")
    val PROVIDERS_FAVORITES = stringPreferencesKey("providers.favoriteModels")
    val HELPER_UNSUPPORTED = booleanPreferencesKey("helper.unsupported")
    val SIGNIN_PENDING = stringPreferencesKey("signin.pending")
    val ACCOUNT_DISPLAY_NAME = stringPreferencesKey("account.displayName")
    val ACCOUNT_SCOPE = stringPreferencesKey("account.scope")
    val NOTIFICATIONS_V1 = stringPreferencesKey("notifications.v1")
    val NOTIFICATIONS_KEEP_CONNECTED = booleanPreferencesKey("notifications.keepConnected")
    val NOTIFICATIONS_SEEN_REQUEST_IDS = stringPreferencesKey("notifications.seenRequestIds")
    val NOTIFICATIONS_KNOWN_SESSION_IDS = stringPreferencesKey("notifications.knownSessionIds")
    val TOOL_DETAIL_WRAP_LINES = booleanPreferencesKey("toolDetail.wrapLines")
    val WHATS_NEW_SEEN_ID = stringPreferencesKey("whatsNew.seenId")
    val SCHEDULES_KEEP_ALIVE_DISMISSED_FOR = stringPreferencesKey("schedules.keepAliveNoticeDismissedFor")
    val HOME_WORK_IN_WORKTREE = booleanPreferencesKey("home.workInWorktree")
    val TRANSCRIPT_MESSAGE_DETAILS = stringPreferencesKey("transcript.messageDetails")

    fun composerMode(sessionId: String?) = stringPreferencesKey(if (sessionId == null) "composer.mode.default" else "composer.mode.$sessionId")
    fun composerDraft(key: String) = stringPreferencesKey("composer.draft.$key")

    // sessions file
    val SESSIONS_GLOBAL_CURSOR = longPreferencesKey("sessions.globalCursor")
    val SESSIONS_LAST_DISCONNECT_AT = longPreferencesKey("sessions.lastDisconnectAt")
    val SESSIONS_UNREAD = stringPreferencesKey("sessions.unread")
    val SESSIONS_LAST_SEEN = stringPreferencesKey("sessions.lastSeen")
    val SESSIONS_SUSPECT_INTERRUPTED = stringPreferencesKey("sessions.suspectInterrupted")
    val SESSIONS_SIDEBAR_SORT = stringPreferencesKey("sessions.sidebarSort")
    val SESSIONS_STOP_NOTICES = stringPreferencesKey("sessions.stopNotices")

    // legacy (pre docs/07) names, migrated once then removed
    val LEGACY_ONBOARDING_DONE = booleanPreferencesKey("onboarding.done")
    val LEGACY_KEEP_RUNNING = booleanPreferencesKey("kernel.keepRunning")
    val LEGACY_MODEL_PROVIDER = stringPreferencesKey("model.provider")
    val LEGACY_MODEL_ID = stringPreferencesKey("model.id")
    val LEGACY_WORKSPACE_LAST = stringPreferencesKey("workspace.last")
    const val LEGACY_CURSOR_PREFIX = "hub.cursor."
}

/** `onboarding.done`, `kernel.keepRunning`, `model.*`, `workspace.last` → their §7.2 successors. */
internal class LegacyAppMigration(private val clock: Clock = Clock.systemUTC()) : DataMigration<Preferences> {
    private val legacy = listOf(
        Keys.LEGACY_ONBOARDING_DONE, Keys.LEGACY_KEEP_RUNNING, Keys.LEGACY_MODEL_PROVIDER,
        Keys.LEGACY_MODEL_ID, Keys.LEGACY_WORKSPACE_LAST,
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacy.any { currentData.contains(it) }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val p = currentData.toMutablePreferences()
        if (p[Keys.LEGACY_ONBOARDING_DONE] == true && p[Keys.ONBOARDING_COMPLETED_AT] == null) {
            p[Keys.ONBOARDING_COMPLETED_AT] = clock.millis()
        }
        p[Keys.LEGACY_KEEP_RUNNING]?.let { if (p[Keys.KERNEL_KEEP_AWAKE] == null) p[Keys.KERNEL_KEEP_AWAKE] = it }
        val provider = p[Keys.LEGACY_MODEL_PROVIDER]
        val model = p[Keys.LEGACY_MODEL_ID]
        if (provider != null && p[Keys.MODEL_SELECTION] == null) {
            val selection = if (model != null) ModelSelection().withPick(provider, model) else ModelSelection(lastProvider = provider)
            p[Keys.MODEL_SELECTION] = AppPreferences.json.encodeToString(selection)
        }
        p[Keys.LEGACY_WORKSPACE_LAST]?.takeIf { it.isNotBlank() }?.let { path ->
            if (p[Keys.WORKSPACE_ACTIVE] == null) {
                p[Keys.WORKSPACE_ACTIVE] = AppPreferences.json.encodeToString<WorkspaceChoice>(WorkspaceChoice.Folder(path))
                val recents = p.json<List<String>>(Keys.WORKSPACE_RECENTS) ?: emptyList()
                p[Keys.WORKSPACE_RECENTS] = AppPreferences.json.encodeToString(listOf(path) + (recents - path).take(AppPreferences.MAX_RECENTS - 1))
            }
        }
        legacy.forEach { key -> p.remove(key) }
        return p.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * The per-session `hub.cursor.*` keys lived in the `app` file; the max of all of them seeds
 * `sessions.globalCursor`, then they are deleted (docs/07 §3.1, D2).
 */
internal class LegacyCursorMigration(private val appStore: DataStore<Preferences>) : DataMigration<Preferences> {
    private suspend fun legacyCursors(): Map<Preferences.Key<*>, Long> =
        appStore.data.first().asMap().filter { (key, value) -> key.name.startsWith(Keys.LEGACY_CURSOR_PREFIX) && value is Long }
            .mapValues { it.value as Long }

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyCursors().isNotEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val max = legacyCursors().values.maxOrNull() ?: return currentData
        val p = currentData.toMutablePreferences()
        val current = p[Keys.SESSIONS_GLOBAL_CURSOR]
        if (current == null || current < max) p[Keys.SESSIONS_GLOBAL_CURSOR] = max
        return p.toPreferences()
    }

    override suspend fun cleanUp() {
        appStore.edit { p -> legacyCursors().keys.forEach { key -> p.remove(key) } }
    }
}

/** One DataStore instance per file per process (DataStore refuses two on the same file). */
private object Stores {
    private val app = AtomicReference<DataStore<Preferences>?>()
    private val sessions = AtomicReference<DataStore<Preferences>?>()

    fun app(context: Context): DataStore<Preferences> = app.get() ?: synchronized(this) {
        app.get() ?: PreferenceDataStoreFactory.create(
            migrations = listOf(LegacyAppMigration()),
            produceFile = { context.applicationContext.preferencesDataStoreFile("app") },
        ).also(app::set)
    }

    fun sessions(context: Context): DataStore<Preferences> = sessions.get() ?: synchronized(this) {
        sessions.get() ?: PreferenceDataStoreFactory.create(
            migrations = listOf(LegacyCursorMigration(app(context))),
            produceFile = { context.applicationContext.preferencesDataStoreFile("sessions") },
        ).also(sessions::set)
    }
}
