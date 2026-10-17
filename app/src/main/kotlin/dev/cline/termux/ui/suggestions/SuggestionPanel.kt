package dev.cline.termux.ui.suggestions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.hasHardwareKeyboard
import dev.cline.termux.ui.theme.Spacing

/** Desktop `max-h-56`. */
val SuggestionPanelMaxHeight: Dp = 224.dp

/**
 * The shared suggestion panel (composer-approvals.md C5.8, C6.7; DS addition
 * "SuggestionPanel"): a docked child of `ComposerSurface` — `Surface(surfaceContainer,
 * RectangleShape)`, 4dp vertical padding, a `LazyColumn` of at most 224dp. It never takes
 * focus, so the IME stays up. TalkBack reaches it after the field ([traversalIndex] 1) and
 * hears a one-column collection. The row highlight shows only with a hardware keyboard.
 */
@Composable
fun SuggestionPanel(
    state: SuggestionMenuState,
    onSelect: (Int) -> Unit,
    onChooseWorkspace: () -> Unit,
    onAllowCommands: () -> Unit,
    modifier: Modifier = Modifier,
    showHighlight: Boolean = LocalConfiguration.current.hasHardwareKeyboard(),
) {
    val description = stringResource(
        when (state) {
            is SuggestionMenuState.Slash -> R.string.cd_slash_panel
            is SuggestionMenuState.Mention -> R.string.cd_mention_panel
        },
    )
    val listState = rememberLazyListState()
    LaunchedEffect(state.highlighted, showHighlight) {
        if (showHighlight && state.rowCount > 0) listState.animateScrollToItem(state.highlighted)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                traversalIndex = 1f
                contentDescription = description
            },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SuggestionPanelMaxHeight)
                .semantics { collectionInfo = CollectionInfo(rowCount = state.rowCount, columnCount = 1) },
            contentPadding = PaddingValues(vertical = Spacing.xs),
        ) {
            when (state) {
                is SuggestionMenuState.Slash -> slashRows(state, showHighlight, onSelect)
                is SuggestionMenuState.Mention -> mentionRows(state, showHighlight, onSelect, onChooseWorkspace, onAllowCommands)
            }
        }
    }
}

/** A selectable row: `Role.Button`, min height, 16dp gutters, `secondaryContainer` when highlighted. */
@Composable
internal fun SuggestionRow(
    highlighted: Boolean,
    minHeight: Dp,
    rowDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/** A notice row: "Loading commands...", "No matching files", "Searching files..." (48dp, `bodyMedium onSurfaceVariant`). */
@Composable
internal fun SuggestionNoticeRow(text: String, trailing: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The 32dp tail row ("Loading...", "Updating..."), `labelSmall onSurfaceVariant`. */
@Composable
internal fun SuggestionTailRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = Spacing.l),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
