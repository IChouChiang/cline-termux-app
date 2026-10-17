package dev.cline.termux.ui.composer

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.model.ModelChoice
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.prefs.ComposerMode
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.LiveProjection
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1.11 chip and first-run projections. */
class ComposerProjectionTest {

    @Test fun sessionModelWinsOverHome() {
        val home = ModelChoice("anthropic", "claude")
        assertEquals(home, ComposerProjection.choice(null, home))
        assertEquals(ModelChoice("openai", "gpt"), ComposerProjection.choice(SessionFacts("s", providerId = "openai", modelId = "gpt"), home))
        assertEquals(home, ComposerProjection.choice(SessionFacts("s"), home))
    }

    @Test fun noChoiceIsAnEnabledEmptyChip() {
        val chip = ComposerProjection.modelChip(null, CatalogState(), busy = true)
        assertTrue(chip.enabled)
        assertNull(chip.providerId)
    }

    @Test fun busyDisablesTheModelChip() {
        val chip = ComposerProjection.modelChip(ModelChoice("p", "m"), CatalogState(), busy = true)
        assertFalse(chip.enabled)
        assertEquals("p", chip.providerName)
        assertEquals("m", chip.modelName)
    }

    @Test fun explicitEffortFromSessionThenRemembered() {
        val remembered = mapOf("p" to ReasoningEffort.High)
        assertEquals(ReasoningEffort.None, ComposerProjection.explicitEffort(SessionFacts("s", thinking = false), "p", remembered))
        assertEquals(ReasoningEffort.High, ComposerProjection.explicitEffort(null, "p", remembered))
        assertNull(ComposerProjection.explicitEffort(null, "q", remembered))
    }

    @Test fun reasoningChipNeedsSupport() {
        assertFalse(ComposerProjection.reasoningChip(null, null).enabled)
        assertTrue(ComposerProjection.reasoningChip(null, null).unsupportedReason)
        assertTrue(ComposerProjection.reasoningChip(ReasoningEffort.High, true).enabled)
    }

    @Test fun providerConfiguredIsUnknownUntilRead() {
        assertNull(ComposerProjection.providerConfigured(CatalogState()))
        assertNull(ComposerProjection.providerConfigured(CatalogState(initialized = true)))
        assertEquals(false, ComposerProjection.providerConfigured(CatalogState(source = CatalogSource.Catalog, initialized = true)))
    }

    @Test fun usageNeedsAnAssistantRowWithTokens() {
        assertNull(ComposerProjection.usage(null, 200_000))
    }

    @Test fun modeMapping() {
        assertEquals(ComposerMode.Plan, PromptMode.Plan.toComposerMode())
        assertEquals(ComposerMode.Act, PromptMode.Yolo.toComposerMode())
        assertEquals(PromptMode.Plan, ComposerMode.Plan.toPromptMode())
    }

    // ---- P4: the composer's session slice ---------------------------------------------------------

    private fun row(id: String, role: Role, at: Long, tokensIn: Int? = null, content: String = "x") =
        ChatMessage(id = id, sessionId = "s", role = role, content = content, createdAt = at, meta = Meta(inputTokens = tokensIn, outputTokens = tokensIn?.let { 10 }))

    private fun state(canonical: List<ChatMessage>, live: List<ChatMessage>) =
        SessionState("s", canonical = canonical, projection = LiveProjection(messages = live), seed = SeedState.Seeded(1))

    private val withTokens: (ChatMessage) -> Boolean = { it.role == Role.Assistant && it.meta.inputTokens != null }

    /** Every shape Reconciler.merge handles gives the same row as a scan of the merged list. */
    @Test fun lastMergedMatchesTheMergedScan() {
        val c1 = row("h:1", Role.User, 1)
        val c2 = row("h:2", Role.Assistant, 2, tokensIn = 100)
        val c3 = row("t:3", Role.Tool, 3)
        val cases = listOf(
            state(emptyList(), emptyList()),
            state(listOf(c1, c2, c3), emptyList()),
            // A live assistant row with tokens is appended after the history.
            state(listOf(c1, c2, c3), listOf(row("local:4", Role.User, 4), row("a:5", Role.Assistant, 5, tokensIn = 200))),
            // A live row without tokens: the history row still wins.
            state(listOf(c1, c2, c3), listOf(row("a:5", Role.Assistant, 5))),
            // A live row replaces the canonical assistant in place (it carries no tokens).
            state(listOf(c1, c2, c3), listOf(row("h:2", Role.Assistant, 2))),
            // A live replacement that carries tokens, followed by an appended plain row.
            state(listOf(c1, c2, c3), listOf(row("h:2", Role.Assistant, 2, tokensIn = 300), row("a:6", Role.Assistant, 6))),
            // Live-only rows slot in by createdAt.
            state(listOf(c1, c2, c3), listOf(row("e:7", Role.Error, 1), row("a:8", Role.Assistant, 8, tokensIn = 400))),
            state(listOf(c1, c2), listOf(row("e:7", Role.Error, 3))),
        )
        for (case in cases) {
            assertEquals(case.messages.lastOrNull(withTokens), ComposerProjection.lastMerged(case, withTokens))
            assertEquals(case.messages.lastOrNull { it.role == Role.User }, ComposerProjection.lastMerged(case) { it.role == Role.User })
        }
        // A predicate that matches a live-only row takes the exact path.
        val errors = state(listOf(c1, c2), listOf(row("e:1", Role.Error, 0), row("a:2", Role.Assistant, 5)))
        val anyRow: (ChatMessage) -> Boolean = { it.role == Role.Error || it.role == Role.User }
        assertEquals(errors.messages.lastOrNull(anyRow), ComposerProjection.lastMerged(errors, anyRow))
    }

    @Test fun usageComesFromTheLatestRowAndTheSessionCost() {
        val s = state(listOf(row("h:1", Role.Assistant, 1, tokensIn = 1_000)), listOf(row("a:2", Role.Assistant, 2, tokensIn = 2_000)))
            .let { it.copy(facts = it.facts.copy(usage = UsageTotals(totalCost = 0.5))) }
        val inputs = ComposerProjection.inputs(s)
        assertEquals(LatestUsage(2_000, 10, 0, null), inputs.latestUsage)
        val usage = ComposerProjection.usage(inputs.latestUsage, inputs.sessionTotalCost, 200_000)!!
        assertEquals(ComposerProjection.usage(s, 200_000), usage)
        assertEquals(0.5, usage.totalCostUsd!!, 0.0)
        assertNull(ComposerProjection.usage(null, 0.5, 200_000))
    }

    /** A streaming delta or a tool chunk changes the state but not the composer's slice. */
    @Test fun streamingLeavesTheInputsEqual() {
        val before = state(listOf(row("h:1", Role.User, 1)), listOf(row("a:2", Role.Assistant, 2, content = "Hel")))
        val after = before.copy(projection = LiveProjection(messages = listOf(row("a:2", Role.Assistant, 2, content = "Hello"))))
        assertEquals(ComposerProjection.inputs(before), ComposerProjection.inputs(after))
        val busy = after.copy(facts = after.facts.copy(runPhase = dev.cline.termux.data.chat.RunPhase.Running))
        assertFalse(ComposerProjection.inputs(after) == ComposerProjection.inputs(busy))
    }

    @Test fun inputsReadTheLastUserModeOnlyOnceSeeded() {
        val user = row("h:1", Role.User, 1, content = "<user_input mode=\"plan\">hi</user_input>")
        assertEquals(PromptMode.Plan, ComposerProjection.inputs(state(listOf(user), emptyList())).lastUserMode)
        assertNull(ComposerProjection.inputs(state(listOf(user), emptyList()).copy(seed = SeedState.Seeding)).lastUserMode)
        assertTrue(ComposerProjection.inputs(state(emptyList(), emptyList())).empty)
        assertFalse(ComposerProjection.inputs(state(listOf(user), emptyList())).empty)
    }

    // ---- i18n-07: the usage bar mirrors -----------------------------------------------------------

    @Test fun usageBarSegmentsRunFromTheStartEdge() {
        val ltr = usageBarSegments(listOf(0.5f, 0.25f, 0.1f), 100f, rtl = false)
        assertEquals(listOf(0f to 50f, 50f to 25f, 75f to 10f), ltr)
        val rtl = usageBarSegments(listOf(0.5f, 0.25f, 0.1f), 100f, rtl = true)
        assertEquals(listOf(50f to 50f, 25f to 25f, 15f to 10f), rtl)
        // Out-of-range fractions are clamped to the track.
        assertEquals(listOf(0f to 100f, 0f to 0f), usageBarSegments(listOf(2f, -1f), 100f, rtl = true))
    }
}
