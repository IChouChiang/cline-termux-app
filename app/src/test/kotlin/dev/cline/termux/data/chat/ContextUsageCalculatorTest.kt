package dev.cline.termux.data.chat

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from sdk/packages/ui/tests/agent-context-usage.test.tsx, plus the CA C1.12 clamp cases. */
class ContextUsageCalculatorTest {
    private val usage = ContextUsageData(tokensIn = 1_000, tokensOut = 500, cacheReadTokens = 250, contextWindow = 2_000)
    private fun compute(data: ContextUsageData) = ContextUsageCalculator.compute(data, Locale.US)

    @Test fun `hides the meter until context and token usage exist`() {
        assertNull(compute(usage.copy(contextWindow = null)))
        assertNull(compute(usage.copy(contextWindow = 0)))
        assertNull(compute(usage.copy(tokensIn = 0, tokensOut = 0)))
    }

    @Test fun `preserves the desktop warning thresholds`() {
        assertEquals(ContextUsageTone.Normal, compute(usage.copy(tokensIn = 499, tokensOut = 0, contextWindow = 1_000))!!.tone)
        assertEquals(ContextUsageTone.Warning, compute(usage.copy(tokensIn = 500, tokensOut = 0, contextWindow = 1_000))!!.tone)
        assertEquals(ContextUsageTone.Critical, compute(usage.copy(tokensIn = 750, tokensOut = 0, contextWindow = 1_000))!!.tone)
    }

    @Test fun `uses current input plus output for the ring`() {
        val result = compute(usage)!!
        assertEquals(1_500L, result.totalTokens)
        assertEquals(75, result.percent)
        assertEquals("1.5k / 2k (75%)", result.valueLabel)
        assertEquals("1,500", ContextUsageCalculator.formatInteger(result.totalTokens, Locale.US))
        val widths = ContextUsageCalculator.segmentWidths(1_000, 500, 250, 2_000)
        assertEquals(0.375, widths.uncachedInput, 1e-12)
        assertEquals(0.125, widths.cachedInput, 1e-12)
        assertEquals(0.25, widths.output, 1e-12)
    }

    @Test fun `caps the ring and scales segments when a model has a smaller context window`() {
        val result = compute(usage.copy(contextWindow = 750, cacheReadTokens = 2_000))!!
        assertEquals(100, result.percent)
        assertEquals(1.0, result.ratio, 0.0)
        // Display the reported cache value, but never draw it beyond input usage.
        assertEquals(2_000L, result.cacheReadTokens)
        assertEquals(1_000L, result.cachedTokens)
        assertEquals(0L, result.uncachedInputTokens)
        assertEquals(0f, result.uncachedInputFraction, 0f)
        val widths = ContextUsageCalculator.segmentWidths(1_000, 500, 2_000, 750)
        assertEquals(0.0, widths.uncachedInput, 0.0)
        assertEquals(0.6666666666666666, widths.cachedInput, 1e-15)
        assertEquals(0.3333333333333333, widths.output, 1e-15)
        assertEquals(1.0, widths.uncachedInput + widths.cachedInput + widths.output, 1e-12)
    }

    @Test fun `supports output-only usage and keeps the established million-token label`() {
        val outputOnly = compute(usage.copy(tokensIn = 0, tokensOut = 500))!!
        assertEquals(25, outputOnly.percent)
        assertEquals("1.5k / 1.0M (0%)", compute(usage.copy(contextWindow = 1_000_000))!!.valueLabel)
    }

    @Test fun `cache reads above input clamp the cached share`() {
        val result = compute(ContextUsageData(tokensIn = 100, tokensOut = 0, cacheReadTokens = 400, contextWindow = 1_000))!!
        assertEquals(100L, result.cachedTokens)
        assertEquals(0L, result.uncachedInputTokens)
        assertEquals(0.1f, result.cachedInputFraction, 1e-6f)
    }

    @Test fun `formats compact tokens like the desktop meter`() {
        assertEquals("999", ContextUsageCalculator.formatCompactTokens(999, Locale.US))
        assertEquals("12.3k", ContextUsageCalculator.formatCompactTokens(12_340, Locale.US))
        assertEquals("200k", ContextUsageCalculator.formatCompactTokens(200_000, Locale.US))
        assertEquals("1.2M", ContextUsageCalculator.formatCompactTokens(1_200_000, Locale.US))
    }
}
