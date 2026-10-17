package dev.cline.termux.core.kernel

import dev.cline.termux.data.prefs.AppPreferences

/**
 * The `kernel.*` keys of the `app` DataStore (docs/07 §7.2) as the coordinator reads them.
 * [AppKernelPreferences] adapts `AppPreferences`; tests use [InMemoryKernelPreferences].
 */
interface KernelPreferences {
    /** Settings › Kernel › "Keep Termux awake" (take `termux-wake-lock` while connected); default on. */
    suspend fun keepAwake(): Boolean
    suspend fun setKeepAwake(value: Boolean)

    /** The user pressed Stop in the app: no auto-start until they start it again (KS auto-start policy). */
    suspend fun userStopped(): Boolean
    suspend fun setUserStopped(value: Boolean)

    /** We took the wake lock and have not released it yet (so a later `stopKernel` releases it). */
    suspend fun wakeLockRequested(): Boolean
    suspend fun setWakeLockRequested(value: Boolean)

    /** The port release the running Hub was started from (E.5 update row, E.7 outcome table). */
    suspend fun hubLifetimeRelease(): String?
    suspend fun setHubLifetimeRelease(value: String?)
}

class AppKernelPreferences(private val prefs: AppPreferences) : KernelPreferences {
    override suspend fun keepAwake(): Boolean = prefs.keepAwake()
    override suspend fun setKeepAwake(value: Boolean) { prefs.setKeepAwake(value) }
    override suspend fun userStopped(): Boolean = prefs.userStopped()
    override suspend fun setUserStopped(value: Boolean) { prefs.setUserStopped(value) }
    override suspend fun wakeLockRequested(): Boolean = prefs.wakeLockRequested()
    override suspend fun setWakeLockRequested(value: Boolean) { prefs.setWakeLockRequested(value) }
    override suspend fun hubLifetimeRelease(): String? = prefs.hubLifetimeRelease()
    override suspend fun setHubLifetimeRelease(value: String?) { prefs.setHubLifetimeRelease(value) }
}

/** In-memory [KernelPreferences], for tests and previews. */
class InMemoryKernelPreferences(
    @Volatile var keepAwake: Boolean = true,
    @Volatile var userStopped: Boolean = false,
    @Volatile var wakeLockRequested: Boolean = false,
    @Volatile var hubLifetimeRelease: String? = null,
) : KernelPreferences {
    override suspend fun keepAwake(): Boolean = keepAwake
    override suspend fun setKeepAwake(value: Boolean) { keepAwake = value }
    override suspend fun userStopped(): Boolean = userStopped
    override suspend fun setUserStopped(value: Boolean) { userStopped = value }
    override suspend fun wakeLockRequested(): Boolean = wakeLockRequested
    override suspend fun setWakeLockRequested(value: Boolean) { wakeLockRequested = value }
    override suspend fun hubLifetimeRelease(): String? = hubLifetimeRelease
    override suspend fun setHubLifetimeRelease(value: String?) { hubLifetimeRelease = value }
}
