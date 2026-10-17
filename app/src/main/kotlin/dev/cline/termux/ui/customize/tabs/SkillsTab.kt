package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.customize.ActionMessageLine
import dev.cline.termux.ui.customize.CardSwitch
import dev.cline.termux.ui.customize.CommandCard
import dev.cline.termux.ui.customize.CommandType
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeRules
import dev.cline.termux.ui.customize.CustomizeSearchField
import dev.cline.termux.ui.customize.CustomizeTab
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.MarketplaceSetupDisclosures
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.customize.itemMenu
import dev.cline.termux.ui.customize.label
import dev.cline.termux.ui.icons.ClineIcons

/**
 * Skills tab (CR §1.5): skills and workflows merged and sorted by name; a workflow's switch
 * is disabled with the visible reason "Toggling workflows isn't supported yet".
 */
fun LazyGridScope.skillsTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    val query = state.query(CustomizeTab.Skills)
    val shown = CustomizeRules.filterCommands(state.commands, query)
    fullSpan("skills-search") {
        CustomizeSearchField(
            query = query,
            placeholder = stringResource(R.string.customize_search_skills),
            contentDescription = stringResource(R.string.customize_search_skills),
            onQuery = { actions.onQuery(CustomizeTab.Skills, it) },
        )
    }
    fullSpan("skills-header") { ListSectionHeader(stringResource(R.string.customize_installed), shown.size) }
    if (shown.isEmpty()) {
        fullSpan("skills-empty") {
            EmptyNotice(stringResource(if (state.commands.isEmpty()) R.string.customize_empty_skills else R.string.customize_search_empty_skills))
        }
    }
    items(shown, key = { it.key }) { command -> CommandCardItem(command, state, actions) }
}

@Composable
private fun CommandCardItem(command: CommandCard, state: CustomizeUiState, actions: CustomizeActions) {
    val workflow = command.type == CommandType.Workflow
    val menu = if (command.agentPlugin) emptyList() else itemMenu(command.key, command.item, command.canUninstall, state, actions)
    ExtensionCard(
        icon = if (workflow) ClineIcons.PlayArrow else ClineIcons.Bolt,
        title = command.name,
        badges = listOfNotNull(
            command.scope.label(),
            stringResource(if (workflow) R.string.customize_type_workflow else R.string.customize_type_skill),
            if (command.agentPlugin) stringResource(R.string.customize_agent_plugin) else null,
            if (command.marketplaceMatches.isNotEmpty()) stringResource(R.string.customize_marketplace_badge) else null,
        ),
        description = command.preview.ifEmpty { null },
        path = command.path,
        switch = CardSwitch(
            checked = command.enabled,
            enabled = command.toggleable && state.mutationsAllowed,
            label = stringResource(R.string.customize_toggle_cd, command.name),
            onCheckedChange = { actions.onToggleItem(command.key, command.item, it) },
            reason = if (workflow) stringResource(R.string.customize_workflows_unsupported) else null,
            saving = command.key in state.pending && command.key !in state.uninstalling,
            unavailableWhileReconnecting = state.reconnecting && command.toggleable,
        ),
        menuItems = menu,
        menuLabel = stringResource(R.string.customize_more_actions_cd, command.name),
        pendingMenuItemId = if (command.key in state.uninstalling) "uninstall" else null,
        dimmed = command.key in state.uninstalling,
        bottom = {
            MarketplaceSetupDisclosures(command.key, command.marketplaceMatches, state.expanded, actions.onToggleExpanded)
            state.messages[command.key]?.let { ActionMessageLine(it) }
        },
    )
}
