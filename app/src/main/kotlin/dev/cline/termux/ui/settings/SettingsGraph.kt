package dev.cline.termux.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.cline.termux.AppGraph
import dev.cline.termux.BuildInfo
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.PageAppBar
import dev.cline.termux.ui.components.openAppDetails
import dev.cline.termux.ui.components.pageScrollBehavior
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.components.startActivitySafely
import dev.cline.termux.ui.navigation.AboutDiagnostics
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.LocalRootNavigator
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.Settings
import dev.cline.termux.ui.navigation.SettingsAppearance
import dev.cline.termux.ui.navigation.SettingsNotifications
import dev.cline.termux.ui.navigation.ShellNavigationIcon
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.rememberPaneDirective
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.Spacing

/**
 * Detail panes other lanes may plug into the expanded Settings scaffold (SP §0.2). Only
 * single-page sections are honoured ([SettingsSection.singlePane]: Kernel, Account, About);
 * Appearance and Notifications are always shown in the pane by this package, and Providers,
 * Installed, Marketplace and Schedules always replace the Settings scaffold with their own.
 * A section without content here pushes its route, as on compact and medium.
 */
@Immutable
class SettingsDetailPanes(private val content: Map<SettingsSection, @Composable () -> Unit> = emptyMap()) {
    fun showsInPane(section: SettingsSection): Boolean = when (section) {
        SettingsSection.Appearance, SettingsSection.Notifications -> true
        else -> section.singlePane && content.containsKey(section)
    }

    fun content(section: SettingsSection): (@Composable () -> Unit)? = content[section]
}

/**
 * The settings destinations of this package (SP S1–S3): `settings` (top-level; a list-detail
 * scaffold on expanded windows), `settings/appearance` and `settings/notifications` (pushed
 * on compact and medium), registered from `shellDestinations` with the Kernel, Account and
 * About detail panes. The Providers row navigates to `settings/providers`, which the
 * providers lane registers; on expanded it replaces this scaffold (never nested).
 */
fun NavGraphBuilder.settingsGraph(nav: ShellNavigator, graph: AppGraph, panes: SettingsDetailPanes = SettingsDetailPanes()) {
    composable<Settings> { entry -> SettingsRoute(entry, nav, graph, panes) }
    composable<SettingsAppearance> { entry ->
        RedirectIntoSettingsPane(entry, nav, SettingsSection.Appearance)
        AppearancePage(graph, navigationIcon = { BackNavIcon { nav.back() } })
    }
    composable<SettingsNotifications> { entry ->
        RedirectIntoSettingsPane(entry, nav, SettingsSection.Notifications)
        NotificationSettingsPage(graph, nav, navigationIcon = { BackNavIcon { nav.back() } })
    }
}

// ------------------------------------------------------------------------------- factories

/** ViewModel factories over the app graph; the screens' tests construct the ViewModels with fakes. */
object SettingsViewModels {
    private val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** [handle] is the `settings` entry's own handle, so a pushed section can preselect the pane. */
    fun home(context: Context, graph: AppGraph, handle: SavedStateHandle): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SettingsHomeViewModel(handle, GraphSettingsHomeSources(context.applicationContext, graph), BuildInfo.VERSION_NAME, dynamicColorSupported)
        }
    }

    fun appearance(context: Context, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AppearanceViewModel(GraphAppearanceStore(graph), AndroidLauncherIcons.of(context), dynamicColorSupported, graph.appScope)
        }
    }

    fun notifications(context: Context, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            NotificationSettingsViewModel(createSavedStateHandle(), GraphNotificationSettingsSources(context.applicationContext, graph))
        }
    }
}

// --------------------------------------------------------------------------------- S1 route

@Composable
private fun SettingsRoute(entry: NavBackStackEntry, nav: ShellNavigator, graph: AppGraph, panes: SettingsDetailPanes) {
    val context = LocalContext.current
    val viewModel: SettingsHomeViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = SettingsViewModels.home(context, graph, entry.savedStateHandle),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.onStop() }
    }

    val twoPane = LocalShellLayout.current.listDetailPanes
    // An explicit section that cannot sit in a pane here (compact/medium, a deep link to a
    // two-pane section, or a pane chosen before the window shrank) opens its page instead.
    LaunchedEffect(twoPane, state.selected) {
        val section = state.selected ?: return@LaunchedEffect
        if (!(twoPane && panes.showsInPane(section))) {
            viewModel.select(null)
            nav.push(section.route)
        }
    }

    val root = LocalRootNavigator.current
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            permanentlyDenied = activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, TermuxRunner.PERMISSION) } ?: false
        }
        viewModel.onResume()
    }
    val actions = SettingsHomeActions(
        onSection = { section ->
            if (twoPane && panes.showsInPane(section)) viewModel.select(section) else nav.push(section.route)
        },
        onTelemetry = viewModel::setTelemetryEnabled,
        onAutoApprove = viewModel::setAutoApprove,
        onReplayOnboarding = { root?.replayOnboarding() },
        onDiagnostics = { nav.push(AboutDiagnostics) },
        onBlockAction = { action ->
            when (action) {
                KernelBlockAction.InstallTermux -> nav.openRoot(KernelInstallTermux)
                KernelBlockAction.RequestPermission -> permissionLauncher.launch(TermuxRunner.PERMISSION)
                KernelBlockAction.OpenAppSettings -> context.openAppDetails()
                KernelBlockAction.OpenKernelSetup -> nav.openRoot(KernelRepair.of(state.blocked?.checkId(), pushed = true))
                KernelBlockAction.Retry -> viewModel.onResume()
                KernelBlockAction.RunDiagnostics -> nav.openKernelStatus(diagnostics = true)
            }
        },
        onCompaction = viewModel::setCompactionMode,
        onMessageDetails = viewModel::setMessageDetails,
    )
    val snackbar = LocalSnackbarHost.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsHomeEvent.CompactionSaveFailed -> snackbar.showReplacing(context.compactionSaveFailedText(event.error))
            }
        }
    }
    val banner: (@Composable () -> Unit)? = if (LocalShellKernelUi.current != null) {
        { ShellKernelBanner(hasComposer = false, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) }
    } else {
        null
    }

    if (twoPane) {
        SettingsListDetail(state, actions, nav, graph, panes, permanentlyDenied, banner)
    } else {
        PageScaffold(
            title = stringResource(R.string.settings_title),
            navigationIcon = { ShellNavigationIcon(Settings(), nav) },
        ) { padding ->
            SettingsHomeList(state, actions, padding, permissionPermanentlyDenied = permanentlyDenied, banner = banner)
        }
    }
}

/**
 * Expanded (≥ 840dp): `NavigableListDetailPaneScaffold` with the S1 list (360dp) and the
 * selected section's page. Both panes are visible, so pane back has nothing to pop and back
 * leaves Settings through the NavHost (SP §0.2). Only one snackbar host exists: the detail page's.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SettingsListDetail(
    state: SettingsHomeUiState,
    actions: SettingsHomeActions,
    nav: ShellNavigator,
    graph: AppGraph,
    panes: SettingsDetailPanes,
    permanentlyDenied: Boolean,
    banner: (@Composable () -> Unit)?,
) {
    val scaffold = rememberListDetailPaneScaffoldNavigator(
        scaffoldDirective = rememberPaneDirective(LIST_PANE_WIDTH),
        initialDestinationHistory = listOf(ThreePaneScaffoldDestinationItem<Any>(ListDetailPaneScaffoldRole.Detail, null)),
    )
    val detail = state.selected?.takeIf(panes::showsInPane) ?: SettingsSection.DEFAULT_DETAIL
    NavigableListDetailPaneScaffold(
        navigator = scaffold,
        listPane = {
            AnimatedPane {
                val scrollBehavior = pageScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.bottomSafe),
                    topBar = { PageAppBar(title = stringResource(R.string.settings_title), scrollBehavior = scrollBehavior) },
                ) { padding ->
                    SettingsHomeList(
                        state = state,
                        actions = actions,
                        contentPadding = padding,
                        highlight = detail,
                        permissionPermanentlyDenied = permanentlyDenied,
                        banner = banner,
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                AnimatedContent(
                    targetState = detail,
                    transitionSpec = {
                        fadeIn(tween(ClineMotion.Medium2 - ClineMotion.Short2, delayMillis = ClineMotion.Short2, easing = ClineMotion.Standard)) togetherWith
                            fadeOut(tween(ClineMotion.Short2, easing = ClineMotion.Standard))
                    },
                    label = "settings-detail",
                ) { section ->
                    when (section) {
                        SettingsSection.Appearance -> AppearancePage(graph, navigationIcon = null)
                        SettingsSection.Notifications -> NotificationSettingsPage(graph, nav, navigationIcon = null)
                        else -> panes.content(section)?.invoke()
                    }
                }
            }
        },
        defaultBackBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
    )
}

private val LIST_PANE_WIDTH = 360.dp

/**
 * A pushed Appearance / Notifications route shown on an expanded window right above the
 * `settings` entry (a rotation, or a deep link from Settings): hand the section to the
 * Settings detail pane and pop, so the page is never shown full width beside nothing.
 */
@Composable
private fun RedirectIntoSettingsPane(entry: NavBackStackEntry, nav: ShellNavigator, section: SettingsSection) {
    val twoPane = LocalShellLayout.current.listDetailPanes
    LaunchedEffect(twoPane) {
        if (!twoPane || nav.nav.currentBackStackEntry != entry) return@LaunchedEffect
        val previous = nav.nav.previousBackStackEntry ?: return@LaunchedEffect
        if (!previous.destination.hasRoute<Settings>()) return@LaunchedEffect
        previous.savedStateHandle[SettingsHomeViewModel.KEY_SECTION] = section.key
        nav.back()
    }
}

// ------------------------------------------------------------------------------ S2 / S3 pages

/** S2 page: [navigationIcon] is the back arrow on a pushed route, null in the expanded detail pane. */
@Composable
private fun AppearancePage(graph: AppGraph, navigationIcon: (@Composable () -> Unit)?) {
    val context = LocalContext.current
    val viewModel: AppearanceViewModel = viewModel(key = "settings-appearance", factory = SettingsViewModels.appearance(context, graph))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val switched = stringResource(R.string.appearance_icon_switched)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                AppearanceEvent.IconSwitched -> snackbar.showReplacing(switched)
            }
        }
    }
    PageScaffold(title = stringResource(R.string.appearance_title), navigationIcon = navigationIcon) { padding ->
        AppearanceContent(
            state = state,
            actions = AppearanceActions(
                onThemeMode = viewModel::setThemeMode,
                onDynamicColor = viewModel::setDynamicColor,
                onFontSizeDrag = viewModel::onFontSizeDrag,
                onFontSizeDragFinished = viewModel::onFontSizeDragFinished,
                onFontSizeStep = viewModel::stepFontSize,
                onAppIcon = viewModel::setAppIcon,
            ),
            contentPadding = padding,
        )
    }
}

/** S3 page: the permission request, the system-settings intents and the Kernel settings link. */
@Composable
private fun NotificationSettingsPage(graph: AppGraph, nav: ShellNavigator, navigationIcon: (@Composable () -> Unit)?) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel: NotificationSettingsViewModel = viewModel(key = "settings-notifications", factory = SettingsViewModels.notifications(context, graph))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rationale = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(granted, rationale())
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume(rationale())
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                NotificationSettingsEvent.RequestPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.onPermissionResult(granted = true, rationale = false)
                    }
            }
        }
    }
    val appNotificationSettings: () -> Unit = {
        context.startActivitySafely(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    PageScaffold(title = stringResource(R.string.notif_settings_title), navigationIcon = navigationIcon) { padding ->
        NotificationSettingsContent(
            state = state,
            actions = NotificationSettingsActions(
                onAllow = viewModel::requestPermission,
                onCheckPermission = appNotificationSettings,
                onNotify = viewModel::setEnabled,
                onSound = viewModel::setSound,
                onOpenChannel = { channelId ->
                    context.startActivitySafely(
                        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, channelId),
                    )
                },
                onKeepConnected = viewModel::setKeepConnected,
                onSystemSettings = appNotificationSettings,
            ),
            contentPadding = padding,
        )
    }
}
