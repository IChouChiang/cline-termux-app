package dev.cline.termux.fakes

import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.TurnRecords
import dev.cline.termux.data.session.TurnStore

/** `chat.turns` in memory; stores the encoded JSON so the round trip is exercised. */
class InMemoryTurnStore(initial: List<TurnRecord> = emptyList()) : TurnStore {
    var json: String = TurnRecords.encode(initial)
    var saves = 0
        private set

    val records: List<TurnRecord> get() = TurnRecords.decode(json)

    override suspend fun load(): List<TurnRecord> = TurnRecords.decode(json)

    override suspend fun save(records: List<TurnRecord>) {
        saves++
        json = TurnRecords.encode(records)
    }
}
