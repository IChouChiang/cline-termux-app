package dev.cline.termux.ui.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.ui.components.AdaptiveDialog
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.DialogSecureEffect
import dev.cline.termux.ui.navigation.EditorBackHandler
import dev.cline.termux.ui.navigation.EditorDialogWindow
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.SecureWindowEffect
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/*
 * S6 Add OpenAI-compatible provider (desktop add-provider.tsx): a full-screen editor on compact
 * and medium windows, a 560dp dialog on expanded ones. The form lives in the VM's
 * SavedStateHandle; the API key only in `remember` state here (docs/07 §3.7). Closing a dirty
 * form asks "Discard this provider?" (S6.2).
 */

/** Callbacks of the stateless form. */
class AddProviderCallbacks(
    val onUpdate: ((AddProviderForm) -> AddProviderForm) -> Unit,
    val onApiKey: (String) -> Unit,
    val onToggleKeyShown: () -> Unit,
    val onCopyKey: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
)

/**
 * The editor wired to [vm]. [asDialog] = expanded windows (drawn inside a dialog destination);
 * [onClose] pops the editor.
 */
@Composable
fun AddProviderRoute(
    vm: ProvidersViewModel,
    asDialog: Boolean,
    onClose: () -> Unit,
    copySecret: (label: String, secret: String) -> Unit,
) {
    val state by vm.addState.collectAsStateWithLifecycle()
    // Held by the graph-scoped VM in memory (not SavedStateHandle): survives rotation, not process death.
    val apiKey by vm.addKeyDraft.collectAsStateWithLifecycle()
    var keyShown by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = state.form.dirty || apiKey.isNotEmpty()
    val keyLabel = stringResource(R.string.add_provider_api_key_label)

    val discard = {
        confirmDiscard = false
        vm.resetAddForm()
        onClose()
    }
    val requestClose = {
        if (!state.saving) {
            if (dirty) confirmDiscard = true else discard()
        }
    }
    EditorBackHandler(dirty = dirty, saving = state.saving, onRequestDiscard = { confirmDiscard = true })
    // Inside a dialog window nothing else handles Back on a clean form.
    BackHandler(enabled = asDialog && !dirty && !state.saving) { discard() }

    val callbacks = remember(vm) {
        AddProviderCallbacks(
            onUpdate = vm::updateAddForm,
            onApiKey = vm::setAddKeyDraft,
            onToggleKeyShown = { keyShown = !keyShown },
            onCopyKey = { if (apiKey.isNotEmpty()) copySecret(keyLabel, apiKey) },
            onRetryHelper = vm::retryHelper,
            onStartKernel = vm::startKernel,
        )
    }
    val save = { vm.saveNewProvider(apiKey) }
    val confirmLabel = stringResource(if (state.saving) R.string.add_provider_saving else R.string.add_provider_save)

    if (asDialog) {
        DialogSecureEffect(secure = keyShown)
        // The window's tap detector adds no semantics: TalkBack keeps the title heading and the
        // form's own nodes, and closes through Back or Cancel (DS §8.1.1, §8.1.7).
        EditorDialogWindow(onTapOutside = requestClose) {
            AddProviderDialogBody(state, apiKey, keyShown, callbacks, onCancel = requestClose, onSave = save)
        }
    } else {
        SecureWindowEffect(revealed = keyShown, keyFieldShown = true)
        val medium = LocalShellLayout.current.width != WidthClass.Compact
        AdaptiveDialog(
            expanded = false,
            title = stringResource(R.string.add_provider_title),
            onDismissRequest = requestClose,
            confirmLabel = confirmLabel,
            onConfirm = save,
            confirmEnabled = state.canSave,
            busy = state.saving,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .then(if (medium) Modifier.widthIn(max = Spacing.pageMaxWidth) else Modifier)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = LocalShellLayout.current.gutter, vertical = Spacing.l),
                ) {
                    AddProviderFormContent(state, apiKey, keyShown, callbacks, twoColumn = medium, asDialog = false)
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.add_provider_discard_title),
            confirmLabel = stringResource(R.string.add_provider_discard),
            onConfirm = discard,
            onDismiss = { confirmDiscard = false },
            text = stringResource(R.string.add_provider_discard_text),
            dismissLabel = stringResource(R.string.add_provider_keep_editing),
            destructive = true,
        )
    }
}

/**
 * The expanded-window dialog content (S6.1 "Expanded"): title, the form in a scrolling column,
 * the save error above the footer, footer "Cancel" + "Add Provider" only.
 */
@Composable
fun AddProviderDialogBody(
    state: AddProviderUiState,
    apiKey: String,
    keyShown: Boolean,
    callbacks: AddProviderCallbacks,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.padding(Spacing.xxl)) {
        Text(
            stringResource(R.string.add_provider_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.size(Spacing.l))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            AddProviderFormContent(state, apiKey, keyShown, callbacks, twoColumn = true, asDialog = true)
        }
        state.error?.let { error ->
            Spacer(Modifier.size(Spacing.m))
            AddProviderError(error)
        }
        Spacer(Modifier.size(Spacing.xxl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = !state.saving) { Text(stringResource(R.string.components_cancel)) }
            Spacer(Modifier.width(Spacing.s))
            Button(onClick = onSave, enabled = state.canSave && !state.saving) {
                if (state.saving) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(stringResource(if (state.saving) R.string.add_provider_saving else R.string.add_provider_save))
            }
        }
    }
}

/** The S6 save error: `bodySmall error`, announced politely. */
@Composable
private fun AddProviderError(error: String, modifier: Modifier = Modifier) {
    Text(
        error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The S6 fields. [twoColumn] puts Provider ID and Name side by side. On the full-screen editor
 * ([asDialog] false) the save error sits right under the description and is scrolled into
 * view; the dialog draws it above its footer instead ([AddProviderDialogBody]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddProviderFormContent(
    state: AddProviderUiState,
    apiKey: String,
    keyShown: Boolean,
    callbacks: AddProviderCallbacks,
    twoColumn: Boolean,
    asDialog: Boolean,
) {
    val form = state.form
    val enabled = !state.saving
    val update = callbacks.onUpdate
    val errorRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.error, asDialog) { if (state.error != null && !asDialog) errorRequester.bringIntoView() }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.add_provider_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!asDialog) {
            state.error?.let { error -> AddProviderError(error, Modifier.bringIntoViewRequester(errorRequester)) }
        }
        state.helperCard?.let {
            HelperUnavailableCard(model = it, onRetry = callbacks.onRetryHelper, onStartKernel = callbacks.onStartKernel)
        }
        Text(stringResource(R.string.add_provider_group), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })

        val idField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = form.providerId,
                onValueChange = { text -> update { it.copy(providerId = text) } },
                enabled = enabled,
                singleLine = true,
                isError = state.duplicate,
                label = { Text(stringResource(R.string.add_provider_id_label)) },
                placeholder = { Text(stringResource(R.string.add_provider_id_placeholder)) },
                supportingText = {
                    Text(stringResource(if (state.duplicate) R.string.add_provider_id_duplicate else R.string.add_provider_id_help))
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = modifier,
            )
        }
        val nameField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = form.name,
                onValueChange = { text -> update { it.copy(name = text) } },
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.add_provider_name_label)) },
                placeholder = { Text(stringResource(R.string.add_provider_name_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = modifier,
            )
        }
        if (twoColumn) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                idField(Modifier.weight(1f))
                nameField(Modifier.weight(1f))
            }
        } else {
            idField(Modifier.fillMaxWidth())
            nameField(Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { text -> update { it.copy(baseUrl = text) } },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.add_provider_base_url_label)) },
            placeholder = { Text(stringResource(R.string.add_provider_base_url_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.modelsSourceUrl,
            onValueChange = { text -> update { it.copy(modelsSourceUrl = text) } },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.add_provider_source_label)) },
            placeholder = { Text(stringResource(R.string.add_provider_source_placeholder)) },
            supportingText = { Text(stringResource(R.string.add_provider_source_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        ModelChipsField(form, enabled, update)
        if (form.models.size > 1) DefaultModelField(form, enabled, update)
        ApiKeyField(apiKey, keyShown, enabled, callbacks)
        CapabilityChips(form, enabled, update)
        AdvancedSection(form, enabled, update)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChipsField(form: AddProviderForm, enabled: Boolean, update: ((AddProviderForm) -> AddProviderForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (form.models.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (model in form.models) {
                    val removeLabel = stringResource(R.string.add_provider_remove_model, model)
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { update { it.removeModel(model) } },
                        label = { Text(model, style = ClineMono.small) },
                        trailingIcon = {
                            Icon(ClineIcons.Close, contentDescription = removeLabel, modifier = Modifier.size(InputChipDefaults.IconSize))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = form.modelInput,
            onValueChange = { text -> update { it.typeModelInput(text) } },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.add_provider_models_label)) },
            // S6.2: the hint shows only while no model chip exists yet.
            placeholder = if (form.models.isEmpty()) {
                { Text(stringResource(R.string.add_provider_models_placeholder)) }
            } else {
                null
            },
            supportingText = { Text(stringResource(R.string.add_provider_models_help)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { update { it.commitModelInput() } }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter -> {
                            update { it.commitModelInput() }
                            true
                        }
                        event.key == Key.Backspace && form.modelInput.isEmpty() && form.models.isNotEmpty() -> {
                            update { it.removeLastModel() }
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultModelField(form: AddProviderForm, enabled: Boolean, update: ((AddProviderForm) -> AddProviderForm) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = form.defaultModel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.add_provider_default_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (model in form.models) {
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        open = false
                        update { it.copy(defaultModel = model) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(apiKey: String, shown: Boolean, enabled: Boolean, callbacks: AddProviderCallbacks) {
    val state = stringResource(if (shown) R.string.provider_secret_shown else R.string.provider_secret_hidden)
    OutlinedTextField(
        value = apiKey,
        onValueChange = callbacks.onApiKey,
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.add_provider_api_key_label)) },
        placeholder = { Text(stringResource(R.string.add_provider_api_key_placeholder)) },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(
                    icon = if (shown) ClineIcons.VisibilityOff else ClineIcons.Visibility,
                    contentDescription = stringResource(if (shown) R.string.add_provider_hide_key else R.string.add_provider_show_key),
                    onClick = callbacks.onToggleKeyShown,
                    modifier = Modifier.semantics { stateDescription = state },
                )
                if (apiKey.isNotEmpty()) {
                    TooltipIconButton(
                        icon = ClineIcons.ContentCopy,
                        contentDescription = stringResource(R.string.add_provider_copy_key),
                        onClick = callbacks.onCopyKey,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(form: AddProviderForm, enabled: Boolean, update: ((AddProviderForm) -> AddProviderForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.add_provider_capabilities), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for (capability in ProviderCapability.entries) {
                val selected = capability.wire in form.capabilities
                FilterChip(
                    selected = selected,
                    enabled = enabled,
                    onClick = { update { it.toggleCapability(capability) } },
                    label = { Text(capabilityLabel(capability)) },
                    leadingIcon = if (selected) {
                        { Icon(ClineIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun capabilityLabel(capability: ProviderCapability): String = stringResource(
    when (capability) {
        ProviderCapability.Streaming -> R.string.add_provider_cap_streaming
        ProviderCapability.Tools -> R.string.add_provider_cap_tools
        ProviderCapability.Reasoning -> R.string.add_provider_cap_reasoning
        ProviderCapability.Vision -> R.string.add_provider_cap_vision
        ProviderCapability.PromptCache -> R.string.add_provider_cap_prompt_cache
    },
)

@Composable
private fun AdvancedSection(form: AddProviderForm, enabled: Boolean, update: ((AddProviderForm) -> AddProviderForm) -> Unit) {
    val expandedState = stringResource(if (form.advancedOpen) R.string.providers_state_expanded else R.string.providers_state_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TextButton(
            onClick = { update { it.copy(advancedOpen = !it.advancedOpen) } },
            modifier = Modifier.semantics { stateDescription = expandedState },
        ) {
            Icon(
                if (form.advancedOpen) ClineIcons.ExpandMore else ClineIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.add_provider_advanced))
        }
        if (!form.advancedOpen) return@Column
        OutlinedTextField(
            value = form.timeoutMs,
            onValueChange = { text -> update { it.copy(timeoutMs = text) } },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.add_provider_timeout_label)) },
            placeholder = { Text(stringResource(R.string.add_provider_timeout_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.add_provider_headers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        form.headers.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = row.key,
                    onValueChange = { text -> update { it.updateHeader(index, row.copy(key = text)) } },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_provider_header_name)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { text -> update { it.updateHeader(index, row.copy(value = text)) } },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_provider_header_value)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(
                    icon = ClineIcons.Delete,
                    contentDescription = stringResource(R.string.add_provider_remove_header),
                    onClick = { update { it.removeHeader(index) } },
                    enabled = enabled,
                )
            }
        }
        TextButton(onClick = { update { it.addHeader() } }, enabled = enabled) {
            Icon(ClineIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.add_provider_add_header))
        }
    }
}
