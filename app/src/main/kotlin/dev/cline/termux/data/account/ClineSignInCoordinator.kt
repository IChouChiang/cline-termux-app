package dev.cline.termux.data.account

import android.content.ActivityNotFoundException
import android.content.Context
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.core.kernel.termux.readProviderPresence
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.providers.CatalogHelper
import dev.cline.termux.data.providers.OAUTH_LOGIN_TIMEOUT_MS
import dev.cline.termux.data.providers.ProviderCatalogRepository
import dev.cline.termux.data.schedules.CLINE_DEFAULT_MODEL_ID
import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsString
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Sign in with Cline (settings-providers.md S7; onboarding-kernel.md C.6–C.8 for the polling
 * details). The WorkOS device-code flow runs as `cline auth <cline|openai-codex|oca>` in a
 * visible Termux session that the app brings to the front (OQ 5); the app never sees the
 * code or the tokens. Completion is keyed on the foreground session's exit code 0 or on
 * `providers.json` (a token whose `updatedAt` is at or after the start), never on output text.
 * One process-wide instance: S5, Account, Connect a model and the sheet observe one [state],
 * and waiting survives dismissing the sheet.
 */

/** Where a sign-in was started; decides what success does in the UI (S7.1). */
enum class SignInOrigin { Onboarding, ProviderDetail, Account, Chat, WelcomeNotice }

/** Why a sign-in or key save failed; the UI renders it inside "Sign in failed: %1$s" / "Failed to save API key: %1$s". */
sealed interface SignInFailure {
    /** The Termux session ended with this exit code: "Sign-in did not finish in Termux (exit code <n>)." */
    data class ExitCode(val code: Int) : SignInFailure

    /** RUN_COMMAND `err != -1`: Termux's own message, verbatim. */
    data class Termux(val errmsg: String) : SignInFailure

    /** 15 minutes passed (`OAUTH_LOGIN_TIMEOUT_MS`): "The sign-in timed out." */
    data object TimedOut : SignInFailure

    /** The saved key failed `fetchMe` and was rolled back: "the key could not be verified (<error>)". */
    data class KeyNotVerified(val error: String) : SignInFailure

    /** The key could not be saved: the helper's error or the CLI's last stderr line, verbatim. */
    data class KeySaveFailed(val message: String) : SignInFailure

    /** RUN_COMMAND did not answer in time while saving the key. */
    data object TermuxDidNotAnswer : SignInFailure
}

/** S7.4 coordinator state. */
sealed interface SignInState {
    data object Idle : SignInState

    /** `providers.json` already shows the provider connected (S7.3 step 1). */
    data class AlreadySignedIn(val providerId: String, val displayName: String?) : SignInState

    data class WaitingForTermux(val providerId: String, val startedAtMs: Long) : SignInState

    data class SavingKey(val providerId: String) : SignInState

    data class Failed(val failure: SignInFailure, val kind: Kind) : SignInState {
        enum class Kind { SignIn, ApiKey }
    }

    /**
     * Transient; consumed by the UI with [ClineSignInCoordinator.acknowledge]. [verified] is
     * false for a key saved by the CLI without the helper (KS C.6 "Done shows the extra line").
     */
    data class Succeeded(val providerId: String, val origin: SignInOrigin, val viaApiKey: Boolean, val verified: Boolean) : SignInState

    /** Termux missing, permission missing or blocking external apps: the sheet shows `KernelBlockedCard` (§0.5). */
    data class Blocked(val error: TermuxUnavailable) : SignInState
}

/** Emitted once per successful sign-in; the notifier posts "Signed in to Cline" when [appInForeground] is false (S7.3 step 5). */
data class SignInCompletion(val providerId: String, val origin: SignInOrigin, val appInForeground: Boolean)

/** The persistence the coordinator needs ([AppPreferences] in the app). */
interface SignInStore {
    suspend fun pending(): SignInPending?
    suspend fun setPending(pending: SignInPending?)

    /** `account.displayName` / `account.scope` (non-secret summary for S1). */
    suspend fun setAccount(displayName: String?, scope: String?)
    suspend fun accountDisplayName(): String?

    companion object {
        fun of(prefs: AppPreferences): SignInStore = object : SignInStore {
            override suspend fun pending(): SignInPending? = prefs.signInPending()
            override suspend fun setPending(pending: SignInPending?) {
                prefs.setSignInPending(pending)
            }
            override suspend fun setAccount(displayName: String?, scope: String?) {
                prefs.setAccount(displayName, scope)
            }
            override suspend fun accountDisplayName(): String? = prefs.account.first().first
        }
    }
}

/** Brings Termux's activity to the front (S7.3 step 2): Termux cannot raise itself. */
fun interface TermuxForeground {
    /** False when Termux's activity could not be started. */
    fun bringToFront(): Boolean
}

/** Starts [TermuxRunner.termuxActivityIntent] (`com.termux/.app.TermuxActivity` in a new task). */
class ActivityTermuxForeground(private val context: Context) : TermuxForeground {
    override fun bringToFront(): Boolean = try {
        context.startActivity(TermuxRunner.termuxActivityIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** What the coordinator invalidates after a sign-in (§0.7). */
fun interface CatalogInvalidator {
    fun invalidate()

    companion object {
        fun of(repository: ProviderCatalogRepository) = CatalogInvalidator { repository.invalidate() }
    }
}

class ClineSignInCoordinator(
    private val termux: TermuxExecutor,
    private val store: SignInStore,
    private val helper: CatalogHelper,
    private val catalog: CatalogInvalidator,
    private val modelSelection: ModelSelectionStore,
    private val termuxForeground: TermuxForeground,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val pollIntervalMs: Long = 3_000,
        /** After two consecutive `stat` timeouts the poll backs off (KS C.8). */
        val backoffIntervalMs: Long = 10_000,
        val timeoutMs: Long = OAUTH_LOGIN_TIMEOUT_MS,
        /** `cancelClineAuth` retries (desktop loop). */
        val cancelRetryDelaysMs: List<Long> = listOf(250, 500, 750),
    )

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _completions = MutableSharedFlow<SignInCompletion>(extraBufferCapacity = 4)
    val completions: SharedFlow<SignInCompletion> = _completions.asSharedFlow()

    private val transition = Mutex()

    /** Bumped by every start, cancel and finish; a late result of an older attempt is ignored. */
    private var attempt = 0
    private var origin: SignInOrigin = SignInOrigin.ProviderDetail
    private var watchJob: Job? = null
    private var handle: DetachedHandle? = null

    /** A start is between its pre-flight and WaitingForTermux; claimed under [transition] (S7.4). */
    @Volatile private var startInFlight = false

    @Volatile private var appInForeground = true

    // ---- lifecycle hooks (ProcessLifecycleOwner, wired by the app) -----------------------

    /** Recovers a sign-in interrupted by process death (`signin.pending`, S7.7). Call once at start. */
    fun restore() {
        scope.launch { restorePending() }
    }

    /** `ON_RESUME`: one `providers.json` read while waiting (S7.3 step 4, KS C.6 trigger b). */
    fun onAppResumed() {
        appInForeground = true
        if (_state.value is SignInState.WaitingForTermux) scope.launch { checkPresence(attempt) }
    }

    fun onAppStarted() {
        appInForeground = true
    }

    /** `ON_STOP`: the `stat` poll pauses until the app is started again. */
    fun onAppStopped() {
        appInForeground = false
    }

    // ---- UI intents ----------------------------------------------------------------------

    /**
     * Starts the Termux sign-in for [providerId] (`cline-pass` signs in as `cline`). A second
     * call while waiting only brings Termux to the front again (S7.4).
     */
    fun start(providerId: String, origin: SignInOrigin) {
        scope.launch { startNow(providerId, origin) }
    }

    /** "Open Termux" in the waiting state (S7.3 step 9). */
    fun openTermux(): Boolean = termuxForeground.bringToFront()

    /** "Cancel" while waiting (S7.3 step 7). */
    fun cancel() {
        scope.launch { cancelNow(timedOut = false) }
    }

    /** "Connect" on "Use a Cline API key". [apiKey] is never stored or put in [state]. */
    fun submitApiKey(apiKey: String, origin: SignInOrigin) {
        scope.launch { submitApiKeyNow(apiKey, origin) }
    }

    /** The UI consumed [SignInState.Succeeded], [SignInState.AlreadySignedIn], a failure or a block. */
    fun acknowledge() {
        _state.value.let {
            if (it is SignInState.Succeeded || it is SignInState.AlreadySignedIn || it is SignInState.Failed || it is SignInState.Blocked) {
                _state.compareAndSet(it, SignInState.Idle)
            }
        }
    }

    /**
     * Sign out (S5 for `cline`, Account): `save_provider_settings {provider:"cline", enabled:false}`,
     * clears the cached account summary, invalidates the catalog. Helper only (§0.4).
     */
    suspend fun signOut(providerId: String = CLINE): Result<Unit> {
        val helperState = helper.state.value.let { if (it is HelperState.Starting) helper.awaitSettled() else it }
        if (helperState !is HelperState.Ready) return Result.failure(IllegalStateException("settings helper is not running"))
        return try {
            helper.desktopCommand(SAVE_PROVIDER_SETTINGS, buildJsonObject { put("provider", providerId); put("enabled", false) })
            if (providerId == CLINE) store.setAccount(null, null)
            catalog.invalidate()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- the sequence --------------------------------------------------------------------

    internal suspend fun startNow(requestedId: String, origin: SignInOrigin) {
        val providerId = signInProviderId(requestedId)
        transition.withLock {
            val current = _state.value
            if (current is SignInState.WaitingForTermux) {
                termuxForeground.bringToFront()
                return
            }
            if (current is SignInState.SavingKey || startInFlight) return
            startInFlight = true
        }
        try {
            startClaimed(providerId, origin)
        } finally {
            startInFlight = false
        }
    }

    /** The body of [startNow] once it holds the single sign-in slot. */
    private suspend fun startClaimed(providerId: String, origin: SignInOrigin) {
        // 1. Pre-flight: Termux usable, and not already signed in.
        val presence = try {
            termux.readProviderPresence()
        } catch (e: TermuxUnavailable) {
            _state.value = blockedOrFailed(e)
            return
        }
        val providers = presence.providersOrEmpty
        if (isSignedIn(providers, providerId)) {
            _state.value = SignInState.AlreadySignedIn(providerId, if (providerId == CLINE) store.accountDisplayName() else null)
            return
        }
        // 2. Start: record the pending sign-in, run the foreground session, raise Termux.
        val startedAt = clock.millis()
        val myAttempt = transition.withLock {
            attempt += 1
            this.origin = origin
            attempt
        }
        store.setPending(SignInPending(providerId, startedAt))
        val started = try {
            termux.runDetached(ClineCommands.authInteractive(providerId))
        } catch (e: TermuxUnavailable) {
            store.setPending(null)
            _state.value = blockedOrFailed(e)
            return
        }
        handle = started
        termuxForeground.bringToFront()
        _state.value = SignInState.WaitingForTermux(providerId, startedAt)
        watch(providerId, startedAt, myAttempt, started)
    }

    private suspend fun restorePending() {
        val pending = store.pending() ?: return
        if (_state.value !is SignInState.Idle) return
        val myAttempt = transition.withLock {
            attempt += 1
            attempt
        }
        val age = clock.millis() - pending.startedAtMs
        if (age in 0 until timing.timeoutMs) {
            _state.value = SignInState.WaitingForTermux(pending.providerId, pending.startedAtMs)
            watch(pending.providerId, pending.startedAtMs, myAttempt, null)
            checkPresence(myAttempt)
        } else {
            // Too old to keep waiting: a completed sign-in still counts, otherwise forget it.
            val presence = runCatching { termux.readProviderPresence() }.getOrNull()
            if (presence != null && signedInSince(presence.providersOrEmpty, pending.providerId, pending.startedAtMs)) {
                succeed(myAttempt, pending.providerId, viaApiKey = false, verified = true)
            } else {
                store.setPending(null)
            }
        }
    }

    private fun watch(providerId: String, startedAt: Long, myAttempt: Int, started: DetachedHandle?) {
        watchJob?.cancel()
        watchJob = scope.launch {
            // (c) the foreground session's result: exit 0 = success, else the exit error.
            if (started != null) {
                launch {
                    val result = try {
                        started.earlyExit.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TermuxUnavailable) {
                        fail(myAttempt, failureFor(e), SignInState.Failed.Kind.SignIn)
                        return@launch
                    }
                    onSessionResult(myAttempt, providerId, startedAt, result)
                }
            }
            // (a) the secret-free stat poll while the app is started.
            launch { pollProviders(myAttempt, startedAt) }
            // The 15-minute limit.
            launch {
                delay((startedAt + timing.timeoutMs - clock.millis()).coerceAtLeast(0))
                cancelNow(timedOut = true, expectedAttempt = myAttempt)
            }
        }
    }

    private suspend fun onSessionResult(myAttempt: Int, providerId: String, startedAt: Long, result: TermuxResult) {
        when {
            result.internalError -> fail(myAttempt, SignInFailure.Termux(result.errmsg.orEmpty()), SignInState.Failed.Kind.SignIn)
            result.exitCode == 0 -> succeed(myAttempt, providerId, viaApiKey = false, verified = true)
            else -> {
                // A token written just before a non-zero exit still wins.
                val presence = runCatching { termux.readProviderPresence() }.getOrNull()
                if (presence != null && signedInSince(presence.providersOrEmpty, providerId, startedAt)) {
                    succeed(myAttempt, providerId, viaApiKey = false, verified = true)
                } else {
                    fail(myAttempt, SignInFailure.ExitCode(result.exitCode), SignInState.Failed.Kind.SignIn)
                }
            }
        }
    }

    private suspend fun pollProviders(myAttempt: Int, startedAt: Long) {
        var baselineSeconds = startedAt / 1000 - 1
        var consecutiveTimeouts = 0
        while (true) {
            delay(if (consecutiveTimeouts >= 2) timing.backoffIntervalMs else timing.pollIntervalMs)
            if (!appInForeground) continue
            val result = try {
                termux.run(ClineCommands.statProviders())
            } catch (e: TermuxUnavailable.TimedOut) {
                consecutiveTimeouts += 1
                continue
            } catch (e: TermuxUnavailable) {
                continue
            }
            consecutiveTimeouts = 0
            if (!result.ok) continue
            val mtimeSeconds = result.stdout.trim().toLongOrNull() ?: continue
            if (mtimeSeconds > baselineSeconds) {
                // One read per write: the baseline moves to this mtime.
                baselineSeconds = mtimeSeconds
                checkPresence(myAttempt)
            }
        }
    }

    /** One `providers.json` read; success when the provider's token was written since the start. */
    private suspend fun checkPresence(myAttempt: Int) {
        val waiting = _state.value as? SignInState.WaitingForTermux ?: return
        val presence = try {
            termux.readProviderPresence()
        } catch (e: TermuxUnavailable) {
            return
        }
        if (signedInSince(presence.providersOrEmpty, waiting.providerId, waiting.startedAtMs)) {
            succeed(myAttempt, waiting.providerId, viaApiKey = false, verified = true)
        }
    }

    internal suspend fun cancelNow(timedOut: Boolean, expectedAttempt: Int? = null) {
        val waiting = transition.withLock {
            val current = _state.value as? SignInState.WaitingForTermux ?: return
            if (expectedAttempt != null && expectedAttempt != attempt) return
            attempt += 1
            current
        }
        val myAttempt = attempt
        handle?.cancel()
        handle = null
        val job = watchJob
        watchJob = null
        // pkill exit 0 (killed) and 1 (nothing matched) both mean done; retry only when Termux did not run it.
        for ((index, pause) in (listOf(0L) + timing.cancelRetryDelaysMs).withIndex()) {
            if (pause > 0) delay(pause)
            val ran = try {
                termux.run(ClineCommands.cancelAuth()).delivered
            } catch (e: TermuxUnavailable) {
                false
            }
            if (ran || index == timing.cancelRetryDelaysMs.size) break
        }
        // The sign-in may have won the race: a fresh token is success, not a cancellation.
        val presence = try {
            termux.readProviderPresence()
        } catch (e: TermuxUnavailable) {
            null
        }
        if (presence != null && signedInSince(presence.providersOrEmpty, waiting.providerId, waiting.startedAtMs)) {
            succeed(myAttempt, waiting.providerId, viaApiKey = false, verified = true, force = true)
        } else {
            store.setPending(null)
            _state.value = if (timedOut) {
                SignInState.Failed(SignInFailure.TimedOut, SignInState.Failed.Kind.SignIn)
            } else {
                SignInState.Idle
            }
        }
        job?.cancel()
    }

    internal suspend fun submitApiKeyNow(apiKey: String, origin: SignInOrigin) {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        val myAttempt = transition.withLock {
            val current = _state.value
            if (current is SignInState.SavingKey || current is SignInState.WaitingForTermux || startInFlight) return
            attempt += 1
            this.origin = origin
            _state.value = SignInState.SavingKey(CLINE)
            attempt
        }
        val helperState = helper.state.value.let { if (it is HelperState.Starting) helper.awaitSettled() else it }
        if (helperState is HelperState.Ready) {
            try {
                helper.desktopCommand(
                    SAVE_PROVIDER_SETTINGS,
                    buildJsonObject { put("provider", CLINE); put("enabled", true); put("api_key", key) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(myAttempt, SignInFailure.KeySaveFailed(ProviderCatalogRepository.errorMessage(e)), SignInState.Failed.Kind.ApiKey)
                return
            }
            val user = try {
                helper.desktopCommand(CLINE_ACCOUNT, FETCH_ME_ARGS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // "No Cline account auth token found" (the save did not stick) and any other error
                // after a fresh key save both roll back.
                runCatching { helper.desktopCommand(SAVE_PROVIDER_SETTINGS, buildJsonObject { put("provider", CLINE); put("api_key", "") }) }
                catalog.invalidate()
                fail(myAttempt, SignInFailure.KeyNotVerified(ProviderCatalogRepository.errorMessage(e)), SignInState.Failed.Kind.ApiKey)
                return
            }
            cacheAccount(user)
            succeed(myAttempt, CLINE, viaApiKey = true, verified = true, fetchAccount = false)
        } else {
            val model = modelSelection.current().modelFor(CLINE)?.takeIf { it.isNotBlank() } ?: CLINE_DEFAULT_MODEL_ID
            val result = try {
                termux.run(ClineCommands.authProvider(CLINE, key, model))
            } catch (e: TermuxUnavailable.TimedOut) {
                fail(myAttempt, SignInFailure.TermuxDidNotAnswer, SignInState.Failed.Kind.ApiKey)
                return
            } catch (e: TermuxUnavailable) {
                _state.value = blockedOrFailed(e, SignInState.Failed.Kind.ApiKey)
                return
            }
            if (result.ok) {
                succeed(myAttempt, CLINE, viaApiKey = true, verified = false)
            } else {
                val detail = result.lastStderrLine ?: result.errmsg ?: "exit code ${result.exitCode}"
                fail(myAttempt, SignInFailure.KeySaveFailed(detail), SignInState.Failed.Kind.ApiKey)
            }
        }
    }

    /** S7.3 step 5. A late success of a superseded attempt still refreshes the configured state (KS C.6). */
    private suspend fun succeed(
        myAttempt: Int,
        providerId: String,
        viaApiKey: Boolean,
        verified: Boolean,
        fetchAccount: Boolean = true,
        force: Boolean = false,
    ) {
        val current = transition.withLock {
            if (!force && myAttempt != attempt) null else {
                attempt += 1
                origin
            }
        }
        store.setPending(null)
        catalog.invalidate()
        if (current == null) return
        handle?.cancel()
        handle = null
        val selection = modelSelection.current()
        if (providerId == CLINE) {
            when {
                selection.lastProvider.isNullOrBlank() -> modelSelection.rememberProviderSelection(CLINE, CLINE_DEFAULT_MODEL_ID)
                current == SignInOrigin.Onboarding -> modelSelection.rememberProviderSelection(CLINE)
            }
        } else if (selection.lastProvider.isNullOrBlank()) {
            modelSelection.rememberProviderSelection(providerId)
        }
        if (fetchAccount && providerId == CLINE && helper.state.value is HelperState.Ready) {
            runCatching { helper.desktopCommand(CLINE_ACCOUNT, FETCH_ME_ARGS) }.getOrNull()?.let { cacheAccount(it) }
        }
        _state.value = SignInState.Succeeded(providerId, current, viaApiKey, verified)
        _completions.tryEmit(SignInCompletion(providerId, current, appInForeground))
        val job = watchJob
        watchJob = null
        job?.cancel()
    }

    private suspend fun fail(myAttempt: Int, failure: SignInFailure, kind: SignInState.Failed.Kind) {
        val proceed = transition.withLock {
            if (myAttempt != attempt) false else {
                attempt += 1
                true
            }
        }
        if (!proceed) return
        if (kind == SignInState.Failed.Kind.SignIn) store.setPending(null)
        handle = null
        _state.value = SignInState.Failed(failure, kind)
        val job = watchJob
        watchJob = null
        job?.cancel()
    }

    private suspend fun cacheAccount(user: JsonElement) {
        val account = accountSummary(user) ?: return
        store.setAccount(account.first, account.second)
    }

    private fun blockedOrFailed(e: TermuxUnavailable, kind: SignInState.Failed.Kind = SignInState.Failed.Kind.SignIn): SignInState = when (e) {
        is TermuxUnavailable.NotInstalled, is TermuxUnavailable.PermissionMissing, is TermuxUnavailable.ExternalAppsBlocked -> SignInState.Blocked(e)
        is TermuxUnavailable.TimedOut -> SignInState.Failed(if (kind == SignInState.Failed.Kind.ApiKey) SignInFailure.TermuxDidNotAnswer else SignInFailure.Termux(e.message.orEmpty()), kind)
        is TermuxUnavailable.StartFailed -> SignInState.Failed(SignInFailure.Termux(e.message.orEmpty()), kind)
    }

    private fun failureFor(e: TermuxUnavailable): SignInFailure = when (e) {
        is TermuxUnavailable.ExternalAppsBlocked -> SignInFailure.Termux(e.errmsg)
        else -> SignInFailure.Termux(e.message.orEmpty())
    }

    companion object {
        const val CLINE = "cline"
        const val SAVE_PROVIDER_SETTINGS = "save_provider_settings"
        const val CLINE_ACCOUNT = "cline_account"

        /** The `ClineAccountActionRequest` every desktop call sends. */
        val FETCH_ME_ARGS: JsonObject = buildJsonObject {
            put("action", "clineAccount")
            put("operation", "fetchMe")
        }

        /** S5.3: `cline-pass` signs in as `cline`; the others run `cline auth <id>`. */
        fun signInProviderId(providerId: String): String {
            val id = providerId.trim()
            val mapped = if (id == "cline-pass") CLINE else id
            require(mapped in ClineCommands.OAUTH_PROVIDER_IDS) { "no Termux sign-in for provider $providerId" }
            return mapped
        }

        /**
         * S7.3 step 1: for `cline`, an API key or an OAuth access token (a Cline key is
         * account-capable); for `openai-codex` / `oca`, an OAuth access token.
         */
        fun isSignedIn(providers: ConfiguredProviders, providerId: String): Boolean {
            val entry = providers.providers[providerId] ?: return false
            return if (providerId == CLINE) entry.hasApiKey || entry.hasOAuth else entry.hasOAuth
        }

        /** S7.3 step 4: signed in, and `providers.<id>.updatedAt` ≥ [startedAtMs]. */
        fun signedInSince(providers: ConfiguredProviders, providerId: String, startedAtMs: Long): Boolean =
            isSignedIn(providers, providerId) && providers.updatedSince(providerId, startedAtMs)

        /**
         * `fetchMe` → (`displayName` or `email`, active organisation name). A null scope means the
         * personal account; the UI shows "Personal".
         */
        fun accountSummary(user: JsonElement): Pair<String?, String?>? {
            val obj = user as? JsonObject ?: return null
            val name = obj["displayName"].jsString()?.trim()?.ifEmpty { null } ?: obj["email"].jsString()?.trim()?.ifEmpty { null }
            val scope = (obj["organizations"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["active"].jsBoolean() == true }
                ?.get("name").jsString()?.trim()?.ifEmpty { null }
            return name to scope
        }
    }
}
