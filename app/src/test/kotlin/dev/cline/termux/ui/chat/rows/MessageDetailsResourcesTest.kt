package dev.cline.termux.ui.chat.rows

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import dev.cline.termux.data.catalog.ProviderUsageCost
import dev.cline.termux.data.session.TurnStats
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The copy behind A11.9 as the app ships it: the stats line, its spoken form, the usage-cost
 * bundle and the Message details setting come out of the real strings and resources. The
 * answer row has no fork (A16): the whole-session fork is the chat three-dot's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDetailsResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the stats line from the shipped strings`() {
        val strings = TurnStatsStrings.from(context.resources)
        val line = TurnStatsFormat.format(TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120), strings, Locale.US)
        assertEquals("1.2k in · 340 out · $0.0040 · 8.1 s", line)
        assertEquals("2 min 5 s", TurnStatsFormat.duration(125_000, strings, Locale.US))
        assertEquals("1.3m", TurnStatsFormat.tokens(1_250_000, strings, Locale.US))
    }

    @Test
    fun `the spoken stats from the shipped plurals`() {
        val spoken = TurnStatsSpokenStrings.from(context.resources)
        assertEquals(
            "1,234 input tokens, 340 output tokens, cost $0.0040, 8 seconds",
            TurnStatsFormat.spoken(TurnStats(inputTokens = 1_234, outputTokens = 340, totalCost = 0.004, durationMs = 8_120), spoken, Locale.US),
        )
        assertEquals("1 input token, 1 output token, 1 minute 1 second", TurnStatsFormat.spoken(TurnStats(1, 1, durationMs = 61_000), spoken, Locale.US))
    }

    @Test
    fun `the shipped usage-cost bundle hides the subscription providers' cost`() {
        val text = context.resources.openRawResource(R.raw.provider_usage_cost).bufferedReader().use { it.readText() }
        val costs = ProviderUsageCost.parse(text)
        for (id in listOf("openai-codex", "openai-codex-cli", "cline-pass")) assertFalse(id, costs.showsCost(id))
        assertTrue(costs.showsCost("deepseek"))
        assertTrue(costs.showsCost("anthropic"))
    }

    @Test
    fun `settings copy`() {
        assertEquals("Message details", context.getString(R.string.message_details_title))
        assertEquals("Brief", context.getString(R.string.message_details_brief))
        assertEquals("Verbose", context.getString(R.string.message_details_verbose))
        assertEquals("Copy and time under each answer", context.getString(R.string.message_details_brief_supporting))
        assertEquals(
            "Also shows each turn's tokens, cost and duration",
            context.getString(R.string.message_details_verbose_supporting),
        )
    }
}
