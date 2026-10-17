package dev.cline.termux.ui.kernel

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(shownAt: Long? = null) : ReliabilityStore {
        val confirmedFlow = MutableStateFlow<Set<ReliabilityItemId>>(emptySet())
        var shownAt: Long? = shownAt
        var marks = 0
        override val confirmed: Flow<Set<ReliabilityItemId>> = confirmedFlow
        override suspend fun setConfirmed(id: ReliabilityItemId, confirmed: Boolean) {
            confirmedFlow.value = if (confirmed) confirmedFlow.value + id else confirmedFlow.value - id
        }
        override suspend fun onboardingShownAt(): Long? = shownAt
        override suspend fun markOnboardingShown() {
            marks++
            shownAt = 1L
        }
    }

    private var readings = SystemReadings(
        sdkInt = 34, samsung = true, termuxUnrestricted = false, clineUnrestricted = true,
        phantomProcs = PhantomSetting.Value("false"), developerOptionsOn = true, notificationsOn = true, canRequestIgnoreBattery = false,
    )

    private fun vm(
        store: FakeStore,
        variant: ReliabilityVariant,
        io: TestDispatcher,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = ReliabilityViewModel(saved, variant, ReliabilityChecks({ readings }, store), io)

    @Test
    fun `onboarding shows the step when a verifiable item is missing`() = runTest(main) {
        val store = FakeStore()
        val vm = vm(store, ReliabilityVariant.Onboarding, main)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.skipOnboarding)
        assertEquals(0, store.marks)
        assertTrue(vm.state.value.loaded)
        assertEquals(setOf(ReliabilityItemId.TermuxBattery), vm.state.value.expanded)
        assertEquals(3, vm.state.value.doneCount)
        assertEquals(5, vm.state.value.countedTotal)
    }

    @Test
    fun `onboarding skips once shown and still writes the mark`() = runTest(main) {
        val store = FakeStore(shownAt = 42L)
        val vm = vm(store, ReliabilityVariant.Onboarding, main)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.skipOnboarding)
        assertEquals(1, store.marks)
    }

    @Test
    fun `onboarding skips when nothing verifiable is missing`() = runTest(main) {
        readings = readings.copy(termuxUnrestricted = true, phantomProcs = PhantomSetting.Value(null))
        val store = FakeStore()
        val vm = vm(store, ReliabilityVariant.Onboarding, main)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.skipOnboarding)
        assertEquals(1, store.marks)
    }

    @Test
    fun `the skip decision is restored from saved state without re-reading`() = runTest(main) {
        val store = FakeStore(shownAt = 42L)
        val vm = vm(store, ReliabilityVariant.Onboarding, main, SavedStateHandle(mapOf("reliability.skipOnboarding" to false)))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.skipOnboarding)
        assertEquals(0, store.marks)
    }

    @Test
    fun `the standalone page never decides a skip`() = runTest(main) {
        val vm = vm(FakeStore(shownAt = 42L), ReliabilityVariant.Standalone, main)
        advanceUntilIdle()
        assertNull(vm.state.value.skipOnboarding)
    }

    @Test
    fun `a toggled item keeps its state across refreshes and process death`() = runTest(main) {
        val saved = SavedStateHandle()
        val store = FakeStore()
        val vm = vm(store, ReliabilityVariant.Standalone, main, saved)
        advanceUntilIdle()
        vm.toggle(ReliabilityItemId.TermuxBattery)
        vm.toggle(ReliabilityItemId.Notifications)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.Notifications), vm.state.value.expanded)

        val restored = vm(store, ReliabilityVariant.Standalone, main, saved)
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.Notifications), restored.state.value.expanded)
    }

    @Test
    fun `an item the user opened collapses once it is done, and the toggle is forgotten`() = runTest(main) {
        // Trial row 14: Notifications opened by hand, then "Allow" granted in the dialog.
        val saved = SavedStateHandle()
        val store = FakeStore()
        readings = readings.copy(notificationsOn = false)
        val vm = vm(store, ReliabilityVariant.Standalone, main, saved)
        advanceUntilIdle()
        vm.toggle(ReliabilityItemId.Notifications)
        assertEquals(setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.Notifications), vm.state.value.expanded)

        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
        readings = readings.copy(notificationsOn = true)
        assertEquals(NotificationStep.None, vm.onNotificationsResult(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.item(ReliabilityItemId.Notifications)!!.done)
        assertEquals("collapsed like the other done items", setOf(ReliabilityItemId.TermuxBattery), vm.state.value.expanded)

        // The resume after the dialog reads it again; a recreated page does not reopen it either.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.TermuxBattery), vm.state.value.expanded)
        val restored = vm(store, ReliabilityVariant.Standalone, main, saved)
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.TermuxBattery), restored.state.value.expanded)
    }

    @Test
    fun `any item the user opened collapses once done, outside the app or by its checkbox`() = runTest(main) {
        readings = readings.copy(clineUnrestricted = false)
        val vm = vm(FakeStore(), ReliabilityVariant.Standalone, main)
        advanceUntilIdle()
        vm.toggle(ReliabilityItemId.ClineBattery)
        vm.toggle(ReliabilityItemId.TermuxOverlay)
        assertEquals(setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.ClineBattery, ReliabilityItemId.TermuxOverlay), vm.state.value.expanded)

        // Allowed in the system dialog: the resume reads it.
        readings = readings.copy(clineUnrestricted = true)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.TermuxOverlay), vm.state.value.expanded)

        // "I've done this" on the optional overlay item.
        vm.setConfirmed(ReliabilityItemId.TermuxOverlay, true)
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.TermuxBattery), vm.state.value.expanded)

        // A done item the user opens again stays open, e.g. to undo the checkbox.
        vm.toggle(ReliabilityItemId.TermuxOverlay)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.TermuxOverlay), vm.state.value.expanded)
    }

    @Test
    fun `refresh picks up a setting changed outside the app`() = runTest(main) {
        val vm = vm(FakeStore(), ReliabilityVariant.Standalone, main)
        advanceUntilIdle()
        assertFalse(vm.state.value.item(ReliabilityItemId.TermuxBattery)!!.done)
        readings = readings.copy(termuxUnrestricted = true)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.item(ReliabilityItemId.TermuxBattery)!!.done)
        assertEquals(4, vm.state.value.doneCount)
    }

    @Test
    fun `confirming the last self-check completes the list and collapses everything`() = runTest(main) {
        readings = readings.copy(termuxUnrestricted = true)
        val store = FakeStore()
        val vm = vm(store, ReliabilityVariant.Standalone, main)
        advanceUntilIdle()
        assertFalse(vm.state.value.allDone)
        assertEquals(setOf(ReliabilityItemId.NeverSleeping), vm.state.value.expanded)

        vm.setConfirmed(ReliabilityItemId.NeverSleeping, true)
        advanceUntilIdle()
        assertEquals(setOf(ReliabilityItemId.NeverSleeping), store.confirmedFlow.value)
        assertTrue(vm.state.value.allDone)
        assertEquals(5, vm.state.value.doneCount)
        assertTrue(vm.state.value.expanded.isEmpty())
    }

    @Test
    fun `a throwing probe reads every verifiable item as not set`() = runTest(main) {
        val vm = ReliabilityViewModel(SavedStateHandle(), ReliabilityVariant.Standalone, ReliabilityChecks({ error("no power service") }, FakeStore()), main)
        advanceUntilIdle()
        assertFalse(vm.state.value.item(ReliabilityItemId.TermuxBattery)!!.done)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun `markShown writes the onboarding mark`() = runTest(main) {
        val store = FakeStore()
        val vm = vm(store, ReliabilityVariant.Onboarding, main)
        advanceUntilIdle()
        vm.markShown()
        advanceUntilIdle()
        assertEquals(1, store.marks)
    }

    // ---- B5: the notification request -----------------------------------------------------------

    /** The request clock (`elapsedMs`), advanced by [answer]. */
    private var now = 10_000L

    private fun notificationsVm(saved: SavedStateHandle = SavedStateHandle()): ReliabilityViewModel {
        readings = readings.copy(notificationsOn = false)
        return ReliabilityViewModel(saved, ReliabilityVariant.Standalone, ReliabilityChecks({ readings }, FakeStore()), main, elapsedMs = { now })
    }

    /** The system answers the request [afterMs] after the launch (a dialog the user read and answered by default). */
    private fun ReliabilityViewModel.answer(granted: Boolean, rationale: Boolean, afterMs: Long = 2_000): NotificationStep {
        now += afterMs
        return onNotificationsResult(granted, rationale)
    }

    @Test
    fun `the first tap on Allow asks`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a dismissed dialog asks again on the next tap`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
        assertEquals(NotificationStep.None, vm.answer(granted = false, rationale = false))
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `one denial asks again on the next tap`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        assertEquals(NotificationStep.None, vm.answer(granted = false, rationale = true))
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a result without a dialog opens the notification settings, then and on later taps`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        assertEquals(NotificationStep.OpenSettings, vm.answer(granted = false, rationale = false, afterMs = 60))
        assertEquals(NotificationStep.OpenSettings, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a slow result is a dialog even at the threshold`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        assertEquals(NotificationStep.None, vm.answer(granted = false, rationale = false, afterMs = NotificationRequestRules.INSTANT_RESULT_MS))
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a second denial opens the settings on the next tap`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        vm.answer(granted = false, rationale = true)
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
        // Android stops asking after the second denial: the rationale is gone, the dialog was shown.
        assertEquals(NotificationStep.None, vm.answer(granted = false, rationale = false))
        assertEquals(NotificationStep.OpenSettings, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a grant in the dialog clears what was learned`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        vm.answer(granted = false, rationale = true)
        vm.onNotificationsTapped(granted = false)
        readings = readings.copy(notificationsOn = true)
        assertEquals(NotificationStep.None, vm.answer(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.item(ReliabilityItemId.Notifications)!!.done)
        // Revoked later: a single denial is not the second one.
        readings = readings.copy(notificationsOn = false)
        vm.onNotificationsTapped(granted = false)
        assertEquals(NotificationStep.None, vm.answer(granted = false, rationale = false))
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `a grant in the settings clears a known permanent denial`() = runTest(main) {
        val vm = notificationsVm()
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        assertEquals(NotificationStep.OpenSettings, vm.answer(granted = false, rationale = false, afterMs = 30))
        // The user allows notifications there, comes back (ON_RESUME refresh), and revokes them later.
        readings = readings.copy(notificationsOn = true)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(NotificationStep.None, vm.onNotificationsTapped(granted = true))
        readings = readings.copy(notificationsOn = false)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(NotificationStep.Request, vm.onNotificationsTapped(granted = false))
    }

    @Test
    fun `what was learned survives process death, a request in flight does not`() = runTest(main) {
        val saved = SavedStateHandle()
        val vm = notificationsVm(saved)
        advanceUntilIdle()
        vm.onNotificationsTapped(granted = false)
        vm.answer(granted = false, rationale = true)
        vm.onNotificationsTapped(granted = false)

        // Recreated while the dialog was up: no launch time, so the result counts as a dialog shown.
        val restored = notificationsVm(saved)
        advanceUntilIdle()
        assertEquals(NotificationStep.None, restored.onNotificationsResult(granted = false, rationale = false))
        assertEquals("the second denial was still recognised", NotificationStep.OpenSettings, restored.onNotificationsTapped(granted = false))
        assertEquals(NotificationStep.OpenSettings, notificationsVm(saved).onNotificationsTapped(granted = false))
    }
}
