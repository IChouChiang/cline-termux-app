package dev.cline.termux.fakes

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A settable [Clock] for tests (docs/07 §10.1 fakes). */
class FakeClock(
    private var now: Instant = Instant.parse("2026-10-03T12:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)
    override fun instant(): Instant = now

    fun advanceMillis(ms: Long) {
        now = now.plusMillis(ms)
    }

    fun set(instant: Instant) {
        now = instant
    }
}
