package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.dashboard.DesktopCommandException
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.data.providers.CatalogHelper
import dev.cline.termux.data.providers.CatalogProjection
import dev.cline.termux.data.providers.CatalogProjectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The settings-helper slice the provider data layer uses: scripted `desktopCommand` replies by command name. */
class FakeCatalogHelper(initial: HelperState = HelperState.Ready) : CatalogHelper {
    data class Call(val command: String, val args: JsonObject)

    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<Call>()
    private val responders = HashMap<String, suspend (JsonObject) -> JsonElement>()

    fun respond(command: String, responder: suspend (JsonObject) -> JsonElement) {
        responders[command] = responder
    }

    fun respond(command: String, result: JsonElement) = respond(command) { result }

    fun fail(command: String, error: String) = respond(command) { throw DesktopCommandException(command, error) }

    fun callsFor(command: String): List<Call> = calls.filter { it.command == command }

    override suspend fun awaitSettled(): HelperState = state.value

    override suspend fun desktopCommand(command: String, args: JsonObject): JsonElement {
        if (state.value !is HelperState.Ready) throw HelperUnavailable.NotRunning((state.value as? HelperState.Unavailable)?.reason)
        calls += Call(command, args)
        val responder = responders[command] ?: throw DesktopCommandException(command, "no fake reply for $command")
        return responder(args)
    }
}

/** `catalog-cache.json` in memory; [writes] records every projection written. */
class InMemoryProjectionStore(var value: CatalogProjection? = null) : CatalogProjectionStore {
    val writes = mutableListOf<CatalogProjection>()
    override suspend fun read(): CatalogProjection? = value
    override suspend fun write(projection: CatalogProjection) {
        writes += projection
        value = projection
    }
}
