package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.termux.PortRelease
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.TermuxPresence
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelStateTest {
    @Test
    fun `hub status reply parses the fixture shape`() {
        val payload = HubJson.parseToJsonElement(
            """{"hubId":"hub_1791064687984_lzgjs","draining":false,"activeRpcTurns":0,"pendingRuns":0,"idle":true,"eventLog":{"lastSequence":114}}""",
        ).jsonObject
        val status = HubLiveStatus.parse(payload)
        assertEquals(HubLiveStatus(draining = false, activeRpcTurns = 0, pendingRuns = 0, lastSequence = 114, idle = true, hubId = "hub_1791064687984_lzgjs"), status)
        assertEquals(0L, HubLiveStatus.parse(HubJson.parseToJsonElement("{}").jsonObject).lastSequence)
    }

    @Test
    fun `doctor json is the last object on stdout and carries no secrets`() {
        val stdout = "Checking processes...\n" +
            """{"cliVersion":"3.0.68","coreVersion":"3.0.68","hubHealthy":true,"hubPid":4242,"hubUptime":"2h 14m","listeningPids":[4242],"staleHubPids":[1,2],"hubStartupLocks":[{"pid":3}],"staleCliPids":[7]}""" + "\n"
        val doctor = DoctorSummary.parseStdout(stdout)!!
        assertEquals("3.0.68", doctor.cliVersion)
        assertTrue(doctor.hubHealthy)
        assertEquals(4242, doctor.hubPid)
        assertEquals("2h 14m", doctor.hubUptime)
        assertEquals(listOf(1, 2), doctor.staleHubPids)
        assertEquals(1, doctor.hubStartupLocks)
        assertEquals(listOf(7), doctor.staleCliPids)
        assertNull(DoctorSummary.parseStdout("no json here"))
        assertNull(DoctorSummary.parseStdout("{not json"))
    }

    @Test
    fun `hub stop output is read from its last json line`() {
        assertEquals(true, KernelCoordinator.parseStopped("""{"stopped":true}""" + "\n"))
        assertEquals(false, KernelCoordinator.parseStopped("stopping\n{\"stopped\":false}"))
        assertNull(KernelCoordinator.parseStopped(""))
        assertNull(KernelCoordinator.parseStopped("nothing"))
    }

    @Test
    fun `the port VERSION file and release ordering`() {
        val file = PortVersion.parse("# written by the installer\nrelease=3.0.68-termux.2\ntag=\"v3.0.68-termux.2\"\ncline=3.0.68\nbun=1.4.0-canary.1\n")
        assertEquals("3.0.68-termux.2", file.release)
        assertEquals("v3.0.68-termux.2", file.tag)
        assertEquals("3.0.68", file.cline)
        assertEquals("3.0.68-termux.2", PortVersion.parse("tag=v3.0.68-termux.2\n").release)

        assertTrue(PortRelease.satisfies("3.0.68-termux.2", "3.0.68-termux.2"))
        assertTrue(PortRelease.satisfies("3.0.68-termux.3", "3.0.68-termux.2"))
        assertTrue(PortRelease.satisfies("3.0.69-termux.1", "3.0.68-termux.2"))
        assertTrue(PortRelease.satisfies("v3.1.0-termux.1", "3.0.68-termux.2"))
        assertFalse(PortRelease.satisfies("3.0.68-termux.1", "3.0.68-termux.2"))
        assertFalse(PortRelease.satisfies("3.0.67-termux.9", "3.0.68-termux.2"))
        assertFalse(PortRelease.satisfies("3.0.68", "3.0.68-termux.2"))
        assertFalse(PortRelease.satisfies(null, "3.0.68-termux.2"))
        assertFalse(PortRelease.satisfies("3.0.68-termux.2", null))
        assertTrue(PortRelease.parse("3.0.68-termux.10")!! > PortRelease.parse("3.0.68-termux.9")!!)
        assertEquals("3.0.68-termux.2", PortRelease.parse(" v3.0.68-termux.2 ").toString())
    }

    @Test
    fun `termux versions parse like Termux names them`() {
        assertTrue(TermuxPresence.Installed(118, "0.118.3").supported)
        assertTrue(TermuxPresence.Installed(109, "0.109").supported)
        assertFalse(TermuxPresence.Installed(101, "0.101.0").supported)
        assertTrue("a future 1.x counts as new", TermuxPresence.Installed(1, "1.0.0").supported)
        assertTrue("versionCode alone is enough", TermuxPresence.Installed(118, "unknown").supported)
        assertNull(TermuxPresence.parsedMinor("garbage"))
        assertEquals(118, TermuxPresence.parsedMinor("v0.118.3"))
    }

    @Test
    fun `uptime formats like upstream formatUptime`() {
        assertEquals("9s", formatUptime(9_400))
        assertEquals("4m 12s", formatUptime(252_000))
        assertEquals("2h 14m", formatUptime(8_040_000))
        assertEquals("1d 2h", formatUptime(93_600_000))
        assertEquals("0s", formatUptime(-5))
    }
}
