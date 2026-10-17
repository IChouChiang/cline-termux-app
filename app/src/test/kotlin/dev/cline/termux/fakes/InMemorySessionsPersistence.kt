package dev.cline.termux.fakes

import dev.cline.termux.data.sessions.SessionsPersistence

/** `sessions` DataStore keys in memory (docs/07 §10.1 fakes). */
class InMemorySessionsPersistence(
    var lastDisconnect: Long? = null,
    val unread: MutableMap<String, Int> = mutableMapOf(),
    val lastSeenMap: MutableMap<String, Long> = mutableMapOf(),
    val suspects: MutableSet<String> = mutableSetOf(),
) : SessionsPersistence {
    val removedKnown = mutableListOf<String>()
    val forgotten = mutableListOf<String>()
    var lastSeenWrites = 0
        private set

    override suspend fun lastDisconnectAt(): Long? = lastDisconnect
    override suspend fun setLastDisconnectAt(atMs: Long) {
        lastDisconnect = atMs
    }
    override suspend fun unreadCounts(): Map<String, Int> = unread.toMap()
    override suspend fun setUnreadCounts(counts: Map<String, Int>) {
        unread.clear()
        unread.putAll(counts.filterValues { it > 0 })
    }
    override suspend fun lastSeen(): Map<String, Long> = lastSeenMap.toMap()
    override suspend fun setLastSeen(sessionId: String, sequence: Long) {
        lastSeenWrites++
        if ((lastSeenMap[sessionId] ?: -1L) < sequence) lastSeenMap[sessionId] = sequence
    }
    override suspend fun forget(sessionId: String) {
        forgotten += sessionId
        lastSeenMap.remove(sessionId)
        unread.remove(sessionId)
        suspects.remove(sessionId)
    }
    override suspend fun suspectInterrupted(): Set<String> = suspects.toSet()
    override suspend fun setSuspectInterrupted(ids: Set<String>) {
        suspects.clear()
        suspects.addAll(ids)
    }
    override suspend fun removeKnownSession(sessionId: String) {
        removedKnown += sessionId
    }
}
