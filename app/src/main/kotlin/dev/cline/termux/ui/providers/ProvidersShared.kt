package dev.cline.termux.ui.providers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.findActivity
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.openAppDetails
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.Spacing

/**
 * Turns the providers ViewModel's one-shot events into snackbars and navigation. Every
 * destination of the graph collects (each event is delivered once, to whichever collects it);
 * the handling does not depend on which one it is. [onSelect] is the expanded detail pane's
 * selection.
 */
@Composable
fun ProvidersEventsEffect(
    vm: ProvidersViewModel,
    navigator: ShellNavigator?,
    twoPane: Boolean,
) {
    val snackbar = LocalSnackbarHost.current
    val resources = LocalResources.current
    val currentTwoPane by rememberUpdatedState(twoPane)
    LaunchedEffect(vm, navigator) {
        vm.events.collect { event ->
            when (event) {
                is ProvidersEvent.SaveFailed -> snackbar.showReplacing(
                    resources.getString(R.string.provider_save_failed, event.providerId, event.message),
                    duration = SnackbarDuration.Long,
                )
                is ProvidersEvent.ProviderConfigured -> snackbar.showReplacing(
                    resources.getString(R.string.provider_configured_snackbar, event.providerId, event.modelId),
                )
                is ProvidersEvent.ProviderVanished -> {
                    navigator?.let { nav ->
                        val current = nav.currentRoute()
                        if (current is dev.cline.termux.ui.navigation.SettingsProvider && current.providerId == event.providerId) nav.back()
                    }
                    snackbar.showReplacing(resources.getString(R.string.provider_vanished))
                }
                is ProvidersEvent.SignedIn -> if (event.providerId == dev.cline.termux.data.account.ClineSignInCoordinator.CLINE) {
                    snackbar.showReplacing(resources.getString(R.string.signin_connected))
                }
                is ProvidersEvent.ProviderAdded -> navigator?.let { nav ->
                    nav.nav.popBackStack<dev.cline.termux.ui.navigation.SettingsProvidersAdd>(inclusive = true)
                    if (!currentTwoPane) nav.push(dev.cline.termux.ui.navigation.SettingsProvider(event.providerId))
                }
            }
        }
    }
}

// ---- shared bits of the list rows ---------------------------------------------------------

/** The row's auth hint (S4.2): icon + label. */
@Composable
fun authHintLabel(kind: AuthKind): String = when (kind) {
    AuthKind.OAuth -> stringResource(R.string.providers_auth_oauth)
    AuthKind.Local -> stringResource(R.string.providers_auth_local)
    AuthKind.ApiKey -> stringResource(R.string.providers_auth_api_key)
}

@Composable
fun AuthHintIcon(kind: AuthKind, modifier: Modifier = Modifier) {
    val icon = when (kind) {
        AuthKind.OAuth -> ClineIcons.Language
        AuthKind.Local -> ClineIcons.Devices
        AuthKind.ApiKey -> ClineIcons.Key
    }
    Icon(icon, contentDescription = null, modifier = modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun sectionTitle(section: ProviderSection): String = when (section) {
    ProviderSection.Configured -> stringResource(R.string.providers_section_configured)
    ProviderSection.Popular -> stringResource(R.string.providers_section_popular)
    ProviderSection.All -> stringResource(R.string.providers_section_all)
}

/**
 * A collapsible section header (S4.1): one 48dp button with `heading()`, the `expand_more`
 * chevron pointing to the end when collapsed (DS #4), label `titleSmall onSurfaceVariant`. TalkBack
 * reads "<section>, <n> providers, Expanded".
 */
@Composable
fun ProviderSectionHeader(
    section: ProviderSection,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.l,
) {
    val title = sectionTitle(section)
    val countText = copyPluralStringResource(R.plurals.providers_section_count, count, count)
    val description = stringResource(R.string.providers_section_cd, title, countText)
    val state = stringResource(if (expanded) R.string.providers_state_expanded else R.string.providers_state_collapsed)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                contentDescription = description
                stateDescription = state
            }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisclosureChevron(
            expanded = expanded,
            size = DisclosureChevronDefaults.ToolRowSize,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.s))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

