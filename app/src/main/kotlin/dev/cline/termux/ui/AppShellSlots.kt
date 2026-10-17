package dev.cline.termux.ui

import androidx.compose.runtime.compositionLocalOf
import dev.cline.termux.ui.about.ShellWhatsNewDialog
import dev.cline.termux.ui.kernel.KernelStatusSheet
import dev.cline.termux.ui.sessions.SessionSearchDocked
import dev.cline.termux.ui.sessions.SessionsDrawerContent
import dev.cline.termux.ui.sessions.SessionsDrawerFooter
import dev.cline.termux.ui.sessions.sessionsRailBadge

/**
 * True while the sessions list is on screen as the expanded list pane (shown by the layout, not
 * collapsed with the rail `menu`, Chats area): the rail's unread badge stays off then (SS §1.6).
 */
val LocalShellListPaneOnScreen = compositionLocalOf { false }

/**
 * The feature slots of the production shell (docs/07 §6.1, DS §5.2): the sessions drawer / list
 * pane (SS S1), its docked search on expanded windows (SS S3), the account footer (SS §2.3), the
 * rail's unread badge, the Kernel status sheet (KS E) and the What's-new dialog (CR C11).
 */
val AppShellSlots: ShellSlots = ShellSlots(
    sessionList = { modifier -> SessionsDrawerContent(modifier) },
    dockedSearch = { SessionSearchDocked() },
    drawerFooter = { SessionsDrawerFooter() },
    railBadge = { destination -> sessionsRailBadge(destination, listOnScreen = LocalShellListPaneOnScreen.current) },
    kernelStatusSheet = { request, onDismiss ->
        KernelStatusSheet(open = true, onDismiss = onDismiss, scrollToDiagnostics = request.diagnostics, restartRequest = request.restart)
    },
    whatsNewDialog = { id, onClose, onSeeAll -> ShellWhatsNewDialog(id, onClose, onSeeAll) },
)
