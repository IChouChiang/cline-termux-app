package dev.cline.termux.ui.model

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.SettingsHelper
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ModelsLoad
import dev.cline.termux.data.providers.ProviderCatalogRepository
import dev.cline.termux.data.session.Liveness
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * The seams ModelPickerViewModel reads through (docs/07 §0 rule 8: screens reach the kernel only
 * through repositories and stores). The app adapts ProviderCatalogRepository + SettingsHelper and
 * SessionStore + HubSessionApi; unit tests pass in-memory fakes.
 */

/** The catalog and the on-demand helper as the picker needs them (SP S8.2, §0.7). */
interface PickerCatalogPort {
    val state: StateFlow<CatalogState>

    /** The settings helper's state; the picker refreshes on open only when it is `Ready` and never starts it by itself. */
    val helperState: StateFlow<HelperState>

    /** `list_provider_models {provider}` through the repository (replaces that provider's cached list). */
    suspend fun refreshModels(providerId: String): ModelsLoad

    /** "Refresh models": lease the helper (SP §0.3 `acquire`) and wait for the start sequence to settle. */
    suspend fun acquireHelper(owner: String): Boolean

    suspend fun releaseHelper(owner: String)

    companion object {
        fun of(repository: ProviderCatalogRepository, helper: SettingsHelper): PickerCatalogPort = object : PickerCatalogPort {
            override val state: StateFlow<CatalogState> get() = repository.state
            override val helperState: StateFlow<HelperState> get() = helper.state

            override suspend fun refreshModels(providerId: String): ModelsLoad = repository.refreshModels(providerId)

            override suspend fun acquireHelper(owner: String): Boolean {
                helper.acquire(owner)
                return helper.awaitSettled() is HelperState.Ready
            }

            override suspend fun releaseHelper(owner: String) = helper.release(owner)
        }
    }
}

/** What the picker reads of the hosting session (SP S8.3 host modes). */
data class PickerSessionSnapshot(
    val providerId: String?,
    val modelId: String?,
    /** A run is in flight (`status` running/pending/starting/stopping, or the app's create). */
    val busy: Boolean,
    /** SP S8.3 liveness: attached on this connection and not hinted persisted. */
    val live: Boolean,
) {
    companion object {
        fun of(state: SessionState) = PickerSessionSnapshot(
            providerId = state.facts.providerId,
            modelId = state.facts.modelId,
            busy = state.facts.status.isRunning || state.creating,
            live = state.liveness == Liveness.Live,
        )
    }
}

/** `session.update_connection` `updates`; null fields are omitted (never `apiKey`, FM). */
data class ConnectionUpdate(
    val providerId: String? = null,
    val modelId: String? = null,
    val thinking: Boolean? = null,
    val reasoningEffort: String? = null,
)

/** The session store slice the picker needs; liveness is read before sending, never inferred from an error. */
interface PickerSessionPort {
    fun observe(sessionId: String): Flow<PickerSessionSnapshot>

    /** Read at pick time (SP S8.3): true only when the session is attached on the current socket. */
    fun isLive(sessionId: String): Boolean

    /** @throws dev.cline.termux.core.kernel.hub.HubCommandException on an `ok:false` reply or a lost socket. */
    suspend fun updateConnection(sessionId: String, update: ConnectionUpdate)

    /** The reply said the session is not live after all: resume on the next send. */
    suspend fun markNotLive(sessionId: String)

    /** `hub.drain_changed {draining:false}` (KC); false when the wait timed out. */
    suspend fun awaitNotDraining(): Boolean

    companion object {
        /**
         * The store, API and client are resolved on first use, so a Home-hosted picker never
         * touches the Hub graph (docs/07 §8: those lazies stay off Main until the splash releases).
         */
        fun of(store: () -> SessionStore, api: () -> HubSessionApi, hub: () -> HubClient): PickerSessionPort = object : PickerSessionPort {
            override fun observe(sessionId: String): Flow<PickerSessionSnapshot> =
                store().state(sessionId).map(PickerSessionSnapshot::of).distinctUntilChanged()

            override fun isLive(sessionId: String): Boolean = store().state(sessionId).value.liveness == Liveness.Live

            override suspend fun updateConnection(sessionId: String, update: ConnectionUpdate) {
                api().updateConnection(sessionId, update.providerId, update.modelId, update.thinking, update.reasoningEffort)
            }

            override suspend fun markNotLive(sessionId: String) = store().onSessionGone(sessionId)

            override suspend fun awaitNotDraining(): Boolean = hub().awaitNotDraining()
        }
    }
}
