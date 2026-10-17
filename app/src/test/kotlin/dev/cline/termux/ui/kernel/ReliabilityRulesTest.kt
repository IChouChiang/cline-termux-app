package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The D.6 table, the counting rule and D.4 (onboarding-kernel.md D). */
class ReliabilityRulesTest {
    private fun readings(
        sdk: Int = 34,
        samsung: Boolean = true,
        termux: Boolean = true,
        cline: Boolean = true,
        phantom: PhantomSetting = PhantomSetting.Value("false"),
        developer: Boolean = true,
        notifications: Boolean = true,
        canRequest: Boolean = false,
    ) = SystemReadings(sdk, samsung, termux, cline, phantom, developer, notifications, canRequest)

    private fun ids(items: List<ReliabilityItemUi>) = items.map { it.id }

    @Test
    fun `samsung on API 34 shows all six items in D order`() {
        val items = ReliabilityRules.items(readings(), confirmed = setOf(ReliabilityItemId.NeverSleeping))
        assertEquals(ReliabilityItemId.entries.toList(), ids(items))
        assertEquals(
            listOf(ReliabilityGroup.Required, ReliabilityGroup.Required, ReliabilityGroup.Required, ReliabilityGroup.Recommended, ReliabilityGroup.Recommended, ReliabilityGroup.Optional),
            items.map { it.group },
        )
        assertEquals(ReliabilitySummary(doneCount = 5, countedTotal = 5, requiredMissing = false), ReliabilityRules.summary(items))
        assertTrue(ReliabilityRules.allDone(items))
        assertFalse(ReliabilityRules.verifiableMissing(items))
        assertNull(ReliabilityRules.firstOpen(items.filter { it.group != ReliabilityGroup.Optional }))
    }

    @Test
    fun `non-samsung below API 31 shows only the battery items and the overlay`() {
        val items = ReliabilityRules.items(readings(sdk = 30, samsung = false), emptySet())
        assertEquals(listOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.ClineBattery, ReliabilityItemId.TermuxOverlay), ids(items))
        assertEquals(2, ReliabilityRules.summary(items).countedTotal)
    }

    @Test
    fun `API 31 to 33 offers only the adb command for child processes and has no notification item`() {
        val items = ReliabilityRules.items(readings(sdk = 32, samsung = false, phantom = PhantomSetting.Value(null)), emptySet())
        val child = items.single { it.id == ReliabilityItemId.ChildProcess }
        assertTrue(child.legacyAdbOnly)
        assertFalse(child.developerOptionsOff)
        assertEquals(ItemStatus.NotSet, child.status)
        assertFalse(ReliabilityItemId.Notifications in ids(items))
    }

    @Test
    fun `API 34 with developer options off flags the child process item`() {
        val items = ReliabilityRules.items(readings(developer = false, phantom = PhantomSetting.Value("true")), emptySet())
        val child = items.single { it.id == ReliabilityItemId.ChildProcess }
        assertTrue(child.developerOptionsOff)
        assertFalse(child.legacyAdbOnly)
        assertEquals(ItemStatus.NotSet, child.status)
    }

    @Test
    fun `an unreadable phantom setting becomes a self-check that counts once confirmed`() {
        val unconfirmed = ReliabilityRules.items(readings(phantom = PhantomSetting.Unreadable), setOf(ReliabilityItemId.NeverSleeping))
        assertEquals(ItemStatus.SelfCheck, unconfirmed.single { it.id == ReliabilityItemId.ChildProcess }.status)
        assertEquals(4, ReliabilityRules.summary(unconfirmed).doneCount)

        val confirmed = ReliabilityRules.items(
            readings(phantom = PhantomSetting.Unreadable),
            setOf(ReliabilityItemId.NeverSleeping, ReliabilityItemId.ChildProcess),
        )
        assertEquals(ItemStatus.SelfConfirmed, confirmed.single { it.id == ReliabilityItemId.ChildProcess }.status)
        assertEquals(5, ReliabilityRules.summary(confirmed).doneCount)
    }

    @Test
    fun `the optional overlay item is never counted`() {
        val without = ReliabilityRules.summary(ReliabilityRules.items(readings(), setOf(ReliabilityItemId.NeverSleeping)))
        val with = ReliabilityRules.summary(ReliabilityRules.items(readings(), setOf(ReliabilityItemId.NeverSleeping, ReliabilityItemId.TermuxOverlay)))
        assertEquals(without, with)
    }

    @Test
    fun `an unconfirmed never-sleeping item is a missing required item`() {
        val items = ReliabilityRules.items(readings(), emptySet())
        val summary = ReliabilityRules.summary(items)
        assertTrue(summary.requiredMissing)
        assertEquals(4, summary.doneCount)
        assertFalse(ReliabilityRules.allDone(items))
        assertEquals(ReliabilityItemId.NeverSleeping, ReliabilityRules.firstOpen(items))
    }

    @Test
    fun `first open is the first item not done`() {
        val items = ReliabilityRules.items(readings(termux = false, cline = false), emptySet())
        assertEquals(ReliabilityItemId.TermuxBattery, ReliabilityRules.firstOpen(items))
    }

    @Test
    fun `D4 only verifiable items decide whether onboarding shows the step`() {
        // Item 4 off and item 3 unconfirmed: not verifiable, so the step is skipped.
        assertFalse(ReliabilityRules.verifiableMissing(ReliabilityRules.items(readings(phantom = PhantomSetting.Value(null)), emptySet())))
        assertTrue(ReliabilityRules.verifiableMissing(ReliabilityRules.items(readings(notifications = false), emptySet())))
        assertTrue(ReliabilityRules.verifiableMissing(ReliabilityRules.items(readings(cline = false), emptySet())))
    }

    @Test
    fun `item 2 requests the system dialog only when the permission is declared`() {
        assertFalse(ReliabilityRules.items(readings(), emptySet()).single { it.id == ReliabilityItemId.ClineBattery }.directRequest)
        assertTrue(ReliabilityRules.items(readings(canRequest = true), emptySet()).single { it.id == ReliabilityItemId.ClineBattery }.directRequest)
    }

    @Test
    fun `newly done are the shown items that turned done since the last read`() {
        val before = ReliabilityRules.items(readings(termux = false, notifications = false, phantom = PhantomSetting.Unreadable), emptySet())
        val after = ReliabilityRules.items(readings(notifications = true, phantom = PhantomSetting.Unreadable), setOf(ReliabilityItemId.ChildProcess))
        assertEquals(
            setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.ChildProcess, ReliabilityItemId.Notifications),
            ReliabilityRules.newlyDone(before, after),
        )
        assertEquals("nothing on the first read", emptySet<ReliabilityItemId>(), ReliabilityRules.newlyDone(emptyList(), after))
        assertEquals("done already is not newly done", emptySet<ReliabilityItemId>(), ReliabilityRules.newlyDone(after, after))
        assertEquals("turned off again", emptySet<ReliabilityItemId>(), ReliabilityRules.newlyDone(after, before))
    }

    @Test
    fun `notifications once on have nothing to allow and nothing to expand into`() {
        // Trial row 14: "Allow" did nothing once the permission was granted.
        val on = ReliabilityRules.items(readings(notifications = true), emptySet()).single { it.id == ReliabilityItemId.Notifications }
        assertNull(on.actionLabel())
        assertFalse(on.hasDetails())
        val off = ReliabilityRules.items(readings(notifications = false), emptySet()).single { it.id == ReliabilityItemId.Notifications }
        assertEquals(R.string.reliability_allow, off.actionLabel())
        assertTrue(off.hasDetails())
    }

    @Test
    fun `every other item keeps something to expand into, done or not`() {
        val cases = listOf(
            readings(),
            readings(termux = false, cline = false, phantom = PhantomSetting.Value(null), developer = false, notifications = false, canRequest = true),
            readings(sdk = 32, phantom = PhantomSetting.Value(null)),
            readings(phantom = PhantomSetting.Unreadable),
        )
        val selfChecks = setOf(ReliabilityItemId.NeverSleeping, ReliabilityItemId.ChildProcess, ReliabilityItemId.TermuxOverlay)
        for (r in cases) {
            for (confirmed in listOf(emptySet(), selfChecks)) {
                ReliabilityRules.items(r, confirmed).filterNot { it.id == ReliabilityItemId.Notifications }.forEach { item ->
                    assertTrue("$item", item.hasDetails())
                }
            }
        }
    }

    @Test
    fun `expansion overrides survive the saved-state round trip and ignore garbage`() {
        val map = mapOf(ReliabilityItemId.TermuxBattery to false, ReliabilityItemId.Notifications to true)
        assertEquals(map, ReliabilityViewModel.decodeOverrides(ReliabilityViewModel.encodeOverrides(map)))
        assertEquals(
            mapOf(ReliabilityItemId.ChildProcess to true),
            ReliabilityViewModel.decodeOverrides("ChildProcess=true,Nope=true,broken,,=false"),
        )
    }
}
