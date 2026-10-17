package dev.cline.termux.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.CommandBlock
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.joinSpokenLines
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.rememberShellLayout
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/*
 * The onboarding components of onboarding-kernel.md §0.7: OnboardingScaffold, SetupCheckRow
 * and SetupOptionCard. They live with the onboarding screens, their only users. CommandBlock
 * is shared with the kernel screens and lives in ui/components.
 */

/** `OnboardingContent` surfaces (desktop `surface="plain" | "panel"`). */
enum class OnboardingSurface {
    /** Welcome, Done, Install Termux: no border, transparent over the grid. */
    Plain,

    /** Kernel setup, Connect: a bordered card on medium+, plain on compact. */
    Panel,
}

/** Width of the content card on medium and expanded windows (desktop `max-w-148`). */
val OnboardingCardMaxWidth: Dp = 592.dp

/**
 * KS 0.7 OnboardingScaffold: the `surface` Scaffold with the shared snackbar host, the 40dp
 * grid backdrop, a scrolling content column (full width with 16dp gutters on compact, a
 * centred 592dp card on medium+), an optional back arrow and an optional action bar. The
 * action bar is the Scaffold's bottom bar on compact (hairline when content scrolls under it)
 * and the card's last child on medium+; whichever is last takes the IME ∪ bottom-safe inset.
 */
@Composable
fun OnboardingScaffold(
    modifier: Modifier = Modifier,
    surface: OnboardingSurface = OnboardingSurface.Panel,
    onBack: (() -> Unit)? = null,
    /** Center the content vertically (Welcome, Done, Install Termux). */
    centered: Boolean = false,
    /** Done's `wide-grid` band: the grid only behind the content's middle. */
    gridBand: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    actionBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = rememberShellLayout()
    val wide = layout.widthDp >= MEDIUM_MIN_WIDTH && !layout.heightCompact
    val bordered = wide && surface == OnboardingSurface.Panel
    val bottomInsets = WindowInsets.ime.union(WindowInsets.bottomSafe)
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
        // Compact: the back arrow is a pinned top bar at the M3 TopAppBar position (DS §4.2
        // OnboardingScaffold "small TopAppBar, back arrow only"), so it stays at the top even when
        // the content is centred (B, Done). Medium+: it sits inside the card (A.4).
        topBar = {
            if (!wide && onBack != null) {
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(Spacing.xs),
                ) { BackNavIcon(onBack) }
            }
        },
        bottomBar = {
            if (!wide && actionBar != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        if (scrollState.canScrollForward) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(bottomInsets)
                                .padding(horizontal = Spacing.gutterCompact, vertical = Spacing.s),
                        ) { actionBar() }
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OnboardingGrid(
                band = gridBand,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
            )
            // The IME / navigation-bar inset shrinks the scroll viewport itself (outside the scroll),
            // so bringIntoView keeps the focused field and "Connect" above the keyboard (C.4) and no
            // content sits behind the IME. The grid stays full bleed behind it.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .let { if (actionBar == null || wide) it.windowInsetsPadding(bottomInsets) else it },
            ) {
                val viewportHeight = maxHeight
                val columnModifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = if (wide) Spacing.gutterWide else Spacing.gutterCompact)
                // Centred when it fits, top-aligned (with the 48dp margins on medium+) when it scrolls (A.4).
                Column(
                    modifier = columnModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (centered || wide) Arrangement.Center else Arrangement.Top,
                ) {
                    if (wide) Spacer(Modifier.height(Spacing.huge))
                    val card = Modifier
                        .widthIn(max = OnboardingCardMaxWidth)
                        .fillMaxWidth()
                    if (bordered) {
                        Surface(
                            modifier = card,
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Column(Modifier.padding(Spacing.xxxl)) {
                                CardBody(onBack, actionBar, inCard = true, content = content)
                            }
                        }
                    } else {
                        Column(card.padding(vertical = if (wide) Spacing.xxxl else 0.dp)) {
                            CardBody(onBack.takeIf { wide }, actionBar.takeIf { wide }, inCard = wide, content = content)
                        }
                    }
                    if (wide) Spacer(Modifier.height(Spacing.huge))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CardBody(
    onBack: (() -> Unit)?,
    actionBar: (@Composable () -> Unit)?,
    inCard: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) {
        Box(Modifier.padding(bottom = Spacing.s)) { BackNavIcon(onBack) }
    } else if (!inCard) {
        Spacer(Modifier.height(Spacing.l))
    }
    content()
    if (actionBar != null && inCard) {
        Spacer(Modifier.height(Spacing.xxl))
        Box(Modifier.fillMaxWidth()) { actionBar() }
    } else {
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** The decorative 40dp grid (`outlineVariant` @ 20 %), full bleed or as Done's horizontal band. */
@Composable
private fun OnboardingGrid(band: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    Canvas(modifier) {
        val step = 40.dp.toPx()
        val stroke = 1.dp.toPx()
        val top = if (band) size.height * 0.3f else 0f
        val bottom = if (band) size.height * 0.7f else size.height
        var x = (size.width / 2f) % step
        while (x <= size.width) {
            drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
            x += step
        }
        var y = top + ((bottom - top) / 2f) % step
        while (y <= bottom) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += step
        }
    }
}

/** Panel title: `headlineSmall` SemiBold, a heading (KS 0.7). */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** Panel description: `bodyMedium` `onSurfaceVariant`, 8dp under the title. */
@Composable
fun PanelDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.s),
    )
}

/** Plain title (Done, B): `headlineMedium` SemiBold, centred, a heading. */
@Composable
fun PlainTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

// ---- SetupCheckRow ------------------------------------------------------------------------------

/**
 * KS 0.7 SetupCheckRow: a 24dp status glyph (a different shape per status, never colour
 * only), the title, the supporting line and an optional mono value line, merged into one
 * TalkBack node "<title>, <status word>, <supporting>"; the fix area animates open below.
 */
@Composable
fun SetupCheckRow(
    title: String,
    status: CheckStatus,
    supporting: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    extraLines: List<String> = emptyList(),
    fixArea: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val statusWord = stringResource(KernelSetupRows.statusWord(status))
    val resources = LocalResources.current
    val lines = joinSpokenLines(
        listOf(supporting) + extraLines,
        sentence = { first, second -> resources.getString(R.string.setup_row_cd_lines, first, second) },
        continued = { first, second -> resources.getString(R.string.setup_row_cd_lines_continued, first, second) },
    )
    val description = stringResource(R.string.setup_row_cd, title, statusWord, lines)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(vertical = Spacing.m)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusGlyph(status) }
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extraLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (value != null) {
                    LtrBlock { Text(value, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        AnimatedVisibility(
            visible = fixArea != null,
            enter = expandVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard), expandFrom = Alignment.Top) +
                fadeIn(tween(ClineMotion.Short3, delayMillis = 50)),
            exit = shrinkVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard)) + fadeOut(tween(ClineMotion.Short2)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp + Spacing.l, bottom = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) { fixArea?.invoke(this) }
        }
    }
}

/** The five glyph shapes: ring, spinner, check circle, warning triangle, error circle. */
@Composable
fun StatusGlyph(status: CheckStatus) {
    when (status) {
        CheckStatus.Waiting -> Box(
            Modifier
                .size(8.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        CheckStatus.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        CheckStatus.Passed -> Icon(ClineIcons.CheckCircleFilled, contentDescription = null, tint = ClineTheme.colors.success.color)
        CheckStatus.NeedsAction -> Icon(ClineIcons.WarningFilled, contentDescription = null, tint = ClineTheme.colors.warning.color)
        CheckStatus.Failed -> Icon(ClineIcons.ErrorFilled, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
}

// ---- SetupOptionCard ----------------------------------------------------------------------------

/**
 * KS 0.7 SetupOptionCard: a non-clickable `Card` whose header is `selectable` (one click handler,
 * radio role) only while unselected, so taps on the selected card reach its controls. Header: a
 * 32dp icon tile, the title, an optional badge and the description; the body is composed only
 * while selected and expands with the disclosure motion.
 *
 * Accessibility (onboarding C.9): the selectable header is the card's single radio node. It owns
 * the role, contentDescription (title, then the badge), stateDescription "Selected"/"Not selected"
 * and the click label (the desktop's hidden select-button label, i.e. the title); its icon tile and
 * texts are cleared into it. The header carries the card padding, so while the card is unselected
 * (body absent) the whole card is the tap target. The body sits outside that merge boundary so its
 * texts, live regions and controls stay separate focus stops instead of being folded into the radio.
 */
@Composable
fun SetupOptionCard(
    title: String,
    description: String?,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val selectedWord = stringResource(R.string.connect_selected)
    val notSelectedWord = stringResource(R.string.connect_not_selected)
    val label = if (badge != null) stringResource(R.string.connect_option_with_badge, title, badge) else title
    // While unselected the header's bottom padding closes the card; it shrinks as the body opens.
    val headerBottom by animateDpAsState(
        if (selected) 0.dp else Spacing.l,
        tween(ClineMotion.Short4, easing = ClineMotion.Standard),
        label = "setupOptionHeaderBottom",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(MaterialTheme.shapes.medium)
                    .selectable(selected = selected, enabled = !selected, role = Role.RadioButton, onClick = onSelect)
                    .semantics {
                        contentDescription = label
                        stateDescription = if (selected) selectedWord else notSelectedWord
                        if (!selected) {
                            onClick(label = title) {
                                onSelect()
                                true
                            }
                        }
                    }
                    .padding(start = Spacing.l, top = Spacing.l, end = Spacing.l, bottom = headerBottom),
            ) {
                Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.Top) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(32.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (badge != null) TagBadge(badge)
                        }
                        if (description != null) {
                            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = selected,
                enter = expandVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard), expandFrom = Alignment.Top) +
                    fadeIn(tween(ClineMotion.Short3, delayMillis = 50)),
                exit = shrinkVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard)) + fadeOut(tween(ClineMotion.Short2)),
            ) {
                Column(
                    Modifier.padding(start = Spacing.l, top = Spacing.m, end = Spacing.l, bottom = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) { body() }
            }
        }
    }
}

/** A `TextButton` that shows a 16dp spinner and [busyLabel] while [busy] (A.6 "Check again"). */
@Composable
fun BusyTextButton(label: String, busy: Boolean, busyLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        if (busy) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(busyLabel)
        } else {
            Text(label)
        }
    }
}

private const val MEDIUM_MIN_WIDTH = 600

// ---- previews -----------------------------------------------------------------------------------

@LightDarkPreviews
@Composable
private fun SetupCheckRowStatesPreview() {
    ComponentPreview {
        Column {
            SetupCheckRow("Termux", CheckStatus.Passed, "Termux 0.118.3 is installed")
            SetupCheckRow("Permission to run commands", CheckStatus.Checking, "Checking…")
            SetupCheckRow("Cline kernel", CheckStatus.NeedsAction, "The Cline kernel is not installed in Termux. Run the installer in Termux:") {
                CommandBlock(KernelSetupRows.INSTALLER_COMMAND)
            }
            SetupCheckRow("Cline Hub", CheckStatus.Failed, "Couldn't start the Cline Hub.", value = "Timed out after 15000ms waiting for detached hub startup.")
            SetupCheckRow("Cline Hub", CheckStatus.Waiting, "Waiting for the steps above.")
        }
    }
}

@LightDarkPreviews
@Composable
private fun SetupOptionCardPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SetupOptionCard("Sign in with Cline", null, ClineIcons.AccountCircle, selected = true, onSelect = {}, badge = "Recommended") {
                Text("No API key needed", style = MaterialTheme.typography.bodyMedium)
            }
            SetupOptionCard("Use your own API key", "Anthropic, OpenAI, OpenRouter, and more.", ClineIcons.Key, selected = false, onSelect = {}) {}
        }
    }
}
