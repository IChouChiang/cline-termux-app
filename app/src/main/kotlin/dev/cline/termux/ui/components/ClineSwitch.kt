package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * The app's one switch (DS §4.1 #12): M3 1.4 defaults with the check icon in the thumb while
 * checked, so every toggle looks the same from screen to screen. Pass `onCheckedChange = null`
 * when the whole row is `toggleable` and owns the click and the semantics.
 */
@Composable
fun ClineSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(ClineIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
    )
}

@LightDarkPreviews
@Composable
private fun ClineSwitchPreview() = ComponentPreview {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ClineSwitch(checked = true, onCheckedChange = {})
            ClineSwitch(checked = false, onCheckedChange = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ClineSwitch(checked = true, onCheckedChange = {}, enabled = false)
            ClineSwitch(checked = false, onCheckedChange = {}, enabled = false)
        }
    }
}
