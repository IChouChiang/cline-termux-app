package dev.cline.termux.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException

/**
 * The documented back precedence (DS §5.8, docs/07 §6.3, CT DS-A9), highest first. Each
 * level consumes back only while it is showing; nothing here is an always-on handler, so
 * Home keeps the system back-to-home preview (DS §5.8 item 10).
 *
 * Material components handle several levels themselves (menus and tooltips are popups, sheets
 * are their own window with predictive collapse, `ModalDrawerSheet(drawerState)` registers
 * after the content so it wins over the NavHost). The levels a feature draws itself use
 * [OrderedBackHandler], and every surface that can be open **reports** itself with
 * [ReportBackLevel], so a lower handler (a chat's selection back, the list-pane reveal)
 * stays disabled while something above it is open, whatever the registration order.
 *
 * The one exception is the expanded list-pane reveal (level 8): it checks
 * [BackOrder.allows] but never reports itself, because SS §2.9.1 / HC §2.9 put the chat's
 * selected-session → Home (level 9) before it. Registered before the NavHost, it loses to
 * every enabled level-9 handler and runs only once the chat is at Home.
 */
enum class BackLevel(val label: String) {
    /** 1. Open `DropdownMenu` / tooltip (M3); editors' "discard changes?" confirm and "consume while saving". */
    Menu("1"),

    /** 2. Open `ModalBottomSheet`: predictive collapse; the approval sheet collapses to its banner. */
    Sheet("2"),

    /** 3. Image lightbox: scales 1 → 0.9 and fades the scrim with progress. */
    Lightbox("3"),

    /** 4. Inline rename / prompt-queue edit: cancels the edit (desktop Esc). */
    InlineEdit("4"),

    /** 5. Slash or mention panel in the composer (per token). */
    SuggestionPanel("5"),

    /** 6. Search expanded or focused: collapses (M3 `SearchBar`). */
    SearchFocus("6"),

    /** 7. `ModalNavigationDrawer` open: predictive close (M3). */
    Drawer("7"),

    /** 7a. The chat's supporting pane (Changes / Tool detail at ≥ 1200dp) closes. */
    SupportingPane("7a"),

    /**
     * 8. List-detail pane back on expanded windows. The shell's sessions pane registers this
     * *before* its NavHost and does not report it, so the detail NavHost pops and the chat's
     * selection goes Home first (sessions.md §2.9.1).
     */
    ListDetailPane("8"),

    /** 9. NavHost pop (seekable shared-axis), and the chat's selected-session → Home back. */
    NavHostPop("9"),

    /** 10. Nothing open at Home: not intercepted, back leaves the app. */
    LeaveApp("10"),
    ;

    /** True when this level consumes back before [other]. */
    fun precedes(other: BackLevel): Boolean = ordinal < other.ordinal
}

/**
 * Which surfaces are open right now, by level. Lives at the shell root ([LocalBackOrder]);
 * pure snapshot state, so a handler's `enabled` recomputes when a surface above it opens.
 */
@Stable
class BackOrder {
    private val open = mutableStateMapOf<Any, BackLevel>()

    /** Marks [owner]'s surface at [level] open or closed. */
    fun report(owner: Any, level: BackLevel, isOpen: Boolean) {
        if (isOpen) open[owner] = level else open.remove(owner)
    }

    /** Removes [owner] (its surface left composition). */
    fun forget(owner: Any) {
        open.remove(owner)
    }

    fun isOpen(level: BackLevel): Boolean = open.values.any { it == level }

    /** True while any surface of a higher-precedence level than [level] is open. */
    fun hasOpenAbove(level: BackLevel): Boolean = open.values.any { it.precedes(level) }

    /** The level back goes to now, or [BackLevel.LeaveApp] when nothing is open. */
    val current: BackLevel get() = BackPrecedence.winner(open.values)

    /** Whether a handler at [level] should be enabled (its own condition is [wanted]). */
    fun allows(level: BackLevel, wanted: Boolean): Boolean = wanted && !hasOpenAbove(level)
}

/** The precedence rule as a pure function (unit-tested). */
object BackPrecedence {
    /** The highest-precedence open level, or [BackLevel.LeaveApp] when none is open. */
    fun winner(open: Collection<BackLevel>): BackLevel = open.minByOrNull { it.ordinal } ?: BackLevel.LeaveApp

    /** A handler at [level] with its own condition [wanted] consumes back only if nothing above it is open. */
    fun consumes(level: BackLevel, wanted: Boolean, open: Collection<BackLevel>): Boolean =
        wanted && open.none { it.precedes(level) }
}

/** The shell's [BackOrder]; a fresh, empty one outside the shell (previews, root screens). */
val LocalBackOrder = staticCompositionLocalOf { BackOrder() }

/**
 * Reports a surface whose back handling is built into Material (a sheet, the drawer, an
 * expanded search bar, a menu) so lower [OrderedBackHandler]s stand down while it is open.
 */
@Composable
fun ReportBackLevel(level: BackLevel, isOpen: Boolean) {
    val order = LocalBackOrder.current
    val owner = remember { Any() }
    DisposableEffect(order, owner, level, isOpen) {
        order.report(owner, level, isOpen)
        onDispose { order.forget(owner) }
    }
}

/**
 * A back handler at [level], enabled only while [enabled] holds **and** no higher level is
 * open. It also reports itself, so handlers below it stand down. Never pass `enabled = true`
 * unconditionally (DS §5.8: no always-on handler).
 */
@Composable
fun OrderedBackHandler(level: BackLevel, enabled: Boolean, onBack: () -> Unit) {
    val order = LocalBackOrder.current
    ReportBackLevel(level, enabled)
    BackHandler(enabled = order.allows(level, enabled), onBack = onBack)
}

/**
 * The predictive form of [OrderedBackHandler] (for seekable pops such as the chat's
 * selected-session → Home, HC §2.9, and the lightbox scale, DS §5.8 item 3). [onStart] runs
 * when the gesture begins and returns what [onCancel] needs to restore; [onProgress] follows
 * every progress event; a completed gesture calls [onCommit], a cancelled one [onCancel].
 */
@Composable
fun <T> OrderedPredictiveBackHandler(
    level: BackLevel,
    enabled: Boolean,
    onStart: () -> T,
    onProgress: suspend (start: T, event: BackEventCompat) -> Unit,
    onCommit: (start: T) -> Unit,
    onCancel: (start: T) -> Unit,
) {
    val order = LocalBackOrder.current
    val started by rememberUpdatedState(onStart)
    val progressed by rememberUpdatedState(onProgress)
    val committed by rememberUpdatedState(onCommit)
    val cancelled by rememberUpdatedState(onCancel)
    ReportBackLevel(level, enabled)
    PredictiveBackHandler(enabled = order.allows(level, enabled)) { progress ->
        val from = started()
        try {
            progress.collect { event -> progressed(from, event) }
            committed(from)
        } catch (e: CancellationException) {
            cancelled(from)
            throw e
        }
    }
}

/** What an editor's close request (Back, close icon, Cancel, outside tap) does (docs/07 §6.3). */
enum class EditorCloseAction { Close, ConfirmDiscard, Ignore }

/** Saving wins (desktop `disabled={busy}`), then the dirty form's discard confirm, else close. */
fun editorCloseAction(dirty: Boolean, saving: Boolean): EditorCloseAction = when {
    saving -> EditorCloseAction.Ignore
    dirty -> EditorCloseAction.ConfirmDiscard
    else -> EditorCloseAction.Close
}

/**
 * The editor rules of docs/07 §6.3 / CR §0.2 at level 1: while [saving], back is consumed and
 * ignored (desktop `disabled={busy}`); while [dirty], back asks [onRequestDiscard] to show the
 * "Discard changes?" confirm; with neither, back pops the editor. A full-screen editor leaves
 * that pop to the NavHost; an editor drawn in a `dialog()` window ([asDialog]) closes through
 * [onClose] itself, because [EditorDialogProperties] turns the window's own Back dismissal off.
 */
@Composable
fun EditorBackHandler(
    dirty: Boolean,
    saving: Boolean,
    onRequestDiscard: () -> Unit,
    asDialog: Boolean = false,
    onClose: () -> Unit = {},
) {
    BackHandler(enabled = saving) { }
    BackHandler(enabled = dirty && !saving, onBack = onRequestDiscard)
    BackHandler(enabled = asDialog && editorCloseAction(dirty, saving) == EditorCloseAction.Close, onBack = onClose)
}
