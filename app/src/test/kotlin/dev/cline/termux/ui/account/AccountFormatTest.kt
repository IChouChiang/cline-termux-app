package dev.cline.termux.ui.account

import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.data.account.PaymentTransaction
import dev.cline.termux.data.account.UsageTransaction
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.fakes.FakeAccountSource
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFormatTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `creditBalance divides micro-credits and groups en-US`() {
        assertEquals("12.50", AccountFormat.creditBalance(12_500_000.0))
        assertEquals("1,234,567.89", AccountFormat.creditBalance(1_234_567_890_000.0))
        assertEquals("0.00", AccountFormat.creditBalance(0.0))
        assertEquals("0.0123", AccountFormat.creditBalance(12_345.0, decimals = 4))
    }

    @Test
    fun `creditBalance rounds half up`() {
        assertEquals("0.01", AccountFormat.creditBalance(5_000.0))
        assertEquals("-0.01", AccountFormat.creditBalance(-5_000.0))
    }

    @Test
    fun `creditBalance keeps exactly the requested decimals`() {
        assertEquals("1,234.57", AccountFormat.creditBalance(1_234_567_890.0))
        assertEquals("12.3", AccountFormat.creditBalance(12_300_000.0, decimals = 1))
        assertEquals("12.30", AccountFormat.creditBalance(12_300_000.0))
    }

    @Test
    fun `creditBalance rounds ties away from zero like Intl, not to even`() {
        // Intl.NumberFormat's default roundingMode is "halfExpand"; HALF_EVEN would give 0.12 and 2.
        assertEquals("0.13", AccountFormat.creditBalance(125_000.0))
        assertEquals("-0.13", AccountFormat.creditBalance(-125_000.0))
        assertEquals("3", AccountFormat.creditBalance(2_500_000.0, decimals = 0))
        assertEquals("0.38", AccountFormat.creditBalance(375_000.0))
    }

    @Test
    fun `organization initial is the first grapheme, upper-cased`() {
        assertEquals("😀", AccountFormat.initial("😀 Team", Locale.US)) // one surrogate pair
        assertEquals("𠀋", AccountFormat.initial("𠀋組", Locale.US)) // CJK Ext. B, one surrogate pair
        assertEquals("É", AccountFormat.initial("école", Locale.US)) // combining acute kept
        assertEquals("A", AccountFormat.initial("acme", Locale.US))
        assertEquals("B", AccountFormat.initial("  beta labs", Locale.US))
        assertEquals("İ", AccountFormat.initial("istanbul", Locale.forLanguageTag("tr")))
        assertEquals("?", AccountFormat.initial(null, Locale.US))
        assertEquals("?", AccountFormat.initial("", Locale.US))
        assertEquals("?", AccountFormat.initial("   ", Locale.US))
    }

    @Test
    fun `tokens follow the given locale`() {
        assertEquals("1,234,567", AccountFormat.tokens(1_234_567, Locale.US))
        assertEquals("1.234.567", AccountFormat.tokens(1_234_567, Locale.GERMANY))
    }

    @Test
    fun `token plural quantity keeps the count and clamps past Int`() {
        assertEquals(1, AccountFormat.pluralQuantity(1))
        assertEquals(0, AccountFormat.pluralQuantity(0))
        assertEquals(12_345, AccountFormat.pluralQuantity(12_345))
        assertEquals(Int.MAX_VALUE, AccountFormat.pluralQuantity(Long.MAX_VALUE))
    }

    @Test
    fun `credit plural quantity reads one only for a whole 1 without fraction digits`() {
        assertEquals(1, AccountFormat.creditsPluralQuantity("1"))
        assertEquals(1, AccountFormat.creditsPluralQuantity("+1"))
        assertEquals(0, AccountFormat.creditsPluralQuantity("0"))
        assertEquals(1_234, AccountFormat.creditsPluralQuantity("1,234"))
        assertTrue(AccountFormat.creditsPluralQuantity("1.00") != 1)
        assertTrue(AccountFormat.creditsPluralQuantity("+20.00") != 1)
        assertTrue(AccountFormat.creditsPluralQuantity(AccountFormat.creditBalance(1_000_000.0)) != 1)
        assertTrue(AccountFormat.creditsPluralQuantity("") != 1)
    }

    @Test
    fun `usage times keep en-US and follow the 24-hour switch`() {
        assertEquals("00:00", AccountFormat.time("2025-03-04T00:00:00Z", utc, is24Hour = true))
        assertEquals("12:00", AccountFormat.time("2025-03-04T12:00:00Z", utc, is24Hour = true))
        assertEquals("17:15", AccountFormat.time("2025-03-04T17:15:00", utc, is24Hour = true))
        assertEquals("12:00 AM", AccountFormat.time("2025-03-04T00:00:00Z", utc, is24Hour = false))
        assertEquals("12:00 PM", AccountFormat.time("2025-03-04T12:00:00Z", utc, is24Hour = false))
    }

    @Test
    fun `dates and times parse the shapes the API returns`() {
        assertEquals("Mar 4, 2025", AccountFormat.date("2025-03-04T10:00:00Z", utc))
        assertEquals("10:00 AM", AccountFormat.time("2025-03-04T10:00:00Z", utc))
        assertEquals("Mar 5, 2025", AccountFormat.date("2025-03-04T23:30:00-02:00", utc))
        assertEquals("1:30 AM", AccountFormat.time("2025-03-04T23:30:00-02:00", utc))
        assertEquals("Mar 4, 2025", AccountFormat.date("2025-03-04", utc))
        assertEquals("Jan 1, 1970", AccountFormat.date("0", utc))
        assertEquals("5:15 PM", AccountFormat.time("2025-03-04T17:15:00", utc))
        assertEquals("Sep 13, 2026", AccountFormat.date("2026-09-13T14:05:00Z", utc))
        assertEquals("2:05 PM", AccountFormat.time("2026-09-13T14:05:00Z", utc))
        assertNull(AccountFormat.date(null, utc))
        assertNull(AccountFormat.date("  ", utc))
        assertNull(AccountFormat.date("yesterday", utc))
    }

    @Test
    fun `overview projects balances, organisations and member since`() {
        val acme = FakeAccountSource.org("o1", "Acme", active = true, roles = listOf("admin", "member"))
        val ui = AccountProjection.overview(
            FakeAccountSource.overview(balance = 2_000_000.0, organizations = listOf(acme), organizationBalance = 7_250_000.0),
            utc,
        )
        assertEquals("Ada", ui.name)
        assertEquals("A", ui.initial)
        assertEquals("Mar 4, 2025", ui.memberSince)
        assertEquals("Acme", ui.organizationName)
        assertEquals("7.25", ui.balance)
        assertEquals(7.25, ui.balanceCredits!!, 0.0001)
        assertEquals("2.00", ui.personalCredits)
        assertEquals(listOf(null, "o1"), ui.accounts.map { it.id })
        assertFalse(ui.accounts[0].active)
        assertTrue(ui.accounts[1].active)
        assertEquals("", ui.accounts[0].switchKey)
        assertEquals("o1", ui.accounts[1].switchKey)
    }

    @Test
    fun `personal overview has no organisation line and a missing balance hides the card`() {
        val ui = AccountProjection.overview(FakeAccountSource.overview(balance = null), utc)
        assertNull(ui.organizationName)
        assertNull(ui.balance)
        assertNull(ui.personalCredits)
        assertTrue(ui.accounts.single().active)
        assertEquals("ada@example.com", ui.accounts.single().subtitle)
    }

    @Test
    fun `usage and payments rows`() {
        val usage = AccountProjection.usage(
            listOf(
                UsageTransaction("", "claude-sonnet", " ", 1200, 1_500_000.0, "2025-03-04T10:00:00Z"),
                UsageTransaction("t2", "gpt", "openai", 5, 0.0, null),
            ),
            utc,
        )
        assertEquals("usage-0", usage[0].id)
        assertNull(usage[0].provider)
        assertEquals("1.50", usage[0].credits)
        assertEquals("Mar 4, 2025", usage[0].date)
        assertEquals("openai", usage[1].provider)
        assertNull(usage[1].date)

        val payments = AccountProjection.payments(listOf(PaymentTransaction("not a date", 1999, 20_000_000.0, null)), utc)
        assertEquals("not a date", payments.single().date)
        assertEquals("$19.99", payments.single().amount)
        assertEquals("20.00", payments.single().credits)
    }

    @Test
    fun `tab wire round trip`() {
        AccountTab.entries.forEach { assertEquals(it, AccountTab.fromWire(it.wire)) }
        assertEquals(AccountTab.Overview, AccountTab.fromWire(null))
        assertEquals(AccountTab.Overview, AccountTab.fromWire("bogus"))
    }

    @Test
    fun `failures log reasons without text`() {
        assertEquals("hub:command_failed", RepoFailure.Hub("command_failed", "secret text").logReason)
        assertEquals("helper_down:StartTimeout", RepoFailure.HelperDown(HelperFailure.StartTimeout).logReason)
        assertEquals("helper_error", RepoFailure.Helper("token=abc").logReason)
    }

    @Test
    fun `the active organisation row stays enabled and only switching or sign-out disables rows`() {
        val active = AccountRow(id = null, name = null, subtitle = null, active = true)
        val other = AccountRow(id = "o1", name = "Acme", subtitle = "Admin", active = false)

        val idle = AccountUiState()
        assertTrue(idle.rowEnabled(active))
        assertTrue(idle.rowEnabled(other))

        val switching = AccountUiState(switchingTo = "o1")
        assertTrue(switching.rowEnabled(active))
        assertFalse(switching.rowEnabled(other))

        val signingOut = AccountUiState(signingOut = true)
        assertFalse(signingOut.rowEnabled(active))
        assertFalse(signingOut.rowEnabled(other))
    }
}
