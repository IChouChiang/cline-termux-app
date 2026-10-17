package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.hub.HubCursorStore

/** The one global cursor (D2) in memory; records every write so tests can assert the throttle. */
class InMemoryCursorStore(@Volatile var value: Long? = null) : HubCursorStore {
    val puts = mutableListOf<Long>()

    override suspend fun get(): Long? = value

    override suspend fun put(sequence: Long) {
        puts += sequence
        val current = value
        if (current == null || sequence > current) value = sequence
    }
}
