package dev.cline.termux.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.customize.block
import dev.cline.termux.data.marketplace.CliStep
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceEnvVar
import dev.cline.termux.data.marketplace.MarketplaceFailure
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/** What the detail (C4) does when tapped. */
@Immutable
class MarketplaceDetailActions(
    val onInstall: (key: String) -> Unit,
    val onUninstall: (key: String) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onSelectTag: (String) -> Unit,
    val onClose: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
) {
    companion object {
        val Preview = MarketplaceDetailActions({}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * C4 as a pushed page (compact and medium, CR §3.3): `MediumTopAppBar` titled with the entry
 * name and a back arrow; the page KernelBanner and the helper surfaces sit above the buttons.
 * While the catalog is still loading (process death, direct entry) the bar shows its progress
 * line; an entry the catalog does not have shows "This entry is no longer in the marketplace."
 */
@Composable
fun MarketplaceDetailPage(
    state: MarketplaceUiState,
    key: String,
    actions: MarketplaceDetailActions,
    modifier: Modifier = Modifier,
    gutter: Dp = Spacing.gutterCompact,
) {
    val detail = state.detail(key)
    val loading = detail == null && state.catalogState == MarketplaceCatalogState.Loading
    PageScaffold(
        title = detail?.entry?.name ?: stringResource(R.string.marketplace_title),
        modifier = modifier,
        navigationIcon = { BackNavIcon(actions.onClose) },
        loading = loading,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                detail != null -> MarketplaceDetailContent(
                    detail = detail,
                    actions = actions,
                    paneMode = false,
                    helper = state.helper,
                    kernel = state.kernel,
                    contentPadding = PaddingValues(horizontal = gutter, vertical = Spacing.l),
                )
                state.catalogState is MarketplaceCatalogState.Error -> EmptyState(
                    title = state.catalogState.text(),
                    body = null,
                    icon = ClineIcons.CloudOff,
                )
                !loading -> EmptyState(
                    title = stringResource(R.string.marketplace_vanished),
                    body = null,
                    icon = ClineIcons.Storefront,
                )
            }
        }
    }
}

/**
 * The expanded detail pane (CR §3.3): the entry, or the "Select an entry" placeholder when
 * nothing is selected. 32dp padding (desktop `px-8 py-8`), content at most 720dp wide.
 */
@Composable
fun MarketplaceDetailPane(state: MarketplaceUiState, actions: MarketplaceDetailActions, modifier: Modifier = Modifier) {
    val key = state.selectedKey
    val detail = key?.let(state::detail)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                detail != null -> MarketplaceDetailContent(
                    detail = detail,
                    actions = actions,
                    paneMode = true,
                    helper = state.helper,
                    kernel = state.kernel,
                    contentPadding = PaddingValues(Spacing.xxxl),
                )
                key != null && state.catalogState == MarketplaceCatalogState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    title = stringResource(R.string.marketplace_select_title),
                    body = stringResource(R.string.marketplace_select_body),
                    icon = ClineIcons.Storefront,
                )
            }
        }
    }
}

/**
 * The detail body (desktop `DetailPane`): header with the type tile, badges and tagline;
 * Install/Uninstall and "Learn more"; the result message; the type explainer; Author; About
 * with tag chips that filter; Environment setup; install notes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketplaceDetailContent(
    detail: MarketplaceDetail,
    actions: MarketplaceDetailActions,
    paneMode: Boolean,
    helper: HelperState,
    kernel: KernelGate,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val entry = detail.entry
    var confirmUninstall by rememberSaveable(detail.key) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            // The detail's maximum content width (CR §3.3, desktop `max-w-3xl`).
            .widthIn(max = Spacing.pageMaxWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            DetailHeader(detail, paneMode, actions.onClose)
            if (!paneMode) {
                MarketplaceKernelSurface(kernel)
                MarketplaceHelperSurface(helper, kernel, onRetry = actions.onRetryHelper, onStartKernel = actions.onStartKernel)
            }
            DetailButtons(detail, onInstall = { actions.onInstall(detail.key) }, onUninstall = { confirmUninstall = true }, onOpenUrl = actions.onOpenUrl)
            DetailResult(detail, onRetry = { failed -> if (failed.install) actions.onInstall(detail.key) else actions.onUninstall(detail.key) })
        }
        TypeExplainer(entry.type)
        entry.author?.let { author -> AuthorCard(author.name, author.url, actions.onOpenUrl) }
        AboutSection(detail, actions.onSelectTag)
        if (detail.env.isNotEmpty()) EnvSection(detail.env, actions.onOpenUrl)
        entry.install.notes?.takeIf { it.isNotBlank() }?.let { InstallNotes(it) }
    }

    if (confirmUninstall) {
        ConfirmDialog(
            title = stringResource(R.string.marketplace_uninstall_title, entry.name),
            text = stringResource(R.string.marketplace_uninstall_body, entry.name),
            confirmLabel = stringResource(R.string.marketplace_uninstall),
            destructive = true,
            onConfirm = {
                confirmUninstall = false
                actions.onUninstall(detail.key)
            },
            onDismiss = { confirmUninstall = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(detail: MarketplaceDetail, paneMode: Boolean, onClose: () -> Unit) {
    val entry = detail.entry
    Row(verticalAlignment = Alignment.Top) {
        TypeTile(entry.type, size = 48.dp, iconSize = 24.dp)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (paneMode) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (entry.verified == true) VerifiedBadge()
                TypePill(entry.type)
            }
            Text(entry.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (paneMode) {
            TooltipIconButton(
                icon = ClineIcons.Close,
                contentDescription = stringResource(R.string.marketplace_close_details),
                onClick = onClose,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailButtons(detail: MarketplaceDetail, onInstall: () -> Unit, onUninstall: () -> Unit, onOpenUrl: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        val label = stringResource(detail.button.label())
        when (detail.button) {
            PrimaryButton.Checking, PrimaryButton.Installing, PrimaryButton.Uninstalling -> Button(onClick = {}, enabled = false) {
                InlineSpinner()
                Spacer(Modifier.width(Spacing.s))
                Text(label)
            }
            PrimaryButton.Install -> Button(onClick = onInstall, enabled = detail.buttonEnabled) { Text(label) }
            // DS §4.3: destructive outside dialogs is a TextButton in `error`; the confirm dialog follows.
            PrimaryButton.Uninstall -> TextButton(
                onClick = onUninstall,
                enabled = detail.buttonEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(ClineIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(label)
            }
        }
        detail.learnMoreUrl?.let { url ->
            OutlinedButton(onClick = { onOpenUrl(url) }) {
                Icon(ClineIcons.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.marketplace_learn_more))
                Spacer(Modifier.width(Spacing.xs))
                Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The result line (CR §3.4): polite for results, assertive for failures, at most 12 lines until "Show more". */
@Composable
private fun DetailResult(detail: MarketplaceDetail, onRetry: (EntryAction.Failed) -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (detail.vanished) {
            Text(stringResource(R.string.marketplace_vanished), style = small, color = muted)
        }
        when (val action = detail.action) {
            is EntryAction.Done -> Text(
                action.done.toUiText().resolve(),
                style = small,
                color = muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            EntryAction.Interrupted -> Text(
                stringResource(R.string.marketplace_interrupted),
                style = small,
                color = muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            is EntryAction.Failed -> FailureResult(action, onRetry)
            EntryAction.Idle, is EntryAction.Installing, is EntryAction.Uninstalling -> Unit
        }
    }
}

@Composable
private fun FailureResult(action: EntryAction.Failed, onRetry: (EntryAction.Failed) -> Unit) {
    val failure = action.failure
    val repoFailure = (failure as? MarketplaceFailure.Repo)?.failure
    val block = (repoFailure as? RepoFailure.Termux)?.problem?.block()
    if (block != null) {
        MarketplaceBlockedCard(block, onRetry = { onRetry(action) })
        return
    }
    val small = MaterialTheme.typography.bodySmall
    val error = MaterialTheme.colorScheme.error
    val text = failure.toUiText().resolve()
    // A CLI-route failure: the worded headline, then the CLI's own output as LTR data
    // (DS §8.2.6); the headline counts toward the 12 visible lines.
    val output = (failure as? MarketplaceFailure.Cli)?.output?.takeIf { it.isNotEmpty() }
    val body = output ?: text
    var expanded by rememberSaveable(text, body) { mutableStateOf(false) }
    val collapsed = if (output != null) collapsedFailure(output, FAILURE_COLLAPSED_LINES - 1) else collapsedFailure(text)
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
        val shown = if (expanded || collapsed == null) body else collapsed
        when {
            output != null -> {
                Text(text, style = small, color = error)
                LtrText(text = shown, style = small, color = error)
            }
            repoFailure is RepoFailure.Command -> LtrText(text = shown, style = small, color = error)
            else -> Text(shown, style = small, color = error)
        }
        if (collapsed != null && !expanded) {
            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.marketplace_show_more)) }
        }
    }
}

/** The type explainer (desktop: "<Label>." in the type colour + the blurb, on the type tint). */
@Composable
private fun TypeExplainer(type: MarketplaceType) {
    val tint = type.tint()
    val copy = type.copy()
    val label = stringResource(copy.label)
    val full = stringResource(R.string.marketplace_explainer, label, stringResource(copy.blurb))
    val text = buildAnnotatedString {
        append(full)
        val at = full.indexOf(label)
        if (at >= 0) {
            // The label and the stop that follows it ("Skill.") carry the emphasis.
            val end = (at + label.length).let { if (it < full.length && !full[it].isWhitespace()) it + 1 else it }
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, end)
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = tint.container, contentColor = tint.content) {
        Row(Modifier.fillMaxWidth().padding(Spacing.m), verticalAlignment = Alignment.Top) {
            Icon(type.glyph, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Author card (desktop `MetaCell` in a bordered card): tappable when the author has a URL. */
@Composable
private fun AuthorCard(name: String, url: String?, onOpenUrl: (String) -> Unit) {
    val description = if (url != null) {
        stringResource(R.string.marketplace_author_link_description, name)
    } else {
        stringResource(R.string.marketplace_author_description, name)
    }
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.l)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ClineIcons.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.marketplace_author), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (url != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    if (url != null) {
        OutlinedCard(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth().focusRing(MaterialTheme.shapes.medium).semantics { role = Role.Button }) { content() }
    } else {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutSection(detail: MarketplaceDetail, onSelectTag: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(R.string.marketplace_about),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(detail.entry.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail.entry.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                detail.entry.tags.forEach { tag ->
                    val label = detail.tagLabel(tag)
                    val description = stringResource(R.string.marketplace_filter_by_tag, label)
                    AssistChip(
                        onClick = { onSelectTag(tag) },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvSection(env: List<MarketplaceEnvVar>, onOpenUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(R.string.marketplace_env_setup),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        env.forEach { variable -> EnvCard(variable, onOpenUrl) }
    }
}

@Composable
private fun EnvCard(variable: MarketplaceEnvVar, onOpenUrl: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.m, bottom = if (variable.url != null) Spacing.xs else Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LtrText(
                    text = variable.name,
                    // Geist Mono ships 400 and 500 only (DS §2.2); Medium on an ASCII name (DS §2.3).
                    style = ClineMono.small.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f).semantics { contentDescription = variable.name },
                )
                Spacer(Modifier.width(Spacing.s))
                TagBadge(
                    stringResource(if (variable.required == false) R.string.marketplace_env_optional else R.string.marketplace_env_required),
                )
            }
            variable.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            variable.url?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                    Text(stringResource(R.string.marketplace_env_get_value))
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun InstallNotes(notes: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.m))
    }
}

// ---- previews --------------------------------------------------------------------------------

private fun previewDetail(action: EntryAction = EntryAction.Idle, installed: Boolean = true, ready: Boolean = true, vanished: Boolean = false, blocked: Boolean = false, entry: MarketplaceEntry = MarketplacePreviewData.github) =
    MarketplaceDetail(
        entry = entry,
        installed = installed,
        installedReady = ready,
        action = action,
        vanished = vanished,
        kernelBlocked = blocked,
        tagLabels = MarketplacePreviewData.catalog.tagLabels,
    )

@Composable
private fun DetailPreview(detail: MarketplaceDetail, paneMode: Boolean = false) {
    ClinePreviewTheme {
        Surface {
            MarketplaceDetailContent(
                detail = detail,
                actions = MarketplaceDetailActions.Preview,
                paneMode = paneMode,
                helper = HelperState.Ready,
                kernel = KernelGate.Ready,
                contentPadding = PaddingValues(Spacing.l),
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun DetailInstalledPreview() = DetailPreview(previewDetail(EntryAction.Done(MarketplaceDone.Installed("GitHub", global = false), installed = true)))

@LightDarkPreviews
@Composable
private fun DetailInstallPanePreview() = DetailPreview(previewDetail(installed = false), paneMode = true)

@LightDarkPreviews
@Composable
private fun DetailCheckingPreview() = DetailPreview(previewDetail(ready = false))

@LightDarkPreviews
@Composable
private fun DetailInstallingPreview() = DetailPreview(previewDetail(EntryAction.Installing(0), installed = false))

@LightDarkPreviews
@Composable
private fun DetailUninstallingPreview() = DetailPreview(previewDetail(EntryAction.Uninstalling(0)))

@LightDarkPreviews
@Composable
private fun DetailFailedPreview() = DetailPreview(
    previewDetail(
        EntryAction.Failed(MarketplaceFailure.Cli(CliStep.McpInstall, 1, (1..16).joinToString("\n") { "npm ERR! line $it" }), install = true),
        installed = false,
    ),
)

@LightDarkPreviews
@Composable
private fun DetailTermuxBlockedPreview() =
    DetailPreview(previewDetail(EntryAction.Failed(RepoFailure.Termux(TermuxProblem.TimedOut), install = true), installed = false))

@LightDarkPreviews
@Composable
private fun DetailInterruptedPreview() = DetailPreview(previewDetail(EntryAction.Interrupted, installed = false))

@LightDarkPreviews
@Composable
private fun DetailVanishedPreview() = DetailPreview(previewDetail(installed = false, vanished = true))

@LightDarkPreviews
@Composable
private fun DetailKernelDownPreview() = DetailPreview(previewDetail(installed = false, blocked = true))

@LightDarkPreviews
@Composable
private fun DetailPlaceholderPreview() {
    ClinePreviewTheme { MarketplaceDetailPane(MarketplacePreviewData.loaded(), MarketplaceDetailActions.Preview) }
}

@LightDarkPreviews
@Composable
private fun DetailPagePreview() {
    ClinePreviewTheme { MarketplaceDetailPage(MarketplacePreviewData.loaded(), MarketplacePreviewData.github.key, MarketplaceDetailActions.Preview) }
}
