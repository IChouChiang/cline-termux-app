package dev.cline.termux.data.chat

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/*
 * Context usage meter, ported from sdk/packages/ui/components/agent-context-usage.tsx
 * (design/screens/composer-approvals.md C1.12). The usage is the current request's context
 * (`tokensIn + tokensOut`), not accumulated session traffic.
 */

/** `AgentContextUsageData`. */
data class ContextUsageData(
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheReadTokens: Long,
    val contextWindow: Long?,
)

/** Ring tone thresholds of the desktop (primary below 50 %, orange from 50 %, red from 75 %). */
enum class ContextUsageTone { Normal, Warning, Critical }

/** Everything the ring and the sheet draw. Fractions are of the full track and already clamped. */
data class ContextUsage(
    val tokensIn: Long,
    val tokensOut: Long,
    /** As reported (the sheet's "Cached tokens" row), even when above [tokensIn]. */
    val cacheReadTokens: Long,
    val totalTokens: Long,
    val contextWindow: Long,
    /** Clamped to 0..1; the ring's sweep. */
    val ratio: Double,
    val percent: Int,
    val tone: ContextUsageTone,
    /** min(cacheReadTokens, tokensIn): the cached share never draws beyond input. */
    val cachedTokens: Long,
    val uncachedInputTokens: Long,
    val uncachedInputFraction: Float,
    val cachedInputFraction: Float,
    val outputFraction: Float,
    /** "12.3k / 200k (6%)". */
    val valueLabel: String,
)

/** Unclamped segment widths as fractions of the track (the desktop's `segmentWidth`). */
data class SegmentWidths(val uncachedInput: Double, val cachedInput: Double, val output: Double)

object ContextUsageCalculator {
    /** Null when the meter is hidden: no tokens yet, or the model's window is unknown. */
    fun compute(usage: ContextUsageData, locale: Locale = Locale.getDefault()): ContextUsage? {
        val contextWindow = usage.contextWindow ?: return null
        val totalTokens = usage.tokensIn + usage.tokensOut
        if (totalTokens <= 0 || contextWindow <= 0) return null
        val ratio = (totalTokens.toDouble() / maxOf(contextWindow, 1L)).coerceIn(0.0, 1.0)
        val percent = jsRound(ratio * 100).toInt()
        val tone = when {
            ratio >= 0.75 -> ContextUsageTone.Critical
            ratio >= 0.5 -> ContextUsageTone.Warning
            else -> ContextUsageTone.Normal
        }
        val cachedTokens = minOf(usage.cacheReadTokens, usage.tokensIn)
        val uncachedInputTokens = maxOf(usage.tokensIn - cachedTokens, 0L)
        val widths = segmentWidths(usage.tokensIn, usage.tokensOut, usage.cacheReadTokens, contextWindow)
        return ContextUsage(
            tokensIn = usage.tokensIn,
            tokensOut = usage.tokensOut,
            cacheReadTokens = usage.cacheReadTokens,
            totalTokens = totalTokens,
            contextWindow = contextWindow,
            ratio = ratio,
            percent = percent,
            tone = tone,
            cachedTokens = cachedTokens,
            uncachedInputTokens = uncachedInputTokens,
            uncachedInputFraction = widths.uncachedInput.toFloat().coerceIn(0f, 1f),
            cachedInputFraction = widths.cachedInput.toFloat().coerceIn(0f, 1f),
            outputFraction = widths.output.toFloat().coerceIn(0f, 1f),
            valueLabel = "${formatCompactTokens(totalTokens, locale)} / ${formatCompactTokens(contextWindow, locale)} ($percent%)",
        )
    }

    /** Ported literally from agent-context-usage.tsx:56-61. */
    fun segmentWidths(tokensIn: Long, tokensOut: Long, cacheReadTokens: Long, contextWindow: Long): SegmentWidths {
        val totalTokens = tokensIn + tokensOut
        val cachedTokens = minOf(cacheReadTokens, tokensIn)
        val uncachedInputTokens = maxOf(tokensIn - cachedTokens, 0L)
        val segmentScale = if (totalTokens > contextWindow) contextWindow.toDouble() / totalTokens else 1.0
        fun segmentWidth(tokens: Long): Double = tokens.toDouble() / contextWindow * segmentScale
        return SegmentWidths(segmentWidth(uncachedInputTokens), segmentWidth(cachedTokens), segmentWidth(tokensOut))
    }

    /**
     * `formatCompactTokens` of agent-context-usage.tsx: ≥1M one decimal + "M", ≥1k one
     * decimal with ".0" dropped + "k", else the localized integer.
     */
    fun formatCompactTokens(value: Long, locale: Locale = Locale.getDefault()): String {
        if (value >= 1_000_000) return "${toFixed1(value / 1_000_000.0)}M"
        if (value >= 1_000) return "${toFixed1(value / 1_000.0).removeSuffix(".0")}k"
        return formatInteger(value, locale)
    }

    /** `toLocaleString()` of an integer count (the sheet's rows, the TalkBack label). */
    fun formatInteger(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    /** `Number.prototype.toFixed(1)`. */
    private fun toFixed1(value: Double): String =
        BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
}
