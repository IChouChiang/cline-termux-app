package dev.cline.termux.ui.providers

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.account.signInFailureText
import dev.cline.termux.ui.components.ChevronTurn
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.PanePageAppBar
import dev.cline.termux.ui.components.panePageScrollBehavior
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.SecureWindowEffect
import dev.cline.termux.ui.theme.Spacing

/*
 * S5 Provider detail (settings-providers.md S5; desktop `ProviderDetailContent`). The stateful
 * [ProviderDetailRoute] owns the field drafts (only fields the user touched, in `remember`
 * state, secrets included — never in the VM's UiState or SavedStateHandle) and hands them to
 * `commitPendingDrafts` when the detail leaves composition (S5.2). [ProviderDetailContent] is
 * stateless for previews.
 */

/** Everything the detail's buttons do. */
class ProviderDetailActions(
    val fields: FieldCallbacks,
    val models: ModelsCallbacks,
    val onSignIn: () -> Unit,
    val onCancelSignIn: () -> Unit,
    val onOpenTermux: () -> Unit,
    val onDismissSignInError: () -> Unit,
    val onToggleManualKey: () -> Unit,
    val onConnect: () -> Unit,
    val onRequestDisconnect: () -> Unit,
    val onDismissDisconnect: () -> Unit,
    val onConfirmDisconnect: () -> Unit,
    val onSaveFallback: (apiKey: String, modelId: String, baseUrl: String?) -> Unit,
    val onCopySecretValue: (label: String, secret: String) -> Unit,
    val onFallbackReveal: (Boolean) -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
    val blockHandler: KernelBlockHandler,
)

/**
 * The detail wired to [vm]. [pane] = the expanded list-detail scaffold's detail pane (small
 * top bar, no back arrow); otherwise a full page with [navigationIcon].
 */
@Composable
fun ProviderDetailRoute(
    vm: ProvidersViewModel,
    providerId: String,
    pane: Boolean,
    navigationIcon: (@Composable () -> Unit)?,
    copySecret: (label: String, secret: String) -> Unit,
    blockHandler: KernelBlockHandler,
    gutter: Dp,
    modifier: Modifier = Modifier,
) {
    val state by remember(vm, providerId) { vm.detail(providerId) }.collectAsStateWithLifecycle()
    val drafts = remember(providerId, state.resetKey) { mutableStateMapOf<String, String>() }
    val shown = remember(providerId, state.resetKey) { mutableStateListOf<String>() }
    val fallbackRevealed = remember(providerId) { mutableStateListOf<Boolean>() }
    val currentDrafts by rememberUpdatedState(drafts)

    DisposableEffect(vm, providerId) {
        vm.openDetail(providerId)
        onDispose {
            vm.commitPendingDrafts(providerId, currentDrafts.toMap())
            vm.closeDetail(providerId)
        }
    }

    val secretFieldShown = state.fields.any { it.secret } || state.fallbackForm != null
    SecureWindowEffect(revealed = shown.isNotEmpty() || fallbackRevealed.isNotEmpty(), keyFieldShown = secretFieldShown)

    val openUrl = rememberOpenExternal()
    val copier = rememberPlainCopier()
    val actions = remember(vm, providerId, drafts, shown, blockHandler) {
        ProviderDetailActions(
            fields = FieldCallbacks(
                onDraft = { path, text -> drafts[path] = text },
                onCommit = { path -> drafts[path]?.let { vm.commitField(providerId, path, it) } },
                onSelect = { path, value -> vm.commitSelect(providerId, path, value) },
                onBoolean = { path, checked -> vm.commitBoolean(providerId, path, checked) },
                onToggleShown = { path -> if (path in shown) shown.remove(path) else shown.add(path) },
                onCopySecret = { label, path ->
                    val value = drafts[path] ?: vm.storedSecret(providerId, path)
                    if (value.isNotEmpty()) copySecret(label, value)
                },
                onOpenUrl = openUrl,
            ),
            models = ModelsCallbacks(
                onRefresh = { vm.refreshModels(providerId) },
                onQuery = { vm.setModelQuery(providerId, it) },
                onToggleFavorite = { vm.toggleFavorite(providerId, it) },
                onCopy = { modelId ->
                    copier.copy(modelId, modelId)
                    vm.markCopied(providerId, modelId)
                },
            ),
            onSignIn = { vm.startSignIn(providerId) },
            onCancelSignIn = vm::cancelSignIn,
            onOpenTermux = vm::openTermux,
            onDismissSignInError = vm::dismissSignInError,
            onToggleManualKey = { vm.toggleManualKey(providerId) },
            onConnect = { vm.connect(providerId) },
            onRequestDisconnect = { vm.requestDisconnect(providerId) },
            onDismissDisconnect = vm::dismissDisconnect,
            onConfirmDisconnect = { vm.confirmDisconnect(providerId) },
            onSaveFallback = { key, model, url -> vm.saveFallback(providerId, key, model, url) },
            onCopySecretValue = copySecret,
            onFallbackReveal = { revealed -> fallbackRevealed.clear(); if (revealed) fallbackRevealed.add(true) },
            onRetryHelper = vm::retryHelper,
            onStartKernel = vm::startKernel,
            blockHandler = blockHandler,
        )
    }
    ProviderDetailContent(
        state = state,
        fieldText = { field ->
            drafts[field.path] ?: if (field.secret || field.path == ProviderListItem.API_KEY_PATH) {
                vm.storedSecret(providerId, field.path)
            } else {
                field.storedText
            }
        },
        shown = { it in shown },
        actions = actions,
        pane = pane,
        navigationIcon = navigationIcon,
        gutter = gutter,
        modifier = modifier,
    )
}

/**
 * The S5 page or pane. [fieldText] = the draft or the stored value; [shown] = a revealed secret.
 *
 * Compact/medium: one `LazyColumn` (status, connection, fields, then the models card drawn
 * per item). Expanded pane: the same top blocks in a `Column` bounded to half the pane
 * (`verticalScroll`, desktop `max-h-1/2`) above the models card in its own `LazyColumn`
 * (`weight(1f)`, desktop `flex-1`): two sibling scroll containers, never nested (S5.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailContent(
    state: ProviderDetailUiState,
    fieldText: (FieldUi) -> String,
    shown: (String) -> Boolean,
    actions: ProviderDetailActions,
    pane: Boolean,
    navigationIcon: (@Composable () -> Unit)?,
    gutter: Dp,
    modifier: Modifier = Modifier,
) {
    val disconnectAction: @Composable () -> Unit = {
        if (state.canDisconnect) {
            TextButton(
                onClick = actions.onRequestDisconnect,
                enabled = !state.disconnecting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (state.signOutAction) R.string.provider_sign_out else R.string.provider_disconnect))
            }
        }
    }
    // The pending seed draws the app bar and progress only, so no guessed section flashes.
    val blocks = if (state.pending) emptyList() else detailBlocks(state, fieldText, shown, actions, gutter)
    val showModels = state.blocked == null && !state.pending
    if (pane) {
        val scrollBehavior = panePageScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                PanePageAppBar(
                    title = state.name,
                    scrollBehavior = scrollBehavior,
                    actions = { disconnectAction() },
                    loading = state.pending,
                )
            },
        ) { padding ->
            // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
            val direction = LocalLayoutDirection.current
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                ),
            ) {
                val topMax = maxHeight / 2
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = topMax)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (block in blocks) key(block.key) { block.content() }
                    }
                    if (showModels) {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + Spacing.xxl),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            modelsCard(state.models, actions.models, gutter)
                        }
                    }
                }
            }
        }
    } else {
        PageScaffold(
            title = state.name,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = { disconnectAction() },
            loading = state.loading,
        ) { padding ->
            val direction = LocalLayoutDirection.current
            LazyColumn(
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                    bottom = padding.calculateBottomPadding() + Spacing.xxl,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                for (block in blocks) item(key = block.key) { block.content() }
                if (showModels) modelsCard(state.models, actions.models, gutter)
            }
        }
    }
    if (state.confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(if (state.signOutAction) R.string.provider_sign_out_title else R.string.provider_disconnect_title, state.name),
            confirmLabel = stringResource(if (state.signOutAction) R.string.provider_sign_out else R.string.provider_disconnect),
            onConfirm = actions.onConfirmDisconnect,
            onDismiss = actions.onDismissDisconnect,
            text = stringResource(R.string.provider_disconnect_text, state.name),
            destructive = true,
            confirming = state.disconnecting,
        )
    }
}

/** One keyed block above the models card: a lazy item on compact/medium, a Column child in the pane. */
private class DetailBlock(val key: String, val content: @Composable () -> Unit)

/**
 * The blocks above the models card, by the desktop `connectionSection` branches: OAuth →
 * its three variants (only the `apiKey` row, never the other config fields); local → the
 * "Uses your local CLI sign-in" card with Connect inside it; api-key → every field row plus
 * the not-connected Connect footer. In fallback mode (§0.4) the OAuth variants stay (S7 needs
 * only Termux) with the `cline auth` form as their key row, and an api-key provider shows the
 * fallback form alone.
 */
private fun detailBlocks(
    state: ProviderDetailUiState,
    fieldText: (FieldUi) -> String,
    shown: (String) -> Boolean,
    actions: ProviderDetailActions,
    gutter: Dp,
): List<DetailBlock> {
    val blocks = ArrayList<DetailBlock>()
    blocks += DetailBlock("banner") { Column(Modifier.padding(horizontal = gutter)) { ShellKernelBanner(hasComposer = false) } }
    blocks += DetailBlock("status") { StatusHeader(state, gutter) }
    if (state.autoSaveNote) {
        blocks += DetailBlock("autosave") {
            Text(
                stringResource(R.string.provider_autosave_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = gutter).padding(bottom = Spacing.xs),
            )
        }
    }
    if (state.blocked != null) {
        blocks += DetailBlock("blocked") {
            KernelBlockedCard(
                block = state.blocked,
                onAction = { actions.blockHandler.onAction(state.blocked, it) },
                permissionPermanentlyDenied = actions.blockHandler.permissionPermanentlyDenied,
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
            )
        }
        return blocks
    }
    state.helperCard?.let { card ->
        blocks += DetailBlock("helper") {
            HelperUnavailableCard(
                model = card,
                onRetry = actions.onRetryHelper,
                onStartKernel = actions.onStartKernel,
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
            )
        }
    }
    when (state.connection) {
        ConnectionVariant.OAuthSignedIn, ConnectionVariant.OAuthWithApiKey, ConnectionVariant.OAuthSignIn -> {
            blocks += DetailBlock("connection") { OAuthSection(state, fieldText, shown, actions, gutter) }
        }
        ConnectionVariant.Local -> {
            blocks += DetailBlock("connection") { LocalSection(state, actions.onConnect, gutter) }
        }
        ConnectionVariant.ApiKey -> if (state.fallback) {
            state.fallbackForm?.let { form ->
                blocks += DetailBlock("fallback-form") {
                    FallbackAuthForm(
                        form = form,
                        save = state.fallbackSave,
                        onSave = actions.onSaveFallback,
                        onCopySecret = actions.onCopySecretValue,
                        onRevealChange = actions.onFallbackReveal,
                        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
                    )
                }
            }
        } else {
            for (field in state.fields) {
                blocks += DetailBlock("field-${field.path}") {
                    ProviderFieldRow(
                        field = field,
                        providerName = state.name,
                        text = fieldText(field),
                        shown = shown(field.path),
                        enabled = !state.disconnecting,
                        callbacks = actions.fields,
                        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs),
                    )
                }
            }
            if (state.showConnect) {
                blocks += DetailBlock("connect") { ConnectFooter(state.connecting, actions.onConnect, gutter) }
            }
        }
    }
    return blocks
}

@Composable
private fun StatusHeader(state: ProviderDetailUiState, gutter: Dp) {
    val status = stringResource(if (state.connected) R.string.provider_status_configured else R.string.provider_status_not_configured)
    val description = stringResource(R.string.provider_title_cd, state.name, status)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = Spacing.s)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Desktop status pill: rounded-full, 1px border, muted text (S5.1).
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xxs),
            )
        }
    }
}

/**
 * The OAuth `connectionSection`: signed in via browser → configured with an API key (the
 * `apiKey` row) → the "Sign in to <name>" card with its "Use an API key instead" expander.
 * The key row is the auto-saving `apiKey` field with the helper, the `cline auth` form in
 * fallback mode.
 */
@Composable
private fun OAuthSection(
    state: ProviderDetailUiState,
    fieldText: (FieldUi) -> String,
    shown: (String) -> Boolean,
    actions: ProviderDetailActions,
    gutter: Dp,
) {
    val keyRow: (@Composable () -> Unit)? = if (state.fallback) {
        state.fallbackForm?.let { form ->
            {
                FallbackAuthForm(
                    form = form,
                    save = state.fallbackSave,
                    onSave = actions.onSaveFallback,
                    onCopySecret = actions.onCopySecretValue,
                    onRevealChange = actions.onFallbackReveal,
                )
            }
        }
    } else {
        state.apiKeyField?.let { field ->
            {
                ProviderFieldRow(
                    field = field,
                    providerName = state.name,
                    text = fieldText(field),
                    shown = shown(field.path),
                    enabled = !state.disconnecting,
                    callbacks = actions.fields,
                )
            }
        }
    }
    Column(Modifier.padding(horizontal = gutter, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        when (state.connection) {
            ConnectionVariant.OAuthSignedIn -> InfoCard(
                icon = ClineIcons.CheckCircle,
                title = stringResource(R.string.provider_signed_in_title),
                body = stringResource(R.string.provider_signed_in_body),
            )
            ConnectionVariant.OAuthWithApiKey -> {
                Text(
                    stringResource(R.string.provider_configured_with_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                keyRow?.invoke()
            }
            else -> SignInCard(state, actions, keyRow)
        }
    }
}

@Composable
private fun SignInCard(state: ProviderDetailUiState, actions: ProviderDetailActions, keyRow: (@Composable () -> Unit)?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.provider_sign_in_title, state.name),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.provider_sign_in_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val signIn = state.signIn) {
                DetailSignIn.Idle -> if (state.canSignIn) {
                    Button(onClick = actions.onSignIn) {
                        Icon(ClineIcons.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.provider_sign_in_browser))
                    }
                }
                DetailSignIn.Waiting -> {
                    val announcement = stringResource(R.string.signin_waiting_a11y)
                    Button(onClick = {}, enabled = false) {
                        InlineSpinner()
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.provider_waiting_browser))
                    }
                    Text(
                        stringResource(R.string.provider_waiting_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = announcement
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        TextButton(onClick = actions.onOpenTermux) {
                            Icon(ClineIcons.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.provider_open_termux))
                        }
                        TextButton(onClick = actions.onCancelSignIn) { Text(stringResource(R.string.components_cancel)) }
                    }
                }
                is DetailSignIn.Failed -> {
                    InlineErrorCard(
                        message = signInFailureText(signIn.failure, SignInState.Failed.Kind.SignIn),
                        actionLabel = stringResource(R.string.components_retry),
                        onAction = {
                            actions.onDismissSignInError()
                            actions.onSignIn()
                        },
                    )
                }
                is DetailSignIn.Blocked -> KernelBlockedCard(
                    block = signIn.block,
                    onAction = { action ->
                        actions.onDismissSignInError()
                        actions.blockHandler.onAction(signIn.block, action)
                    },
                    permissionPermanentlyDenied = actions.blockHandler.permissionPermanentlyDenied,
                )
            }
            if (keyRow != null) {
                if (state.canSignIn) {
                    val expandedState = stringResource(if (state.manualKeyExpanded) R.string.providers_state_expanded else R.string.providers_state_collapsed)
                    TextButton(
                        onClick = actions.onToggleManualKey,
                        modifier = Modifier.semantics { stateDescription = expandedState },
                    ) {
                        Text(stringResource(R.string.provider_use_api_key))
                        DisclosureChevron(
                            expanded = state.manualKeyExpanded,
                            modifier = Modifier.padding(start = Spacing.xs),
                            size = ButtonDefaults.IconSize,
                            turn = ChevronTurn.DownToUp,
                        )
                    }
                    if (state.manualKeyExpanded) keyRow()
                } else {
                    keyRow()
                }
            }
        }
    }
}

/** The local-auth `connectionSection`: the card, with "Connect" inside it while not connected (never in fallback). */
@Composable
private fun LocalSection(state: ProviderDetailUiState, onConnect: () -> Unit, gutter: Dp) {
    Column(Modifier.padding(horizontal = gutter, vertical = Spacing.s)) {
        InfoCard(
            icon = ClineIcons.Devices,
            title = stringResource(R.string.provider_local_title),
            body = stringResource(R.string.provider_local_body),
            trailing = if (state.showConnect) {
                {
                    Button(onClick = onConnect, enabled = !state.connecting) {
                        if (state.connecting) {
                            InlineSpinner(Modifier.padding(end = Spacing.s))
                        }
                        Text(stringResource(R.string.provider_connect))
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, trailing: (@Composable () -> Unit)? = null) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Spacing.xxs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.m))
                trailing()
            }
        }
    }
}

@Composable
private fun ConnectFooter(connecting: Boolean, onConnect: () -> Unit, gutter: Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.provider_connect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onConnect, enabled = !connecting) {
            if (connecting) {
                InlineSpinner(Modifier.padding(end = Spacing.s))
            }
            Text(stringResource(R.string.provider_connect))
        }
    }
}
