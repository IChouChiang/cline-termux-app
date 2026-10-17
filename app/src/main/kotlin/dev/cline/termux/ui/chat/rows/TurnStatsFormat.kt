package dev.cline.termux.ui.chat.rows

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.cline.termux.R
import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.data.sessions.formatCostUsd
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/*
 * A11.9 "Message details: Verbose": the turn's totals on the revealed action row of its final
 * answer, e.g. "1.2k in · 340 out · $0.0040 · 8.1 s". The terminal's verbose line
 * (chat-entry.tsx "done") shows time, tokens, cost and iterations; the decision keeps tokens,
 * cost and duration. The cost is the app's one USD rule, `formatCostUsd` (the composer's usage
 * popover and the Sessions screen use it too), and it is absent where the kernel hides it for
 * the turn's provider (`TurnRecords.statsByAnswer`). The other numbers follow the device's
 * format locale (B2), the words are resources. TalkBack reads [TurnStatsFormat.spoken] instead
 * of the abbreviations: "1,234 input tokens, 340 output tokens, cost $0.0040, 8 seconds".
 *
 * The tokens differ from the terminal's on purpose: its single "tokens" figure is the context
 * size after the turn (`result.currentContextSize`, use-prompt-input-controller.ts:375-382),
 * while this line shows the turn's input and output tokens summed over its model calls (the
 * `usage` of the run). The totals come from this app's `run.start` result when it sent the turn
 * (`TurnStats.fromResult`, kept 24 h with the turn record); for every other finished turn, from
 * the per-message `metrics` and `ts` the kernel persisted (`TurnRecords.historyStatsByAnswer`),
 * which sum to the same usage. Turns folded by compaction lose those metrics and show none.
 */

/** The copy of the stats line (strings_message_details.xml); tests build it from plain strings. */
@Immutable
data class TurnStatsStrings(
    /** "%1$s in". */
    val tokensIn: String,
    /** "%1$s out". */
    val tokensOut: String,
    /** "%1$sk": a token count in thousands. */
    val thousands: String,
    /** "%1$sm": a token count in millions. */
    val millions: String,
    /** "%1$s s". */
    val seconds: String,
    /** "%1$s min %2$s s". */
    val minutes: String,
    /** "%1$s · %2$s". */
    val join: String,
) {
    companion object {
        fun from(resources: Resources) = TurnStatsStrings(
            tokensIn = resources.getString(R.string.message_details_stats_in),
            tokensOut = resources.getString(R.string.message_details_stats_out),
            thousands = resources.getString(R.string.message_details_stats_thousands),
            millions = resources.getString(R.string.message_details_stats_millions),
            seconds = resources.getString(R.string.message_details_stats_seconds),
            minutes = resources.getString(R.string.message_details_stats_minutes),
            join = resources.getString(R.string.message_details_stats_join),
        )
    }
}

/** The plural-aware words of the spoken stats ([TurnStatsSpokenStrings.plural]). */
enum class SpokenStatsPlural(@PluralsRes val res: Int) {
    InputTokens(R.plurals.message_details_spoken_input_tokens),
    OutputTokens(R.plurals.message_details_spoken_output_tokens),
    Minutes(R.plurals.message_details_spoken_minutes),
    Seconds(R.plurals.message_details_spoken_seconds),
}

/** The TalkBack copy of the stats line; tests pass a plain [plural]. */
@Immutable
data class TurnStatsSpokenStrings(
    /** The pattern for a [SpokenStatsPlural] at a quantity, e.g. "%1$s input tokens" (the caller formats the number). */
    val plural: (kind: SpokenStatsPlural, quantity: Int) -> String,
    /** "cost %1$s". */
    val cost: String,
    /** "%1$s %2$s": the minutes, then the seconds. */
    val minutesSeconds: String,
    /** "%1$s, %2$s". */
    val join: String,
) {
    companion object {
        fun from(resources: Resources) = TurnStatsSpokenStrings(
            plural = { kind, quantity -> resources.getQuantityString(kind.res, quantity) },
            cost = resources.getString(R.string.message_details_spoken_cost),
            minutesSeconds = resources.getString(R.string.message_details_spoken_minutes_seconds),
            join = resources.getString(R.string.message_details_spoken_join),
        )
    }
}

/** Pure formatting of [TurnStats] for the Verbose action row. */
object TurnStatsFormat {
    /**
     * "<in> in · <out> out · <cost> · <duration>"; tokens only when the turn reported any, the
     * cost only when it is known and above zero (the terminal hides a zero cost too), the
     * duration only when positive. Null when nothing is left to show.
     */
    fun format(stats: TurnStats, strings: TurnStatsStrings, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String? {
        val parts = buildList {
            if (hasTokens(stats)) {
                add(String.format(locale, strings.tokensIn, tokens(stats.inputTokens, strings, locale)))
                add(String.format(locale, strings.tokensOut, tokens(stats.outputTokens, strings, locale)))
            }
            cost(stats.totalCost)?.let(::add)
            positiveDuration(stats)?.let { add(duration(it, strings, locale)) }
        }
        return join(parts, strings.join, locale)
    }

    /**
     * The same parts for TalkBack, without abbreviations: exact token counts with their plural
     * words, "cost" before the amount, and the duration in whole seconds (at least one), or
     * minutes and seconds from a minute on. Null exactly when [format] is.
     */
    fun spoken(stats: TurnStats, strings: TurnStatsSpokenStrings, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String? {
        val integers = NumberFormat.getIntegerInstance(locale)
        fun counted(kind: SpokenStatsPlural, count: Long): String =
            String.format(locale, strings.plural(kind, quantityOf(count)), integers.format(count))
        val parts = buildList {
            if (hasTokens(stats)) {
                add(counted(SpokenStatsPlural.InputTokens, stats.inputTokens.coerceAtLeast(0)))
                add(counted(SpokenStatsPlural.OutputTokens, stats.outputTokens.coerceAtLeast(0)))
            }
            cost(stats.totalCost)?.let { add(String.format(locale, strings.cost, it)) }
            positiveDuration(stats)?.let { ms ->
                val tenths = (ms / 100.0).roundToLong().coerceAtLeast(1)
                if (tenths < 600) {
                    add(counted(SpokenStatsPlural.Seconds, (ms / 1_000.0).roundToLong().coerceIn(1, 59)))
                } else {
                    val seconds = (ms / 1_000.0).roundToLong()
                    val minutes = counted(SpokenStatsPlural.Minutes, seconds / 60)
                    add(String.format(locale, strings.minutesSeconds, minutes, counted(SpokenStatsPlural.Seconds, seconds % 60)))
                }
            }
        }
        return join(parts, strings.join, locale)
    }

    /** "340", "1.2k", "12.5k", "1.3m": under 1,000 as is, then one decimal in thousands or millions. */
    fun tokens(count: Long, strings: TurnStatsStrings, locale: Locale): String {
        val value = count.coerceAtLeast(0)
        if (value < 1_000) return NumberFormat.getIntegerInstance(locale).format(value)
        val tenthsOfThousands = (value / 100.0).roundToLong()
        if (tenthsOfThousands < 10_000) return String.format(locale, strings.thousands, oneDecimal(tenthsOfThousands, locale))
        return String.format(locale, strings.millions, oneDecimal((value / 100_000.0).roundToLong(), locale))
    }

    /** The app's one USD rule, [formatCostUsd] (desktop `formatCostUsd`); null for no positive cost. */
    fun cost(usd: Double?): String? = formatCostUsd(usd)

    /** "0.4 s", "8.1 s" (one decimal, at least 0.1 s) under a minute; "2 min 5 s" from a minute on. */
    fun duration(ms: Long, strings: TurnStatsStrings, locale: Locale): String {
        val tenths = (ms / 100.0).roundToLong().coerceAtLeast(1)
        if (tenths < 600) return String.format(locale, strings.seconds, oneDecimal(tenths, locale))
        val seconds = (ms / 1_000.0).roundToLong()
        val integers = NumberFormat.getIntegerInstance(locale)
        return String.format(locale, strings.minutes, integers.format(seconds / 60), integers.format(seconds % 60))
    }

    private fun hasTokens(stats: TurnStats): Boolean = stats.inputTokens > 0 || stats.outputTokens > 0

    private fun positiveDuration(stats: TurnStats): Long? = stats.durationMs?.takeIf { it > 0 }

    private fun join(parts: List<String>, pattern: String, locale: Locale): String? =
        if (parts.isEmpty()) null else parts.reduce { acc, part -> String.format(locale, pattern, acc, part) }

    /** A count as a plural quantity (`getQuantityString` takes an Int). */
    private fun quantityOf(count: Long): Int = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** [tenths] / 10 with exactly one decimal in [locale] ("1.2", "1,2"). */
    private fun oneDecimal(tenths: Long, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(tenths / 10.0)
}

@Composable
internal fun rememberTurnStatsStrings(): TurnStatsStrings {
    val resources = LocalResources.current
    return remember(resources) { TurnStatsStrings.from(resources) }
}

@Composable
internal fun rememberTurnStatsSpokenStrings(): TurnStatsSpokenStrings {
    val resources = LocalResources.current
    return remember(resources) { TurnStatsSpokenStrings.from(resources) }
}

/**
 * The Verbose stats on the revealed action row: `labelMedium` `onSurfaceVariant`, like the time
 * beside it. TalkBack reads the spoken form ([TurnStatsFormat.spoken]), not the abbreviations.
 */
@Composable
internal fun TurnStatsText(stats: TurnStats, modifier: Modifier = Modifier) {
    val strings = rememberTurnStatsStrings()
    val spokenStrings = rememberTurnStatsSpokenStrings()
    val text = remember(stats, strings) { TurnStatsFormat.format(stats, strings) } ?: return
    val spoken = remember(stats, spokenStrings) { TurnStatsFormat.spoken(stats, spokenStrings) } ?: text
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}
