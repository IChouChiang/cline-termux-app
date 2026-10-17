package dev.cline.termux.ui.settings

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.ClineSwitch

// Adapted from cline-termux-voice (same owner): ui/settings/Prefs.kt (`PrefHeader`, `PrefRow`, `PrefSwitch`).

/*
 * The preference-list vocabulary of the settings pages (DS §4.1 #12, SP §0.8): flat Material 3
 * `ListItem`s on `surface`, one focus stop per row, `titleSmall` `primary` headers
 * (`SectionHeader`), and the 720dp content column of medium/expanded windows (DS §3.3).
 */

/**
 * The page list: a `LazyColumn` whose items are centred in [Spacing.pageMaxWidth]. Use
 * [settingsItem] inside so every row gets the column width.
 */
@Composable
fun SettingsList(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    // DS §5.6: all four sides of the Scaffold's padding (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction),
            bottom = contentPadding.calculateBottomPadding() + Spacing.l,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** One row of [SettingsList], constrained to the column width. */
fun LazyListScope.settingsItem(key: Any? = null, contentType: Any? = null, content: @Composable () -> Unit) {
    item(key = key, contentType = contentType) {
        Column(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth()) { content() }
    }
}

/** The page description (desktop `PageHeader.description`): `bodyMedium` `onSurfaceVariant`, 16dp gutters. */
@Composable
fun SettingsDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}

/**
 * Transparent list colours, or the M3 selected indicator (`secondaryContainer`, DS §1.4); a
 * disabled row dims its text and leading icon to 38 %. Every Settings row uses it.
 */
@Composable
internal fun rowColors(selected: Boolean, enabled: Boolean) = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    headlineColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    },
    supportingColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    },
    leadingIconColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    },
    trailingIconColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
)

private const val DISABLED_ALPHA = 0.38f

/**
 * A navigation or action row (S1.2 anatomy): leading 24dp symbol, `bodyLarge` headline,
 * `bodyMedium` supporting (max 2 lines), trailing decorative [trailingIcon] (`chevron_right` for navigation, `open_in_new` for system pages, none for action rows).
 * The whole row is one `Role.Button` target; [onClickLabel] is the TalkBack action name
 * ("double-tap to Export…"). [spokenSupporting] replaces the supporting text for TalkBack
 * when it has a visual-only part (the kernel dot).
 */
@Composable
fun PrefNavRow(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailingIcon: ImageVector? = ClineIcons.ChevronRight,
    selected: Boolean = false,
    onClickLabel: String? = null,
    spokenSupporting: String? = null,
) {
    val description = spokenSupporting?.let { stringResource(R.string.settings_cd_pair, headline, it) }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { content -> { SupportingSlot(content) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
        trailingContent = trailingIcon?.let { { Icon(it, contentDescription = null) } },
        colors = rowColors(selected, enabled = true),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (selected) this.selected = true
                if (description != null) contentDescription = description
            },
    )
}

/**
 * A row whose switch is the whole row (SP §0.8, voice `PrefSwitch`): one `toggleable` target
 * with `Role.Switch`. [saving] keeps the switch visible but disabled with a 16dp spinner
 * before it (DS #12 "saving") and announces "Saving". [contentDescription] names the switch
 * out of context ("Task completed notifications").
 */
@Composable
fun PrefSwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    saving: Boolean = false,
    contentDescription: String? = null,
    startInset: Dp = 0.dp,
) {
    val savingState = stringResource(
        R.string.settings_cd_pair,
        stringResource(if (checked) R.string.settings_switch_on else R.string.settings_switch_off),
        stringResource(R.string.settings_saving),
    )
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { content -> { SupportingSlot(content) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                ClineSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled && !saving,
                )
            }
        },
        colors = rowColors(selected = false, enabled = enabled),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = startInset)
            .focusRing(RectangleShape, inset = true)
            .toggleable(value = checked, enabled = enabled && !saving, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
                if (saving) stateDescription = savingState
            },
    )
}

/** Supporting content at most two lines tall, as the M3 two-line row allows. */
@Composable
private fun SupportingSlot(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) { content() }
}
