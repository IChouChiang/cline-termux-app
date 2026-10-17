package dev.cline.termux.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * True while the list scrolls towards its start, and at rest: the ExtendedFAB shows its label
 * then and collapses to icon-only while the list scrolls down (DS §5.4 `isScrollingUp`).
 */
@Composable
fun LazyListState.isScrollingUp(): Boolean = rememberScrollingUp(this) { firstVisibleItemIndex to firstVisibleItemScrollOffset }

/** The grid form of [LazyListState.isScrollingUp] (the Customize MCP grid). */
@Composable
fun LazyGridState.isScrollingUp(): Boolean = rememberScrollingUp(this) { firstVisibleItemIndex to firstVisibleItemScrollOffset }

/**
 * Tracks the scroll direction of any lazy layout from its first visible (index, offset)
 * [position], keyed on [key] (the layout state).
 */
@Composable
private fun rememberScrollingUp(key: Any, position: () -> Pair<Int, Int>): Boolean {
    var previous by remember(key) { mutableStateOf(position()) }
    var up by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key) {
        snapshotFlow(position).collect { current ->
            up = isScrollingUp(previous, current)
            previous = current
        }
    }
    return up
}

/**
 * The direction from [previous] to [current], each a first visible (index, offset) pair: a
 * smaller index is up; within one item a smaller or equal offset is up (no movement keeps the
 * label shown).
 */
internal fun isScrollingUp(previous: Pair<Int, Int>, current: Pair<Int, Int>): Boolean =
    if (current.first != previous.first) current.first < previous.first else current.second <= previous.second
