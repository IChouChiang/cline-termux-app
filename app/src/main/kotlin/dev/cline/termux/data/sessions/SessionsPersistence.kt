package dev.cline.termux.data.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.JsonFileCache
import dev.cline.termux.data.prefs.SessionListCache
import java.io.File
import java.time.Clock
import kotlinx.serialization.Serializable

/**
 * The `sessions` DataStore keys [SessionsRepository] owns (docs/07 §7.3): the unread cap
 * (`sessions.lastDisconnectAt`), unread counts, last-seen sequences and the interrupted
 * suspects. Behind an interface so tests run without DataStore (docs/07 rule 6).
 */
interface SessionsPersistence {
    suspend fun lastDisconnectAt(): Long?
    suspend fun setLastDisconnectAt(atMs: Long)
    suspend fun unreadCounts(): Map<String, Int>
    suspend fun setUnreadCounts(counts: Map<String, Int>)
    suspend fun lastSeen(): Map<String, Long>
    suspend fun setLastSeen(sessionId: String, sequence: Long)

    /** Drops the session's unread count, last-seen sequence and suspect entry (F5 step 4). */
    suspend fun forget(sessionId: String)
    suspend fun suspectInterrupted(): Set<String>
    suspend fun setSuspectInterrupted(ids: Set<String>)

    /** `notifications.knownSessionIds` loses a deleted session (docs/07 §4.7). */
    suspend fun removeKnownSession(sessionId: String)
}

/** Production [SessionsPersistence] over [AppPreferences]. */
class PreferencesSessionsPersistence(private val prefs: AppPreferences) : SessionsPersistence {
    override suspend fun lastDisconnectAt(): Long? = prefs.lastDisconnectAt()
    override suspend fun setLastDisconnectAt(atMs: Long) {
        prefs.setLastDisconnectAt(atMs)
    }
    override suspend fun unreadCounts(): Map<String, Int> = prefs.unreadCounts()
    override suspend fun setUnreadCounts(counts: Map<String, Int>) {
        prefs.setUnreadCounts(counts)
    }
    override suspend fun lastSeen(): Map<String, Long> = prefs.lastSeenSequences()
    override suspend fun setLastSeen(sessionId: String, sequence: Long) {
        prefs.setLastSeen(sessionId, sequence)
    }
    override suspend fun forget(sessionId: String) {
        prefs.forgetSession(sessionId)
    }
    override suspend fun suspectInterrupted(): Set<String> = prefs.suspectInterrupted()
    override suspend fun setSuspectInterrupted(ids: Set<String>) {
        prefs.setSuspectInterrupted(ids)
    }
    override suspend fun removeKnownSession(sessionId: String) {
        prefs.removeKnownSession(sessionId)
    }
}

/** SS §1.3 rule 5: a pin or rename keeps the row's age; [prevActivityAt] wins over a newer `updatedAt`. */
@Immutable
@Serializable
data class AnnotationOverride(val prevActivityAt: Long, val armedAt: Long)

/**
 * The payload of `filesDir/sessions/list-cache.json` (SS §1.8): the last good list (≤ 200
 * rows without `metadata`, titles and workspace paths only), the annotation overrides
 * (rule 5), the schedule links (rule 6) and the hubId each interrupted suspect ran on (the
 * suspect ids themselves live in `sessions.suspectInterrupted`). Never tokens, keys or
 * transcripts.
 */
@Serializable
data class SessionsCachePayload(
    val rows: List<SessionSummary> = emptyList(),
    val overrides: Map<String, AnnotationOverride> = emptyMap(),
    val scheduleLinks: Map<String, ScheduleLink> = emptyMap(),
    val suspects: Map<String, SuspectRecord> = emptyMap(),
)

/** An interrupted suspect's turn: the daemon it ran on and its `run.started` requestId. */
@Serializable
data class SuspectRecord(val hubId: String? = null, val requestId: String? = null)

/** The production list cache under [filesDir] (`sessions/list-cache.json`, excluded from backup). */
fun sessionsListCache(filesDir: File, clock: Clock): JsonFileCache<SessionsCachePayload> =
    SessionListCache(filesDir, SessionsCachePayload.serializer(), clock = clock)
