package dev.cline.termux.ui.about

import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.data.about.WHATS_NEW_RELEASES
import dev.cline.termux.fakes.FakeAboutSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class AboutViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `format helpers`() {
        assertEquals("3.0.68-termux.2", AboutFormat.releaseFromReadlink("/data/data/com.termux/files/usr/opt/cline-termux/3.0.68-termux.2\n"))
        assertEquals("3.0.68-termux.2", AboutFormat.releaseFromReadlink("3.0.68-termux.2/"))
        assertNull(AboutFormat.releaseFromReadlink("  \n"))
        assertEquals("3.0.68", AboutFormat.firstLine("\n  3.0.68  \nmore"))
        assertEquals("9f3c2a1b", AboutFormat.shortBuildId(" 9f3c2a1b77d0 "))
        assertNull(AboutFormat.shortBuildId(""))
        assertEquals("b1c2d3e4", AboutFormat.shortBuildId("b1c2d3e4f5"))
        assertNull(AboutFormat.shortBuildId("source-0.0.90"))
        assertNull(AboutFormat.shortBuildId(" source-0.0.90 "))
        assertEquals(AboutHubVersion("0.0.90", null), AboutFormat.hubVersion(HubHealth(ok = true, coreVersion = "0.0.90", buildId = "source-0.0.90")))
        assertEquals(AboutHubVersion("0.42.1", "9f3c2a1b"), AboutFormat.hubVersion(HubHealth(ok = true, coreVersion = "0.42.1", buildId = "9f3c2a1b77d0")))
        assertNull(AboutFormat.hubVersion(HubHealth(ok = true, coreVersion = " ")))
        assertNull(AboutFormat.hubVersion(null as HubHealth?))
        assertNull(AboutFormat.hubVersion(KernelState.Unknown))
    }

    @Test
    fun `gate keys settle only on non-busy gates`() {
        assertEquals("up", AboutViewModel.gateKey(KernelGate.Ready))
        assertEquals("up", AboutViewModel.gateKey(KernelGate.DegradedNoCommands))
        assertEquals("down:Stopped", AboutViewModel.gateKey(KernelGate.Down(KernelGate.DownVariant.Stopped, null)))
        assertEquals("blocked", AboutViewModel.gateKey(KernelGate.Blocked(CheckId.Termux)))
        assertNull(AboutViewModel.gateKey(KernelGate.Busy(KernelGate.BusyKind.Starting, null, slow = false)))
    }

    @Test
    fun `rows load from Termux and the health probe`() = runTest(main) {
        val sources = FakeAboutSources(whatsNew = WHATS_NEW_RELEASES.firstOrNull())
        val vm = AboutViewModel(sources, versionName = "0.1.0", versionCode = 7)
        val state = vm.ui.value
        assertEquals("0.1.0", state.version)
        assertEquals(7, state.versionCode)
        assertFalse(state.beta)
        assertEquals(VersionValue.Value("3.0.68-termux.2"), state.kernelRelease)
        assertEquals(VersionValue.Value("3.0.68"), state.cliVersion)
        assertEquals(VersionValue.Value("0.42.1"), state.hub)
        assertEquals(AboutHubVersion("0.42.1", "9f3c2a1b"), state.hubVersion)
        assertEquals(WHATS_NEW_RELEASES.firstOrNull(), state.whatsNew)
        val notes = state.releases as ReleaseNotes.Ready
        assertEquals("0.1.0", notes.releases.single().version)
    }

    @Test
    fun `cached release skips the readlink and a connected hub skips the probe`() = runTest(main) {
        val info = HubInfo(url = "ws://127.0.0.1:25463", port = 25463, pid = 1, startedAt = null, coreVersion = "0.50.0", buildId = "abcdef0123", protocolVersion = "1")
        val sources = FakeAboutSources(cached = "3.0.68-termux.1")
        sources.kernelState.value = KernelState.Ready(info, null)
        val vm = AboutViewModel(sources, "0.1.0-beta.1", 1)
        assertTrue(vm.ui.value.beta)
        assertEquals(VersionValue.Value("3.0.68-termux.1"), vm.ui.value.kernelRelease)
        assertEquals(0, sources.portReads)
        assertEquals(0, sources.healthProbes)
        assertEquals(AboutHubVersion("0.50.0", "abcdef01"), vm.ui.value.hubVersion)
    }

    @Test
    fun `stopped hub, unreadable rows and a broken changelog`() = runTest(main) {
        val sources = FakeAboutSources(
            portRelease = { null },
            cli = { throw IllegalStateException("termux died") },
            health = { null },
            changelogText = { "no headings here" },
        )
        val vm = AboutViewModel(sources, "0.1.0", 1)
        assertEquals(VersionValue.Unavailable, vm.ui.value.kernelRelease)
        assertEquals(VersionValue.Unavailable, vm.ui.value.cliVersion)
        assertEquals(VersionValue.NotRunning, vm.ui.value.hub)
        assertNull(vm.ui.value.hubVersion)
        assertEquals(ReleaseNotes.Error, vm.ui.value.releases)
    }

    @Test
    fun `blocked gate marks the Termux rows unavailable without asking Termux`() = runTest(main) {
        val sources = FakeAboutSources(gate = KernelGate.Blocked(CheckId.Permission))
        val vm = AboutViewModel(sources, "0.1.0", 1)
        assertTrue(vm.ui.value.kernelBlocked)
        assertEquals(VersionValue.Unavailable, vm.ui.value.kernelRelease)
        assertEquals(0, sources.portReads)
        assertEquals(0, sources.cliReads)

        sources.gate.value = KernelGate.Ready
        assertEquals(1, sources.portReads)
        assertEquals(VersionValue.Value("3.0.68"), vm.ui.value.cliVersion)
    }

    @Test
    fun `blocked gate marks the Hub row unavailable without probing, also on refresh`() = runTest(main) {
        val sources = FakeAboutSources(gate = KernelGate.Blocked(CheckId.Termux))
        val vm = AboutViewModel(sources, "0.1.0", 1)
        assertEquals(VersionValue.Unavailable, vm.ui.value.hub)
        assertNull(vm.ui.value.hubVersion)
        assertEquals(0, sources.healthProbes)

        vm.refresh()
        assertEquals(VersionValue.Unavailable, vm.ui.value.hub)
        assertEquals(0, sources.healthProbes)
        assertEquals(0, sources.cliReads)

        sources.gate.value = KernelGate.Ready
        assertEquals(1, sources.healthProbes)
        assertTrue(vm.ui.value.hub is VersionValue.Value)
    }

    @Test
    fun `refresh re-reads every row`() = runTest(main) {
        val sources = FakeAboutSources()
        val vm = AboutViewModel(sources, "0.1.0", 1)
        val probes = sources.healthProbes
        sources.cli = { "3.0.69" }
        vm.refresh()
        assertEquals(2, sources.cliReads)
        assertEquals(probes + 1, sources.healthProbes)
        assertEquals(VersionValue.Value("3.0.69"), vm.ui.value.cliVersion)
    }

    @Test
    fun `whats new uses two columns from 400dp`() {
        assertEquals(1, WhatsNewLayout.columns(399f))
        assertEquals(2, WhatsNewLayout.columns(400f))
    }
}
