package dev.cline.termux.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.data.session.KernelVisual
import dev.cline.termux.ui.chat.BranchChipState
import dev.cline.termux.ui.chat.HomeKernelNotice
import dev.cline.termux.ui.chat.HomeState
import dev.cline.termux.ui.chat.KernelLine
import dev.cline.termux.ui.chat.KernelNoticeState
import dev.cline.termux.ui.chat.WorkspaceChipState
import dev.cline.termux.ui.chat.WorkspaceLabel
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelStatusDot
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.WelcomeHero
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * Home (home-chat.md §2): the welcome hero (bot and grid, no visible heading, as on the
 * desktop), the workspace and branch chips and the kernel notice, centred in the space above
 * the persistent composer the chat screen docks below it (§2.9: the same composer instance
 * carries over into the session after the first send). [heroHidden] on height-compact windows
 * (the chips stay at the top, §2.3); [largeHeading] on medium and expanded windows (96 dp bot,
 * 24 dp gutters); [statusBarInset] when no app bar sits above Home (medium, §2.4).
 * [chipFocus] lets the workspace and branch sheets hand focus back to their chip (DS §8.1.7).
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onWorkspace: () -> Unit,
    onBranch: () -> Unit,
    onKernelStatus: () -> Unit,
    modifier: Modifier = Modifier,
    heroHidden: Boolean = false,
    largeHeading: Boolean = false,
    statusBarInset: Boolean = false,
    chipFocus: HomeChipFocus? = null,
    onWorktreeChange: (Boolean) -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .then(if (statusBarInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (largeHeading) Spacing.gutterWide else Spacing.gutterCompact,
                vertical = if (heroHidden) Spacing.s else Spacing.xxl,
            ),
        contentAlignment = if (heroHidden) Alignment.TopCenter else Alignment.Center,
    ) {
        // §2.3: the bot is centred and the chips are start-aligned below it. Like the desktop
        // (`welcome-chat.tsx`: an sr-only `<h1>` and then `AgentWelcomeHero`), Home shows no
        // visible heading; the screen-reader heading rides on the hero, or on a hairline node
        // when height-compact windows hide the hero.
        Column(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth()) {
            if (heroHidden) {
                HomeHeading(Modifier.fillMaxWidth().height(SrOnlyHeadingHeight))
            } else {
                HomeHeading(Modifier.fillMaxWidth()) {
                    WelcomeHero(size = if (largeHeading) HeroSizeLarge else HeroSizeCompact)
                }
                // Desktop `mt-11` between the hero and the controls row (§2.3: 24 dp below the
                // hero frame, whose grid already extends half a bot below the bot itself).
                Spacer(Modifier.height(Spacing.xxl))
            }
            HomeChips(
                state.workspace,
                state.branch,
                onWorkspace,
                onBranch,
                Modifier.fillMaxWidth(),
                chipFocus,
                worktree = state.worktree,
                onWorktreeChange = onWorktreeChange,
            )
            if (LocalShellKernelUi.current != null) {
                // KS 0.10 / F.3: Home's kernel notice is the shared KernelBanner with F.2's lines
                // ("Couldn't connect to the Cline Hub" for a protocol mismatch, "Kernel stopped",
                // …), bound to the shell's gate; "Kernel status" because the composer carries
                // the primary.
                ShellKernelBanner(hasComposer = true, contentPadding = PaddingValues(top = Spacing.xxl))
            } else {
                state.kernelNotice?.let {
                    Spacer(Modifier.height(Spacing.xxl))
                    KernelNotice(it, onKernelStatus)
                }
            }
        }
    }
}

/**
 * The desktop's sr-only `<h1>`: no visible text, only a TalkBack heading reading "What would you
 * like to build?" (§2.4 Hero row). It wraps the decorative [WelcomeHero] when one is shown, so
 * the focus outline sits on the illustration; otherwise it is a hairline-high node (a zero-size
 * node would be dropped from the accessibility tree).
 */
@Composable
private fun HomeHeading(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val label = stringResource(R.string.components_hero_a11y)
    Box(
        modifier.semantics {
            heading()
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * §2.4: the workspace chip, then the branch chip when the folder is a git checkout, then (G3)
 * the "Worktree" filter chip when [worktree] is not null.
 */
@Composable
fun HomeChips(
    workspace: WorkspaceChipState,
    branch: BranchChipState?,
    onWorkspace: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
    focus: HomeChipFocus? = null,
    worktree: Boolean? = null,
    onWorktreeChange: (Boolean) -> Unit = {},
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val label = workspaceLabel(workspace.label)
        val workspaceCd = stringResource(R.string.home_workspace_cd, workspace.path ?: label)
        AssistChip(
            onClick = onWorkspace,
            label = { ChipLabel(label) },
            leadingIcon = {
                // §2.4 / desktop `Folder`: the same icon for the Chat workspace and a folder.
                Icon(
                    ClineIcons.Chrome.WorkspaceFolder,
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            trailingIcon = { ExpandIcon() },
            modifier = Modifier
                .chipFocus(focus?.workspace)
                .focusRing(MaterialTheme.shapes.small)
                .semantics { contentDescription = workspaceCd },
        )
        if (branch != null) {
            val branchCd = if (branch.busy) {
                stringResource(R.string.home_branch_cd_busy, branch.name)
            } else {
                stringResource(R.string.home_branch_cd, branch.name)
            }
            AssistChip(
                onClick = onBranch,
                label = { ChipLabel(branch.name) },
                leadingIcon = { Icon(ClineIcons.Chrome.Branch, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                trailingIcon = { ExpandIcon() },
                modifier = Modifier
                    .chipFocus(focus?.branch)
                    .focusRing(MaterialTheme.shapes.small)
                    .semantics { contentDescription = branchCd },
            )
        }
        if (branch != null && worktree != null) {
            // G3: the desktop's "Worktree" switch and its "About worktrees" tooltip, as an M3
            // filter chip whose long-press tooltip carries the same text.
            PlainTooltipBox(stringResource(R.string.worktree_tooltip)) {
                FilterChip(
                    selected = worktree,
                    onClick = { onWorktreeChange(!worktree) },
                    label = { Text(stringResource(R.string.worktree_chip), maxLines = 1) },
                    leadingIcon = { Icon(ClineIcons.Chrome.Worktree, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                    modifier = Modifier.focusRing(MaterialTheme.shapes.small),
                )
            }
        }
    }
}

/** The Home chips' requesters, owned by the screen that hosts their sheets (DS §8.1.7). */
@Stable
class HomeChipFocus {
    val workspace = FocusRequester()
    val branch = FocusRequester()
}

private fun Modifier.chipFocus(requester: FocusRequester?): Modifier = if (requester != null) focusRequester(requester) else this

/** Label capped at 176 dp (desktop `max-w-44`), ellipsized in the middle. */
@Composable
private fun ChipLabel(text: String) {
    Text(text, Modifier.widthIn(max = ChipLabelMaxWidth), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
}

@Composable
private fun ExpandIcon() {
    Icon(ClineIcons.ExpandMore, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
}

@Composable
private fun workspaceLabel(label: WorkspaceLabel): String = when (label) {
    WorkspaceLabel.Default -> stringResource(R.string.home_workspace_default)
    WorkspaceLabel.Home -> stringResource(R.string.home_workspace_home)
    is WorkspaceLabel.Name -> label.name
}

/**
 * §2.4 kernel notice: a card with the DS §1.6 kernel dot (pulsing while connecting or
 * reconnecting), line 1 in `titleSmall`, the optional line 2 in `bodyMedium` `error`, and a
 * "Kernel status" button that opens the Kernel status sheet. A polite live region.
 */
@Composable
private fun KernelNotice(state: KernelNoticeState, onKernelStatus: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            KernelStatusDot(kernelVisualOf(state.line), standalone = false, boxSize = NoticeDotBox)
            Spacer(Modifier.size(Spacing.s))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    stringResource(state.notice.line1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.notice.line2?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onKernelStatus) { Text(stringResource(R.string.home_kernel_status)) }
        }
    }
}

/** The DS §1.6 kernel-table visual for a [KernelLine] shown on Home. */
internal fun kernelVisualOf(line: KernelLine): KernelVisual = when (line) {
    KernelLine.Connected -> KernelVisual.Connected(0)
    KernelLine.Connecting -> KernelVisual.Connecting
    KernelLine.Reconnecting, KernelLine.Draining -> KernelVisual.Reconnecting
    KernelLine.TermuxAsleep -> KernelVisual.TermuxAsleep
    KernelLine.Unavailable, KernelLine.Blocked -> KernelVisual.Unavailable
}

private val ChipLabelMaxWidth = 176.dp
private val HeroSizeCompact = 72.dp
private val HeroSizeLarge = 96.dp
private val NoticeDotBox = 8.dp

/** The sr-only analogue: desktop `.sr-only` is a 1px clipped box. */
private val SrOnlyHeadingHeight = 1.dp

private val previewChips = WorkspaceChipState(WorkspaceLabel.Name("cline-termux"), "/data/data/com.termux/files/home/cline-termux")

@LightDarkPreviews
@Composable
private fun HomeScreenPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(previewChips, BranchChipState("main", busy = false), kernelNotice = null, setupNotice = false, worktree = false),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun HomeScreenMediumPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(previewChips, BranchChipState("feature/chat-screen", busy = true), kernelNotice = null, setupNotice = false, worktree = true),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
            largeHeading = true,
        )
    }
}

@LightDarkPreviews
@Composable
private fun HomeScreenKernelAsleepPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(
                workspace = WorkspaceChipState.Default,
                branch = null,
                kernelNotice = KernelNoticeState(
                    HomeKernelNotice(R.string.components_kernel_asleep, R.string.components_kernel_asleep_detail),
                    KernelLine.TermuxAsleep,
                ),
                setupNotice = false,
            ),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
        )
    }
}

/** FirstRun: kernel connected, no provider configured yet, so the C8 setup notice shows. */
@LightDarkPreviews
@Composable
private fun HomeScreenFirstRunPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(previewChips, BranchChipState("main", busy = false), kernelNotice = null, setupNotice = true),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
        )
    }
}

/** Blocked / unavailable: the Hub refused or failed, the notice points at Kernel status. */
@LightDarkPreviews
@Composable
private fun HomeScreenBlockedPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(
                workspace = WorkspaceChipState.Default,
                branch = null,
                kernelNotice = KernelNoticeState(
                    HomeKernelNotice(R.string.components_kernel_unavailable, R.string.components_kernel_unavailable_detail),
                    KernelLine.Unavailable,
                ),
                setupNotice = false,
            ),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun HomeScreenConnectingHeightCompactPreview() {
    ComponentPreview {
        HomeScreen(
            state = HomeState(
                workspace = WorkspaceChipState(WorkspaceLabel.Home, "/data/data/com.termux/files/home"),
                branch = null,
                kernelNotice = KernelNoticeState(HomeKernelNotice(R.string.components_kernel_connecting), KernelLine.Connecting),
                setupNotice = true,
            ),
            onWorkspace = {},
            onBranch = {},
            onKernelStatus = {},
            heroHidden = true,
        )
    }
}
