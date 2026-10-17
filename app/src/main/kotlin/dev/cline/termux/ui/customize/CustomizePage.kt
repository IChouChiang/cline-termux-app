package dev.cline.termux.ui.customize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.DashedNotice
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.Skeleton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** Every callback a Customize page needs; built once by the screen from its ViewModel. */
class CustomizeActions(
    val onToggleTool: (ToolCard, Boolean) -> Unit,
    val onSetAllTools: (List<ToolCard>, Boolean) -> Unit,
    val onToggleItem: (key: String, item: SettingsItem, enabled: Boolean) -> Unit,
    val onToggleMcp: (McpServerCard, Boolean) -> Unit,
    /** Opens the uninstall confirm dialog (CR §1.6). */
    val onRequestUninstall: (key: String, item: SettingsItem) -> Unit,
    val onCopyPath: (String) -> Unit,
    val onToggleExpanded: (String) -> Unit,
    val onQuery: (CustomizeTab, String) -> Unit,
    /** C2: null name = "Add MCP Server". */
    val onEditMcp: (String?) -> Unit,
    val onDeleteMcp: (String) -> Unit,
    val onRetry: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
    val onBlockedAction: (KernelBlockAction) -> Unit,
    /** The RUN_COMMAND permission was denied for good: the card offers "Open app settings". */
    val permissionPermanentlyDenied: Boolean = false,
) {
    companion object {
        /** For previews. */
        val None = CustomizeActions({ _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
    }
}

/** The SP §0.5 card for a blocked RUN_COMMAND source, wired to the screen's handler. */
@Composable
fun CustomizeBlockedCard(block: KernelBlock, actions: CustomizeActions) {
    KernelBlockedCard(
        block = block,
        onAction = actions.onBlockedAction,
        modifier = Modifier.padding(vertical = Spacing.xs),
        permissionPermanentlyDenied = actions.permissionPermanentlyDenied,
    )
}

/** A full-width grid item (headers, notices, search fields). */
fun LazyGridScope.fullSpan(key: Any, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

/** The menu of skills, workflows, plugins and rules (CR §1.6): "Copy path", plus "Uninstall" where offered. */
@Composable
fun itemMenu(
    key: String,
    item: SettingsItem,
    canUninstall: Boolean,
    state: CustomizeUiState,
    actions: CustomizeActions,
): List<ContextMenuItem> {
    val copy = stringResource(R.string.customize_copy_path)
    val uninstall = stringResource(R.string.customize_uninstall)
    val uninstalling = stringResource(R.string.customize_uninstalling)
    return buildList {
        add(ContextMenuItem("copy", copy, { actions.onCopyPath(item.path) }, ClineIcons.ContentCopy))
        if (canUninstall) {
            add(
                ContextMenuItem(
                    id = "uninstall",
                    label = uninstall,
                    onClick = { actions.onRequestUninstall(key, item) },
                    icon = ClineIcons.Delete,
                    destructive = true,
                    enabled = state.mutationsAllowed,
                    pendingLabel = uninstalling,
                ),
            )
        }
    }
}

/** "Installed 3", "BuiltIn Tools 9": `titleSmall` SemiBold heading + count, optional trailing control. */
@Composable
fun ListSectionHeader(title: String, count: Int, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(Spacing.s))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** The "Enable all / Disable all" `Checkbox` row (48dp), applied to the filtered tools of a section. */
@Composable
fun BulkToggleRow(allEnabled: Boolean, enabled: Boolean, contentDescription: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .focusRing(RectangleShape, inset = true)
            .toggleable(value = allEnabled, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(start = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = allEnabled, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.s))
        Text(
            stringResource(if (allEnabled) R.string.customize_disable_all else R.string.customize_enable_all),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The tab search field (CR §1.6): filters as you type, a clear button when non-empty, IME
 * action Search hides the keyboard, and the first back while focused only clears focus.
 */
@Composable
fun CustomizeSearchField(query: String, placeholder: String, contentDescription: String, onQuery: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = focused) { focusManager.clearFocus() }
    SearchField(
        value = query,
        onValueChange = onQuery,
        placeholder = placeholder,
        clearContentDescription = stringResource(R.string.customize_clear_search),
        contentDescription = contentDescription,
        modifier = Modifier
            .padding(vertical = Spacing.s)
            .onFocusChanged { focused = it.isFocused },
    )
}

/** The tab's description line (`bodyMedium` `onSurfaceVariant`). */
@Composable
fun TabDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.xs),
    )
}

/** A dashed empty / search-empty notice (Appendix A `DashedNotice`). */
@Composable
fun EmptyNotice(text: String) {
    DashedNotice(text, modifier = Modifier.padding(vertical = Spacing.xs))
}

/** The red page error: "Failed to load configuration lists: <message>" + Retry (CR §1.4). */
@Composable
fun LoadErrorCard(failure: RepoFailure, onRetry: () -> Unit) {
    InlineErrorCard(
        message = stringResource(R.string.customize_load_error, failure.text()),
        actionLabel = stringResource(R.string.components_retry),
        onAction = onRetry,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}

/** The amber "Partial results" card with one `<source>: <message>` bullet per warning (CR §1.4). */
@Composable
fun PartialResultsCard(lines: List<String>) {
    val warning = ClineTheme.colors.warning
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = warning.container,
        contentColor = warning.onContainer,
    ) {
        Row(Modifier.padding(Spacing.m)) {
            Icon(ClineIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp), tint = warning.color)
            Spacer(Modifier.width(Spacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    stringResource(R.string.customize_partial_results),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                lines.forEach { line -> Text(stringResource(R.string.customize_bullet, line), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** First load: three 88dp skeleton cards (CR §1.4). */
@Composable
fun SkeletonCard() {
    Skeleton(
        modifier = Modifier.fillMaxWidth().height(88.dp).padding(vertical = Spacing.xs).clearAndSetSemantics { },
        shape = MaterialTheme.shapes.medium,
    )
}

@LightDarkPreviews
@Composable
private fun PageBlocksPreview() {
    ComponentPreview {
        Column {
            TabDescription("Inspect built-in tools and tools contributed by plugins.")
            CustomizeSearchField("edit", "Search tools", "Search tools") {}
            ListSectionHeader("BuiltIn Tools", 9) {
                BulkToggleRow(allEnabled = true, enabled = true, contentDescription = "Disable all builtin tools", onToggle = {})
            }
            LoadErrorCard(RepoFailure.Hub("settings_list_failed", "plugin setup exploded"), onRetry = {})
            PartialResultsCard(listOf("hooks: Termux did not answer", "tools: Termux is blocking Cline"))
            EmptyNotice("No tools match your search.")
            SkeletonCard()
        }
    }
}
