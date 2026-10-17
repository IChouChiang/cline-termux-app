package dev.cline.termux.debug

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The debug `SET_HUB_TOKEN` broadcast's token + port override (docs/06 "Developer Hub override"). */
class HubOverrideRequestTest {
    @get:Rule val tmp = TemporaryFolder()

    private val job = SupervisorJob()
    private val io = CoroutineScope(Dispatchers.IO + job)

    @After
    fun tearDown() = runTest { job.cancelAndJoin() }

    @Test
    fun `a token with an optional port is a set request`() {
        assertEquals(HubOverrideRequest.Set("tok", 25498), HubOverrideRequest.parse(clear = false, token = " tok ", port = 25498))
        assertEquals(HubOverrideRequest.Set("tok", null), HubOverrideRequest.parse(clear = false, token = "tok", port = null))
        assertEquals(HubOverrideRequest.Set("tok", 1024), HubOverrideRequest.parse(clear = false, token = "tok", port = 1024))
        assertEquals(HubOverrideRequest.Set("tok", 65535), HubOverrideRequest.parse(clear = false, token = "tok", port = 65535))
    }

    @Test
    fun `clear wins over everything else`() {
        assertEquals(HubOverrideRequest.Clear, HubOverrideRequest.parse(clear = true, token = null, port = null))
        assertEquals(HubOverrideRequest.Clear, HubOverrideRequest.parse(clear = true, token = "tok", port = 25498))
        assertNull(HubTokenOverrideReceiver.immediateResult(HubOverrideRequest.Clear))
    }

    @Test
    fun `a missing token or a bad port is refused without touching storage`() {
        val bad = listOf(
            HubOverrideRequest.parse(clear = false, token = null, port = null),
            HubOverrideRequest.parse(clear = false, token = "  ", port = 25498),
            HubOverrideRequest.parse(clear = false, token = null, port = 25498),
            HubOverrideRequest.parse(clear = false, token = "tok", port = 80),
            HubOverrideRequest.parse(clear = false, token = "tok", port = 0),
            HubOverrideRequest.parse(clear = false, token = "tok", port = 65536),
            // `--es port 25498` arrives as a String (the receiver reads it as Int.MIN_VALUE).
            HubOverrideRequest.parse(clear = false, token = "tok", port = "25498"),
            HubOverrideRequest.parse(clear = false, token = "tok", port = Int.MIN_VALUE),
        )
        bad.forEach { assertEquals(HubOverrideRequest.Invalid, it) }
        assertEquals(HubTokenOverrideReceiver.RESULT_BAD_REQUEST, HubTokenOverrideReceiver.immediateResult(HubOverrideRequest.Invalid))
        assertNull(HubTokenOverrideReceiver.immediateResult(HubOverrideRequest.Set("tok", 25498)))
    }

    private fun store(): HubOverrideStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = io, produceFile = { File(tmp.root, "debug.preferences_pb") })
        // A reversible stand-in for the Keystore SecretBox: the stored value is never the token itself.
        return HubOverrideStore(dataStore, seal = { "sealed:" + it.reversed() }, open = { it.removePrefix("sealed:").reversed() })
    }

    @Test
    fun `the store keeps the token sealed and the port beside it`() = runTest {
        val store = store()
        assertNull(store.token())
        assertNull(store.port())
        store.set("secret-token", 25498)
        assertEquals("secret-token", store.token())
        assertEquals(25498, store.port())
    }

    @Test
    fun `a set without a port drops the older port, and clear removes both`() = runTest {
        val store = store()
        store.set("a", 25498)
        store.set("b", null)
        assertEquals("b", store.token())
        assertNull(store.port())
        store.set("c", 25499)
        store.clear()
        assertNull(store.token())
        assertNull(store.port())
    }

    @Test
    fun `a port without a token is never used`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = io, produceFile = { File(tmp.root, "orphan.preferences_pb") })
        dataStore.edit { it[HubOverrideStore.KEY_PORT] = 25498 }
        val store = HubOverrideStore(dataStore, seal = { it }, open = { it })
        assertNull(store.port())
        assertFalse(dataStore.data.first().contains(HubOverrideStore.KEY_TOKEN))
    }
}
