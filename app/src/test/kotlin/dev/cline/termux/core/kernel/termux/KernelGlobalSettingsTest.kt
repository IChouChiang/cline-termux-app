package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 1 seam S1: the global settings the app reads and the merge write it sends. */
class KernelGlobalSettingsTest {
    private fun result(stdout: String = "", stderr: String = "", exitCode: Int = 0, err: Int = TermuxResult.ERR_NONE) =
        TermuxResult(stdout = stdout, stderr = stderr, exitCode = exitCode, err = err, errmsg = null)

    @Test
    fun `a missing toolAutoApprove key means Auto-approve all, as in the terminal`() {
        val settings = KernelGlobalSettings.parse("""{"telemetryOptOut":true}""")!!
        assertNull(settings.toolAutoApprove)
        assertTrue(settings.autoApproveAll)
    }

    @Test
    fun `an explicit value is read as written`() {
        assertFalse(KernelGlobalSettings.parse("""{"toolAutoApprove":false}""")!!.autoApproveAll)
        assertTrue(KernelGlobalSettings.parse("""{"toolAutoApprove":true}""")!!.autoApproveAll)
        // A non-boolean value is treated like a missing key.
        assertTrue(KernelGlobalSettings.parse("""{"toolAutoApprove":"no"}""")!!.autoApproveAll)
    }

    @Test
    fun `a boolean written as a string is missing, as in the kernel's schema`() {
        val settings = KernelGlobalSettings.parse("""{"toolAutoApprove":"false","compactionEnabled":"false"}""")!!
        assertNull(settings.toolAutoApprove)
        assertTrue(settings.autoApproveAll)
        assertNull(settings.compactionEnabled)
        assertEquals(KernelGlobalSettings.COMPACTION_AGENTIC, settings.compactionMode)
        assertNull(KernelGlobalSettings.parse("""{"toolAutoApprove":"true"}""")!!.toolAutoApprove)
    }

    @Test
    fun `compaction follows the kernel's own reading`() {
        assertEquals(KernelGlobalSettings.COMPACTION_AGENTIC, KernelGlobalSettings.parse("{}")!!.compactionMode)
        assertEquals(KernelGlobalSettings.COMPACTION_BASIC, KernelGlobalSettings.parse("""{"compactionStrategy":"basic"}""")!!.compactionMode)
        assertEquals(KernelGlobalSettings.COMPACTION_AGENTIC, KernelGlobalSettings.parse("""{"compactionStrategy":"weird"}""")!!.compactionMode)
        assertEquals(KernelGlobalSettings.COMPACTION_OFF, KernelGlobalSettings.parse("""{"compactionEnabled":false,"compactionStrategy":"basic"}""")!!.compactionMode)
    }

    @Test
    fun `unparseable content and non-objects are unknown`() {
        assertNull(KernelGlobalSettings.parse("{not json"))
        assertNull(KernelGlobalSettings.parse("[1,2]"))
        assertNull(KernelGlobalSettings.parse(""))
    }

    @Test
    fun `readOrNull - ok parses, a missing file is the default, anything else is unknown`() {
        assertEquals(false, KernelGlobalSettings.readOrNull(result("""{"toolAutoApprove":false}"""))!!.toolAutoApprove)
        val missing = result(stderr = "cat: /data/data/com.termux/files/home/.cline/data/settings/global-settings.json: No such file or directory", exitCode = 1)
        assertEquals(KernelGlobalSettings.DEFAULT, KernelGlobalSettings.readOrNull(missing))
        assertTrue(KernelGlobalSettings.readOrNull(missing)!!.autoApproveAll)
        assertNull(KernelGlobalSettings.readOrNull(result(stderr = "Permission denied", exitCode = 1)))
        assertNull(KernelGlobalSettings.readOrNull(result(err = 2)))
        assertNull(KernelGlobalSettings.readOrNull(result("{broken")))
    }

    @Test
    fun `a patch carries only the keys it changes`() {
        assertEquals("""{"toolAutoApprove":true}""", KernelGlobalSettingsPatch(toolAutoApprove = true).toJson())
        assertEquals("{}", KernelGlobalSettingsPatch().toJson())
        val all = Json.parseToJsonElement(KernelGlobalSettingsPatch(false, "basic", true).toJson()).jsonObject
        assertEquals(setOf("toolAutoApprove", "compactionStrategy", "compactionEnabled"), all.keys)
    }

    @Test
    fun `the write runs the device Bun with the patch on stdin and never a shell`() {
        val command = KernelGlobalSettingsCommands.write(KernelGlobalSettingsPatch(toolAutoApprove = false))
        assertEquals("${TermuxPaths.BIN}/bun-ffi", command.path)
        assertEquals("-e", command.args.first())
        assertEquals("""{"toolAutoApprove":false}""", command.stdin)
        assertEquals(KernelGlobalSettingsCommands.LABEL_WRITE, command.label)
        val script = command.args[1]
        assertTrue(script.contains(TermuxPaths.GLOBAL_SETTINGS_JSON))
        assertTrue("an unparseable file is left alone", script.contains("process.exit(${KernelGlobalSettingsCommands.EXIT_UNREADABLE})"))
        assertTrue("written through a temp file and a rename", script.contains("renameSync"))
        assertFalse("no shell expansion can reach the script", script.contains("$"))
        assertTrue(Json.parseToJsonElement(command.stdin!!) is JsonObject)
    }
}
