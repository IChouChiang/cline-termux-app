package dev.cline.termux.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.AccountResult
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.account.UsageTransaction
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.fakes.FakeAccountSource
import dev.cline.termux.fakes.FakeHelperLease
import dev.cline.termux.fakes.FakeSignInPort
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val account = FakeAccountSource()
    private val helper = FakeHelperLease()
    private val signIn = FakeSignInPort()
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(releaseScope: CoroutineScope = CoroutineScope(main)) =
        AccountViewModel(savedState, account, helper, signIn, gate, releaseScope, zone = { ZoneId.of("UTC") })

    @Test
    fun `signed in loads the overview and the usage tab lazily once`() = runTest(main) {
        account.usage = AccountResult.Ok(listOf(UsageTransaction("t1", "claude", "anthropic", 10, 1_000_000.0, null)))
        val vm = viewModel()
        assertEquals(1, helper.acquired.size)
        assertEquals(SignedIn.Yes, vm.ui.value.signedIn)
        assertEquals("12.50", vm.ui.value.overview.valueOrNull?.balance)
        assertTrue(vm.ui.value.showTabs)
        assertEquals(0, account.usageCalls)

        vm.selectTab(AccountTab.Usage)
        assertEquals(1, account.usageCalls)
        assertEquals("usage", savedState.get<String>(AccountViewModel.KEY_TAB))
        assertEquals("1.00", vm.ui.value.usage.valueOrNull?.single()?.credits)

        vm.selectTab(AccountTab.Overview)
        vm.selectTab(AccountTab.Usage)
        assertEquals(1, account.usageCalls)
    }

    @Test
    fun `restored tab is loaded with the overview`() = runTest(main) {
        savedState[AccountViewModel.KEY_TAB] = "billing"
        val vm = viewModel()
        assertEquals(AccountTab.Billing, vm.ui.value.tab)
        assertEquals(1, account.billingCalls)
        assertTrue(vm.ui.value.billing is Loadable.Ready)
    }

    @Test
    fun `signed out shows the card and loads nothing else`() = runTest(main) {
        account.overview = AccountResult.SignedOut
        val vm = viewModel()
        assertEquals(SignedIn.No, vm.ui.value.signedIn)
        assertEquals(Loadable.Idle, vm.ui.value.overview)
        vm.selectTab(AccountTab.Usage)
        assertEquals(0, account.usageCalls)
        assertEquals(false, vm.ui.value.showTabs)
    }

    @Test
    fun `older helper and failures surface as tab errors and retry reloads`() = runTest(main) {
        account.overview = AccountResult.OlderHelper
        val vm = viewModel()
        assertEquals(Loadable.Error(AccountError.OlderHelper), vm.ui.value.overview)

        account.overview = AccountResult.Failed(RepoFailure.HubTimeout)
        vm.retry()
        assertEquals(Loadable.Error(AccountError.Failure(RepoFailure.HubTimeout)), vm.ui.value.overview)

        account.overview = AccountResult.Ok(FakeAccountSource.overview())
        vm.retry()
        assertTrue(vm.ui.value.overview is Loadable.Ready)
    }

    @Test
    fun `switching reloads the overview and announces the account`() = runTest(main) {
        val acme = FakeAccountSource.org("o1", "Acme")
        account.overview = AccountResult.Ok(FakeAccountSource.overview(organizations = listOf(acme)))
        val vm = viewModel()
        val rows = vm.ui.value.overview.valueOrNull!!.accounts
        vm.events.test {
            vm.switchAccount(rows[0]) // the active personal row: ignored
            vm.switchAccount(rows[1])
            assertEquals(AccountEvent.Switched("Acme"), awaitItem())
        }
        assertEquals(listOf<String?>("o1"), account.switches)
        assertEquals(2, account.overviewCalls)
        assertNull(vm.ui.value.switchingTo)
    }

    @Test
    fun `a failed switch reports and keeps the overview`() = runTest(main) {
        account.overview = AccountResult.Ok(FakeAccountSource.overview(organizations = listOf(FakeAccountSource.org("o1", "Acme"))))
        account.switchResult = AccountResult.Failed(RepoFailure.HubDisconnected)
        val vm = viewModel()
        vm.events.test {
            vm.switchAccount(vm.ui.value.overview.valueOrNull!!.accounts[1])
            assertEquals(AccountEvent.Failed(AccountError.Failure(RepoFailure.HubDisconnected)), awaitItem())
        }
        assertTrue(vm.ui.value.overview is Loadable.Ready)
    }

    @Test
    fun `sign out resets to the signed-out card`() = runTest(main) {
        savedState[AccountViewModel.KEY_TAB] = "usage"
        val vm = viewModel()
        vm.events.test {
            vm.signOut()
            assertEquals(AccountEvent.SignedOut, awaitItem())
        }
        assertEquals(1, account.signOuts)
        assertEquals(SignedIn.No, vm.ui.value.signedIn)
        assertEquals(AccountTab.Overview, vm.ui.value.tab)
        assertEquals("overview", savedState.get<String>(AccountViewModel.KEY_TAB))
    }

    @Test
    fun `helper unavailable shows the reduced page with the file check`() = runTest(main) {
        helper.state.value = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        val vm = viewModel()
        assertNotNull(vm.ui.value.helperUnavailable)
        assertEquals(1, account.fallbackCalls)
        assertEquals(Loadable.Ready(true), vm.ui.value.fallbackSignedIn)
        assertEquals(0, account.overviewCalls)

        helper.state.value = HelperState.Ready
        assertEquals(1, account.overviewCalls)
        assertNull(vm.ui.value.helperUnavailable)
    }

    @Test
    fun `blocking helper failures show the kernel card and skip the file check`() = runTest(main) {
        helper.state.value = HelperState.Unavailable(HelperFailure.PermissionMissing, null)
        val vm = viewModel()
        assertEquals(HelperFailure.PermissionMissing, vm.ui.value.blockedFailure)
        assertNull(vm.ui.value.helperUnavailable)
        assertEquals(0, account.fallbackCalls)
    }

    @Test
    fun `a helper lost mid-call is re-acquired once, then the error shows`() = runTest(main) {
        account.overview = AccountResult.Failed(RepoFailure.HelperLost)
        val vm = viewModel()
        assertEquals(1, helper.retries)
        assertTrue(vm.ui.value.overview !is Loadable.Error)

        helper.state.value = HelperState.Starting(0)
        helper.state.value = HelperState.Ready
        assertEquals(2, account.overviewCalls)
        assertEquals(1, helper.retries)
        assertEquals(Loadable.Error(AccountError.Failure(RepoFailure.HelperLost)), vm.ui.value.overview)
    }

    @Test
    fun `sign-in from the card reloads the overview on success`() = runTest(main) {
        account.overview = AccountResult.SignedOut
        val vm = viewModel()
        vm.startSignIn()
        assertEquals(1, signIn.starts)
        assertEquals(1, signIn.acknowledgements)

        account.overview = AccountResult.Ok(FakeAccountSource.overview())
        signIn.state.value = SignInState.Succeeded("cline", SignInOrigin.Account, viaApiKey = false, verified = true)
        assertEquals(2, signIn.acknowledgements)
        assertEquals(SignedIn.Yes, vm.ui.value.signedIn)
    }

    @Test
    fun `another screen's sign-in is not consumed`() = runTest(main) {
        val vm = viewModel()
        val calls = account.overviewCalls
        signIn.state.value = SignInState.Succeeded("cline", SignInOrigin.ProviderDetail, viaApiKey = false, verified = true)
        assertEquals(0, signIn.acknowledgements)
        assertEquals(calls, account.overviewCalls)
        assertTrue(vm.ui.value.signIn is SignInState.Succeeded)
    }

    @Test
    fun `clearing the view model releases the helper lease`() = runTest(main) {
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { viewModel(releaseScope = backgroundScope) } }
        ViewModelProvider.create(store, factory)[AccountViewModel::class]
        assertEquals(1, helper.acquired.size)
        store.clear()
        assertEquals(helper.acquired, helper.released)
    }

    @Test
    fun `the kernel gate is mirrored`() = runTest(main) {
        val vm = viewModel()
        gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Stopped, null), vm.ui.value.kernel)
    }
}
