package dev.cline.termux.ui.customize.mcp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.McpInputError
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.ShellKernelUi
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.toUnavailableModel
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.DialogSecureEffect
import dev.cline.termux.ui.navigation.EditorDialogWindow
import dev.cline.termux.ui.navigation.SecureWindowEffect
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.ClineSwitch

/** Everything the editor form calls back into (the ViewModel in the app, no-ops in previews). */
class McpEditorActions(
    val onName: (String) -> Unit,
    val onType: (ServerType) -> Unit,
    val onTransport: (RemoteTransport) -> Unit,
    val onCommand: (String) -> Unit,
    val onArgs: (String) -> Unit,
    val onCwd: (String) -> Unit,
    val onEnvKey: (Long, String) -> Unit,
    val onEnvValue: (Long, String) -> Unit,
    val onToggleReveal: (Long) -> Unit,
    val onAddEnv: () -> Unit,
    val onRemoveEnv: (Long) -> Unit,
    val onUrl: (String) -> Unit,
    val onHeaders: (String) -> Unit,
    val onEnabled: (Boolean) -> Unit,
    val onAdvanced: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onRequestDelete: () -> Unit,
) {
    companion object {
        fun of(vm: McpServerEditorViewModel) = McpEditorActions(
            vm::setName, vm::setType, vm::setTransport, vm::setCommand, vm::setArgs, vm::setCwd, vm::setEnvKey, vm::setEnvValue,
            vm::toggleReveal, vm::addEnv, vm::removeEnv, vm::setUrl, vm::setHeaders, vm::setEnabled, vm::setAdvancedOpen, vm::save,
            vm::retryHelper, vm::requestDelete,
        )

        val None = McpEditorActions({}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The MCP server editor (CR C2). [asDialog] = the expanded `dialog()` destination (560dp
 * surface, footer buttons, no app bar); otherwise the full-screen page with the M3
 * full-screen dialog anatomy. Back: discard confirm when dirty, consumed while saving
 * (CR §0.2, §2.7); Ctrl+Enter saves, Esc closes (CR §2.6).
 */
@Composable
fun McpServerEditorScreen(viewModel: McpServerEditorViewModel, asDialog: Boolean, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discardOpen by rememberSaveable { mutableStateOf(false) }
    val requestClose = {
        when {
            state.saving || state.deleting -> Unit
            state.dirty -> discardOpen = true
            else -> onClose()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event == McpEditorEvent.Close) onClose() }
    }

    if (asDialog) {
        // The dialog window never dismisses itself (EditorDialogProperties): Back comes here, outside taps
        // reach requestClose through EditorDialogWindow.
        BackHandler(enabled = true) { requestClose() }
        DialogSecureEffect(state.anyRevealed)
    } else {
        BackHandler(enabled = state.dirty && !state.saving && !state.deleting) { discardOpen = true }
        BackHandler(enabled = state.saving || state.deleting) { }
        SecureWindowEffect(revealed = state.anyRevealed)
    }

    McpServerEditorContent(
        state = state,
        asDialog = asDialog,
        actions = McpEditorActions.of(viewModel),
        onClose = requestClose,
    )

    if (discardOpen) {
        McpDiscardDialog(
            onConfirm = {
                discardOpen = false
                onClose()
            },
            onDismiss = { discardOpen = false },
        )
    }
    if (state.metadataConfirm) {
        val keys = state.metadataKeys.joinToString(", ")
        ConfirmDialog(
            title = stringResource(R.string.mcp_metadata_confirm_title),
            text = stringResource(R.string.mcp_metadata_confirm_text, keys),
            confirmLabel = stringResource(R.string.mcp_metadata_confirm_save),
            onConfirm = viewModel::confirmMetadataSave,
            onDismiss = viewModel::dismissMetadataConfirm,
        )
    }
    if (state.deleteDialog) McpDeleteDialog(state, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::dismissDelete)
}

/** "Discard changes?" (CR §2.7): back or close with unsaved edits. */
@Composable
private fun McpDiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.mcp_discard_title),
        text = stringResource(R.string.mcp_discard_text),
        confirmLabel = stringResource(R.string.mcp_discard),
        dismissLabel = stringResource(R.string.mcp_keep_editing),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The delete confirm; [McpEditorUiState.deleting] keeps it open with "Deleting...". */
@Composable
private fun McpDeleteDialog(state: McpEditorUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = (state.mode as? McpEditorMode.Edit)?.originalName.orEmpty()
    ConfirmDialog(
        title = stringResource(R.string.mcp_delete_title),
        text = if (name.isNotBlank()) stringResource(R.string.mcp_delete_text, name) else stringResource(R.string.mcp_delete_text_fallback),
        confirmLabel = stringResource(R.string.mcp_delete),
        destructive = true,
        confirming = state.deleting,
        confirmingLabel = stringResource(R.string.mcp_deleting),
        error = state.deleteError?.text(),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The stateless editor (previews render it directly). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerEditorContent(state: McpEditorUiState, asDialog: Boolean, actions: McpEditorActions, onClose: () -> Unit) {
    val title = stringResource(if (state.isEdit) R.string.mcp_title_edit else R.string.mcp_title_add)
    val keys = Modifier.onPreviewKeyEvent { event ->
        when {
            event.type != KeyEventType.KeyDown -> false
            event.key == Key.Enter && event.isCtrlPressed -> {
                actions.onSave()
                true
            }
            event.key == Key.Escape -> {
                onClose()
                true
            }
            else -> false
        }
    }
    val waiting = state.helper is HelperState.Starting || (state.saving && state.route == McpRoute.Waiting)
    if (asDialog) {
        // A tap outside the surface is a close request: discard confirm when dirty, ignored while saving.
        EditorDialogWindow(onTapOutside = onClose, surfaceModifier = keys) {
            Column(Modifier.padding(top = Spacing.xxl, bottom = Spacing.l)) {
                Row(Modifier.padding(horizontal = Spacing.xxl), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    if (state.isEdit) EditorOverflow(enabled = !state.saving && !state.deleting, onDelete = actions.onRequestDelete)
                }
                if (waiting) HelperStartingLine(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.s))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.xxl),
                ) {
                    EditorForm(state, actions)
                }
                Spacer(Modifier.height(Spacing.l))
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, enabled = !state.saving && !state.deleting) { Text(stringResource(R.string.components_cancel)) }
                    if (!state.blockedInFallback) {
                        Spacer(Modifier.width(Spacing.s))
                        Button(onClick = actions.onSave, enabled = state.canSave) { PrimaryLabel(state) }
                    }
                }
            }
        }
    } else {
        Scaffold(
            modifier = keys,
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime).union(WindowInsets.bottomSafe),
            snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            TooltipIconButton(
                                ClineIcons.Close,
                                contentDescription = stringResource(R.string.components_close),
                                onClick = onClose,
                                enabled = !state.saving && !state.deleting,
                            )
                        },
                        actions = {
                            if (!state.blockedInFallback) {
                                TextButton(onClick = actions.onSave, enabled = state.canSave) { PrimaryLabel(state) }
                            }
                            if (state.isEdit) EditorOverflow(enabled = !state.saving && !state.deleting, onDelete = actions.onRequestDelete)
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    if (waiting) HelperStartingLine(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs))
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    EditorForm(state, actions)
                }
            }
        }
    }
}

@Composable
private fun HelperStartingLine(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.xs))
        Text(stringResource(R.string.customize_helper_starting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrimaryLabel(state: McpEditorUiState) {
    if (state.saving) {
        InlineSpinner()
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.mcp_saving))
    } else {
        Text(stringResource(if (state.isEdit) R.string.mcp_save_changes else R.string.mcp_add_server))
    }
}

@Composable
private fun EditorOverflow(enabled: Boolean, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(ClineIcons.MoreVert, contentDescription = stringResource(R.string.mcp_more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 200.dp)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mcp_delete_server)) },
                leadingIcon = { Icon(ClineIcons.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** The form fields, in the desktop order (CR §2.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.EditorForm(state: McpEditorUiState, actions: McpEditorActions) {
    val enabled = !state.saving && !state.loading && !state.deleting
    val nameFocus = remember { FocusRequester() }
    val commandFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(state.fieldError, state.formError) {
        when (state.fieldError) {
            McpField.Name -> runCatching { nameFocus.requestFocus() }
            McpField.Command -> runCatching { commandFocus.requestFocus() }
            McpField.Url -> runCatching { urlFocus.requestFocus() }
            null -> Unit
        }
    }
    val errorText = state.formError?.text()
    val local = state.type == ServerType.Local

    if (!state.kernelReady) ShellKernelBanner(hasComposer = false, modifier = Modifier.padding(bottom = Spacing.s))
    if (state.fallback) FallbackCard(state.helper as? HelperState.Unavailable, actions.onRetryHelper)

    PathDescription(state)
    Spacer(Modifier.height(Spacing.l))

    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        enabled = enabled,
        label = { Text(stringResource(R.string.mcp_server_name)) },
        placeholder = { Text(stringResource(R.string.mcp_server_name_placeholder)) },
        singleLine = true,
        isError = state.fieldError == McpField.Name,
        supportingText = if (state.fieldError == McpField.Name && errorText != null) ({ Text(errorText) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).errorSemantics(state.fieldError == McpField.Name, errorText),
    )
    Spacer(Modifier.height(Spacing.m))

    Text(stringResource(R.string.mcp_server_type), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.s))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TypeCard(ServerType.Local, state.type, enabled, R.string.mcp_type_local, R.string.mcp_type_local_description, actions.onType)
        TypeCard(ServerType.Remote, state.type, enabled, R.string.mcp_type_remote, R.string.mcp_type_remote_description, actions.onType)
    }
    Spacer(Modifier.height(Spacing.m))

    if (local) {
        OutlinedTextField(
            value = state.command,
            onValueChange = actions.onCommand,
            enabled = enabled,
            label = { Text(stringResource(R.string.mcp_command)) },
            placeholder = { Text(stringResource(R.string.mcp_command_placeholder)) },
            singleLine = true,
            isError = state.fieldError == McpField.Command,
            supportingText = if (state.fieldError == McpField.Command && errorText != null) ({ Text(errorText) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().focusRequester(commandFocus).errorSemantics(state.fieldError == McpField.Command, errorText),
        )
        Spacer(Modifier.height(Spacing.m))
        OutlinedTextField(
            value = state.argsText,
            onValueChange = actions.onArgs,
            enabled = enabled,
            label = { Text(stringResource(R.string.mcp_args)) },
            placeholder = { Text(stringResource(R.string.mcp_args_placeholder)) },
            supportingText = { Text(stringResource(R.string.mcp_args_supporting)) },
            minLines = 2,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = if (state.fallback) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.m))
        if (state.fallback) {
            NoteText(stringResource(R.string.mcp_fallback_note))
        } else {
            EnvSection(state, enabled, actions)
        }
    } else {
        OutlinedTextField(
            value = state.url,
            onValueChange = actions.onUrl,
            enabled = enabled,
            label = { Text(stringResource(R.string.mcp_url)) },
            placeholder = { Text(stringResource(R.string.mcp_url_placeholder)) },
            singleLine = true,
            isError = state.fieldError == McpField.Url,
            supportingText = if (state.fieldError == McpField.Url && errorText != null) ({ Text(errorText) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().focusRequester(urlFocus).errorSemantics(state.fieldError == McpField.Url, errorText),
        )
        Spacer(Modifier.height(Spacing.m))
        OutlinedTextField(
            value = state.headersText,
            onValueChange = actions.onHeaders,
            enabled = enabled,
            label = { Text(stringResource(R.string.mcp_headers)) },
            placeholder = { Text(stringResource(R.string.mcp_headers_placeholder)) },
            supportingText = { Text(stringResource(R.string.mcp_headers_supporting)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.m))
        TransportSelect(state.transport, enabled, actions.onTransport)
    }

    Spacer(Modifier.height(Spacing.m))
    AdvancedSection(state, enabled, local, actions)
    Spacer(Modifier.height(Spacing.m))
    EnabledRow(state.enabled, enabled, actions.onEnabled)

    if (state.blockedInFallback) {
        Spacer(Modifier.height(Spacing.m))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.m)) {
                Text(stringResource(R.string.mcp_fallback_blocking_note), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onRetryHelper, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.components_retry)) }
            }
        }
    }
    if (errorText != null) {
        Spacer(Modifier.height(Spacing.m))
        ErrorBox(errorText)
    }
    Spacer(Modifier.height(Spacing.l))
}

private fun Modifier.errorSemantics(isError: Boolean, message: String?): Modifier =
    if (isError && message != null) semantics { error(message) } else this

/** "The server is saved to <path>." with the path in `ClineMono.small`. */
@Composable
private fun PathDescription(state: McpEditorUiState) {
    val path = state.settingsPath ?: stringResource(R.string.mcp_settings_file_fallback)
    val full = stringResource(if (state.isEdit) R.string.mcp_description_edit else R.string.mcp_description_create, path)
    val mono = ClineMono.small
    val annotated: AnnotatedString = remember(full, path, mono) {
        buildAnnotatedString {
            append(full)
            val start = full.indexOf(path)
            if (start >= 0) addStyle(SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize), start, start + path.length)
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TypeCard(type: ServerType, selected: ServerType, enabled: Boolean, titleRes: Int, bodyRes: Int, onSelect: (ServerType) -> Unit) {
    val isSelected = type == selected
    OutlinedCard(
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(MaterialTheme.shapes.medium)
            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(type) }),
    ) {
        Row(Modifier.padding(Spacing.m).heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = isSelected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(Spacing.m))
            Column {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Environment variables: KEY / VALUE rows (password values, reveal toggle), "+" and "−" (CR §2.6). */
@Composable
private fun EnvSection(state: McpEditorUiState, enabled: Boolean, actions: McpEditorActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mcp_env), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = actions.onAddEnv, enabled = enabled) {
            Icon(ClineIcons.Add, contentDescription = stringResource(R.string.mcp_env_add_cd))
        }
    }
    if (state.reenterEnv) NoteText(stringResource(R.string.mcp_env_reenter))
    // CR §2.3: the *window* width (not the gutter-reduced form column) decides the stacking.
    val stacked = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        state.env.forEachIndexed { index, row ->
            EnvRowFields(row, last = index == state.env.lastIndex, stacked = stacked, enabled = enabled, actions = actions)
        }
    }
}

@Composable
private fun EnvRowFields(row: EnvRow, last: Boolean, stacked: Boolean, enabled: Boolean, actions: McpEditorActions) {
    val removeCd = if (row.key.isNotBlank()) stringResource(R.string.mcp_env_remove_cd, row.key) else stringResource(R.string.mcp_env_remove_row_cd)
    val passwordCd = stringResource(R.string.mcp_value_password_cd)
    val key: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = row.key,
            onValueChange = { actions.onEnvKey(row.id, it) },
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.mcp_env_key)) },
            label = { Text(stringResource(R.string.mcp_env_key)) },
            singleLine = true,
            textStyle = ClineMono.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = modifier,
        )
    }
    val value: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = row.value,
            onValueChange = { actions.onEnvValue(row.id, it) },
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.mcp_env_value)) },
            label = { Text(stringResource(R.string.mcp_env_value)) },
            singleLine = true,
            textStyle = ClineMono.body,
            visualTransformation = if (row.revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            trailingIcon = {
                IconButton(onClick = { actions.onToggleReveal(row.id) }) {
                    Icon(
                        if (row.revealed) ClineIcons.VisibilityOff else ClineIcons.Visibility,
                        contentDescription = stringResource(if (row.revealed) R.string.mcp_env_hide_value else R.string.mcp_env_show_value),
                    )
                }
            },
            modifier = modifier.then(if (!row.revealed) Modifier.semantics { contentDescription = passwordCd } else Modifier),
        )
    }
    val remove: @Composable () -> Unit = {
        IconButton(onClick = { actions.onRemoveEnv(row.id) }, enabled = enabled) {
            Icon(ClineIcons.Remove, contentDescription = removeCd)
        }
    }
    if (stacked) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                key(Modifier.weight(1f))
                remove()
            }
            value(Modifier.fillMaxWidth())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            remove()
            key(Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.s))
            value(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportSelect(selected: RemoteTransport, enabled: Boolean, onSelect: (RemoteTransport) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label: @Composable (RemoteTransport) -> String = {
        stringResource(if (it == RemoteTransport.Sse) R.string.mcp_transport_sse else R.string.mcp_transport_http)
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = label(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.mcp_transport)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RemoteTransport.entries.forEach { transport ->
                DropdownMenuItem(
                    text = { Text(label(transport)) },
                    onClick = {
                        onSelect(transport)
                        open = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** "Advanced": Working directory (local, helper route) and the metadata note (CR §2.4, §2.5). */
@Composable
private fun AdvancedSection(state: McpEditorUiState, enabled: Boolean, local: Boolean, actions: McpEditorActions) {
    val expandedText = stringResource(dev.cline.termux.R.string.customize_expanded)
    val collapsedText = stringResource(dev.cline.termux.R.string.customize_collapsed)
    TextButton(
        onClick = { actions.onAdvanced(!state.advancedOpen) },
        modifier = Modifier.semantics { stateDescription = if (state.advancedOpen) expandedText else collapsedText },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
    ) {
        DisclosureChevron(expanded = state.advancedOpen, size = DisclosureChevronDefaults.IconButtonSize)
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(R.string.mcp_advanced))
    }
    AnimatedVisibility(state.advancedOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (local && !state.fallback) {
                OutlinedTextField(
                    value = state.cwd,
                    onValueChange = actions.onCwd,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.mcp_cwd)) },
                    placeholder = { Text(stringResource(R.string.mcp_cwd_placeholder)) },
                    singleLine = true,
                    textStyle = ClineMono.body,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.metadataKeys.isNotEmpty()) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.m)) {
                        Text(stringResource(R.string.mcp_metadata_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            stringResource(R.string.mcp_metadata_text, state.metadataKeys.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnabledRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cd = stringResource(R.string.mcp_enabled_cd)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.m).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mcp_enabled), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.mcp_enabled_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ClineSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = cd })
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.xs))
}

/** The red form error box (desktop), a polite live region. */
@Composable
private fun ErrorBox(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(ClineIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The shared SP §0.3 unavailable card with the editor's fallback wording (CR §2.5 "Fallback
 * card"). A RUN_COMMAND block (SP §0.5) has no card here: the CLI fallback cannot run either,
 * and the kernel banner above shows the block.
 */
@Composable
private fun FallbackCard(helper: HelperState.Unavailable?, onRetry: () -> Unit) {
    val model = helper?.toUnavailableModel() ?: return
    val kernelUi = LocalShellKernelUi.current
    HelperUnavailableCard(
        model = model,
        modifier = Modifier.padding(bottom = Spacing.m),
        body = stringResource(R.string.mcp_fallback_card_body),
        onRetry = onRetry,
        onStartKernel = kernelUi?.let { ui -> { ui.onAction(KernelAction.StartKernel) } },
    )
}

@Composable
private fun McpFormError.text(): String = when (this) {
    is McpFormError.Validation -> stringResource(
        when (error) {
            McpInputError.NameRequired -> R.string.mcp_error_name_required
            McpInputError.CommandRequired -> R.string.mcp_error_command_required
            McpInputError.UrlRequired -> R.string.mcp_error_url_required
        },
    )
    is McpFormError.Collision -> stringResource(R.string.mcp_error_collision, name)
    is McpFormError.Failure -> failure.text()
    McpFormError.Unconfirmed -> stringResource(R.string.mcp_error_unconfirmed)
    McpFormError.NotFound -> stringResource(R.string.mcp_error_not_found)
}

// ---- previews --------------------------------------------------------------------------------------

private val previewPath = "/data/data/com.termux/files/home/.cline/data/settings/cline_mcp_settings.json"

@LightDarkPreviews
@Composable
private fun McpEditorCreatePreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(mode = McpEditorMode.Create, settingsPath = previewPath, env = listOf(EnvRow(1, "", "")), route = McpRoute.Helper, helper = HelperState.Ready),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorValidationPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(
                mode = McpEditorMode.Create, settingsPath = previewPath, env = listOf(EnvRow(1, "GITHUB_TOKEN", "x")), dirty = true,
                fieldError = McpField.Name, formError = McpFormError.Validation(McpInputError.NameRequired), route = McpRoute.Helper, helper = HelperState.Ready,
            ),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorEditMetadataPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(
                mode = McpEditorMode.Edit("github"), name = "github", command = "npx", argsText = "-y, @modelcontextprotocol/server-github",
                env = listOf(EnvRow(1, "GITHUB_TOKEN", "secret")), advancedOpen = true, metadataKeys = listOf("autoApprove", "timeout"),
                settingsPath = previewPath, route = McpRoute.Helper, helper = HelperState.Ready,
            ),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorRemoteSavingPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(
                mode = McpEditorMode.Create, name = "docs", type = ServerType.Remote, url = "https://example.com/mcp", saving = true,
                settingsPath = previewPath, route = McpRoute.Helper, helper = HelperState.Ready,
            ),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorFallbackPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(
                mode = McpEditorMode.Edit("github"), name = "github", command = "npx", blockedInFallback = true,
                route = McpRoute.Fallback, helper = HelperState.Unavailable(dev.cline.termux.core.kernel.dashboard.HelperFailure.StartTimeout, "timed out"),
            ),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorHelperStartingPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(mode = McpEditorMode.Create, env = listOf(EnvRow(1, "", "")), route = McpRoute.Waiting, helper = HelperState.Starting(0L)),
            asDialog = false, actions = McpEditorActions.None, onClose = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorDialogPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(
            McpEditorUiState(mode = McpEditorMode.Edit("github"), name = "github", command = "npx", env = listOf(EnvRow(1, "", "")), settingsPath = previewPath, route = McpRoute.Helper, helper = HelperState.Ready),
            asDialog = true, actions = McpEditorActions.None, onClose = {},
        )
    }
}

private val previewEditState = McpEditorUiState(
    mode = McpEditorMode.Edit("github"), name = "github", command = "npx", argsText = "-y, @modelcontextprotocol/server-github",
    env = listOf(EnvRow(1, "GITHUB_TOKEN", "secret")), settingsPath = previewPath, route = McpRoute.Helper, helper = HelperState.Ready,
)

@LightDarkPreviews
@Composable
private fun McpEditorKernelDownPreview() {
    val gate = KernelGate.Down(KernelGate.DownVariant.Disconnected, null)
    ComponentPreview(padding = false) {
        // The shell provides the gate for the KernelBanner; the preview stands in for it.
        CompositionLocalProvider(LocalShellKernelUi provides ShellKernelUi(gate, null, null) {}) {
            McpServerEditorContent(previewEditState.copy(dirty = true, gate = gate), asDialog = false, actions = McpEditorActions.None, onClose = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorDeletingPreview() {
    ComponentPreview(padding = false) {
        val state = previewEditState.copy(deleteDialog = true, deleting = true)
        McpServerEditorContent(state, asDialog = false, actions = McpEditorActions.None, onClose = {})
        McpDeleteDialog(state, onConfirm = {}, onDismiss = {})
    }
}

@LightDarkPreviews
@Composable
private fun McpEditorDiscardPreview() {
    ComponentPreview(padding = false) {
        McpServerEditorContent(previewEditState.copy(dirty = true), asDialog = false, actions = McpEditorActions.None, onClose = {})
        McpDiscardDialog(onConfirm = {}, onDismiss = {})
    }
}
