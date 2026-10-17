package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.customize.CardSwitch
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.RuleCard
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.customize.label
import dev.cline.termux.ui.icons.ClineIcons

/**
 * Rules tab (CR §1.5): "Installed" + count, Global first then Project; the switch is on and
 * disabled with "Toggling rules isn't supported yet"; the menu has only "Copy path".
 */
fun LazyGridScope.rulesTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    fullSpan("rules-header") { ListSectionHeader(stringResource(R.string.customize_installed), state.rules.size) }
    if (state.rules.isEmpty()) {
        fullSpan("rules-empty") { EmptyNotice(stringResource(R.string.customize_empty_rules)) }
    }
    items(state.rules, key = { it.key }) { rule -> RuleCardItem(rule, actions) }
}

@Composable
private fun RuleCardItem(rule: RuleCard, actions: CustomizeActions) {
    val copy = stringResource(R.string.customize_copy_path)
    ExtensionCard(
        icon = ClineIcons.Description,
        title = rule.name,
        badges = listOf(rule.scope.label()),
        description = rule.preview.ifEmpty { null },
        path = rule.path,
        switch = CardSwitch(
            checked = true,
            enabled = false,
            label = stringResource(R.string.customize_toggle_cd, rule.name),
            onCheckedChange = {},
            reason = stringResource(R.string.customize_rules_unsupported),
        ),
        menuItems = listOf(ContextMenuItem("copy", copy, { actions.onCopyPath(rule.path) }, ClineIcons.ContentCopy)),
        menuLabel = stringResource(R.string.customize_more_actions_cd, rule.name),
    )
}
