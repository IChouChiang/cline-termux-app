package dev.cline.termux.ui.providers

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.HelperUnavailableReason
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.navigation.EditorDialogSurface
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.theme.ThemeMode

/* Previews of S4–S6 in light and dark (sample data only; nothing here ships to users). */

private val previewBlockHandler = KernelBlockHandler(onAction = { _, _ -> }, permissionPermanentlyDenied = false)

private val previewListActions = ProvidersListActions(
    onQuery = {}, onToggleSection = {}, onOpen = {}, onAdd = {}, onRefresh = {}, onRetryLoad = {},
    onRetryHelper = {}, onStartKernel = {}, blockHandler = previewBlockHandler,
)

private val previewRows = ProviderRows(
    configured = listOf(ProviderRowUi("anthropic", "Anthropic", connected = true, authKind = AuthKind.ApiKey, saving = true)),
    popular = listOf(
        ProviderRowUi("cline", "Cline", connected = false, authKind = AuthKind.OAuth),
        ProviderRowUi("openrouter", "OpenRouter", connected = false, authKind = AuthKind.ApiKey),
    ),
    others = listOf(
        ProviderRowUi("ollama", "Ollama", connected = false, authKind = AuthKind.Local),
        ProviderRowUi("gemini", "Google Gemini", connected = false, authKind = AuthKind.ApiKey),
    ),
)

private val loadedList = ProvidersListUiState(
    helper = HelperState.Ready,
    helperStarting = false,
    loading = false,
    rows = previewRows,
    configuredCount = 1,
    totalCount = 5,
    collapsed = setOf(ProviderSection.All),
)

@Composable
private fun ListPreview(state: ProvidersListUiState) {
    ComponentPreview(padding = false) {
        ProvidersListScreen(state = state, actions = previewListActions, navigationIcon = {}, gutter = Spacing.l)
    }
}

@LightDarkPreviews
@Composable
private fun ProvidersListLoadedPreview() = ListPreview(loadedList)

@LightDarkPreviews
@Composable
private fun ProvidersListLoadingPreview() = ListPreview(ProvidersListUiState(loading = true, helperStarting = false))

@LightDarkPreviews
@Composable
private fun ProvidersListHelperStartingPreview() = ListPreview(ProvidersListUiState(helperStarting = true))

@LightDarkPreviews
@Composable
private fun ProvidersListFallbackPreview() = ListPreview(
    loadedList.copy(
        helper = HelperState.Unavailable(HelperFailure.StartTimeout, "settings helper did not start"),
        fallback = true,
        helperCard = HelperUnavailableModel(HelperUnavailableReason.StartTimeout, "settings helper did not start"),
    ),
)

@LightDarkPreviews
@Composable
private fun ProvidersListBlockedPreview() = ListPreview(ProvidersListUiState(loading = false, helperStarting = false, blocked = KernelBlock.PermissionMissing))

@LightDarkPreviews
@Composable
private fun ProvidersListNoMatchPreview() = ListPreview(
    loadedList.copy(query = "zzz", rows = ProviderRows(emptyList(), emptyList(), emptyList())),
)

@LightDarkPreviews
@Composable
private fun ProvidersListErrorPreview() = ListPreview(
    ProvidersListUiState(loading = false, helperStarting = false, error = "connection refused"),
)

/** System scale 2.0 × in-app size 20: the status stacks under the name (DS §8.1.8). */
@Composable
private fun LargeTextRows(dark: Boolean) {
    ClineTheme(AppearancePrefs(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false, fontSize = 20)) {
        Surface {
            Column {
                ProviderSectionHeader(ProviderSection.Popular, count = 2, expanded = true, onToggle = {})
                ProviderRow(ProviderRowUi("cline", "Cline Usage-Billing", connected = false, authKind = AuthKind.OAuth), selected = false, onClick = {})
                ProviderRow(ProviderRowUi("openai-compatible", "OpenAI Compatible", connected = true, authKind = AuthKind.ApiKey, saving = true), selected = true, onClick = {})
                ProviderSectionHeader(ProviderSection.All, count = 1, expanded = false, onToggle = {})
            }
        }
    }
}

@Preview(name = "Rows, font scale 2 × size 20, light", fontScale = 2f, widthDp = 412)
@Composable
private fun ProviderRowsLargeTextLightPreview() = LargeTextRows(dark = false)

@Preview(
    name = "Rows, font scale 2 × size 20, dark", fontScale = 2f, widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun ProviderRowsLargeTextDarkPreview() = LargeTextRows(dark = true)

/** RTL: the collapsed section chevron points at the end (left) edge (DS §8.2.6). */
@LightDarkPreviews
@Composable
private fun ProviderSectionHeadersRtlPreview() = ComponentPreview(padding = false) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column {
            ProviderSectionHeader(ProviderSection.Configured, count = 1, expanded = true, onToggle = {})
            ProviderRow(ProviderRowUi("gemini", "Google Gemini", connected = false, authKind = AuthKind.ApiKey), selected = false, onClick = {})
            ProviderSectionHeader(ProviderSection.All, count = 12, expanded = false, onToggle = {})
        }
    }
}

// ---- S5------------------------------------------------------------------------------------

private fun field(path: String, label: String, type: String = "text", stored: String = "", secret: Boolean = false, keyUrl: String? = null) =
    FieldUi(path, label, type, description = null, placeholder = null, secret = secret, options = emptyList(), storedText = stored, checked = false, keyUrl = keyUrl, saving = false)

private val previewModels = ModelsCardUi(
    rows = listOf(
        ModelRowUi(
            id = "claude-sonnet-4-5", name = "Claude Sonnet 4.5", description = "Balanced model for coding.",
            badges = listOf(ModelBadge.Recommended), fileSupport = true, imageSupport = true, audioSupport = false,
            reasoningSupport = true, audioPill = null, streamingUnsupported = false, favorite = true,
        ),
        ModelRowUi(
            id = "gpt-realtime", name = "GPT Realtime", description = null,
            badges = listOf(ModelBadge.Free, ModelBadge.Tag("NEW")), fileSupport = false, imageSupport = false, audioSupport = true,
            reasoningSupport = false, audioPill = AudioPill.Realtime, streamingUnsupported = true, favorite = false,
        ),
    ),
    totalCount = 2,
    refreshAvailable = true,
    searchVisible = true,
    copiedId = "gpt-realtime",
)

private val apiKeyDetail = ProviderDetailUiState(
    providerId = "anthropic", name = "Anthropic", known = true, loading = false, connected = true,
    authKind = AuthKind.ApiKey, oauthSignedIn = false, apiKeyPresent = true, connection = ConnectionVariant.ApiKey,
    fields = listOf(
        field("apiKey", "API Key", type = "password", secret = true, keyUrl = "https://console.anthropic.com/settings/keys"),
        field("baseUrl", "Base URL", type = "url", stored = "https://api.anthropic.com"),
        FieldUi("thinking", "Extended thinking", "boolean", "Let the model think before answering.", null, false, emptyList(), "true", true, null, false),
    ),
    apiKeyField = null, manualKeyExpanded = false, canSignIn = false, signIn = DetailSignIn.Idle, models = previewModels,
    fallback = false, fallbackForm = null, fallbackSave = FallbackSaveState.Idle, autoSaveNote = true, signOutAction = false,
    canDisconnect = true, confirmDisconnect = false, disconnecting = false, showConnect = false, connecting = false,
    helperCard = null, blocked = null, resetKey = 0, valuesRevision = 1,
)

private val previewDetailActions = ProviderDetailActions(
    fields = FieldCallbacks({ _, _ -> }, {}, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, {}),
    models = ModelsCallbacks({}, {}, {}, {}),
    onSignIn = {}, onCancelSignIn = {}, onOpenTermux = {}, onDismissSignInError = {}, onToggleManualKey = {},
    onConnect = {}, onRequestDisconnect = {}, onDismissDisconnect = {}, onConfirmDisconnect = {},
    onSaveFallback = { _, _, _ -> }, onCopySecretValue = { _, _ -> }, onFallbackReveal = {}, onRetryHelper = {},
    onStartKernel = {}, blockHandler = previewBlockHandler,
)

@Composable
private fun DetailPreview(state: ProviderDetailUiState, pane: Boolean = false) {
    ComponentPreview(padding = false) {
        ProviderDetailContent(
            state = state,
            fieldText = { if (it.secret) "sk-ant-preview" else it.storedText },
            shown = { false },
            actions = previewDetailActions,
            pane = pane,
            navigationIcon = {},
            gutter = Spacing.l,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ProviderDetailApiKeyPreview() = DetailPreview(apiKeyDetail)

@LightDarkPreviews
@Composable
private fun ProviderDetailPanePreview() = DetailPreview(apiKeyDetail, pane = true)

@LightDarkPreviews
@Composable
private fun ProviderDetailPendingPreview() = DetailPreview(ProviderDetailUiState.pending("anthropic"))

@LightDarkPreviews
@Composable
private fun ProviderDetailPendingPanePreview() = DetailPreview(ProviderDetailUiState.pending("anthropic"), pane = true)

@LightDarkPreviews
@Composable
private fun ProviderDetailOAuthWaitingPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "cline", name = "Cline", connected = false, authKind = AuthKind.OAuth, apiKeyPresent = false,
        connection = ConnectionVariant.OAuthSignIn, fields = emptyList(), canSignIn = true, signIn = DetailSignIn.Waiting,
        autoSaveNote = false, canDisconnect = false, models = ModelsCardUi(loading = true, refreshAvailable = true),
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailOAuthFailedPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "cline", name = "Cline", connected = false, authKind = AuthKind.OAuth, apiKeyPresent = false,
        connection = ConnectionVariant.OAuthSignIn, fields = listOf(field("apiKey", "API Key", type = "password", secret = true)),
        apiKeyField = field("apiKey", "API Key", type = "password", secret = true), manualKeyExpanded = true, canSignIn = true,
        signIn = DetailSignIn.Failed(SignInFailure.ExitCode(1)), autoSaveNote = false, canDisconnect = false,
        models = ModelsCardUi(),
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailOAuthWithApiKeyPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "cline", name = "Cline", connected = true, authKind = AuthKind.OAuth, oauthSignedIn = false, apiKeyPresent = true,
        connection = ConnectionVariant.OAuthWithApiKey,
        fields = listOf(field("apiKey", "API Key", type = "password", secret = true, keyUrl = "https://app.cline.bot")),
        apiKeyField = field("apiKey", "API Key", type = "password", secret = true, keyUrl = "https://app.cline.bot"),
        canSignIn = true, autoSaveNote = false, signOutAction = false, canDisconnect = true,
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailFallbackOAuthPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "cline", name = "Cline", connected = false, authKind = AuthKind.OAuth, apiKeyPresent = false,
        connection = ConnectionVariant.OAuthSignIn, fields = emptyList(), canSignIn = true, manualKeyExpanded = true,
        fallback = true,
        fallbackForm = FallbackFormUi(showBaseUrl = false, initialModelId = "anthropic/claude-sonnet-5", apiKeySaved = false, keylessHint = false),
        autoSaveNote = false, canDisconnect = false,
        helperCard = HelperUnavailableModel(HelperUnavailableReason.BuildUnsupported, ""),
        models = ModelsCardUi(rows = previewModels.rows.take(1), totalCount = 1),
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailSignedInPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "openai-codex", name = "OpenAI Codex", authKind = AuthKind.OAuth, oauthSignedIn = true,
        connection = ConnectionVariant.OAuthSignedIn, fields = emptyList(), signOutAction = true, confirmDisconnect = false,
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailLocalConnectPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "ollama", name = "Ollama", connected = false, authKind = AuthKind.Local, apiKeyPresent = false,
        connection = ConnectionVariant.Local, fields = listOf(field("baseUrl", "Base URL", type = "url", stored = "http://localhost:11434")),
        canDisconnect = false, autoSaveNote = false, showConnect = true, models = ModelsCardUi(error = "connection refused", refreshAvailable = true),
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailLocalConnectingPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "claude-code", name = "Claude Code", connected = false, authKind = AuthKind.Local, apiKeyPresent = false,
        connection = ConnectionVariant.Local, fields = emptyList(), canDisconnect = false, autoSaveNote = false,
        showConnect = true, connecting = true,
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailApiKeyNotConnectedPreview() = DetailPreview(
    apiKeyDetail.copy(connected = false, apiKeyPresent = false, autoSaveNote = false, canDisconnect = false, showConnect = true),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailFallbackPreview() = DetailPreview(
    apiKeyDetail.copy(
        providerId = "openai-compatible", name = "OpenAI Compatible", connected = false, fields = emptyList(), fallback = true,
        fallbackForm = FallbackFormUi(showBaseUrl = true, initialModelId = "gpt-4o", apiKeySaved = true, keylessHint = true),
        fallbackSave = FallbackSaveState.Error(FallbackError.ExitCode(2)), canDisconnect = false, autoSaveNote = false,
        helperCard = HelperUnavailableModel(HelperUnavailableReason.KernelStopped, ""),
        models = ModelsCardUi(),
    ),
)

@LightDarkPreviews
@Composable
private fun ProviderDetailDisconnectPreview() = DetailPreview(apiKeyDetail.copy(confirmDisconnect = true))

// ---- S6 ------------------------------------------------------------------------------------

private val previewAddCallbacks = AddProviderCallbacks(onUpdate = {}, onApiKey = {}, onToggleKeyShown = {}, onCopyKey = {}, onRetryHelper = {}, onStartKernel = {})

@Composable
private fun AddPreview(state: AddProviderUiState, twoColumn: Boolean = false) {
    ComponentPreview {
        Column { AddProviderFormContent(state, apiKey = "", keyShown = false, callbacks = previewAddCallbacks, twoColumn = twoColumn, asDialog = false) }
    }
}

private val previewFilledForm = AddProviderForm(
    providerId = "my-gateway", name = "My Gateway", baseUrl = "https://gateway.example.com/v1",
    models = listOf("llama-3.3-70b", "qwen-2.5-coder"), defaultModel = "llama-3.3-70b",
)

@LightDarkPreviews
@Composable
private fun AddProviderEmptyPreview() = AddPreview(AddProviderUiState())

@LightDarkPreviews
@Composable
private fun AddProviderSavingPreview() = AddPreview(AddProviderUiState(form = previewFilledForm, canSave = true, saving = true))

@LightDarkPreviews
@Composable
private fun AddProviderErrorPreview() = AddPreview(AddProviderUiState(form = previewFilledForm, canSave = true, error = "fetch failed: connect ECONNREFUSED"))

@Composable
private fun AddDialogPreview(state: AddProviderUiState) {
    ComponentPreview(padding = false) {
        EditorDialogSurface {
            AddProviderDialogBody(state, apiKey = "", keyShown = false, callbacks = previewAddCallbacks, onCancel = {}, onSave = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun AddProviderDialogPreview() = AddDialogPreview(AddProviderUiState(form = previewFilledForm, canSave = true))

@LightDarkPreviews
@Composable
private fun AddProviderDialogErrorPreview() = AddDialogPreview(
    AddProviderUiState(form = previewFilledForm, canSave = true, error = "fetch failed: connect ECONNREFUSED"),
)

@LightDarkPreviews
@Composable
private fun AddProviderDialogSavingPreview() = AddDialogPreview(AddProviderUiState(form = previewFilledForm, canSave = true, saving = true))

@LightDarkPreviews
@Composable
private fun AddProviderFilledPreview() = AddPreview(
    AddProviderUiState(
        form = AddProviderForm(
            providerId = "anthropic", name = "My Gateway", baseUrl = "https://gateway.example.com/v1",
            models = listOf("llama-3.3-70b", "qwen-2.5-coder"), defaultModel = "llama-3.3-70b", advancedOpen = true,
            timeoutMs = "30000", headers = listOf(HeaderRow("X-Team", "core")),
        ),
        duplicate = true,
        error = "provider already exists",
    ),
    twoColumn = true,
)
