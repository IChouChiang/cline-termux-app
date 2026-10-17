package dev.cline.termux.ui.chat.rows

import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.data.sessions.formatCostUsd
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A11.9 Verbose stats line: "1.2k in · 340 out · $0.0040 · 8.1 s". Token and duration numbers
 * follow the device's locale; the cost is the app's one USD rule, [formatCostUsd], so the line
 * agrees with the composer's usage popover and the Sessions screen. TalkBack reads [TurnStatsFormat.spoken].
 */
class TurnStatsFormatTest {
    private val strings = TurnStatsStrings(
        tokensIn = "%1\$s in",
        tokensOut = "%1\$s out",
        thousands = "%1\$sk",
        millions = "%1\$sm",
        seconds = "%1\$s s",
        minutes = "%1\$s min %2\$s s",
        join = "%1\$s · %2\$s",
    )

    /** English plural rules: "one" for exactly 1. */
    private val spokenStrings = TurnStatsSpokenStrings(
        plural = { kind, quantity ->
            val one = quantity == 1
            when (kind) {
                SpokenStatsPlural.InputTokens -> if (one) "%1\$s input token" else "%1\$s input tokens"
                SpokenStatsPlural.OutputTokens -> if (one) "%1\$s output token" else "%1\$s output tokens"
                SpokenStatsPlural.Minutes -> if (one) "%1\$s minute" else "%1\$s minutes"
                SpokenStatsPlural.Seconds -> if (one) "%1\$s second" else "%1\$s seconds"
            }
        },
        cost = "cost %1\$s",
        minutesSeconds = "%1\$s %2\$s",
        join = "%1\$s, %2\$s",
    )
    private val us = Locale.US

    private fun format(stats: TurnStats, locale: Locale = us) = TurnStatsFormat.format(stats, strings, locale)

    private fun spoken(stats: TurnStats, locale: Locale = us) = TurnStatsFormat.spoken(stats, spokenStrings, locale)

    @Test
    fun `the brief's example`() {
        assertEquals("1.2k in · 340 out · $0.0040 · 8.1 s", format(TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120)))
    }

    @Test
    fun `no cost when unknown or zero`() {
        assertEquals("2.5k in · 120 out · 0.4 s", format(TurnStats(inputTokens = 2_500, outputTokens = 120, totalCost = null, durationMs = 407)))
        assertEquals("2.5k in · 120 out · 0.4 s", format(TurnStats(inputTokens = 2_500, outputTokens = 120, totalCost = 0.0, durationMs = 407)))
    }

    @Test
    fun `thousands and millions keep one decimal, under a thousand is exact`() {
        assertEquals("999", TurnStatsFormat.tokens(999, strings, us))
        assertEquals("1.0k", TurnStatsFormat.tokens(1_000, strings, us))
        assertEquals("1.1k", TurnStatsFormat.tokens(1_050, strings, us))
        assertEquals("12.5k", TurnStatsFormat.tokens(12_480, strings, us))
        assertEquals("999.9k", TurnStatsFormat.tokens(999_940, strings, us))
        assertEquals("1.0m", TurnStatsFormat.tokens(999_960, strings, us))
        assertEquals("1.3m", TurnStatsFormat.tokens(1_250_000, strings, us))
        assertEquals("0", TurnStatsFormat.tokens(-5, strings, us))
    }

    @Test
    fun `sub-second durations read in tenths, at least 0_1 s`() {
        assertEquals("0.4 s", TurnStatsFormat.duration(407, strings, us))
        assertEquals("0.1 s", TurnStatsFormat.duration(12, strings, us))
        assertEquals("0.9 s", TurnStatsFormat.duration(949, strings, us))
        assertEquals("1.0 s", TurnStatsFormat.duration(951, strings, us))
        assertEquals("59.9 s", TurnStatsFormat.duration(59_940, strings, us))
    }

    @Test
    fun `from a minute on the duration is minutes and seconds`() {
        assertEquals("1 min 0 s", TurnStatsFormat.duration(59_960, strings, us))
        assertEquals("2 min 5 s", TurnStatsFormat.duration(125_200, strings, us))
    }

    @Test
    fun `the cost is formatCostUsd, the same text the composer and Sessions show`() {
        for (usd in listOf(0.00416, 0.004, 0.1234, 0.5, 12.346)) assertEquals(formatCostUsd(usd), TurnStatsFormat.cost(usd))
        assertEquals("$0.0042", TurnStatsFormat.cost(0.00416))
        assertEquals("$0.0040", TurnStatsFormat.cost(0.004))
        assertEquals("$0.123", TurnStatsFormat.cost(0.1234))
        assertEquals("$12.35", TurnStatsFormat.cost(12.346))
        assertNull(TurnStatsFormat.cost(Double.NaN))
        assertNull(TurnStatsFormat.cost(-1.0))
    }

    @Test
    fun `token and duration numbers follow the locale, the cost keeps the app's one rule`() {
        val de = Locale.GERMANY
        val line = format(TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120), de)!!
        assertEquals("1,2k in · 340 out · $0.0040 · 8,1 s", line)
        assertEquals("1.234,5m", TurnStatsFormat.tokens(1_234_500_000, strings, de))
        // The words are resources (English copy, B2); only the numbers follow the locale.
        assertEquals("1 min 5 s", TurnStatsFormat.duration(65_000, strings, de))
    }

    @Test
    fun `parts without data are left out, nothing to show is null`() {
        assertEquals("8.1 s", format(TurnStats(durationMs = 8_120)))
        assertEquals("0 in · 15 out", format(TurnStats(inputTokens = 0, outputTokens = 15)))
        assertEquals("$0.010", format(TurnStats(totalCost = 0.01)))
        assertNull(format(TurnStats()))
        assertNull(format(TurnStats(durationMs = 0)))
    }

    @Test
    fun `TalkBack hears the words, not the abbreviations`() {
        assertEquals(
            "1,234 input tokens, 340 output tokens, cost $0.0040, 8 seconds",
            spoken(TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120)),
        )
        assertEquals("1 input token, 1 output token, 1 second", spoken(TurnStats(inputTokens = 1, outputTokens = 1, durationMs = 407)))
        assertEquals("1 minute 5 seconds", spoken(TurnStats(durationMs = 65_000)))
        assertEquals("2 minutes 1 second", spoken(TurnStats(durationMs = 121_000)))
        assertEquals("59 seconds", spoken(TurnStats(durationMs = 59_600)))
        assertEquals("1.234.567 input tokens, 0 output tokens", spoken(TurnStats(inputTokens = 1_234_567), Locale.GERMANY))
        assertNull(spoken(TurnStats()))
    }
}
