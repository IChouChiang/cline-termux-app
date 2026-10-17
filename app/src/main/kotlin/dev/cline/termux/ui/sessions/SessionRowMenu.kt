package dev.cline.termux.ui.sessions

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.R
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.icons.ClineIcons

/** Menu item ids (DS §4.1 #15): the order is the desktop's plus Details before the divider. */
object SessionMenuIds {
    const val PIN = "pin"
    const val RENAME = "rename"
    const val FORK = "fork"
    const val DETAILS = "details"
    const val DELETE = "delete"

    /** The item showing the spinner while [kind] runs on the row. */
    fun pendingItem(kind: PendingKind?): String? = when (kind) {
        PendingKind.Rename -> RENAME
        PendingKind.Fork -> FORK
        PendingKind.Delete -> DELETE
        null -> null
    }
}

/**
 * The row menu of S1 and S2 (sessions.md §2.8, §3.7): Pin/Unpin, Rename, Fork, Details, Delete.
 * Kernel actions are disabled while [actionsEnabled] is false (cached list, kernel down, §1.7);
 * Details only reads, so it stays enabled. The same list feeds TalkBack custom actions.
 */
@Composable
fun sessionMenuItems(summary: SessionSummary, actionsEnabled: Boolean, actions: SessionRowActions): List<ContextMenuItem> = listOf(
    ContextMenuItem(
        id = SessionMenuIds.PIN,
        label = stringResource(if (summary.pinned) R.string.sessions_menu_unpin else R.string.sessions_menu_pin),
        onClick = { actions.togglePin(summary) },
        icon = if (summary.pinned) ClineIcons.PushPinFilled else ClineIcons.PushPin,
        enabled = actionsEnabled,
    ),
    ContextMenuItem(
        id = SessionMenuIds.RENAME,
        label = stringResource(R.string.sessions_menu_rename),
        onClick = { actions.startRename(summary) },
        icon = ClineIcons.Edit,
        enabled = actionsEnabled,
        pendingLabel = stringResource(R.string.sessions_menu_renaming),
    ),
    ContextMenuItem(
        id = SessionMenuIds.FORK,
        label = stringResource(R.string.sessions_menu_fork),
        onClick = { actions.fork(summary) },
        icon = ClineIcons.ForkRight,
        enabled = actionsEnabled,
        pendingLabel = stringResource(R.string.sessions_menu_forking),
    ),
    ContextMenuItem(
        id = SessionMenuIds.DETAILS,
        label = stringResource(R.string.sessions_menu_details),
        onClick = { actions.showDetails(summary.id) },
        icon = ClineIcons.Info,
    ),
    ContextMenuItem(
        id = SessionMenuIds.DELETE,
        label = stringResource(R.string.sessions_menu_delete),
        onClick = { actions.requestDelete(summary) },
        icon = ClineIcons.Delete,
        destructive = true,
        enabled = actionsEnabled,
        pendingLabel = stringResource(R.string.sessions_menu_deleting),
    ),
)

/**
 * The session row's `DropdownMenu` (DS §4.1 #15, sessions.md §2.8): at the touch point
 * ([offset]) or under the overflow button, a divider before the destructive item, and with
 * [pendingItemId] set that item shows a 16dp spinner and its pending label while every item
 * is disabled (desktop agent-sidebar.tsx lines 1741-1747).
 */
@Composable
fun SessionRowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<ContextMenuItem>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    pendingItemId: String? = null,
) {
    ReportBackLevel(BackLevel.Menu, expanded)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp),
        offset = offset,
    ) {
        items.forEach { item ->
            if (item.destructive) HorizontalDivider()
            val pending = item.id == pendingItemId
            DropdownMenuItem(
                text = { Text(if (pending) item.pendingLabel ?: item.label else item.label) },
                onClick = {
                    onDismissRequest()
                    item.onClick()
                },
                enabled = item.enabled && pendingItemId == null,
                colors = if (item.destructive) {
                    MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error)
                } else {
                    MenuDefaults.itemColors()
                },
                leadingIcon = when {
                    pending -> {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    }
                    item.icon != null -> {
                        { Icon(item.icon, contentDescription = null) }
                    }
                    else -> null
                },
            )
        }
    }
}
