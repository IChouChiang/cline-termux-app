package dev.cline.termux.ui.onboarding

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.ConfiguredProvider
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.fakes.FakeClineSignIn
import dev.cline.termux.fakes.FakeProviderSetup
import dev.cline.termux.fakes.FakeSetupKernel
import dev.cline.termux.ui.kernel.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class ConnectModelViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val hubDown = KernelState.HubDown(DownReason.Stopped, null, null)

    private fun provider(id: String, apiKey: Boolean = true, oauth: Boolean = false) =
        ConfiguredProvider(id, hasApiKey = apiKey, hasOAuth = oauth, hasCloudCredentials = false, model = null, hasBaseUrl = false, tokenSource = null, updatedAt = null)

    private fun configured(vararg providers: ConfiguredProvider, last: String? = null) =
        ConfiguredProviders(last, providers.associateBy { it.id })

    private class Rig(
        val kernel: FakeSetupKernel = FakeSetupKernel(KernelState.HubDown(DownReason.Stopped, null, null)),
        val setup: FakeProviderSetup = FakeProviderSetup(),
        val signIn: FakeClineSignIn = FakeClineSignIn(),
    ) {
        val vm = ConnectModelViewModel(kernel, setup, signIn)
    }

    @Test
    fun `entry reads providers once and loads the provider list`() = runTest(main) {
        val rig = Rig()
        val ui = rig.vm.ui.value
        assertEquals(1, rig.setup.reads)
        assertEquals(1, rig.setup.loads)
        assertFalse(ui.loading)
        assertTrue(ui.kernelUsable)
        assertEquals(SetupMethod.Cline, ui.method)
        assertEquals(listOf("anthropic", OPENAI_COMPATIBLE_ID), ui.options.map { it.id })
        assertEquals(ClineSignInUi.Idle, ui.cline)
        rig.vm.onResume()
        assertEquals(2, rig.setup.reads)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a blocked kernel disables the cards and names the repair row`() = runTest(main) {
        val rig = Rig(kernel = FakeSetupKernel(KernelState.PermissionMissing))
        assertFalse(rig.vm.ui.value.kernelUsable)
        assertEquals(CheckId.Permission, rig.vm.ui.value.kernelFocus)
        rig.vm.signIn()
        assertTrue(rig.signIn.calls.isEmpty())
        rig.kernel.emit(hubDown)
        assertTrue(rig.vm.ui.value.kernelUsable)
        assertNull(rig.vm.ui.value.kernelFocus)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `repair focus follows the blocked state and a revoked permission`() {
        assertEquals(CheckId.Termux, ConnectModelViewModel.focusFor(KernelState.TermuxMissing(false), true))
        assertEquals(CheckId.ExternalApps, ConnectModelViewModel.focusFor(KernelState.ExternalAppsBlocked("x"), true))
        assertEquals(CheckId.Kernel, ConnectModelViewModel.focusFor(KernelState.PortTooOld(null), true))
        assertEquals(CheckId.Permission, ConnectModelViewModel.focusFor(hubDown, false))
        assertNull(ConnectModelViewModel.focusFor(hubDown, true))
        assertEquals(CheckId.Termux, ConnectModelViewModel.focusFor(TermuxUnavailable.NotInstalled()))
        assertEquals(CheckId.ExternalApps, ConnectModelViewModel.focusFor(TermuxUnavailable.ExternalAppsBlocked("e")))
    }

    @Test
    fun `an already signed-in Cline account shows Signed in as`() = runTest(main) {
        val rig = Rig(setup = FakeProviderSetup(presence = ProviderPresence.Present(configured(provider("cline", apiKey = false, oauth = true))), displayName = "Ada"))
        assertEquals(ClineSignInUi.SignedIn("Ada"), rig.vm.ui.value.cline)
        rig.vm.events.test {
            rig.vm.continueConnected(SetupMethod.Cline)
            assertEquals(ConnectEvent.ToDone(OnboardingConnection.Cline()), awaitItem())
        }
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `Sign in waits for Termux, back cancels it, a failure shows its detail`() = runTest(main) {
        val rig = Rig()
        rig.vm.signIn()
        assertEquals(listOf("start"), rig.signIn.calls)
        rig.signIn.mutableState.value = SignInState.WaitingForTermux("cline", 1_000L)
        val waiting = rig.vm.ui.value.cline as ClineSignInUi.Waiting
        assertEquals(1_000L, waiting.startedAtEpochMs)
        assertTrue(rig.vm.ui.value.clineBusy)
        rig.vm.signIn() // busy: ignored
        assertEquals(1, rig.signIn.calls.count { it == "start" })

        assertTrue(rig.vm.onBack())
        assertTrue("cancel" in rig.signIn.calls)
        assertEquals(ClineSignInUi.Idle, rig.vm.ui.value.cline)
        assertFalse(rig.vm.onBack())

        rig.signIn.mutableState.value = SignInState.Failed(SignInFailure.ExitCode(2), SignInState.Failed.Kind.SignIn)
        assertEquals(
            ClineSignInUi.Error(UiText.Res(R.string.connect_sign_in_failed, UiText.Res(R.string.connect_sign_in_exit, 2))),
            rig.vm.ui.value.cline,
        )
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a successful Cline sign-in clears keys and goes to Done`() = runTest(main) {
        val rig = Rig()
        rig.vm.events.test {
            rig.signIn.mutableState.value = SignInState.Succeeded("cline", SignInOrigin.Onboarding, viaApiKey = false, verified = true)
            assertEquals(ConnectEvent.ClearKeys, awaitItem())
            assertEquals(ConnectEvent.ToDone(OnboardingConnection.Cline(unverified = false)), awaitItem())
        }
        assertTrue("acknowledge" in rig.signIn.calls)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a Cline API key is passed through once and an unverified save says so on Done`() = runTest(main) {
        val rig = Rig()
        rig.vm.toggleClineKeyForm()
        assertTrue(rig.vm.ui.value.clineKeyFormExpanded)
        rig.vm.submitClineKey("   ")
        assertTrue(rig.signIn.submittedKeyLengths.isEmpty())
        rig.vm.submitClineKey(" sk-cline-123 ")
        assertEquals(listOf("sk-cline-123".length), rig.signIn.submittedKeyLengths)
        assertTrue(rig.vm.ui.value.clineKeySaving)
        rig.vm.events.test {
            rig.signIn.mutableState.value = SignInState.Succeeded("cline", SignInOrigin.Onboarding, viaApiKey = true, verified = false)
            assertEquals(ConnectEvent.ClearKeys, awaitItem())
            assertEquals(ConnectEvent.ToDone(OnboardingConnection.Cline(unverified = true)), awaitItem())
        }
        assertFalse(rig.vm.ui.value.clineKeySaving)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a rejected Cline API key shows the key error, not the sign-in error`() = runTest(main) {
        val rig = Rig()
        rig.vm.submitClineKey("sk")
        rig.signIn.mutableState.value = SignInState.Failed(SignInFailure.KeyNotVerified("401"), SignInState.Failed.Kind.ApiKey)
        val ui = rig.vm.ui.value
        assertEquals(ConnectModelViewModel.keyFailureText(SignInFailure.KeyNotVerified("401")), ui.clineKeyError)
        assertEquals(ClineSignInUi.Idle, ui.cline)
        assertFalse(ui.clineKeySaving)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a blocked sign-in turns the kernel card on`() = runTest(main) {
        val rig = Rig()
        rig.signIn.mutableState.value = SignInState.Blocked(TermuxUnavailable.PermissionMissing())
        assertFalse(rig.vm.ui.value.kernelUsable)
        assertEquals(CheckId.Permission, rig.vm.ui.value.kernelFocus)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `choosing a provider prefills the model unless the user typed one`() = runTest(main) {
        val rig = Rig()
        rig.vm.selectMethod(SetupMethod.ApiKey)
        rig.vm.selectProvider("anthropic")
        assertEquals("claude-sonnet-5", rig.vm.ui.value.modelId)
        rig.vm.setModelId("claude-opus")
        rig.vm.selectProvider(OPENAI_COMPATIBLE_ID)
        assertEquals("claude-opus", rig.vm.ui.value.modelId)
        assertTrue(rig.vm.ui.value.selectedOption!!.needsBaseUrl)
        rig.vm.selectProvider("not-listed")
        assertEquals(OPENAI_COMPATIBLE_ID, rig.vm.ui.value.selectedProviderId)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `Connect with a missing base URL only marks the fields touched`() = runTest(main) {
        val rig = Rig()
        rig.vm.selectProvider(OPENAI_COMPATIBLE_ID)
        rig.vm.setModelId("qwen")
        rig.vm.connect("sk-123")
        assertTrue(rig.setup.saves.isEmpty())
        assertEquals(ConnectFieldError.BaseUrlInvalid, rig.vm.ui.value.fieldErrors[ConnectField.BaseUrl])
        rig.vm.setBaseUrl("http://192.168.1.4:8000/v1")
        assertTrue(rig.vm.ui.value.canSubmitKey("sk-123"))
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a saved key remembers the selection, clears keys and goes to Done with the provider name`() = runTest(main) {
        val setup = FakeProviderSetup().apply { afterSave = configured(provider("anthropic"), last = "anthropic") }
        val rig = Rig(setup = setup)
        rig.vm.selectProvider("anthropic")
        rig.vm.events.test {
            rig.vm.connect("  sk-ant-1  ")
            assertEquals(ConnectEvent.ClearKeys, awaitItem())
            assertEquals(ConnectEvent.ToDone(OnboardingConnection.Provider("Anthropic")), awaitItem())
        }
        assertEquals(listOf(listOf("anthropic", "8", "claude-sonnet-5", null)), setup.saves)
        assertEquals(listOf("anthropic" to "claude-sonnet-5"), setup.remembered)
        assertEquals("Anthropic", rig.vm.ui.value.connectedProviderName)
        assertFalse(rig.vm.ui.value.saving)
        // The key text is never part of the UI state.
        assertFalse(rig.vm.ui.value.toString().contains("sk-ant-1"))
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `save failures show the stderr line or the timeout sentence`() = runTest(main) {
        val setup = FakeProviderSetup(saveResult = ProviderSetup.SaveResult.Failed("Error: invalid key"))
        val rig = Rig(setup = setup)
        rig.vm.selectProvider("anthropic")
        rig.vm.connect("sk")
        assertEquals(UiText.Res(R.string.connect_save_failed, UiText.Raw("Error: invalid key")), rig.vm.ui.value.saveError)
        setup.saveResult = ProviderSetup.SaveResult.TimedOut
        rig.vm.connect("sk")
        assertEquals(UiText.Res(R.string.connect_save_failed, UiText.Res(R.string.connect_save_timeout_detail)), rig.vm.ui.value.saveError)
        setup.saveResult = ProviderSetup.SaveResult.Unavailable(TermuxUnavailable.NotInstalled())
        rig.vm.connect("sk")
        assertFalse(rig.vm.ui.value.kernelUsable)
        assertEquals(CheckId.Termux, rig.vm.ui.value.kernelFocus)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `a failed catalog falls back to the bundled list with the error`() = runTest(main) {
        val setup = FakeProviderSetup(options = ProviderSetup.OptionsResult.CatalogFailed("helper exited"))
        val rig = Rig(setup = setup)
        val providers = rig.vm.ui.value.providers as ProviderOptionsUi.Loaded
        assertEquals("helper exited", providers.catalogError)
        assertEquals(ProviderSource.Bundled, providers.source)
        assertEquals(listOf("anthropic"), providers.options.map { it.id })
        setup.options = ProviderSetup.OptionsResult.Loaded(setup.bundled, ProviderSource.Helper)
        rig.vm.retryCatalog()
        assertNull((rig.vm.ui.value.providers as ProviderOptionsUi.Loaded).catalogError)
        rig.vm.viewModelScope.cancel()
    }

    @Test
    fun `an existing own-key provider offers Continue with its name`() = runTest(main) {
        val setup = FakeProviderSetup(presence = ProviderPresence.Present(configured(provider("cline", oauth = true), provider("anthropic"), last = "anthropic")))
        val rig = Rig(setup = setup)
        assertEquals("Anthropic", rig.vm.ui.value.connectedProviderName)
        rig.vm.events.test {
            rig.vm.continueConnected(SetupMethod.ApiKey)
            assertEquals(ConnectEvent.ToDone(OnboardingConnection.Provider("Anthropic")), awaitItem())
        }
        rig.vm.viewModelScope.cancel()
    }
}
