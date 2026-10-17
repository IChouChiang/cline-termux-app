package dev.cline.termux.fakes

import dev.cline.termux.data.session.StopNotice
import dev.cline.termux.data.session.StopNoticeStore
import dev.cline.termux.data.session.StopNotices

/** `sessions.stopNotices` in memory; stores the encoded JSON so the round trip is exercised. */
class InMemoryStopNoticeStore(initial: List<StopNotice> = emptyList()) : StopNoticeStore {
    var json: String = StopNotices.encode(initial)

    val notices: List<StopNotice> get() = StopNotices.decode(json)

    override suspend fun load(): List<StopNotice> = StopNotices.decode(json)

    override suspend fun save(notices: List<StopNotice>) {
        json = StopNotices.encode(notices)
    }
}
