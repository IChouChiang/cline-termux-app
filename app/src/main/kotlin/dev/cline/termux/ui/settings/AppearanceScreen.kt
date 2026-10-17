package dev.cline.termux.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.SectionHeader
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.markdown.MarkdownText
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.ClineBrand
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.LocalClineMono
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.theme.ThemeMode
import dev.cline.termux.ui.theme.clineMono
import dev.cline.termux.ui.theme.clineTypography
import kotlin.math.roundToInt

/** What the Appearance controls do (the route binds them to [AppearanceViewModel]). */
data class AppearanceActions(
    val onThemeMode: (ThemeMode) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onFontSizeDrag: (Int) -> Unit = {},
    val onFontSizeDragFinished: () -> Unit = {},
    val onFontSizeStep: (Int) -> Unit = {},
    val onAppIcon: (AppIcon) -> Unit = {},
)

/**
 * S2 body: Dark mode (three-way), Dynamic colour (API 31+ only), Font size stepper + slider
 * with a live preview card, and the App icon tiles. Everything applies without a restart.
 */
@Composable
fun AppearanceContent(
    state: AppearanceUiState,
    actions: AppearanceActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SettingsList(contentPadding = contentPadding, modifier = modifier) {
        settingsItem(key = "description") { SettingsDescription(stringResource(R.string.appearance_description)) }

        settingsItem(key = "dark-header") { SectionHeader(stringResource(R.string.appearance_dark_mode)) }
        settingsItem(key = "dark-options") {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(label = themeLabel(mode), selected = state.themeMode == mode, onClick = { actions.onThemeMode(mode) })
                }
            }
            SettingsDescription(stringResource(R.string.appearance_dark_mode_help))
        }
        if (state.dynamicSupported) {
            settingsItem(key = "dynamic") {
                PrefSwitchRow(
                    headline = stringResource(R.string.appearance_dynamic),
                    supporting = { Text(stringResource(R.string.appearance_dynamic_supporting)) },
                    checked = state.dynamicColor,
                    onCheckedChange = actions.onDynamicColor,
                )
            }
        }

        settingsItem(key = "font-header") { SectionHeader(stringResource(R.string.appearance_font_size)) }
        settingsItem(key = "font") {
            SettingsDescription(stringResource(R.string.appearance_font_size_description))
            FontSizeControl(state, actions)
            FontPreviewCard(state.previewFontSize, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
        }

        settingsItem(key = "icon-header") { SectionHeader(stringResource(R.string.appearance_app_icon)) }
        settingsItem(key = "icon") {
            SettingsDescription(stringResource(R.string.appearance_app_icon_description))
            AppIconPicker(state.appIcon, actions.onAppIcon, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            state.iconError?.let { message ->
                Text(
                    stringResource(R.string.appearance_icon_error, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = Spacing.l)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }
}

/** One option of a `selectableGroup`: the whole 56dp row is the target, `Role.RadioButton`. */
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.l))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The font-size stepper (desktop "Font size" row): `remove` · `Slider(12..20, steps 7)` ·
 * `add` · the value in tabular mono. The slider is at most 320dp wide (desktop `w-64`); below
 * 360dp of available width the row wraps to two lines (S2.4).
 */
@Composable
private fun FontSizeControl(state: AppearanceUiState, actions: AppearanceActions) {
    val sliderLabel = stringResource(R.string.appearance_font_size)
    val valueText = stringResource(R.string.appearance_font_value, state.previewFontSize)
    val valueDescription = stringResource(R.string.settings_cd_pair, stringResource(R.string.appearance_font_selected), valueText)

    val decrease: @Composable () -> Unit = {
        TooltipIconButton(
            icon = ClineIcons.Remove,
            contentDescription = stringResource(R.string.appearance_font_decrease),
            onClick = { actions.onFontSizeStep(-1) },
            enabled = state.canDecrease,
        )
    }
    val increase: @Composable () -> Unit = {
        TooltipIconButton(
            icon = ClineIcons.Add,
            contentDescription = stringResource(R.string.appearance_font_increase),
            onClick = { actions.onFontSizeStep(1) },
            enabled = state.canIncrease,
        )
    }
    val value: @Composable () -> Unit = {
        Text(
            valueText,
            style = ClineTheme.mono.small.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(40.dp)
                .semantics { contentDescription = valueDescription },
        )
    }
    val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = state.previewFontSize.toFloat(),
            onValueChange = { actions.onFontSizeDrag(it.roundToInt()) },
            onValueChangeFinished = actions.onFontSizeDragFinished,
            valueRange = FontSizeSetting.MIN.toFloat()..FontSizeSetting.MAX.toFloat(),
            steps = FontSizeSetting.MAX - FontSizeSetting.MIN - 1,
            modifier = sliderModifier.semantics {
                contentDescription = sliderLabel
                stateDescription = valueText
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
        if (maxWidth < 360.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    decrease()
                    increase()
                    Spacer(Modifier.weight(1f))
                    value()
                    Spacer(Modifier.width(Spacing.m))
                }
                slider(Modifier.fillMaxWidth().padding(horizontal = Spacing.m))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                decrease()
                slider(Modifier.weight(1f, fill = false).widthIn(max = 320.dp))
                increase()
                value()
                // Ends the label on the 16dp gutter, like the icon buttons' 12dp inner padding.
                Spacer(Modifier.width(Spacing.m))
            }
        }
    }
}

/**
 * The preview bubble's vertical padding, the user bubble's 16dp x 10dp of DS #5 (desktop
 * `py-2.5`), kept off the 4dp grid so the preview matches the real transcript bubble.
 */
private val PreviewBubblePaddingVertical = 10.dp

/**
 * The live preview *(Android)*: a user bubble (DS #5), a static tool label (DS #2) and an
 * assistant markdown line with a CJK sentence (DS §2.3 weight check), rendered with the
 * typography for [fontSize] while the slider moves. TalkBack reads only "Preview".
 */
@Composable
private fun FontPreviewCard(fontSize: Int, modifier: Modifier = Modifier) {
    val scale = FontSizeSetting.scale(fontSize)
    val typography = remember(scale) { clineTypography(scale) }
    val mono = remember(scale) { clineMono(scale) }
    val previewLabel = stringResource(R.string.appearance_preview)
    val toolCode = stringResource(R.string.appearance_preview_tool_code)
    val toolTemplate = stringResource(R.string.appearance_preview_tool, PLACEHOLDER)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = previewLabel },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalClineMono provides mono) {
                Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.widthIn(max = 280.dp),
                        ) {
                            Text(
                                stringResource(R.string.appearance_preview_user),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = Spacing.l, vertical = PreviewBubblePaddingVertical),
                            )
                        }
                    }
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            ClineIcons.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            toolLabel(toolTemplate, toolCode, ClineTheme.mono.label.toSpanStyle()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Wraps instead of clipping at system scale 2.0 × size 20 (SP §12).
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    MarkdownText(stringResource(R.string.appearance_preview_assistant), selectable = false)
                }
            }
        }
    }
}

private const val PLACEHOLDER = "\u0000"

/** "Ran command `bun test`": the template with the code part in the mono label style. */
private fun toolLabel(template: String, code: String, mono: SpanStyle): AnnotatedString = buildAnnotatedString {
    val at = template.indexOf(PLACEHOLDER)
    if (at < 0) {
        append(template)
        return@buildAnnotatedString
    }
    append(template.substring(0, at))
    withStyle(mono) { append(code) }
    append(template.substring(at + PLACEHOLDER.length))
}

/**
 * The App icon tiles (DS §10.6): 64dp previews of each adaptive icon (background + the
 * foreground cropped to the launcher's 72/108 viewport), 12dp apart; the selected tile has a
 * 2dp `primary` ring 2dp out and a SemiBold `onSurface` label.
 */
@Composable
private fun AppIconPicker(selected: AppIcon, onSelect: (AppIcon) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        AppIcon.entries.forEach { icon ->
            val isSelected = icon == selected
            val label = stringResource(
                when (icon) {
                    AppIcon.Midnight -> R.string.appearance_icon_midnight
                    AppIcon.Classic -> R.string.appearance_icon_classic
                },
            )
            Column(
                modifier = Modifier
                    .focusRing(MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(icon) })
                    .padding(Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .padding(Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    LauncherIconPreview(icon)
                }
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LauncherIconPreview(icon: AppIcon) {
    val background = when (icon) {
        AppIcon.Midnight -> ClineBrand.LogoInk
        AppIcon.Classic -> ClineBrand.Accent9
    }
    val foreground = when (icon) {
        AppIcon.Midnight -> R.drawable.ic_launcher_foreground
        AppIcon.Classic -> R.drawable.ic_launcher_foreground_classic
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        // The 108dp canvas shows its central 72dp in a launcher mask: 64dp × 108 / 72 = 96dp.
        Image(painterResource(foreground), contentDescription = null, modifier = Modifier.requiredSize(96.dp))
    }
}

// ------------------------------------------------------------------------------ previews

@LightDarkPreviews
@Composable
private fun AppearanceFirstRunPreview() {
    SettingsPreviewSurface { AppearanceContent(AppearanceUiState(), AppearanceActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun AppearancePopulatedPreview() {
    SettingsPreviewSurface {
        AppearanceContent(
            AppearanceUiState(themeMode = ThemeMode.Dark, dynamicColor = false, fontSize = 17, previewFontSize = 17, appIcon = AppIcon.Classic),
            AppearanceActions(),
            PaddingValues(0.dp),
        )
    }
}

@LightDarkPreviews
@Composable
private fun AppearanceLargestFontPreview() {
    SettingsPreviewSurface {
        AppearanceContent(
            AppearanceUiState(fontSize = 15, previewFontSize = FontSizeSetting.MAX, dynamicSupported = false),
            AppearanceActions(),
            PaddingValues(0.dp),
        )
    }
}

@LightDarkPreviews
@Composable
private fun AppearanceIconErrorPreview() {
    SettingsPreviewSurface {
        AppearanceContent(
            AppearanceUiState(iconError = "Component class dev.cline.termux.LauncherClassic does not exist"),
            AppearanceActions(),
            PaddingValues(0.dp),
        )
    }
}
