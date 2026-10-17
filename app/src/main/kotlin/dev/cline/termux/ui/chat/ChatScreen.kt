package dev.cline.termux.ui.chat

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.ui.LocalShellChat
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.agents.AgentsRosterSheet
import dev.cline.termux.ui.approval.ApprovalBanner
import dev.cline.termux.ui.approval.ApprovalEffectsHandler
import dev.cline.termux.ui.approval.ApprovalSheet
import dev.cline.termux.ui.approval.PendingInputFocus
import dev.cline.termux.ui.approval.ApprovalModeActions
import dev.cline.termux.ui.approval.ApprovalModeButton
import dev.cline.termux.ui.approval.approvalWriteFailureText
import dev.cline.termux.ui.changes.ChangesPane
import dev.cline.termux.ui.changes.openChangesFullScreen
import dev.cline.termux.ui.chat.rows.GalleryKind
import dev.cline.termux.ui.chat.rows.ImageGallery
import dev.cline.termux.ui.chat.rows.TranscriptCallbacks
import dev.cline.termux.ui.checkpoint.CheckpointHost
import dev.cline.termux.ui.checkpoint.CheckpointViewModel
import dev.cline.termux.ui.checkpoint.rowErrorText
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.KernelStatePanel
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.MenuNavIcon
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.composer.Composer
import dev.cline.termux.ui.composer.ComposerDock
import dev.cline.termux.ui.composer.ComposerTriggerFocus
import dev.cline.termux.ui.composer.WelcomeSetupNotice
import dev.cline.termux.ui.home.HomeChipFocus
import dev.cline.termux.ui.home.HomeScreen
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.model.ModelPickerSheetHost
import dev.cline.termux.ui.model.ModelPickerViewModel
import dev.cline.termux.ui.model.ReasoningMenu
import dev.cline.termux.ui.navigation.Account
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ChatSelection
import dev.cline.termux.ui.navigation.ChatSupportingPaneHost
import dev.cline.termux.ui.navigation.Customize
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.navigation.OrderedPredictiveBackHandler
import dev.cline.termux.ui.navigation.SettingsProviders
import dev.cline.termux.ui.navigation.SetupConnect
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.SupportingContent
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.components.openTermux
import dev.cline.termux.ui.question.AskQuestionSheet
import dev.cline.termux.ui.question.QuestionActions
import dev.cline.termux.ui.question.QuestionBanner
import dev.cline.termux.ui.question.QuestionEffectsHandler
import dev.cline.termux.ui.queue.PromptQueueHost
import dev.cline.termux.ui.queue.PromptQueueStrip
import dev.cline.termux.ui.sessions.DeleteSessionDialog
import dev.cline.termux.ui.sessions.SessionDetailsSheetHost
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.suggestions.SuggestionPanel
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.tool.ToolPane
import dev.cline.termux.ui.workspace.WorkspacePickerSheetHost
import dev.cline.termux.ui.workspace.WorkspacePickerViewModel
import dev.cline.termux.ui.workspace.rememberWorkspacePickerSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Touches the graph members the chat reads, off the main thread (docs/07 §8: several are
 * `runBlocking` lazies). Idempotent; [done] lets later entries skip the wait.
 */
object ChatGraphWarmup {
    @Volatile
    var done: Boolean = false
        private set

    fun warm(graph: AppGraph) {
        graph.prefs
        graph.termux
        graph.hubClient
        graph.kernel
        graph.sessionApi
        graph.sessionStore
        graph.diffStores
        graph.toolStores
        graph.sessionsRepository
        graph.sessionActions
        graph.settingsHelper
        graph.providerCatalog
        graph.modelSelection
        graph.workspaceStore
        graph.knownSessions
        graph.agents
        done = true
    }
}

/**
 * The chat destination (home-chat.md §1.1, §2.9): [entry] is the root `Chat()` entry (Home +
 * the top-level selection) or a pushed `ChildChat` entry ([childSessionId]). Warms the graph
 * off Main, then creates the entry's ViewModels; until then the welcome hero shows.
 */
@Composable
fun ChatRoute(entry: NavBackStackEntry, navigator: ShellNavigator, graph: AppGraph, childSessionId: String? = null) {
    val warmed by produceState(ChatGraphWarmup.done, graph) {
        if (!value) {
            withContext(Dispatchers.Default) { ChatGraphWarmup.warm(graph) }
            value = true
        }
    }
    if (!warmed) {
        Box(Modifier.fillMaxSize()) { HomeWhileWarming() }
        return
    }
    // The first send resolves its provider and model from the catalog: prime it once the
    // kernel is up (on the app scope, so leaving the chat does not cancel the read).
    LaunchedEffect(graph) {
        graph.kernel.state.first { it is KernelState.Ready }
        graph.appScope.launch { runCatching { graph.providerCatalog.primeForHome() } }
    }
    val handle = entry.savedStateHandle
    // A child route seeds its selection before the ViewModel first reads the handle.
    remember(childSessionId) {
        if (childSessionId != null && ChatSelection.selected(handle) == null) ChatSelection.select(handle, childSessionId)
        ChatSelection.selected(handle)
    }
    val context = LocalContext.current
    val isChild = childSessionId != null
    val vm: ChatViewModel = viewModel(viewModelStoreOwner = entry, factory = ChatViewModel.factory(graph, context, handle, isChild))
    val checkpointVm: CheckpointViewModel = viewModel(viewModelStoreOwner = entry, factory = CheckpointViewModel.factory(graph))
    val pickerVm: ModelPickerViewModel = viewModel(viewModelStoreOwner = entry, factory = ModelPickerViewModel.factory(graph))
    val workspaceVm: WorkspacePickerViewModel = viewModel(viewModelStoreOwner = entry, factory = WorkspacePickerViewModel.factory(graph))
    ChatScreen(vm, checkpointVm, pickerVm, workspaceVm, navigator, graph)
}

/** Home as it first renders, shown for the one frame the chat graph warms up off the main thread. */
@Composable
private fun HomeWhileWarming() {
    HomeScreen(
        state = HomeState(WorkspaceChipState.Default, branch = null, kernelNotice = null, setupNotice = false),
        onWorkspace = {},
        onBranch = {},
        onKernelStatus = {},
    )
}

/**
 * The chat screen: Home or a session in one scaffold with the persistent composer docked at
 * the bottom (the same [ChatViewModel.composer] across the first send, §2.9), every sheet of
 * the screen, the lightbox overlay and the supporting pane on ≥ 1200 × ≥ 480 windows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    checkpointVm: CheckpointViewModel,
    pickerVm: ModelPickerViewModel,
    workspaceVm: WorkspacePickerViewModel,
    navigator: ShellNavigator,
    graph: AppGraph,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val layout = LocalShellLayout.current
    val shellChat = LocalShellChat.current
    val kernelUi = LocalShellKernelUi.current
    val snackbar = LocalSnackbarHost.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val controllers by vm.controllers.collectAsStateWithLifecycle()
    val composerState by vm.composer.uiState.collectAsStateWithLifecycle()
    val suggestion by vm.suggestions.state.collectAsStateWithLifecycle()
    val reasoningSelected by vm.reasoningSelected.collectAsStateWithLifecycle()
    val checkpointState by checkpointVm.uiState.collectAsStateWithLifecycle()
    // HC §5.2: the shell's lightbox covers the rail and every pane; outside the shell, our own.
    val shellLightbox = LocalShellLightbox.current
    val ownLightbox = rememberLightboxState()
    val lightbox = shellLightbox ?: ownLightbox
    // DS §8.1.4 / CA C1.14: one polite region per conversation, shared by the transcript.
    val liveRegion = rememberLiveRegionState()
    val chrome = remember { TranscriptChrome() }
    val holder = rememberSaveableStateHolder()
    val workspaceSheet = rememberWorkspacePickerSheetState()

    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var branchOpen by rememberSaveable { mutableStateOf(false) }
    // DS §8.1.7: each sheet hands focus back to the control that opened it.
    val headerFocus = remember { HeaderFocus() }
    val homeChipFocus = remember { HomeChipFocus() }
    val composerTriggers = remember { ComposerTriggerFocus() }
    val queueStripFocus = remember { FocusRequester() }
    val pendingFocus = remember { PendingInputFocus() }
    var agentsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var storageOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    // The shell's list pane as it is on screen (also hidden while the supporting pane is open).
    val listPaneExpanded = dev.cline.termux.ui.LocalShellListPaneOnScreen.current

    val session = state as? ChatUiState.Session
    val sessionId = session?.sessionId

    // ---- content motion (HC §2.7, §2.9; DS §6) -----------------------------------------------------
    val reducedMotion = ClineTheme.reducedMotion
    val density = LocalDensity.current
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val risePx = with(density) { ChatContentMotion.WELCOME_RISE_DP.dp.roundToPx() }
    val slidePx = with(density) { dev.cline.termux.ui.navigation.NavTransitions.SLIDE_DP.dp.roundToPx() }
    val contentKey = ChatContentMotion.keyOf(state)
    // The last state shown under each key: the outgoing side of a swap and the Home preview of predictive back.
    val lastStates = remember { HashMap<String, ChatUiState>() }
    lastStates[contentKey] = state
    val seekable = remember { SeekableTransitionState(contentKey) }
    LaunchedEffect(contentKey, reducedMotion) {
        if (seekable.currentState == contentKey && seekable.targetState == contentKey) return@LaunchedEffect
        if (reducedMotion || ChatContentMotion.swap(seekable.currentState, contentKey) == ChatSwap.None) {
            seekable.snapTo(contentKey)
        } else {
            seekable.animateTo(contentKey)
        }
        lastStates.keys.retainAll(setOf(contentKey, ChatContentMotion.HOME))
    }
    val selectedId = sessionId?.takeIf { session.header.sessionId != null }

    // ---- bindings ----------------------------------------------------------------------------------
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.setVisible(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.setVisible(false) }
    LaunchedEffect(selectedId) {
        checkpointVm.bindSession(selectedId)
        if (selectedId == null) pickerVm.setHomeHost() else pickerVm.setSessionHost(selectedId)
        if (selectedId == null) {
            renaming = false
            agentsOpen = false
            detailsOpen = false
            deleteOpen = false
        }
    }
    LaunchedEffect(session?.transcript?.status, session?.transcript?.switching) {
        val transcript = session?.transcript
        checkpointVm.setHostFlags(
            abortPending = transcript?.status == ChatStatus.Stopping,
            switching = transcript != null && transcript.switching != Switching.None,
        )
    }
    val checkpointResources = LocalContext.current.resources
    LaunchedEffect(checkpointState) {
        vm.setCheckpointActions(CheckpointRowActions.of(checkpointState) { rowErrorText(checkpointResources, it) })
    }
    LaunchedEffect(shellChat) { shellChat?.composerFocusRequests?.collect { vm.onNewSessionRequested() } }
    LaunchedEffect(branchOpen) { if (branchOpen) vm.openBranchSheet() }

    val onNewSession: () -> Unit = remember(vm, navigator) {
        {
            vm.onNewSessionRequested()
            navigator.newSession()
        }
    }

    LaunchedEffect(vm) {
        vm.eventFlow.collect { event ->
            when (event) {
                is ChatEvent.OpenSession -> navigator.openSession(event.sessionId)
                ChatEvent.OpenHome -> {
                    deleteOpen = false
                    if (vm.isChild) navigator.openHome() else vm.goHome()
                }
                is ChatEvent.OpenParent -> {
                    val previous = navigator.nav.previousBackStackEntry
                    val previousId = previous?.let { ChatSelection.selected(it.savedStateHandle) }
                    if (vm.isChild && previousId == event.parentId) navigator.back() else navigator.openSession(event.parentId)
                }
                is ChatEvent.RenameFailed -> scope.launch { snackbar.showReplacing(resources.getString(R.string.rename_error, event.message)) }
                is ChatEvent.PinFailed -> scope.launch {
                    snackbar.showReplacing(resources.getString(if (event.pinned) R.string.sessions_pin_failed else R.string.sessions_unpin_failed))
                }
                is ChatEvent.ForkFailed -> scope.launch { snackbar.showReplacing(resources.getString(R.string.fork_failed, event.message)) }
                is ChatEvent.DeleteFailed -> scope.launch { snackbar.showReplacing(resources.getString(R.string.delete_error, event.message)) }
                is ChatEvent.ExportSaved -> scope.launch { snackbar.showReplacing(resources.getString(R.string.export_saved, event.fileName)) }
                is ChatEvent.ExportFailed -> scope.launch { snackbar.showReplacing(resources.getString(R.string.export_error, event.message)) }
                ChatEvent.ExportNeedsStorage -> storageOpen = true
                is ChatEvent.BranchSwitchFailed -> scope.launch {
                    snackbar.showReplacing(resources.getString(R.string.branch_switch_error, event.branch, event.detail))
                }
                ChatEvent.BranchSwitched -> branchOpen = false
                is ChatEvent.ApprovalModeFailed -> scope.launch {
                    snackbar.showReplacing(approvalWriteFailureText(resources, event.turningOn, event.failure))
                }
                is ChatEvent.ApprovalModeChangedOutside -> scope.launch {
                    snackbar.showReplacing(resources.getString(if (event.on) R.string.approval_mode_external_on else R.string.approval_mode_external_off))
                }
                is ChatEvent.Credential -> when (event.action) {
                    CredentialAction.Account -> navigator.push(Account())
                    CredentialAction.Providers -> navigator.push(SettingsProviders)
                }
            }
        }
    }

    // ---- back ----------------------------------------------------------------------------------------
    OrderedBackHandler(BackLevel.SuggestionPanel, enabled = suggestion != null) { vm.suggestions.dismiss() }
    // Back from a selected session: the shared-axis pop to Home follows the gesture; commit selects Home.
    OrderedPredictiveBackHandler(
        BackLevel.NavHostPop,
        enabled = !vm.isChild && session != null,
        onStart = { seekable.currentState },
        onProgress = { _, event ->
            if (!reducedMotion) seekable.seekTo(event.progress, targetState = ChatContentMotion.HOME)
        },
        onCommit = { vm.goHome() },
        onCancel = { from -> scope.launch { seekable.snapTo(from) } },
    )

    val callbacks = remember(vm, checkpointVm, navigator) {
        TranscriptCallbacks(
            onEditMessage = { id, content, runCount -> checkpointVm.openEdit(id, content, runCount) },
            onRestoreCheckpoint = { id, runCount -> checkpointVm.openRevert(id, runCount) },
            onResend = vm::resend,
            onCredentialAction = vm::onCredentialAction,
            onProceedWhileRunning = { id, toolCallId -> vm.proceed(id, toolCallId) },
            onOpenToolDetail = { id, toolCallId -> navigator.openTool(id, toolCallId) },
        )
    }
    // DS §6 / checklist: the transcript re-emits up to 20 times a second while text streams, so
    // the header's callbacks are built once and read the changing session through these, which
    // lets ConversationHeader skip every tick its HeaderState has not changed.
    val currentSession by rememberUpdatedState(session)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val headerActions = remember(vm, navigator, checkpointVm, onNewSession) {
        HeaderActions(
            onNavigation = { if (vm.isChild) navigator.back() else navigator.openDrawer() },
            onToggleListPane = { navigator.toggleListPane() },
            onAgents = { agentsOpen = true },
            onDiff = { currentSelectedId?.let(navigator::openChanges) },
            onParent = vm::openParent,
            onNewSession = onNewSession,
            onRename = { title -> vm.rename(title, currentSession?.header?.title) },
            onTogglePin = { vm.togglePin(currentSession?.header?.pinned == true) },
            onFork = vm::fork,
            onDetails = { detailsOpen = true },
            onRestoreCheckpoint = checkpointVm::openPicker,
            onExport = vm::export,
            onDelete = { deleteOpen = true },
        )
    }
    val navigation = when {
        vm.isChild -> HeaderNavigation.Back
        layout.showsRail -> HeaderNavigation.None
        else -> HeaderNavigation.Drawer
    }
    // Decided A1 / A′: the tool approvals control before the title, on Home and in every chat.
    val approvalButton by vm.approvalButton.collectAsStateWithLifecycle()
    val approvalActions = remember(vm) { ApprovalModeActions(onMenuOpened = vm::onApprovalMenuOpened, onSelect = vm::setApprovalMode) }
    val approvalControl: @Composable () -> Unit = remember(approvalActions, liveRegion) {
        { ApprovalModeButton(approvalButton, approvalActions, announcer = liveRegion) }
    }

    ChatSupportingPaneHost(
        supporting = shellChat?.supporting,
        onCloseSupporting = { shellChat?.closeSupporting?.invoke() },
        main = {
            Box(Modifier.fillMaxSize()) {
                // C1.4 step 6: in a height-compact window with the IME open (S25U landscape: ≈412dp
                // tall, Samsung keyboard ≈233dp) the app bar would leave the field under itself, so it
                // steps aside while typing and only the status bar inset stays.
                val typingInCompactHeight = layout.heightCompact && WindowInsets.isImeVisible
                Scaffold(
                    modifier = Modifier.hiddenBehindLightbox(lightbox),
                    // DS §5.7 step 2: the bars and the cutout on the top and both sides (landscape
                    // 3-button navigation and a 270° punch-hole sit on the end edge); the docked
                    // stack takes the bottom (IME ∪ bottomSafe) itself.
                    contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    topBar = {
                        if (typingInCompactHeight) {
                            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        } else if (session != null) {
                            ConversationHeader(
                                state = session.header,
                                navigation = navigation,
                                actions = headerActions,
                                renaming = renaming,
                                onRenamingChange = { renaming = it },
                                listPaneExpanded = if (layout.showsListPane) listPaneExpanded else null,
                                scrolled = chrome.contentUnderTopBar,
                                focus = headerFocus,
                                approvalControl = approvalControl,
                            )
                        } else {
                            // HC §2.4 had no app bar on medium windows (the rail holds the menu); the
                            // tool approvals control (decided A1) needs one on Home at every width.
                            HomeTopBar(navigation, if (layout.showsListPane) listPaneExpanded else null, headerActions, approvalControl)
                        }
                    },
                ) { padding ->
                    // All of the Scaffold's insets reach the content (DS §5.6); consumed here so the
                    // dock's own window-insets padding does not add them again.
                    val direction = LocalLayoutDirection.current
                    val contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(direction),
                    )
                    BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
                        val paneHeight = maxHeight
                        Column(Modifier.fillMaxSize()) {
                            val homeBar = navigation != HeaderNavigation.None || layout.showsListPane
                            // KS F.3 "Chat, nothing cached" (e.g. opened from a notification after process
                            // death): the pane shows the KernelStatePanel instead of an empty transcript
                            // and no banner; the composer under it carries the primary.
                            val nothingCachedGate = kernelUi?.gate?.takeIf { gate ->
                                val session = state as? ChatUiState.Session
                                session != null && session.transcript.isEmpty && !session.transcript.missing && KernelCopy.panel(gate) != null
                            }
                            if (state is ChatUiState.Session && nothingCachedGate == null) {
                                // KS F.3: the KernelBanner sits at the top of the transcript column,
                                // under the app bar, on the transcript's own gutters.
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val gutter = Spacing.gutterFor(maxWidth)
                                    val side = max(gutter, (maxWidth - Spacing.contentMaxWidth) / 2)
                                    ShellKernelBanner(hasComposer = true, contentPadding = PaddingValues(start = side, end = side, top = Spacing.s))
                                }
                            }
                            // DS §4.1 #17: the snackbar floats over the bottom of the transcript, just
                            // above the docked stack; it never takes layout height from the list.
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                // DS §4.1 #17: while a snackbar shows, the scroll-to-latest button rides
                                // above it, as M3 Scaffold lifts a FAB.
                                val snackbarInset = if (snackbar.currentSnackbarData != null) with(density) { snackbarHeight.toDp() } else 0.dp
                                rememberTransition(seekable, label = "chatContent").AnimatedContent(
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = { with(ChatContentMotion) { transform(reducedMotion, risePx, slidePx) } },
                                ) { key ->
                                    val shown = if (key == contentKey) state else lastStates[key] ?: ChatUiState.Initial.takeIf { key == ChatContentMotion.HOME } ?: state
                                    holder.SaveableStateProvider(key) {
                                        when (shown) {
                                            is ChatUiState.Home -> HomeScreen(
                                                state = shown.home,
                                                onWorkspace = { workspaceSheet.open = true },
                                                onBranch = { branchOpen = true },
                                                onKernelStatus = { navigator.openKernelStatus() },
                                                heroHidden = layout.heightCompact,
                                                largeHeading = layout.width != WidthClass.Compact,
                                                statusBarInset = !homeBar,
                                                chipFocus = homeChipFocus,
                                                onWorktreeChange = vm::setWorkInWorktree,
                                            )
                                            is ChatUiState.Session -> if (nothingCachedGate != null && kernelUi != null) {
                                                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                                                    KernelStatePanel(
                                                        gate = nothingCachedGate,
                                                        hasComposer = true,
                                                        onAction = kernelUi.onAction,
                                                        primaryInFlight = kernelUi.actionInFlight != null,
                                                    )
                                                }
                                            } else {
                                                ChatTranscript(
                                                    state = shown.transcript,
                                                    callbacks = callbacks,
                                                    lightbox = lightbox,
                                                    chrome = chrome,
                                                    onBannerAction = vm::onBannerAction,
                                                    onNewSession = onNewSession,
                                                    liveRegion = liveRegion,
                                                    snackbarInset = snackbarInset,
                                                )
                                            }
                                        }
                                    }
                                }
                                // DS §4.1 #17: one host at a time. While the lightbox is open it shows
                                // the snackbars itself, above its scrim, so this one steps aside.
                                if (!lightbox.isOpen) {
                                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).onSizeChanged { snackbarHeight = it.height })
                                }
                            }
                            val imeOpen = WindowInsets.isImeVisible
                            val approval = controllers?.approval?.state?.collectAsStateWithLifecycle()?.value
                            val question = controllers?.question?.state?.collectAsStateWithLifecycle()?.value
                            val stall by vm.stall.collectAsStateWithLifecycle()
                            val bannerVisible = approval?.bannerVisible == true || question?.bannerVisible == true || stall != null
                            ComposerDock(
                                maxHeight = DockRules.maxHeight(paneHeight, layout.heightCompact, imeOpen, bannerVisible),
                            ) {
                                controllers?.let { c ->
                                    if (approval?.bannerVisible == true) {
                                        ApprovalBanner(
                                            approval.cards.size,
                                            onReview = {
                                                pendingFocus.reviewFrom(pane = false)
                                                c.approval.reopen()
                                            },
                                            modifier = Modifier.focusRequester(pendingFocus.approvalBanner),
                                        )
                                    }
                                    if (question?.bannerVisible == true) {
                                        QuestionBanner(onAnswer = c.question::reopen, modifier = Modifier.focusRequester(pendingFocus.questionBanner))
                                    }
                                }
                                // A4: the kernel's run went silent for a whole heartbeat interval. While typing in a
                                // height-compact window it takes one row (D18, trial row 13).
                                stall?.let { shown ->
                                    StallBanner(
                                        onRestartKernel = { kernelUi?.onAction?.invoke(KernelAction.RestartKernel) },
                                        onKeepWaiting = vm::keepWaiting,
                                        episode = shown.since,
                                        liveRegion = liveRegion,
                                        singleRow = layout.heightCompact && imeOpen,
                                    )
                                }
                                val home = (state as? ChatUiState.Home)?.home
                                if (home?.setupNotice == true) {
                                    WelcomeSetupNotice(onConnectModel = { navigator.openRoot(SetupConnect) }, onModelSettings = { navigator.push(SettingsProviders) })
                                }
                                val queueController = controllers?.queue
                                val queueState = queueController?.state?.collectAsStateWithLifecycle()?.value
                                val queueShown = queueState != null &&
                                    DockRules.queueStripShown(queueState.stripVisible, layout.heightCompact, imeOpen, bannerVisible)
                                Composer(
                                    controller = vm.composer,
                                    onModelChip = { pickerOpen = true },
                                    onOpenImage = { image ->
                                        scope.launch {
                                            val data = withContext(Dispatchers.IO) {
                                                runCatching { Base64.encodeToString(image.cacheFile.readBytes(), Base64.NO_WRAP) }.getOrNull()
                                            } ?: return@launch
                                            lightbox.open(composerState.sessionId, ImageGallery(GalleryKind.Attachment, listOf(ChatImage(image.id, image.mime, data))), 0)
                                        }
                                    },
                                    onConnectModel = { navigator.push(SettingsProviders) },
                                    onOpenTools = { navigator.push(Customize(tab = TOOLS_TAB, pushed = true)) },
                                    onNewSession = onNewSession,
                                    reasoningMenu = { expanded, onDismiss ->
                                        ReasoningMenu(
                                            expanded = expanded,
                                            // No explicit pick yet: mark the level the chip shows (seeded Low).
                                            selected = reasoningSelected ?: (composerState.reasoning.label as? ReasoningChipLabel.Level)?.effort,
                                            onSelect = { effort ->
                                                pickerVm.pickReasoning(effort)
                                                onDismiss()
                                            },
                                            onDismissRequest = onDismiss,
                                        )
                                    },
                                    suggestionPanel = suggestion?.let { menu ->
                                        {
                                            SuggestionPanel(
                                                state = menu,
                                                onSelect = { index -> vm.applySuggestion(vm.suggestions.select(index)) },
                                                onChooseWorkspace = { workspaceSheet.open = true },
                                                onAllowCommands = { kernelUi?.onAction?.invoke(KernelAction.Fix) },
                                            )
                                        }
                                    },
                                    queueStrip = if (queueController != null && queueState != null && queueShown) {
                                        {
                                            PromptQueueStrip(
                                                queueState,
                                                onOpenSheet = queueController::openSheet,
                                                onSteerFirst = { queueController.steerFirst() },
                                                focus = queueStripFocus,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onPanelKey = if (suggestion != null) {
                                        { event -> handlePanelKey(event, vm) }
                                    } else {
                                        null
                                    },
                                    suggestionCount = suggestion?.rowCount,
                                    announcer = liveRegion,
                                    focusRequester = pendingFocus.composerField,
                                    triggers = composerTriggers,
                                )
                            }
                        }
                    }
                }
                // DS §8.1.4: the conversation's one polite status region (transcript, composer,
                // approval and question announcements all go here).
                LiveRegionAnnouncer(liveRegion)
                // Outside the shell (previews, tests) the chat hosts its own lightbox; in the shell
                // AppShell draws it above every pane (HC §5.2).
                if (shellLightbox == null) ImageLightboxOverlay(lightbox)
            }
        },
        supportingPane = { content ->
            when (content) {
                is SupportingContent.Changes -> ChangesPane(
                    sessionId = content.sessionId,
                    onClose = { shellChat?.closeSupporting?.invoke() },
                    onOpenFullScreen = {
                        shellChat?.closeSupporting?.invoke()
                        navigator.openChangesFullScreen(content.sessionId)
                    },
                    activeSessionId = selectedId,
                )
                is SupportingContent.Tool -> ToolPane(
                    sessionId = content.sessionId,
                    toolCallId = content.toolCallId,
                    onClose = { shellChat?.closeSupporting?.invoke() },
                    onViewAllChanges = { navigator.openChanges(content.sessionId) },
                    activeSessionId = selectedId,
                    approvalBanner = {
                        controllers?.takeIf { it.sessionId == content.sessionId }?.let { c ->
                            val approval by c.approval.state.collectAsStateWithLifecycle()
                            if (approval.cards.isNotEmpty()) {
                                DisposableEffect(Unit) { onDispose { pendingFocus.paneBannerGone() } }
                                ApprovalBanner(
                                    approval.cards.size,
                                    onReview = {
                                        pendingFocus.reviewFrom(pane = true)
                                        c.approval.reopen()
                                    },
                                    modifier = Modifier.focusRequester(pendingFocus.paneApprovalBanner),
                                )
                            }
                        }
                    },
                )
            }
        },
    )

    // ---- sheets and dialogs -----------------------------------------------------------------------------
    controllers?.let { c ->
        val approval by c.approval.state.collectAsStateWithLifecycle()
        val question by c.question.state.collectAsStateWithLifecycle()
        // DS §8.1.7: a collapsed sheet hands focus to its banner; one with nothing left, to the composer field.
        val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
        ApprovalSheet(
            state = approval,
            onApprove = c.approval::approve,
            onReject = c.approval::reject,
            onToggleRaw = c.approval::toggleRaw,
            onStop = c.approval::stop,
            onCollapse = c.approval::collapse,
            returnFocus = pendingFocus.approvalReturn(approval.cards.isNotEmpty(), keyboardMode),
            // §3: the "Approve ▾" menu only while the switch would change something and can be written.
            onApproveAndAutoApprove = c.approval::approveAndAutoApprove.takeIf {
                approvalButton.enabled && approvalButton.mode != dev.cline.termux.data.approvals.ApprovalMode.Auto
            },
        )
        AskQuestionSheet(
            question,
            QuestionActions.of(c.question),
            returnFocus = pendingFocus.questionReturn(question.items.isNotEmpty(), keyboardMode),
        )
        ApprovalEffectsHandler(c.approval.effects, liveRegion, onReview = c.approval::reopen, awaitStopFailureEnd = c.approval::awaitStopFailureEnd)
        QuestionEffectsHandler(c.question.effects, liveRegion, onAnswer = c.question::reopen)
        PromptQueueHost(c.queue, returnFocus = queueStripFocus)
    }
    ModelPickerSheetHost(
        open = pickerOpen,
        onOpenChange = { pickerOpen = it },
        viewModel = pickerVm,
        onResult = vm::onModelPickerResult,
        onSetUpProvider = { navigator.push(SettingsProviders) },
        returnFocus = composerTriggers.model,
    )
    WorkspacePickerSheetHost(workspaceSheet, workspaceVm, navigator, returnFocus = homeChipFocus.workspace)
    if (branchOpen) {
        val branchState by vm.branchSheet.collectAsStateWithLifecycle()
        BranchSheet(branchState, onQueryChange = vm::setBranchQuery, onSelect = vm::selectBranch, onDismiss = { branchOpen = false }, returnFocus = homeChipFocus.branch)
    }
    if (agentsOpen && selectedId != null) {
        AgentsRosterSheet(
            parentSessionId = selectedId,
            graph = graph,
            onDismiss = { agentsOpen = false },
            onOpenAgent = { child ->
                agentsOpen = false
                navigator.openChildSession(child)
            },
            returnFocus = headerFocus.opener,
        )
    }
    if (detailsOpen && selectedId != null) {
        SessionDetailsSheetHost(selectedId, vm.sessionsPort, onDismiss = { detailsOpen = false }, returnFocus = headerFocus.opener)
    }
    if (deleteOpen && selectedId != null) {
        val summary by remember(selectedId) { vm.summary(selectedId) }.collectAsStateWithLifecycle(null)
        summary?.let {
            DeleteSessionDialog(
                summary = it,
                deleting = session.header.pending == HeaderAction.Delete,
                onConfirm = vm::delete,
                onDismiss = { deleteOpen = false },
            )
        }
    }
    if (storageOpen) {
        ConfirmDialog(
            title = stringResource(R.string.export_storage_title),
            confirmLabel = stringResource(R.string.export_open_termux),
            text = stringResource(R.string.export_storage_body),
            onConfirm = {
                storageOpen = false
                openTermux(context)
            },
            onDismiss = { storageOpen = false },
        )
    }
    CheckpointHost(
        viewModel = checkpointVm,
        onSessionReplaced = { event -> vm.onSessionReplaced(event.newSessionId, event.composerText) },
        onOpenSessions = navigator::openSessions,
        onKernelAction = { action -> kernelUi?.onAction?.invoke(action) },
        pickerReturnFocus = headerFocus.overflow,
    )
}

/**
 * Home's bar: the drawer button on compact windows, the list-pane toggle on expanded ones and,
 * after them, the tool approvals control (decided A1, A′; Home always takes the title placement,
 * since it has no actions to sit beside).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(navigation: HeaderNavigation, listPaneExpanded: Boolean?, actions: HeaderActions, approvalControl: @Composable () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            androidx.compose.foundation.layout.Row {
                if (navigation == HeaderNavigation.Drawer) MenuNavIcon(actions.onNavigation)
                if (listPaneExpanded != null) ListPaneToggle(listPaneExpanded, actions.onToggleListPane)
                approvalControl()
            }
        },
    )
}

/** C1.8: the open suggestion panel takes Up/Down, Tab/Enter and Esc before the field. */
private fun handlePanelKey(event: KeyEvent, vm: ChatViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionUp -> {
            vm.suggestions.moveHighlight(-1)
            true
        }
        Key.DirectionDown -> {
            vm.suggestions.moveHighlight(1)
            true
        }
        Key.Tab, Key.Enter, Key.NumPadEnter -> {
            val edit = vm.suggestions.acceptHighlighted()
            vm.applySuggestion(edit)
            edit != null
        }
        Key.Escape -> vm.suggestions.dismiss()
        else -> false
    }
}

private const val TOOLS_TAB = "tools"
