package dev.cline.termux.ui.kernel

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.data.session.DotTone
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.CommandBlock
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.StatusDot
import dev.cline.termux.ui.components.appDetailsIntent
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------- actions

/** The system screens behind the D.6 actions. */
object ReliabilityIntents {
    private const val TERMUX = TermuxPaths.PACKAGE

    /**
     * One UI "Battery" pages, tried in turn (open question 5). Device Care is `com.samsung.android.lool`
     * on global firmware and `com.samsung.android.sm_cn` on China firmware; the S25U (One UI 8, CHC)
     * resolves the `sm_cn` BatteryActivity.
     */
    private val SAMSUNG_BATTERY = listOf(
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )

    /** The intents to try, first to last, for [id]'s primary action. */
    fun forItem(item: ReliabilityItemUi, ownPackage: String): List<Intent> = when (item.id) {
        ReliabilityItemId.TermuxBattery -> listOf(appDetailsIntent(TERMUX))
        ReliabilityItemId.ClineBattery -> buildList {
            if (item.directRequest) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$ownPackage")))
            add(appDetailsIntent(ownPackage))
        }
        ReliabilityItemId.NeverSleeping -> SAMSUNG_BATTERY.map { Intent().setComponent(it) } + Intent(Settings.ACTION_SETTINGS)
        ReliabilityItemId.ChildProcess -> listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        ReliabilityItemId.Notifications -> listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ownPackage),
            appDetailsIntent(ownPackage),
        )
        ReliabilityItemId.TermuxOverlay -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$TERMUX")))
    }

    /** Starts the first intent that resolves; false when none does (D.6 "error"). */
    fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return false
    }
}

// ------------------------------------------------------------------------------- copy

@StringRes
private fun ReliabilityItemId.title(): Int = when (this) {
    ReliabilityItemId.TermuxBattery -> R.string.reliability_termux_battery_title
    ReliabilityItemId.ClineBattery -> R.string.reliability_cline_battery_title
    ReliabilityItemId.NeverSleeping -> R.string.reliability_never_sleeping_title
    ReliabilityItemId.ChildProcess -> R.string.reliability_child_title
    ReliabilityItemId.Notifications -> R.string.reliability_notifications_title
    ReliabilityItemId.TermuxOverlay -> R.string.reliability_overlay_title
}

/** D.6 "Supporting text by status". */
@StringRes
internal fun ReliabilityItemUi.supporting(): Int = when (id) {
    ReliabilityItemId.TermuxBattery -> if (done) R.string.reliability_battery_done else R.string.reliability_termux_battery_not_set
    ReliabilityItemId.ClineBattery -> if (done) R.string.reliability_battery_done else R.string.reliability_cline_battery_not_set
    ReliabilityItemId.NeverSleeping -> if (done) R.string.reliability_never_sleeping_done else R.string.reliability_never_sleeping_body
    ReliabilityItemId.ChildProcess -> if (done) R.string.reliability_child_done else R.string.reliability_child_not_set
    ReliabilityItemId.Notifications -> if (done) R.string.reliability_notifications_done else R.string.reliability_notifications_not_set
    ReliabilityItemId.TermuxOverlay -> R.string.reliability_overlay_body
}

/**
 * The item's one primary action, or null: item 4 on API 31–33 offers only the adb command, and
 * item 5 has nothing left to allow once notifications are on (trial row 14).
 */
@StringRes
internal fun ReliabilityItemUi.actionLabel(): Int? = when (id) {
    ReliabilityItemId.TermuxBattery -> R.string.reliability_open_termux_settings
    ReliabilityItemId.ClineBattery -> R.string.reliability_allow
    ReliabilityItemId.NeverSleeping -> R.string.reliability_open_battery_settings
    ReliabilityItemId.ChildProcess -> if (legacyAdbOnly) null else R.string.reliability_open_dev_options
    ReliabilityItemId.Notifications -> if (done) null else R.string.reliability_allow
    ReliabilityItemId.TermuxOverlay -> R.string.reliability_open_termux_setting
}

/** The instruction lines (string resources) the expanded item shows above its action (D.6). */
internal fun ReliabilityItemUi.instructionLines(): List<Int> = buildList {
    when (id) {
        ReliabilityItemId.TermuxBattery -> add(R.string.reliability_battery_instructions)
        ReliabilityItemId.ClineBattery -> if (!directRequest) add(R.string.reliability_battery_instructions)
        ReliabilityItemId.NeverSleeping -> add(R.string.reliability_never_sleeping_path)
        ReliabilityItemId.ChildProcess -> if (developerOptionsOff && !done) add(R.string.reliability_child_dev_off)
        ReliabilityItemId.Notifications, ReliabilityItemId.TermuxOverlay -> Unit
    }
}

/** Item 4 on API 31–33: the adb command block. */
internal val ReliabilityItemUi.showsAdbCommand: Boolean get() = id == ReliabilityItemId.ChildProcess && legacyAdbOnly

/**
 * Whether expanding the item shows anything: instructions, the adb command, an action or the
 * "I've done this" row. An item without details (Notifications once on, trial row 14) is not a
 * toggle: it shows its title and status only.
 */
internal fun ReliabilityItemUi.hasDetails(): Boolean = instructionLines().isNotEmpty() || showsAdbCommand || actionLabel() != null || selfCheck

@StringRes
internal fun ItemStatus.pillLabel(): Int = when (this) {
    ItemStatus.Done, ItemStatus.SelfConfirmed -> R.string.reliability_status_done
    ItemStatus.NotSet -> R.string.reliability_status_not_set
    ItemStatus.SelfCheck -> R.string.reliability_status_check_yourself
}

private fun ItemStatus.tone(): DotTone = when (this) {
    ItemStatus.Done, ItemStatus.SelfConfirmed -> DotTone.Success
    ItemStatus.NotSet -> DotTone.Warning
    ItemStatus.SelfCheck -> DotTone.Outline
}

@Composable
private fun ReliabilityItemId.icon(): ImageVector = when (this) {
    ReliabilityItemId.TermuxBattery, ReliabilityItemId.ClineBattery -> ClineIcons.BatteryFull
    ReliabilityItemId.NeverSleeping -> ClineIcons.Bedtime
    ReliabilityItemId.ChildProcess -> ClineIcons.AccountTree
    ReliabilityItemId.Notifications -> ClineIcons.Notifications
    ReliabilityItemId.TermuxOverlay -> ClineIcons.Layers
}

@StringRes
private fun ReliabilityGroup.header(): Int = when (this) {
    ReliabilityGroup.Required -> R.string.reliability_group_required
    ReliabilityGroup.Recommended -> R.string.reliability_group_recommended
    ReliabilityGroup.Optional -> R.string.reliability_group_optional
}

// ------------------------------------------------------------------------------- route

/**
 * D as a destination: the ViewModel, the `ON_RESUME` re-read, the system screens behind each
 * action (with the "not available" snackbar), and the notification permission request.
 * [onBack] pops (both variants); [onContinue] is the onboarding footer ("Continue" and
 * "Skip for now" both lead to C after `reliabilityShownAt` is written).
 */
@Composable
fun ReliabilityRoute(
    viewModel: ReliabilityViewModel,
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.reliability_unavailable)
    val notificationIntents = remember(context) {
        ReliabilityIntents.forItem(ReliabilityItemUi(ReliabilityItemId.Notifications, ReliabilityGroup.Recommended, ItemStatus.NotSet), context.packageName)
    }
    // B5 (D.6 item 5): the system prompt on every tap until a denial is known to be permanent
    // (NotificationRequestRules); only then the app's notification settings, as Settings →
    // Notifications does. A dismissed prompt is asked again on the next tap.
    val openNotificationSettings: () -> Unit = {
        if (!ReliabilityIntents.startFirst(context, notificationIntents)) {
            viewModel.setExpanded(ReliabilityItemId.Notifications, true)
            scope.launch { snackbar.showReplacing(unavailable) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = activity != null && Build.VERSION.SDK_INT >= 33 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (viewModel.onNotificationsResult(granted, rationale) == NotificationStep.OpenSettings) openNotificationSettings()
    }
    val onAction: (ReliabilityItemUi) -> Unit = { item ->
        if (item.id == ReliabilityItemId.Notifications && Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            when (viewModel.onNotificationsTapped(granted)) {
                NotificationStep.Request -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                NotificationStep.OpenSettings -> openNotificationSettings()
                NotificationStep.None -> viewModel.refresh()
            }
        } else if (!ReliabilityIntents.startFirst(context, ReliabilityIntents.forItem(item, context.packageName))) {
            viewModel.setExpanded(item.id, true)
            scope.launch { snackbar.showReplacing(unavailable) }
        }
    }
    val content: @Composable (Modifier) -> Unit = { m ->
        ReliabilityContent(
            state = state,
            onToggle = viewModel::toggle,
            onConfirm = { id, checked -> viewModel.setConfirmed(id, checked) },
            onAction = onAction,
            modifier = m,
            showTitle = state.variant == ReliabilityVariant.Onboarding,
        )
    }
    when {
        embedded -> content(modifier)
        state.variant == ReliabilityVariant.Onboarding -> ReliabilityOnboardingFrame(
            onBack = onBack,
            onSkip = { viewModel.markShown(); onContinue() },
            onContinue = { viewModel.markShown(); onContinue() },
            modifier = modifier,
            content = content,
        )
        else -> PageScaffold(
            title = stringResource(R.string.reliability_title),
            modifier = modifier,
            navigationIcon = onBack?.let { back -> { BackNavIcon(back) } },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                content(Modifier.widthIn(max = STANDALONE_MAX_WIDTH).fillMaxWidth().padding(bottom = Spacing.l))
            }
        }
    }
}

private val STANDALONE_MAX_WIDTH = 640.dp
private val PANEL_MAX_WIDTH = 592.dp
private val GRID_CELL = 40.dp

/**
 * The onboarding variant's frame (KS 0.7 OnboardingScaffold `panel`): the faint 40dp grid
 * backdrop, a content column (compact: full width with 16dp gutters; medium+: a bordered
 * card, max 592dp, 32dp padding) and the footer with "Skip for now" (start) and "Continue"
 * (end), which rides the navigation bar.
 */
@Composable
private fun ReliabilityOnboardingFrame(
    onBack: (() -> Unit)?,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val footer: @Composable (Modifier) -> Unit = { m ->
        Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.reliability_skip)) }
            Button(onClick = onContinue) { Text(stringResource(R.string.reliability_continue)) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= Spacing.gutterBreakpoint
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
            topBar = {
                if (onBack != null) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)).padding(Spacing.xs)) {
                        BackNavIcon(onBack)
                    }
                }
            },
            bottomBar = {
                if (!wide) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            footer(Modifier.windowInsetsPadding(WindowInsets.bottomSafe).padding(horizontal = Spacing.l, vertical = Spacing.s))
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                GridBackdrop()
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (wide) {
                        Surface(
                            modifier = Modifier.padding(Spacing.xxl).widthIn(max = PANEL_MAX_WIDTH).fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Column(Modifier.padding(Spacing.xxxl)) {
                                content(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(Spacing.xxl))
                                footer(Modifier.windowInsetsPadding(WindowInsets.bottomSafe))
                            }
                        }
                    } else {
                        content(Modifier.fillMaxWidth().padding(top = Spacing.l, bottom = Spacing.l))
                    }
                }
            }
        }
    }
}

/** The decorative 40dp grid (`outlineVariant` @ 20 %) behind the onboarding panel. */
@Composable
private fun GridBackdrop() {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    val cell = with(LocalDensity.current) { GRID_CELL.toPx() }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        var x = 0f
        while (x <= size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += cell
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += cell
        }
    }
}

// ------------------------------------------------------------------------------- content

/**
 * The checklist itself (D.5): description, "N of M done" with its determinate bar (a polite
 * live region), the all-done line, then the Required / Recommended / Optional groups.
 * [showTitle] draws the panel title (onboarding; the standalone page has it in the app bar).
 */
@Composable
fun ReliabilityContent(
    state: ReliabilityUiState,
    onToggle: (ReliabilityItemId) -> Unit,
    onConfirm: (ReliabilityItemId, Boolean) -> Unit,
    onAction: (ReliabilityItemUi) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
) {
    Column(modifier) {
        val gutter = Modifier.padding(horizontal = Spacing.l)
        if (showTitle) {
            Text(
                stringResource(R.string.reliability_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = gutter.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.s))
        }
        Text(
            stringResource(R.string.reliability_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = gutter,
        )
        if (state.loaded && state.countedTotal > 0) {
            Spacer(Modifier.height(Spacing.l))
            val progress = stringResource(R.string.reliability_progress, state.doneCount, state.countedTotal)
            Column(gutter.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(progress, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.s))
                LinearProgressIndicator(
                    progress = { state.doneCount.toFloat() / state.countedTotal },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                )
            }
        }
        if (state.allDone) {
            Spacer(Modifier.height(Spacing.l))
            Surface(
                modifier = gutter.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = ClineTheme.colors.success.container,
                contentColor = ClineTheme.colors.success.onContainer,
            ) {
                Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ClineIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.reliability_all_done), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ReliabilityGroup.entries.forEach { group ->
            val items = state.items.filter { it.group == group }
            if (items.isEmpty()) return@forEach
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(group.header()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = gutter.padding(vertical = Spacing.s).semantics { heading() },
            )
            items.forEach { item ->
                ReliabilityItem(
                    item = item,
                    expanded = item.id in state.expanded,
                    onToggle = { onToggle(item.id) },
                    onConfirm = { onConfirm(item.id, it) },
                    onAction = { onAction(item) },
                )
            }
        }
    }
}

/**
 * KS 0.7 ReliabilityItem: a `ListItem` (leading 24dp icon, headline, the state line, trailing
 * status label) that expands into the instructions, one `FilledTonalButton` and, for self-
 * checks, the full-width "I've done this" checkbox row. At font scale ≥ 1.3 or below 360dp the
 * status label moves into the supporting column. TalkBack reads one node per item. An item with
 * nothing to expand into ([hasDetails], trial row 14) is not a toggle and never expands.
 */
@Composable
fun ReliabilityItem(
    item: ReliabilityItemUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(item.id.title())
    val supporting = stringResource(item.supporting())
    val pill = stringResource(item.status.pillLabel())
    val description = stringResource(R.string.reliability_item_cd, title, pill, supporting)
    val details = item.hasDetails()
    val open = expanded && details
    val expandedWord = stringResource(if (open) R.string.reliability_expanded else R.string.reliability_collapsed)
    val reduced = ClineTheme.reducedMotion
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = LocalDensity.current.fontScale >= 1.3f || maxWidth < 360.dp
        Column(Modifier.fillMaxWidth()) {
            val toggle = if (details) Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button, onClick = onToggle) else Modifier
            ListItem(
                modifier = toggle
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        if (details) stateDescription = expandedWord
                    },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                leadingContent = { Icon(item.id.icon(), contentDescription = null, modifier = Modifier.size(24.dp)) },
                headlineContent = { Text(title) },
                supportingContent = {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            StatusDot(tone = item.status.tone(), boxSize = 20.dp)
                            Text(supporting, modifier = Modifier.padding(top = Spacing.xxs))
                        }
                        if (stacked) {
                            Spacer(Modifier.height(Spacing.xs))
                            StatusPill(item.status, pill)
                        }
                    }
                },
                trailingContent = if (stacked) null else ({ StatusPill(item.status, pill) }),
            )
            AnimatedVisibility(
                visible = open,
                enter = if (reduced) EnterTransition.None else expandVertically(tween(ClineMotion.Short4)) + fadeIn(tween(ClineMotion.Short4)),
                exit = if (reduced) ExitTransition.None else shrinkVertically(tween(ClineMotion.Short4)) + fadeOut(tween(ClineMotion.Short4)),
            ) {
                ItemInstructions(item, onConfirm, onAction)
            }
        }
    }
}

@Composable
private fun StatusPill(status: ItemStatus, label: String) {
    val colors = ClineTheme.colors
    val (container, content) = when (status) {
        ItemStatus.Done, ItemStatus.SelfConfirmed -> colors.success.container to colors.success.onContainer
        ItemStatus.NotSet -> colors.warning.container to colors.warning.onContainer
        ItemStatus.SelfCheck -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = container, contentColor = content) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs))
    }
}

@Composable
private fun ItemInstructions(item: ReliabilityItemUi, onConfirm: (Boolean) -> Unit, onAction: () -> Unit) {
    val start = Spacing.l + 24.dp + Spacing.l
    Column(Modifier.fillMaxWidth().padding(start = start, end = Spacing.l, bottom = Spacing.m)) {
        item.instructionLines().forEach { line ->
            Text(
                stringResource(line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
        }
        if (item.showsAdbCommand) {
            CommandBlock(stringResource(R.string.reliability_child_adb_command))
            Text(
                stringResource(R.string.reliability_child_adb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s),
            )
        }
        item.actionLabel()?.let { label ->
            FilledTonalButton(onClick = onAction) { Text(stringResource(label)) }
        }
        if (item.selfCheck) {
            val checked = item.status == ItemStatus.SelfConfirmed
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .focusRing(RectangleShape, inset = true)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = onConfirm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.reliability_confirm), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ------------------------------------------------------------------------------- previews

private fun previewItems(samsung: Boolean = true, allDone: Boolean = false): ReliabilityUiState {
    val readings = SystemReadings(
        sdkInt = 36, samsung = samsung, termuxUnrestricted = true, clineUnrestricted = allDone,
        phantomProcs = PhantomSetting.Value(if (allDone) "false" else null), developerOptionsOn = allDone,
        notificationsOn = true, canRequestIgnoreBattery = false,
    )
    val confirmed = if (allDone) setOf(ReliabilityItemId.NeverSleeping) else emptySet()
    val items = ReliabilityRules.items(readings, confirmed)
    val summary = ReliabilityRules.summary(items)
    val all = ReliabilityRules.allDone(items)
    return ReliabilityUiState(
        variant = ReliabilityVariant.Standalone, items = items, doneCount = summary.doneCount,
        countedTotal = summary.countedTotal, allDone = all, loaded = true,
        expanded = setOfNotNull(if (all) null else ReliabilityRules.firstOpen(items), ReliabilityItemId.NeverSleeping.takeIf { !all }),
    )
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ReliabilityPopulatedPreview() {
    ComponentPreview(padding = false) {
        ReliabilityContent(previewItems(), onToggle = {}, onConfirm = { _, _ -> }, onAction = {})
    }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ReliabilityAllDonePreview() {
    ComponentPreview(padding = false) {
        ReliabilityContent(previewItems(allDone = true), onToggle = {}, onConfirm = { _, _ -> }, onAction = {})
    }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ReliabilityOnboardingPreview() {
    ComponentPreview(padding = false) {
        ReliabilityContent(previewItems(samsung = false).copy(variant = ReliabilityVariant.Onboarding), onToggle = {}, onConfirm = { _, _ -> }, onAction = {}, showTitle = true)
    }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ReliabilityLegacyAdbPreview() {
    ComponentPreview(padding = false) {
        ReliabilityItem(
            item = ReliabilityItemUi(ReliabilityItemId.ChildProcess, ReliabilityGroup.Recommended, ItemStatus.NotSet, legacyAdbOnly = true),
            expanded = true, onToggle = {}, onConfirm = {}, onAction = {},
        )
    }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun ReliabilitySelfCheckPreview() {
    ComponentPreview(padding = false) {
        Column {
            ReliabilityItem(
                item = ReliabilityItemUi(ReliabilityItemId.TermuxOverlay, ReliabilityGroup.Optional, ItemStatus.SelfCheck),
                expanded = true, onToggle = {}, onConfirm = {}, onAction = {},
            )
            ReliabilityItem(
                item = ReliabilityItemUi(ReliabilityItemId.ChildProcess, ReliabilityGroup.Recommended, ItemStatus.NotSet, developerOptionsOff = true),
                expanded = true, onToggle = {}, onConfirm = {}, onAction = {},
            )
        }
    }
}
