package dev.cline.termux.ui.marketplace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceRepository.ActionResult
import dev.cline.termux.data.marketplace.MarketplaceRepository.CatalogLoad
import dev.cline.termux.data.marketplace.MarketplaceRepository.Installed
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.fakes.FakeMarketplaceBackend
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class MarketplaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Action scopes are foreground work on the test scheduler (advanceUntilIdle skips background work). */
    private val actionScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        actionScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private val github = TestCatalog.github.key
    private val prReview = TestCatalog.prReview.key

    private class Rig(val vm: MarketplaceViewModel, val backend: FakeMarketplaceBackend, val handle: SavedStateHandle, val store: ViewModelStore) {
        val state: MarketplaceUiState get() = vm.state.value
    }

    private fun TestScope.rig(
        handle: SavedStateHandle = SavedStateHandle(),
        configure: FakeMarketplaceBackend.() -> Unit = { catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, stale = false) } },
    ): Rig {
        val actionScope = CoroutineScope(SupervisorJob() + dispatcher).also { actionScopes += it }
        val backend = FakeMarketplaceBackend(actionScope).apply(configure)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                MarketplaceViewModel(backend, handle, compute = dispatcher) as T
        }
        val vm = ViewModelProvider.create(store, factory)[MarketplaceViewModel::class]
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()
        return Rig(vm, backend, handle, store)
    }

    @Test
    fun `loads the catalog, then reads installed keys from the workspace`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, stale = false) }
            installedAnswer = { Outcome.Ok(Installed(setOf("mcp:github"), approximate = false)) }
        }
        assertEquals(MarketplaceCatalogState.Loaded(stale = false), r.state.catalogState)
        assertEquals(listOf(false), r.backend.catalogCalls)
        assertEquals(listOf(r.backend.workspace), r.backend.installedCalls)
        assertTrue(r.state.installedReady)
        assertEquals(setOf(github), r.state.installedKeys)
        assertTrue(r.state.sections.flatMap { it.rows }.single { it.key == github }.installed)
        assertEquals(1, r.backend.acquired.size)
    }

    @Test
    fun `shows loading first`() = runTest(dispatcher) {
        val gate = CompletableDeferred<CatalogLoad>()
        val r = rig { catalogAnswer = { gate.await() } }
        assertEquals(MarketplaceCatalogState.Loading, r.state.catalogState)
        assertFalse(r.state.installedReady)
        gate.complete(CatalogLoad.Loaded(TestCatalog.catalog, stale = true))
        advanceUntilIdle()
        assertTrue(r.state.stale)
    }

    @Test
    fun `catalog error ends the installed check, retry loads from the network`() = runTest(dispatcher) {
        var answer: CatalogLoad = CatalogLoad.Error(503, "Failed to fetch marketplace: 503")
        val r = rig { catalogAnswer = { answer } }
        assertEquals(MarketplaceCatalogState.Error(503), r.state.catalogState)
        assertTrue(r.state.installedReady)
        assertTrue(r.backend.installedCalls.isEmpty())

        answer = CatalogLoad.Loaded(TestCatalog.catalog, stale = false)
        r.vm.load(force = true)
        advanceUntilIdle()
        assertEquals(listOf(false, true), r.backend.catalogCalls)
        assertEquals(MarketplaceCatalogState.Loaded(stale = false), r.state.catalogState)
    }

    @Test
    fun `refresh keeps the list while it reloads`() = runTest(dispatcher) {
        val gate = CompletableDeferred<CatalogLoad>()
        var first = true
        val r = rig {
            catalogAnswer = { if (first) { first = false; CatalogLoad.Loaded(TestCatalog.catalog, false) } else gate.await() }
        }
        r.vm.refresh()
        advanceUntilIdle()
        assertTrue(r.state.refreshing)
        assertEquals(MarketplaceCatalogState.Loaded(stale = false), r.state.catalogState)
        assertTrue(r.state.sections.isNotEmpty())
        gate.complete(CatalogLoad.Loaded(TestCatalog.catalog, false))
        advanceUntilIdle()
        assertFalse(r.state.refreshing)
    }

    @Test
    fun `an entry gone after a refresh keeps its detail`() = runTest(dispatcher) {
        val trimmed = TestCatalog.catalog.copy(entries = TestCatalog.catalog.entries - TestCatalog.github)
        var answer: CatalogLoad = CatalogLoad.Loaded(TestCatalog.catalog, false)
        val r = rig { catalogAnswer = { answer } }
        answer = CatalogLoad.Loaded(trimmed, false)
        r.vm.refresh()
        advanceUntilIdle()
        val detail = r.state.detail(github)!!
        assertTrue(detail.vanished)
        assertEquals("GitHub", detail.entry.name)
        r.vm.install(github)
        advanceUntilIdle()
        assertTrue(r.backend.installs.isEmpty())
    }

    @Test
    fun `filters and selection live in the saved state`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("type" to "mcp", "tag" to "dev"))
        val r = rig(handle)
        assertEquals(MarketplaceType.Mcp, r.state.type)
        assertEquals("dev", r.state.tag)

        r.vm.setQuery("linear")
        r.vm.select(github)
        advanceUntilIdle()
        assertEquals("linear", handle[MarketplaceViewModel.KEY_QUERY])
        assertEquals(github, handle[MarketplaceViewModel.KEY_SELECTED])
        assertEquals(listOf("mcp:linear"), r.state.sections.flatMap { it.rows }.map { it.key })
    }

    @Test
    fun `tapping the selected type returns to All and keeps the tag`() = runTest(dispatcher) {
        val r = rig()
        r.vm.setTag("git")
        r.vm.toggleType(MarketplaceType.Plugin)
        advanceUntilIdle()
        assertEquals(MarketplaceType.Plugin, r.state.type)
        assertEquals("git", r.state.tag)
        assertTrue(r.state.filteredEmpty)
        assertEquals("plugin", r.handle[MarketplaceViewModel.KEY_TYPE])

        r.vm.toggleType(MarketplaceType.Plugin)
        advanceUntilIdle()
        assertNull(r.state.type)
        assertEquals("git", r.state.tag)

        r.vm.toggleType(MarketplaceType.Skill)
        r.vm.showAllTypes()
        advanceUntilIdle()
        assertNull(r.state.type)
        assertNull(r.handle[MarketplaceViewModel.KEY_TYPE])
    }

    @Test
    fun `install shows installing, then the message and the check`() = runTest(dispatcher) {
        val gate = CompletableDeferred<ActionResult>()
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { gate.await() }
        }
        r.vm.install(github)
        advanceUntilIdle()
        assertEquals(EntryAction.Installing(r.backend.now), r.state.action(github))
        assertEquals(PrimaryButton.Installing, r.state.detail(github)!!.button)

        // One action per entry at a time.
        r.vm.install(github)
        advanceUntilIdle()
        assertEquals(listOf(github), r.backend.installs)

        r.backend.installedAnswer = { Outcome.Ok(Installed(setOf(github), approximate = false)) }
        gate.complete(ActionResult.Done(MarketplaceDone.Installed("GitHub", global = false)))
        advanceUntilIdle()
        assertEquals(EntryAction.Done(MarketplaceDone.Installed("GitHub", global = false), installed = true), r.state.action(github))
        assertTrue(github in r.state.installedKeys)
        // Installed keys are read again after the change.
        assertEquals(2, r.backend.installedCalls.size)
    }

    @Test
    fun `different entries may run in parallel`() = runTest(dispatcher) {
        val gate = CompletableDeferred<ActionResult>()
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { gate.await() }
        }
        r.vm.install(github)
        r.vm.install(prReview)
        advanceUntilIdle()
        assertEquals(listOf(github, prReview), r.backend.installs)
        gate.complete(ActionResult.Done(MarketplaceDone.Message("ok")))
    }

    @Test
    fun `uninstall removes the check`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installedAnswer = { Outcome.Ok(Installed(setOf("mcp:github"), approximate = false)) }
        }
        r.backend.installedAnswer = { Outcome.Ok(Installed(emptySet(), approximate = false)) }
        r.vm.uninstall(github)
        advanceUntilIdle()
        assertEquals(listOf(github), r.backend.uninstalls)
        assertEquals(EntryAction.Done(MarketplaceDone.Uninstalled("GitHub"), installed = false), r.state.action(github))
        assertFalse(github in r.state.installedKeys)
        assertEquals(PrimaryButton.Install, r.state.detail(github)!!.button)
    }

    @Test
    fun `a failure keeps the typed reason and which action failed`() = runTest(dispatcher) {
        val failure = RepoFailure.Command(1, "MCP install failed with exit code 1:\nnpm ERR!")
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { ActionResult.Failed(failure) }
        }
        r.vm.install(github)
        advanceUntilIdle()
        assertEquals(EntryAction.Failed(failure, install = true), r.state.action(github))
        assertFalse(github in r.state.installedKeys)
        assertTrue(r.state.detail(github)!!.buttonEnabled)
    }

    @Test
    fun `an unknown outcome whose key flipped shows the normal success message`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { ActionResult.Unknown }
        }
        r.backend.installedAnswer = { Outcome.Ok(Installed(setOf("mcp:github"), approximate = false)) }
        r.vm.install(github)
        advanceUntilIdle()
        assertEquals(EntryAction.Done(MarketplaceDone.Installed("GitHub", global = false), installed = true), r.state.action(github))
        assertTrue(github in r.state.installedKeys)
    }

    @Test
    fun `an unknown skill install that did not flip asks to check the result`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { ActionResult.Unknown }
        }
        r.vm.install(prReview)
        advanceUntilIdle()
        assertEquals(EntryAction.Interrupted, r.state.action(prReview))
    }

    @Test
    fun `a reconciled skill install uses the dashboard skill wording`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { ActionResult.Unknown }
        }
        r.backend.installedAnswer = { Outcome.Ok(Installed(setOf(prReview), approximate = true)) }
        r.vm.install(prReview)
        advanceUntilIdle()
        assertEquals(EntryAction.Done(MarketplaceDone.Installed("PR Review", global = true), installed = true), r.state.action(prReview))
    }

    @Test
    fun `resume after the 300 s timeout reconciles a stuck install`() = runTest(dispatcher) {
        val never = CompletableDeferred<ActionResult>()
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installAnswer = { never.await() }
        }
        r.vm.install(github)
        advanceUntilIdle()

        r.backend.now += 60_000
        r.vm.onResume()
        advanceUntilIdle()
        assertEquals(EntryAction.Installing(1_000_000L), r.state.action(github))

        r.backend.now += MarketplaceViewModel.INSTALL_TIMEOUT_MS
        r.backend.installedAnswer = { Outcome.Ok(Installed(setOf(github), approximate = false)) }
        r.vm.onResume()
        advanceUntilIdle()
        assertEquals(EntryAction.Done(MarketplaceDone.Installed("GitHub", global = false), installed = true), r.state.action(github))
    }

    @Test
    fun `kernel down blocks install`() = runTest(dispatcher) {
        val r = rig()
        r.backend.kernelGate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        advanceUntilIdle()
        r.vm.install(github)
        advanceUntilIdle()
        assertTrue(r.backend.installs.isEmpty())
        assertEquals(EntryAction.Idle, r.state.action(github))
    }

    @Test
    fun `install waits for a starting helper to settle`() = runTest(dispatcher) {
        val r = rig()
        r.backend.helperState.value = HelperState.Starting(0)
        advanceUntilIdle()
        r.vm.install(github)
        advanceUntilIdle()
        assertTrue(r.backend.installs.isEmpty())
        assertTrue(r.state.action(github).busy)
        r.backend.helperState.value = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        advanceUntilIdle()
        assertEquals(listOf(github), r.backend.installs)
    }

    @Test
    fun `installed keys wait for a starting helper and show checking meanwhile`() = runTest(dispatcher) {
        val r = rig {
            helperState.value = HelperState.Starting(0)
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
        }
        assertFalse(r.state.installedReady)
        assertEquals(PrimaryButton.Checking, r.state.detail(github)!!.button)
        assertTrue(r.backend.installedCalls.isEmpty())
        r.backend.helperState.value = HelperState.Ready
        advanceUntilIdle()
        assertTrue(r.state.installedReady)
        assertEquals(1, r.backend.installedCalls.size)
    }

    @Test
    fun `an approximate read is redone once the helper becomes ready`() = runTest(dispatcher) {
        val r = rig {
            helperState.value = HelperState.Unavailable(HelperFailure.SocketFailed, null)
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installedAnswer = { Outcome.Ok(Installed(emptySet(), approximate = true)) }
        }
        assertTrue(r.state.installedApproximate)
        r.backend.installedAnswer = { Outcome.Ok(Installed(setOf(github), approximate = false)) }
        r.backend.helperState.value = HelperState.Ready
        advanceUntilIdle()
        assertFalse(r.state.installedApproximate)
        assertEquals(setOf(github), r.state.installedKeys)
        assertEquals(2, r.backend.installedCalls.size)
    }

    @Test
    fun `an approximate read keeps the result of an install this screen confirmed`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installedAnswer = { Outcome.Ok(Installed(emptySet(), approximate = true)) }
        }
        r.vm.install(prReview)
        advanceUntilIdle()
        assertTrue(prReview in r.state.installedKeys)
        assertTrue(r.state.installedApproximate)
    }

    @Test
    fun `a failed installed read keeps the current keys`() = runTest(dispatcher) {
        val r = rig {
            catalogAnswer = { CatalogLoad.Loaded(TestCatalog.catalog, false) }
            installedAnswer = { Outcome.Ok(Installed(setOf(github), approximate = false)) }
        }
        r.backend.installedAnswer = { Outcome.Failed(RepoFailure.HubDisconnected) }
        r.vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(github), r.state.installedKeys)
        assertTrue(r.state.installedReady)
    }

    @Test
    fun `helper retry and start kernel`() = runTest(dispatcher) {
        val r = rig()
        r.vm.retryHelper()
        advanceUntilIdle()
        assertEquals(1, r.backend.helperRetries)
        r.vm.startKernel()
        advanceUntilIdle()
        assertEquals(1, r.backend.kernelStarts)
        assertEquals(2, r.backend.helperRetries)
    }

    @Test
    fun `leaving releases the helper lease it acquired`() = runTest(dispatcher) {
        val r = rig()
        assertTrue(r.backend.released.isEmpty())
        r.store.clear()
        assertEquals(r.backend.acquired, r.backend.released)
    }

    @Test
    fun `state carries helper and kernel changes`() = runTest(dispatcher) {
        val r = rig()
        r.vm.state.test {
            assertEquals(HelperState.Ready, awaitItem().helper)
            r.backend.helperState.value = HelperState.Starting(5)
            assertEquals(HelperState.Starting(5), awaitItem().helper)
            r.backend.kernelGate.value = KernelGate.DegradedNoCommands
            assertEquals(KernelGate.DegradedNoCommands, awaitItem().kernel)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
