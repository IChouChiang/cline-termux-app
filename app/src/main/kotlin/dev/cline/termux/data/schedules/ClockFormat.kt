package dev.cline.termux.data.schedules

import android.content.Context
import android.text.format.DateFormat
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * The device clock for every time of day the app shows. java.time's `ofLocalizedTime` and
 * `ofLocalizedDateTime` follow the locale's own clock and ignore Android's "Use 24-hour format"
 * setting, so an en-US phone set to 24 hours read "10:42 PM" and a zh-CN phone set to 12 hours
 * read "22:42".
 *
 * When [is24Hour] agrees with the locale's clock, the formatters are exactly the localized SHORT
 * styles the app used before. When it does not, the time comes from the locale's CLDR pattern for
 * the `Hm` or `hm` skeleton ([ClockPatterns]), and a date-time keeps the locale's own date-time
 * pattern with only its time part replaced, so the date, its order and its separators stay as
 * they were. Everything here is plain JVM code over a [ClockPatterns]; only
 * [ClockPatterns.Platform] and [is24HourClock] touch Android. The date and time ports keep the
 * locale their caller passes; nothing here picks or pins one.
 */
object ClockFormat {
    /** The CLDR skeleton of a 12-hour hour-minute time ("9:05 AM", "上午9:05"). */
    const val SKELETON_12H = "hm"

    /** The CLDR skeleton of a 24-hour hour-minute time ("09:05", "9:05"). */
    const val SKELETON_24H = "Hm"

    /** The short time of day ("9:05 AM" or "09:05" in en-US). */
    fun timeFormatter(locale: Locale, is24Hour: Boolean, patterns: ClockPatterns = ClockPatterns.Platform): DateTimeFormatter {
        val localized = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        if (localeDefaultIs24Hour(locale) == is24Hour) return localized
        return ofPatternOr(timePattern(locale, is24Hour, patterns), locale, localized)
    }

    /** [dateStyle] date plus the short time of day ("Sep 13, 2026, 2:05 PM" or "Sep 13, 2026, 14:05"). */
    fun dateTimeFormatter(
        dateStyle: FormatStyle,
        locale: Locale,
        is24Hour: Boolean,
        patterns: ClockPatterns = ClockPatterns.Platform,
    ): DateTimeFormatter {
        val localized = DateTimeFormatter.ofLocalizedDateTime(dateStyle, FormatStyle.SHORT).withLocale(locale)
        if (localeDefaultIs24Hour(locale) == is24Hour) return localized
        val combined = localizedPattern(dateStyle, FormatStyle.SHORT, locale)
        val localeTime = localizedPattern(null, FormatStyle.SHORT, locale)
        val at = combined.lastIndexOf(localeTime)
        val pattern = if (at >= 0) {
            combined.replaceRange(at, at + localeTime.length, timePattern(locale, is24Hour, patterns))
        } else {
            javaTimePattern(patterns.best(locale, dateSkeleton(dateStyle) + if (is24Hour) SKELETON_24H else SKELETON_12H))
        }
        return ofPatternOr(pattern, locale, localized)
    }

    /** [time] (a `LocalTime`, `ZonedDateTime`, …) as a short time of day. */
    fun formatTime(time: TemporalAccessor, locale: Locale, is24Hour: Boolean, patterns: ClockPatterns = ClockPatterns.Platform): String =
        timeFormatter(locale, is24Hour, patterns).format(time)

    /** [dateTime] (a `ZonedDateTime`, `LocalDateTime`, …) as a [dateStyle] date and a short time of day. */
    fun formatDateTime(
        dateTime: TemporalAccessor,
        dateStyle: FormatStyle,
        locale: Locale,
        is24Hour: Boolean,
        patterns: ClockPatterns = ClockPatterns.Platform,
    ): String = dateTimeFormatter(dateStyle, locale, is24Hour, patterns).format(dateTime)

    /**
     * Whether [locale]'s own short time uses a 24-hour clock: what the formatters printed before
     * they followed the setting, and the default of every port that takes the flag.
     */
    fun localeDefaultIs24Hour(locale: Locale): Boolean = usesHour24(localizedPattern(null, FormatStyle.SHORT, locale))

    /** The java.time pattern of [locale]'s hour-minute time on the requested clock. */
    fun timePattern(locale: Locale, is24Hour: Boolean, patterns: ClockPatterns = ClockPatterns.Platform): String =
        javaTimePattern(patterns.best(locale, if (is24Hour) SKELETON_24H else SKELETON_12H))

    /** True when [pattern] has an unquoted 24-hour field (`H` 0-23 or `k` 1-24). */
    internal fun usesHour24(pattern: String): Boolean {
        var quoted = false
        for (ch in pattern) {
            if (ch == '\'') quoted = !quoted else if (!quoted && (ch == 'H' || ch == 'k')) return true
        }
        return false
    }

    /**
     * ICU patterns may carry day-period letters java.time cannot read (`b` am/pm/noon/midnight
     * everywhere, `B` flexible periods such as "in the afternoon" before API 34); both stand for
     * the 12-hour marker here, so a run of them becomes one `a`. Quoted literals are left alone.
     */
    fun javaTimePattern(icuPattern: String): String {
        val out = StringBuilder(icuPattern.length)
        var quoted = false
        var lastWasPeriod = false
        for (ch in icuPattern) {
            if (ch == '\'') quoted = !quoted
            if (!quoted && (ch == 'b' || ch == 'B')) {
                if (!lastWasPeriod) out.append('a')
                lastWasPeriod = true
                continue
            }
            lastWasPeriod = false
            out.append(ch)
        }
        return out.toString()
    }

    private fun dateSkeleton(style: FormatStyle): String = when (style) {
        FormatStyle.SHORT -> "yMd"
        FormatStyle.MEDIUM -> "yMMMd"
        FormatStyle.LONG -> "yMMMMd"
        FormatStyle.FULL -> "yMMMMEEEEd"
    }

    private fun localizedPattern(date: FormatStyle?, time: FormatStyle?, locale: Locale): String =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, IsoChronology.INSTANCE, locale)

    /** A pattern java.time cannot read (no sanitized CLDR one should) keeps the locale's style. */
    private fun ofPatternOr(pattern: String, locale: Locale, fallback: DateTimeFormatter): DateTimeFormatter =
        runCatching { DateTimeFormatter.ofPattern(pattern, locale) }.getOrElse { fallback }
}

/** Maps a CLDR skeleton ("Hm", "hm", "yMMMdHm") to [ClockFormat]'s pattern for a locale. */
fun interface ClockPatterns {
    fun best(locale: Locale, skeleton: String): String

    companion object {
        /** The platform's CLDR data (ICU), through `android.text.format.DateFormat.getBestDateTimePattern`. */
        val Platform: ClockPatterns = ClockPatterns { locale, skeleton -> DateFormat.getBestDateTimePattern(locale, skeleton) }
    }
}

/**
 * The user's "Use 24-hour format" setting (the locale's clock until they choose). Composables
 * read it through `rememberIs24HourClock` and pass the Boolean down; ViewModels read it per
 * projection through a provider, like their locale.
 */
fun is24HourClock(context: Context): Boolean = DateFormat.is24HourFormat(context)
