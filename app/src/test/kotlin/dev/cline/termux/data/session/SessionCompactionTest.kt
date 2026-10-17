package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.termux.KernelGlobalSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** G1 + A11 row 2: `sessionConfig.compaction` from the shared settings, and each Settings choice's write. */
class SessionCompactionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun settings(text: String) = requireNotNull(KernelGlobalSettings.parse(text))

    @Test fun `unknown settings or nothing set enables compaction with core's default strategy`() {
        assertEquals(json("""{"enabled":true}"""), compactionConfig(null))
        assertEquals(json("""{"enabled":true}"""), compactionConfig(settings("{}")))
        assertEquals(json("""{"enabled":true}"""), compactionConfig(settings("""{"telemetryOptOut":true}""")))
    }

    @Test fun `a stored strategy is sent with enabled true`() {
        assertEquals(json("""{"enabled":true,"strategy":"agentic"}"""), compactionConfig(settings("""{"compactionStrategy":"agentic"}""")))
        assertEquals(json("""{"enabled":true,"strategy":"basic"}"""), compactionConfig(settings("""{"compactionStrategy":"basic","compactionEnabled":true}""")))
    }

    @Test fun `compactionEnabled false turns it off whatever the strategy`() {
        assertEquals(json("""{"enabled":false}"""), compactionConfig(settings("""{"compactionEnabled":false}""")))
        assertEquals(json("""{"enabled":false}"""), compactionConfig(settings("""{"compactionEnabled":false,"compactionStrategy":"basic"}""")))
    }

    @Test fun `an unknown strategy reads as agentic like the kernel`() {
        assertEquals(json("""{"enabled":true,"strategy":"agentic"}"""), compactionConfig(settings("""{"compactionStrategy":"weird"}""")))
    }

    @Test fun `each Settings choice writes the terminal's keys and Off keeps the strategy`() {
        assertEquals("""{"compactionEnabled":false}""", CompactionMode.Off.patch().toJson())
        assertEquals("""{"compactionStrategy":"agentic","compactionEnabled":true}""", CompactionMode.Llm.patch().toJson())
        assertEquals("""{"compactionStrategy":"basic","compactionEnabled":true}""", CompactionMode.Truncation.patch().toJson())
    }

    @Test fun `the row reads the stored mode`() {
        assertEquals(CompactionMode.Llm, CompactionMode.of(KernelGlobalSettings.DEFAULT))
        assertEquals(CompactionMode.Llm, CompactionMode.of(settings("""{"compactionStrategy":"agentic"}""")))
        assertEquals(CompactionMode.Truncation, CompactionMode.of(settings("""{"compactionStrategy":"basic"}""")))
        assertEquals(CompactionMode.Off, CompactionMode.of(settings("""{"compactionEnabled":false,"compactionStrategy":"basic"}""")))
    }
}
