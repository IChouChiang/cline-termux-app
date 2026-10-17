package dev.cline.termux.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * KS 0.7 CommandBlock (onboarding-kernel.md §0.7), the one implementation for every screen that
 * shows a command to run (kernel setup, Background reliability, the Hub update dialog).
 *
 * The exact command in `surfaceContainer`, monospace, selectable, forced LTR, horizontally
 * scrollable and read as written. When [copyable], a trailing copy button (CD "Copy command"
 * → "Copied command" with `content_copy` → `check` for 2 s) writes
 * `ClipData.newPlainText("command", …)` with exactly the command, no trailing newline (A.6),
 * and shows the snackbar "Command copied" below API 33, where the system shows no clipboard
 * confirmation of its own. [maxLines] caps the lines of a multi-line command; the block
 * scrolls sideways instead of wrapping.
 */
@Composable
fun CommandBlock(
    command: String,
    modifier: Modifier = Modifier,
    copyable: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    var copied by remember { mutableStateOf(false) }
    val copier = rememberCopier(legacyConfirmation = stringResource(R.string.setup_command_copied))
    LaunchedEffect(copied) {
        if (copied) {
            delay(COMMAND_COPIED_FEEDBACK_MS)
            copied = false
        }
    }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                LtrText(
                    text = command,
                    style = ClineMono.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = maxLines,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.m, vertical = Spacing.m),
                )
            }
            if (copyable) {
                IconButton(
                    onClick = { copier.copy(COMMAND_CLIP_LABEL, commandClipText(command)) { ok -> if (ok) copied = true } },
                    modifier = Modifier.focusRing(CircleShape),
                ) {
                    Icon(
                        if (copied) ClineIcons.Check else ClineIcons.ContentCopy,
                        contentDescription = stringResource(if (copied) R.string.setup_copied_command else R.string.setup_copy_command),
                    )
                }
            }
        }
    }
}

/** The clipboard text for [command]: exactly the command, without trailing newlines (A.6). */
internal fun commandClipText(command: String): String = command.trimEnd('\n')

/** `ClipDescription` label of a copied command (KS 0.7). */
internal const val COMMAND_CLIP_LABEL = "command"

/** How long the copy button shows `check` / "Copied command" (KS 0.7: 2 s). */
internal const val COMMAND_COPIED_FEEDBACK_MS = 2_000L

@LightDarkPreviews
@Composable
private fun CommandBlockPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CommandBlock("curl -fsSL https://github.com/IChouChiang/cline-termux/releases/latest/download/install-cline-termux.sh | bash", maxLines = 1)
            CommandBlock("adb shell settings put global settings_enable_monitor_phantom_procs false")
            CommandBlock("mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings", copyable = false)
        }
    }
}
