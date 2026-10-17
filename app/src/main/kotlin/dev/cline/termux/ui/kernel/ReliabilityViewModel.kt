package dev.cline.termux.ui.kernel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** D.3: inside onboarding (OnboardingScaffold panel, footer actions) or the pushed standalone page. */
enum class ReliabilityVariant { Onboarding, Standalone }

/** D.7 `ReliabilityUiState`, plus the disclosure state of each item. */
@Immutable
data class ReliabilityUiState(
    val variant: ReliabilityVariant,
    val items: List<ReliabilityItemUi> = emptyList(),
    val doneCount: Int = 0,
    val countedTotal: Int = 0,
    /** Every counted item is done: the success line shows and every item is collapsed (D.6). */
    val allDone: Boolean = false,
    val expanded: Set<ReliabilityItemId> = emptySet(),
    /** The first read finished (D.6 "loading": the first frame computes everything synchronously). */
    val loaded: Boolean = false,
    /**
     * Onboarding only (D.4): null until decided; true when the step must be skipped (shown
     * before, or nothing verifiable is missing). Decided once per back-stack entry.
     */
    val skipOnboarding: Boolean? = null,
) {
    fun item(id: ReliabilityItemId): ReliabilityItemUi? = items.firstOrNull { it.id == id }
}

/** What the notification item's "Allow" does next (B5); the screen performs it. */
enum class NotificationStep {
    /** Nothing to do (already granted, or the user dismissed the dialog: the next tap asks again). */
    None,

    /** Launch `RequestPermission(POST_NOTIFICATIONS)`. */
    Request,

    /** Open the app's notification settings: the system will not ask any more. */
    OpenSettings,
}

/**
 * B5: the notification permission request of the Reliability step, aligned with Settings →
 * Notifications and Android's guidance: ask again on the next tap, open the app's notification
 * settings only after a permanent denial. The system cannot be asked whether a denial is
 * permanent (a never-asked permission and a permanently denied one look alike), so it is learned
 * from the results: a result that comes back without a dialog ([INSTANT_RESULT_MS]) and without a
 * rationale is permanent; so is a second explicit denial (Android stops asking after two). A
 * dismissed dialog (no rationale, no earlier denial) is neither.
 */
object NotificationRequestRules {
    /** A denial faster than this came without a dialog: the system had stopped asking. */
    const val INSTANT_RESULT_MS = 400L

    /** What is remembered between taps (saved state of the ViewModel). */
    data class Memory(val knownPermanent: Boolean = false, val deniedOnce: Boolean = false)

    /** The tap on "Allow". */
    fun onTap(granted: Boolean, memory: Memory): NotificationStep = when {
        granted -> NotificationStep.None
        memory.knownPermanent -> NotificationStep.OpenSettings
        else -> NotificationStep.Request
    }

    /** The request's result: the memory to keep and what to do now. [elapsedMs] null = unknown (treated as a dialog shown). */
    fun onResult(granted: Boolean, rationale: Boolean, elapsedMs: Long?, memory: Memory): Pair<Memory, NotificationStep> {
        val instant = elapsedMs != null && elapsedMs < INSTANT_RESULT_MS
        return when {
            granted -> Memory() to NotificationStep.None
            // No dialog at all: this tap meant "grant", and only the settings can.
            !rationale && instant -> memory.copy(knownPermanent = true) to NotificationStep.OpenSettings
            // Denied once: the system will still show its dialog next time.
            rationale -> memory.copy(deniedOnce = true) to NotificationStep.None
            // A second explicit denial: permanent; the settings open on the next tap.
            memory.deniedOnce -> memory.copy(knownPermanent = true) to NotificationStep.None
            // Dismissed: the next tap asks again.
            else -> memory to NotificationStep.None
        }
    }
}

/**
 * D.7 `ReliabilityViewModel`: [refresh] re-reads every check (called on `ON_RESUME`, voice
 * `rememberSystemChecks` pattern); self-checks persist through [ReliabilityStore]. No kernel
 * call is ever made here: the page works with Termux stopped (D.6 "offline"). The notification
 * item's request follows [NotificationRequestRules] (B5); [elapsedMs] times its result.
 */
class ReliabilityViewModel(
    private val savedState: SavedStateHandle,
    private val variant: ReliabilityVariant,
    private val checks: ReliabilityChecks,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    private val _state = MutableStateFlow(ReliabilityUiState(variant))
    val state: StateFlow<ReliabilityUiState> = _state.asStateFlow()

    /**
     * User toggles win over the "first not-done item is open" default until the page is left, or
     * until the item becomes done (trial row 14).
     */
    private val overrides = mutableMapOf<ReliabilityItemId, Boolean>()

    init {
        savedState.get<String>(KEY_OVERRIDES)?.let(::decodeOverrides)?.let(overrides::putAll)
        refresh()
        viewModelScope.launch {
            checks.store.confirmed.collect { refreshNow(it) }
        }
        if (variant == ReliabilityVariant.Onboarding) decideOnboarding()
    }

    /** Re-reads every check (D.7: on every `ON_RESUME`, and after a self-check changes). */
    fun refresh(): Job = viewModelScope.launch { refreshNow(withContext(io) { checks.store.confirmed.first() }) }

    // ---- B5: the notification request -------------------------------------------------------

    private var notificationMemory: NotificationRequestRules.Memory
        get() = NotificationRequestRules.Memory(
            knownPermanent = savedState[KEY_NOTIF_PERMANENT] ?: false,
            deniedOnce = savedState[KEY_NOTIF_DENIED_ONCE] ?: false,
        )
        set(value) {
            savedState[KEY_NOTIF_PERMANENT] = value.knownPermanent
            savedState[KEY_NOTIF_DENIED_ONCE] = value.deniedOnce
        }

    /** When the request in flight was launched; null when none is (or the process was recreated meanwhile). */
    private var requestLaunchedAt: Long? = null

    /** "Allow" on the notification item. [granted]: `POST_NOTIFICATIONS` is held now. Returns what the screen does. */
    fun onNotificationsTapped(granted: Boolean): NotificationStep {
        val step = NotificationRequestRules.onTap(granted, notificationMemory)
        if (step == NotificationStep.Request) requestLaunchedAt = elapsedMs()
        return step
    }

    /** The `RequestPermission` result; [rationale] = `shouldShowRequestPermissionRationale` afterwards. */
    fun onNotificationsResult(granted: Boolean, rationale: Boolean): NotificationStep {
        val elapsed = requestLaunchedAt?.let { elapsedMs() - it }
        requestLaunchedAt = null
        val (memory, step) = NotificationRequestRules.onResult(granted, rationale, elapsed, notificationMemory)
        notificationMemory = memory
        if (granted) refresh()
        return step
    }

    private suspend fun refreshNow(confirmed: Set<ReliabilityItemId>) {
        val items = withContext(io) { checks.items(confirmed) }
        // B5: a permission granted meanwhile (in settings, or by the dialog) clears what was learned.
        if (items.any { it.id == ReliabilityItemId.Notifications && it.done }) notificationMemory = NotificationRequestRules.Memory()
        // Trial row 14: an item that has just become done collapses like the other done items,
        // also when the user had opened it (Notifications after "Allow"): its toggle is forgotten.
        val settled = ReliabilityRules.newlyDone(_state.value.items, items)
        if (overrides.keys.removeAll(settled)) savedState[KEY_OVERRIDES] = encodeOverrides(overrides)
        val summary = ReliabilityRules.summary(items)
        val allDone = ReliabilityRules.allDone(items)
        _state.update {
            it.copy(
                items = items,
                doneCount = summary.doneCount,
                countedTotal = summary.countedTotal,
                allDone = allDone,
                expanded = expandedFor(items, allDone),
                loaded = true,
            )
        }
    }

    private fun expandedFor(items: List<ReliabilityItemUi>, allDone: Boolean): Set<ReliabilityItemId> {
        val default = if (allDone) null else ReliabilityRules.firstOpen(items)
        return items.mapNotNullTo(mutableSetOf()) { item ->
            val open = overrides[item.id] ?: (item.id == default)
            item.id.takeIf { open }
        }
    }

    /** Taps on an item header: expand or collapse it (the user's choice sticks). */
    fun toggle(id: ReliabilityItemId) {
        val open = id !in _state.value.expanded
        setExpanded(id, open)
    }

    /** Keeps an item open, e.g. after its settings screen failed to open (D.6 "error"). */
    fun setExpanded(id: ReliabilityItemId, open: Boolean) {
        overrides[id] = open
        savedState[KEY_OVERRIDES] = encodeOverrides(overrides)
        _state.update { it.copy(expanded = if (open) it.expanded + id else it.expanded - id) }
    }

    /** The "I've done this" checkbox of a self-checked item. */
    fun setConfirmed(id: ReliabilityItemId, confirmed: Boolean): Job = viewModelScope.launch {
        withContext(io) { checks.store.setConfirmed(id, confirmed) }
    }

    /** D.3: "Continue" and "Skip for now" both write `onboarding.reliabilityShownAt`. */
    fun markShown(): Job = viewModelScope.launch { withContext(io) { runCatching { checks.store.markOnboardingShown() } } }

    private fun decideOnboarding() {
        savedState.get<Boolean>(KEY_SKIP)?.let { skip ->
            _state.update { it.copy(skipOnboarding = skip) }
            return
        }
        viewModelScope.launch {
            val skip = withContext(io) {
                runCatching { !checks.onboardingStepNeeded(checks.store.confirmed.first()) }.getOrDefault(false)
            }
            // D.4: skipped, `reliabilityShownAt` is still written.
            if (skip) withContext(io) { runCatching { checks.store.markOnboardingShown() } }
            savedState[KEY_SKIP] = skip
            _state.update { it.copy(skipOnboarding = skip) }
        }
    }

    companion object {
        private const val KEY_OVERRIDES = "reliability.expandedOverrides"
        private const val KEY_SKIP = "reliability.skipOnboarding"
        private const val KEY_NOTIF_PERMANENT = "reliability.notifications.knownPermanent"
        private const val KEY_NOTIF_DENIED_ONCE = "reliability.notifications.deniedOnce"

        internal fun encodeOverrides(map: Map<ReliabilityItemId, Boolean>): String =
            map.entries.joinToString(",") { "${it.key.name}=${it.value}" }

        internal fun decodeOverrides(value: String): Map<ReliabilityItemId, Boolean> =
            value.split(',').mapNotNull { part ->
                val (name, open) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val id = ReliabilityItemId.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                id to (open == "true")
            }.toMap()

        fun factory(graph: AppGraph, variant: ReliabilityVariant, context: android.content.Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReliabilityViewModel(createSavedStateHandle(), variant, ReliabilityChecks.of(context, graph.prefs))
            }
        }
    }
}
