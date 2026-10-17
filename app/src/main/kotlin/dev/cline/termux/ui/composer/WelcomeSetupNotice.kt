package dev.cline.termux.ui.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.focusRing

/**
 * C8 / DS §4.2 WelcomeSetupNotice (desktop `welcome-setup-notice.tsx`): docked 12dp above
 * Home's composer while no provider is configured. Announced politely once.
 * Show it when [ComposerUiState.showSetupNotice] (hidden while the catalog is still loading
 * and while the kernel is down).
 */
@Composable
fun WelcomeSetupNotice(onConnectModel: () -> Unit, onModelSettings: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(32.dp).background(primary.copy(alpha = 0.15f), MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ClineIcons.Cable, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.composer_setup_notice_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.composer_setup_notice_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                Modifier.padding(start = 32.dp + Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Button(onClick = onConnectModel, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text(stringResource(R.string.composer_setup_notice_connect)) }
                TextButton(onClick = onModelSettings, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text(stringResource(R.string.composer_setup_notice_settings)) }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun WelcomeSetupNoticePreview() = ComponentPreview {
    Box(Modifier.width(412.dp)) { WelcomeSetupNotice(onConnectModel = {}, onModelSettings = {}) }
}
