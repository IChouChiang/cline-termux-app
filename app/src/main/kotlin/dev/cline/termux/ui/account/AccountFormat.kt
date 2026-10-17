package dev.cline.termux.ui.account

import dev.cline.termux.data.chat.firstGrapheme
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Account formatters (customize-and-rest.md §0.7, §5.5), ported from `account-view.tsx`:
 * `formatCreditBalance` (`Intl.NumberFormat("en-US")` with exactly [decimals] fraction digits
 * of `value / 1_000_000`), `formatDate` (`MMM d, yyyy`) and `formatTime` (`h:mm a`, `HH:mm` on
 * the user's 24-hour clock), both
 * `en-US` as the desktop hard-codes them: credits and dollar amounts are technical units shared
 * with app.cline.bot (DS §8.2 item 4). Token counts use the device locale (DS §8.2 item 5).
 */
object AccountFormat {
    private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TIME_24H = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** `formatCreditBalance(value, decimals)`: micro-credits to credits, en-US grouping, half away from zero. */
    fun creditBalance(value: Double, decimals: Int = 2): String {
        val format = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = true
        }
        return format.format(value / 1_000_000.0)
    }

    /** `toLocaleString()` of a token count, in the device locale. */
    fun tokens(value: Long, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(value)

    /**
     * The quantity that picks the plural form for a token count (DS §8.2.2). Counts beyond the
     * `Int` range clamp to its bounds, which every language reads as its "other" form.
     */
    fun pluralQuantity(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /**
     * The quantity that picks the plural form for a formatted credit amount such as "1.50",
     * "+20.00" or "1,234.00". CLDR gives "one" only to a whole number written without fraction
     * digits ("1 credit", but "1.00 credits"), so an amount that carries a fraction always reads
     * as [FRACTION_QUANTITY], a quantity the copy language reads as "other".
     */
    fun creditsPluralQuantity(formatted: String): Int {
        val digits = formatted.trim().trimStart('+', '-').replace(",", "")
        if (digits.contains('.')) return FRACTION_QUANTITY
        return digits.toLongOrNull()?.let(::pluralQuantity) ?: FRACTION_QUANTITY
    }

    private const val FRACTION_QUANTITY = 2

    /** `formatDate`: `MMM d, yyyy`; null when [iso] is missing or not a date. */
    fun date(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? = parse(iso, zone)?.format(DATE)

    /**
     * `formatTime`: `h:mm a`, or en-US's 24-hour `HH:mm` when the user turned on "Use 24-hour
     * format" ([is24Hour]; the desktop's hard-coded clock ignored it); null when [iso] is missing
     * or not a date.
     */
    fun time(iso: String?, zone: ZoneId = ZoneId.systemDefault(), is24Hour: Boolean = false): String? =
        parse(iso, zone)?.format(if (is24Hour) TIME_24H else TIME)

    /**
     * The organization avatar letter: the first grapheme cluster of the trimmed [name],
     * upper-cased in [locale], "?" when there is none (DS §8.2.7: never a lone surrogate or a
     * bare base letter without its combining marks).
     */
    fun initial(name: String?, locale: Locale = Locale.getDefault()): String =
        firstGrapheme(name?.trim().orEmpty()).uppercase(locale).ifEmpty { "?" }

    /** `new Date(value)` for the shapes the account API returns (ISO instant, offset, local date-time, date). */
    internal fun parse(iso: String?, zone: ZoneId): ZonedDateTime? {
        val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(text).atZone(zone) }
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
        runCatching { return LocalDateTime.parse(text).atZone(zone) }
        runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
        text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        return null
    }
}
