package dev.cline.termux.ui.account

import android.icu.text.MeasureFormat
import android.icu.util.Currency
import android.icu.util.Measure
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.OverflowMenuButton
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.Skeleton
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.components.toUiText
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the stateless [AccountScreen] can ask for. */
class AccountActions(
    /** Null in the expanded Settings detail pane (no back arrow there, SP §0.2). */
    val onBack: (() -> Unit)? = {},
    val onSelectTab: (AccountTab) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRetryHelper: () -> Unit = {},
    val onStartKernel: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSwitch: (AccountRow) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onCancelSignIn: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onBlockAction: (KernelBlock, KernelBlockAction) -> Unit = { _, _ -> },
)

/** The `account?tab=` destination: ViewModel, snackbars and navigation around [AccountScreen]. */
@Composable
fun AccountRoute(navigator: ShellNavigator, graph: AppGraph, inPane: Boolean = false) {
    val viewModel: AccountViewModel = viewModel(factory = AccountViewModel.factory(graph))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val resources = LocalResources.current
    val openUrl = rememberOpenExternal()
    val kernelUi = LocalShellKernelUi.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                AccountEvent.SignedOut -> resources.getString(R.string.account_signed_out_snackbar)
                is AccountEvent.Switched -> resources.getString(
                    R.string.account_switched_to,
                    event.name ?: resources.getString(R.string.account_personal),
                )
                is AccountEvent.Failed -> event.error.text(resources)
            }
            launch { snackbar.showReplacing(text) }
        }
    }
    AccountScreen(
        state = state,
        actions = AccountActions(
            onBack = if (inPane) null else ({ navigator.back() }),
            onSelectTab = viewModel::selectTab,
            onRetry = viewModel::retry,
            onRetryHelper = viewModel::retryHelper,
            onStartKernel = {
                kernelUi?.onAction?.invoke(KernelAction.StartKernel)
                viewModel.retryHelper()
            },
            onRefresh = viewModel::refresh,
            onSwitch = viewModel::switchAccount,
            onSignOut = viewModel::signOut,
            onSignIn = viewModel::startSignIn,
            onOpenTermux = { viewModel.openTermux() },
            onCancelSignIn = viewModel::cancelSignIn,
            onOpenUrl = openUrl,
            onBlockAction = { block, action ->
                viewModel.dismissSignInProblem()
                navigator.onKernelBlockAction(block, action) { viewModel.retryHelper() }
            },
        ),
    )
}

/** C8 Account (customize-and-rest.md §5.3–§5.6, §5.10), stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(state: AccountUiState, actions: AccountActions, modifier: Modifier = Modifier) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val signOutLabel = stringResource(R.string.account_sign_out)
    val signingOutLabel = stringResource(R.string.account_signing_out)
    PageScaffold(
        title = stringResource(R.string.account_title),
        modifier = modifier,
        navigationIcon = actions.onBack?.let { back -> { BackNavIcon(back) } },
        actions = {
            if (state.showTabs) {
                OverflowMenuButton(
                    contentDescription = stringResource(R.string.account_more_options),
                    icon = ClineIcons.MoreVert,
                    enabled = !state.busy,
                    pendingItemId = if (state.signingOut) SIGN_OUT_ID else null,
                    items = listOf(
                        ContextMenuItem(
                            id = SIGN_OUT_ID,
                            label = signOutLabel,
                            onClick = { confirmSignOut = true },
                            icon = ClineIcons.Logout,
                            destructive = true,
                            pendingLabel = signingOutLabel,
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        val layout = LocalShellLayout.current
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth()) {
                AccountBody(state, actions, onSignOut = { confirmSignOut = true }, gutter = layout.gutter, expanded = layout.width == WidthClass.Expanded)
            }
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.account_sign_out_confirm_title),
            text = stringResource(R.string.account_sign_out_confirm_text),
            confirmLabel = stringResource(R.string.account_sign_out_confirm_button),
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                actions.onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBody(
    state: AccountUiState,
    actions: AccountActions,
    onSignOut: () -> Unit,
    gutter: androidx.compose.ui.unit.Dp,
    expanded: Boolean,
) {
    val block = state.blockedFailure?.toKernelBlock() ?: state.kernel.blockOrNull()?.takeIf { state.helper !is HelperState.Ready }
    val unavailable = state.helperUnavailable
    when {
        block != null -> Column(Modifier.padding(horizontal = gutter, vertical = Spacing.l)) {
            KernelBlockedCard(block, onAction = { actions.onBlockAction(block, it) })
        }
        unavailable != null -> ReducedPage(state, unavailable, actions, gutter)
        state.signedIn == SignedIn.No -> CenteredScroll(state.refreshing, actions.onRefresh, gutter) {
            SignedOutCard(state.signIn, actions)
        }
        state.signedIn == SignedIn.Yes -> SignedInPages(state, actions, onSignOut, gutter, expanded)
        else -> {
            val overview = state.overview
            if (overview is Loadable.Error) {
                CenteredScroll(state.refreshing, actions.onRefresh, gutter) { ErrorBlock(overview.error, actions.onRetry) }
            } else {
                LoadingState(starting = state.helper is HelperState.Starting || state.helper == HelperState.Idle, gutter = gutter)
            }
        }
    }
}

/** First open / loading (§5.4): the helper line while it starts, then progress + 2 skeleton cards. */
@Composable
private fun LoadingState(starting: Boolean, gutter: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = gutter, vertical = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            if (starting) {
                Text(
                    stringResource(R.string.account_helper_starting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            repeat(2) { Skeleton(Modifier.fillMaxWidth().height(120.dp), shape = MaterialTheme.shapes.medium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CenteredScroll(refreshing: Boolean, onRefresh: () -> Unit, gutter: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter, vertical = Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
            item { content() }
        }
    }
}

// ---- signed in: tabs -------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInPages(
    state: AccountUiState,
    actions: AccountActions,
    onSignOut: () -> Unit,
    gutter: androidx.compose.ui.unit.Dp,
    expanded: Boolean,
) {
    val tabs = AccountTab.entries
    val pager = rememberPagerState(initialPage = state.tab.ordinal) { tabs.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.tab) { if (pager.currentPage != state.tab.ordinal) pager.animateScrollToPage(state.tab.ordinal) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page -> actions.onSelectTab(tabs[page]) }
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pager.currentPage) {
            tabs.forEach { tab ->
                Tab(
                    selected = pager.currentPage == tab.ordinal,
                    onClick = {
                        actions.onSelectTab(tab)
                        scope.launch { pager.animateScrollToPage(tab.ordinal) }
                    },
                    enabled = !state.busy,
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(tab.label())) },
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !state.busy) { page ->
            PullToRefreshBox(isRefreshing = state.refreshing && state.tab.ordinal == page, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = gutter, vertical = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    when (tabs[page]) {
                        AccountTab.Overview -> item { OverviewTab(state, actions, onSignOut, expanded) }
                        AccountTab.Usage -> item { UsageTab(state, actions) }
                        AccountTab.Billing -> item { BillingTab(state, actions) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(state: AccountUiState, actions: AccountActions, onSignOut: () -> Unit, expanded: Boolean) {
    when (val overview = state.overview) {
        is Loadable.Ready -> OverviewContent(overview.value, state, actions, onSignOut, expanded)
        is Loadable.Error -> ErrorBlock(overview.error, actions.onRetry)
        else -> TabLoading()
    }
}

@Composable
private fun OverviewContent(
    overview: AccountOverviewUi,
    state: AccountUiState,
    actions: AccountActions,
    onSignOut: () -> Unit,
    expanded: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        if (expanded && overview.balance != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                ProfileCard(overview, actions, Modifier.weight(1f))
                BalanceCard(overview, actions, Modifier.weight(1f))
            }
        } else {
            ProfileCard(overview, actions, Modifier.fillMaxWidth())
            if (overview.balance != null) BalanceCard(overview, actions, Modifier.fillMaxWidth())
        }
        OrganizationsSection(overview, state, actions)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SignOutButton(signingOut = state.signingOut, enabled = !state.busy && state.switchingTo == null, onClick = onSignOut)
        }
    }
}

@Composable
private fun ProfileCard(overview: AccountOverviewUi, actions: AccountActions, modifier: Modifier) {
    OutlinedCard(modifier) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    overview.initial.uppercase(LocalConfiguration.current.locales[0]),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(overview.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (overview.email.isNotEmpty()) {
                    Text(overview.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                overview.memberSince?.let {
                    Spacer(Modifier.height(Spacing.s))
                    Text(stringResource(R.string.account_member_since, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val dashboard = stringResource(R.string.account_open_dashboard)
            TooltipIconButton(
                icon = ClineIcons.OpenInNew,
                contentDescription = stringResource(R.string.account_opens_in_browser, dashboard),
                tooltip = dashboard,
                onClick = { actions.onOpenUrl(AccountUrls.DASHBOARD_URL) },
            )
        }
    }
}

@Composable
private fun BalanceCard(overview: AccountOverviewUi, actions: AccountActions, modifier: Modifier) {
    val amount = stringResource(R.string.account_balance_amount, overview.balance.orEmpty())
    val spoken = overview.balanceCredits?.let { stringResource(R.string.account_balance_a11y, currencyWords(it)) } ?: amount
    OutlinedCard(modifier) {
        Column(Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    overview.organizationName?.let { stringResource(R.string.account_balance_org, it) } ?: stringResource(R.string.account_balance_credits),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val credit = stringResource(R.string.account_credit)
                val creditCd = stringResource(R.string.account_opens_in_browser, credit)
                FilledTonalButton(
                    onClick = { actions.onOpenUrl(if (overview.activeOrganization != null) AccountUrls.ORGANIZATION_CREDITS_URL else AccountUrls.USER_CREDITS_URL) },
                    modifier = Modifier.semantics { contentDescription = creditCd },
                ) {
                    Text(credit)
                    Spacer(Modifier.width(Spacing.s))
                    Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                amount,
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = ClineMono.body.fontFamily),
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            overview.personalCredits?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.account_personal_line, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "12.40 US dollars" for TalkBack (§5.10: `MeasureFormat` currency, not digit by digit). */
private fun currencyWords(credits: Double): String = runCatching {
    MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        .format(Measure(Math.round(credits * 100) / 100.0, Currency.getInstance("USD")))
}.getOrElse { String.format(Locale.US, "%.2f", credits) }

@Composable
private fun OrganizationsSection(overview: AccountOverviewUi, state: AccountUiState, actions: AccountActions) {
    val live = rememberLiveRegionState()
    val switchingName = state.switchingTo?.let { key -> overview.accounts.firstOrNull { it.switchKey == key } }
    val personalLabel = stringResource(R.string.account_personal)
    val switchingText = switchingName?.let { stringResource(R.string.account_switching_to, it.name ?: personalLabel) }
    LaunchedEffect(switchingText) { switchingText?.let(live::announce) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.account_organizations),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val create = stringResource(R.string.account_create)
            val createCd = stringResource(R.string.account_opens_in_browser, create)
            TextButton(onClick = { actions.onOpenUrl(AccountUrls.CREATE_ORGANIZATION_URL) }, modifier = Modifier.semantics { contentDescription = createCd }) {
                Text(create)
                Spacer(Modifier.width(Spacing.xs))
                Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.selectableGroup()) {
                overview.accounts.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccountRowItem(row, state, actions)
                }
            }
        }
        LiveRegionAnnouncer(live)
    }
}

@Composable
private fun AccountRowItem(row: AccountRow, state: AccountUiState, actions: AccountActions) {
    val switching = state.switchingTo == row.switchKey
    val anotherSwitching = state.switchingTo != null && !switching
    val enabled = state.rowEnabled(row)
    val name = row.name ?: stringResource(R.string.account_personal)
    val subtitle = if (row.personal) row.subtitle ?: stringResource(R.string.account_personal_account) else row.subtitle.orEmpty()
    ListItem(
        modifier = Modifier
            .heightIn(min = 72.dp)
            .alpha(if (anotherSwitching && !row.active) 0.6f else 1f)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = row.active, enabled = enabled, role = Role.RadioButton, onClick = { if (!row.active) actions.onSwitch(row) }),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
        leadingContent = {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                if (row.personal) {
                    Icon(ClineIcons.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        AccountFormat.initial(row.name, LocalConfiguration.current.locales[0]),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = {
            when {
                switching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                row.active -> TagBadge(
                    text = stringResource(R.string.account_active),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                else -> Text(stringResource(R.string.account_switch), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun SignOutButton(signingOut: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        if (signingOut) {
            InlineSpinner()
        } else {
            Icon(ClineIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(if (signingOut) R.string.account_signing_out else R.string.account_sign_out))
    }
}

// ---- usage and billing -----------------------------------------------------------------------

@Composable
private fun UsageTab(state: AccountUiState, actions: AccountActions) {
    val org = state.overview.valueOrNull?.organizationName
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(
            org?.let { stringResource(R.string.account_usage_description_org, it) } ?: stringResource(R.string.account_usage_description_all),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val usage = state.usage) {
            is Loadable.Ready -> {
                UsageTable(usage.value)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val seeMore = stringResource(R.string.account_see_more)
                    val cd = stringResource(R.string.account_opens_in_browser, seeMore)
                    OutlinedButton(onClick = { actions.onOpenUrl(AccountUrls.USAGE_DASHBOARD_URL) }, modifier = Modifier.semantics { contentDescription = cd }) {
                        Text(seeMore)
                        Spacer(Modifier.width(Spacing.s))
                        Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            is Loadable.Error -> ErrorBlock(usage.error, actions.onRetry)
            else -> TabLoading()
        }
    }
}

@Composable
private fun UsageTable(rows: List<UsageRow>) {
    val wide = LocalShellLayout.current.width != WidthClass.Compact
    OutlinedCard(Modifier.fillMaxWidth()) {
        if (wide) {
            TableRow(
                cells = listOf(
                    stringResource(R.string.account_col_model),
                    stringResource(R.string.account_col_tokens),
                    stringResource(R.string.account_col_credits),
                    stringResource(R.string.account_col_time),
                ),
                weights = USAGE_WEIGHTS,
                header = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.account_usage_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xxxl),
            )
            return@OutlinedCard
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            UsageRowItem(row, wide)
        }
    }
}

@Composable
private fun UsageRowItem(row: UsageRow, wide: Boolean) {
    val tokens = dev.cline.termux.ui.account.AccountFormat.tokens(row.tokens)
    val date = row.date
    val time = row.time
    val whenText = if (date != null && time != null) stringResource(R.string.account_date_time, date, time) else date ?: time.orEmpty()
    val spokenTokens = copyPluralStringResource(R.plurals.account_tokens_a11y, AccountFormat.pluralQuantity(row.tokens), tokens)
    val spokenCredits = copyPluralStringResource(R.plurals.account_credits_a11y, AccountFormat.creditsPluralQuantity(row.credits), row.credits)
    val spoken = stringResource(R.string.account_usage_row_a11y, row.model, row.provider.orEmpty(), spokenTokens, spokenCredits, whenText)
    val rowModifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }
    if (wide) {
        Row(rowModifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(USAGE_WEIGHTS[0])) {
                Text(row.model, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.provider?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(tokens, style = ClineMono.small, textAlign = TextAlign.End, modifier = Modifier.weight(USAGE_WEIGHTS[1]))
            Text(row.credits, style = ClineMono.small, textAlign = TextAlign.End, modifier = Modifier.weight(USAGE_WEIGHTS[2]))
            Column(Modifier.weight(USAGE_WEIGHTS[3]), horizontalAlignment = Alignment.End) {
                row.date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                row.time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    } else {
        Row(rowModifier.heightIn(min = 72.dp).padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.model, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(row.provider, whenText.ifEmpty { null })
                if (meta.isNotEmpty()) {
                    Text(
                        if (meta.size == 2) stringResource(R.string.account_meta_line, meta[0], meta[1]) else meta[0],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.m))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.account_tokens_short, tokens), style = ClineMono.small)
                Text(row.credits, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BillingTab(state: AccountUiState, actions: AccountActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(stringResource(R.string.account_billing_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val billing = state.billing) {
            is Loadable.Ready -> if (billing.value.isEmpty()) {
                Text(
                    stringResource(R.string.account_billing_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl),
                )
            } else {
                PaymentsTable(billing.value)
            }
            is Loadable.Error -> ErrorBlock(billing.error, actions.onRetry)
            else -> TabLoading()
        }
    }
}

@Composable
private fun PaymentsTable(rows: List<PaymentRow>) {
    val wide = LocalShellLayout.current.width != WidthClass.Compact
    OutlinedCard(Modifier.fillMaxWidth()) {
        if (wide) {
            TableRow(
                cells = listOf(stringResource(R.string.account_col_date), stringResource(R.string.account_col_amount), stringResource(R.string.account_col_credits)),
                weights = BILLING_WEIGHTS,
                header = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val credits = stringResource(R.string.account_credits_plus, row.credits)
            val spokenCredits = copyPluralStringResource(R.plurals.account_credits_a11y, AccountFormat.creditsPluralQuantity(credits), credits)
            val spoken = stringResource(R.string.account_payment_row_a11y, row.date.orEmpty(), row.amount, spokenCredits)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = spoken }.padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(BILLING_WEIGHTS[0]), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ClineIcons.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(row.date.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                Text(row.amount, style = ClineMono.small, textAlign = TextAlign.End, modifier = Modifier.weight(BILLING_WEIGHTS[1]))
                Text(credits, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(BILLING_WEIGHTS[2]))
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, weights: List<Float>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                style = MaterialTheme.typography.labelMedium,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(weights[index]),
            )
        }
    }
}

/** Usage / Billing loading (§5.4): progress + skeleton in that tab only. */
@Composable
private fun TabLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(2) { Skeleton(Modifier.fillMaxWidth().height(96.dp), shape = MaterialTheme.shapes.medium) }
    }
}

/** Desktop `renderError`: 32dp error icon, the message (max 448dp), "Retry". */
@Composable
fun ErrorBlock(error: AccountError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    ErrorBlock(error.text(resources), onRetry, modifier)
}

@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(Spacing.m))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 448.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Spacer(Modifier.height(Spacing.l))
        OutlinedButton(onClick = onRetry) {
            Icon(ClineIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.components_retry))
        }
    }
}

// ---- signed out and sign-in ------------------------------------------------------------------

@Composable
private fun SignedOutCard(signIn: SignInState, actions: AccountActions) {
    OutlinedCard(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
        Column(Modifier.padding(Spacing.xxl)) {
            when (signIn) {
                is SignInState.WaitingForTermux -> WaitingContent(actions)
                is SignInState.Blocked -> {
                    val block = KernelBlock.of(signIn.error) ?: KernelBlock.TimedOut
                    KernelBlockedCard(block, onAction = { actions.onBlockAction(block, it) })
                }
                else -> IdleSignInContent(signIn, actions)
            }
        }
    }
}

@Composable
private fun IdleSignInContent(signIn: SignInState, actions: AccountActions) {
    Icon(ClineIcons.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(Spacing.l))
    Text(stringResource(R.string.account_signed_out_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Spacing.xs))
    Text(stringResource(R.string.account_signed_out_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Spacing.l))
    val signingIn = signIn is SignInState.SavingKey
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = actions.onSignIn, enabled = !signingIn) {
            if (signingIn) {
                InlineSpinner()
                Spacer(Modifier.width(Spacing.s))
            }
            Text(stringResource(if (signingIn) R.string.account_signing_in else R.string.account_sign_in))
        }
        val create = stringResource(R.string.account_create_account)
        val createCd = stringResource(R.string.account_opens_in_browser, create)
        OutlinedButton(onClick = { actions.onOpenUrl(AccountUrls.CREATE_ACCOUNT_URL) }, modifier = Modifier.semantics { contentDescription = createCd }) {
            Text(create)
            Spacer(Modifier.width(Spacing.s))
            Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    if (signIn is SignInState.Failed) {
        Spacer(Modifier.height(Spacing.m))
        val detail = signIn.failure.text()
        Text(
            stringResource(if (signIn.kind == SignInState.Failed.Kind.ApiKey) R.string.account_save_key_failed else R.string.account_sign_in_failed, detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
private fun WaitingContent(actions: AccountActions) {
    val announcement = stringResource(R.string.account_waiting_a11y)
    val live = rememberLiveRegionState()
    LaunchedEffect(Unit) { live.announce(announcement) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.m))
        Text(stringResource(R.string.account_waiting_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
    Spacer(Modifier.height(Spacing.s))
    Text(stringResource(R.string.account_waiting_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Spacing.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FilledTonalButton(onClick = actions.onOpenTermux) { Text(stringResource(R.string.account_open_termux)) }
        TextButton(onClick = actions.onCancelSignIn) { Text(stringResource(R.string.components_cancel)) }
    }
    LiveRegionAnnouncer(live)
}

/** SP S7 failure detail inside "Sign in failed: %1$s" / "Failed to save API key: %1$s". */
@Composable
private fun SignInFailure.text(): String = when (this) {
    is SignInFailure.ExitCode -> stringResource(R.string.account_signin_failure_exit, code)
    is SignInFailure.Termux -> errmsg
    SignInFailure.TimedOut -> stringResource(R.string.account_signin_failure_timeout)
    is SignInFailure.KeyNotVerified -> stringResource(R.string.account_signin_failure_key_not_verified, error)
    is SignInFailure.KeySaveFailed -> message
    SignInFailure.TermuxDidNotAnswer -> stringResource(R.string.account_signin_failure_no_answer)
}

// ---- helper unavailable ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReducedPage(state: AccountUiState, unavailable: HelperState.Unavailable, actions: AccountActions, gutter: androidx.compose.ui.unit.Dp) {
    PullToRefreshBox(isRefreshing = false, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gutter, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            unavailable.toCardModel()?.let { model ->
                item { HelperUnavailableCard(model, onRetry = actions.onRetryHelper, onStartKernel = actions.onStartKernel) }
            }
            item {
                when (val fallback = state.fallbackSignedIn) {
                    is Loadable.Ready -> if (fallback.value) ReducedSignedIn(actions) else SignedOutCard(state.signIn, actions)
                    is Loadable.Error -> ErrorBlock(fallback.error, actions.onRefresh)
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ReducedSignedIn(actions: AccountActions) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
            leadingContent = { Icon(ClineIcons.AccountCircle, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.account_signed_in_reduced)) },
            supportingContent = { Text(stringResource(R.string.account_signed_in_reduced_body)) },
        )
        val dashboard = stringResource(R.string.account_open_dashboard)
        val cd = stringResource(R.string.account_opens_in_browser, dashboard)
        Row(Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { actions.onOpenUrl(AccountUrls.DASHBOARD_URL) }, modifier = Modifier.semantics { contentDescription = cd }) {
                Text(dashboard)
                Spacer(Modifier.width(Spacing.s))
                Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---- helpers ---------------------------------------------------------------------------------

private fun AccountTab.label(): Int = when (this) {
    AccountTab.Overview -> R.string.account_tab_overview
    AccountTab.Usage -> R.string.account_tab_usage
    AccountTab.Billing -> R.string.account_tab_billing
}

/** An [AccountError] as screen copy (§5.4 "Older helper", §0.5 failures). */
fun AccountError.text(resources: android.content.res.Resources): String = when (this) {
    AccountError.OlderHelper -> resources.getString(R.string.account_older_helper)
    is AccountError.Failure -> failure.toUiText().resolve(resources)
}

private const val SIGN_OUT_ID = "sign_out"
private val USAGE_WEIGHTS = listOf(3f, 1.2f, 1f, 1.2f)
private val BILLING_WEIGHTS = listOf(2f, 1f, 1f)

// ---- previews --------------------------------------------------------------------------------

private val previewOverview = AccountOverviewUi(
    name = "Sam Lee",
    email = "sam@example.com",
    initial = "S",
    memberSince = "Mar 4, 2025",
    organizationName = null,
    balance = "12.40",
    balanceCredits = 12.4,
    personalCredits = null,
    accounts = listOf(
        AccountRow(null, null, "sam@example.com", active = true),
        AccountRow("o1", "Acme", "Admin, Member", active = false),
    ),
    activeOrganization = null,
)

private fun previewState(
    signedIn: SignedIn = SignedIn.Yes,
    tab: AccountTab = AccountTab.Overview,
    helper: HelperState = HelperState.Ready,
) = AccountUiState(
    tab = tab,
    helper = helper,
    signedIn = signedIn,
    overview = if (signedIn == SignedIn.Yes) Loadable.Ready(previewOverview) else Loadable.Idle,
    kernel = KernelGate.Ready,
)

@LightDarkPreviews
@Composable
private fun AccountPopulatedPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountOrganizationPreview() {
    val org = previewOverview.copy(
        organizationName = "Acme",
        balance = "5.00",
        personalCredits = "3.10",
        accounts = listOf(AccountRow(null, null, "sam@example.com", false), AccountRow("o1", "Acme", "Admin, Member", true), AccountRow("o2", "Beta Labs", "Member", false)),
    )
    ComponentPreview(padding = false) {
        AccountScreen(previewState().copy(overview = Loadable.Ready(org), switchingTo = "o2"), AccountActions())
    }
}

/** Mixed-script name: the avatar shows the whole supplementary-plane grapheme (DS §2.3, §8.2.7). */
@LightDarkPreviews
@Composable
private fun AccountCjkNamePreview() {
    val cjk = previewOverview.copy(name = "\uD842\uDFB7\u7530 \u82B1\u5B50", email = "hanako@example.com", initial = "\uD842\uDFB7")
    // Organization avatars take the first grapheme too: 𠀋組 (CJK Ext. B) and an emoji-led name.
    val withOrgs = cjk.copy(
        accounts = listOf(
            AccountRow(null, null, "hanako@example.com", active = true),
            AccountRow("o1", "𠀋組", "Member", active = false),
            AccountRow("o2", "😀 Team", "Admin", active = false),
        ),
    )
    ComponentPreview(padding = false) { AccountScreen(previewState().copy(overview = Loadable.Ready(withOrgs)), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountLoadingPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(SignedIn.Unknown, helper = HelperState.Starting(0)).copy(overview = Loadable.Loading), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountSignedOutPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(SignedIn.No), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountWaitingPreview() {
    ComponentPreview(padding = false) {
        AccountScreen(previewState(SignedIn.No).copy(signIn = SignInState.WaitingForTermux("cline", 0)), AccountActions())
    }
}

@LightDarkPreviews
@Composable
private fun AccountSignInFailedPreview() {
    ComponentPreview(padding = false) {
        AccountScreen(previewState(SignedIn.No).copy(signIn = SignInState.Failed(SignInFailure.TimedOut, SignInState.Failed.Kind.SignIn)), AccountActions())
    }
}

@LightDarkPreviews
@Composable
private fun AccountSigningOutPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState().copy(signingOut = true), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountUsagePreview() {
    val rows = listOf(
        UsageRow("1", "claude-sonnet-5", "Anthropic", 12345, "0.48", "Oct 2, 2026", "4:03 PM"),
        UsageRow("2", "deepseek-v4", "DeepSeek", 802, "0.01", "Oct 2, 2026", "3:58 PM"),
    )
    ComponentPreview(padding = false) { AccountScreen(previewState(tab = AccountTab.Usage).copy(usage = Loadable.Ready(rows)), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountUsageEmptyPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(tab = AccountTab.Usage).copy(usage = Loadable.Ready(emptyList())), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountBillingPreview() {
    val rows = listOf(PaymentRow("a", "Sep 1, 2026", "$20.00", "20.00"), PaymentRow("b", "Aug 1, 2026", "$10.00", "10.00"))
    ComponentPreview(padding = false) { AccountScreen(previewState(tab = AccountTab.Billing).copy(billing = Loadable.Ready(rows)), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountBillingEmptyPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(tab = AccountTab.Billing).copy(billing = Loadable.Ready(emptyList())), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountTabLoadingPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(tab = AccountTab.Usage).copy(usage = Loadable.Loading), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountErrorPreview() {
    ComponentPreview(padding = false) {
        AccountScreen(
            previewState(SignedIn.Unknown).copy(overview = Loadable.Error(AccountError.Failure(RepoFailure.Helper("Request failed with status 500")))),
            AccountActions(),
        )
    }
}

@LightDarkPreviews
@Composable
private fun AccountOlderHelperPreview() {
    ComponentPreview(padding = false) { AccountScreen(previewState(SignedIn.Unknown).copy(overview = Loadable.Error(AccountError.OlderHelper)), AccountActions()) }
}

@LightDarkPreviews
@Composable
private fun AccountHelperUnavailablePreview() {
    ComponentPreview(padding = false) {
        AccountScreen(
            previewState(SignedIn.Unknown, helper = HelperState.Unavailable(HelperFailure.StartTimeout, "listen EADDRINUSE")).copy(fallbackSignedIn = Loadable.Ready(true)),
            AccountActions(),
        )
    }
}

@LightDarkPreviews
@Composable
private fun AccountBlockedPreview() {
    ComponentPreview(padding = false) {
        AccountScreen(previewState(SignedIn.Unknown, helper = HelperState.Unavailable(HelperFailure.PermissionMissing, null)).copy(kernel = KernelGate.Blocked(CheckId.Permission)), AccountActions())
    }
}

@LightDarkPreviews
@Composable
private fun AccountSignInBlockedPreview() {
    ComponentPreview(padding = false) {
        AccountScreen(previewState(SignedIn.No).copy(signIn = SignInState.Blocked(TermuxUnavailable.PermissionMissing())), AccountActions())
    }
}
