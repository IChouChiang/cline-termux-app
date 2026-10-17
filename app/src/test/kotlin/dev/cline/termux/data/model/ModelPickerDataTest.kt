package dev.cline.termux.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from apps/examples/desktop-app/webview/lib/featured-models.test.ts (every case) and model-selection storage. */
class ModelPickerDataTest {
    private fun model(id: String, name: String? = null, featured: FeaturedInfo? = null, description: String? = null) =
        PickerModel(id = id, name = name ?: id, featured = featured, description = description)

    private fun tier(tier: FeaturedTier, rank: Int = 0, vararg tags: String) = FeaturedInfo(tier, rank, tags.toList())

    @Test fun `builds recommended, free and all sections for the cline provider`() {
        val data = buildModelPickerData(
            "cline",
            listOf(
                model("zzz/last-model", "ZZZ Last"),
                model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", tier(FeaturedTier.Free), "Fast and efficient"),
                model("anthropic/claude-opus-5", "Claude Opus 5", tier(FeaturedTier.Recommended, 0, "NEW"), "Most intelligent model"),
                model("aaa/first-model", "AAA First"),
            ),
        )
        assertEquals(listOf("recommended", "free", "all"), data.sections?.map { it.id })
        assertEquals(listOf("anthropic/claude-opus-5", "deepseek/deepseek-v4-flash", "aaa/first-model", "zzz/last-model"), data.options.map { it.value })
        assertEquals(PickerOption("Claude Opus 5", "anthropic/claude-opus-5", "recommended", "Most intelligent model", "NEW"), data.options[0])
        assertNull(data.options[1].badge)
        assertEquals("free", data.options[1].section)
        assertEquals("AAA First", data.options[2].label)
        assertEquals(FREE_SECTION_DESCRIPTION, data.sections!![1].description)
    }

    @Test fun `orders featured tiers by feed rank, not list order`() {
        val data = buildModelPickerData(
            "cline",
            listOf(
                model("openai/gpt-5.6-sol", "GPT-5.6 Sol", tier(FeaturedTier.Recommended, 1)),
                model("moonshotai/kimi-k3", "Kimi K3", tier(FeaturedTier.Recommended, 0, "NEW")),
            ),
        )
        assertEquals(listOf("Kimi K3", "GPT-5.6 Sol"), data.options.map { it.label })
    }

    @Test fun `builds subscribed and free sections for cline-pass and hides stale catalog leftovers`() {
        val data = buildModelPickerData(
            "cline-pass",
            listOf(
                model("cline-pass/kimi-k3", "Kimi K3", tier(FeaturedTier.Subscribed)),
                model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", tier(FeaturedTier.Free)),
                model("nvidia/nemotron-ultra", "Nemotron Ultra"),
            ),
        )
        assertEquals(listOf("subscribed", "free"), data.sections?.map { it.id })
        assertEquals(listOf("subscribed", "free"), data.options.map { it.section })
        assertFalse(data.options.any { it.value == "nvidia/nemotron-ultra" })
    }

    @Test fun `keeps subscription and free fallback models separated with a partial feed`() {
        val data = buildModelPickerData(
            "cline-pass",
            listOf(
                model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", tier(FeaturedTier.Free)),
                model("cline-pass/kimi-k3", "Kimi K3"),
                model("nvidia/nemotron-ultra", "Nemotron Ultra"),
            ),
        )
        assertEquals(listOf("subscribed", "free"), data.sections?.map { it.id })
        assertEquals(
            listOf("cline-pass/kimi-k3" to "subscribed", "deepseek/deepseek-v4-flash" to "free", "nvidia/nemotron-ultra" to "free"),
            data.options.map { it.value to it.section },
        )
    }

    @Test fun `groups the offline ClinePass catalog without confusing identical model names`() {
        val data = buildModelPickerData(
            "cline-pass",
            listOf(
                model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash"),
                model("cline-pass/glm-5.3-flash", "GLM-5.3-Flash"),
                model("cline-free/longcat-2.0", "LongCat 2.0 (free)"),
                model("cline-pass/deepseek-v4-flash", "DeepSeek V4 Flash"),
                model("z-ai/glm-5.3-flash", "GLM-5.3-Flash"),
            ),
        )
        assertEquals(listOf("Subscribed", "Free"), data.sections?.map { it.label })
        assertEquals(
            listOf(
                "cline-pass/deepseek-v4-flash" to "subscribed",
                "cline-pass/glm-5.3-flash" to "subscribed",
                "deepseek/deepseek-v4-flash" to "free",
                "z-ai/glm-5.3-flash" to "free",
                "cline-free/longcat-2.0" to "free",
            ),
            data.options.map { it.value to it.section },
        )
        assertEquals("DeepSeek V4 Flash", data.options[0].label)
        assertTrue(data.options.drop(2).all { it.badge == null })
        assertEquals(CLINE_PASS_FREE_SECTION_DESCRIPTION, data.sections!![1].description)
    }

    @Test fun `falls back to a flat name-sorted list when nothing is featured`() {
        val data = buildModelPickerData("cline", listOf(model("zzz/last", "ZZZ"), model("aaa/first", "AAA")))
        assertNull(data.sections)
        assertEquals(listOf("AAA", "ZZZ"), data.options.map { it.label })
        assertNull(data.options[0].section)
    }

    @Test fun `distinguishes colliding names in the fallback without hiding model routes`() {
        for (providerId in listOf("cline", "other-provider")) {
            val models = listOf(
                model("cline-pass/deepseek-v4-flash", "DeepSeek V4 Flash"),
                model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash"),
                model("cline-pass/glm-5.3-flash", "GLM-5.3-Flash"),
                model("z-ai/glm-5.3-flash", "GLM-5.3-Flash"),
                model("cline-pass/kimi-k3", "Kimi K3"),
            )
            val options = buildModelPickerData(providerId, models).options
            assertEquals(models.size, options.size)
            assertEquals(models.size, options.map { it.label }.toSet().size)
            for (entry in models.take(4)) assertTrue(PickerOption("${entry.name} (${entry.id})", entry.id) in options)
            assertTrue(PickerOption("Kimi K3", "cline-pass/kimi-k3") in options)
        }
    }

    @Test fun `renders other providers as a flat list with display names`() {
        val data = buildModelPickerData("anthropic", listOf(model("claude-sonnet-4-6", "Claude Sonnet 4.6")))
        assertNull(data.sections)
        assertEquals(listOf(PickerOption("Claude Sonnet 4.6", "claude-sonnet-4-6")), data.options)
    }

    // S8.2 additions

    @Test fun `appends a selected model outside the offer under Current model`() {
        val data = buildModelPickerData("cline", listOf(model("a/x", "X", tier(FeaturedTier.Recommended)))).withCurrentModel("custom/model")
        assertEquals(CURRENT_MODEL_SECTION_ID, data.options.last().section)
        assertEquals("Current model", data.sections!!.last().label)
        val unchanged = buildModelPickerData("anthropic", listOf(model("m"))).withCurrentModel("m")
        assertEquals(1, unchanged.options.size)
    }

    @Test fun `appends a selected model to a flat list as an ordinary row without a header`() {
        val data = buildModelPickerData("openai", listOf(model("gpt-4o", "GPT-4o"))).withCurrentModel("mock-1")
        assertNull(data.sections)
        assertEquals(listOf(PickerOption("GPT-4o", "gpt-4o"), PickerOption("mock-1", "mock-1")), data.options)
        assertNull(data.options.last().section)
    }

    @Test fun `labels the appended model with the provider's model details name`() {
        val sectioned = buildModelPickerData("cline", listOf(model("a/x", "X", tier(FeaturedTier.Recommended))))
            .withCurrentModel("custom/model", "  Custom Model ")
        assertEquals(PickerOption("Custom Model", "custom/model", section = CURRENT_MODEL_SECTION_ID), sectioned.options.last())
        val blank = buildModelPickerData("openai", listOf(model("gpt-4o"))).withCurrentModel("mock-1", " ")
        assertEquals("mock-1", blank.options.last().label)
    }

    @Test fun `search matches label and id, and offers a free-text id`() {
        val data = buildModelPickerData("anthropic", listOf(model("claude-sonnet-4-6", "Claude Sonnet 4.6", description = "Balanced")))
        assertTrue(data.options[0].matches("SONNET"))
        assertTrue(data.options[0].matches("4-6"))
        assertFalse(data.options[0].matches("opus"))
        assertEquals("my/model-1", data.freeTextModelId(" my/model-1 "))
        assertNull(data.freeTextModelId("two words"))
        assertNull(data.freeTextModelId("sonnet"))
    }

    @Test fun `capability badges prefer modalities over the vision flag`() {
        assertEquals(listOf(CapabilityBadge.Images, CapabilityBadge.Reasoning), PickerModel("m", inputModalities = listOf("text", "image"), supportsReasoning = true).capabilityBadges())
        assertEquals(emptyList<CapabilityBadge>(), PickerModel("m", inputModalities = listOf("text"), supportsVision = true).capabilityBadges())
        assertEquals(listOf(CapabilityBadge.Images), PickerModel("m", supportsVision = true).capabilityBadges())
    }

    // model-selection.ts

    @Test fun `reasoning levels parse by their wire value and default to Low`() {
        assertEquals(ReasoningEffort.High, ReasoningEffort.parse("high"))
        assertEquals(ReasoningEffort.XHigh, ReasoningEffort.parse("xhigh"))
        assertNull(ReasoningEffort.parse("ultra"))
        assertNull(ReasoningEffort.parse(null))
        assertEquals(ReasoningEffort.Low, ReasoningEffort.DEFAULT)
    }
}
