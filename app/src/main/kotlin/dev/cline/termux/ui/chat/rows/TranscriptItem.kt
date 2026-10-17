package dev.cline.termux.ui.chat.rows

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings

/*
 * The row dispatcher the chat screen puts in its `LazyColumn(reverseLayout = true)`; the model
 * is built off Main by the transcript pipeline (`TranscriptController`):
 *
 *   items(model.rows.asReversed(), key = { it.key }, contentType = { it.type }) { row ->
 *       TranscriptItem(row, actionsState, callbacks, selection)
 *   }
 *
 * A row reads only its own [TranscriptRow.facts], never the whole model, so a streaming tick
 * recomposes the row that changed and every other row is skipped (DS §6). Each item carries
 * its DS §3.3 top gap, is one TalkBack traversal group ordered oldest → newest (HC §3.15), and
 * forwards a disclosure body's per-frame growth for [WORK_PIN_MS] after the user toggles it so
 * the list can hold the toggled header in place (HC §3.7).
 */

@Composable
fun TranscriptItem(
    row: TranscriptRow,
    actionsState: MessageActionsState,
    callbacks: TranscriptCallbacks,
    selection: TranscriptSelection,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val runErrorStrings = remember(resources) { RunErrorStrings.from(resources) }
    val pin = remember(row.key) { DisclosurePin() }
    val onToggled = remember(row.key, callbacks.onDisclosureToggled) {
        {
            pin.toggledAt = SystemClock.uptimeMillis()
            callbacks.onDisclosureToggled?.invoke(row.key)
            Unit
        }
    }
    val heightDelta = callbacks.onRowHeightDelta
    // A disclosure body reports its growth per animation frame (Disclosure.kt) while it opens or
    // closes, which only the user's own tap does (A23); growth within WORK_PIN_MS of it is held.
    val onResize: ((Int) -> Unit)? = remember(row.key, heightDelta) {
        heightDelta?.let { report ->
            { delta: Int ->
                if (SystemClock.uptimeMillis() - pin.toggledAt <= WORK_PIN_MS) report(row.key, delta)
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = row.gapAbove.height)
            .semantics {
                isTraversalGroup = true
                traversalIndex = row.traversalIndex
            },
    ) {
        CompositionLocalProvider(LocalDisclosureToggled provides onToggled, LocalDisclosureResize provides onResize) {
            when (val item = row.item) {
                is ChatRenderItem.Message -> MessageRow(item, row, actionsState, callbacks, selection, runErrorStrings)
                is ChatRenderItem.Tools -> ToolMessageBlock(item.messages, row, callbacks)
                is ChatRenderItem.Run -> RunGroup {
                    WorkingChildren(item.items, row, actionsState, callbacks, selection, runErrorStrings)
                }
                is ChatRenderItem.Work -> WorkBlock(
                    id = item.id,
                    durationMilliseconds = item.durationMilliseconds,
                    toolCallCount = item.toolCallCount,
                ) {
                    WorkingChildren(item.items, row, actionsState, callbacks, selection, runErrorStrings)
                }
            }
        }
    }
}

/** Last measured height and last toggle time of one item. */
private class DisclosurePin {
    var toggledAt: Long = Long.MIN_VALUE / 2
}

/**
 * A run's or work block's rows rendered the same way they render live (desktop
 * `renderWorkingRow`): tools and messages, copy kept, no versioning actions. Assistant rows have
 * no fork anywhere (decided A16), and the only user rows inside a run are steering notes, which
 * have no run count and so no edit or restore.
 */
@Composable
private fun WorkingChildren(
    items: List<ChatRenderItem>,
    row: TranscriptRow,
    actionsState: MessageActionsState,
    callbacks: TranscriptCallbacks,
    selection: TranscriptSelection,
    runErrorStrings: RunErrorStrings,
) {
    items.forEach { child ->
        if (!isRenderedWorkingChild(child)) return@forEach
        key(rowKeyOf(child)) {
            when (child) {
                is ChatRenderItem.Tools -> ToolMessageBlock(child.messages, row, callbacks)
                is ChatRenderItem.Message -> MessageRow(child, row, actionsState, callbacks, selection, runErrorStrings)
                is ChatRenderItem.Run, is ChatRenderItem.Work -> Unit
            }
        }
    }
}

@Composable
private fun MessageRow(
    item: ChatRenderItem.Message,
    row: TranscriptRow,
    actionsState: MessageActionsState,
    callbacks: TranscriptCallbacks,
    selection: TranscriptSelection,
    runErrorStrings: RunErrorStrings,
) {
    val message = item.message
    val facts = row.factsOf(message.id)
    when (message.role) {
        Role.User -> UserMessage(message, facts, actionsState, callbacks, selection)
        Role.Assistant -> AssistantMessage(
            item = item,
            facts = facts,
            callbacks = callbacks,
            selection = selection,
            isRunActive = row.isRunActive,
        )
        Role.Error -> ErrorMessage(message, facts, actionsState, callbacks, runErrorStrings)
        Role.Status -> SessionNoticeRow(message)
        Role.System, Role.Tool -> SystemMessage(message)
    }
}
