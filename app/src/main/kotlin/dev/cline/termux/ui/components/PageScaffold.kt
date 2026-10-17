package dev.cline.termux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.isHeightCompact
import dev.cline.termux.ui.theme.ClineMotion

// Adapted from cline-termux-voice (same owner): ui/Components.kt (`PageScaffold`).

/**
 * A pushed or top-level page (DS §5.3, §4.1 #12): a Material 3 medium top app bar that
 * collapses as the content scrolls (`exitUntilCollapsed`), a navigation-icon slot, optional
 * actions, an optional progress line and a floating action button. The window is edge to
 * edge; the bar takes the status-bar inset and the content gets the rest, never closer to
 * the bottom than [WindowInsets.bottomSafe].
 *
 * The bar stays `surface` in every state; a 1dp `outlineVariant` hairline fades in over
 * 150 ms only while content is scrolled beneath it (DS §3.2 level 2).
 *
 * [navigationIcon] is [BackNavIcon] on a pushed page, [MenuNavIcon] on a top-level
 * destination on compact, and null on medium/expanded where the rail holds `menu`
 * (customize-and-rest.md Appendix A amendment).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    loading: Boolean = false,
    loadingDescription: String? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = pageScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        // DS §5.6: M3 places the FAB 16dp above these insets and pads the content by them, so
        // both clear the gesture handle (gesture mode reports a zero navigation-bar inset).
        // The Scaffold itself leaves out insets an ancestor already consumed.
        contentWindowInsets = pageWindowInsets(ScaffoldDefaults.contentWindowInsets, WindowInsets.bottomSafe),
        snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
        topBar = {
            PageAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
                loading = loading,
                loadingDescription = loadingDescription,
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * The insets [PageScaffold] gives M3 (DS §5.6): the Scaffold's own system-bar insets ∪
 * [bottomSafe], so the FAB sits 16dp above the bottom safe area rather than above a
 * navigation bar that gesture mode reports as zero, and the content's bottom padding clears
 * the handle. A bottom bar keeps its own height, as M3 does.
 */
internal fun pageWindowInsets(scaffoldInsets: WindowInsets, bottomSafe: WindowInsets): WindowInsets =
    scaffoldInsets.union(bottomSafe)

/**
 * The scroll behaviour [PageAppBar] expects: the medium bar collapses (`exitUntilCollapsed`);
 * the small bar of a height-compact window stays pinned, because `exitUntilCollapsed` would
 * scroll a single-row bar out of the window entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun pageScrollBehavior(): TopAppBarScrollBehavior =
    if (isHeightCompact) TopAppBarDefaults.pinnedScrollBehavior() else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

/**
 * The page app bar on its own, for screens that build their own `Scaffold` (list-detail
 * panes): `MediumTopAppBar` + the scroll hairline + an optional indeterminate progress line
 * (DS #12 "loading"). [loadingDescription] is the progress line's spoken label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    loading: Boolean = false,
    loadingDescription: String? = null,
) {
    val hairline = scrollHairlineAlpha(scrollBehavior)
    val colors = barColors()
    Column(modifier) {
        if (isHeightCompact) {
            // Phone landscape: the small bar, no medium collapse (DS §5.1, customize-and-rest §0.6).
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { navigationIcon?.invoke() },
                actions = actions,
                colors = colors,
                scrollBehavior = scrollBehavior,
            )
        } else {
            MediumTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { navigationIcon?.invoke() },
                actions = actions,
                colors = colors,
                scrollBehavior = scrollBehavior,
            )
        }
        BarHairlineAndProgress(hairline, loading, loadingDescription)
    }
}

/**
 * The small, pinned app bar of a list-detail pane (DS §3.2 level 2, §5.3): a `TopAppBar` that
 * stays `surface`, the same 1dp `outlineVariant` hairline as [PageAppBar] fading in over 150 ms
 * while content scrolls beneath it, and an optional full-width indeterminate progress line.
 * Pair it with [panePageScrollBehavior] and `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`
 * on the pane's Scaffold or Column. [loadingDescription] is the progress line's spoken label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanePageAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    loading: Boolean = false,
    loadingDescription: String? = null,
) {
    val hairline = scrollHairlineAlpha(scrollBehavior)
    Column(modifier) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = barColors(),
            scrollBehavior = scrollBehavior,
        )
        BarHairlineAndProgress(hairline, loading, loadingDescription)
    }
}

/** The scroll behaviour [PanePageAppBar] expects: pinned, so the pane bar never scrolls away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun panePageScrollBehavior(): TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

/** DS §3.2 level 2: 0 → 1 over `Short3` (150 ms) once content scrolls under the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun scrollHairlineAlpha(scrollBehavior: TopAppBarScrollBehavior): Float {
    val scrolledUnder by remember(scrollBehavior) { derivedStateOf { scrollBehavior.state.overlappedFraction > 0.01f } }
    val hairline by animateFloatAsState(if (scrolledUnder) 1f else 0f, tween(ClineMotion.Short3), label = "hairline")
    return hairline
}

/** The bar never tints on scroll; the hairline marks the edge instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun BarHairlineAndProgress(hairline: Float, loading: Boolean, loadingDescription: String?) {
    HorizontalDivider(
        Modifier.graphicsLayer { alpha = hairline },
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    if (loading) {
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .then(if (loadingDescription != null) Modifier.semantics { contentDescription = loadingDescription } else Modifier),
        )
    }
}

/** `arrow_back` with the desktop label "Back" (DS §5.3), tooltip on long-press (DS §4.1 #14). */
@Composable
fun BackNavIcon(onBack: () -> Unit) {
    TooltipIconButton(ClineIcons.Chrome.Back, contentDescription = stringResource(R.string.components_back), onClick = onBack)
}

/** `menu` "Open sessions" for a top-level destination on compact (DS §5.2), with its tooltip. */
@Composable
fun MenuNavIcon(onOpen: () -> Unit) {
    TooltipIconButton(ClineIcons.Chrome.Menu, contentDescription = stringResource(R.string.components_open_sessions), onClick = onOpen)
}

@LightDarkPreviews
@Composable
private fun PageScaffoldPreview() {
    ComponentPreview(padding = false) {
        PageScaffold(title = "Settings", navigationIcon = { BackNavIcon {} }) { padding ->
            LazyColumn(contentPadding = padding) {
                items(12) { Text("Row $it", Modifier.padding(16.dp)) }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun PageScaffoldFabPreview() {
    ComponentPreview(padding = false) {
        PageScaffold(
            title = "Sessions",
            navigationIcon = { MenuNavIcon {} },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("New session") },
                    icon = { Icon(ClineIcons.Add, contentDescription = "New session") },
                    onClick = {},
                )
            },
        ) { padding ->
            LazyColumn(contentPadding = padding) { items(12) { Text("Row $it", Modifier.padding(16.dp)) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@LightDarkPreviews
@Composable
private fun PanePageAppBarPreview() {
    ComponentPreview(padding = false) {
        val scrollBehavior = panePageScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = { PanePageAppBar(title = "Providers", scrollBehavior = scrollBehavior, navigationIcon = { BackNavIcon {} }) },
        ) { padding ->
            LazyColumn(contentPadding = padding) { items(12) { Text("Row $it", Modifier.padding(16.dp)) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@LightDarkPreviews
@Composable
private fun PanePageAppBarLoadingPreview() {
    ComponentPreview(padding = false) {
        PanePageAppBar(title = "Schedule", scrollBehavior = panePageScrollBehavior(), loading = true)
    }
}

@LightDarkPreviews
@Composable
private fun PageScaffoldLoadingPreview() {
    ComponentPreview(padding = false) {
        PageScaffold(title = "Customize", navigationIcon = { MenuNavIcon {} }, loading = true) { padding ->
            LazyColumn(contentPadding = padding) { items(3) { Text("Row $it", Modifier.padding(16.dp)) } }
        }
    }
}
