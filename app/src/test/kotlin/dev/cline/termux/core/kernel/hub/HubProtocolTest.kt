package dev.cline.termux.core.kernel.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubProtocolTest {
    @Test
    fun `compatibility follows isHubProtocolCompatible`() {
        assertTrue(HubProtocol.isCompatible("v1", "v1", "v1").compatible)
        assertTrue("missing bounds default to the hub version", HubProtocol.isCompatible("v1", null, null).compatible)
        assertTrue(HubProtocol.isCompatible("v2", "v1", "v2").compatible)
        val tooNew = HubProtocol.isCompatible("v2", "v2", "v3")
        assertFalse(tooNew.compatible)
        assertEquals(HubProtocol.Compatibility.Reason.UnsupportedProtocol, tooNew.reason)
        val missing = HubProtocol.isCompatible(null, null, null)
        assertFalse(missing.compatible)
        assertEquals(HubProtocol.Compatibility.Reason.MissingProtocol, missing.reason)
        assertFalse(HubProtocol.isCompatible("1", null, null).compatible)
        assertTrue(HubProtocol.isCompatible(HubHealth(ok = true, protocolVersion = "v1")).compatible)
    }

    @Test
    fun `direction says which side is newer`() {
        assertEquals(HubProtocol.Direction.HubNewer, HubProtocol.direction("v2", "v2", "v2"))
        assertEquals(HubProtocol.Direction.HubOlder, HubProtocol.direction("v0", "v0", "v0"))
        assertEquals(HubProtocol.Direction.Unknown, HubProtocol.direction("v1", "v1", "v1"))
        assertEquals(HubProtocol.Direction.Unknown, HubProtocol.direction(null, null, null))
        assertEquals(HubProtocol.Direction.HubNewer, HubProtocol.direction(HubHealth(protocolVersion = "v3", minClientProtocolVersion = "v2")))
    }

    @Test
    fun `version numbers parse strictly`() {
        assertEquals(1, HubProtocol.parseNumber("v1"))
        assertEquals(12, HubProtocol.parseNumber(" v12 "))
        assertNull(HubProtocol.parseNumber("1"))
        assertNull(HubProtocol.parseNumber("v1.2"))
        assertNull(HubProtocol.parseNumber(null))
    }
}
