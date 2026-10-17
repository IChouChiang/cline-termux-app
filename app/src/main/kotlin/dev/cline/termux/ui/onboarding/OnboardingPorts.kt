package dev.cline.termux.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelClock
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.account.ClineSignInCoordinator
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.providers.CatalogLoad
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.ProviderCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/*
 * The seams the onboarding ViewModels depend on (docs/07 §0 rule 8: nothing in ui/ talks to
 * HubClient or TermuxExecutor directly). Each interface has one production adapter over the
 * AppGraph members and is faked in the unit tests.
 */

/** What Kernel setup (A), the repair variants and Install Termux (B) need from the kernel. */
interface SetupKernel {
    /** `KernelCoordinator.state`: the kernel group's ViewModels read the state itself. */
    val state: StateFlow<KernelState>

    /** The last `VERSION` read (row 4's "Cline kernel %1$s"). */
    val portVersion: StateFlow<PortVersion?>

    /** P1, synchronous (`PackageManager`). */
    fun termuxPresence(): TermuxPresence

    /** P2, synchronous (`checkSelfPermission`). */
    fun hasRunCommandPermission(): Boolean

    /** P1–P3 and a connect to a Hub that already answers; never starts one. */
    suspend fun recheck()

    /** "Start kernel" / "Try again": `ensureConnected(startIfDown = true, userInitiated = true)`. */
    suspend fun start()

    /** The repair variant's automatic start: policy decides (`kernel.userStopped`, crash loop). */
    suspend fun autoStart()

    /** E.9 inline ("Restart kernel" on a protocol-incompatible Hub): E.7 from step 4. */
    suspend fun restartForProtocol(): RestartOutcome

    /** Monotonic time for the row-5 elapsed counter. */
    fun elapsedMs(): Long

    companion object {
        fun of(kernel: KernelCoordinator, clock: KernelClock, termux: () -> TermuxPresence, permission: () -> Boolean): SetupKernel = object : SetupKernel {
            override val state: StateFlow<KernelState> get() = kernel.state
            override val portVersion: StateFlow<PortVersion?> get() = kernel.portVersion
            override fun termuxPresence(): TermuxPresence = termux()
            override fun hasRunCommandPermission(): Boolean = permission()
            override suspend fun recheck() = kernel.recheck().let { }
            override suspend fun start() = kernel.ensureConnected(startIfDown = true, userInitiated = true).let { }
            override suspend fun autoStart() = kernel.ensureConnected(startIfDown = true, userInitiated = false).let { }
            override suspend fun restartForProtocol(): RestartOutcome = kernel.restart(forced = false)
            override fun elapsedMs(): Long = clock.elapsedMs()
        }

        fun of(graph: AppGraph): SetupKernel = of(
            kernel = graph.kernel,
            clock = graph.kernelClock,
            termux = { graph.termux.isTermuxInstalled() },
            permission = { graph.termux.hasPermission() },
        )
    }
}

/** The onboarding keys of the `app` DataStore this group writes (KS §0.8). */
interface OnboardingPrefs {
    suspend fun reliabilityShownAt(): Long?
    suspend fun markReliabilityShown()

    companion object {
        fun of(prefs: AppPreferences): OnboardingPrefs = object : OnboardingPrefs {
            override suspend fun reliabilityShownAt(): Long? = prefs.onboardingReliabilityShownAt()
            override suspend fun markReliabilityShown() {
                prefs.setOnboardingReliabilityShownAt()
            }
        }
    }
}

/**
 * D.4: "verifiable" checklist items 1, 2 and 5 (Termux battery, Cline battery, notifications)
 * are all done. The checklist shows once during onboarding only when one of them is not.
 */
fun interface ReliabilityProbe {
    fun verifiableItemsDone(): Boolean

    companion object {
        fun of(context: Context): ReliabilityProbe = ReliabilityProbe {
            val power = context.getSystemService(PowerManager::class.java)
            val termuxUnrestricted = runCatching { power?.isIgnoringBatteryOptimizations(TermuxPaths.PACKAGE) == true }.getOrDefault(false)
            val clineUnrestricted = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
            val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
                )
            termuxUnrestricted && clineUnrestricted && notifications
        }
    }
}

/** What C needs for the "Use your own API key" card and the configured-provider state. */
interface ProviderSetup {
    /** `readProviders` (`cat providers.json`), reduced to the redacted projection inside the bridge. */
    suspend fun readPresence(): ProviderPresence

    /** The dropdown rows: helper catalog when the helper runs, else the bundled list (C.7). */
    suspend fun loadOptions(force: Boolean): OptionsResult

    /** The bundled list alone (the helper's catalog failed, C.5 "error: provider catalog"). */
    suspend fun bundledOptions(): List<ProviderOption>

    /** `cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]` (30 s). */
    suspend fun saveProvider(providerId: String, apiKey: String, modelId: String, baseUrl: String?): SaveResult

    /** `rememberProviderSelection` → `model.selection`. */
    suspend fun rememberSelection(providerId: String, modelId: String?)

    /** The cached `fetchMe` display name (`account.displayName`), for "Signed in as %1$s". */
    suspend fun accountDisplayName(): String?

    sealed interface OptionsResult {
        data class Loaded(val options: List<ProviderOption>, val source: ProviderSource) : OptionsResult
        data class CatalogFailed(val message: String) : OptionsResult
    }

    sealed interface SaveResult {
        data object Saved : SaveResult

        /** Kernel text: the last non-blank stderr line (ANSI stripped by the bridge). */
        data class Failed(val detail: String) : SaveResult

        /** RUN_COMMAND did not answer within 30 s. */
        data object TimedOut : SaveResult

        /** Termux cannot run commands (missing, permission, external apps). */
        data class Unavailable(val error: TermuxUnavailable) : SaveResult
    }

    companion object {
        fun of(
            termux: TermuxExecutor,
            catalog: ProviderCatalogRepository,
            bundled: suspend () -> FallbackProviders,
            modelSelection: ModelSelectionStore,
            prefs: AppPreferences,
        ): ProviderSetup = object : ProviderSetup {
            // Through the shared catalog, so the one read also updates what Home's setup notice and
            // the model picker see (C.6 "refresh the configured-provider state so the setup notice
            // disappears"); TermuxUnavailable comes back as null.
            override suspend fun readPresence(): ProviderPresence = catalog.refreshPresence() ?: ProviderPresence.Failed(null)

            override suspend fun loadOptions(force: Boolean): OptionsResult = when (val load = catalog.load(force)) {
                is CatalogLoad.Loaded -> if (load.source == CatalogSource.Catalog) {
                    OptionsResult.Loaded(ProviderOptions.fromCatalog(load.providers), ProviderSource.Helper)
                } else {
                    OptionsResult.Loaded(bundledOptions(), ProviderSource.Bundled)
                }
                is CatalogLoad.Failed -> OptionsResult.CatalogFailed(load.message)
                else -> OptionsResult.Loaded(bundledOptions(), ProviderSource.Bundled)
            }

            override suspend fun bundledOptions(): List<ProviderOption> = ProviderOptions.bundled(
                try {
                    bundled()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    FallbackProviders.EMPTY
                },
            )

            override suspend fun saveProvider(providerId: String, apiKey: String, modelId: String, baseUrl: String?): SaveResult = try {
                val result = termux.run(ClineCommands.authProvider(providerId, apiKey, modelId, baseUrl))
                when {
                    result.ok -> SaveResult.Saved
                    result.internalError -> SaveResult.Failed(result.errmsg?.trim().orEmpty().ifEmpty { result.exitCode.toString() })
                    else -> SaveResult.Failed(ConnectValidation.lastLine(result.stderr) ?: ConnectValidation.lastLine(result.stdout) ?: result.exitCode.toString())
                }
            } catch (e: TermuxUnavailable.TimedOut) {
                SaveResult.TimedOut
            } catch (e: TermuxUnavailable) {
                SaveResult.Unavailable(e)
            }

            override suspend fun rememberSelection(providerId: String, modelId: String?) {
                modelSelection.rememberProviderSelection(providerId, modelId)
            }

            override suspend fun accountDisplayName(): String? = prefs.account.first().first
        }

        fun of(graph: AppGraph, context: Context): ProviderSetup = of(
            termux = graph.termux,
            catalog = graph.providerCatalog,
            bundled = { FallbackProviders.load(context.applicationContext) },
            modelSelection = graph.modelSelection,
            prefs = graph.prefs,
        )
    }
}

/** The process-wide Cline sign-in ([ClineSignInCoordinator], SP S7) as Connect a model uses it. */
interface ClineSignIn {
    val state: StateFlow<SignInState>
    fun start()
    fun cancel()
    fun submitApiKey(apiKey: String)
    fun openTermux(): Boolean
    fun acknowledge()

    companion object {
        fun of(coordinator: ClineSignInCoordinator): ClineSignIn = object : ClineSignIn {
            override val state: StateFlow<SignInState> get() = coordinator.state
            override fun start() = coordinator.start(ClineSignInCoordinator.CLINE, SignInOrigin.Onboarding)
            override fun cancel() = coordinator.cancel()
            override fun submitApiKey(apiKey: String) = coordinator.submitApiKey(apiKey, SignInOrigin.Onboarding)
            override fun openTermux(): Boolean = coordinator.openTermux()
            override fun acknowledge() = coordinator.acknowledge()
        }
    }
}
