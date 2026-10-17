package dev.cline.termux.data.notifications

import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the notifier and the keep-alive read from the session layer. They never subscribe to
 * the socket (D15), and they never collect a session's state flow either: that would keep the
 * session, its transcript, images and tool output in memory for good (the store only evicts
 * states nobody collects, docs/07 §4.1). They watch the store's [NotifyFacts] map instead.
 */
interface LoadedSessionSource {
    /** [NotifyFacts] of every session in memory; an evicted session leaves the map. */
    val facts: Flow<Map<String, NotifyFacts>>
}

fun SessionStore.asStateSource(): LoadedSessionSource = object : LoadedSessionSource {
    override val facts: Flow<Map<String, NotifyFacts>> get() = notifyFacts
}

/**
 * The facts of the sessions in `KnownSessions` that the store holds (customize-and-rest.md
 * §11.3: notifications and keep-alive act only on known sessions). A TUI- or schedule-started
 * session the app never opened is never in the map.
 *
 * Emits only when a known session's facts change or the known set changes, never for a
 * streamed delta or for a session outside the set.
 */
fun knownSessionStates(source: LoadedSessionSource, known: Flow<Set<String>>): Flow<Map<String, NotifyFacts>> =
    combine(source.facts, known) { all, ids -> all.filterKeys { it in ids } }
        .distinctUntilChanged()
