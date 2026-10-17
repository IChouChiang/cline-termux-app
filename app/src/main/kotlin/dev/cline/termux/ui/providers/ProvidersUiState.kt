package dev.cline.termux.ui.providers

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.HelperUnavailableReason
import dev.cline.termux.ui.components.KernelBlock

/*
 * UiStates of the one providers ViewModel (settings-providers.md S4.3, S5.5, S6). None of
 * them ever carries a secret: stored API keys and secret config values are read by the field
 * composables through `ProvidersViewModel.storedSecret`, typed ones stay in `remember` state
 * (docs/07 §3.7).
 */

/** S4.3 `ProvidersListUiState`. */
@Immutable
data class ProvidersListUiState(
    val helper: HelperState = HelperState.Idle,
    /** The helper is not available: the list comes from `fallback_providers.json` + `providers.json` (§0.4). */
    val fallback: Boolean = false,
    /** "Starting settings helper..." replaces the loading text. */
    val helperStarting: Boolean = false,
    /** No load has finished yet: "Loading providers...", search disabled, FAB hidden. */
    val loading: Boolean = true,
    /** A pull-to-refresh reload is running. */
    val refreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val collapsed: Set<ProviderSection> = emptySet(),
    val rows: ProviderRows = ProviderRows(emptyList(), emptyList(), emptyList()),
    val configuredCount: Int = 0,
    val totalCount: Int = 0,
    /** The detail pane's provider on expanded windows (`effectiveSelectedProviderId`). */
    val selectedId: String? = null,
    /** §0.5: replaces the list. */
    val blocked: KernelBlock? = null,
    /** §0.3 unavailable card above the (fallback) list. */
    val helperCard: HelperUnavailableModel? = null,
) {
    /** "Sign in or add an API key to connect." only when nothing is configured and there is no query. */
    val showPopularDescription: Boolean get() = configuredCount == 0 && query.isBlank()

    val showFab: Boolean get() = !loading && blocked == null && !helperStarting

    /** "No providers match …" (the query matched nothing). */
    val emptySearch: Boolean get() = !loading && query.isNotBlank() && rows.isEmpty
}

/** A config field as the detail renders it; [storedText] is empty for secret fields (they are read from the VM's memory). */
@Immutable
data class FieldUi(
    val path: String,
    val label: String,
    /** `text` | `password` | `url` | `number` | `select` | `boolean`. */
    val type: String,
    val description: String?,
    val placeholder: String?,
    val secret: Boolean,
    val options: List<FieldOption>,
    val storedText: String,
    val checked: Boolean,
    /** "Get a <name> API key" target, only on the `apiKey` field. */
    val keyUrl: String?,
    val saving: Boolean,
)

@Immutable
data class FieldOption(val label: String, val valueText: String)

/** The provider's sign-in as S5 renders it inline (S7 waiting / failed states). */
sealed interface DetailSignIn {
    data object Idle : DetailSignIn
    data object Waiting : DetailSignIn
    data class Failed(val failure: SignInFailure) : DetailSignIn
    data class Blocked(val block: KernelBlock) : DetailSignIn
}

/** Why a fallback `cline auth` save failed. */
sealed interface FallbackError {
    /** The ANSI-stripped last stderr line (or Termux's `errmsg`), verbatim. */
    data class Message(val text: String) : FallbackError
    data class ExitCode(val code: Int) : FallbackError
    data object TimedOut : FallbackError
}

/** S5.5 `FallbackSaveState`. */
sealed interface FallbackSaveState {
    data object Idle : FallbackSaveState
    data object Saving : FallbackSaveState
    data class Error(val error: FallbackError) : FallbackSaveState
    data object Done : FallbackSaveState
}

/** The §0.4 fallback form: API Key, Model ID, Base URL where `cline auth --baseurl` is accepted. */
@Immutable
data class FallbackFormUi(
    val showBaseUrl: Boolean,
    /** Prefill: the saved `model`, else the provider's first fallback model. */
    val initialModelId: String,
    /** A key is already saved: the empty field reads "Saved in Termux". */
    val apiKeySaved: Boolean,
    /** `openai-compatible`: "Keyless servers accept any value here…". */
    val keylessHint: Boolean,
)

/** S5.4 models card. */
@Immutable
data class ModelsCardUi(
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val rows: List<ModelRowUi> = emptyList(),
    val totalCount: Int = 0,
    val refreshAvailable: Boolean = false,
    val searchVisible: Boolean = false,
    val copiedId: String? = null,
)

/** S5.5 `ProviderDetailUiState`. */
@Immutable
data class ProviderDetailUiState(
    val providerId: String,
    /** The provider's display name (the id until the catalog knows it). */
    val name: String,
    /** The catalog has this provider. */
    val known: Boolean,
    val loading: Boolean,
    val connected: Boolean,
    val authKind: AuthKind,
    val oauthSignedIn: Boolean,
    val apiKeyPresent: Boolean,
    val connection: ConnectionVariant,
    val fields: List<FieldUi>,
    /** The OAuth variants' `apiKey` row. */
    val apiKeyField: FieldUi?,
    val manualKeyExpanded: Boolean,
    val canSignIn: Boolean,
    val signIn: DetailSignIn,
    val models: ModelsCardUi,
    val fallback: Boolean,
    val fallbackForm: FallbackFormUi?,
    val fallbackSave: FallbackSaveState,
    val autoSaveNote: Boolean,
    val signOutAction: Boolean,
    val canDisconnect: Boolean,
    val confirmDisconnect: Boolean,
    val disconnecting: Boolean,
    val showConnect: Boolean,
    val connecting: Boolean,
    val helperCard: HelperUnavailableModel?,
    val blocked: KernelBlock?,
    /** Field drafts remount when this changes (desktop `detailResetToken`: a failed save, a disconnect). */
    val resetKey: Int,
    /** Bumped when a load hands the VM fresh stored values; untouched drafts follow them. */
    val valuesRevision: Int,
    /**
     * The seed before the first build lands from `Dispatchers.Default` (docs/07 §8): the page
     * shows its app bar and progress only, never a guessed connection section.
     */
    val pending: Boolean = false,
) {
    companion object {
        /** The cheap `stateIn` seed for [providerId]; no catalog work happens in composition. */
        fun pending(providerId: String): ProviderDetailUiState = ProviderDetailUiState(
            providerId = providerId,
            name = "",
            known = false,
            loading = true,
            connected = false,
            authKind = AuthKind.ApiKey,
            oauthSignedIn = false,
            apiKeyPresent = false,
            connection = ConnectionVariant.ApiKey,
            fields = emptyList(),
            apiKeyField = null,
            manualKeyExpanded = false,
            canSignIn = false,
            signIn = DetailSignIn.Idle,
            models = ModelsCardUi(loading = true),
            fallback = false,
            fallbackForm = null,
            fallbackSave = FallbackSaveState.Idle,
            autoSaveNote = false,
            signOutAction = false,
            canDisconnect = false,
            confirmDisconnect = false,
            disconnecting = false,
            showConnect = false,
            connecting = false,
            helperCard = null,
            blocked = null,
            resetKey = 0,
            valuesRevision = 0,
            pending = true,
        )
    }
}

/** S6 `AddProviderUiState`. */
@Immutable
data class AddProviderUiState(
    val form: AddProviderForm = AddProviderForm(),
    val duplicate: Boolean = false,
    val canSave: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** The helper went away mid-form (S6.3 "Helper lost mid-form"). */
    val helperCard: HelperUnavailableModel? = null,
)

/** One-shot effects the screens turn into snackbars or navigation. */
sealed interface ProvidersEvent {
    /** "Failed to save provider settings for <id>: <message>" (Long). */
    data class SaveFailed(val providerId: String, val message: String) : ProvidersEvent

    /** Fallback `cline auth` success: "Provider configured: <id> (<model>)". */
    data class ProviderConfigured(val providerId: String, val modelId: String) : ProvidersEvent

    /** "This provider is no longer available." and back to S4. */
    data class ProviderVanished(val providerId: String) : ProvidersEvent

    /** A browser sign-in from S5 finished. */
    data class SignedIn(val providerId: String) : ProvidersEvent

    /** S6 saved: close the editor and open S5 on the new id. */
    data class ProviderAdded(val providerId: String) : ProvidersEvent
}

// ---- helper state → cards ----------------------------------------------------------------

/** §0.3: the reasons that keep the list (fallback) under an unavailable card. */
fun HelperState.helperCard(): HelperUnavailableModel? {
    val unavailable = this as? HelperState.Unavailable ?: return null
    val reason = when (unavailable.reason) {
        HelperFailure.StartTimeout -> HelperUnavailableReason.StartTimeout
        HelperFailure.SocketFailed -> HelperUnavailableReason.SocketFailed
        HelperFailure.BuildUnsupported -> HelperUnavailableReason.BuildUnsupported
        HelperFailure.PortInUse -> HelperUnavailableReason.PortInUse
        HelperFailure.KernelStopped -> HelperUnavailableReason.KernelStopped
        HelperFailure.TermuxMissing, HelperFailure.PermissionMissing, HelperFailure.ExternalAppsBlocked -> return null
    }
    return HelperUnavailableModel(reason, unavailable.detail)
}

/** §0.5: the reasons that replace the content with a `KernelBlockedCard`. */
fun HelperState.kernelBlock(): KernelBlock? = when ((this as? HelperState.Unavailable)?.reason) {
    HelperFailure.TermuxMissing -> KernelBlock.TermuxMissing
    HelperFailure.PermissionMissing -> KernelBlock.PermissionMissing
    HelperFailure.ExternalAppsBlocked -> KernelBlock.ExternalAppsBlocked
    else -> null
}
