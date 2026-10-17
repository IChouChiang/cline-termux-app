package dev.cline.termux.data.schedules

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClockFormat] on the plain JVM: the platform's skeleton lookup is replaced by a fixed CLDR
 * table, so these pin the clock switch, the date-time splice and the day-period sanitizing.
 * ClockFormatPlatformTest runs the same cases through the real lookup (Robolectric).
 */
class ClockFormatTest {
    private val zhCn: Locale = Locale.SIMPLIFIED_CHINESE
    private val us: Locale = Locale.US

    /** CLDR's hm / Hm patterns for the two locales (what ICU returns for them). */
    private val table = ClockPatterns { locale, skeleton ->
        when (locale.language to skeleton) {
            "en" to ClockFormat.SKELETON_12H -> "h:mm a"
            "en" to ClockFormat.SKELETON_24H -> "HH:mm"
            "zh" to ClockFormat.SKELETON_12H -> "ah:mm"
            "zh" to ClockFormat.SKELETON_24H -> "HH:mm"
            else -> error("no pattern for $locale $skeleton")
        }
    }

    /** CLDR separates "9:00 AM" with a narrow no-break space (JDK 20+); compare with plain spaces. */
    private fun nb(text: String): String = text.replace(' ', ' ').replace(' ', ' ')

    private fun time(t: LocalTime, locale: Locale, is24Hour: Boolean) = nb(ClockFormat.formatTime(t, locale, is24Hour, table))

    private val afternoon = LocalDateTime.parse("2026-09-13T14:05:00").atZone(ZoneOffset.UTC)

    @Test fun `en-US follows the 24-hour switch at midnight and noon`() {
        assertEquals("12:00 AM", time(LocalTime.MIDNIGHT, us, is24Hour = false))
        assertEquals("12:00 PM", time(LocalTime.NOON, us, is24Hour = false))
        assertEquals("00:00", time(LocalTime.MIDNIGHT, us, is24Hour = true))
        assertEquals("12:00", time(LocalTime.NOON, us, is24Hour = true))
    }

    @Test fun `zh-CN follows the 12-hour switch at midnight and noon`() {
        assertEquals("00:00", time(LocalTime.MIDNIGHT, zhCn, is24Hour = true))
        assertEquals("12:00", time(LocalTime.NOON, zhCn, is24Hour = true))
        assertEquals("上午12:00", time(LocalTime.MIDNIGHT, zhCn, is24Hour = false))
        assertEquals("下午12:00", time(LocalTime.NOON, zhCn, is24Hour = false))
    }

    @Test fun `the locale's own clock keeps the localized short style untouched`() {
        assertFalse(ClockFormat.localeDefaultIs24Hour(us))
        assertTrue(ClockFormat.localeDefaultIs24Hour(zhCn))
        // No lookup at all when the switch matches the locale: a table that throws is never asked.
        val never = ClockPatterns { _, _ -> error("not consulted") }
        assertEquals("2:05 PM", nb(ClockFormat.formatTime(afternoon, us, is24Hour = false, patterns = never)))
        assertEquals("14:05", ClockFormat.formatTime(afternoon, zhCn, is24Hour = true, patterns = never))
        assertEquals("Sep 13, 2026, 2:05 PM", nb(ClockFormat.formatDateTime(afternoon, FormatStyle.MEDIUM, us, false, never)))
    }

    @Test fun `a date-time keeps the locale's date and swaps only the time`() {
        assertEquals("Sep 13, 2026, 14:05", nb(ClockFormat.formatDateTime(afternoon, FormatStyle.MEDIUM, us, true, table)))
        assertEquals("9/13/26, 14:05", nb(ClockFormat.formatDateTime(afternoon, FormatStyle.SHORT, us, true, table)))
        assertEquals("2026年9月13日 下午2:05", nb(ClockFormat.formatDateTime(afternoon, FormatStyle.MEDIUM, zhCn, false, table)))
        assertEquals("2026/9/13 下午2:05", nb(ClockFormat.formatDateTime(afternoon, FormatStyle.SHORT, zhCn, false, table)))
        val midnight = afternoon.withHour(0).withMinute(0)
        assertEquals("Sep 13, 2026, 00:00", nb(ClockFormat.formatDateTime(midnight, FormatStyle.MEDIUM, us, true, table)))
        assertEquals("2026年9月13日 上午12:00", nb(ClockFormat.formatDateTime(midnight, FormatStyle.MEDIUM, zhCn, false, table)))
    }

    @Test fun `a pattern java time cannot read falls back to the locale's style`() {
        val broken = ClockPatterns { _, _ -> "{" }
        assertEquals("12:00 PM", nb(ClockFormat.formatTime(LocalTime.NOON, us, is24Hour = true, patterns = broken)))
    }

    @Test fun `ICU day periods become the am-pm marker outside quotes`() {
        assertEquals("ah:mm", ClockFormat.javaTimePattern("Bh:mm"))
        assertEquals("h:mm a", ClockFormat.javaTimePattern("h:mm b"))
        assertEquals("a h:mm", ClockFormat.javaTimePattern("BBBB h:mm"))
        assertEquals("'Bb' h:mm a", ClockFormat.javaTimePattern("'Bb' h:mm a"))
        assertEquals("HH:mm", ClockFormat.javaTimePattern("HH:mm"))
    }

    @Test fun `24-hour fields are found outside quotes only`() {
        assertTrue(ClockFormat.usesHour24("HH:mm"))
        assertTrue(ClockFormat.usesHour24("k:mm"))
        assertFalse(ClockFormat.usesHour24("h:mm a"))
        assertFalse(ClockFormat.usesHour24("K:mm a 'H'"))
    }
}
