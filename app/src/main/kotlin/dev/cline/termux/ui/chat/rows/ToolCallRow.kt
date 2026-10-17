package dev.cline.termux.ui.chat.rows

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import dev.cline.termux.R
import dev.cline.termux.core.kernel.termux.stripAnsi
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.tools.ToolActivityStatus
import dev.cline.termux.data.tools.ToolRowModel
import dev.cline.termux.ui.components.AnchoredContextMenu
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.rememberCopyQuantityResources
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.quantityResources
import dev.cline.termux.ui.markdown.MarkdownText
import dev.cline.termux.ui.terminal.CommandOutput
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/*
 * Tool rows (HC §3.4.4, DS §4.1 #2; desktop `tool-message-block.tsx` `ToolCallRow` and
 * `ToolMessageBlock`, `agent-chat` `ToolActivity*`). The row rules (status, label,
 * expandability, proceed) are the data-layer port `buildToolRowModel`; this file draws them
 * with the compact [DisclosureRowTokens] (A24): the icon slot (the spinner takes it while
 * running, so the label never moves), the label with mono code parts, `+N -N` trailing and no
 * chevron, and the body under the rail in desktop order. Every row starts folded and opens
 * only when tapped (A23: the desktop's auto-open rule is dropped).
 */

/**
 * Consecutive tool calls (one `Tools` item): rows with 0dp gaps (DS §3.3). Each row's model
 * comes ready from the row's facts (built off Main by the transcript pipeline).
 */
@Composable
fun ToolMessageBlock(
    messages: List<ChatMessage>,
    row: TranscriptRow,
    callbacks: TranscriptCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        messages.forEach { message ->
            val facts = row.factsOf(message.id).tool ?: return@forEach
            androidx.compose.runtime.key(message.renderKey) {
                ToolCallRow(facts = facts, callbacks = callbacks)
            }
        }
    }
}

/**
 * One tool call from its ready [facts] (the model and its output media, built off Main).
 * Folded until the user taps it (A23), whatever its body holds (a diff, media, live output,
 * the submit text); the open state is saved per row (`renderKey`), so a row the user opened
 * stays open while its output grows and the run ends. [initiallyOpen] is for previews only.
 */
@Composable
fun ToolCallRow(
    facts: ToolRowFacts,
    callbacks: TranscriptCallbacks,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    val model = facts.modelFor(proceedAvailable = callbacks.onProceedWhileRunning != null)
    val message = model.presentation.message
    var open by rememberSaveable(message.renderKey) { mutableStateOf(initiallyOpen) }
    val toggled = LocalDisclosureToggled.current
    val toggle = {
        if (model.expandable) {
            toggled?.invoke()
            open = !open
        }
    }

    val label = toolLabelText(model)
    val statusWord = stringResource(
        when (model.status) {
            ToolActivityStatus.Error -> R.string.chat_tool_status_error
            ToolActivityStatus.Running, ToolActivityStatus.Pending -> R.string.chat_tool_status_running
            ToolActivityStatus.Success -> R.string.chat_tool_status_done
            ToolActivityStatus.Interrupted -> R.string.chat_tool_status_interrupted
        },
    )
    val resources = LocalContext.current.resources
    val quantity = rememberCopyQuantityResources()
    val description = remember(model.label, statusWord, model.additions, model.deletions, resources, quantity) {
        // Plain label (no code spans, no bidi isolates) for TalkBack.
        val plain = resolveToolLabel(model.label, resources, code = null, quantity = quantity).text
        toolRowDescription(resources, plain, statusWord, model.additions, model.deletions, quantity = quantity)
    }

    val copier = rememberPlainCopier()
    val haptic = rememberLongPressHaptic()
    val press = remember { PressPoint() }
    var menuOpen by remember { mutableStateOf(false) }
    val menuItems = toolMenuItems(model, callbacks, copier::copy)

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.trackPress(press)) {
            DisclosureHeader(
                expanded = if (model.expandable) open else null,
                onToggle = toggle,
                contentDescription = description,
                menuItems = menuItems,
                onLongPress = if (menuItems.isEmpty()) null else { { haptic(); menuOpen = true } },
            ) {
                ToolRowHeaderContent(model, label)
            }
            AnchoredContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = menuItems,
                offset = press.offset,
            )
        }
        if (model.expandable) {
            DisclosureBody(visible = open) {
                ToolRowBody(model, facts.media, callbacks)
            }
        }
    }
}

/** The row label with mono code spans (`submit_and_exit` carries the milestone phrase). */
@Composable
private fun toolLabelText(model: ToolRowModel): AnnotatedString {
    val mono = DisclosureRowTokens.labelCodeStyle
    val code = remember(mono) { monoSpan(mono) }
    return toolLabelText(model.label, code)
}

/**
 * DS §8.1.5 "full label + status", plus the trailing `+N` / `-N` counts the header shows in
 * colour (DS §8.1.3: colour is never the only signal). The counts pick their plural form with
 * [quantity] (`rememberCopyQuantityResources`), the locale of the copy, not the device's.
 */
internal fun toolRowDescription(
    resources: Resources,
    label: String,
    status: String,
    additions: Int?,
    deletions: Int?,
    quantity: Resources = quantityResources(resources),
): String {
    val added = additions?.let { quantity.getQuantityString(R.plurals.changes_additions_a11y, it, it) }
    val removed = deletions?.let { quantity.getQuantityString(R.plurals.changes_deletions_a11y, it, it) }
    return when {
        added != null && removed != null -> resources.getString(R.string.chat_row_label_status_diff, label, status, added, removed)
        added != null -> resources.getString(R.string.chat_row_label_status_count, label, status, added)
        removed != null -> resources.getString(R.string.chat_row_label_status_count, label, status, removed)
        else -> resources.getString(R.string.chat_row_label_status, label, status)
    }
}

@Composable
private fun RowScope.ToolRowHeaderContent(model: ToolRowModel, label: AnnotatedString) {
    val contentColor = if (model.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        DisclosureIconSlot {
            when {
                model.status.inFlight -> DisclosureSpinner(LocalContentColor.current)
                model.hasError -> Icon(ClineIcons.Tools.Error, contentDescription = null, modifier = Modifier.size(DisclosureRowTokens.IconSize))
                // A21: the tool detail's Interrupted glyph and colour.
                model.isInterrupted -> Icon(
                    ClineIcons.Warning,
                    contentDescription = null,
                    tint = ClineTheme.colors.warning.color,
                    modifier = Modifier.size(DisclosureRowTokens.IconSize),
                )
                else -> Icon(
                    ImageVector.vectorResource(ClineIcons.drawableOf(model.icon)),
                    contentDescription = null,
                    modifier = Modifier.size(DisclosureRowTokens.IconSize),
                )
            }
        }
        ShimmerText(
            text = label,
            shimmer = model.isRunning,
            color = contentColor,
            modifier = Modifier.weight(1f),
            style = DisclosureRowTokens.labelStyle,
        )
        if (model.additions != null || model.deletions != null) {
            // DS §8.2.6: diff counts stay "+N -N" (LTR) while the row around them mirrors.
            LtrBlock {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    model.additions?.let {
                        Text(stringResource(R.string.diff_additions_count, it), style = ClineMono.small, color = ClineTheme.colors.success.color)
                    }
                    model.deletions?.let {
                        Text(stringResource(R.string.diff_deletions_count, it), style = ClineMono.small, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** The body in desktop order: details, diffs, output, images, media, input (debug), error, proceed, details link. */
@Composable
private fun ToolRowBody(model: ToolRowModel, media: List<MediaPresentation>, callbacks: TranscriptCallbacks) {
    val message = model.presentation.message
    if (model.details.isNotEmpty()) {
        // Commands are selectable in place (DS §8.1.11, HC §3.14); LtrText keeps reading the
        // command as written for TalkBack.
        SelectionContainer {
            Column(Modifier.padding(top = Spacing.xs)) {
                model.details.forEach { detail ->
                    if (model.isCommand) {
                        LtrText(
                            text = "$ $detail",
                            style = ClineMono.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    model.fileDiffs.forEachIndexed { index, diff ->
        ToolFileDiffView(diff = diff, index = index, count = model.fileDiffs.size)
    }
    when {
        model.commandOutput.isNotEmpty() -> {
            val copier = rememberPlainCopier()
            val copyLabel = stringResource(R.string.tool_copy_output)
            CommandOutput(
                output = model.commandOutput,
                isRunning = model.isRunning,
                onCopyOutput = { text -> copier.copy(copyLabel, text) },
            )
        }
        model.submitText.isNotEmpty() -> MarkdownText(
            markdown = model.submitText,
            modifier = Modifier.padding(top = Spacing.xs),
            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        !model.outputText.isNullOrEmpty() -> MonoBlock(model.outputText!!)
    }
    if (model.outputImages.isNotEmpty()) {
        GeneratedImages(
            cacheKeyPrefix = "${message.id}:tool",
            images = model.outputImages,
            onExpand = callbacks.onExpandImage,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
    if (media.isNotEmpty()) {
        GeneratedMediaPresentations(
            presentations = media,
            cacheKeyPrefix = "${message.id}:tool",
            onExpandImage = callbacks.onExpandImage,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
    if (model.inputPreview.isNotEmpty()) {
        Text(
            text = stringResource(R.string.chat_tool_overline_input).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s),
        )
        MonoBlock(model.inputPreview)
    }
    model.errorText?.takeIf { it.isNotEmpty() }?.let { error ->
        SelectionContainer {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
    if (model.canProceed) ProceedWhileRunning(model, callbacks)
    val toolCallId = message.meta.toolCallId
    val openDetail = callbacks.onOpenToolDetail
    if (toolCallId != null && openDetail != null && message.sessionId.isNotEmpty()) {
        TextButton(onClick = { openDetail(message.sessionId, toolCallId) }) {
            Text(stringResource(R.string.chat_tool_view_details))
        }
    }
}

/**
 * "Proceed while running" (desktop `handleProceedWhileRunning`): row-local pending and error.
 * [initiallyPending] and [initialError] seed that state for previews only.
 */
@Composable
internal fun ProceedWhileRunning(
    model: ToolRowModel,
    callbacks: TranscriptCallbacks,
    initiallyPending: Boolean = false,
    initialError: String? = null,
) {
    val message = model.presentation.message
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(initiallyPending) }
    var error by remember { mutableStateOf(initialError) }
    val fallback = stringResource(R.string.chat_tool_proceed_error)
    val finished = stringResource(R.string.chat_tool_proceed_finished)
    Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedButton(
            enabled = !pending,
            onClick = {
                val proceed = callbacks.onProceedWhileRunning ?: return@OutlinedButton
                pending = true
                error = null
                scope.launch {
                    val outcome = runCatching { proceed(message.sessionId, message.meta.toolCallId) }
                        .getOrElse { ProceedOutcome.Failed(it.message) }
                    error = when (outcome) {
                        is ProceedOutcome.Detached -> null
                        ProceedOutcome.NothingDetached -> finished
                        is ProceedOutcome.Failed -> outcome.message?.takeIf { it.isNotBlank() } ?: fallback
                    }
                    pending = false
                }
            },
        ) {
            if (pending) {
                InlineSpinner()
                Box(Modifier.size(Spacing.s))
            }
            Text(stringResource(R.string.chat_tool_proceed))
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** CT DS-A5: "View details", "Copy command", "Copy output" on long-press and as TalkBack actions. */
@Composable
private fun toolMenuItems(
    model: ToolRowModel,
    callbacks: TranscriptCallbacks,
    copy: (label: String, text: String) -> Unit,
): List<ContextMenuItem> {
    val message = model.presentation.message
    val viewDetails = stringResource(R.string.chat_tool_view_details)
    val copyCommand = stringResource(R.string.chat_tool_copy_command)
    val copyOutput = stringResource(R.string.tool_copy_output)
    return remember(model, callbacks, viewDetails, copyCommand, copyOutput) {
        buildList {
            val toolCallId = message.meta.toolCallId
            val openDetail = callbacks.onOpenToolDetail
            if (toolCallId != null && openDetail != null && message.sessionId.isNotEmpty()) {
                add(ContextMenuItem("details", viewDetails, { openDetail(message.sessionId, toolCallId) }, icon = null))
            }
            if (model.isCommand && model.details.isNotEmpty()) {
                val command = model.details.joinToString("\n")
                add(ContextMenuItem("copy_command", copyCommand, { copy(copyCommand, command) }))
            }
            val output = toolOutputForCopy(model)
            if (output.isNotEmpty()) add(ContextMenuItem("copy_output", copyOutput, { copy(copyOutput, output) }))
        }
    }
}

/** The text "Copy output" copies: command output without ANSI escapes, else the output text. */
internal fun toolOutputForCopy(model: ToolRowModel): String = when {
    model.commandOutput.isNotEmpty() -> model.commandOutput.stripAnsi()
    model.submitText.isNotEmpty() -> model.submitText
    else -> model.outputText.orEmpty()
}
