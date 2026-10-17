package dev.cline.termux.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.WelcomeHero
import dev.cline.termux.ui.navigation.rememberShellLayout
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * A0 Welcome (onboarding-kernel.md A.4, A.5; desktop `WelcomeStep`): the hero mark, "Cline Termux",
 * the unofficial notice in place of the desktop tagline, the body, "Get started" and the footer,
 * whose text depends on P1 ([termuxInstalled], evaluated synchronously). Back is not intercepted
 * (it leaves the app).
 */
@Composable
fun WelcomeScreen(termuxInstalled: Boolean, onGetStarted: () -> Unit) {
    val layout = rememberShellLayout()
    val wide = layout.widthDp >= 600
    OnboardingScaffold(surface = OnboardingSurface.Plain, centered = true) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!layout.heightCompact) {
                WelcomeHero(size = if (wide) 96.dp else 72.dp)
                Spacer(Modifier.height(Spacing.l))
            }
            Text(
                stringResource(R.string.onboarding_welcome_title),
                style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.s))
            // Balanced breaks, so a wrapped notice does not leave one word on its last line.
            Text(
                stringResource(R.string.onboarding_welcome_tagline),
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.onboarding_welcome_notice),
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(R.string.onboarding_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xxl))
            Button(onClick = onGetStarted, modifier = Modifier.widthIn(max = 256.dp).fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_welcome_cta))
            }
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(if (termuxInstalled) R.string.onboarding_welcome_footer else R.string.onboarding_welcome_footer_no_termux),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun WelcomePreview() {
    ClinePreviewTheme { WelcomeScreen(termuxInstalled = true, onGetStarted = {}) }
}

@LightDarkPreviews
@Composable
private fun WelcomeNoTermuxPreview() {
    ClinePreviewTheme { WelcomeScreen(termuxInstalled = false, onGetStarted = {}) }
}
