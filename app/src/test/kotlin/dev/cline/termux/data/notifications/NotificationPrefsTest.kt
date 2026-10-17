package dev.cline.termux.data.notifications

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop `desktop-notifications.ts` settings: defaults, `readPreference` fallback, independent toggles. */
class NotificationPrefsTest {
    private class MemoryStore : NotificationSettingsStore {
        val state = MutableStateFlow<String?>(null)
        override val json: Flow<String?> get() = state
        override suspend fun read(): String? = state.value
        override suspend fun write(json: String) {
            state.value = json
        }
    }

    @Test
    fun defaultsAreAllEnabledWithoutSound() {
        val settings = NotificationSettingsCodec.read(null)
        for (type in NotificationEventType.entries) {
            assertEquals(NotificationPreference(enabled = true, sound = false), settings[type])
        }
        assertEquals(4, settings.enabledCount)
    }

    @Test
    fun unparseableOrNonObjectJsonFallsBackToDefaults() {
        assertEquals(NotificationSettings.DEFAULT, NotificationSettingsCodec.read("{not json"))
        assertEquals(NotificationSettings.DEFAULT, NotificationSettingsCodec.read("[1,2]"))
        assertEquals(NotificationSettings.DEFAULT, NotificationSettingsCodec.read(""))
    }

    @Test
    fun readPreferenceKeepsBooleansAndFallsBackPerField() {
        val raw = """
            {"taskCompletion":{"enabled":false,"sound":"yes"},
             "approvalNeeded":{"sound":true},
             "questionAsked":[true],
             "sessionError":{"enabled":"false","sound":1}}
        """.trimIndent()
        val settings = NotificationSettingsCodec.read(raw)
        assertEquals(NotificationPreference(enabled = false, sound = false), settings.taskCompletion)
        assertEquals(NotificationPreference(enabled = true, sound = true), settings.approvalNeeded)
        assertEquals(NotificationPreference(enabled = true, sound = false), settings.questionAsked)
        assertEquals(NotificationPreference(enabled = true, sound = false), settings.sessionError)
    }

    @Test
    fun writeProducesTheDesktopShape() {
        val json = Json.parseToJsonElement(NotificationSettingsCodec.write(NotificationSettings.DEFAULT)) as JsonObject
        assertEquals(listOf("taskCompletion", "approvalNeeded", "questionAsked", "sessionError"), json.keys.toList())
        assertTrue(json.getValue("approvalNeeded").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertFalse(json.getValue("approvalNeeded").jsonObject.getValue("sound").jsonPrimitive.boolean)
    }

    /** Desktop "persists independent event and sound preferences". */
    @Test
    fun persistsIndependentEventAndSoundPreferences() = runTest {
        val store = MemoryStore()
        val prefs = NotificationPrefs(store)
        prefs.setEnabled(NotificationEventType.TaskCompletion, false)
        prefs.setSound(NotificationEventType.ApprovalNeeded, true)

        val read = NotificationPrefs(store).current()
        assertEquals(NotificationPreference(enabled = false, sound = false), read.taskCompletion)
        assertEquals(NotificationPreference(enabled = true, sound = true), read.approvalNeeded)
        assertEquals(NotificationPreference(enabled = true, sound = false), read.questionAsked)
        assertEquals(NotificationPreference(enabled = true, sound = false), read.sessionError)
        assertEquals(3, read.enabledCount)
    }

    @Test
    fun settingsFlowEmitsOnChange() = runTest {
        val store = MemoryStore()
        val prefs = NotificationPrefs(store)
        prefs.settings.test {
            assertEquals(NotificationSettings.DEFAULT, awaitItem())
            prefs.setSound(NotificationEventType.SessionError, true)
            assertTrue(awaitItem().sessionError.sound)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
