package dev.cline.termux.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import dev.cline.termux.fakes.FakeClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A11.9 `transcript.messageDetails`: Brief by default, Verbose once chosen, anything unknown Brief. */
class MessageDetailsPreferenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() = runTest { jobs.forEach { it.cancelAndJoin() } }

    private fun store(name: String): DataStore<Preferences> {
        val job = SupervisorJob().also(jobs::add)
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(tmp.root, "$name.preferences_pb") },
        )
    }

    private fun prefs(app: DataStore<Preferences> = store("app")): AppPreferences {
        val job = SupervisorJob().also(jobs::add)
        return AppPreferences(app, store("sessions"), CoroutineScope(Dispatchers.IO + job), FakeClock())
    }

    @Test
    fun `message details default to Brief`() = runTest {
        val p = prefs()
        assertEquals(MessageDetails.Brief, p.messageDetails())
        assertEquals(MessageDetails.Brief, p.messageDetails.first())
    }

    @Test
    fun `choosing Verbose is stored under transcript_messageDetails and emitted`() = runTest {
        val p = prefs()
        p.messageDetails.test {
            assertEquals(MessageDetails.Brief, awaitItem())
            p.setMessageDetails(MessageDetails.Verbose)
            assertEquals(MessageDetails.Verbose, awaitItem())
            p.setMessageDetails(MessageDetails.Brief)
            assertEquals(MessageDetails.Brief, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        p.setMessageDetails(MessageDetails.Verbose)
        assertEquals("verbose", p.data.first()[stringPreferencesKey("transcript.messageDetails")])
        assertEquals(MessageDetails.Verbose, p.messageDetails())
    }

    @Test
    fun `an unknown stored value reads as Brief`() = runTest {
        val app = store("app")
        app.edit { it[stringPreferencesKey("transcript.messageDetails")] = "chatty" }
        assertEquals(MessageDetails.Brief, prefs(app).messageDetails())
        assertEquals(MessageDetails.Brief, MessageDetails.fromStorage(null))
        assertEquals(MessageDetails.Verbose, MessageDetails.fromStorage("verbose"))
    }
}
