package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceRepository
import dev.cline.termux.ui.marketplace.MarketplaceBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The marketplace screen's seam (ui/marketplace `MarketplaceBackend`): settable helper state
 * and kernel gate, canned catalog / installed / install answers, and a record of every call.
 */
class FakeMarketplaceBackend(override val actionScope: CoroutineScope) : MarketplaceBackend {
    override val helperState = MutableStateFlow<HelperState>(HelperState.Ready)
    override val kernelGate = MutableStateFlow<KernelGate>(KernelGate.Ready)

    var now: Long = 1_000_000L
    var workspace: String = "/data/data/com.termux/files/home/project"

    val acquired = mutableListOf<String>()
    val released = mutableListOf<String>()
    var helperRetries = 0
    var kernelStarts = 0

    val catalogCalls = mutableListOf<Boolean>()
    var catalogAnswer: suspend (force: Boolean) -> MarketplaceRepository.CatalogLoad = {
        MarketplaceRepository.CatalogLoad.Error(null, "offline")
    }

    val installedCalls = mutableListOf<String>()
    var installedAnswer: suspend (MarketplaceCatalog) -> Outcome<MarketplaceRepository.Installed> = {
        Outcome.Ok(MarketplaceRepository.Installed(emptySet(), approximate = false))
    }

    val installs = mutableListOf<String>()
    val uninstalls = mutableListOf<String>()
    var installAnswer: suspend (MarketplaceEntry) -> MarketplaceRepository.ActionResult = {
        MarketplaceRepository.ActionResult.Done(MarketplaceDone.of(it, install = true))
    }
    var uninstallAnswer: suspend (MarketplaceEntry) -> MarketplaceRepository.ActionResult = {
        MarketplaceRepository.ActionResult.Done(MarketplaceDone.of(it, install = false))
    }

    override suspend fun acquireHelper(owner: String) {
        acquired += owner
    }

    override fun releaseHelper(owner: String) {
        released += owner
    }

    override suspend fun retryHelper() {
        helperRetries++
    }

    override suspend fun startKernel() {
        kernelStarts++
    }

    override suspend fun workspaceRoot(): String = workspace

    override suspend fun catalog(force: Boolean): MarketplaceRepository.CatalogLoad {
        catalogCalls += force
        return catalogAnswer(force)
    }

    override suspend fun installedKeys(catalog: MarketplaceCatalog, workspaceRoot: String): Outcome<MarketplaceRepository.Installed> {
        installedCalls += workspaceRoot
        return installedAnswer(catalog)
    }

    override suspend fun install(entry: MarketplaceEntry, workspaceRoot: String): MarketplaceRepository.ActionResult {
        installs += entry.key
        return installAnswer(entry)
    }

    override suspend fun uninstall(entry: MarketplaceEntry, workspaceRoot: String): MarketplaceRepository.ActionResult {
        uninstalls += entry.key
        return uninstallAnswer(entry)
    }

    override fun nowMs(): Long = now
}
