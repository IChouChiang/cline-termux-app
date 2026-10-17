package dev.cline.termux.ui.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.core.kernel.termux.TermuxRunner

/**
 * Developer screen (debug builds only, reached through `DebugScreens.KernelProbe`): drives
 * the Termux RUN_COMMAND bridge and shows raw results. [onBack] adds the back arrow when the
 * screen is pushed (from About).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernelProbeScreen(viewModel: KernelProbeViewModel = viewModel(), onBack: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kernel probe") },
                navigationIcon = { onBack?.let { dev.cline.termux.ui.components.BackNavIcon(it) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = viewModel::refreshStatus, label = {
                        Text(if (state.termuxInstalled) "Termux installed" else "Termux missing")
                    })
                    AssistChip(onClick = { permissionLauncher.launch(TermuxRunner.PERMISSION) }, label = {
                        Text(if (state.permissionGranted) "RUN_COMMAND granted" else "Grant RUN_COMMAND")
                    })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.foregroundStart, onCheckedChange = viewModel::setForegroundStart)
                    Spacer(Modifier.width(8.dp))
                    Text("startForegroundService", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(viewModel.probes) { (title, command) ->
                OutlinedButton(
                    onClick = { viewModel.run(title, command) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(title) }
            }
            item {
                TextButton(onClick = viewModel::clear) { Text("Clear results") }
            }
            itemsIndexed(state.entries.asReversed()) { _, entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (entry.running) CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        }
                        if (entry.summary.isNotEmpty()) {
                            Text(entry.summary, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            entry.body,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}
