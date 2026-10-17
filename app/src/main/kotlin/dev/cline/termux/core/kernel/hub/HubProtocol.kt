package dev.cline.termux.core.kernel.hub

/**
 * 1:1 port of `isHubProtocolCompatible` (`sdk/packages/shared/src/hub.ts`): a missing
 * `minClientProtocolVersion` / `maxClientProtocolVersion` defaults to the Hub's own
 * `protocolVersion`; an unparsable version counts as incompatible (onboarding-kernel.md §0.4 H1).
 */
object HubProtocol {
    const val CURRENT = HUB_PROTOCOL_VERSION

    data class Compatibility(val compatible: Boolean, val reason: Reason? = null) {
        enum class Reason { MissingProtocol, UnsupportedProtocol }
    }

    /** `"v1"` → 1; anything else → null (upstream `parseHubProtocolNumber`). */
    fun parseNumber(version: String?): Int? {
        val match = VERSION_PATTERN.matchEntire(version?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun isCompatible(
        protocolVersion: String?,
        minClientProtocolVersion: String?,
        maxClientProtocolVersion: String?,
        clientProtocolVersion: String = CURRENT,
    ): Compatibility {
        val hub = parseNumber(protocolVersion)
        val client = parseNumber(clientProtocolVersion)
        if (hub == null || client == null) return Compatibility(false, Compatibility.Reason.MissingProtocol)
        val min = parseNumber(minClientProtocolVersion) ?: hub
        val max = parseNumber(maxClientProtocolVersion) ?: hub
        return if (client in min..max) Compatibility(true) else Compatibility(false, Compatibility.Reason.UnsupportedProtocol)
    }

    fun isCompatible(health: HubHealth, clientProtocolVersion: String = CURRENT): Compatibility =
        isCompatible(health.protocolVersion, health.minClientProtocolVersion, health.maxClientProtocolVersion, clientProtocolVersion)

    /**
     * Which side is newer (onboarding-kernel.md E.9): the Hub requires a newer client when its
     * minimum exceeds ours; it is older than this app when its maximum is below ours.
     */
    enum class Direction { HubNewer, HubOlder, Unknown }

    fun direction(
        protocolVersion: String?,
        minClientProtocolVersion: String?,
        maxClientProtocolVersion: String?,
        clientProtocolVersion: String = CURRENT,
    ): Direction {
        val client = parseNumber(clientProtocolVersion) ?: return Direction.Unknown
        val hub = parseNumber(protocolVersion) ?: return Direction.Unknown
        val min = parseNumber(minClientProtocolVersion) ?: hub
        val max = parseNumber(maxClientProtocolVersion) ?: hub
        return when {
            min > client -> Direction.HubNewer
            max < client -> Direction.HubOlder
            else -> Direction.Unknown
        }
    }

    fun direction(health: HubHealth, clientProtocolVersion: String = CURRENT): Direction =
        direction(health.protocolVersion, health.minClientProtocolVersion, health.maxClientProtocolVersion, clientProtocolVersion)

    private val VERSION_PATTERN = Regex("^v(\\d+)$")
}
