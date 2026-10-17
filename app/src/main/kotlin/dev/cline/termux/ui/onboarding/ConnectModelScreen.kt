package dev.cline.termux.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.termux.ConfiguredProvider
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.data.providers.CLINE_DASHBOARD_URL
import dev.cline.termux.ui.components.ChevronTurn
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.account.AccountUrls
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.navigation.SecureWindowEffect
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything Connect does besides typing (the screen wires these). */
class ConnectActions(
    val onBack: () -> Unit = {},
    val onSelectMethod: (SetupMethod) -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onCancelSignIn: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onToggleClineKey: () -> Unit = {},
    val onSubmitClineKey: (String) -> Unit = {},
    val onSelectProvider: (String) -> Unit = {},
    val onModelChange: (String) -> Unit = {},
    val onBaseUrlChange: (String) -> Unit = {},
    val onFieldBlur: (ConnectField) -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onRetryCatalog: () -> Unit = {},
    val onContinue: (SetupMethod) -> Unit = {},
    val onOpenKernelSetup: (CheckId?) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onSkip: () -> Unit = {},
)

/**
 * C wired: key texts in `remember` only (never saved state), cleared on success, on leaving and
 * after 10 s in the background; the Recents thumbnail is hidden while the screen is up; back
 * cancels a waiting sign-in before it navigates (C.3).
 */
@Composable
fun ConnectModelScreen(
    viewModel: ConnectModelViewModel,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onOpenKernelSetup: (CheckId?) -> Unit,
    onDone: (OnboardingConnection) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val open = rememberOpenExternal()
    var apiKey by remember { mutableStateOf("") }
    var clineKey by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConnectEvent.ClearKeys -> {
                    apiKey = ""
                    clineKey = ""
                }
                is ConnectEvent.ToDone -> onDone(event.connection)
            }
        }
    }
    // KS §0.6 rule 3: the typed keys are dropped after 10 s in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        var clearJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> clearJob = scope.launch {
                    delay(KEY_CLEAR_AFTER_MS)
                    apiKey = ""
                    clineKey = ""
                }
                Lifecycle.Event.ON_START -> clearJob?.cancel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            clearJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val waiting = ui.cline is ClineSignInUi.Waiting
    // Back during a waiting sign-in cancels it first; the predictive preview is off only then.
    BackHandler(enabled = waiting) { viewModel.cancelSignIn() }
    ConnectContent(
        ui = ui,
        apiKey = apiKey,
        onApiKeyChange = { apiKey = it },
        clineKey = clineKey,
        onClineKeyChange = { clineKey = it },
        actions = ConnectActions(
            onBack = { if (!viewModel.onBack()) onBack() },
            onSelectMethod = viewModel::selectMethod,
            onSignIn = viewModel::signIn,
            onCancelSignIn = viewModel::cancelSignIn,
            onOpenTermux = { viewModel.openTermux() },
            onToggleClineKey = viewModel::toggleClineKeyForm,
            onSubmitClineKey = viewModel::submitClineKey,
            onSelectProvider = viewModel::selectProvider,
            onModelChange = viewModel::setModelId,
            onBaseUrlChange = viewModel::setBaseUrl,
            onFieldBlur = viewModel::markTouched,
            onConnect = viewModel::connect,
            onRetryCatalog = viewModel::retryCatalog,
            onContinue = viewModel::continueConnected,
            onOpenKernelSetup = onOpenKernelSetup,
            onOpenUrl = open,
            onSkip = onSkip,
        ),
    )
}

@Composable
fun ConnectContent(
    ui: ConnectUiState,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    clineKey: String,
    onClineKeyChange: (String) -> Unit,
    actions: ConnectActions,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var clineKeyVisible by remember { mutableStateOf(false) }
    SecureWindowEffect(revealed = keyVisible || clineKeyVisible, keyFieldShown = true)
    OnboardingScaffold(surface = OnboardingSurface.Panel, onBack = actions.onBack) {
        PanelTitle(stringResource(R.string.connect_title))
        PanelDescription(stringResource(R.string.connect_body))
        if (ui.loading) {
            Spacer(Modifier.height(Spacing.s))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        Spacer(Modifier.height(Spacing.l))
        if (!ui.kernelUsable) {
            KernelUnavailableCard(onOpen = { actions.onOpenKernelSetup(ui.kernelFocus) })
            Spacer(Modifier.height(Spacing.m))
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SetupOptionCard(
                title = stringResource(R.string.connect_cline_title),
                description = null,
                icon = ClineIcons.AccountCircle,
                selected = ui.method == SetupMethod.Cline,
                onSelect = { actions.onSelectMethod(SetupMethod.Cline) },
                badge = stringResource(R.string.connect_recommended),
            ) {
                ClineCardBody(ui, clineKey, onClineKeyChange, clineKeyVisible, { clineKeyVisible = it }, actions)
            }
            SetupOptionCard(
                title = stringResource(R.string.connect_api_title),
                description = stringResource(R.string.connect_api_body),
                icon = ClineIcons.Key,
                selected = ui.method == SetupMethod.ApiKey,
                onSelect = { actions.onSelectMethod(SetupMethod.ApiKey) },
            ) {
                ApiKeyCardBody(ui, apiKey, onApiKeyChange, keyVisible, { keyVisible = it }, actions)
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = actions.onSkip) { Text(stringResource(R.string.connect_skip)) }
        }
    }
}

@Composable
private fun KernelUnavailableCard(onOpen: () -> Unit) {
    Surface(color = ClineTheme.colors.warning.container, contentColor = ClineTheme.colors.warning.onContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ClineIcons.WarningFilled, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.connect_kernel_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.connect_open_kernel_setup)) }
        }
    }
}

@Composable
private fun ClineCardBody(
    ui: ConnectUiState,
    clineKey: String,
    onClineKeyChange: (String) -> Unit,
    keyVisible: Boolean,
    onKeyVisible: (Boolean) -> Unit,
    actions: ConnectActions,
) {
    val enabled = ui.kernelUsable && !ui.loading
    when (val cline = ui.cline) {
        is ClineSignInUi.SignedIn -> {
            Text(
                cline.displayName?.let { stringResource(R.string.connect_signed_in_as, it) } ?: stringResource(R.string.connect_signed_in),
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Button(onClick = { actions.onContinue(SetupMethod.Cline) }) { Text(stringResource(R.string.onboarding_continue)) }
            }
            return
        }
        else -> Unit
    }
    listOf(R.string.connect_cline_benefit_1, R.string.connect_cline_benefit_2, R.string.connect_cline_benefit_3).forEach { benefit ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(ClineIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(benefit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val waiting = ui.cline is ClineSignInUi.Waiting
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (waiting) {
            Button(onClick = {}, enabled = false) {
                InlineSpinner()
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.connect_waiting_browser))
            }
            TextButton(onClick = actions.onCancelSignIn) { Text(stringResource(R.string.connect_cancel)) }
        } else {
            Button(onClick = actions.onSignIn, enabled = enabled && !ui.clineBusy) { Text(stringResource(R.string.connect_sign_in)) }
            LinkButton(stringResource(R.string.connect_sign_up)) { actions.onOpenUrl(AccountUrls.CREATE_ACCOUNT_URL) }
        }
    }
    LiveRegionAnnouncer(if (waiting) stringResource(R.string.connect_waiting_browser) else null)
    if (waiting) {
        Text(stringResource(R.string.connect_waiting_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = actions.onOpenTermux) { Text(stringResource(R.string.setup_open_termux)) }
    }
    (ui.cline as? ClineSignInUi.Error)?.let { ErrorLine(it.message) }
    // "Use a Cline API key" disclosure (disabled while the sign-in runs, desktop clineBusy).
    val expandedWord = stringResource(R.string.setup_state_expanded)
    val collapsedWord = stringResource(R.string.setup_state_collapsed)
    TextButton(
        onClick = actions.onToggleClineKey,
        enabled = !ui.clineBusy,
        modifier = Modifier.semantics { stateDescription = if (ui.clineKeyFormExpanded) expandedWord else collapsedWord },
    ) {
        Text(stringResource(R.string.connect_cline_key_toggle), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(Spacing.xs))
        DisclosureChevron(expanded = ui.clineKeyFormExpanded, size = ButtonDefaults.IconSize, turn = ChevronTurn.DownToUp)
    }
    if (ui.clineKeyFormExpanded) {
        val requester = remember { BringIntoViewRequester() }
        val scope = rememberCoroutineScope()
        Column(Modifier.bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            KeyField(
                value = clineKey,
                onValueChange = onClineKeyChange,
                label = stringResource(R.string.connect_cline_key_field),
                placeholder = stringResource(R.string.connect_cline_key_field),
                visible = keyVisible,
                onVisible = onKeyVisible,
                enabled = enabled && !ui.clineKeySaving,
                imeAction = ImeAction.Done,
                onImeAction = { if (ui.canSubmitClineKey(clineKey)) actions.onSubmitClineKey(clineKey) },
                modifier = Modifier.onFocusChanged {
                    if (it.isFocused) scope.launch {
                        delay(IME_SETTLE_MS)
                        requester.bringIntoView()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LinkButton(stringResource(R.string.connect_find_key)) { actions.onOpenUrl(CLINE_DASHBOARD_URL) }
                Spacer(Modifier.weight(1f))
                ConnectButton(saving = ui.clineKeySaving, enabled = ui.canSubmitClineKey(clineKey)) { actions.onSubmitClineKey(clineKey) }
            }
            ui.clineKeyError?.let { ErrorLine(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyCardBody(
    ui: ConnectUiState,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    keyVisible: Boolean,
    onKeyVisible: (Boolean) -> Unit,
    actions: ConnectActions,
) {
    val enabled = ui.kernelUsable && !ui.loading && !ui.saving
    ui.connectedProviderName?.let { name ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connect_provider_connected, name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Button(onClick = { actions.onContinue(SetupMethod.ApiKey) }) { Text(stringResource(R.string.onboarding_continue)) }
        }
    }
    val loaded = ui.providers as? ProviderOptionsUi.Loaded
    loaded?.catalogError?.let { message ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.connect_providers_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            TextButton(onClick = actions.onRetryCatalog) { Text(stringResource(R.string.connect_try_again)) }
        }
    }
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val fieldTops = remember { mutableStateMapOf<ConnectField, Float>() }
    var formSize by remember { mutableFloatStateOf(0f) }
    var formWidth by remember { mutableFloatStateOf(0f) }
    fun reveal(field: ConnectField) {
        scope.launch {
            delay(IME_SETTLE_MS)
            // The focused field down to "Connect" and the error line under it (C.4).
            val top = fieldTops[field] ?: 0f
            requester.bringIntoView(Rect(0f, top, formWidth, formSize))
        }
    }
    val focused = remember { mutableStateMapOf<ConnectField, Boolean>() }
    fun Modifier.field(field: ConnectField): Modifier = this
        .onGloballyPositioned { fieldTops[field] = it.positionInParent().y }
        .onFocusChanged { state ->
            if (state.isFocused) {
                focused[field] = true
                reveal(field)
            } else if (focused.remove(field) == true) {
                actions.onFieldBlur(field)
            }
        }
    Column(
        Modifier
            .bringIntoViewRequester(requester)
            .onGloballyPositioned {
                formSize = it.size.height.toFloat()
                formWidth = it.size.width.toFloat()
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        var menuOpen by remember { mutableStateOf(false) }
        val option = ui.selectedOption
        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = {
                if (enabled && loaded != null) {
                    menuOpen = it
                    if (it) keyboard?.hide()
                }
            },
        ) {
            OutlinedTextField(
                value = option?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.connect_provider_label)) },
                placeholder = {
                    Text(stringResource(if (loaded == null) R.string.connect_provider_loading else R.string.connect_provider_placeholder))
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .field(ConnectField.Provider),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ui.options.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            menuOpen = false
                            actions.onSelectProvider(item.id)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        val providerName = option?.name ?: stringResource(R.string.connect_provider_label)
        KeyField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = stringResource(R.string.connect_api_key_label),
            placeholder = stringResource(R.string.connect_api_key_placeholder, providerName),
            visible = keyVisible,
            onVisible = onKeyVisible,
            enabled = enabled,
            imeAction = ImeAction.Next,
            onImeAction = null,
            modifier = Modifier.field(ConnectField.ApiKey),
        )
        val errors = ui.fieldErrors
        if (option?.needsBaseUrl == true) {
            val error = errors[ConnectField.BaseUrl]
            OutlinedTextField(
                value = ui.baseUrl,
                onValueChange = actions.onBaseUrlChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.connect_base_url_label)) },
                placeholder = { Text(stringResource(R.string.connect_base_url_placeholder)) },
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(R.string.connect_base_url_invalid)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                textStyle = ClineMono.body,
                modifier = Modifier.fillMaxWidth().field(ConnectField.BaseUrl),
            )
        }
        val modelError = errors[ConnectField.Model]
        OutlinedTextField(
            value = ui.modelId,
            onValueChange = actions.onModelChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.connect_model_label)) },
            isError = modelError != null,
            supportingText = {
                Text(stringResource(if (modelError != null) R.string.connect_model_required else R.string.connect_model_supporting))
            },
            textStyle = ClineMono.body,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { if (ui.canSubmitKey(apiKey)) actions.onConnect(apiKey) }),
            modifier = Modifier.fillMaxWidth().field(ConnectField.Model),
        )
        option?.keyUrl?.let { url ->
            LinkButton(option.docLabel ?: stringResource(R.string.connect_get_key, option.name)) { actions.onOpenUrl(url) }
        }
        if (loaded?.source == ProviderSource.Bundled) {
            Text(stringResource(R.string.connect_more_providers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            ConnectButton(saving = ui.saving, enabled = ui.canSubmitKey(apiKey)) { actions.onConnect(apiKey) }
        }
        ui.saveError?.let { ErrorLine(it) }
    }
}

/** A password field with the separate 48dp show/hide toggle ("Show API key" / "Hide API key"). */
@Composable
private fun KeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    visible: Boolean,
    onVisible: (Boolean) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction,
    onImeAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { onVisible(!visible) }) {
                Icon(
                    if (visible) ClineIcons.VisibilityOff else ClineIcons.Visibility,
                    contentDescription = stringResource(if (visible) R.string.connect_hide_key else R.string.connect_show_key),
                )
            }
        },
        modifier = modifier.fillMaxWidth().semantics { password() },
    )
}

@Composable
private fun ConnectButton(saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !saving) {
        if (saving) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.connect_connecting))
        } else {
            Text(stringResource(R.string.connect_connect))
        }
    }
}

/** A `TextButton` link with the trailing `open_in_new` and the CD "<label>, opens in browser". */
@Composable
fun LinkButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cd = stringResource(R.string.onboarding_link_cd, label)
    TextButton(onClick = onClick, modifier = modifier.semantics { contentDescription = cd }) {
        Text(label)
        Spacer(Modifier.width(Spacing.xs))
        Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    }
}

/** Inline error under the action that failed (`bodySmall` `error`, assertive, desktop `role="alert"`). */
@Composable
private fun ErrorLine(message: UiText) {
    Text(
        message.resolve(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

private const val KEY_CLEAR_AFTER_MS = 10_000L

/** Lets the IME finish opening before the form is brought into view. */
private const val IME_SETTLE_MS = 250L

// ---- previews -----------------------------------------------------------------------------------

private val previewOptions = listOf(
    ProviderOption("anthropic", "Anthropic", "claude-sonnet-5", needsBaseUrl = false, keyUrl = "https://console.anthropic.com/settings/keys"),
    ProviderOption("openai-native", "OpenAI", "gpt-5.4", needsBaseUrl = false, keyUrl = "https://platform.openai.com/api-keys"),
    ProviderOption("openai-compatible", "OpenAI Compatible", null, needsBaseUrl = true, keyUrl = null),
)

private fun previewUi(
    cline: ClineSignInUi = ClineSignInUi.Idle,
    method: SetupMethod = SetupMethod.Cline,
    configured: ConfiguredProviders? = ConfiguredProviders.EMPTY,
    expanded: Boolean = false,
    selected: String? = null,
    saving: Boolean = false,
    saveError: UiText? = null,
    kernelUsable: Boolean = true,
    catalogError: String? = null,
) = ConnectUiState(
    kernelUsable = kernelUsable,
    configured = configured,
    method = method,
    cline = cline,
    clineKeyFormExpanded = expanded,
    providers = ProviderOptionsUi.Loaded(previewOptions, if (catalogError != null) ProviderSource.Bundled else ProviderSource.Helper, catalogError),
    selectedProviderId = selected,
    modelId = previewOptions.firstOrNull { it.id == selected }?.defaultModelId.orEmpty(),
    saving = saving,
    saveError = saveError,
)

@Composable
private fun ConnectPreview(ui: ConnectUiState, key: String = "") {
    ClinePreviewTheme { ConnectContent(ui, key, {}, "", {}, ConnectActions()) }
}

@LightDarkPreviews
@Composable
private fun ConnectEmptyPreview() = ConnectPreview(previewUi())

@LightDarkPreviews
@Composable
private fun ConnectLoadingPreview() = ConnectPreview(previewUi(configured = null).copy(providers = ProviderOptionsUi.Loading))

@LightDarkPreviews
@Composable
private fun ConnectWaitingPreview() = ConnectPreview(previewUi(cline = ClineSignInUi.Waiting(0L, 1)))

@LightDarkPreviews
@Composable
private fun ConnectSignInErrorPreview() = ConnectPreview(
    previewUi(cline = ClineSignInUi.Error(UiText.Res(R.string.connect_sign_in_failed, UiText.Res(R.string.connect_sign_in_timeout)))),
)

@LightDarkPreviews
@Composable
private fun ConnectClineKeyPreview() = ConnectPreview(
    previewUi(expanded = true).copy(clineKeyError = UiText.Res(R.string.connect_cline_key_failed, UiText.Res(R.string.connect_cline_key_unverified_detail, "401"))),
)

@LightDarkPreviews
@Composable
private fun ConnectSignedInPreview() = ConnectPreview(previewUi(cline = ClineSignInUi.SignedIn("ada@example.com")))

@LightDarkPreviews
@Composable
private fun ConnectApiKeyPreview() = ConnectPreview(previewUi(method = SetupMethod.ApiKey, selected = "anthropic"), key = "sk-ant-preview")

@LightDarkPreviews
@Composable
private fun ConnectSavingPreview() = ConnectPreview(previewUi(method = SetupMethod.ApiKey, selected = "anthropic", saving = true), key = "sk-ant-preview")

@LightDarkPreviews
@Composable
private fun ConnectSaveErrorPreview() = ConnectPreview(
    previewUi(
        method = SetupMethod.ApiKey,
        selected = "openai-compatible",
        saveError = UiText.Res(R.string.connect_save_failed, UiText.Raw("invalid provider \"x\"")),
    ).copy(baseUrl = "ftp://x", touched = setOf(ConnectField.BaseUrl, ConnectField.Model)),
)

@LightDarkPreviews
@Composable
private fun ConnectCatalogErrorPreview() = ConnectPreview(previewUi(method = SetupMethod.ApiKey, catalogError = "settings helper closed the connection"))

@LightDarkPreviews
@Composable
private fun ConnectKernelDownPreview() = ConnectPreview(previewUi(kernelUsable = false))

@LightDarkPreviews
@Composable
private fun ConnectProviderConnectedPreview() = ConnectPreview(
    previewUi(
        method = SetupMethod.ApiKey,
        configured = ConfiguredProviders(
            lastUsedProvider = "anthropic",
            providers = mapOf(
                "anthropic" to ConfiguredProvider("anthropic", hasApiKey = true, hasOAuth = false, hasCloudCredentials = false, model = "claude-sonnet-5", hasBaseUrl = false, tokenSource = "manual", updatedAt = null),
            ),
        ),
    ),
)
