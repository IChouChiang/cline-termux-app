package dev.cline.termux.ui.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.openTermux
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/** B.5 store links. */
const val TERMUX_FDROID_URL = "https://f-droid.org/packages/com.termux/"
const val TERMUX_GITHUB_URL = "https://github.com/termux/termux-app/releases/latest"

class InstallTermuxActions(
    val onBack: (() -> Unit)? = null,
    val onOpenUrl: (String) -> Unit = {},
    val onOpenTermuxSettings: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onCheckAgain: () -> Unit = {},
)

/**
 * B wired: `ON_RESUME` runs P1 (and P3), a context receiver for the `com.termux` package
 * broadcasts runs while B is composed (B.6), links go through `openExternal`.
 */
@Composable
fun InstallTermuxScreen(viewModel: InstallTermuxViewModel, onBack: (() -> Unit)?) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = rememberOpenExternal()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    TermuxPackageReceiver(onChanged = viewModel::onPackageChanged)
    InstallTermuxContent(
        ui,
        InstallTermuxActions(
            onBack = onBack,
            onOpenUrl = open,
            onOpenTermuxSettings = { OnboardingIntents.openTermuxSettings(context) },
            onOpenTermux = { openTermux(context) },
            onCheckAgain = viewModel::checkAgain,
        ),
    )
}

/** Registers for `PACKAGE_ADDED` / `REPLACED` / `CHANGED` (scheme `package`) and filters for `com.termux`. */
@Composable
private fun TermuxPackageReceiver(onChanged: () -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onChanged)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == TermuxPaths.PACKAGE) callback()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

@Composable
fun InstallTermuxContent(ui: InstallTermuxUiState, actions: InstallTermuxActions) {
    val (title, body) = when (val v = ui.variant) {
        InstallTermuxVariant.Missing -> stringResource(R.string.install_termux_title) to stringResource(R.string.install_termux_body)
        is InstallTermuxVariant.TooOld -> stringResource(R.string.install_termux_update_title) to stringResource(R.string.install_termux_update_body, v.versionName)
        InstallTermuxVariant.Disabled -> stringResource(R.string.install_termux_disabled_title) to stringResource(R.string.install_termux_disabled_body)
        InstallTermuxVariant.NotSetUp -> stringResource(R.string.install_termux_setup_title) to stringResource(R.string.install_termux_setup_body)
    }
    val buttonsEnabled = !ui.checking && !ui.ready
    OnboardingScaffold(surface = OnboardingSurface.Plain, onBack = actions.onBack, centered = true) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(ClineIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(Spacing.l))
            PlainTitle(title)
            Spacer(Modifier.height(Spacing.l))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (ui.variant is InstallTermuxVariant.Missing) {
                Spacer(Modifier.height(Spacing.m))
                Text(
                    stringResource(R.string.install_termux_builds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
            val buttonWidth = Modifier.widthIn(max = 320.dp).fillMaxWidth()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                when (ui.variant) {
                    InstallTermuxVariant.Missing, is InstallTermuxVariant.TooOld -> {
                        val fdroid = stringResource(R.string.install_termux_fdroid)
                        val github = stringResource(R.string.install_termux_github)
                        val fdroidCd = stringResource(R.string.onboarding_link_cd, fdroid)
                        val githubCd = stringResource(R.string.onboarding_link_cd, github)
                        Button(
                            onClick = { actions.onOpenUrl(TERMUX_FDROID_URL) },
                            enabled = buttonsEnabled,
                            modifier = buttonWidth.semantics { contentDescription = fdroidCd },
                        ) { LinkLabel(fdroid) }
                        OutlinedButton(
                            onClick = { actions.onOpenUrl(TERMUX_GITHUB_URL) },
                            enabled = buttonsEnabled,
                            modifier = buttonWidth.semantics { contentDescription = githubCd },
                        ) { LinkLabel(github) }
                    }
                    InstallTermuxVariant.Disabled -> Button(onClick = actions.onOpenTermuxSettings, enabled = buttonsEnabled, modifier = buttonWidth) {
                        Text(stringResource(R.string.check_termux_open_settings))
                    }
                    InstallTermuxVariant.NotSetUp -> Button(onClick = actions.onOpenTermux, enabled = buttonsEnabled, modifier = buttonWidth) {
                        Text(stringResource(R.string.setup_open_termux))
                    }
                }
                TextButton(onClick = actions.onCheckAgain, enabled = buttonsEnabled) { Text(stringResource(R.string.setup_check_again)) }
            }
            Spacer(Modifier.height(Spacing.l))
            val status = when {
                ui.ready -> stringResource(R.string.install_termux_ready)
                ui.checking -> stringResource(R.string.setup_checking)
                ui.variant != InstallTermuxVariant.NotSetUp -> stringResource(R.string.install_termux_waiting)
                else -> null
            }
            if (status != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    if (!ui.ready) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.s))
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LinkLabel(text: String) {
    Text(text)
    Spacer(Modifier.width(Spacing.s))
    Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
}

@LightDarkPreviews
@Composable
private fun InstallTermuxMissingPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.Missing, pushedFromSetup = true), InstallTermuxActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun InstallTermuxTooOldPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.TooOld("0.101.0")), InstallTermuxActions()) }
}

@LightDarkPreviews
@Composable
private fun InstallTermuxDisabledPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.Disabled), InstallTermuxActions()) }
}

@LightDarkPreviews
@Composable
private fun InstallTermuxNotSetUpPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.NotSetUp), InstallTermuxActions()) }
}

@LightDarkPreviews
@Composable
private fun InstallTermuxCheckingPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.Missing, checking = true), InstallTermuxActions()) }
}

@LightDarkPreviews
@Composable
private fun InstallTermuxReadyPreview() {
    ClinePreviewTheme { InstallTermuxContent(InstallTermuxUiState(InstallTermuxVariant.NotSetUp, ready = true), InstallTermuxActions()) }
}
