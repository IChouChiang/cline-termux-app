package dev.cline.termux.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.cline.termux.R
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.LocalIsDarkTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.first

/**
 * B9's open/close/send rules, kept out of the composable so they test on the JVM. The editor
 * edits the composer's own `TextFieldState` (no copy), so closing it never loses text: the
 * field below shows the same text.
 */
@Stable
class FullScreenEditorState(initiallyOpen: Boolean = false) {
    var isOpen by mutableStateOf(initiallyOpen)
        private set

    /** The field's expand icon. */
    fun open() {
        isOpen = true
    }

    /** Close, Back and Esc: the text stays in the composer. */
    fun close() {
        isOpen = false
    }

    /** The top bar's Send: sends as the composer's Send would and closes; nothing to send = stays open. */
    fun send(canSend: Boolean, send: () -> Unit): Boolean {
        if (!canSend) return false
        send()
        isOpen = false
        return true
    }

    /** Hardware Enter: does what the composer's Enter does ([onEnter]) and closes when that sent the draft. */
    fun enter(canSend: Boolean, onEnter: () -> Boolean): Boolean {
        val consumed = onEnter()
        if (canSend) isOpen = false
        return consumed
    }

    companion object {
        val Saver: Saver<FullScreenEditorState, Boolean> = Saver(save = { it.isOpen }, restore = { FullScreenEditorState(it) })
    }
}

@Composable
fun rememberFullScreenEditorState(): FullScreenEditorState = rememberSaveable(saver = FullScreenEditorState.Saver) { FullScreenEditorState() }

/** The full-screen editor's callbacks (B9). */
class FullScreenEditorActions(
    val onClose: () -> Unit,
    val onSend: () -> Unit,
    val onAttach: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onOpenImage: (DraftAttachment.Image) -> Unit,
    /** Hardware Enter / Shift+Enter / Esc, as in the composer field (C1.8). */
    val keys: FieldKeys,
)

/**
 * B9: the composer's draft in a Material full-screen dialog. The top bar holds close (returns
 * to the composer with the text), attach and Send (enabled as in the composer; sends and
 * closes); the attachment chips sit under it; the body is the composer's own [draft] state, so
 * nothing is copied and nothing is lost. Back closes; the body rides the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditor(
    state: ComposerUiState,
    draft: TextFieldState,
    actions: FullScreenEditorActions,
    attachFocus: FocusRequester? = null,
) {
    Dialog(
        onDismissRequest = actions.onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EditorSystemBarIcons()
        val title = stringResource(R.string.composer_editor_title)
        val focus = remember { FocusRequester() }
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { paneTitle = title },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        TooltipIconButton(
                            icon = ClineIcons.Close,
                            contentDescription = stringResource(R.string.composer_editor_cd_close),
                            onClick = actions.onClose,
                        )
                    },
                    actions = {
                        val attach = stringResource(R.string.composer_cd_attach)
                        TooltipIconButton(
                            icon = ClineIcons.AttachFile,
                            contentDescription = attach,
                            tooltip = stringResource(if (state.model.supportsImages == false) R.string.composer_tt_attach_no_images else R.string.composer_tt_attach),
                            onClick = actions.onAttach,
                            enabled = !state.loading && !state.kernel.readOnly,
                            modifier = if (attachFocus != null) Modifier.focusRequester(attachFocus) else Modifier,
                        )
                        EditorSendButton(enabled = state.canSend, onClick = actions.onSend)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .consumeWindowInsets(inner)
                    .imePadding(),
            ) {
                if (state.attachments.isNotEmpty()) {
                    AttachmentChips(state.attachments, enabled = !state.readOnly, actions.onRemoveAttachment, actions.onOpenImage)
                }
                val placeholder = composerPlaceholder(state, draft.text.isEmpty())
                BasicTextField(
                    state = draft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event -> handleComposerKey(event, draft, actions.keys) },
                    enabled = !state.loading,
                    readOnly = state.readOnly,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
                    decorator = { inner ->
                        Box {
                            if (draft.text.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        // Focus goes to the field at once (with a hardware keyboard the dialog would otherwise focus
        // the close button first); the keyboard is asked for once the dialog window holds window
        // focus, since a request before that is dropped.
        val window = LocalWindowInfo.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        LaunchedEffect(window, keyboard) {
            snapshotFlow { window.isWindowFocused }.first { it }
            keyboard?.show()
        }
    }
}

/** Send in the editor's top bar: the composer's filled primary arrow (C1.10 copy). */
@Composable
private fun EditorSendButton(enabled: Boolean, onClick: () -> Unit) {
    val cd = stringResource(R.string.composer_cd_send)
    PlainTooltipBox(text = stringResource(R.string.composer_tt_send)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.focusRing(CircleShape).size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Icon(ClineIcons.ArrowUpward, contentDescription = cd, modifier = Modifier.size(20.dp)) }
        }
    }
}

/**
 * The dialog window draws behind the system bars (`decorFitsSystemWindows = false`), so its
 * status and navigation bar icons follow the editor's surface: dark icons on a light theme.
 */
@Composable
private fun EditorSystemBarIcons() {
    val view = LocalView.current
    val dark = LocalIsDarkTheme.current
    DisposableEffect(view, dark) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
}
