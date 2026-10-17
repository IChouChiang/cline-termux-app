package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeBlockedCard
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.HookCard
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.block
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.customize.label
import dev.cline.termux.ui.icons.ClineIcons

/**
 * Hooks tab (CR §1.5): hook files from `cline config hooks --json` with the event badge,
 * path and scope; no execution badges (CR §1.2). RUN_COMMAND blocked → `KernelBlockedCard`.
 */
fun LazyGridScope.hooksTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    val blocked = state.hooksBlocked?.block()
    if (blocked != null) {
        fullSpan("hooks-blocked") { CustomizeBlockedCard(blocked, actions) }
        return
    }
    fullSpan("hooks-header") { ListSectionHeader(stringResource(R.string.customize_installed), state.hooks.size) }
    if (state.hooks.isEmpty()) {
        fullSpan("hooks-empty") { EmptyNotice(stringResource(R.string.customize_empty_hooks)) }
    }
    items(state.hooks, key = { it.key }) { hook -> HookCardItem(hook, actions) }
}

@Composable
private fun HookCardItem(hook: HookCard, actions: CustomizeActions) {
    val copy = stringResource(R.string.customize_copy_path)
    ExtensionCard(
        icon = ClineIcons.Code,
        title = hook.fileName,
        badges = listOfNotNull(hook.scope.label(), hook.eventName),
        path = hook.path,
        menuItems = listOf(ContextMenuItem("copy", copy, { actions.onCopyPath(hook.path) }, ClineIcons.ContentCopy)),
        menuLabel = stringResource(R.string.customize_more_actions_cd, hook.fileName),
    )
}
