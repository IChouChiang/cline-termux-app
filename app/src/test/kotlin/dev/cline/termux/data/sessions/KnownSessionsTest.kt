package dev.cline.termux.data.sessions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.cline.termux.data.prefs.AppPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `notifications.knownSessionIds` LRU 500 on a real DataStore (docs/07 §10.1 "Persistence"). */
@OptIn(ExperimentalCoroutinesApi::class)
class KnownSessionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val job = SupervisorJob()
    private val io = CoroutineScope(Dispatchers.IO + job)

    @After
    fun tearDown() = runTest { job.cancelAndJoin() }

    private fun prefs(): AppPreferences = AppPreferences(
        app = PreferenceDataStoreFactory.create(scope = io, produceFile = { File(tmp.root, "app.preferences_pb") }),
        sessions = PreferenceDataStoreFactory.create(scope = io, produceFile = { File(tmp.root, "sessions.preferences_pb") }),
        scope = io,
    )

    @Test
    fun addMovesToTheRecentEndAndRemoveForgets() = runTest {
        val prefs = prefs()
        val known = KnownSessions(prefs, io)
        known.add("a")
        known.add("b")
        known.add("a")
        assertEquals(listOf("b", "a"), prefs.knownSessionIds())
        known.ids.first { it == setOf("a", "b") }
        assertTrue(known.contains("a"))
        known.remove("a")
        known.ids.first { it == setOf("b") }
        assertFalse(known.contains("a"))
        known.add("  ")
        assertEquals(listOf("b"), prefs.knownSessionIds())
    }

    @Test
    fun keepsTheMostRecent500() = runTest {
        val prefs = prefs()
        val known = KnownSessions(prefs, io)
        repeat(AppPreferences.MAX_KNOWN_SESSIONS + 3) { known.add("s$it") }
        val ids = prefs.knownSessionIds()
        assertEquals(AppPreferences.MAX_KNOWN_SESSIONS, ids.size)
        assertEquals("s3", ids.first())
        assertEquals("s${AppPreferences.MAX_KNOWN_SESSIONS + 2}", ids.last())
        // Re-opening an old session keeps it from falling out.
        known.add("s3")
        known.add("new")
        assertTrue("s3" in prefs.knownSessionIds())
        assertFalse("s4" in prefs.knownSessionIds())
    }
}
