package dev.cline.termux.core.kernel.dashboard

import kotlinx.coroutines.flow.StateFlow

/**
 * The settings helper lease (SP §0.3): screens `acquire(owner)` in `init` while they need it and
 * `release(owner)` in `onCleared()` when they leave (5 s grace, D9). [state] drives the route and
 * the "Starting settings helper..." / unavailable surfaces. ViewModels depend on this seam so
 * tests construct them with fakes (docs/07 §6.5, §10.1).
 */
interface HelperLease {
    val state: StateFlow<HelperState>

    suspend fun acquire(owner: String)

    suspend fun release(owner: String)

    /** The unavailable card's "Retry". */
    suspend fun retry()

    companion object {
        /** The app's lease over the shared [SettingsHelper]. */
        fun of(helper: SettingsHelper): HelperLease = object : HelperLease {
            override val state: StateFlow<HelperState> get() = helper.state
            override suspend fun acquire(owner: String) = helper.acquire(owner)
            override suspend fun release(owner: String) = helper.release(owner)
            override suspend fun retry() = helper.retry()
        }
    }
}
