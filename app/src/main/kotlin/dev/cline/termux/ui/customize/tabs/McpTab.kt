package dev.cline.termux.ui.customize.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.cline.termux.R
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.customize.CardSwitch
import dev.cline.termux.ui.customize.CustomizeActions
import dev.cline.termux.ui.customize.CustomizeBlockedCard
import dev.cline.termux.ui.customize.CustomizeRules
import dev.cline.termux.ui.customize.CustomizeSearchField
import dev.cline.termux.ui.customize.CustomizeTab
import dev.cline.termux.ui.customize.CustomizeUiState
import dev.cline.termux.ui.customize.EmptyNotice
import dev.cline.termux.ui.customize.ExtensionCard
import dev.cline.termux.ui.customize.HelperSurface
import dev.cline.termux.ui.customize.ListSectionHeader
import dev.cline.termux.ui.customize.MarketplaceSetupDisclosures
import dev.cline.termux.ui.customize.McpServerCard
import dev.cline.termux.ui.customize.McpStatusDot
import dev.cline.termux.ui.customize.fullSpan
import dev.cline.termux.ui.customize.surface
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.customize.transportText
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * MCP tab (CR §1.3 MCP layout, §1.4 helper states, §1.5 MCP copy): the helper's starting /
 * unavailable surface, the settings path with a copy button, search, server cards with their
 * details (env redacted), always-visible Edit / Delete, and room under the ExtendedFAB.
 */
fun LazyGridScope.mcpTabItems(state: CustomizeUiState, actions: CustomizeActions) {
    val query = state.query(CustomizeTab.Mcp)
    val shown = CustomizeRules.filterMcp(state.mcp.servers, query)

    when (val helper = state.helper) {
        is HelperState.Starting -> fullSpan("mcp-helper") {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.xs))
                Text(stringResource(R.string.customize_helper_starting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is HelperState.Unavailable -> fullSpan("mcp-helper") {
            Column(Modifier.padding(vertical = Spacing.s)) {
                when (val surface = helper.surface()) {
                    is HelperSurface.Card -> HelperUnavailableCard(surface.model, onRetry = actions.onRetryHelper, onStartKernel = actions.onStartKernel)
                    is HelperSurface.Blocked -> CustomizeBlockedCard(surface.block, actions)
                }
            }
        }
        else -> Unit
    }

    state.mcpError?.let { failure ->
        fullSpan("mcp-error") {
            InlineErrorCard(failure.text(), actionLabel = stringResource(R.string.components_retry), onAction = actions.onRetry, modifier = Modifier.padding(vertical = Spacing.xs))
        }
    }

    state.mcp.settingsPath?.takeIf { state.mcp.hasSettingsFile }?.let { path ->
        fullSpan("mcp-path") {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                Text(stringResource(R.string.customize_mcp_settings_path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LtrText(path, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.onCopyPath(path) }) {
                        Icon(ClineIcons.ContentCopy, contentDescription = stringResource(R.string.customize_copy_file_path))
                    }
                }
            }
        }
    }

    fullSpan("mcp-search") {
        CustomizeSearchField(
            query = query,
            placeholder = stringResource(R.string.customize_search_mcp),
            contentDescription = stringResource(R.string.customize_search_mcp_cd),
            onQuery = { actions.onQuery(CustomizeTab.Mcp, it) },
        )
    }
    fullSpan("mcp-header") { ListSectionHeader(stringResource(R.string.customize_installed), shown.size) }
    if (shown.isEmpty()) {
        fullSpan("mcp-empty") {
            EmptyNotice(stringResource(if (state.mcp.servers.isEmpty()) R.string.customize_empty_mcp else R.string.customize_search_empty_mcp))
        }
    }
    items(shown, key = { it.key }) { server -> McpCardItem(server, state, actions) }
}

@Composable
private fun McpCardItem(server: McpServerCard, state: CustomizeUiState, actions: CustomizeActions) {
    val busy = server.key in state.pending
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val redacted: (String) -> String = { key -> resources.getString(R.string.customize_key_redacted_a11y, key) }
    ExtensionCard(
        icon = ClineIcons.DeployedCode,
        title = server.name,
        titleLeading = { McpStatusDot(server.enabled) },
        badges = listOfNotNull(
            server.transportText(),
            if (server.marketplaceMatches.isNotEmpty()) stringResource(R.string.customize_marketplace_badge) else null,
        ),
        switch = CardSwitch(
            checked = server.enabled,
            enabled = state.mutationsAllowed,
            label = stringResource(R.string.customize_mcp_enable_cd, server.name),
            onCheckedChange = { actions.onToggleMcp(server, it) },
            saving = busy,
            unavailableWhileReconnecting = state.reconnecting,
        ),
        onClick = { actions.onEditMcp(server.name) },
        extra = {
            Spacer(Modifier.height(Spacing.xs))
            if (server.noLaunchCommand) {
                DetailLine(stringResource(R.string.customize_mcp_no_command))
            }
            server.command?.let { DetailLine(stringResource(R.string.customize_mcp_command, it)) }
            server.args.takeIf { it.isNotEmpty() }?.let { DetailLine(stringResource(R.string.customize_mcp_args, it.joinToString(", "))) }
            server.cwd?.let { DetailLine(stringResource(R.string.customize_mcp_cwd, it)) }
            server.url?.let { DetailLine(stringResource(R.string.customize_mcp_url, it)) }
            server.envLine?.let { line ->
                DetailLine(
                    stringResource(R.string.customize_mcp_env, line),
                    spoken = stringResource(R.string.customize_mcp_env, server.envKeys.joinToString(", ") { redacted(it) }),
                )
            }
            server.headersLine?.let { line ->
                val spoken = server.headers.joinToString(", ") { header -> if (header.value == null) redacted(header.key) else "${header.key}=${header.value}" }
                DetailLine(stringResource(R.string.customize_mcp_headers, line), spoken = stringResource(R.string.customize_mcp_headers, spoken))
            }
            // CR §1.3: the setup disclosure sits with the details, above the Edit / Delete row.
            MarketplaceSetupDisclosures(server.key, server.marketplaceMatches, state.expanded, actions.onToggleExpanded)
        },
        footer = {
            IconButton(onClick = { actions.onEditMcp(server.name) }, enabled = !busy) {
                Icon(ClineIcons.Edit, contentDescription = stringResource(R.string.customize_mcp_edit_cd, server.name))
            }
            IconButton(onClick = { actions.onDeleteMcp(server.name) }, enabled = !busy && state.mutationsAllowed) {
                Icon(ClineIcons.Delete, contentDescription = stringResource(R.string.customize_mcp_delete_cd, server.name))
            }
        },
    )
}

@Composable
private fun DetailLine(text: String, spoken: String = text) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.xxs).semantics { contentDescription = spoken },
    )
}
