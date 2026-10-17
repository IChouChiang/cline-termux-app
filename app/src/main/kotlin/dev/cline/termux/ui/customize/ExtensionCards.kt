package dev.cline.termux.ui.customize

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.ui.components.AnchoredContextMenu
import dev.cline.termux.ui.components.ChevronTurn
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.OverflowMenuButton
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.components.toCustomActions
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.ClineSwitch
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/*
 * CR Appendix A `customize/` components: ExtensionCard, ScopeBadge / TagBadge (the shared
 * TagBadge), ActionMessage and the marketplace setup disclosure (desktop
 * `MarketplaceEntrySetupDetails`).
 */

/** The card's switch (CR §1.6): only the 48dp switch toggles, never the whole card. */
data class CardSwitch(
    val checked: Boolean,
    val enabled: Boolean,
    /** "Toggle <name>" / "Enable <name>". */
    val label: String,
    val onCheckedChange: (Boolean) -> Unit,
    /** Visible reason a disabled switch is disabled ("Toggling rules isn't supported yet"). */
    val reason: String? = null,
    /** A toggle is waiting for its reply: `stateDescription` "Saving". */
    val saving: Boolean = false,
    /** Mutations are off while the Hub reconnects: `stateDescription` of §0.4. */
    val unavailableWhileReconnecting: Boolean = false,
)

/**
 * ExtensionCard (CR Appendix A): `OutlinedCard`, 40dp icon tile, `titleSmall` title,
 * badges, an optional visible reason line, a 3-line description with "Show more", a mono
 * path, extra content, then the trailing switch and overflow as separate focus targets.
 * [onClick] is the card body tap (expand for plugins, Edit for MCP); long-press opens the
 * same menu as the overflow at the touch point, and the items are TalkBack custom actions.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ExtensionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    titleLeading: (@Composable () -> Unit)? = null,
    badges: List<String> = emptyList(),
    description: String? = null,
    path: String? = null,
    switch: CardSwitch? = null,
    menuItems: List<ContextMenuItem> = emptyList(),
    menuLabel: String? = null,
    pendingMenuItemId: String? = null,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
    expanded: Boolean? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val press = remember { PressPoint() }
    val haptic = rememberLongPressHaptic()
    val expandedText = stringResource(R.string.customize_expanded)
    val collapsedText = stringResource(R.string.customize_collapsed)

    OutlinedCard(modifier = modifier.fillMaxWidth().alpha(if (dimmed) 0.6f else 1f)) {
        Box(Modifier.trackPress(press)) {
            Column(Modifier.padding(Spacing.l)) {
                Row(verticalAlignment = Alignment.Top) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .then(
                                if (onClick != null || menuItems.isNotEmpty()) {
                                    Modifier.focusRing(MaterialTheme.shapes.small).combinedClickable(
                                        onClick = { onClick?.invoke() },
                                        onLongClick = if (menuItems.isNotEmpty()) {
                                            {
                                                haptic()
                                                menuOpen = true
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics(mergeDescendants = true) {
                                if (expanded != null) stateDescription = if (expanded) expandedText else collapsedText
                                customActions = menuItems.toCustomActions(pendingMenuItemId)
                            },
                    ) {
                        IconTile(icon)
                        Spacer(Modifier.width(Spacing.m))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                titleLeading?.invoke()
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (badges.isNotEmpty()) {
                                Spacer(Modifier.height(Spacing.xs))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    badges.forEach { TagBadge(it) }
                                }
                            }
                            switch?.reason?.let { reason ->
                                Spacer(Modifier.height(Spacing.xs))
                                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!description.isNullOrBlank()) {
                                Spacer(Modifier.height(Spacing.xs))
                                ExpandableDescription(description)
                            }
                            if (!path.isNullOrBlank()) {
                                Spacer(Modifier.height(Spacing.xs))
                                LtrText(
                                    path,
                                    style = ClineMono.small,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            extra?.invoke(this)
                        }
                    }
                    if (switch != null) CardSwitchControl(switch)
                    if (menuItems.isNotEmpty() && menuLabel != null) {
                        OverflowMenuButton(contentDescription = menuLabel, items = menuItems, pendingItemId = pendingMenuItemId, icon = ClineIcons.MoreVert)
                    }
                    if (expanded != null) {
                        val chevronLabel = stringResource(
                            if (expanded) R.string.customize_collapse_item else R.string.customize_expand_item,
                            title,
                        )
                        IconButton(
                            onClick = { onClick?.invoke() },
                            modifier = Modifier.semantics {
                                contentDescription = chevronLabel
                                stateDescription = if (expanded) expandedText else collapsedText
                            },
                        ) {
                            DisclosureChevron(
                                expanded = expanded,
                                size = DisclosureChevronDefaults.IconButtonSize,
                                turn = ChevronTurn.DownToUp,
                            )
                        }
                    }
                }
                if (expandedContent != null) {
                    AnimatedVisibility(
                        visible = expanded == true,
                        enter = expandVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard), expandFrom = Alignment.Top) +
                            fadeIn(tween(ClineMotion.Short3, delayMillis = 50)),
                        exit = shrinkVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard)) + fadeOut(tween(ClineMotion.Short2)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(top = Spacing.m), content = expandedContent)
                    }
                }
                if (footer != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = footer)
                }
                bottom?.invoke(this)
            }
            AnchoredContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = menuItems,
                offset = press.offset,
                pendingItemId = pendingMenuItemId,
            )
        }
    }
}

/** The 40dp icon tile (`surfaceContainerHighest`, `shapes.small`, 20dp `primary` icon). */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.size(40.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CardSwitchControl(switch: CardSwitch) {
    val onOff = stringResource(if (switch.checked) R.string.customize_switch_on else R.string.customize_switch_off)
    val saving = stringResource(R.string.customize_switch_state_pair, onOff, stringResource(R.string.customize_saving))
    val reconnecting = stringResource(
        R.string.customize_switch_state_pair,
        onOff,
        stringResource(R.string.customize_unavailable_reconnecting),
    )
    val label = if (!switch.enabled && switch.reason != null) {
        stringResource(R.string.customize_switch_unavailable_cd, switch.label, switch.reason)
    } else {
        switch.label
    }
    Box(Modifier.size(width = 64.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        ClineSwitch(
            checked = switch.checked,
            onCheckedChange = switch.onCheckedChange,
            enabled = switch.enabled && !switch.saving && !switch.unavailableWhileReconnecting,
            modifier = Modifier.semantics {
                contentDescription = label
                when {
                    switch.saving -> stateDescription = saving
                    switch.unavailableWhileReconnecting -> stateDescription = reconnecting
                }
            },
        )
    }
}

/** Description, 3 lines, with "Show more" / "Show less" when it overflows (CR Appendix A). */
@Composable
private fun ExpandableDescription(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(if (expanded) R.string.customize_show_less else R.string.customize_show_more))
            }
        }
    }
}

/** The ActionMessage line under a card (polite live region; `error` for failures). */
@Composable
fun ActionMessageLine(message: ActionMessage, modifier: Modifier = Modifier) {
    val (text, error) = when (message) {
        is ActionMessage.Uninstalled -> (message.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.customize_uninstalled, message.name)) to false
        is ActionMessage.Failed -> message.failure.text() to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The MCP enabled dot (8dp `primary` / `outline`), with "Enabled" / "Disabled" for TalkBack. */
@Composable
fun McpStatusDot(enabled: Boolean, modifier: Modifier = Modifier) {
    val cd = stringResource(if (enabled) R.string.customize_mcp_status_enabled else R.string.customize_mcp_status_disabled)
    Surface(
        modifier = modifier.padding(end = Spacing.s).size(10.dp).semantics { contentDescription = cd },
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ) {}
}

/**
 * Desktop `MarketplaceEntrySetupDetails`: one disclosure per matched entry that carries env
 * or notes, "Marketplace setup instructions" (with the entry name when several match).
 */
@Composable
fun MarketplaceSetupDisclosures(
    cardKey: String,
    entries: List<MarketplaceEntry>,
    expandedKeys: Set<String>,
    onToggle: (String) -> Unit,
) {
    val guided = CustomizeRules.withSetupGuidance(entries)
    if (guided.isEmpty()) return
    val openUrl = rememberOpenExternal()
    val warning = ClineTheme.colors.warning
    val expandedText = stringResource(R.string.customize_expanded)
    val collapsedText = stringResource(R.string.customize_collapsed)
    Column(Modifier.padding(top = Spacing.xs)) {
        guided.forEach { entry ->
            val key = "setup:$cardKey:${entry.key}"
            val open = key in expandedKeys
            TextButton(
                onClick = { onToggle(key) },
                modifier = Modifier.semantics { stateDescription = if (open) expandedText else collapsedText },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) {
                DisclosureChevron(expanded = open, size = DisclosureChevronDefaults.MetaRowSize, tint = warning.color)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    if (guided.size > 1) stringResource(R.string.customize_setup_instructions_named, entry.name) else stringResource(R.string.customize_setup_instructions),
                    color = warning.color,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            AnimatedVisibility(open) {
                EntrySetupGuidance(entry, onOpenUrl = openUrl)
            }
        }
    }
}

/** Desktop `EntrySetupGuidance`: the env box (required first) and the install notes. */
@Composable
private fun EntrySetupGuidance(entry: MarketplaceEntry, onOpenUrl: (String) -> Unit) {
    val env = entry.install.env.orEmpty()
    val ordered = env.filter { it.required != false } + env.filter { it.required == false }
    val warning = ClineTheme.colors.warning
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (ordered.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = warning.container, contentColor = warning.onContainer) {
                Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                    Text(
                        stringResource(R.string.customize_env_setup_needed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(stringResource(R.string.customize_env_setup_body), style = MaterialTheme.typography.bodySmall)
                    ordered.forEach { variable ->
                        Spacer(Modifier.height(Spacing.s))
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                            Column(Modifier.fillMaxWidth().padding(Spacing.s)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LtrText(variable.name, style = ClineMono.small, modifier = Modifier.weight(1f))
                                    TagBadge(stringResource(if (variable.required == false) R.string.customize_env_optional else R.string.customize_env_required))
                                }
                                variable.description?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                variable.url?.takeIf { it.isNotBlank() }?.let { url ->
                                    TextButton(onClick = { onOpenUrl(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                                        Text(stringResource(R.string.customize_get_value))
                                        Spacer(Modifier.width(Spacing.xs))
                                        Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        entry.install.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(Spacing.m))
            }
        }
    }
}

// ---- previews ------------------------------------------------------------------------------------

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ExtensionCardToolPreview() {
    ComponentPreview {
        ExtensionCard(
            icon = ClineIcons.Build,
            title = "editor",
            description = "Edit files with exact replacements or create new files.",
            extra = { LtrText("editor, apply_patch", style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            switch = CardSwitch(checked = true, enabled = true, label = "Toggle editor", onCheckedChange = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun ExtensionCardWorkflowPreview() {
    ComponentPreview {
        ExtensionCard(
            icon = ClineIcons.PlayArrow,
            title = "release",
            badges = listOf("Global", "workflow"),
            description = "Cut a release candidate and run the device checks.",
            path = "/data/data/com.termux/files/home/Documents/Cline/Workflows/release.md",
            switch = CardSwitch(checked = true, enabled = false, label = "Toggle release", onCheckedChange = {}, reason = "Toggling workflows isn't supported yet"),
            menuItems = listOf(ContextMenuItem("copy", "Copy path", {}, ClineIcons.ContentCopy)),
            menuLabel = "More actions for release",
        )
    }
}

@LightDarkPreviews
@Composable
private fun ExtensionCardUninstallingPreview() {
    ComponentPreview {
        ExtensionCard(
            icon = ClineIcons.Bolt,
            title = "review",
            badges = listOf("Global", "skill", "Marketplace"),
            description = "Review a pull request for correctness.",
            dimmed = true,
            switch = CardSwitch(checked = true, enabled = true, label = "Toggle review", onCheckedChange = {}, saving = true),
            menuItems = listOf(ContextMenuItem("uninstall", "Uninstall", {}, ClineIcons.Delete, destructive = true, pendingLabel = "Uninstalling...")),
            menuLabel = "More actions for review",
            pendingMenuItemId = "uninstall",
            bottom = { ActionMessageLine(ActionMessage.Uninstalled("old-skill", null)) },
        )
    }
}

@LightDarkPreviews
@Composable
private fun ExtensionCardPluginExpandedPreview() {
    ComponentPreview {
        ExtensionCard(
            icon = ClineIcons.Extension,
            title = "my-plugin",
            badges = listOf("Global", "Cline Plugin"),
            switch = CardSwitch(checked = true, enabled = true, label = "Toggle my-plugin", onCheckedChange = {}),
            expanded = true,
            onClick = {},
            expandedContent = { Text("Adds lint helpers for TypeScript.", style = MaterialTheme.typography.bodySmall) },
        )
    }
}

@LightDarkPreviews
@Composable
private fun ExtensionCardMcpPreview() {
    ComponentPreview {
        ExtensionCard(
            icon = ClineIcons.DeployedCode,
            title = "github",
            titleLeading = { McpStatusDot(enabled = true) },
            badges = listOf("Local · stdio"),
            switch = CardSwitch(checked = true, enabled = true, label = "Enable github", onCheckedChange = {}),
            extra = {
                Text("Command: npx", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Env: GITHUB_TOKEN=[REDACTED]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            footer = {
                IconButton(onClick = {}) { Icon(ClineIcons.Edit, contentDescription = "Edit github") }
                IconButton(onClick = {}) { Icon(ClineIcons.Delete, contentDescription = "Delete github") }
            },
            onClick = {},
        )
    }
}

/** Setup disclosures, one closed and one open, in LTR and RTL: the open chevron points down in both. */
@LightDarkPreviews
@Composable
private fun MarketplaceSetupDisclosuresPreview() {
    fun entry(id: String, name: String) = dev.cline.termux.data.marketplace.MarketplaceEntry(
        id = id,
        type = dev.cline.termux.data.marketplace.MarketplaceType.Mcp,
        name = name,
        tagline = name,
        description = name,
        install = dev.cline.termux.data.marketplace.MarketplaceInstall(
            args = listOf(id),
            command = "npx",
            env = listOf(dev.cline.termux.data.marketplace.MarketplaceEnvVar("GITHUB_TOKEN", required = true)),
        ),
    )
    val entries = listOf(entry("github", "GitHub"), entry("gitlab", "GitLab"))
    ComponentPreview {
        Column {
            LayoutDirection.entries.forEach { direction ->
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    MarketplaceSetupDisclosures("mcp:github", entries, setOf("setup:mcp:github:mcp:gitlab"), onToggle = {})
                }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun ActionMessageFailurePreview() {
    ComponentPreview {
        ActionMessageLine(ActionMessage.Failed(dev.cline.termux.data.customize.RepoFailure.Helper("skill not found")))
    }
}
