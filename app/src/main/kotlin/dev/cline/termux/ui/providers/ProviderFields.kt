package dev.cline.termux.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.ClineSwitch

/*
 * S5.2 config field rows (desktop `ProviderConfigFieldControl`): text-like fields keep a draft
 * that is committed on blur or IME Done, selects and switches commit on change. Secret fields
 * are masked until "Show secret" and copy through `SecureClipboard` (SP §0.6).
 */

/** What a field row does; the detail screen wires these to the VM and its draft map. */
class FieldCallbacks(
    val onDraft: (path: String, text: String) -> Unit,
    val onCommit: (path: String) -> Unit,
    val onSelect: (path: String, valueText: String) -> Unit,
    val onBoolean: (path: String, checked: Boolean) -> Unit,
    val onToggleShown: (path: String) -> Unit,
    val onCopySecret: (label: String, path: String) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

/** One config field: [text] is the draft (or the stored value), [shown] reveals a secret. */
@Composable
fun ProviderFieldRow(
    field: FieldUi,
    providerName: String,
    text: String,
    shown: Boolean,
    enabled: Boolean,
    callbacks: FieldCallbacks,
    modifier: Modifier = Modifier,
) {
    when (field.type) {
        "boolean" -> BooleanFieldRow(field, enabled, callbacks, modifier)
        "select" -> SelectFieldRow(field, text, enabled, callbacks, modifier)
        else -> TextFieldRow(field, providerName, text, shown, enabled, callbacks, modifier)
    }
}

@Composable
private fun BooleanFieldRow(field: FieldUi, enabled: Boolean, callbacks: FieldCallbacks, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = field.checked, enabled = enabled, role = Role.Switch) { callbacks.onBoolean(field.path, it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            field.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (field.saving) SavingSpinner()
        ClineSwitch(checked = field.checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectFieldRow(field: FieldUi, text: String, enabled: Boolean, callbacks: FieldCallbacks, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val notSet = stringResource(R.string.provider_select_not_set)
    val selectedLabel = field.options.firstOrNull { it.valueText == text }?.label ?: text.ifEmpty { notSet }
    Column(modifier.fillMaxWidth()) {
    FieldHeader(field, providerName = "", callbacks = callbacks)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (field.saving) SavingSpinner()
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = field.label }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(notSet) },
                onClick = {
                    open = false
                    callbacks.onSelect(field.path, "")
                },
            )
            for (option in field.options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        callbacks.onSelect(field.path, option.valueText)
                    },
                )
            }
        }
    }
    }
}

@Composable
private fun TextFieldRow(
    field: FieldUi,
    providerName: String,
    text: String,
    shown: Boolean,
    enabled: Boolean,
    callbacks: FieldCallbacks,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val masked = field.secret && !shown
    Column(modifier.fillMaxWidth()) {
        FieldHeader(field, providerName, callbacks)
        OutlinedTextField(
            value = text,
            onValueChange = { callbacks.onDraft(field.path, it) },
            enabled = enabled,
            singleLine = true,
            placeholder = field.placeholder?.let { { Text(it) } },
            leadingIcon = if (field.type == "url") {
                { Icon(ClineIcons.Link, contentDescription = null) }
            } else {
                null
            },
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    field.secret -> KeyboardType.Password
                    field.type == "url" -> KeyboardType.Uri
                    field.type == "number" -> KeyboardType.Number
                    else -> KeyboardType.Text
                },
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                callbacks.onCommit(field.path)
                focusManager.clearFocus()
            }),
            trailingIcon = if (field.secret || field.saving) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (field.saving) SavingSpinner()
                        if (field.secret) SecretFieldActions(field, shown, text.isNotEmpty(), callbacks)
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = field.label }
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) callbacks.onCommit(field.path)
                    focused = state.isFocused
                },
        )
    }
}

/**
 * Desktop `ConfigFieldRow` header: the label (titleMedium), its description and, for a
 * provider in the curated key map, the "Get a <Name> API key" link, all above the control.
 */
@Composable
private fun FieldHeader(field: FieldUi, providerName: String, callbacks: FieldCallbacks) {
    Text(
        field.label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clearAndSetSemantics {},
    )
    field.description?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
    field.keyUrl?.let { url ->
        TextButton(onClick = { callbacks.onOpenUrl(url) }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
            Text(stringResource(R.string.provider_get_key, providerName))
            Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = Spacing.xs).size(16.dp))
        }
    }
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun SecretFieldActions(field: FieldUi, shown: Boolean, hasValue: Boolean, callbacks: FieldCallbacks) {
    val state = stringResource(if (shown) R.string.provider_secret_shown else R.string.provider_secret_hidden)
    TooltipIconButton(
        icon = if (shown) ClineIcons.VisibilityOff else ClineIcons.Visibility,
        contentDescription = stringResource(if (shown) R.string.provider_hide_secret else R.string.provider_show_secret),
        onClick = { callbacks.onToggleShown(field.path) },
        modifier = Modifier.semantics { stateDescription = state },
    )
    if (hasValue) {
        val copyLabel = stringResource(R.string.provider_copy_field, field.label)
        TooltipIconButton(
            icon = ClineIcons.ContentCopy,
            contentDescription = copyLabel,
            onClick = { callbacks.onCopySecret(field.label, field.path) },
        )
    }
}

@Composable
internal fun SavingSpinner() {
    val saving = stringResource(R.string.providers_state_saving)
    InlineSpinner(Modifier.padding(horizontal = Spacing.s).semantics { stateDescription = saving })
}

// ---- §0.4 fallback form ------------------------------------------------------------------

/**
 * The fallback `cline auth` form: API Key (remember-only, masked unless shown), Model ID and,
 * where accepted, Base URL. Nothing here is persisted; the key goes straight to [onSave].
 */
@Composable
fun FallbackAuthForm(
    form: FallbackFormUi,
    save: FallbackSaveState,
    onSave: (apiKey: String, modelId: String, baseUrl: String?) -> Unit,
    onCopySecret: (label: String, secret: String) -> Unit,
    onRevealChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember(form.initialModelId) { mutableStateOf(form.initialModelId) }
    var baseUrl by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val saving = save == FallbackSaveState.Saving
    val keyLabel = stringResource(R.string.provider_field_api_key)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            enabled = !saving,
            singleLine = true,
            label = { Text(keyLabel) },
            placeholder = if (form.apiKeySaved) {
                { Text(stringResource(R.string.provider_fallback_saved_placeholder)) }
            } else {
                null
            },
            supportingText = if (form.keylessHint) {
                { Text(stringResource(R.string.provider_keyless_hint)) }
            } else {
                null
            },
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val state = stringResource(if (shown) R.string.provider_secret_shown else R.string.provider_secret_hidden)
                    TooltipIconButton(
                        icon = if (shown) ClineIcons.VisibilityOff else ClineIcons.Visibility,
                        contentDescription = stringResource(if (shown) R.string.provider_hide_secret else R.string.provider_show_secret),
                        onClick = {
                            shown = !shown
                            onRevealChange(shown)
                        },
                        modifier = Modifier.semantics { stateDescription = state },
                    )
                    if (apiKey.isNotEmpty()) {
                        TooltipIconButton(
                            icon = ClineIcons.ContentCopy,
                            contentDescription = stringResource(R.string.provider_copy_field, keyLabel),
                            onClick = { onCopySecret(keyLabel, apiKey) },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            enabled = !saving,
            singleLine = true,
            label = { Text(stringResource(R.string.provider_field_model_id)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (form.showBaseUrl) ImeAction.Next else ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.showBaseUrl) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                enabled = !saving,
                singleLine = true,
                label = { Text(stringResource(R.string.provider_field_base_url)) },
                leadingIcon = { Icon(ClineIcons.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onSave(apiKey, modelId, baseUrl.takeIf { form.showBaseUrl }) },
                enabled = !saving && apiKey.isNotBlank() && modelId.isNotBlank(),
            ) {
                if (saving) {
                    InlineSpinner(Modifier.padding(end = Spacing.s))
                }
                Text(stringResource(R.string.provider_fallback_save))
            }
        }
        // S5.2: the stripped stderr line sits under the Save button.
        if (save is FallbackSaveState.Error) {
            Text(
                text = when (val error = save.error) {
                    is FallbackError.Message -> error.text
                    is FallbackError.ExitCode -> stringResource(R.string.provider_fallback_exit, error.code)
                    FallbackError.TimedOut -> stringResource(R.string.kernel_blocked_timeout_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}
