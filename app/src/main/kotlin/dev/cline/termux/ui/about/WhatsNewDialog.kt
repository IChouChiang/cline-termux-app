package dev.cline.termux.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.cline.termux.R
import dev.cline.termux.data.about.WHATS_NEW_RELEASES
import dev.cline.termux.data.about.WhatsNewIcon
import dev.cline.termux.data.about.WhatsNewRelease
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineBrand
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineLightScheme
import dev.cline.termux.ui.theme.Spacing

/**
 * The hero's bottom padding: desktop `whats-new-dialog.tsx` uses `pb-5.5` (22px), a deliberate
 * half step off the 4dp grid that the port keeps for parity.
 */
private val HeroPaddingBottom = 22.dp

/**
 * The shell's What's-new slot (`ShellSlots.whatsNewDialog`, customize-and-rest.md §8.4): the
 * entry with [id], with "See all changes". Closing (back, scrim, "Get Started") calls
 * [onClose], which marks it seen; [onSeeAll] marks it seen and opens About.
 */
@Composable
fun ShellWhatsNewDialog(id: String, onClose: () -> Unit, onSeeAll: () -> Unit) {
    val release = remember(id) { WHATS_NEW_RELEASES.firstOrNull { it.id == id } }
    if (release == null) {
        LaunchedEffect(id) { onClose() }
        return
    }
    WhatsNewDialog(release, onClose = onClose, onSeeAll = onSeeAll)
}

/**
 * C11 What's new (customize-and-rest.md §8.2): a `BasicAlertDialog` of width
 * min(560dp, screen − 32dp) with the hero (brand violet, or the wallpaper's
 * `primaryContainer` under dynamic colour), the highlights in one column below a 400dp
 * content width and two above, and the footer. [onSeeAll] is null when replayed from About
 * (desktop: "See all changes" only in the shell). Back = close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewDialog(release: WhatsNewRelease, onClose: () -> Unit, onSeeAll: (() -> Unit)?) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    BasicAlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WhatsNewCard(
            release = release,
            onClose = onClose,
            onSeeAll = onSeeAll,
            modifier = Modifier.width(min(560.dp, screenWidth - 32.dp)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsNewCard(release: WhatsNewRelease, onClose: () -> Unit, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    val titleFocus = remember { FocusRequester() }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    Surface(
        modifier = modifier.onPreviewKeyEvent { event ->
            // "Get Started" is the default action (§8.5): hardware Enter closes.
            if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                onClose()
                true
            } else {
                false
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.clip(MaterialTheme.shapes.extraLarge)) {
            WhatsNewHero(release, Modifier.focusRequester(titleFocus).focusable())
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.6f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
            ) {
                val columns = WhatsNewLayout.columns(maxWidth.value)
                val gap = 24.dp
                val itemWidth = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(
                    maxItemsInEachRow = columns,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                ) {
                    release.highlights.forEach { highlight ->
                        Column(Modifier.width(itemWidth)) {
                            Icon(highlight.icon.vector(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(Spacing.s))
                            Text(stringResource(highlight.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(Spacing.xxs))
                            Text(stringResource(highlight.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSeeAll != null) {
                    TextButton(onClick = onSeeAll) {
                        Text(stringResource(R.string.whats_new_see_all))
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(ClineIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose) { Text(stringResource(R.string.whats_new_get_started)) }
            }
        }
    }
}

/** The hero (§8.2 table): brand scheme = fixed violet base + two radial glows; dynamic = primaryContainer. */
@Composable
private fun WhatsNewHero(release: WhatsNewRelease, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = scheme.primary == ClineLightScheme.primary || scheme.primary == ClineDarkScheme.primary
    val base: Color
    val glowA: Color
    val glowB: Color
    val overlineColor: Color
    val titleColor: Color
    if (brand) {
        base = ClineBrand.WhatsNewHeroBase
        glowA = ClineBrand.WhatsNewHeroGlowViolet
        glowB = ClineBrand.WhatsNewHeroGlowIndigo
        overlineColor = ClineBrand.WhatsNewHeroOverline
        titleColor = ClineBrand.WhatsNewHeroOnColor
    } else {
        base = scheme.primaryContainer
        glowA = scheme.primary.copy(alpha = WhatsNewHeroDynamic.PRIMARY_GLOW_ALPHA)
        glowB = scheme.tertiary.copy(alpha = WhatsNewHeroDynamic.TERTIARY_GLOW_ALPHA)
        overlineColor = scheme.onPrimaryContainer
        titleColor = scheme.onPrimaryContainer
    }
    val overline = stringResource(R.string.whats_new_overline)
    val title = stringResource(release.title)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .background(base)
            .drawBehind {
                val w = size.width
                drawRect(
                    Brush.radialGradient(
                        0f to glowA, 0.55f to glowA.copy(alpha = 0f),
                        center = Offset(w * 0.15f, 0f),
                        radius = w * 1.2f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        0f to glowB, 0.6f to glowB.copy(alpha = 0f),
                        center = Offset(w, size.height),
                        radius = w * 0.9f,
                    ),
                )
            }
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xxxl + Spacing.s, bottom = HeroPaddingBottom),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column {
            Text(
                overline.uppercase(LocalConfiguration.current.locales[0]),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
                color = overlineColor,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = titleColor)
        }
    }
}

/**
 * Dynamic-colour hero glows (§8.2 table). The overline is `labelMedium` (normal-size text, DS §8.1
 * 4.5:1) in full-opacity `onPrimaryContainer` (DS §1.8: no text alpha below 0.87). It can sit at the
 * primary glow's centre (a long or large-font title pushes it to the top-start of the hero), so the
 * primary glow's peak alpha is capped where `onPrimaryContainer` still reaches 4.5:1 over
 * `primary` composited on `primaryContainer` for tone-30/90 pairs. The tertiary glow is anchored at
 * the opposite (bottom-end) corner, behind the title only, which is large text (3:1).
 */
internal object WhatsNewHeroDynamic {
    const val PRIMARY_GLOW_ALPHA = 0.2f
    const val TERTIARY_GLOW_ALPHA = 0.45f
}

/** §8.2 grid rule: 2 columns at a content width ≥ 400dp, else 1. */
object WhatsNewLayout {
    fun columns(contentWidthDp: Float): Int = if (contentWidthDp >= 400f) 2 else 1
}

/** The DS §7 symbol of a highlight. */
@Composable
fun WhatsNewIcon.vector(): ImageVector = when (this) {
    WhatsNewIcon.Chat -> ClineIcons.Chat
    WhatsNewIcon.TaskAlt -> ClineIcons.TaskAlt
    WhatsNewIcon.Schedule -> ClineIcons.Schedule
    WhatsNewIcon.Extension -> ClineIcons.Extension
}

@LightDarkPreviews
@Composable
private fun WhatsNewShellPreview() {
    ComponentPreview(padding = false) { WhatsNewCard(WHATS_NEW_RELEASES.first(), onClose = {}, onSeeAll = {}, modifier = Modifier.width(560.dp)) }
}

@LightDarkPreviews
@Composable
private fun WhatsNewReplayCompactPreview() {
    ComponentPreview(padding = false) { WhatsNewCard(WHATS_NEW_RELEASES.first(), onClose = {}, onSeeAll = null, modifier = Modifier.width(380.dp)) }
}

/** The dynamic-colour hero branch (any non-brand scheme): `primaryContainer` base, capped glows. */
@LightDarkPreviews
@Composable
private fun WhatsNewDynamicColorPreview() {
    ComponentPreview(padding = false) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface { WhatsNewCard(WHATS_NEW_RELEASES.first(), onClose = {}, onSeeAll = {}, modifier = Modifier.width(380.dp)) }
        }
    }
}
