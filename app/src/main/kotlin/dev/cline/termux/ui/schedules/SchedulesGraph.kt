package dev.cline.termux.ui.schedules

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.BuildInfo
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.rememberIs24HourClock
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.navigation.ChildChat
import dev.cline.termux.ui.navigation.EditorBackHandler
import dev.cline.termux.ui.navigation.EditorCloseAction
import dev.cline.termux.ui.navigation.editorCloseAction
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.KernelReliability
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ScheduleDetails
import dev.cline.termux.ui.navigation.ScheduleEdit
import dev.cline.termux.ui.navigation.Schedules
import dev.cline.termux.ui.navigation.ShellNavigationIcon
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.navigation.editor
import dev.cline.termux.ui.showReplacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Schedules destinations (customize-and-rest.md C5–C7, docs/07 §6.2): `schedules`
 * (top-level, or pushed from Settings with `pushed = true`), `schedules/{scheduleId}` (C7
 * pushed) and the editor `schedules/edit?id=&template=` (`dialog()` when [editorsAsDialogs],
 * else full screen). On expanded windows `schedules` is its own list-detail scaffold.
 *
 * [reducedScopeWorkspace] is the Origin-only developer connection's scope ("home"; §4.4): the
 * list shows its notice and the editor locks the workspace to the Termux home folder. Null on
 * the normal token connection.
 */
fun NavGraphBuilder.schedulesGraph(
    nav: ShellNavigator,
    graph: AppGraph,
    editorsAsDialogs: Boolean,
    reducedScopeWorkspace: String? = null,
) {
    composable<Schedules> { entry ->
        val route = entry.toRoute<Schedules>()
        val vm: SchedulesViewModel = viewModel(factory = SchedulesFactories.list(graph, reducedScopeWorkspace))
        val layout = LocalShellLayout.current
        val expanded = layout.listDetailPanes
        SchedulesHost(vm, nav) { state, callbacks, inFlight ->
            SchedulesScreen(
                state = state,
                callbacks = callbacks,
                navigationIcon = { ShellNavigationIcon(route, nav) },
                expanded = expanded,
                medium = layout.width == WidthClass.Medium,
                gutter = layout.gutter,
                kernelActionInFlight = inFlight,
            )
        }
    }
    composable<ScheduleDetails> {
        val vm: SchedulesViewModel = viewModel(factory = SchedulesFactories.list(graph, reducedScopeWorkspace))
        val layout = LocalShellLayout.current
        SchedulesHost(vm, nav) { state, callbacks, _ ->
            val details = state.details
            if (details != null) {
                ScheduleDetailsScreen(state, details, callbacks, inPane = false, gutter = layout.gutter, onBack = { nav.back() })
            }
        }
    }
    editor<ScheduleEdit>(editorsAsDialogs) { asDialog ->
        val route = toRoute<ScheduleEdit>()
        val vm: ScheduleEditorViewModel = viewModel(factory = SchedulesFactories.editor(graph, route.id, route.template, reducedScopeWorkspace))
        ScheduleEditorHost(vm, asDialog, onClose = { nav.back() })
    }
}

/** Binds a [SchedulesViewModel] to the navigator, snackbars and the shell's kernel actions. */
@Composable
private fun SchedulesHost(
    vm: SchedulesViewModel,
    nav: ShellNavigator,
    content: @Composable (SchedulesUiState, SchedulesCallbacks, Boolean) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHost.current
    val kernelUi = LocalShellKernelUi.current
    val expanded = LocalShellLayout.current.let { it.width == WidthClass.Expanded && !it.heightCompact }
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }
    LaunchedEffect(vm) {
        vm.effects.collect { event ->
            when (event) {
                is SchedulesEvent.RunStarted -> launch { snackbar.showReplacing(resources.getString(R.string.schedule_snackbar_run_started, event.name)) }
                is SchedulesEvent.RunNotStarted -> launch {
                    snackbar.showReplacing(resources.getString(R.string.schedule_snackbar_run_not_started, event.name), duration = SnackbarDuration.Long)
                }
                is SchedulesEvent.RunFailed -> launch {
                    snackbar.showReplacing(resources.getString(R.string.schedule_snackbar_run_failed, event.failure.text(resources)), duration = SnackbarDuration.Long)
                }
                is SchedulesEvent.Transient -> launch {
                    snackbar.showReplacing(event.failure.text(resources), duration = SnackbarDuration.Long)
                }
                is SchedulesEvent.OpenSession -> nav.push(ChildChat(event.sessionId))
                SchedulesEvent.DetailsDeleted -> nav.back()
            }
        }
    }
    val callbacks = remember(vm, nav, kernelUi, expanded) {
        SchedulesCallbacks(
            onRefresh = vm::refresh,
            onNew = { nav.push(ScheduleEdit()) },
            onTemplate = { id -> nav.push(ScheduleEdit(template = id)) },
            onOpen = { id -> if (expanded) vm.select(id) else nav.push(ScheduleDetails(id)) },
            onEdit = { id -> nav.push(ScheduleEdit(id = id)) },
            onRunNow = vm::runNow,
            onSetEnabled = vm::setEnabled,
            onDelete = vm::requestDelete,
            onConfirmDelete = vm::confirmDelete,
            onDismissDelete = vm::dismissDelete,
            onKeepRunning = { nav.openRoot(KernelReliability) },
            onDismissKeepAlive = vm::dismissKeepAliveNotice,
            onClearActionError = vm::clearActionError,
            onKernelAction = { action -> kernelUi?.onAction?.invoke(action) },
            onBlockAction = { action -> handleBlockAction(action, vm.uiState.value.kernel, nav, vm) },
            onCloseDetails = vm::clearSelection,
            onToggleRuns = vm::toggleShowAllRuns,
            onRetryDetails = vm::retryDetails,
            onOpenSession = vm::openSession,
        )
    }
    content(state, callbacks, kernelUi?.actionInFlight != null)
}

private fun handleBlockAction(action: KernelBlockAction, gate: KernelGate, nav: ShellNavigator, vm: SchedulesViewModel) {
    val focus = (gate as? KernelGate.Blocked)?.focus?.name
    when (action) {
        KernelBlockAction.InstallTermux -> nav.openRoot(KernelInstallTermux)
        KernelBlockAction.RequestPermission, KernelBlockAction.OpenAppSettings, KernelBlockAction.OpenKernelSetup ->
            nav.openRoot(KernelRepair(focus = focus, pushed = true))
        KernelBlockAction.Retry -> vm.refresh()
        KernelBlockAction.RunDiagnostics -> nav.openKernelStatus(diagnostics = true)
    }
}

/** Binds a [ScheduleEditorViewModel]: discard guard, consume-while-saving, close on save. */
@Composable
private fun ScheduleEditorHost(vm: ScheduleEditorViewModel, asDialog: Boolean, onClose: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val is24Hour = rememberIs24HourClock()
    val kernelUi = LocalShellKernelUi.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        when (editorCloseAction(state.dirty, state.saving)) {
            EditorCloseAction.Close -> onClose()
            EditorCloseAction.ConfirmDiscard -> confirmDiscard = true
            EditorCloseAction.Ignore -> Unit
        }
    }
    EditorBackHandler(
        dirty = state.dirty,
        saving = state.saving,
        onRequestDiscard = { confirmDiscard = true },
        asDialog = asDialog,
        onClose = onClose,
    )
    LaunchedEffect(vm) {
        vm.effects.collect { event ->
            when (event) {
                EditorEvent.Saved -> onClose()
            }
        }
    }
    val callbacks = remember(vm, kernelUi) {
        ScheduleEditorCallbacks(
            onName = vm::setName,
            onType = vm::setType,
            onDate = vm::setDate,
            onTime = vm::setTime,
            onToggleDay = vm::toggleDay,
            onPrompt = vm::setPrompt,
            onProvider = vm::setProvider,
            onModel = vm::setModel,
            onWorkspace = vm::setWorkspace,
            onSystemPrompt = vm::setSystemPrompt,
            onTimeout = vm::setTimeout,
            onTags = vm::setTags,
            onSave = vm::save,
            onRetryLoad = vm::retryLoad,
            onKernelAction = { action -> kernelUi?.onAction?.invoke(action) },
        )
    }
    val bound = remember(callbacks, state.dirty, state.saving) {
        ScheduleEditorCallbacks(
            onName = callbacks.onName, onType = callbacks.onType, onDate = callbacks.onDate, onTime = callbacks.onTime,
            onToggleDay = callbacks.onToggleDay, onPrompt = callbacks.onPrompt, onProvider = callbacks.onProvider,
            onModel = callbacks.onModel, onWorkspace = callbacks.onWorkspace, onSystemPrompt = callbacks.onSystemPrompt,
            onTimeout = callbacks.onTimeout, onTags = callbacks.onTags, onSave = callbacks.onSave,
            onClose = requestClose, onRetryLoad = callbacks.onRetryLoad, onKernelAction = callbacks.onKernelAction,
        )
    }
    ScheduleEditor(state, bound, asDialog = asDialog, is24Hour = is24Hour)
    if (confirmDiscard) {
        DiscardScheduleDialog(onKeepEditing = { confirmDiscard = false }, onDiscard = {
            confirmDiscard = false
            onClose()
        })
    }
}

/** ViewModel factories over [AppGraph] (docs/07 §1.3). */
object SchedulesFactories {
    fun list(graph: AppGraph, reducedScopeWorkspace: String?): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SchedulesViewModel(
                repo = graph.schedules,
                kernelGate = graph.kernel.gate,
                keepAlive = keepAliveInputs(graph.prefs),
                appVersion = BuildInfo.VERSION_NAME,
                reducedScopeWorkspace = reducedScopeWorkspace,
                savedState = createSavedStateHandle(),
                is24Hour = graph::is24HourClock,
                words = { scheduleWords(graph.resources) },
            )
        }
    }

    fun editor(graph: AppGraph, scheduleId: String?, templateId: String?, reducedScopeWorkspace: String?): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ScheduleEditorViewModel(
                repo = graph.schedules,
                sources = editorSources(graph),
                kernelGate = graph.kernel.gate,
                savedState = createSavedStateHandle(),
                scheduleId = scheduleId,
                templateId = templateId,
                homeOnly = reducedScopeWorkspace?.let { ScheduleEditorViewModel.TERMUX_HOME },
                nowMs = { graph.clock.millis() },
            )
        }
    }

    /** `kernel.keepAwake`, the One UI checklist (all three confirmed) and the per-version dismissal. */
    fun keepAliveInputs(prefs: AppPreferences): KeepAliveInputs = object : KeepAliveInputs {
        override val keepAwake: Flow<Boolean> = prefs.keepAwake
        override val checklistConfirmed: Flow<Boolean> =
            prefs.reliabilityConfirmed.map { it.size == AppPreferences.ReliabilityCheck.entries.size }
        override suspend fun dismissedFor(): String? = prefs.schedulesKeepAliveNoticeDismissedFor()
        override suspend fun setDismissedFor(version: String) {
            prefs.setSchedulesKeepAliveNoticeDismissedFor(version)
        }
    }

    fun editorSources(graph: AppGraph): ScheduleEditorSources = object : ScheduleEditorSources {
        override val catalog: Flow<CatalogState> = graph.providerCatalog.state
        override suspend fun loadCatalog() {
            graph.providerCatalog.load()
        }
        override suspend fun rememberedSelection(): ModelSelection = graph.modelSelection.current()
        override val workspaces: Flow<List<String>> = graph.workspaceStore.catalog
        override suspend fun recentWorkspace(): String? =
            graph.workspaceStore.activeNow().pathOrNull ?: graph.workspaceStore.recents.first().firstOrNull()
    }
}
