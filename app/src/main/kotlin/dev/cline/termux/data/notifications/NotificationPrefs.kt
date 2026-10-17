package dev.cline.termux.data.notifications

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The four notification events (desktop `DESKTOP_NOTIFICATION_EVENT_TYPES`, settings-providers.md
 * S3.2). [key] is the field name in the persisted `notifications.v1` JSON; the order is the
 * settings page's row order.
 */
enum class NotificationEventType(val key: String) {
    TaskCompletion("taskCompletion"),
    ApprovalNeeded("approvalNeeded"),
    QuestionAsked("questionAsked"),
    SessionError("sessionError"),
}

/** Desktop `DesktopNotificationPreference`. */
@Immutable
data class NotificationPreference(val enabled: Boolean = true, val sound: Boolean = false)

/** Desktop `DesktopNotificationSettings`: one [NotificationPreference] per event. */
@Immutable
data class NotificationSettings(
    val taskCompletion: NotificationPreference = NotificationPreference(),
    val approvalNeeded: NotificationPreference = NotificationPreference(),
    val questionAsked: NotificationPreference = NotificationPreference(),
    val sessionError: NotificationPreference = NotificationPreference(),
) {
    operator fun get(type: NotificationEventType): NotificationPreference = when (type) {
        NotificationEventType.TaskCompletion -> taskCompletion
        NotificationEventType.ApprovalNeeded -> approvalNeeded
        NotificationEventType.QuestionAsked -> questionAsked
        NotificationEventType.SessionError -> sessionError
    }

    fun with(type: NotificationEventType, preference: NotificationPreference): NotificationSettings = when (type) {
        NotificationEventType.TaskCompletion -> copy(taskCompletion = preference)
        NotificationEventType.ApprovalNeeded -> copy(approvalNeeded = preference)
        NotificationEventType.QuestionAsked -> copy(questionAsked = preference)
        NotificationEventType.SessionError -> copy(sessionError = preference)
    }

    /** Events whose Notify switch is on (settings home summary "On for N events"). */
    val enabledCount: Int get() = NotificationEventType.entries.count { get(it).enabled }

    companion object {
        /** Desktop `DEFAULT_DESKTOP_NOTIFICATION_SETTINGS`: all enabled, sound off. */
        val DEFAULT = NotificationSettings()
    }
}

/**
 * The `notifications.v1` codec: a port of the desktop's `readDesktopNotificationSettings` /
 * `writeDesktopNotificationSettings` with its `readPreference` fallback rules (a missing or
 * non-object event, and any non-boolean field, fall back to the default; unparseable JSON is
 * the defaults).
 */
object NotificationSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(raw: String?): NotificationSettings {
        if (raw.isNullOrEmpty()) return NotificationSettings.DEFAULT
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return NotificationSettings.DEFAULT
        var settings = NotificationSettings.DEFAULT
        for (type in NotificationEventType.entries) {
            settings = settings.with(type, readPreference(parsed[type.key], NotificationSettings.DEFAULT[type]))
        }
        return settings
    }

    fun write(settings: NotificationSettings): String = buildJsonObject {
        for (type in NotificationEventType.entries) {
            val preference = settings[type]
            put(
                type.key,
                buildJsonObject {
                    put("enabled", preference.enabled)
                    put("sound", preference.sound)
                },
            )
        }
    }.toString()

    /** Desktop `readPreference(value, fallback)`. */
    fun readPreference(value: JsonElement?, fallback: NotificationPreference): NotificationPreference {
        val record = value as? JsonObject ?: return fallback
        return NotificationPreference(
            enabled = record["enabled"].booleanField() ?: fallback.enabled,
            sound = record["sound"].booleanField() ?: fallback.sound,
        )
    }

    /** Only a JSON boolean counts (`typeof x === "boolean"`); the strings "true"/"false" do not. */
    private fun JsonElement?.booleanField(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }
}

/** Where the settings JSON lives; [AppPreferences] in the app, a map in tests. */
interface NotificationSettingsStore {
    val json: Flow<String?>
    suspend fun read(): String?
    suspend fun write(json: String)
}

/** DataStore `notifications.v1` (docs/07 §7.2) through [AppPreferences]. */
class AppNotificationSettingsStore(private val prefs: AppPreferences) : NotificationSettingsStore {
    override val json: Flow<String?> get() = prefs.notificationsJson
    override suspend fun read(): String? = prefs.notificationsJson()
    override suspend fun write(json: String) {
        prefs.setNotificationsJson(json)
    }
}

/** The four event preferences (SP S3.2); read by `ClineNotifier` and the Notifications settings page. */
class NotificationPrefs(private val store: NotificationSettingsStore) {
    constructor(prefs: AppPreferences) : this(AppNotificationSettingsStore(prefs))

    val settings: Flow<NotificationSettings> get() = store.json.map(NotificationSettingsCodec::read).distinctUntilChanged()

    suspend fun current(): NotificationSettings = NotificationSettingsCodec.read(store.read())

    suspend fun setEnabled(type: NotificationEventType, enabled: Boolean) = update(type) { it.copy(enabled = enabled) }

    suspend fun setSound(type: NotificationEventType, sound: Boolean) = update(type) { it.copy(sound = sound) }

    /** Writes the whole normalized object, as the desktop's `writeDesktopNotificationSettings` does. */
    suspend fun write(settings: NotificationSettings) = store.write(NotificationSettingsCodec.write(settings))

    private suspend fun update(type: NotificationEventType, transform: (NotificationPreference) -> NotificationPreference) {
        val current = current()
        write(current.with(type, transform(current[type])))
    }
}
