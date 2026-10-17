package dev.cline.termux.ui.composer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.findActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.data.chat.Attachments
import dev.cline.termux.data.chat.describeAttachment
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.hasHardwareKeyboard
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.ComposerGate
import dev.cline.termux.ui.kernel.ComposerKernelButton
import dev.cline.termux.ui.kernel.ComposerKernelRow
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The docked stack's column (CA §0.4, DS §5.7): gutters (16dp compact, 24dp wider), centred
 * in the 840dp transcript column, sitting on the IME or the bottom safe area with an 8dp
 * gap. Hosts put the approval/question banners (8dp gaps), the C8 notice (12dp gap) and the
 * [Composer] inside, top to bottom. When the stack is taller than the space left (height-compact
 * landscape with the IME open and no banner docked, C1.4 step 6) it stays bottom-aligned and the
 * overflow goes off the top: the composer the user is typing in never leaves the screen.
 *
 * [maxHeight] (height-compact with the IME closed, DS §5.1: the transcript stays the primary
 * content; and with the IME open while a banner docks, trial row 13) bounds the stack instead:
 * it scrolls inside the bound, starting at the composer, so a banner is never drawn under the
 * app bar or, while typing, under the status bar. The host picks the bound
 * (`DockRules.maxHeight`): room for transcript rows without a banner, the whole pane with one (D18).
 */
@Composable
fun ComposerDock(modifier: Modifier = Modifier, maxHeight: Dp = Dp.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val gutter = LocalShellLayout.current.gutter
    val bound = if (maxHeight != Dp.Unspecified) {
        Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState(), reverseScrolling = true)
    } else {
        Modifier.wrapContentHeight(align = Alignment.Bottom, unbounded = true)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = Spacing.contentMaxWidth)
                .fillMaxWidth()
                .then(bound)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.bottomSafe))
                .padding(start = gutter, end = gutter, bottom = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = content,
        )
    }
}

/**
 * The composer as the chat screen hosts it (C1): binds [controller] to [ComposerSurface],
 * shows its snackbars, owns the attach / usage sheets, the microphone
 * permission and the lifecycle rules (voice stops on ON_STOP and when the chat leaves).
 *
 * - [onModelChip] opens SP S8's model picker; [reasoningMenu] is SP S8's `ReasoningMenu`
 *   anchored to the chip (`expanded`, `onDismiss`).
 * - [suggestionPanel] (C5/C6) and [queueStrip] (C2) are the docked children drawn inside the
 *   surface above the field in §0.4 order, each followed by a divider here; only the queue
 *   strip is dropped in height-compact windows with the IME open (C1.4 step 6).
 * - [onPanelKey] lets the open suggestion panel take Up/Down/Enter/Tab/Esc first (C1.8).
 * - [announcer] is the conversation's single polite region (DS §8.1.4); null hosts one here.
 * - [triggers] holds the settings row's sheet triggers; the host passes its `model` requester
 *   to the model picker, and the attach and usage sheets here return focus to theirs.
 */
@Composable
fun Composer(
    controller: ComposerController,
    onModelChip: () -> Unit,
    onOpenImage: (DraftAttachment.Image) -> Unit,
    onConnectModel: () -> Unit,
    onOpenTools: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    reasoningMenu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = { _, _ -> },
    suggestionPanel: (@Composable () -> Unit)? = null,
    queueStrip: (@Composable () -> Unit)? = null,
    onPanelKey: ((KeyEvent) -> Boolean)? = null,
    suggestionCount: Int? = null,
    announcer: LiveRegionState? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    triggers: ComposerTriggerFocus = remember { ComposerTriggerFocus() },
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val kernelUi = LocalShellKernelUi.current
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    var reasoningOpen by rememberSaveable { mutableStateOf(false) }
    var focusWanted by remember { mutableStateOf(false) }
    var recentPromptsOpen by rememberSaveable { mutableStateOf(false) }
    val editor = rememberFullScreenEditorState()
    val editorAttach = remember { FocusRequester() }
    val ownAnnouncer = rememberLiveRegionState()
    val region = announcer ?: ownAnnouncer

    ComposerMessagesEffect(controller, state.sessionId, onConnectModel = onConnectModel, onOpenTools = onOpenTools)

    LaunchedEffect(configuration) { controller.setHardwareKeyboard(configuration.hasHardwareKeyboard()) }
    LaunchedEffect(controller) {
        snapshotFlow { controller.draft.text.toString() to controller.draft.selection }.collect { controller.onDraftEdited() }
    }
    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                // DS §5.7.4: the keyboard closes after a busy send. Neither `keyboard.hide()` nor a focus
                // change closes the IME of the state-based BasicTextField here, so the window's insets
                // controller hides it; the field is unfocused so a tap brings the keyboard back.
                ComposerEffect.HideKeyboard -> {
                    keyboard?.hide()
                    view.context.findActivity()?.window?.let { window ->
                        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                    }
                    focusManager.clearFocus()
                }
                ComposerEffect.RequestFocus -> focusWanted = true
                // A11.6: a tick per step, and the chip's words for TalkBack.
                is ComposerEffect.HistoryStepped -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    effect.browse?.let { region.announce(resources.getString(R.string.composer_history_chip, it.index, it.total)) }
                }
            }
        }
    }
    // A disabled field rejects or drops focus, and the field is disabled while a session loads (C1.3):
    // a request made then, such as edit-and-restart opening its new session (C.6), waits for it.
    LaunchedEffect(focusWanted, state.settled) {
        if (focusWanted && state.settled) {
            focusWanted = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    controller.voice?.let { voice ->
        LaunchedEffect(voice) {
            voice.events.collect { event ->
                if (event is VoiceEvent.Active) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (event.active) region.announce(resources.getString(R.string.voice_state_listening))
                }
            }
        }
    }
    // Polite announcements (C1.14): resuming, starting kernel, held note, inline image error.
    val holdText = when (state.holdReason) {
        HoldReason.ResumingSession -> stringResource(R.string.composer_resuming)
        HoldReason.HubDraining -> stringResource(R.string.composer_held_for_hub)
        else -> null
    }
    LaunchedEffect(holdText) { holdText?.let(region::announce) }
    val inlineError = stringResource(R.string.composer_images_unsupported_inline)
    LaunchedEffect(state.imagesUnsupported) { if (state.imagesUnsupported) region.announce(inlineError) }
    val startingKernel = stringResource(R.string.composer_kernel_starting)
    val kernelInFlight = kernelUi?.actionInFlight != null
    LaunchedEffect(kernelInFlight) { if (kernelInFlight && state.kernel.readOnly) region.announce(startingKernel) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.voice?.cancel() }
    DisposableEffect(controller) { onDispose { controller.voice?.cancel() } }
    OrderedBackHandler(BackLevel.InlineEdit, enabled = state.voice.active) { controller.stopVoice() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            controller.startVoice()
        } else {
            val activity = context as? Activity
            val permanently = activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            controller.onVoicePermissionDenied(permanently)
        }
    }
    val launchers = rememberAttachLaunchers(remainingImageSlots(state)) { refs -> controller.addAttachments(refs) }

    fun describeAndAdd(uris: List<Uri>) {
        scope.launch { controller.addAttachments(uris.map { describeAttachment(context, it) }) }
    }

    val kernelRow = kernelUi?.let { ComposerGate.of(it.gate, it.hubPort).row } ?: state.kernel.row
    val kernelAction = (kernelRow as? ComposerKernelRow.Down)?.action
    val kernelButton = kernelAction?.let { ComposerKernelButton(it, inFlight = kernelUi?.actionInFlight == it) }

    if (state.sessionDeleted) {
        SessionDeletedNotice(onNewSession, modifier)
    } else {
        ComposerSurface(
            state = state,
            draft = controller.draft,
            kernelRow = kernelRow,
            kernelButton = kernelButton,
            callbacks = ComposerCallbacks(
                onAttach = { attachOpen = true },
                onModeChange = controller::setMode,
                onModelChip = onModelChip,
                onReasoningChip = { reasoningOpen = true },
                onRing = { usageOpen = true },
                onSend = controller::send,
                onStop = controller::stop,
                onMic = {
                    when (state.voice) {
                        VoiceState.Idle -> if (micPermissionGranted(context)) controller.startVoice() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        is VoiceState.Listening, VoiceState.Processing -> controller.stopVoice()
                        VoiceState.Unavailable -> Unit
                    }
                },
                onRemoveAttachment = controller::removeAttachment,
                onOpenImage = onOpenImage,
                onImages = ::describeAndAdd,
                onDropped = ::describeAndAdd,
                onKernelAction = { kernelAction?.let { action -> kernelUi?.onAction?.invoke(action) } },
                keys = FieldKeys(onPanelKey, onEnter = controller::onHardwareEnter, onEscape = controller::onHardwareEscape),
                onSteer = controller::steer,
                history = FieldHistory(onStep = controller::historyStep, onTouch = controller::refreshHistory),
                onFieldFocused = controller::refreshHistory,
                onStopBrowsing = controller::stopHistoryBrowsing,
                onExpand = editor::open,
            ),
            ringOpen = usageOpen,
            focusRequester = focusRequester,
            modifier = modifier,
            suggestionPanel = suggestionPanel,
            queueStrip = queueStrip,
            suggestionCount = suggestionCount,
            reasoningMenu = { reasoningMenu(reasoningOpen) { reasoningOpen = false } },
            triggers = triggers,
        )
    }
    if (announcer == null) LiveRegionAnnouncer(ownAnnouncer)

    // B9 first, so the attach sheet it opens is a later window, drawn above it.
    if (editor.isOpen && !state.sessionDeleted) {
        FullScreenEditor(
            state = state,
            draft = controller.draft,
            attachFocus = editorAttach,
            actions = FullScreenEditorActions(
                onClose = {
                    editor.close()
                    focusWanted = true
                },
                onSend = { editor.send(state.canSend, controller::send) },
                onAttach = { attachOpen = true },
                onRemoveAttachment = controller::removeAttachment,
                // The lightbox is drawn in the activity window, under this dialog: close first (the text stays).
                onOpenImage = { image ->
                    editor.close()
                    onOpenImage(image)
                },
                keys = FieldKeys(
                    onPanelKey = null,
                    onEnter = { editor.enter(state.canSend, controller::onHardwareEnter) },
                    onEscape = {
                        editor.close()
                        focusWanted = true
                        true
                    },
                ),
            ),
        )
    }
    LaunchedEffect(attachOpen) { if (attachOpen) controller.refreshHistory() }
    if (attachOpen) {
        val imagesSupported = state.model.supportsImages != false
        AttachSheet(
            imagesSupported = imagesSupported,
            onDismiss = { attachOpen = false },
            returnFocus = if (editor.isOpen) editorAttach else triggers.attach,
            recentPrompts = controller.hasHistory,
            onChoice = { choice ->
                val remaining = remainingImageSlots(state)
                when (choice) {
                    AttachChoice.Photos, AttachChoice.Camera -> when {
                        !imagesSupported -> controller.report(ComposerMessage.ImagesUnsupported)
                        remaining == 0 -> controller.report(ComposerMessage.TooManyImages)
                        choice == AttachChoice.Photos -> launchers.photos(remaining)
                        else -> launchers.camera()
                    }
                    AttachChoice.Files -> launchers.files()
                    AttachChoice.RecentPrompts -> recentPromptsOpen = true
                }
            },
        )
    }
    if (recentPromptsOpen) {
        val history by controller.recentPrompts.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { controller.refreshHistory() }
        RecentPromptsSheet(
            history = history,
            onPick = controller::pickRecentPrompt,
            onDismiss = { recentPromptsOpen = false },
            returnFocus = if (editor.isOpen) editorAttach else triggers.attach,
        )
    }
    val usage = state.usage
    if (usageOpen && usage != null) {
        ContextUsageSheet(usage, onDismiss = { usageOpen = false }, returnFocus = triggers.ring)
    } else if (usageOpen) {
        // The ring hid (unknown window after a model switch): the sheet closes with it.
        LaunchedEffect(Unit) { usageOpen = false }
    }
}

private fun micPermissionGranted(context: android.content.Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Every callback of the stateless surface. */
class ComposerCallbacks(
    val onAttach: () -> Unit,
    val onModeChange: (dev.cline.termux.data.session.PromptMode) -> Unit,
    val onModelChip: () -> Unit,
    val onReasoningChip: () -> Unit,
    val onRing: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onMic: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onOpenImage: (DraftAttachment.Image) -> Unit,
    val onImages: (List<Uri>) -> Unit,
    val onDropped: (List<Uri>) -> Unit,
    val onKernelAction: () -> Unit,
    val keys: FieldKeys,
    /** Long-press Send while a run is active: steer (A11 row 5). */
    val onSteer: () -> Unit = {},
    /** A11.6: the field's history fling and TalkBack actions. */
    val history: FieldHistory = FieldHistory.None,
    /** The field gained focus (A11.6 refreshes the recent prompts). */
    val onFieldFocused: () -> Unit = {},
    /** A11.6: the browsing chip's ×. */
    val onStopBrowsing: () -> Unit = {},
    /** B9: the field's expand icon; null = no full-screen editor. */
    val onExpand: (() -> Unit)? = null,
) {
    companion object {
        val None = ComposerCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, FieldKeys(null, { false }, { false }))
    }
}

/** The docked children `ComposerSurface` draws above the field (§0.4 items 3 and 4). */
enum class DockedItem { SuggestionPanel, QueueStrip }

/**
 * The docked children to compose, in §0.4 order: the suggestion panel whenever it is
 * present, the queue strip unless the window is height-compact with the IME open
 * (C1.4 step 6).
 */
/**
 * C1.4 step 6 / C1.15 item 17 (docs/07 D18): on a height-compact window with the IME open the
 * field is capped at 2 lines (3 without the IME, 5 elsewhere), so the approval banner and a
 * transcript row stay above a keyboard that takes over half of a phone-landscape window.
 */
internal fun composerMaxLines(heightCompact: Boolean, imeOpen: Boolean): Int = when {
    heightCompact && imeOpen -> 2
    heightCompact -> 3
    else -> 5
}

/**
 * The field's minimum height: 2 lines on the Welcome composer or while focused, else 1. With
 * the IME open on a height-compact window it starts at 1 line and grows to the 2-line cap as
 * the draft does (D18): an empty 2-line field there left no transcript row above it on the S25U.
 */
internal fun composerMinLines(welcome: Boolean, focused: Boolean, heightCompact: Boolean, imeOpen: Boolean): Int = when {
    heightCompact && imeOpen -> 1
    welcome || focused -> 2
    else -> 1
}

/** D18: the settings chip row is hidden while the IME is open on a height-compact window. */
internal fun showsSettingsRow(heightCompact: Boolean, imeOpen: Boolean): Boolean = !(heightCompact && imeOpen)

/**
 * D18: Send and Stop live in the settings row; while that row is hidden for the IME they move
 * to the end of the field row, so a draft can still be sent (and a run stopped) by touch.
 */
internal fun sendInFieldRow(kernelRowNormal: Boolean, heightCompact: Boolean, imeOpen: Boolean): Boolean =
    kernelRowNormal && !showsSettingsRow(heightCompact, imeOpen)

internal fun dockedItems(hasSuggestionPanel: Boolean, hasQueueStrip: Boolean, heightCompact: Boolean, imeOpen: Boolean): List<DockedItem> =
    buildList {
        if (hasSuggestionPanel) add(DockedItem.SuggestionPanel)
        if (hasQueueStrip && !(heightCompact && imeOpen)) add(DockedItem.QueueStrip)
    }

/**
 * C1.3 ComposerSurface: the only rounded, bordered surface (`surfaceContainerHigh`, 1dp
 * `outlineVariant`, `primary` while focused) holding the docked children, the held note,
 * the attachment chips, the inline image error, the field row and the settings row (or the
 * kernel row that replaces it). Stateless, so every state previews.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerSurface(
    state: ComposerUiState,
    draft: TextFieldState,
    kernelRow: ComposerKernelRow,
    kernelButton: ComposerKernelButton?,
    callbacks: ComposerCallbacks,
    ringOpen: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    suggestionPanel: (@Composable () -> Unit)? = null,
    queueStrip: (@Composable () -> Unit)? = null,
    suggestionCount: Int? = null,
    reasoningMenu: @Composable () -> Unit = {},
    imeOpen: Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
    triggers: ComposerTriggerFocus? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val layout = LocalShellLayout.current
    val heightCompact = layout.heightCompact
    val compact = layout.width == WidthClass.Compact
    val context = LocalContext.current
    val dropTarget = remember(callbacks) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                (context as? Activity)?.requestDragAndDropPermissions(drag)
                val uris = drag.clipData?.uris().orEmpty()
                if (uris.isEmpty()) return false
                callbacks.onDropped(uris)
                return true
            }
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().isNotEmpty() && !state.kernel.readOnly }, target = dropTarget),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            // §0.4 items 3 and 4; height-compact windows with the IME open drop only the queue strip (C1.4 step 6).
            dockedItems(suggestionPanel != null, queueStrip != null, heightCompact, imeOpen).forEach { item ->
                when (item) {
                    DockedItem.SuggestionPanel -> suggestionPanel?.invoke()
                    DockedItem.QueueStrip -> queueStrip?.invoke()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.holdReason == HoldReason.HubDraining) {
                Text(
                    stringResource(R.string.composer_held_for_hub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.attachments.isNotEmpty()) {
                AttachmentChips(state.attachments, enabled = !state.readOnly, callbacks.onRemoveAttachment, callbacks.onOpenImage)
            }
            state.history?.let { browse -> HistoryBrowseChip(browse, enabled = !state.readOnly, onStop = callbacks.onStopBrowsing) }
            if (state.imagesUnsupported) {
                Text(
                    stringResource(R.string.composer_images_unsupported_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ComposerFieldRow(
                state = state,
                draft = draft,
                focused = focused,
                heightCompact = heightCompact,
                imeOpen = imeOpen,
                keys = callbacks.keys,
                onImages = callbacks.onImages,
                onFocusChanged = {
                    if (it && !focused) callbacks.onFieldFocused()
                    focused = it
                },
                onMic = callbacks.onMic,
                focusRequester = focusRequester,
                suggestionCount = suggestionCount,
                history = callbacks.history,
                onExpand = callbacks.onExpand,
                trailing = if (sendInFieldRow(kernelRow == ComposerKernelRow.Normal, heightCompact, imeOpen)) {
                    { SendStopGroup(state, callbacks.onSend, callbacks.onStop, callbacks.onSteer) }
                } else {
                    null
                },
            )
            if (sendInFieldRow(kernelRow == ComposerKernelRow.Normal, heightCompact, imeOpen)) {
                // D18: nothing under the field; Send/Stop sit at its end.
            } else if (kernelRow == ComposerKernelRow.Normal && showsSettingsRow(heightCompact, imeOpen)) {
                ComposerSettingsRow(
                    state = state,
                    compact = compact,
                    ringOpen = ringOpen,
                    actions = SettingsRowActions(
                        onAttach = callbacks.onAttach,
                        onModeChange = callbacks.onModeChange,
                        onModelChip = callbacks.onModelChip,
                        onReasoningChip = callbacks.onReasoningChip,
                        onRing = callbacks.onRing,
                        onSend = callbacks.onSend,
                        onStop = callbacks.onStop,
                        onSteer = callbacks.onSteer,
                    ),
                    reasoningMenu = reasoningMenu,
                    triggers = triggers,
                )
            } else {
                ComposerKernelRowContent(kernelRow, kernelButton, callbacks.onKernelAction)
            }
        }
    }
}

private fun ClipData.uris(): List<Uri> = (0 until itemCount).mapNotNull { getItemAt(it).uri }

/**
 * A11.6 browsing chip, compact since A18: "Recent prompt n of N" in `labelMedium` on a 24dp
 * `surfaceContainerHighest` surface (the colour of the app's outline-free chips), with no
 * outline and no leading icon (the `history` glyph duplicated Restore checkpoint), and a
 * trailing × ("Stop browsing") that leaves browsing and clears the field. The chip itself is a
 * label, not a button. The × keeps its 48dp target, so the chip takes one 48dp row with no gap
 * above it (DS §3.4).
 */
@Composable
private fun HistoryBrowseChip(browse: HistoryBrowse, enabled: Boolean, onStop: () -> Unit) {
    val label = stringResource(R.string.composer_history_chip, browse.index, browse.total)
    Box(
        Modifier
            .padding(horizontal = Spacing.l)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            shape = AssistChipDefaults.shape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = attachmentChipContentColor(enabled),
        ) {
            Row(
                Modifier.heightIn(min = HistoryChipHeight).padding(start = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // weight(fill = false): a label squeezed by a narrow window or a large font
                // ellipsizes instead of pushing the × slot out of the chip.
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The ×'s slot, flush with the chip's end: as tall as the chip, so the 16dp glyph
                // sits 4dp clear of the chip's edges and the 24dp ripple fills the chip's end.
                Spacer(Modifier.size(AttachmentRemoveSlot))
            }
        }
        AttachmentRemoveButton(
            contentDescription = stringResource(R.string.composer_history_cd_stop),
            enabled = enabled,
            onClick = onStop,
            iconSize = HistoryChipCloseIconSize,
            // Centred on the slot: half the 24dp slot from the chip's end, so the 48dp button
            // reaches 12dp past it (inside the row's 16dp end padding).
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = AttachmentRemoveSlot / 2),
        )
    }
}

/** A18's compact browsing chip: 24dp tall, against the 32dp of an M3 chip. */
private val HistoryChipHeight = 24.dp

/** The compact chip's × glyph, two dp under the input chips' 18dp. */
private val HistoryChipCloseIconSize = 16.dp

/** C1.7 attachment chips: thumbnail or file icon, middle-ellipsized name, remove button (also the B9 editor's). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentChips(
    attachments: List<DraftAttachment>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onOpenImage: (DraftAttachment.Image) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        var imageIndex = 0
        attachments.forEach { attachment ->
            val index = if (attachment is DraftAttachment.Image) ++imageIndex else 0
            AttachmentChip(attachment, index, enabled, onRemove, onOpenImage)
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: DraftAttachment,
    imageIndex: Int,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onOpenImage: (DraftAttachment.Image) -> Unit,
) {
    val removeCd = stringResource(R.string.composer_cd_remove_attachment, attachment.displayName)
    val writing = attachment is DraftAttachment.TextFile && attachment.state == FileAttachState.Writing
    val writingCd = stringResource(R.string.composer_cd_attachment_writing, attachment.displayName)
    val imageCd = if (attachment is DraftAttachment.Image) stringResource(R.string.attach_cd_attachment_image, imageIndex) else null
    val label: @Composable () -> Unit = {
        // chipLabel caps the grapheme count; the width cap catches wide scripts (24 CJK
        // ideographs are about twice 24 Latin letters) and large font scales, cutting in the
        // middle like chipLabel so the extension stays visible (DS §2.3).
        Text(
            Attachments.chipLabel(attachment.displayName),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.widthIn(max = AttachmentChipLabelMaxWidth),
        )
    }
    // The remove button is the chip's sibling, drawn over the chip's reserved trailing slot: inside
    // the chip its 48dp target and semantic bounds would be clipped to the chip's 32dp shape
    // (DS §3.4, §8.1.2). The box is 48dp tall like a clickable M3 chip's own layout.
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        if (attachment is DraftAttachment.Image) {
            // Tap opens the lightbox over the draft image (C1.7).
            InputChip(
                selected = false,
                onClick = { onOpenImage(attachment) },
                enabled = enabled,
                label = label,
                // A leading icon, not `avatar`: M3 clips avatars to a circle; the thumbnail is `extraSmall` (C1.7).
                leadingIcon = {
                    if (attachment.thumbnail != null) {
                        Image(
                            bitmap = attachment.thumbnail,
                            contentDescription = imageCd,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall),
                        )
                    } else {
                        Icon(ClineIcons.Image, contentDescription = imageCd, modifier = Modifier.size(18.dp))
                    }
                },
                trailingIcon = { Spacer(Modifier.size(AttachmentRemoveSlot)) },
            )
        } else {
            // A text file opens nothing, so its chip is not a clickable node (TalkBack offers no
            // "double-tap to activate"); it reads the full name and the writing state.
            StaticAttachmentChip(
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = attachment.displayName
                    if (writing) stateDescription = writingCd
                },
                leadingIcon = {
                    if (writing) {
                        InlineSpinner()
                    } else {
                        Icon(ClineIcons.Description, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
                    }
                },
                label = label,
            )
        }
        AttachmentRemoveButton(
            contentDescription = removeCd,
            enabled = enabled,
            onClick = { onRemove(attachment.id) },
            // Centred on the chip's trailing slot: 8dp end padding + half the 24dp slot = 20dp from
            // the chip's end, so the 48dp button reaches 4dp past it (inside the 8dp chip gap).
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 4.dp),
        )
    }
}

/** The trailing slot both chip kinds reserve for [AttachmentRemoveButton]'s 24dp visual. */
private val AttachmentRemoveSlot = 24.dp

/**
 * The widest an attachment chip's label draws: about 24 Latin graphemes at `labelLarge`, and
 * narrow enough that a chip (icon, label, remove slot, 8dp paddings) fits a 320dp compact row.
 */
private val AttachmentChipLabelMaxWidth = 200.dp

/**
 * An unselected `InputChip`'s look without its click action (C1.7 file chips): 32dp, the chip
 * shape and border, 8dp padding, the leading icon, the label and the reserved trailing slot.
 */
@Composable
private fun StaticAttachmentChip(
    enabled: Boolean,
    modifier: Modifier,
    leadingIcon: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    // An unselected InputChip has no container (transparent), only its outline.
    Surface(
        modifier = modifier,
        shape = InputChipDefaults.shape,
        color = Color.Transparent,
        contentColor = attachmentChipContentColor(enabled),
        border = InputChipDefaults.inputChipBorder(enabled = enabled, selected = false),
    ) {
        Row(
            Modifier.heightIn(min = InputChipDefaults.Height).padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon()
            // weight(fill = false): the remove slot is measured first, so a label squeezed by
            // the row's width ellipsizes instead of pushing the slot out of the chip.
            Box(Modifier.weight(1f, fill = false).padding(horizontal = Spacing.s)) { label() }
            Spacer(Modifier.size(AttachmentRemoveSlot))
        }
    }
}

/**
 * The unselected InputChip's label and icon colour (M3 input-chip tokens: `onSurfaceVariant`,
 * disabled `onSurface` at 38 %), for the parts drawn outside an `InputChip` and for the
 * compact history chip.
 */
@Composable
private fun attachmentChipContentColor(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

/**
 * C1.7's trailing `close` button ("Remove <name>"): a 24dp visual (18dp glyph, 24dp ripple) on
 * a 48dp clickable node, so its touch target and its accessibility bounds are both 48dp
 * (DS §3.4, §8.1.2). The compact history chip passes a 16dp [iconSize].
 */
@Composable
private fun AttachmentRemoveButton(
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = InputChipDefaults.IconSize,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .focusRing(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = AttachmentRemoveSlot / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            ClineIcons.Close,
            contentDescription = null,
            tint = attachmentChipContentColor(enabled),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** C1.6 "Session deleted": the voice app's NoticeBar pattern replaces the composer. */
@Composable
fun SessionDeletedNotice(onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.composer_session_deleted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onNewSession) { Text(stringResource(R.string.composer_action_new_session)) }
        }
    }
}
