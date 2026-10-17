package dev.cline.termux.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.cline.termux.appGraph
import dev.cline.termux.data.account.TermuxForeground
import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.hub.HubSocket
import dev.cline.termux.core.kernel.hub.HubTransport
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.secure.SecretBox
import dev.cline.termux.debug.fake.FakeKernelScenario
import dev.cline.termux.debug.fake.FakeKernelSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug")

/**
 * Debug-only kernel overrides. The release source set has the same object with identical
 * signatures and pass-through bodies, so `AppGraph` and `MainActivity` compile against both.
 *
 * 1. Developer Hub override (docs/06 "Developer Hub override"): the token pushed by
 *    `scripts/dev-hub.sh reverse` through [HubTokenOverrideReceiver] is kept sealed in the
 *    `debug` DataStore and replaces the `production.json` read in the kernel coordinator;
 *    the port pushed with it ([hubPort]) replaces 25463.
 * 2. Debug fake kernel (onboarding-kernel.md §0.9): a scripted Termux + Hub selected with
 *    `adb shell am start -S -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity --es dev.cline.termux.FAKE_KERNEL <scenario>
 *    [--ez dev.cline.termux.RESET_ONBOARDING true]`; `--es dev.cline.termux.FAKE_KERNEL off`
 *    returns to the real kernel. The choice persists across process restarts until `off`.
 *    `AppGraph` wraps its seams with [termuxExecutor], [kernelCommands], [hubProber] and
 *    [hubTransport]; each wrapper asks the active scenario on every call, so the real
 *    implementations run whenever no scenario is selected.
 * 3. Developer settings helper: `--es dev.cline.termux.HELPER_SECRET <32 hex>` on a cold
 *    start (`am start -S`) makes the settings helper use that room secret instead of a
 *    random one, so it adopts the dashboard `scripts/dev-hub.sh helper` runs on the
 *    workstation (reached through `adb reverse tcp:8787`) against the isolated dev home.
 *    Held in memory only (never DataStore or logs) and gone with the process.
 */
object KernelOverrides {
    const val EXTRA_FAKE_KERNEL = "dev.cline.termux.FAKE_KERNEL"
    const val EXTRA_RESET_ONBOARDING = "dev.cline.termux.RESET_ONBOARDING"
    const val EXTRA_HELPER_SECRET = "dev.cline.termux.HELPER_SECRET"
    private val HELPER_SECRET_PATTERN = Regex("[0-9a-f]{32}")

    private const val TAG = "ClineFakeKernel"
    private const val PREFS = "debug_fake_kernel"
    private const val KEY_SCENARIO = "scenario"

    private val box by lazy { SecretBox(ALIAS) }

    private val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val switch = FakeKernelSwitch(fakeScope) { label -> Log.i(TAG, "RUN_COMMAND $label") }

    @Volatile private var loaded = false
    @Volatile private var onboardingResetDone = false
    @Volatile private var helperSecret: String? = null

    /** The developer settings-helper secret of this process (item 3), or null for a random one. */
    fun helperRoomSecret(): String? = helperSecret

    // ---- developer Hub override ------------------------------------------------------------

    /**
     * The override token, or null when none is set (the normal case). Never logged. A fake
     * kernel scenario (item 2) has its own Hub with its own `production.json` token, so the
     * developer token is set aside while one runs; otherwise every scripted Hub would reject
     * the app once `scripts/dev-hub.sh reverse` has pushed a token.
     */
    suspend fun hubToken(context: Context): String? {
        ensureLoaded(context)
        if (switch.scenario != null) return null
        return overrideStore(context).token()
    }

    /**
     * The developer Hub port (`--ei port`, stored with the token), or null for the default
     * 25463. `AppGraph` reads it once while it builds the kernel coordinator, so a change takes
     * effect at the next cold start (`scripts/dev-hub.sh reverse` force-stops the app). With
     * it the bench Hub is reached through `adb reverse tcp:25498 tcp:25498` and the phone's own
     * 25463 stays the real Hub's. Set aside while a fake kernel scenario runs, like the token.
     */
    fun hubPort(context: Context): Int? {
        ensureLoaded(context)
        if (switch.scenario != null) return null
        return runBlocking(Dispatchers.IO) { overrideStore(context).port() }
    }

    suspend fun setHubToken(context: Context, token: String, port: Int? = null) {
        overrideStore(context).set(token, port)
    }

    /** Clears the token and the port override. */
    suspend fun clearHubToken(context: Context) {
        overrideStore(context).clear()
    }

    private fun overrideStore(context: Context) =
        HubOverrideStore(context.applicationContext.debugDataStore, seal = box::seal, open = box::open)

    // ---- debug fake kernel --------------------------------------------------------------------

    /**
     * Called once at the top of `MainActivity.onCreate`. Applies [EXTRA_FAKE_KERNEL] (a §0.9
     * scenario id, or `off`) and [EXTRA_RESET_ONBOARDING]. Selecting a scenario also clears
     * `kernel.userStopped`, so the scenario's auto-start runs as the checklists expect. The
     * onboarding reset runs once per process, so a configuration change does not repeat it.
     */
    fun applyLaunchIntent(context: Context, intent: Intent?) {
        ensureLoaded(context)
        if (intent == null) return
        intent.getStringExtra(EXTRA_HELPER_SECRET)?.trim()?.let { secret ->
            if (HELPER_SECRET_PATTERN.matches(secret)) {
                helperSecret = secret
                Log.i(TAG, "developer settings-helper secret set")
            } else {
                Log.w(TAG, "ignored $EXTRA_HELPER_SECRET: expected 32 lowercase hex characters")
            }
        }
        if (intent.hasExtra(EXTRA_FAKE_KERNEL)) {
            val id = intent.getStringExtra(EXTRA_FAKE_KERNEL)
            if (id == FakeKernelScenario.OFF) {
                val changed = switch.scenario != null
                persist(context, null)
                switch.select(null)
                if (changed) context.appGraph.providerCatalog.invalidate()
                Log.i(TAG, "fake kernel off")
            } else {
                val scenario = FakeKernelScenario.fromId(id)
                if (scenario == null) {
                    Log.w(TAG, "unknown fake kernel scenario '$id'; known: ${FakeKernelScenario.entries.joinToString { it.id }}")
                } else {
                    val changed = switch.scenario != scenario
                    persist(context, scenario.id)
                    switch.select(scenario)
                    if (changed) {
                        blockingPrefs(context) { setUserStopped(false) }
                        // Another scenario is another providers.json: drop what the catalog read before.
                        context.appGraph.providerCatalog.invalidate()
                    }
                    Log.i(TAG, "fake kernel scenario ${scenario.id}")
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_RESET_ONBOARDING, false) && !onboardingResetDone) {
            onboardingResetDone = true
            blockingPrefs(context) {
                setOnboardingCompleted(null)
                setOnboardingSignInStartedAt(null)
                setOnboardingReliabilityShownAt(null)
            }
            Log.i(TAG, "onboarding reset")
        }
    }

    /** The active scenario id, or null for the real kernel. */
    fun fakeKernelScenario(context: Context): String? {
        ensureLoaded(context)
        return switch.scenario?.id
    }

    /** RUN_COMMAND labels the active scenario has run (labels only). */
    fun fakeKernelTrace(): List<String> = switch.current?.trace.orEmpty()

    /** The RUN_COMMAND bridge `AppGraph.termux` is built from. */
    fun termuxExecutor(context: Context, real: TermuxExecutor): TermuxExecutor {
        ensureLoaded(context)
        return switch.executor(real)
    }

    /** The coordinator's command surface (`AppGraph.kernelCommands`). */
    fun kernelCommands(context: Context, real: KernelCommands): KernelCommands {
        ensureLoaded(context)
        return switch.commands(real)
    }

    /**
     * The sign-in coordinator's "bring Termux forward" (S7.3 step 2). While a fake scenario
     * runs it starts nothing, so the checklists never open the real Termux UI.
     */
    fun termuxForeground(context: Context, real: TermuxForeground): TermuxForeground {
        ensureLoaded(context)
        return TermuxForeground {
            if (switch.scenario != null) {
                Log.i(TAG, "fake kernel: Termux activity start skipped")
                true
            } else {
                real.bringToFront()
            }
        }
    }

    /** The loopback `/health` + `/version` probe (`AppGraph.hubProber`). */
    fun hubProber(context: Context, real: HubProber): HubProber {
        ensureLoaded(context)
        return switch.prober(real)
    }

    /**
     * Instrumented UI tests (docs/07 §10.2): while a fake-kernel scenario runs, sockets come from
     * here instead of the scenario's scripted socket, so a test replays recorded Hub traffic
     * (`docs/08-hub-fixtures.md`) through the real `HubClient` and `SessionStore`. Null otherwise.
     */
    @Volatile var testHubSockets: (suspend (url: String, token: String?) -> HubSocket)? = null

    /** The Hub WebSocket transport the `HubClient` is built with. */
    fun hubTransport(context: Context, real: HubTransport): HubTransport {
        ensureLoaded(context)
        val scripted = switch.transport(real)
        return object : HubTransport {
            override suspend fun open(url: String, token: String?): HubSocket {
                val test = testHubSockets
                return if (test != null && switch.scenario != null) test(url, token) else scripted.open(url, token)
            }
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val id = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCENARIO, null)
            switch.select(FakeKernelScenario.fromId(id))
            loaded = true
        }
    }

    private fun persist(context: Context, id: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(KEY_SCENARIO) else putString(KEY_SCENARIO, id)
        }.commit()
    }

    /** Debug tooling only: a launch-time DataStore write that must land before the start destination is read. */
    private fun blockingPrefs(context: Context, block: suspend dev.cline.termux.data.prefs.AppPreferences.() -> Unit) {
        runBlocking(Dispatchers.IO) { context.appGraph.prefs.block() }
    }

    private const val ALIAS = "dev.cline.termux.debug-hub-token"
}
