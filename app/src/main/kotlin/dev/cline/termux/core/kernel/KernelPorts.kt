package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.hub.HubDiscoveryRecord
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubProbe
import dev.cline.termux.core.kernel.hub.HubTokenStore
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.hub.StoredHubToken

/** Minimal surface the coordinator needs from the loopback probe (fakeable). */
interface HubProber {
    suspend fun health(port: Int): HubHealth?
    suspend fun version(port: Int): HubVersion?
}

class OkHttpHubProber(private val probe: HubProbe = HubProbe()) : HubProber {
    override suspend fun health(port: Int) = probe.health(port)
    override suspend fun version(port: Int) = probe.version(port)
}

/** Credential persistence as the coordinator sees it (fakeable). */
interface HubTokenCache {
    suspend fun read(): StoredHubToken?
    suspend fun write(record: HubDiscoveryRecord)
    suspend fun clear()
}

class DataStoreHubTokenCache(private val store: HubTokenStore) : HubTokenCache {
    override suspend fun read() = store.read()
    override suspend fun write(record: HubDiscoveryRecord) = store.write(record)
    override suspend fun clear() = store.clear()
}

/**
 * How the coordinator re-attaches sessions after a (re)connect (docs/07 §3.2 H5). The
 * session core (`SessionStore`, Phase 1) implements it; until then [None] does nothing.
 */
interface SessionReattachHook {
    /** The sessions that are open in the UI and should be live on the new socket. */
    suspend fun openSessions(): Set<String>

    /** `session.attach` succeeded on the current socket. */
    suspend fun onAttached(sessionId: String)

    /** `session_not_found`: the daemon does not know the session; resume happens lazily on the next send (CA C1.7). */
    suspend fun onSessionGone(sessionId: String)

    object None : SessionReattachHook {
        override suspend fun openSessions(): Set<String> = emptySet()
        override suspend fun onAttached(sessionId: String) = Unit
        override suspend fun onSessionGone(sessionId: String) = Unit
    }
}
