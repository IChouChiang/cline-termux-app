package dev.cline.termux.ui.navigation

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shell-wide hardware-keyboard shortcuts (DS §5.7 item 5, sessions.md §2.8). The desktop binds
 * Cmd/Ctrl+N (new session), Cmd/Ctrl+P (the command bar, its session search) and Cmd/Ctrl+,
 * (Settings) in `webview/app/page.tsx`, and Cmd/Ctrl+B (toggle the sidebar) in
 * `components/ui/sidebar.tsx`; the app takes them with Ctrl, plus Ctrl+K for search.
 */
enum class ShellShortcut {
    /** Ctrl+K / Ctrl+P: session search (desktop "Search sessions (Cmd/Ctrl+P)"). */
    Search,

    /** Ctrl+N: a new session (desktop Cmd/Ctrl+N). */
    NewSession,

    /** Ctrl+,: Settings, the drawer's / rail's Settings destination (desktop Cmd/Ctrl+,). */
    Settings,

    /**
     * Ctrl+B: the sessions drawer opens or closes on windows with a modal drawer; on expanded
     * windows the list pane collapses or expands, like the rail's menu (desktop Cmd/Ctrl+B).
     */
    ToggleDrawer,
}

object ShellShortcuts {
    /**
     * Maps one key event to a shortcut: key down, Ctrl held, no Alt / Meta / Shift, and
     * K, P, N, comma or B. Everything else (Enter, Esc, arrows: the composer's and the lists'
     * own keys) is left to the focused composable.
     */
    fun match(action: Int, keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean, repeatCount: Int = 0): ShellShortcut? {
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return null
        if (!ctrl || alt || shift || meta) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_P -> ShellShortcut.Search
            KeyEvent.KEYCODE_N -> ShellShortcut.NewSession
            KeyEvent.KEYCODE_COMMA -> ShellShortcut.Settings
            KeyEvent.KEYCODE_B -> ShellShortcut.ToggleDrawer
            else -> null
        }
    }

    fun match(event: KeyEvent): ShellShortcut? =
        match(event.action, event.keyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed, event.repeatCount)

    /**
     * Settings and ToggleDrawer do nothing while a menu, a sheet, a dialog or the lightbox is
     * up ([isOpen] is the shell's `BackOrder.isOpen`). Dialogs and sheets are their own windows,
     * so their keys rarely reach the activity at all; this covers the in-window surfaces.
     */
    fun suppressedByOverlay(shortcut: ShellShortcut, isOpen: (BackLevel) -> Boolean): Boolean = when (shortcut) {
        ShellShortcut.Settings, ShellShortcut.ToggleDrawer ->
            isOpen(BackLevel.Menu) || isOpen(BackLevel.Sheet) || isOpen(BackLevel.Lightbox)
        ShellShortcut.Search, ShellShortcut.NewSession -> false
    }
}

/**
 * The activity forwards matched shortcuts here (`MainActivity.dispatchKeyEvent`); the shell
 * collects them. A shortcut that arrives while no shell is on screen (onboarding, a repair
 * screen) is dropped, because the buffer only holds what a live collector has not read yet.
 */
class ShortcutBus {
    private val events = MutableSharedFlow<ShellShortcut>(extraBufferCapacity = 4)

    val shortcuts: SharedFlow<ShellShortcut> = events.asSharedFlow()

    /** True when a collector will see it; the activity consumes the key only then. */
    fun offer(shortcut: ShellShortcut): Boolean = events.subscriptionCount.value > 0 && events.tryEmit(shortcut)
}

val LocalShortcutBus = staticCompositionLocalOf { ShortcutBus() }
