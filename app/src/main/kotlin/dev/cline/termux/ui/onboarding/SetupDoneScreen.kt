package dev.cline.termux.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * C5 Done (onboarding-kernel.md C.4, C.5; desktop `DoneStep`): the check, "You're all set",
 * what is connected, the ClinePass card for the Cline path (the desktop's "Free models" list
 * needs a sidecar command the kernel lacks, so the card shows alone, as the desktop does when
 * that call fails), the unverified-key line, and "Start building".
 */
@Composable
fun SetupDoneScreen(connection: OnboardingConnection, onStartBuilding: () -> Unit, onBack: (() -> Unit)?) {
    val open = rememberOpenExternal()
    SetupDoneContent(connection, onStartBuilding, onBack, onGetClinePass = { open(CLINE_PASS_SUBSCRIBE_URL) })
}

@Composable
fun SetupDoneContent(connection: OnboardingConnection, onStartBuilding: () -> Unit, onBack: (() -> Unit)?, onGetClinePass: () -> Unit) {
    OnboardingScaffold(surface = OnboardingSurface.Plain, onBack = onBack, centered = true, gridBand = true) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(ClineIcons.CheckCircleFilled, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(Spacing.l))
            PlainTitle(stringResource(R.string.done_title))
            Spacer(Modifier.height(Spacing.s))
            Text(
                when (connection) {
                    is OnboardingConnection.Cline -> stringResource(R.string.done_cline_connected)
                    is OnboardingConnection.Provider -> stringResource(R.string.done_provider_connected, connection.name)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (connection is OnboardingConnection.Cline && connection.unverified) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.done_cline_unverified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (connection is OnboardingConnection.Cline) {
                Spacer(Modifier.height(Spacing.xxl))
                ClinePassCard(onGetClinePass)
            }
            Spacer(Modifier.height(Spacing.xxl))
            Button(onClick = onStartBuilding, modifier = Modifier.widthIn(max = 256.dp).fillMaxWidth()) {
                Text(stringResource(R.string.done_start_building))
            }
        }
    }
}

@Composable
private fun ClinePassCard(onGetClinePass: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.done_clinepass_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.done_clinepass_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val label = stringResource(R.string.done_clinepass_cta)
            val cd = stringResource(R.string.onboarding_link_cd, label)
            Box(Modifier.fillMaxWidth().padding(top = Spacing.s), contentAlignment = Alignment.CenterEnd) {
                FilledTonalButton(onClick = onGetClinePass, modifier = Modifier.semantics { contentDescription = cd }) {
                    Text(label)
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun DoneClinePreview() {
    ClinePreviewTheme { SetupDoneContent(OnboardingConnection.Cline(), {}, onBack = {}, onGetClinePass = {}) }
}

@LightDarkPreviews
@Composable
private fun DoneClineUnverifiedPreview() {
    ClinePreviewTheme { SetupDoneContent(OnboardingConnection.Cline(unverified = true), {}, onBack = {}, onGetClinePass = {}) }
}

@LightDarkPreviews
@Composable
private fun DoneProviderPreview() {
    ClinePreviewTheme { SetupDoneContent(OnboardingConnection.Provider("Anthropic"), {}, onBack = {}, onGetClinePass = {}) }
}
