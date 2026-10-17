package dev.cline.termux.data.schedules

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The routine prompts are verbatim copies of desktop routine-templates.ts. */
class RoutineTemplatesTest {
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `prompts are byte-identical to the desktop`() {
        assertEquals("bf1e0d81c21ccda1df088ec1f6d958510d1d44c76874c188ebb46d4cd70ecec1", sha256(FIND_CRITICAL_BUGS_PROMPT))
        assertEquals("1b51dd91955b137c78c3c66cb22b5596b377fab7bc1012887848e77afdbd59b1", sha256(SECURITY_SCAN_PROMPT))
        assertEquals("ee7c5954cf1e5d75550b44dca409bd69fc06825e2f1a8b47b3abd2c23a4b5fcd", sha256(DAILY_DIGEST_PROMPT))
        assertEquals("b063af5f5b449752a0971b487d1aaf55a1fb34aceaffbc89707f18fd428f4379", sha256(UPDATE_DOCS_PROMPT))
    }

    @Test fun `templates keep the desktop order and timing`() {
        assertEquals(listOf("find-critical-bugs", "security-scan", "daily-digest", "update-docs"), ROUTINE_TEMPLATES.map { it.id })
        assertEquals("0 8 * * MON", ROUTINE_TEMPLATES[1].let { buildCronPattern(it.scheduleDays, it.scheduleHour, it.scheduleMinute) })
        assertEquals("0 17 * * *", ROUTINE_TEMPLATES[2].let { buildCronPattern(it.scheduleDays, it.scheduleHour, it.scheduleMinute) })
    }

    @Test fun `suggested hides templates whose schedule name already exists`() {
        assertEquals(4, suggestedTemplates(emptyList()).size)
        assertEquals(listOf("find-critical-bugs", "update-docs"), suggestedTemplates(listOf(" security SCAN ", "Daily change digest")).map { it.id })
    }

    @Test fun `a template seeds the form`() {
        val form = ROUTINE_TEMPLATES[3].toForm()
        assertEquals("Update docs", form.name)
        assertEquals(UPDATE_DOCS_PROMPT, form.prompt)
        assertEquals(ScheduleType.Weekly, form.scheduleType)
        assertEquals(listOf("FRI"), form.scheduleDays)
        assertEquals("10", form.scheduleHour)
    }
}
