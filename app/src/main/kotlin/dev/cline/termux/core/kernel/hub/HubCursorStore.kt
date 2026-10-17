package dev.cline.termux.core.kernel.hub

/**
 * The one persisted replay cursor (docs/07 §3.1, D2): the highest global `sequence` this
 * install has processed, kept as `sessions.globalCursor` in the `sessions` DataStore
 * (§7.3; `AppPreferences.cursorStore()` is the production implementation, and its
 * migration seeds the value from the legacy `hub.cursor.*` keys once).
 *
 * Per-session cursors are not kept: per-session subscribes carry no `sinceSequence` and
 * their duplicates are dropped by sequence inside [HubClient]. The client coalesces
 * writes to one per second and flushes on disconnect, so [put] may hit the store directly.
 */
interface HubCursorStore {
    suspend fun get(): Long?

    /** Monotonic: a lower value than the stored one is ignored by the store. */
    suspend fun put(sequence: Long)
}
