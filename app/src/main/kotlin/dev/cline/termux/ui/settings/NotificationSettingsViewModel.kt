package dev.cline.termux.ui.settings

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.service.NotificationChannels
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Notifications page's data and system checks ([GraphNotificationSettingsSources] in the
 * app). The system calls are cheap and re-read on every `ON_RESUME` (voice `rememberSystemChecks`).
 */
interface NotificationSettingsSources {
    val settings: Flow<NotificationSettings>

    /** `KeepAlive.Denied`: Android refused the foreground service (docs/07 §9). */
    val keepAliveDenied: Flow<Boolean>

    /** docs/07 D11: DataStore `notifications.keepConnected` (default false), the Background connection switch. */
    val keepConnected: Flow<Boolean>
    suspend fun setKeepConnected(enabled: Boolean)
    suspend fun setEnabled(type: NotificationEventType, enabled: Boolean)
    suspend fun setSound(type: NotificationEventType, sound: Boolean)

    val sdkInt: Int
    fun appNotificationsEnabled(): Boolean
    fun postPermissionGranted(): Boolean
    fun isChannelBlocked(channelId: String): Boolean
}

/** S3.4 permission states. */
enum class NotificationPermission { Checking, Granted, NotAsked, Denied, NotRequired }

/** S3.4 `EventPrefUi`: [channelBlocked] concerns the event's **active** channel (sounding or `_quiet`). */
@Immutable
data class EventPrefUi(val type: NotificationEventType, val enabled: Boolean, val sound: Boolean, val channelBlocked: Boolean) {
    /** The channel this event posts on with its Sound choice (S3.3). */
    val activeChannel: String get() = NotificationChannels.channelFor(type, sound)
}

/** S3.4 `NotificationSettingsUiState`. */
@Immutable
data class NotificationSettingsUiState(
    val permission: NotificationPermission = NotificationPermission.Checking,
    /** "Allow notifications" is disabled while the system dialog is up. */
    val requesting: Boolean = false,
    /** Order: taskCompletion, approvalNeeded, questionAsked, sessionError. */
    val events: List<EventPrefUi> = NotificationEventType.entries.map { EventPrefUi(it, enabled = true, sound = false, channelBlocked = false) },
    /** "Background connection": Android did not allow the foreground service. */
    val keepAliveDenied: Boolean = false,
    /** docs/07 D11: stay connected (foreground service) while Cline is in the background. */
    val keepConnected: Boolean = false,
) {
    /** The permission card shows unless posting is allowed (S3.1). */
    val showsPermissionCard: Boolean
        get() = permission == NotificationPermission.Checking ||
            permission == NotificationPermission.NotAsked ||
            permission == NotificationPermission.Denied

    /** "Allowed by system. …" on the System notification settings row. */
    val allowedBySystem: Boolean
        get() = permission == NotificationPermission.Granted || permission == NotificationPermission.NotRequired
}

/** S3.2 permission flow, pure. */
object NotificationPermissionRules {
    /**
     * 1. API < 33: no runtime permission; only `areNotificationsEnabled()` matters.
     * 2. API ≥ 33, not granted and never asked (or the system would still show its dialog,
     *    `shouldShowRequestPermissionRationale == true`): "Allow notifications".
     * 3. Denied permanently (asked before and no rationale) or app-level notifications off:
     *    "Check permission" → the app's notification settings.
     */
    fun derive(sdkInt: Int, appEnabled: Boolean, granted: Boolean, requestedBefore: Boolean, rationale: Boolean): NotificationPermission = when {
        sdkInt < Build.VERSION_CODES.TIRAMISU -> if (appEnabled) NotificationPermission.NotRequired else NotificationPermission.Denied
        granted -> if (appEnabled) NotificationPermission.Granted else NotificationPermission.Denied
        !requestedBefore || rationale -> NotificationPermission.NotAsked
        else -> NotificationPermission.Denied
    }
}

/** One-shot requests the screen performs with its activity. */
sealed interface NotificationSettingsEvent {
    /** Launch `RequestPermission(POST_NOTIFICATIONS)`. */
    data object RequestPermission : NotificationSettingsEvent
}

/**
 * Notifications (S3): the four events' Notify / Sound choices (DataStore `notifications.v1`
 * through `NotificationPrefs`), the `POST_NOTIFICATIONS` states, per-channel blocking and the
 * keep-alive note. "Requested before" lives in the [SavedStateHandle] (it cannot be read back
 * from the system: a never-asked and a permanently denied permission look alike).
 */
class NotificationSettingsViewModel(
    private val savedState: SavedStateHandle,
    private val sources: NotificationSettingsSources,
) : ViewModel() {

    private val permission = MutableStateFlow(NotificationPermission.Checking)
    private val requesting = MutableStateFlow(false)

    /** Bumped on every resume so channel blocking is re-read from the system. */
    private val systemTick = MutableStateFlow(0)
    private var autoRequested = false
    private var lastRationale = false

    private val _events = Channel<NotificationSettingsEvent>(Channel.BUFFERED)
    val events: Flow<NotificationSettingsEvent> = _events.receiveAsFlow()

    private val settings: StateFlow<NotificationSettings> =
        sources.settings.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettings.DEFAULT)

    val uiState: StateFlow<NotificationSettingsUiState> = combine(
        settings,
        permission,
        requesting,
        combine(sources.keepAliveDenied, sources.keepConnected) { denied, keep -> denied to keep },
        systemTick,
    ) { settings, permission, requesting, (keepAliveDenied, keepConnected), _ ->
        NotificationSettingsUiState(
            permission = permission,
            requesting = requesting,
            events = NotificationEventType.entries.map { type ->
                val preference = settings[type]
                EventPrefUi(
                    type = type,
                    enabled = preference.enabled,
                    sound = preference.sound,
                    channelBlocked = sources.isChannelBlocked(NotificationChannels.channelFor(type, preference.sound)),
                )
            },
            keepAliveDenied = keepAliveDenied,
            keepConnected = keepConnected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

    private val requestedBefore: Boolean get() = savedState[KEY_REQUESTED] ?: false

    /** ON_RESUME: re-read the permission and the channels. [rationale] is the activity's `shouldShowRequestPermissionRationale`. */
    fun onResume(rationale: Boolean) {
        lastRationale = rationale
        recompute()
        systemTick.value += 1
    }

    /** The Background connection switch (docs/07 D11). */
    fun setKeepConnected(enabled: Boolean) {
        viewModelScope.launch { sources.setKeepConnected(enabled) }
    }

    /** "Allow notifications". */
    fun requestPermission() {
        if (requesting.value) return
        requesting.value = true
        _events.trySend(NotificationSettingsEvent.RequestPermission)
    }

    /** The `RequestPermission` result. */
    fun onPermissionResult(granted: Boolean, rationale: Boolean) {
        savedState[KEY_REQUESTED] = true
        requesting.value = false
        lastRationale = rationale
        recompute(grantedOverride = granted)
        systemTick.value += 1
    }

    /** A Notify switch. Turning one on without permission asks once (desktop `updatePreference`). */
    fun setEnabled(type: NotificationEventType, enabled: Boolean) {
        viewModelScope.launch { sources.setEnabled(type, enabled) }
        if (enabled && permission.value == NotificationPermission.NotAsked && !autoRequested) {
            autoRequested = true
            requestPermission()
        }
    }

    /** A Sound switch: picks the event's sounding or `_quiet` channel; the row re-checks that channel. */
    fun setSound(type: NotificationEventType, sound: Boolean) {
        viewModelScope.launch { sources.setSound(type, sound) }
    }

    private fun recompute(grantedOverride: Boolean? = null) {
        permission.value = NotificationPermissionRules.derive(
            sdkInt = sources.sdkInt,
            appEnabled = sources.appNotificationsEnabled(),
            granted = grantedOverride ?: sources.postPermissionGranted(),
            requestedBefore = requestedBefore,
            rationale = lastRationale,
        )
    }

    companion object {
        const val KEY_REQUESTED = "notificationPermissionRequested"
    }
}
