@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.cline.termux.data.account

import kotlinx.coroutines.ExperimentalCoroutinesApi

import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.model.InMemoryModelSelectionPrefs
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.fakes.FakeCatalogHelper
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemorySignInStore : SignInStore {
    var pending: SignInPending? = null
    var account: Pair<String?, String?> = null to null
    override suspend fun pending(): SignInPending? = pending
    override suspend fun setPending(pending: SignInPending?) {
        this.pending = pending
    }
    override suspend fun setAccount(displayName: String?, scope: String?) {
        account = displayName to scope
    }
    override suspend fun accountDisplayName(): String? = account.first
}

/** SP S7.3 sequence, KS C.6 polling, process-death recovery, the API-key route and sign-out. */
class ClineSignInCoordinatorTest {

    private val clock = FakeClock()
    private val startMs = clock.millis()
    private val termux = FakeTermuxExecutor()
    private val store = InMemorySignInStore()
    private val helper = FakeCatalogHelper(HelperState.Idle)
    private val selectionPrefs = InMemoryModelSelectionPrefs()
    private var invalidations = 0
    private var termuxRaised = 0
    private val completions = mutableListOf<SignInCompletion>()

    private fun TestScope.coordinator(): ClineSignInCoordinator = ClineSignInCoordinator(
        termux = termux,
        store = store,
        helper = helper,
        catalog = { invalidations += 1 },
        modelSelection = ModelSelectionStore(selectionPrefs),
        termuxForeground = { termuxRaised += 1; true },
        scope = backgroundScope,
        clock = clock,
    ).also { c -> backgroundScope.launch { c.completions.collect { completions += it } } }

    private fun providersJson(clineToken: Boolean, updatedAt: String = "2026-10-03T12:00:05Z", apiKey: Boolean = false): String {
        val settings = buildList {
            if (clineToken) add(""""auth":{"accessToken":"tok"}""")
            if (apiKey) add(""""apiKey":"key"""")
        }.joinToString(",")
        return """{"providers":{"cline":{"settings":{$settings},"updatedAt":"$updatedAt"}}}"""
    }

    private fun presence(json: String?) {
        termux.on(ClineCommands.Labels.READ_PROVIDER_PRESENCE) {
            if (json == null) TermuxResult.failure(1, "cat: providers.json: No such file or directory") else TermuxResult.success(json)
        }
    }

    @Test fun `start runs cline auth in a visible Termux session and raises Termux`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        assertEquals(SignInState.WaitingForTermux("cline", startMs), c.state.value)
        val command = termux.detached.single().command
        assertEquals(listOf("auth", "cline"), command.args)
        assertFalse(command.background)
        assertEquals(1, termuxRaised)
        assertEquals(SignInPending("cline", startMs), store.pending)
    }

    @Test fun `exit code 0 is success, with the selection defaulted and the catalog invalidated`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.Account)
        runCurrent()
        termux.detached.single().exitWith(TermuxResult.success(""))
        runCurrent()
        assertEquals(SignInState.Succeeded("cline", SignInOrigin.Account, viaApiKey = false, verified = true), c.state.value)
        assertNull(store.pending)
        assertTrue(invalidations >= 1)
        assertEquals(ModelSelection("cline", mapOf("cline" to "anthropic/claude-sonnet-5")), selectionPrefs.selectionFlow.value)
        assertEquals(listOf(SignInCompletion("cline", SignInOrigin.Account, appInForeground = true)), completions)
        c.acknowledge()
        assertEquals(SignInState.Idle, c.state.value)
    }

    @Test fun `an existing selection is kept, except that onboarding points it at Cline`() = runTest {
        presence(null)
        selectionPrefs.selectionFlow.value = ModelSelection("anthropic", mapOf("anthropic" to "claude"))
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        termux.detached.single().exitWith(TermuxResult.success(""))
        runCurrent()
        assertEquals("anthropic", selectionPrefs.selectionFlow.value.lastProvider)
        c.acknowledge()
        c.start("openai-codex", SignInOrigin.Onboarding)
        runCurrent()
        termux.detached.last().exitWith(TermuxResult.success(""))
        runCurrent()
        assertEquals("anthropic", selectionPrefs.selectionFlow.value.lastProvider)
        c.acknowledge()
        c.start("cline", SignInOrigin.Onboarding)
        runCurrent()
        termux.detached.last().exitWith(TermuxResult.success(""))
        runCurrent()
        assertEquals(ModelSelection("cline", mapOf("anthropic" to "claude")), selectionPrefs.selectionFlow.value)
    }

    @Test fun `a non-zero exit fails with the exit code`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        termux.detached.single().exitWith(TermuxResult.failure(1, "Error: device code expired"))
        runCurrent()
        assertEquals(SignInState.Failed(SignInFailure.ExitCode(1), SignInState.Failed.Kind.SignIn), c.state.value)
        assertNull(store.pending)
    }

    @Test fun `a delivery error fails with Termux's message`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        termux.detached.single().exitWith(TermuxResult.undelivered(2, "Termux session limit reached"))
        runCurrent()
        assertEquals(SignInState.Failed(SignInFailure.Termux("Termux session limit reached"), SignInState.Failed.Kind.SignIn), c.state.value)
    }

    @Test fun `already signed in short-circuits with the cached name`() = runTest {
        presence(providersJson(clineToken = false, apiKey = true))
        store.account = "Ada" to null
        val c = coordinator()
        c.start("cline-pass", SignInOrigin.Onboarding)
        runCurrent()
        assertEquals(SignInState.AlreadySignedIn("cline", "Ada"), c.state.value)
        assertTrue(termux.detached.isEmpty())
    }

    @Test fun `Termux missing blocks the sheet`() = runTest {
        termux.presence = TermuxPresence.Missing
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        assertTrue((c.state.value as SignInState.Blocked).error is TermuxUnavailable.NotInstalled)
    }

    @Test fun `a second start while waiting only raises Termux again`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        c.start("cline", SignInOrigin.Account)
        runCurrent()
        assertEquals(1, termux.detached.size)
        assertEquals(2, termuxRaised)
        assertTrue(c.openTermux())
        assertEquals(3, termuxRaised)
    }

    @Test fun `a second start during the pre-flight read does not launch a second sign-in`() = runTest {
        val gate = CompletableDeferred<Unit>()
        termux.on(ClineCommands.Labels.READ_PROVIDER_PRESENCE) {
            gate.await()
            TermuxResult.failure(1, "cat: providers.json: No such file or directory")
        }
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        c.start("cline", SignInOrigin.Account)
        c.submitApiKey("sk-test", SignInOrigin.Account)
        runCurrent()
        assertEquals(SignInState.Idle, c.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, termux.detached.size)
        assertEquals(SignInState.WaitingForTermux("cline", startMs), c.state.value)
    }

    @Test fun `a start after a finished pre-flight can run again`() = runTest {
        presence(providersJson(clineToken = true))
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        assertTrue(c.state.value is SignInState.AlreadySignedIn)
        c.acknowledge()
        presence(null)
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        assertEquals(1, termux.detached.size)
    }

    @Test fun `the stat poll reads providers json once per new write`() = runTest {
        presence(providersJson(clineToken = false, updatedAt = "2026-10-03T11:00:00Z"))
        var mtime = startMs / 1000 - 30
        termux.on(ClineCommands.Labels.STAT_PROVIDERS) { TermuxResult.success("$mtime\n") }
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        val readsAfterStart = termux.commandsLabelled(ClineCommands.Labels.READ_PROVIDER_PRESENCE).size
        advanceTimeBy(3_001)
        assertEquals(readsAfterStart, termux.commandsLabelled(ClineCommands.Labels.READ_PROVIDER_PRESENCE).size)
        // A write after the start, but without a token yet: one read, still waiting.
        mtime = startMs / 1000 + 2
        advanceTimeBy(3_000)
        assertEquals(readsAfterStart + 1, termux.commandsLabelled(ClineCommands.Labels.READ_PROVIDER_PRESENCE).size)
        assertTrue(c.state.value is SignInState.WaitingForTermux)
        // Same mtime again: no second read.
        advanceTimeBy(3_000)
        assertEquals(readsAfterStart + 1, termux.commandsLabelled(ClineCommands.Labels.READ_PROVIDER_PRESENCE).size)
        // The token lands.
        presence(providersJson(clineToken = true))
        mtime = startMs / 1000 + 5
        advanceTimeBy(3_000)
        assertEquals(SignInState.Succeeded("cline", SignInOrigin.ProviderDetail, viaApiKey = false, verified = true), c.state.value)
    }

    @Test fun `the poll pauses while the app is stopped and resume reads once`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        c.onAppStopped()
        advanceTimeBy(30_000)
        assertTrue(termux.commandsLabelled(ClineCommands.Labels.STAT_PROVIDERS).isEmpty())
        presence(providersJson(clineToken = true))
        c.onAppResumed()
        runCurrent()
        assertTrue(c.state.value is SignInState.Succeeded)
        assertEquals(listOf(SignInCompletion("cline", SignInOrigin.ProviderDetail, appInForeground = true)), completions)
    }

    @Test fun `a success while in the background is reported for the notification`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.Account)
        runCurrent()
        c.onAppStopped()
        termux.detached.single().exitWith(TermuxResult.success(""))
        runCurrent()
        assertEquals(listOf(SignInCompletion("cline", SignInOrigin.Account, appInForeground = false)), completions)
    }

    @Test fun `stat timeouts back off to 10 s`() = runTest {
        presence(null)
        termux.never(ClineCommands.Labels.STAT_PROVIDERS)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        // Two polls that each time out after the command's own timeout.
        val statTimeout = ClineCommands.statProviders().timeoutMs
        advanceTimeBy(2 * (3_000 + statTimeout) + 1)
        assertEquals(2, termux.commandsLabelled(ClineCommands.Labels.STAT_PROVIDERS).size)
        advanceTimeBy(3_001)
        assertEquals(2, termux.commandsLabelled(ClineCommands.Labels.STAT_PROVIDERS).size)
        advanceTimeBy(7_000)
        assertEquals(3, termux.commandsLabelled(ClineCommands.Labels.STAT_PROVIDERS).size)
        assertTrue(c.state.value is SignInState.WaitingForTermux)
    }

    @Test fun `cancel kills the Bun child and returns to idle`() = runTest {
        presence(null)
        termux.on(ClineCommands.Labels.CANCEL_AUTH) { TermuxResult.failure(1, "") }
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        c.cancel()
        runCurrent()
        assertEquals(SignInState.Idle, c.state.value)
        val kill = termux.commandsLabelled(ClineCommands.Labels.CANCEL_AUTH).single()
        assertEquals(listOf("-f", "index.js auth (cline|openai-codex|oca)$"), kill.args)
        assertNull(store.pending)
        assertTrue(termux.detached.single().cancelled)
    }

    @Test fun `cancel retries when Termux does not run pkill`() = runTest {
        presence(null)
        var attempts = 0
        termux.on(ClineCommands.Labels.CANCEL_AUTH) {
            attempts += 1
            if (attempts < 3) TermuxResult.undelivered(1, "busy") else TermuxResult.success("")
        }
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        c.cancel()
        advanceTimeBy(2_000)
        assertEquals(3, attempts)
        assertEquals(SignInState.Idle, c.state.value)
    }

    @Test fun `a sign-in that won the race against cancel is a success`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        presence(providersJson(clineToken = true))
        c.cancel()
        runCurrent()
        assertTrue(c.state.value is SignInState.Succeeded)
    }

    @Test fun `after 15 minutes the sign-in is cancelled and times out`() = runTest {
        presence(null)
        val c = coordinator()
        c.start("cline", SignInOrigin.ProviderDetail)
        runCurrent()
        advanceTimeBy(15 * 60_000L + 1)
        assertEquals(SignInState.Failed(SignInFailure.TimedOut, SignInState.Failed.Kind.SignIn), c.state.value)
        assertTrue(termux.ran(ClineCommands.Labels.CANCEL_AUTH))
        assertNull(store.pending)
    }

    @Test fun `a pending sign-in survives process death`() = runTest {
        store.pending = SignInPending("cline", startMs - 60_000)
        presence(providersJson(clineToken = true, updatedAt = "2026-10-03T11:59:30Z"))
        val c = coordinator()
        c.restore()
        runCurrent()
        assertEquals(SignInState.Succeeded("cline", SignInOrigin.ProviderDetail, viaApiKey = false, verified = true), c.state.value)
        assertNull(store.pending)
    }

    @Test fun `a recent pending sign-in without a token keeps waiting`() = runTest {
        store.pending = SignInPending("oca", startMs - 60_000)
        presence(null)
        val c = coordinator()
        c.restore()
        runCurrent()
        assertEquals(SignInState.WaitingForTermux("oca", startMs - 60_000), c.state.value)
        // The 15 minutes count from the original start.
        advanceTimeBy(14 * 60_000L + 1)
        assertTrue(c.state.value is SignInState.Failed)
    }

    @Test fun `a stale pending sign-in is forgotten`() = runTest {
        store.pending = SignInPending("cline", startMs - 20 * 60_000)
        presence(null)
        val c = coordinator()
        c.restore()
        runCurrent()
        assertEquals(SignInState.Idle, c.state.value)
        assertNull(store.pending)
    }

    // ---- Use a Cline API key ---------------------------------------------------------------

    @Test fun `helper route saves the key, verifies with fetchMe and caches the account`() = runTest {
        helper.state.value = HelperState.Ready
        helper.respond(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        helper.respond(
            ClineSignInCoordinator.CLINE_ACCOUNT,
            Json.parseToJsonElement("""{"id":"u1","email":"ada@example.com","displayName":"","organizations":[{"active":true,"name":"Analytical","organizationId":"o"}]}"""),
        )
        val c = coordinator()
        c.submitApiKey(" sk-cline-123 ", SignInOrigin.Onboarding)
        runCurrent()
        assertEquals(SignInState.Succeeded("cline", SignInOrigin.Onboarding, viaApiKey = true, verified = true), c.state.value)
        val save = helper.callsFor(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS).single().args
        assertEquals(JsonPrimitive("sk-cline-123"), save["api_key"])
        assertEquals(JsonPrimitive(true), save["enabled"])
        assertEquals(ClineSignInCoordinator.FETCH_ME_ARGS, helper.callsFor(ClineSignInCoordinator.CLINE_ACCOUNT).single().args)
        assertEquals("ada@example.com" to "Analytical", store.account)
        assertFalse(c.state.value.toString().contains("sk-cline"))
    }

    @Test fun `an unverifiable key is rolled back`() = runTest {
        helper.state.value = HelperState.Ready
        helper.respond(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        helper.fail(ClineSignInCoordinator.CLINE_ACCOUNT, "No Cline account auth token found")
        val c = coordinator()
        c.submitApiKey("bad", SignInOrigin.Onboarding)
        runCurrent()
        assertEquals(
            SignInState.Failed(SignInFailure.KeyNotVerified("No Cline account auth token found"), SignInState.Failed.Kind.ApiKey),
            c.state.value,
        )
        val saves = helper.callsFor(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS)
        assertEquals(2, saves.size)
        assertEquals(JsonPrimitive(""), saves[1].args["api_key"])
    }

    @Test fun `a failed key save reports the dashboard error`() = runTest {
        helper.state.value = HelperState.Ready
        helper.fail(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS, "permission denied")
        val c = coordinator()
        c.submitApiKey("k", SignInOrigin.Account)
        runCurrent()
        assertEquals(SignInState.Failed(SignInFailure.KeySaveFailed("permission denied"), SignInState.Failed.Kind.ApiKey), c.state.value)
    }

    @Test fun `without the helper the key goes through cline auth, unverified`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        termux.on(ClineCommands.authLabel("cline")) { TermuxResult.success("Provider configured: cline") }
        val c = coordinator()
        c.submitApiKey("sk-1", SignInOrigin.Onboarding)
        runCurrent()
        assertEquals(SignInState.Succeeded("cline", SignInOrigin.Onboarding, viaApiKey = true, verified = false), c.state.value)
        val command = termux.commandsLabelled(ClineCommands.authLabel("cline")).single()
        assertEquals(listOf("auth", "--provider", "cline", "--apikey", "sk-1", "--modelid", "anthropic/claude-sonnet-5"), command.args)
        assertFalse(command.toString().contains("sk-1"))
    }

    @Test fun `a CLI key save failure shows the last stderr line`() = runTest {
        selectionPrefs.selectionFlow.value = ModelSelection("cline", mapOf("cline" to "x/model"))
        termux.on(ClineCommands.authLabel("cline")) { TermuxResult.failure(1, "warning\ninvalid api key\n") }
        val c = coordinator()
        c.submitApiKey("k", SignInOrigin.Account)
        runCurrent()
        assertEquals(SignInState.Failed(SignInFailure.KeySaveFailed("invalid api key"), SignInState.Failed.Kind.ApiKey), c.state.value)
        assertEquals("x/model", termux.commandsLabelled(ClineCommands.authLabel("cline")).single().args.last())
    }

    @Test fun `a CLI key save timeout says Termux did not answer`() = runTest {
        termux.never(ClineCommands.authLabel("cline"))
        val c = coordinator()
        c.submitApiKey("k", SignInOrigin.Account)
        runCurrent()
        advanceTimeBy(ClineCommands.authProvider("cline", "k", "m").timeoutMs + 1)
        assertEquals(SignInState.Failed(SignInFailure.TermuxDidNotAnswer, SignInState.Failed.Kind.ApiKey), c.state.value)
    }

    // ---- sign out and helpers ------------------------------------------------------------------

    @Test fun `sign out disables cline, clears the account and invalidates`() = runTest {
        helper.state.value = HelperState.Ready
        helper.respond(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        store.account = "Ada" to "Org"
        val c = coordinator()
        assertTrue(c.signOut().isSuccess)
        assertEquals(JsonPrimitive(false), helper.callsFor(ClineSignInCoordinator.SAVE_PROVIDER_SETTINGS).single().args["enabled"])
        assertEquals(null to null, store.account)
        assertEquals(1, invalidations)
        helper.state.value = HelperState.Idle
        assertTrue(c.signOut().isFailure)
    }

    @Test fun `signed-in predicates follow S7_3`() {
        val providers = ConfiguredProviders.parse(
            """{"providers":{"cline":{"settings":{"apiKey":"k"},"updatedAt":"2026-10-03T12:00:00Z"},
                "oca":{"settings":{"apiKey":"k"}},"openai-codex":{"settings":{"auth":{"accessToken":"t"}},"updatedAt":"1759492800000"}}}""",
        )
        assertTrue(ClineSignInCoordinator.isSignedIn(providers, "cline"))
        assertFalse(ClineSignInCoordinator.isSignedIn(providers, "oca"))
        assertTrue(ClineSignInCoordinator.isSignedIn(providers, "openai-codex"))
        assertTrue(ClineSignInCoordinator.signedInSince(providers, "cline", startMs))
        assertFalse(ClineSignInCoordinator.signedInSince(providers, "cline", startMs + 1))
        assertEquals("cline", ClineSignInCoordinator.signInProviderId(" cline-pass "))
        assertEquals("Ada" to null, ClineSignInCoordinator.accountSummary(Json.parseToJsonElement("""{"displayName":"Ada","organizations":[{"active":false,"name":"X"}]}""")))
        assertNull(ClineSignInCoordinator.accountSummary(JsonPrimitive("x")))
    }
}
