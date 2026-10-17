package dev.cline.termux.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.about.AppLinks
import dev.cline.termux.data.about.ChangelogRelease
import dev.cline.termux.data.about.WHATS_NEW_RELEASES
import dev.cline.termux.data.about.isInstalledRelease
import dev.cline.termux.debug.DebugScreens
import dev.cline.termux.ui.account.blockOrNull
import dev.cline.termux.ui.account.onKernelBlockAction
import dev.cline.termux.ui.account.rememberLabelledCopy
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.SectionHeader
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.markdown.MarkdownText
import dev.cline.termux.ui.navigation.AboutDiagnostics
import dev.cline.termux.ui.navigation.AboutLicenses
import dev.cline.termux.ui.navigation.DebugKernelProbe
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/** What the About page can do; [onDeveloper] is null outside debug builds. */
class AboutActions(
    /** Null in the expanded Settings detail pane (no back arrow there, SP §0.2). */
    val onBack: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val onKernelStatus: () -> Unit,
    val onShowHighlights: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onDiagnostics: () -> Unit,
    val onLicenses: () -> Unit,
    val onDeveloper: (() -> Unit)?,
    val onBlockAction: (KernelBlock, KernelBlockAction) -> Unit,
)

/** The `about` destination (customize-and-rest.md C10). */
@Composable
fun AboutRoute(navigator: ShellNavigator, graph: AppGraph, inPane: Boolean = false) {
    val context = LocalContext.current
    val viewModel: AboutViewModel = viewModel(factory = AboutViewModel.factory(context, graph))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val openUrl = rememberOpenExternal()
    var showHighlights by rememberSaveable { mutableStateOf(false) }
    val actions = remember(navigator, viewModel, openUrl) {
        AboutActions(
            onBack = if (inPane) null else ({ navigator.back() }),
            onRefresh = viewModel::refresh,
            onKernelStatus = { navigator.openKernelStatus() },
            onShowHighlights = { showHighlights = true },
            onOpenUrl = openUrl,
            onDiagnostics = { navigator.push(AboutDiagnostics) },
            onLicenses = { navigator.push(AboutLicenses) },
            onDeveloper = if (DebugScreens.available) ({ navigator.push(DebugKernelProbe) }) else null,
            onBlockAction = { block, action -> navigator.onKernelBlockAction(block, action) { viewModel.refresh() } },
        )
    }
    AboutScreen(state, actions)
    val release = state.whatsNew
    // Replaying from About never marks the release as seen (§8.1).
    if (showHighlights && release != null) {
        WhatsNewDialog(release, onClose = { showHighlights = false }, onSeeAll = null)
    }
}

/** C10, stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(state: AboutUiState, actions: AboutActions) {
    val anyChecking = state.kernelRelease == VersionValue.Checking || state.cliVersion == VersionValue.Checking || state.hub == VersionValue.Checking
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(anyChecking) { if (!anyChecking) pulled = false }
    PageScaffold(title = stringResource(R.string.about_title), navigationIcon = actions.onBack?.let { back -> { BackNavIcon(back) } }) { padding ->
        PullToRefreshBox(
            isRefreshing = pulled && anyChecking,
            onRefresh = {
                pulled = true
                actions.onRefresh()
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth().padding(bottom = Spacing.xxl)) {
                    AboutHeader(state)
                    UnofficialNotice()
                    VersionsSection(state, actions)
                    LinksSection(state, actions)
                    ReleaseNotesSection(state, actions)
                }
            }
        }
    }
}

@Composable
private fun AboutHeader(state: AboutUiState) {
    val gutter = LocalShellLayout.current.gutter
    Column(
        Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(ClineIcons.AppLogo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(if (state.beta) R.string.about_product_beta else R.string.about_product),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            LtrText(stringResource(R.string.about_version, state.version), style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.beta) TagBadge(stringResource(R.string.about_beta))
        }
    }
}

/** The unofficial notice (2026-10-16): who makes the app, whose names it uses, where to report problems. */
@Composable
private fun UnofficialNotice() {
    val gutter = LocalShellLayout.current.gutter
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Icon(ClineIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(R.string.about_notice), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VersionsSection(state: AboutUiState, actions: AboutActions) {
    val gutter = LocalShellLayout.current.gutter
    SectionHeader(stringResource(R.string.about_versions), Modifier.padding(horizontal = sectionHeaderInset(gutter)))
    state.kernel.blockOrNull()?.let { block ->
        KernelBlockedCard(block, onAction = { actions.onBlockAction(block, it) }, modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s))
    }
    VersionRow(stringResource(R.string.about_app), VersionValue.Value(stringResource(R.string.about_app_value, state.version, state.versionCode)))
    VersionRow(stringResource(R.string.about_kernel_release), state.kernelRelease)
    VersionRow(stringResource(R.string.about_cli), state.cliVersion)
    val hub = when (val value = state.hub) {
        is VersionValue.Value -> {
            val build = state.hubVersion?.buildId
            VersionValue.Value(
                if (build != null) stringResource(R.string.about_hub_value, value.text, build) else stringResource(R.string.about_hub_value_core, value.text),
            )
        }
        else -> value
    }
    VersionRow(stringResource(R.string.about_hub), hub)
    NavRow(ClineIcons.AppLogo, stringResource(R.string.about_kernel_status), null, actions.onKernelStatus)
}

/** A label/value row; long-press copies the value (with a TalkBack custom long-click action). */
@Composable
private fun VersionRow(label: String, value: VersionValue) {
    val copy = rememberLabelledCopy()
    val text = when (value) {
        VersionValue.Checking -> stringResource(R.string.about_checking)
        VersionValue.NotRunning -> stringResource(R.string.about_not_running)
        VersionValue.Unavailable -> stringResource(R.string.about_unavailable)
        is VersionValue.Value -> value.text
    }
    val copyable = (value as? VersionValue.Value)?.text
    val copyLabel = stringResource(R.string.about_copy)
    val description = stringResource(R.string.about_version_row_a11y, label, text)
    val gutter = LocalShellLayout.current.gutter
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (copyable != null) Modifier.pointerInput(copyable) { detectTapGestures(onLongPress = { copy(label, copyable) }) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                if (copyable != null) {
                    onLongClick(label = copyLabel) {
                        copy(label, copyable)
                        true
                    }
                }
            }
            .padding(horizontal = gutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.l))
        if (copyable != null) {
            LtrText(text, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, readAsWritten = false)
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, body: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        trailingContent = { Icon(ClineIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A row with a named action: the whole row is the target on compact, an outlined button on medium+. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, body: String, actionLabel: String, external: Boolean, onClick: () -> Unit) {
    val wide = LocalShellLayout.current.width != WidthClass.Compact
    ListItem(
        modifier = if (wide) Modifier else Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = {
            if (wide) {
                OutlinedButton(onClick = onClick) { Text(actionLabel) }
            } else {
                Icon(if (external) ClineIcons.OpenInNew else ClineIcons.ChevronRight, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun LinksSection(state: AboutUiState, actions: AboutActions) {
    Spacer(Modifier.height(Spacing.s))
    if (state.whatsNew != null) {
        ActionRow(
            ClineIcons.Star,
            stringResource(R.string.about_highlights),
            stringResource(R.string.about_highlights_body),
            stringResource(R.string.about_show_whats_new),
            external = false,
            onClick = actions.onShowHighlights,
        )
    }
    ActionRow(
        ClineIcons.ContactSupport,
        stringResource(R.string.about_report),
        stringResource(R.string.about_report_body),
        stringResource(R.string.about_open_issues),
        external = true,
        onClick = { actions.onOpenUrl(AppLinks.ISSUES_URL) },
    )
    ActionRow(
        ClineIcons.Description,
        stringResource(R.string.about_diagnostics),
        stringResource(R.string.about_diagnostics_body),
        stringResource(R.string.about_export),
        external = false,
        onClick = actions.onDiagnostics,
    )
    NavRow(ClineIcons.LibraryBooks, stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_body), actions.onLicenses)
    actions.onDeveloper?.let { NavRow(ClineIcons.Build, stringResource(R.string.about_developer), stringResource(R.string.about_developer_body), it) }
}

@Composable
private fun ReleaseNotesSection(state: AboutUiState, actions: AboutActions) {
    val gutter = LocalShellLayout.current.gutter
    Spacer(Modifier.height(Spacing.l))
    SectionHeader(
        stringResource(R.string.about_release_notes),
        Modifier.padding(horizontal = sectionHeaderInset(gutter)),
        trailing = {
            TextButton(onClick = { actions.onOpenUrl(AppLinks.CHANGELOG_URL_ON_GITHUB) }) { Text(stringResource(R.string.about_full_changelog)) }
        },
    )
    Column(Modifier.padding(horizontal = gutter)) {
        when (val notes = state.releases) {
            ReleaseNotes.Loading -> NoteText(stringResource(R.string.about_notes_loading))
            ReleaseNotes.Error -> NoteText(stringResource(R.string.about_notes_error))
            is ReleaseNotes.Ready -> notes.releases.forEach { release ->
                ReleaseBlock(release, installed = isInstalledRelease(release, state.version), onOpen = { actions.onOpenUrl(AppLinks.releaseUrl(release.version)) })
            }
        }
    }
}

/**
 * Extra inset for a [SectionHeader] so its title lines up with the rows below at [gutter] (DS §3.3,
 * §4.1 #12): the header already pads `Spacing.l` itself, so only the gutter's excess is added.
 */
internal fun sectionHeaderInset(gutter: Dp): Dp = (gutter - Spacing.l).coerceAtLeast(0.dp)

@Composable
private fun NoteText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.s))
}

@Composable
private fun ReleaseBlock(release: ChangelogRelease, installed: Boolean, onOpen: () -> Unit) {
    val linkLabel = stringResource(R.string.about_release_link_cd)
    Column(Modifier.padding(vertical = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .focusRing(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClickLabel = linkLabel, onClick = onOpen)
                    .semantics { heading() },
                contentAlignment = Alignment.CenterStart,
            ) {
                LtrText(
                    stringResource(R.string.about_version, release.version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (installed) TagBadge(stringResource(R.string.about_installed))
        }
        MarkdownText(release.notes.joinToString("\n") { "- $it" }, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- previews --------------------------------------------------------------------------------

private val previewActions = AboutActions({}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })

private fun previewState(beta: Boolean = false) = AboutUiState(
    version = if (beta) "0.1.0-beta.2" else "0.1.0",
    versionCode = 1,
    beta = beta,
    kernelRelease = VersionValue.Value("3.0.68-termux.2"),
    cliVersion = VersionValue.Value("3.0.68"),
    hub = VersionValue.Value("0.42.1"),
    hubVersion = AboutHubVersion("0.42.1", "9f3c2a1b"),
    whatsNew = WHATS_NEW_RELEASES.firstOrNull(),
    releases = ReleaseNotes.Ready(listOf(ChangelogRelease("0.1.0", listOf("First Android release.", "Export diagnostics from **About**.")))),
)

@LightDarkPreviews
@Composable
private fun AboutPreview() {
    ComponentPreview(padding = false) { AboutScreen(previewState(), previewActions) }
}

/** A source-built Hub (`buildId = "source-<coreVersion>"`): the row reads "core 0.0.90". */
@LightDarkPreviews
@Composable
private fun AboutSourceHubPreview() {
    ComponentPreview(padding = false) {
        AboutScreen(
            previewState().copy(hub = VersionValue.Value("0.0.90"), hubVersion = AboutHubVersion("0.0.90", AboutFormat.shortBuildId("source-0.0.90"))),
            previewActions,
        )
    }
}

@LightDarkPreviews
@Composable
private fun AboutBetaCheckingPreview() {
    ComponentPreview(padding = false) {
        AboutScreen(
            AboutUiState(version = "0.1.0-beta.2", versionCode = 1, beta = true, hub = VersionValue.NotRunning, releases = ReleaseNotes.Loading),
            previewActions,
        )
    }
}

@LightDarkPreviews
@Composable
private fun AboutBlockedPreview() {
    ComponentPreview(padding = false) {
        AboutScreen(
            previewState().copy(
                kernel = KernelGate.Blocked(CheckId.Termux),
                kernelRelease = VersionValue.Unavailable,
                cliVersion = VersionValue.Unavailable,
                hub = VersionValue.Unavailable,
                hubVersion = null,
                releases = ReleaseNotes.Error,
            ),
            previewActions,
        )
    }
}
