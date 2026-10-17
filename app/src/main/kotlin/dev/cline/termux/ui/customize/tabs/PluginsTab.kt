package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.cline.termux.R
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.customize.ActionMessageLine
import dev.cline.termux.ui.customize.CardSwitch
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeRules
import dev.cline.termux.ui.customize.CustomizeSearchField
import dev.cline.termux.ui.customize.CustomizeTab
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.MarketplaceSetupDisclosures
import dev.cline.termux.ui.customize.PluginCard
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.customize.itemMenu
import dev.cline.termux.ui.customize.label
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * Plugins tab (CR §1.3 plugin card, §1.5): search, "Installed" + count, expandable plugin
 * cards with the contribution groups, scope and "Agent Plugin"/"Cline Plugin" badges.
 */
fun LazyGridScope.pluginsTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    val query = state.query(CustomizeTab.Plugins)
    val shown = CustomizeRules.filterPlugins(state.plugins, query)
    fullSpan("plugins-search") {
        CustomizeSearchField(
            query = query,
            placeholder = stringResource(R.string.customize_search_plugins),
            contentDescription = stringResource(R.string.customize_search_plugins),
            onQuery = { actions.onQuery(CustomizeTab.Plugins, it) },
        )
    }
    fullSpan("plugins-header") { ListSectionHeader(stringResource(R.string.customize_installed), shown.size) }
    if (shown.isEmpty()) {
        fullSpan("plugins-empty") {
            EmptyNotice(stringResource(if (state.plugins.isEmpty()) R.string.customize_empty_plugins else R.string.customize_search_empty_plugins))
        }
    }
    items(shown, key = { it.key }) { plugin -> PluginCardItem(plugin, state, actions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCardItem(plugin: PluginCard, state: CustomizeUiState, actions: CustomizeActions) {
    val expanded = plugin.key in state.expanded
    val menu = if (plugin.agentPlugin) emptyList() else itemMenu(plugin.key, plugin.item, plugin.canUninstall, state, actions)
    ExtensionCard(
        icon = ClineIcons.Extension,
        title = plugin.name,
        badges = listOfNotNull(
            plugin.scope.label(),
            stringResource(if (plugin.agentPlugin) R.string.customize_agent_plugin else R.string.customize_cline_plugin),
            if (plugin.marketplaceMatches.isNotEmpty()) stringResource(R.string.customize_marketplace_badge) else null,
        ),
        switch = CardSwitch(
            checked = plugin.enabled,
            enabled = plugin.toggleable && state.mutationsAllowed,
            label = stringResource(R.string.customize_toggle_cd, plugin.name),
            onCheckedChange = { actions.onToggleItem(plugin.key, plugin.item, it) },
            saving = plugin.key in state.pending && plugin.key !in state.uninstalling,
            unavailableWhileReconnecting = state.reconnecting,
        ),
        menuItems = menu,
        menuLabel = stringResource(R.string.customize_more_actions_cd, plugin.name.ifBlank { stringResource(R.string.customize_more_actions_fallback) }),
        pendingMenuItemId = if (plugin.key in state.uninstalling) "uninstall" else null,
        dimmed = plugin.key in state.uninstalling,
        onClick = { actions.onToggleExpanded(plugin.key) },
        expanded = expanded,
        expandedContent = {
            plugin.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.s))
            }
            plugin.loadError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(Spacing.s))
            }
            if (plugin.inspectionDisabled) {
                Text(stringResource(R.string.customize_plugin_enable_to_inspect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.s))
            }
            if (plugin.groups.isEmpty()) {
                Text(stringResource(R.string.customize_no_contributions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.customize_contributions), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    plugin.groups.forEach { group -> TagBadge(stringResource(R.string.customize_group_count, group.kind.label(), group.items.size)) }
                }
                Spacer(Modifier.height(Spacing.s))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    plugin.groups.forEach { group ->
                        Column {
                            Text(group.kind.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xxs))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                group.items.forEach { item ->
                                    TagBadge(item, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        },
        bottom = {
            MarketplaceSetupDisclosures(plugin.key, plugin.marketplaceMatches, state.expanded, actions.onToggleExpanded)
            state.messages[plugin.key]?.let { ActionMessageLine(it) }
        },
    )
}
