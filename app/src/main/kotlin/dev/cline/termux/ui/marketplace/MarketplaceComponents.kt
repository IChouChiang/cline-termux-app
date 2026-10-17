package dev.cline.termux.ui.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.rememberKernelBlockHandler
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.LocalShellNavigator
import dev.cline.termux.ui.sessions.joinA11y
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/*
 * Marketplace building blocks (customize-and-rest.md §3.3, §3.5, Appendix A "TypeTile"):
 * the per-type tile and pill, the verified badge, section headers, list rows, the category
 * sheet and the helper / kernel surfaces both panes show.
 */

/** Appendix A TypeTile: a [size] tile in the type tint with the type glyph (desktop `MarketplaceTypeGlyph`). */
@Composable
fun TypeTile(type: MarketplaceType, modifier: Modifier = Modifier, size: Dp = 40.dp, iconSize: Dp = 20.dp) {
    val tint = type.tint()
    Surface(
        modifier = modifier.size(size),
        shape = if (size > 40.dp) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = tint.container,
        contentColor = tint.content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(type.glyph, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

/** The type pill (desktop `MarketplaceTypePill`): the short label on the type tint, glyph 16dp. */
@Composable
fun TypePill(type: MarketplaceType, modifier: Modifier = Modifier) {
    val tint = type.tint()
    TagBadge(
        text = stringResource(type.copy().short),
        modifier = modifier,
        icon = type.glyph,
        containerColor = tint.container,
        contentColor = tint.content,
    )
}

/** "Verified" badge of the detail header: `verified` (fill 1) in `info.color` + the word. */
@Composable
fun VerifiedBadge(modifier: Modifier = Modifier) {
    TagBadge(
        text = stringResource(R.string.marketplace_verified),
        modifier = modifier,
        icon = ClineIcons.VerifiedFilled,
        containerColor = ClineTheme.colors.info.container,
        contentColor = ClineTheme.colors.info.onContainer,
    )
}

/** Section header (desktop `SectionHeader`): glyph tile 20dp, plural `titleSmall`, blurb `bodySmall`. */
@Composable
fun MarketplaceSectionHeader(type: MarketplaceType, modifier: Modifier = Modifier) {
    val copy = type.copy()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Top,
    ) {
        TypeTile(type, size = 20.dp, iconSize = 14.dp, modifier = Modifier.padding(top = Spacing.xxs))
        Spacer(Modifier.width(Spacing.s))
        Text(
            stringResource(copy.plural),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Spacing.s))
        Text(
            stringResource(copy.blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(top = Spacing.xxs),
        )
    }
}

/**
 * One catalog row (desktop `MarketplaceListRow`, CR §3.3): `ListItem` 72dp with the type tile,
 * name + verified mark, tagline, the type pill when searching across types, and the trailing
 * installed check. One focus target reading "<name>, <type label>, verified, installed,
 * <tagline>" (CR §3.10). The expanded pane's selected row uses `secondaryContainer`.
 */
@Composable
fun MarketplaceListRow(
    row: MarketplaceRow,
    showTypePill: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // DS §1.8 / §8.1.9: on the selected row's secondaryContainer every line is onSecondaryContainer.
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val muted = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val description = context.joinA11y(
        listOfNotNull(
            row.name,
            stringResource(row.type.copy().label),
            stringResource(R.string.marketplace_verified).takeIf { row.verified },
            stringResource(R.string.marketplace_installed).takeIf { row.installed },
            row.tagline,
        ),
    )
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            headlineColor = content,
            supportingColor = muted,
            trailingIconColor = muted,
        ),
        leadingContent = { TypeTile(row.type) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.verified) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        ClineIcons.VerifiedFilled,
                        contentDescription = null,
                        tint = ClineTheme.colors.info.color,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (showTypePill) {
                    Spacer(Modifier.width(Spacing.s))
                    TypePill(row.type)
                }
            }
        },
        supportingContent = {
            Text(
                row.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = if (row.installed) {
            {
                Icon(
                    ClineIcons.Check,
                    contentDescription = null,
                    tint = ClineTheme.colors.success.color,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            null
        },
    )
}

/**
 * The category sheet (CR §3.3): "Filter by category", a radio list of "All categories" and
 * each visible tag with its count. Choosing applies and closes; back or swipe closes without
 * change. The title takes focus on open and [returnFocus] (the chip) gets it back on close (ClineSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    tags: List<TagCount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    // A long category list opens full; one predictive back closes it (DS §5.8). Focus goes back
    // to the category chip on close (DS §8.1.7).
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.marketplace_filter_by_category),
        returnFocus = returnFocus,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            CategoryRow(label = stringResource(R.string.marketplace_all_categories), count = null, selected = selected == null) { onSelect(null) }
            tags.forEach { tag ->
                CategoryRow(label = tag.label, count = tag.count, selected = selected == tag.id) { onSelect(tag.id) }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = Spacing.s),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = count?.let {
            {
                Text(it.toString(), style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * The helper surfaces of CR §3.4: "Starting settings helper..." under the app bar while it
 * starts, and the shared SP §0.3 card with the marketplace body when it is unavailable
 * ("Start kernel" variant when the user stopped the kernel). A RUN_COMMAND block (SP §0.5)
 * shows its KernelBlockedCard instead, unless [kernel] is blocked and the kernel surface
 * already shows one.
 */
@Composable
fun MarketplaceHelperSurface(
    helper: HelperState,
    kernel: KernelGate,
    onRetry: () -> Unit,
    onStartKernel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (helper) {
        is HelperState.Starting -> Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.marketplace_helper_starting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        is HelperState.Unavailable -> when (val view = helper.marketplaceView(kernel)) {
            is MarketplaceHelperView.Card -> HelperUnavailableCard(
                model = view.model,
                modifier = modifier,
                body = stringResource(R.string.marketplace_helper_body),
                onRetry = onRetry,
                onStartKernel = onStartKernel,
            )
            is MarketplaceHelperView.Blocked -> MarketplaceBlockedCard(view.block, modifier, onRetry = onRetry)
            MarketplaceHelperView.None -> Unit
        }
        HelperState.Idle, HelperState.Ready -> Unit
    }
}

/**
 * The kernel surface at the top of a marketplace pane (CR §0.4, §3.4): the page KernelBanner
 * for Down / Busy, the KernelBlockedCard for a blocked gate. The catalog stays browsable.
 */
@Composable
fun MarketplaceKernelSurface(gate: KernelGate, modifier: Modifier = Modifier) {
    if (gate is KernelGate.Blocked) {
        MarketplaceBlockedCard(KernelBlock.of(gate), modifier)
    } else {
        ShellKernelBanner(hasComposer = false, modifier = modifier)
    }
}

/** A KernelBlockedCard whose buttons do what SP §0.5 says (the shared [rememberKernelBlockHandler]). */
@Composable
fun MarketplaceBlockedCard(block: KernelBlock, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    val handler = rememberKernelBlockHandler(LocalShellNavigator.current, onRetry)
    KernelBlockedCard(
        block = block,
        modifier = modifier,
        permissionPermanentlyDenied = handler.permissionPermanentlyDenied,
        onAction = { action -> handler.onAction(block, action) },
    )
}

// ---- previews --------------------------------------------------------------------------------

@LightDarkPreviews
@Composable
private fun TypeTilesPreview() {
    ComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            MarketplaceType.entries.forEach { TypeTile(it) }
            MarketplaceType.entries.forEach { TypePill(it) }
            VerifiedBadge()
        }
    }
}

@LightDarkPreviews
@Composable
private fun SectionHeaderPreview() {
    ComponentPreview(padding = false) {
        Column { MarketplaceType.entries.forEach { MarketplaceSectionHeader(it) } }
    }
}

@LightDarkPreviews
@Composable
private fun ListRowsPreview() {
    ComponentPreview(padding = false) {
        Column {
            MarketplaceListRow(MarketplaceRow("skill:review", "Code review", "Review diffs for bugs and style", MarketplaceType.Skill, false, true), showTypePill = false, selected = false, onClick = {})
            MarketplaceListRow(MarketplaceRow("mcp:github", "GitHub", "Issues, PRs and repos as tools", MarketplaceType.Mcp, true, true), showTypePill = true, selected = true, onClick = {})
            MarketplaceListRow(MarketplaceRow("plugin:lint", "Lint", "Lint on every edit", MarketplaceType.Plugin, false, false), showTypePill = true, selected = false, onClick = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun HelperSurfacesPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            val ready = KernelGate.Ready
            MarketplaceHelperSurface(HelperState.Starting(0), ready, onRetry = {}, onStartKernel = {})
            MarketplaceHelperSurface(HelperState.Unavailable(HelperFailure.StartTimeout, "error: listen EADDRINUSE"), ready, onRetry = {}, onStartKernel = {})
            MarketplaceHelperSurface(HelperState.Unavailable(HelperFailure.PortInUse, null), ready, onRetry = {}, onStartKernel = {})
            MarketplaceHelperSurface(HelperState.Unavailable(HelperFailure.BuildUnsupported, "HTTP 426"), ready, onRetry = {}, onStartKernel = {})
            MarketplaceHelperSurface(HelperState.Unavailable(HelperFailure.KernelStopped, null), ready, onRetry = {}, onStartKernel = {})
            MarketplaceHelperSurface(HelperState.Unavailable(HelperFailure.ExternalAppsBlocked, "allow-external-apps is false"), ready, onRetry = {}, onStartKernel = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun CategoryRowsPreview() {
    ComponentPreview(padding = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).selectableGroup()) {
                CategoryRow(stringResource(R.string.marketplace_all_categories), null, selected = false) {}
                CategoryRow("Developer tools", 12, selected = true) {}
                CategoryRow("Git", 4, selected = false) {}
            }
        }
    }
}
