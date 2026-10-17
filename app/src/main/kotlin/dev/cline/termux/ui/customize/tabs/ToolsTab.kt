package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.cline.termux.R
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.customize.BulkToggleRow
import dev.cline.termux.ui.customize.CardSwitch
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeBlockedCard
import dev.cline.termux.ui.customize.CustomizeRules
import dev.cline.termux.ui.customize.CustomizeSearchField
import dev.cline.termux.ui.customize.CustomizeTab
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.ToolCard
import dev.cline.termux.ui.customize.block
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono

/**
 * Tools tab (CR §1.3, §1.5): search, "BuiltIn Tools" and "Plugin Tools" sections with their
 * "Enable all / Disable all" rows, tool cards. Built-in tools come from the CLI catalog over
 * RUN_COMMAND, so a blocked Termux shows the `KernelBlockedCard` in their place (CR §1.9).
 */
fun LazyGridScope.toolsTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    val query = state.query(CustomizeTab.Tools)
    val builtin = CustomizeRules.filterTools(state.tools.builtin, query)
    val plugin = CustomizeRules.filterTools(state.tools.plugin, query)
    val searching = query.isNotBlank()

    fullSpan("tools-search") {
        CustomizeSearchField(
            query = query,
            placeholder = stringResource(R.string.customize_search_tools),
            contentDescription = stringResource(R.string.customize_search_tools),
            onQuery = { actions.onQuery(CustomizeTab.Tools, it) },
        )
    }

    val blocked = state.toolsBlocked?.block()
    if (blocked != null) {
        fullSpan("tools-blocked") { CustomizeBlockedCard(blocked, actions) }
    } else {
        toolSection(
            key = "builtin",
            titleRes = R.string.customize_builtin_tools,
            shown = builtin,
            state = state,
            actions = actions,
            emptyRes = if (searching) R.string.customize_search_empty_tools else R.string.customize_empty_builtin_tools,
            enableCd = R.string.customize_enable_all_builtin_cd,
            disableCd = R.string.customize_disable_all_builtin_cd,
        )
    }
    toolSection(
        key = "plugin",
        titleRes = R.string.customize_plugin_tools,
        shown = plugin,
        state = state,
        actions = actions,
        emptyRes = if (searching) R.string.customize_search_empty_tools else R.string.customize_empty_plugin_tools,
        enableCd = R.string.customize_enable_all_plugin_cd,
        disableCd = R.string.customize_disable_all_plugin_cd,
    )
}

private fun LazyGridScope.toolSection(
    key: String,
    titleRes: Int,
    shown: List<ToolCard>,
    state: CustomizeUiState,
    actions: CustomizeActions,
    emptyRes: Int,
    enableCd: Int,
    disableCd: Int,
) {
    fullSpan("$key-header") {
        val allEnabled = CustomizeRules.allEnabled(shown)
        ListSectionHeader(stringResource(titleRes), shown.size) {
            BulkToggleRow(
                allEnabled = allEnabled,
                enabled = shown.isNotEmpty() && state.mutationsAllowed && shown.none { it.key in state.pending },
                contentDescription = stringResource(if (allEnabled) disableCd else enableCd),
                onToggle = { actions.onSetAllTools(shown, !allEnabled) },
            )
        }
    }
    if (shown.isEmpty()) {
        fullSpan("$key-empty") { EmptyNotice(stringResource(emptyRes)) }
    }
    items(shown, key = { it.key }) { tool -> ToolCardItem(tool, state, actions) }
}

@androidx.compose.runtime.Composable
private fun ToolCardItem(tool: ToolCard, state: CustomizeUiState, actions: CustomizeActions) {
    ExtensionCard(
        icon = ClineIcons.Build,
        title = tool.name,
        badges = listOfNotNull(tool.pluginName),
        description = tool.description?.trim()?.ifEmpty { null } ?: stringResource(R.string.customize_no_description),
        switch = CardSwitch(
            checked = tool.enabled,
            enabled = state.mutationsAllowed,
            label = stringResource(R.string.customize_toggle_cd, tool.name),
            onCheckedChange = { actions.onToggleTool(tool, it) },
            saving = tool.key in state.pending,
            unavailableWhileReconnecting = state.reconnecting,
        ),
        extra = tool.headlessLine?.let { line ->
            {
                LtrText(line, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
