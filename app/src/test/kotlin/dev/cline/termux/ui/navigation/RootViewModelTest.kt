package dev.cline.termux.ui.navigation

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.ui.WhatsNewTrigger
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class RootViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val installed = TermuxPresence.Installed(1_000, "0.118.3")

    private class FakeOnboarding(var completed: Long?) : OnboardingStore {
        var read: CompletableDeferred<Long?>? = null
        var readError: Exception? = null
        var completes = 0
        var resets = 0
        override suspend fun completedAt(): Long? {
            readError?.let { throw it }
            return read?.await() ?: completed
        }
        override suspend fun complete() {
            completes++
            completed = 1L
        }
        override suspend fun reset() {
            resets++
            completed = null
        }
    }

    private class FakeWhatsNew(var pending: String?) : WhatsNewTrigger {
        val seen = mutableListOf<String>()
        override suspend fun pendingId(): String? = pending
        override suspend fun markSeen(id: String) {
            seen += id
        }
    }

    private val known = mutableListOf<String>()

    private fun vm(
        onboarding: OnboardingStore = FakeOnboarding(1L),
        appearance: Flow<AppearancePrefs> = MutableStateFlow(AppearancePrefs(themeMode = ThemeMode.Dark)),
        termux: TermuxPresence = installed,
        savedState: SavedStateHandle = SavedStateHandle(),
        whatsNew: WhatsNewTrigger = WhatsNewTrigger.None,
    ) = RootViewModel(
        savedState = savedState,
        onboarding = onboarding,
        appearance = appearance,
        termuxPresence = { termux },
        addKnownSession = { known += it },
        whatsNew = whatsNew,
        io = main,
    )

    @Test
    fun `start destination rule`() {
        assertEquals(OnboardingGraph, RootViewModel.startFor(null, readFailed = false, termux = installed))
        assertEquals(Shell, RootViewModel.startFor(5L, readFailed = false, termux = installed))
        assertEquals(KernelInstallTermux, RootViewModel.startFor(5L, readFailed = false, termux = TermuxPresence.Missing))
        assertEquals(KernelInstallTermux, RootViewModel.startFor(5L, readFailed = false, termux = TermuxPresence.Disabled))
        // An unreadable store never traps the user in onboarding.
        assertEquals(Shell, RootViewModel.startFor(null, readFailed = true, termux = installed))
        assertEquals(KernelInstallTermux, RootViewModel.startFor(null, readFailed = true, termux = TermuxPresence.Missing))
    }

    @Test
    fun `a completed onboarding starts the shell with the stored appearance`() = runTest {
        val vm = vm()
        assertEquals(Shell, vm.start.value)
        assertEquals(ThemeMode.Dark, vm.appearance.value?.themeMode)
        assertTrue(vm.ready)
    }

    @Test
    fun `the splash waits for both reads`() = runTest {
        val onboarding = FakeOnboarding(1L).apply { read = CompletableDeferred() }
        val vm = vm(onboarding)
        assertNull(vm.start.value)
        assertFalse(vm.ready)
        onboarding.read!!.complete(1L)
        assertEquals(Shell, vm.start.value)
        assertTrue(vm.ready)
    }

    @Test
    fun `a first run starts onboarding`() = runTest {
        assertEquals(OnboardingGraph, vm(FakeOnboarding(null)).start.value)
    }

    @Test
    fun `missing termux starts on the install screen`() = runTest {
        assertEquals(KernelInstallTermux, vm(termux = TermuxPresence.Missing).start.value)
    }

    @Test
    fun `a failing read falls back to the shell`() = runTest {
        val onboarding = FakeOnboarding(null).apply { readError = IllegalStateException("corrupt") }
        assertEquals(Shell, vm(onboarding).start.value)
    }

    @Test
    fun `hung reads time out to the shell and the default theme, then a late empty read starts onboarding`() = runTest {
        // A hung DataStore counts as unreadable (never traps an onboarded user in onboarding);
        // the read keeps running, and a store that really has no completedAt is a first run.
        val onboarding = FakeOnboarding(null).apply { read = CompletableDeferred() }
        val vm = vm(onboarding, appearance = flow { awaitCancellation() })
        assertFalse(vm.ready)
        advanceTimeBy(RootViewModel.READ_TIMEOUT_MS + 1)
        assertEquals(Shell, vm.start.value)
        assertEquals(AppearancePrefs(), vm.appearance.value)
        assertTrue(vm.ready)
        onboarding.read!!.complete(null)
        assertEquals(OnboardingGraph, vm.start.value)
    }

    @Test
    fun `a hung read with termux missing times out to the install screen`() = runTest {
        val onboarding = FakeOnboarding(1L).apply { read = CompletableDeferred() }
        val vm = vm(onboarding, termux = TermuxPresence.Missing)
        advanceTimeBy(RootViewModel.READ_TIMEOUT_MS + 1)
        assertEquals(KernelInstallTermux, vm.start.value)
    }

    @Test
    fun `a slow read of a completed onboarding goes to the shell on timeout and keeps the deep link`() = runTest {
        val onboarding = FakeOnboarding(1L).apply { read = CompletableDeferred() }
        val vm = vm(onboarding)
        assertNull(vm.start.value)
        advanceTimeBy(RootViewModel.READ_TIMEOUT_MS - 1)
        assertNull(vm.start.value)
        advanceTimeBy(2)
        assertEquals(Shell, vm.start.value)
        val link = DeepLink.Session("s-1")
        vm.onDeepLink(link)
        assertEquals(link, vm.deepLink.value)
        // The late read confirms onboarding: the start and the link stay.
        onboarding.read!!.complete(1L)
        assertEquals(Shell, vm.start.value)
        assertEquals(link, vm.deepLink.value)
    }

    @Test
    fun `a late empty read drops a pending deep link`() = runTest {
        val onboarding = FakeOnboarding(null).apply { read = CompletableDeferred() }
        val vm = vm(onboarding)
        advanceTimeBy(RootViewModel.READ_TIMEOUT_MS + 1)
        vm.onDeepLink(DeepLink.Session("s-1"))
        onboarding.read!!.complete(null)
        assertEquals(OnboardingGraph, vm.start.value)
        assertNull(vm.deepLink.value)
        vm.onDeepLink(DeepLink.Session("s-2"))
        assertNull(vm.deepLink.value)
    }

    @Test
    fun `a broken appearance flow uses the defaults`() = runTest {
        val vm = vm(appearance = flow { throw IllegalStateException("io") })
        assertEquals(AppearancePrefs(), vm.appearance.value)
    }

    @Test
    fun `session links are queued, saved and remembered as known sessions`() = runTest {
        val handle = SavedStateHandle()
        val vm = vm(savedState = handle)
        val link = DeepLink.Session("s-1", ChatSheet.Question)
        vm.onDeepLink(link)
        assertEquals(link, vm.deepLink.value)
        assertEquals(listOf("s-1"), known)
        // Recreation restores the queued link.
        assertEquals(link, vm(savedState = handle).deepLink.value)

        vm.clearDeepLink(DeepLink.KernelStatus)
        assertEquals(link, vm.deepLink.value)
        vm.clearDeepLink(link)
        assertNull(vm.deepLink.value)
        assertNull(vm(savedState = handle).deepLink.value)
    }

    @Test
    fun `kernel status links are not sessions`() = runTest {
        val vm = vm()
        vm.onDeepLink(DeepLink.KernelStatus)
        assertEquals(DeepLink.KernelStatus, vm.deepLink.value)
        assertTrue(known.isEmpty())
        vm.onDeepLink(null)
        assertEquals(DeepLink.KernelStatus, vm.deepLink.value)
    }

    @Test
    fun `links are dropped while onboarding is incomplete`() = runTest {
        val vm = vm(FakeOnboarding(null))
        vm.onDeepLink(DeepLink.Session("s-1"))
        assertNull(vm.deepLink.value)
        assertTrue(known.isEmpty())
    }

    @Test
    fun `a link that arrives before the read is dropped when onboarding starts`() = runTest {
        val onboarding = FakeOnboarding(null).apply { read = CompletableDeferred() }
        val vm = vm(onboarding)
        vm.onDeepLink(DeepLink.Session("s-1"))
        assertEquals(DeepLink.Session("s-1"), vm.deepLink.value)
        onboarding.read!!.complete(null)
        assertEquals(OnboardingGraph, vm.start.value)
        assertNull(vm.deepLink.value)
    }

    @Test
    fun `completing onboarding stores it, marks whats new seen and accepts links`() = runTest {
        val onboarding = FakeOnboarding(null)
        val whatsNew = FakeWhatsNew("3.0.68-termux.2")
        val vm = vm(onboarding, whatsNew = whatsNew)
        vm.completeOnboarding().join()
        assertEquals(1, onboarding.completes)
        assertEquals(listOf("3.0.68-termux.2"), whatsNew.seen)
        vm.onDeepLink(DeepLink.Session("s-2"))
        assertEquals(DeepLink.Session("s-2"), vm.deepLink.value)
    }

    @Test
    fun `replaying onboarding resets the store and drops a queued link`() = runTest {
        val onboarding = FakeOnboarding(1L)
        val vm = vm(onboarding)
        vm.onDeepLink(DeepLink.Session("s-1"))
        vm.resetOnboarding().join()
        assertEquals(1, onboarding.resets)
        assertNull(vm.deepLink.value)
        vm.onDeepLink(DeepLink.Session("s-3"))
        assertNull(vm.deepLink.value)
    }
}
