package dev.cline.termux.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** What a key does in an inline edit field (the drawer rename, DS §4.1 #1; the header rename, chat §6.7). */
enum class InlineEditKey {
    /** Enter / NumPad Enter: save the edit. */
    Commit,

    /** Escape, or a Back key press: restore the old value. */
    Cancel,

    /** The release of a Back key: taken so it never reaches the field or the activity. */
    Swallow,
}

/**
 * The key map shared by every inline edit field; `null` leaves the key to the text field.
 *
 * Back is taken on both edges. With the IME already hidden (button navigation, a hardware
 * key) the key reaches the field before the back dispatcher, and Compose's root turns an
 * unconsumed one into a focus exit, which a commit-on-blur field would save instead of cancel.
 */
fun inlineEditKeyAction(key: Key, type: KeyEventType): InlineEditKey? {
    val down = type == KeyEventType.KeyDown
    return when (key) {
        Key.Back -> if (down) InlineEditKey.Cancel else InlineEditKey.Swallow
        Key.Escape -> InlineEditKey.Cancel.takeIf { down }
        Key.Enter, Key.NumPadEnter -> InlineEditKey.Commit.takeIf { down }
        else -> null
    }
}

/**
 * One exit per interaction with an inline edit field: whichever of Enter, Done, Back, Escape
 * or focus loss comes first wins, and the echoes that follow it (the blur as a committed or
 * cancelled field leaves composition or is disabled while the save runs) are dropped. A cancel
 * ends the edit for good. A commit the owner did not close the field for (a failed save that
 * leaves the field open for a retry) is re-armed by [rearm] once the person is back in the
 * field: it takes focus again or the text changes.
 */
@Stable
class InlineEditExit(private val onCommit: () -> Unit, private val onCancel: () -> Unit) {
    private var cancelled = false
    private var sent = false
    private var hadFocus = false

    /**
     * Follows the field's focus: regaining it re-arms the exit; losing it after having had it
     * commits when [commitOnBlur] (the desktop `onBlur`). The drawer passes `commitOnBlur && !pending`,
     * so the field being disabled while its save runs is not a second exit.
     */
    fun onFocusChanged(focused: Boolean, commitOnBlur: Boolean) {
        if (focused) {
            if (!hadFocus) rearm()
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            if (commitOnBlur) commit()
        }
    }

    /** Saves the edit unless an exit was already sent for this interaction. */
    fun commit() {
        if (cancelled || sent) return
        sent = true
        onCommit()
    }

    /** Restores the old value; nothing exits after it. */
    fun cancel() {
        if (cancelled || sent) return
        cancelled = true
        sent = true
        onCancel()
    }

    /** The person is back in the field (focus regained, text edited): the next exit counts. */
    fun rearm() {
        if (!cancelled) sent = false
    }
}

/** Remembers an [InlineEditExit] for one edit; a new [key] (another row's draft) starts a new one. */
@Composable
fun rememberInlineEditExit(key: Any?, onCommit: () -> Unit, onCancel: () -> Unit): InlineEditExit {
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    return remember(key) { InlineEditExit(onCommit = { commit() }, onCancel = { cancel() }) }
}

/**
 * The shared key and focus handling of an inline edit field: Enter commits, Escape and a
 * Back key cancel ([inlineEditKeyAction]); focus goes through [InlineEditExit.onFocusChanged]
 * with [commitOnBlur]. Place it on the text field, after any `focusRequester`.
 */
fun Modifier.inlineEditKeys(exit: InlineEditExit, commitOnBlur: Boolean): Modifier =
    this
        .onFocusChanged { state -> exit.onFocusChanged(state.isFocused, commitOnBlur) }
        .onPreviewKeyEvent { event ->
            when (inlineEditKeyAction(event.key, event.type)) {
                InlineEditKey.Commit -> {
                    exit.commit()
                    true
                }
                InlineEditKey.Cancel -> {
                    exit.cancel()
                    true
                }
                InlineEditKey.Swallow -> true
                null -> false
            }
        }
