package dev.cline.termux.data.schedules

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.ui.approval.formatApprovalTimestamp
import dev.cline.termux.ui.chat.rows.formatMessageTime
import dev.cline.termux.ui.schedules.SchedulePreviewData
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every time-of-day site through the platform's CLDR lookup ([ClockPatterns.Platform], ICU under
 * Robolectric): en-US and zh-CN with the 24-hour switch on and off, at midnight and noon. The
 * ports' own default (the locale's clock) is pinned by their plain JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockFormatPlatformTest {
    private val us: Locale = Locale.US
    private val zhCn: Locale = Locale.SIMPLIFIED_CHINESE
    private val utc: ZoneId = ZoneOffset.UTC
    private val words = SchedulePreviewData.words

    /** CLDR uses narrow and regular no-break spaces around the am/pm marker. */
    private fun nb(text: String?): String? = text?.replace(' ', ' ')?.replace(' ', ' ')
    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun `the platform patterns for the hour skeletons`() {
        assertEquals("HH:mm", ClockFormat.timePattern(us, is24Hour = true))
        assertEquals("h:mm a", nb(ClockFormat.timePattern(us, is24Hour = false)))
        assertEquals("HH:mm", ClockFormat.timePattern(zhCn, is24Hour = true))
        assertEquals("ah:mm", ClockFormat.timePattern(zhCn, is24Hour = false))
    }

    @Test fun `schedule times follow the switch in en-US and zh-CN`() {
        assertEquals("00:00", formatScheduleTime("0", "0", us, is24Hour = true))
        assertEquals("12:00", formatScheduleTime("12", "0", us, is24Hour = true))
        assertEquals("12:00 AM", nb(formatScheduleTime("0", "0", us, is24Hour = false)))
        assertEquals("12:00 PM", nb(formatScheduleTime("12", "0", us, is24Hour = false)))
        assertEquals("00:00", formatScheduleTime("0", "0", zhCn, is24Hour = true))
        assertEquals("12:00", formatScheduleTime("12", "0", zhCn, is24Hour = true))
        assertEquals("上午12:00", formatScheduleTime("0", "0", zhCn, is24Hour = false))
        assertEquals("下午12:00", formatScheduleTime("12", "0", zhCn, is24Hour = false))
    }

    @Test fun `schedule labels keep their wording and take the device clock`() {
        assertEquals("Daily · 17:30", formatScheduleTrigger(ScheduleFacts("30 17 * * *"), words, utc, us, is24Hour = true))
        assertEquals("Daily · 5:30 PM", nb(formatScheduleTrigger(ScheduleFacts("30 17 * * *"), words, utc, us, is24Hour = false)))
        assertEquals("Weekdays · 下午5:30 · Asia/Shanghai", formatScheduleTrigger(ScheduleFacts("30 17 * * MON-FRI", timezone = "Asia/Shanghai"), words, utc, zhCn, is24Hour = false))
        val once = ScheduleFacts(ONE_TIME_SCHEDULE_CRON_PATTERN, oneTimeRunAt = at("2026-09-13T00:00:00"))
        assertEquals("Once · Sep 13, 2026, 00:00", nb(formatScheduleTrigger(once, words, utc, us, is24Hour = true)))
        assertEquals("Once · Sep 13, 2026, 12:00 AM", nb(formatScheduleTrigger(once, words, utc, us, is24Hour = false)))
    }

    @Test fun `schedule dates keep the locale's date part`() {
        val noon = at("2026-09-13T12:00:00")
        assertEquals("Sep 13, 2026, 12:00", nb(formatDateTime(noon, utc, us, is24Hour = true)))
        assertEquals("Sep 13, 2026, 12:00 PM", nb(formatDateTime(noon, utc, us, is24Hour = false)))
        assertEquals("2026年9月13日 12:00", formatDateTime(noon, utc, zhCn, is24Hour = true))
        assertEquals("2026年9月13日 下午12:00", formatDateTime(noon, utc, zhCn, is24Hour = false))
        assertEquals("completed at 2026年9月13日 上午12:00", formatExecutionResult(ExecutionFacts("completed", endedAt = at("2026-09-13T00:00:00")), words, utc, zhCn, false))
        assertEquals("Sep 13, 2026, 00:00", nb(formatExecutionTimestamp(ExecutionFacts(triggeredAt = at("2026-09-13T00:00:00")), utc, us, true)))
    }

    @Test fun `approval and message times follow the switch`() {
        val evening = 1_791_064_690_209 // 2026-10-03T21:58:10Z
        assertEquals("10/3/26, 21:58", nb(formatApprovalTimestamp(evening, utc, us, is24Hour = true)))
        assertEquals("10/3/26, 9:58 PM", nb(formatApprovalTimestamp(evening, utc, us, is24Hour = false)))
        assertEquals("2026/10/3 21:58", formatApprovalTimestamp(evening, utc, zhCn, is24Hour = true))
        assertEquals("2026/10/3 下午9:58", formatApprovalTimestamp(evening, utc, zhCn, is24Hour = false))
        val midnight = at("2026-10-03T00:00:00")
        assertEquals("00:00", formatMessageTime(midnight, utc, us, is24Hour = true))
        assertEquals("12:00 AM", nb(formatMessageTime(midnight, utc, us, is24Hour = false)))
        assertEquals("上午12:00", formatMessageTime(midnight, utc, zhCn, is24Hour = false))
        assertEquals("00:00", formatMessageTime(midnight, utc, zhCn, is24Hour = true))
    }

    @Test fun `the setting is read from Settings, the locale's clock until set`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertTrue(is24HourClock(context))
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        assertFalse(is24HourClock(context))
    }
}
