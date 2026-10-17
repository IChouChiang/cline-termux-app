package dev.cline.termux.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.R
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/** C13 list (customize-and-rest.md §10): the bundled index, loaded once. */
@Composable
fun LicensesRoute(onBack: () -> Unit, onOpen: (LicenseEntry) -> Unit) {
    val context = LocalContext.current
    val vm: LicensesViewModel = viewModel(factory = LicensesViewModel.factory(context))
    val entries by vm.entries.collectAsStateWithLifecycle()
    LicensesScreen(entries, onBack, onOpen)
}

/** "Open source licenses": one `ListItem` per entry (name, "<licence> · <version>"), headings for TalkBack. */
@Composable
fun LicensesScreen(entries: List<LicenseEntry>?, onBack: () -> Unit, onOpen: (LicenseEntry) -> Unit) {
    PageScaffold(
        title = stringResource(R.string.licenses_title),
        navigationIcon = { BackNavIcon(onBack) },
        loading = entries == null,
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth(), contentPadding = padding) {
                items(entries.orEmpty(), key = { it.name }) { entry ->
                    ListItem(
                        modifier = Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button) { onOpen(entry) },
                        headlineContent = { Text(entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { heading() }) },
                        supportingContent = { Text(stringResource(R.string.licenses_meta, entry.license, entry.version), style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = { Icon(ClineIcons.ChevronRight, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/** A licence text: `ClineMono.small`, selectable, LTR. */
@Composable
fun LicenseTextRoute(name: String, file: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LicensesViewModel = viewModel(key = "license:$file", factory = LicensesViewModel.factory(context, file))
    val text by vm.text.collectAsStateWithLifecycle()
    LicenseTextScreen(name, text, onBack)
}

@Composable
fun LicenseTextScreen(name: String, text: String?, onBack: () -> Unit) {
    val gutter = LocalShellLayout.current.gutter
    PageScaffold(title = name, navigationIcon = { BackNavIcon(onBack) }, loading = text == null) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                text == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                text.isEmpty() -> Text(
                    stringResource(R.string.licenses_text_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(gutter),
                )
                else -> SelectionContainer(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxSize().verticalScroll(rememberScrollState())) {
                    LtrText(text, style = ClineMono.small, modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.l), readAsWritten = false)
                }
            }
        }
    }
}

private val previewEntries = listOf(
    LicenseEntry("Jetpack Compose and AndroidX", "Compose BOM 2026.09.00", "Apache-2.0", "https://developer.android.com/jetpack/androidx", "Apache-2.0.txt"),
    LicenseEntry("Geist Mono", "1.700", "OFL-1.1", "https://github.com/vercel/geist-font", "GeistMono-OFL.txt"),
)

@LightDarkPreviews
@Composable
private fun LicensesPreview() {
    ComponentPreview(padding = false) { LicensesScreen(previewEntries, onBack = {}, onOpen = {}) }
}

@LightDarkPreviews
@Composable
private fun LicenseTextPreview() {
    ComponentPreview(padding = false) {
        LicenseTextScreen("Geist Mono", "Copyright 2024 The Geist Project Authors\n\nThis Font Software is licensed under the SIL Open Font License, Version 1.1.", onBack = {})
    }
}
