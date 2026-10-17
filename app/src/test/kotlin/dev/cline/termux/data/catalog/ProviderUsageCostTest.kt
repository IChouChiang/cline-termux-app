package dev.cline.termux.data.catalog

import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.TurnRecords
import dev.cline.termux.data.session.TurnStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11.9: the Verbose stats leave out the cost wherever the terminal's verbose line does
 * (`shouldShowCliUsageCost` → `@cline/llms` `shouldShowProviderUsageCost`: only "show" shows).
 */
class ProviderUsageCostTest {
    private val bundle = ProviderUsageCost.parse(
        """{"schema":1,"generatedAt":"x","providers":{"openai-codex":"subscription","cline-pass":"subscription","quiet":"hide","odd":"sometimes"}}""",
    )

    @Test
    fun `subscription and hidden providers show no cost, every other provider does`() {
        assertFalse(bundle.showsCost("openai-codex"))
        assertFalse(bundle.showsCost(" cline-pass "))
        assertFalse("hide is not show either", bundle.showsCost("quiet"))
        assertTrue(bundle.showsCost("deepseek"))
        assertTrue("an unknown value is dropped, so show", bundle.showsCost("odd"))
        assertTrue(bundle.showsCost(null))
        assertTrue(bundle.showsCost(""))
        assertEquals("subscription", bundle.display("openai-codex"))
        assertEquals("show", bundle.display("anthropic"))
        assertTrue(ProviderUsageCost.EMPTY.showsCost("openai-codex"))
    }

    @Test
    fun `aliases resolve like the kernel's provider ids`() {
        val costs = ProviderUsageCost.parse("""{"providers":{"openai-native":"hide"}}""")
        assertFalse(costs.showsCost("openai"))
    }

    @Test
    fun `a live catalog row that carries the value wins over the bundle`() {
        val live = bundle.withLive(
            listOf(
                ProviderListItem("openai-codex", usageCostDisplay = "show"),
                ProviderListItem("deepseek", usageCostDisplay = "subscription"),
                ProviderListItem("anthropic"),
                ProviderListItem("bad", usageCostDisplay = "nope"),
            ),
        )
        assertTrue(live.showsCost("openai-codex"))
        assertFalse(live.showsCost("deepseek"))
        assertTrue(live.showsCost("anthropic"))
        assertTrue(live.showsCost("bad"))
        assertFalse("the bundle's other rows stay", live.showsCost("cline-pass"))
        assertSame("nothing live: the same policy", bundle, bundle.withLive(listOf(ProviderListItem("anthropic"))))
        assertEquals("equal by value (the transcript memo compares it)", bundle, ProviderUsageCost.parse("""{"providers":{"openai-codex":"subscription","cline-pass":"subscription","quiet":"hide"}}"""))
    }

    @Test
    fun `statsByAnswer drops the cost of an openai-codex turn and keeps a deepseek one`() {
        val stats = TurnStats(inputTokens = 1_000, outputTokens = 200, totalCost = 0.02, durationMs = 4_000)
        val astra = TurnRecord(requestId = "r1", sessionId = "s1", sentAt = 1, open = false, providerId = "openai-codex", stats = stats, answerRenderKey = "k1", answerMessageId = "h:a1:0")
        val deepseek = TurnRecord(requestId = "r2", sessionId = "s1", sentAt = 2, open = false, providerId = "deepseek", stats = stats, answerRenderKey = "k2")
        val unknownProvider = TurnRecord(requestId = "r3", sessionId = "s1", sentAt = 3, open = false, stats = stats, answerRenderKey = "k3")
        val map = TurnRecords.statsByAnswer(listOf(astra, deepseek, unknownProvider), sessionProviderId = "openai-codex", showsCost = bundle::showsCost)
        assertNull(map.getValue("k1").totalCost)
        assertNull("the history id gets the same stats", map.getValue("h:a1:0").totalCost)
        assertEquals(1_000L, map.getValue("k1").inputTokens)
        assertEquals(0.02, map.getValue("k2").totalCost!!, 0.0)
        assertNull("no provider on the turn: the session's", map.getValue("k3").totalCost)
        assertEquals("the default shows every cost", 0.02, TurnRecords.statsByAnswer(listOf(astra)).getValue("k1").totalCost!!, 0.0)
    }
}
