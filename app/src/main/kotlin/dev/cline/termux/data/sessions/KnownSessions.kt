package dev.cline.termux.data.sessions

import dev.cline.termux.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The sessions notifications and the keep-alive act on (customize-and-rest.md §11.3, docs/07
 * §4.7 and §14): every session this install created, opened, or opened from a notification.
 * DataStore `notifications.knownSessionIds`, the most recent [AppPreferences.MAX_KNOWN_SESSIONS]
 * by last open (LRU), removed on `session.delete` from the app.
 *
 * Callers: `add` on `session.create` from the app, on the chat route resuming for a session
 * (including child sessions and schedule-run rows), and on a notification tap; `remove` after
 * a successful delete.
 */
class KnownSessions(private val store: Store, scope: CoroutineScope) {
    constructor(prefs: AppPreferences, scope: CoroutineScope) : this(AppStore(prefs), scope)

    /** The persisted list, oldest first (the LRU order). */
    interface Store {
        val ids: Flow<List<String>>
        suspend fun add(sessionId: String)
        suspend fun remove(sessionId: String)
    }

    private class AppStore(private val prefs: AppPreferences) : Store {
        override val ids: Flow<List<String>> get() = prefs.knownSessionIds
        override suspend fun add(sessionId: String) {
            prefs.addKnownSession(sessionId)
        }
        override suspend fun remove(sessionId: String) {
            prefs.removeKnownSession(sessionId)
        }
    }

    /** The known set; empty until the first DataStore read. */
    val ids: StateFlow<Set<String>> = store.ids.map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun contains(sessionId: String): Boolean = sessionId in ids.value

    /** Moves [sessionId] to the most-recent end, dropping the oldest beyond the cap. */
    suspend fun add(sessionId: String) {
        if (sessionId.isBlank()) return
        store.add(sessionId)
    }

    suspend fun remove(sessionId: String) = store.remove(sessionId)
}
