package dev.cline.termux.ui.about

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.BuildInfo
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.about.CHANGELOG_ASSET
import dev.cline.termux.data.about.ChangelogRelease
import dev.cline.termux.data.about.WhatsNewRelease
import dev.cline.termux.data.about.isBetaVersion
import dev.cline.termux.data.about.parseChangelog
import dev.cline.termux.data.about.recentReleases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A kernel version row (customize-and-rest.md §7.4): "Checking…", the value, "Not running", "Unavailable". */
sealed interface VersionValue {
    data object Checking : VersionValue
    data class Value(val text: String) : VersionValue
    data object NotRunning : VersionValue
    data object Unavailable : VersionValue
}

/** The Cline Hub row's value: "core <coreVersion> · <buildId first 8>", or "core <coreVersion>" without a hash id. */
@Immutable
data class AboutHubVersion(val coreVersion: String, val buildId: String?)

/** Release notes from the bundled changelog. */
sealed interface ReleaseNotes {
    data object Loading : ReleaseNotes
    data class Ready(val releases: List<ChangelogRelease>) : ReleaseNotes
    data object Error : ReleaseNotes
}

/** customize-and-rest.md §7.7 `AboutUiState`. */
@Immutable
data class AboutUiState(
    val version: String,
    val versionCode: Int,
    val beta: Boolean,
    val kernelRelease: VersionValue = VersionValue.Checking,
    val cliVersion: VersionValue = VersionValue.Checking,
    val hub: VersionValue = VersionValue.Checking,
    val hubVersion: AboutHubVersion? = null,
    /** The Highlights row is hidden when null (desktop). */
    val whatsNew: WhatsNewRelease? = null,
    val releases: ReleaseNotes = ReleaseNotes.Loading,
    val kernel: KernelGate = KernelGate.Ready,
) {
    /** The kernel rows cannot be read: Termux missing or RUN_COMMAND not granted (§7.4). */
    val kernelBlocked: Boolean get() = kernel is KernelGate.Blocked
}

/** Everything About reads (§7.7); [of] binds the app graph, tests pass fakes. */
interface AboutSources {
    val gate: StateFlow<KernelGate>
    val kernelState: StateFlow<KernelState>

    /** KS's cached `VERSION` probe (`release=`), when the coordinator has one. */
    fun cachedRelease(): String?

    /** RUN_COMMAND `readlink $PREFIX/opt/cline-termux/current` → basename. */
    suspend fun readPortRelease(): String?

    /** RUN_COMMAND `cline --version` → first line. */
    suspend fun cliVersion(): String?

    /** `GET /health` (no auth). */
    suspend fun probeHealth(): HubHealth?

    /** The bundled `assets/CHANGELOG.md`. */
    suspend fun changelog(): String

    fun latestWhatsNew(): WhatsNewRelease?

    companion object {
        fun of(context: Context, graph: AppGraph): AboutSources {
            val app = context.applicationContext
            return object : AboutSources {
                override val gate: StateFlow<KernelGate> get() = graph.kernel.gate
                override val kernelState: StateFlow<KernelState> get() = graph.kernel.state
                override fun cachedRelease(): String? = graph.kernel.portVersion.value?.release
                override suspend fun readPortRelease(): String? = runTermux(graph.termux) { termux ->
                    val result = termux.run(ClineCommands.readPortRelease())
                    if (result.ok) AboutFormat.releaseFromReadlink(result.stdout) else null
                }
                override suspend fun cliVersion(): String? = runTermux(graph.termux) { termux ->
                    val result = termux.run(ClineCommands.cliVersion())
                    if (result.ok) AboutFormat.firstLine(result.stdout) else null
                }
                override suspend fun probeHealth(): HubHealth? = graph.kernel.probeHealth()
                override suspend fun changelog(): String = withContext(Dispatchers.IO) {
                    app.assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
                }
                override fun latestWhatsNew(): WhatsNewRelease? = graph.whatsNew.latest()
            }
        }

        private suspend fun runTermux(termux: TermuxExecutor, block: suspend (TermuxExecutor) -> String?): String? = block(termux)
    }
}

/** Pure helpers of the About rows (unit-tested). */
object AboutFormat {
    /** `readlink …/current` → its basename (`/…/opt/cline-termux/3.0.68-termux.2` → `3.0.68-termux.2`). */
    fun releaseFromReadlink(stdout: String): String? = firstLine(stdout)?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /** The first non-blank line, trimmed. */
    fun firstLine(stdout: String): String? = stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    /**
     * A hash `buildId` cut to its first 8 characters (§7.5). A source-built Hub reports
     * `source-<coreVersion>` (sdk/packages/core/src/hub/discovery/index.ts), which carries
     * nothing beyond the core version already shown, so it yields null and the row reads
     * "core <coreVersion>".
     */
    fun shortBuildId(buildId: String?): String? = buildId?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith(SOURCE_BUILD_PREFIX) }
        ?.take(8)

    private const val SOURCE_BUILD_PREFIX = "source-"

    /** The Hub row from a health reply; null when it carries no core version. */
    fun hubVersion(health: HubHealth?): AboutHubVersion? =
        health?.coreVersion?.takeIf { it.isNotBlank() }?.let { AboutHubVersion(it, shortBuildId(health.buildId)) }

    /** The Hub row from the connected coordinator state (the last probe), or null. */
    fun hubVersion(state: KernelState): AboutHubVersion? {
        val info = (state as? KernelState.Ready)?.info ?: return null
        return info.coreVersion?.takeIf { it.isNotBlank() }?.let { AboutHubVersion(it, shortBuildId(info.buildId)) }
    }
}

/**
 * C10 About (customize-and-rest.md §7): app version and channel, the kernel's release, CLI
 * and Hub versions (each "Checking…" until its source answers; "Not running" for a stopped
 * Hub; "Unavailable" when Termux cannot be asked), the latest What's new entry and the
 * bundled release notes.
 */
class AboutViewModel(
    private val sources: AboutSources,
    versionName: String = BuildInfo.VERSION_NAME,
    versionCode: Int = BuildInfo.VERSION_CODE,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        AboutUiState(
            version = versionName,
            versionCode = versionCode,
            beta = isBetaVersion(versionName),
            whatsNew = sources.latestWhatsNew(),
            kernel = sources.gate.value,
        ),
    )
    val ui: StateFlow<AboutUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { loadReleaseNotes() }
        viewModelScope.launch {
            sources.gate.collect { gate ->
                _ui.update { it.copy(kernel = gate) }
            }
        }
        viewModelScope.launch {
            // Blocked ↔ usable decides whether Termux can be asked at all.
            sources.gate.map { it is KernelGate.Blocked }.distinctUntilChanged().collect { blocked ->
                if (blocked) {
                    _ui.update {
                        it.copy(
                            kernelRelease = VersionValue.Unavailable,
                            cliVersion = VersionValue.Unavailable,
                            hub = VersionValue.Unavailable,
                            hubVersion = null,
                        )
                    }
                } else {
                    loadTermuxRows()
                }
            }
        }
        viewModelScope.launch {
            // The Hub row follows the connection: refresh on every settled gate change. While
            // Termux is missing or blocked the row stays "Unavailable" (§7.4).
            sources.gate.map { gate -> gateKey(gate) }.distinctUntilChanged().collect { key ->
                if (key != null && key != BLOCKED) loadHub()
            }
        }
    }

    /** Re-reads every kernel row (pull to refresh *(Android)*). */
    fun refresh() {
        if (_ui.value.kernel is KernelGate.Blocked) return
        viewModelScope.launch { loadTermuxRows() }
        viewModelScope.launch { loadHub() }
    }

    private suspend fun loadTermuxRows() {
        _ui.update { it.copy(kernelRelease = VersionValue.Checking, cliVersion = VersionValue.Checking) }
        val cached = sources.cachedRelease()?.takeIf { it.isNotBlank() }
        val release = cached?.let { VersionValue.Value(it) } ?: termuxValue { sources.readPortRelease() }
        _ui.update { it.copy(kernelRelease = release) }
        val cli = termuxValue { sources.cliVersion() }
        _ui.update { it.copy(cliVersion = cli) }
    }

    private suspend fun termuxValue(read: suspend () -> String?): VersionValue = try {
        read()?.let { VersionValue.Value(it) } ?: VersionValue.Unavailable
    } catch (e: CancellationException) {
        throw e
    } catch (e: TermuxUnavailable) {
        log("termux row", e.javaClass.simpleName)
        VersionValue.Unavailable
    } catch (e: Exception) {
        log("termux row", e.javaClass.simpleName)
        VersionValue.Unavailable
    }

    private suspend fun loadHub() {
        val connected = AboutFormat.hubVersion(sources.kernelState.value)
        if (connected != null) {
            _ui.update { it.copy(hub = VersionValue.Value(connected.coreVersion), hubVersion = connected) }
            return
        }
        if (_ui.value.hub !is VersionValue.Value) _ui.update { it.copy(hub = VersionValue.Checking) }
        val probed = try {
            AboutFormat.hubVersion(sources.probeHealth())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("health", e.javaClass.simpleName)
            null
        }
        _ui.update {
            if (probed != null) it.copy(hub = VersionValue.Value(probed.coreVersion), hubVersion = probed) else it.copy(hub = VersionValue.NotRunning, hubVersion = null)
        }
    }

    private suspend fun loadReleaseNotes() {
        val notes = try {
            ReleaseNotes.Ready(recentReleases(parseChangelog(sources.changelog())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("changelog", e.javaClass.simpleName)
            ReleaseNotes.Error
        }
        _ui.update { it.copy(releases = if (notes is ReleaseNotes.Ready && notes.releases.isEmpty()) ReleaseNotes.Error else notes) }
    }

    private fun log(what: String, reason: String) {
        runCatching { Log.w(TAG, "$what: $reason") }
    }

    companion object {
        private const val TAG = "About"
        private const val BLOCKED = "blocked"

        /** Ready and Down settle the Hub row; busy states keep the last value until they settle. */
        internal fun gateKey(gate: KernelGate): String? = when (gate) {
            KernelGate.Ready, KernelGate.DegradedNoCommands -> "up"
            is KernelGate.Down -> "down:${gate.variant}"
            is KernelGate.Blocked -> BLOCKED
            is KernelGate.Busy -> null
        }

        fun factory(context: Context, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { AboutViewModel(AboutSources.of(context, graph)) }
        }
    }
}
