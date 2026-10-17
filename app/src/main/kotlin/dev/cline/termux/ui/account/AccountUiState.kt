package dev.cline.termux.ui.account

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.AccountOrganization
import dev.cline.termux.data.account.AccountOverview
import dev.cline.termux.data.account.AccountRepository
import dev.cline.termux.data.account.PaymentTransaction
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.account.UsageTransaction
import dev.cline.termux.data.customize.RepoFailure
import java.time.ZoneId

/** The three Account tabs (customize-and-rest.md §5.3); [wire] is the `account?tab=` spelling. */
enum class AccountTab(val wire: String) {
    Overview("overview"),
    Usage("usage"),
    Billing("billing"),
    ;

    companion object {
        fun fromWire(value: String?): AccountTab = entries.firstOrNull { it.wire == value } ?: Overview
    }
}

/** Whether the kernel holds a Cline account: unknown until the first `fetchMe` (or file check) resolves. */
enum class SignedIn { Unknown, Yes, No }

/** Why a tab could not load (§5.4 "Error" and "Older helper"). */
sealed interface AccountError {
    data class Failure(val failure: RepoFailure) : AccountError

    /** "unsupported desktop command: cline_account". */
    data object OlderHelper : AccountError
}

/** One lazily loaded part of the page (desktop `overviewLoading` / `usageLoaded` / `billingLoaded`). */
sealed interface Loadable<out T> {
    /** Never requested (Usage / Billing load on first selection). */
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Error(val error: AccountError) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

/**
 * The Overview tab as shown (§5.3, §5.5): copy that comes from resources is resolved in the
 * composable; values here are kernel data or technical-unit strings (credits, dates).
 */
@Immutable
data class AccountOverviewUi(
    /** `displayName` or `email`. */
    val name: String,
    val email: String,
    /** First grapheme of the name (may be several `Char`s: emoji, supplementary-plane Han). */
    val initial: String,
    /** `MMM d, yyyy` of `createdAt`, or null when the account has none. */
    val memberSince: String?,
    /** The active organisation's name ("<org> Balance"), or null ("Credits Balance"). */
    val organizationName: String?,
    /** `formatCreditBalance(displayedBalance)`, or null when no balance came back (card hidden, desktop). */
    val balance: String?,
    /** The displayed balance in credits (dollars), for the currency TalkBack label. */
    val balanceCredits: Double?,
    /** "Personal account: <n> credits", only while an organisation is active. */
    val personalCredits: String?,
    /** "Personal" first, then each organisation. */
    val accounts: List<AccountRow>,
    val activeOrganization: AccountOrganization?,
)

/** An Organizations row; [id] null is the personal account. */
@Immutable
data class AccountRow(
    val id: String?,
    /** The organisation name; null for the personal row ("Personal"). */
    val name: String?,
    /** Roles ("Admin, Member") or the email; null for the personal row without an email ("Personal account"). */
    val subtitle: String?,
    val active: Boolean,
) {
    val personal: Boolean get() = id == null

    /** `switchingTo` key: "" for personal (desktop `switchTargetId === ""`). */
    val switchKey: String get() = id ?: ""
}

/** A Usage row (§5.3): model, provider, tokens, credits and the date/time. */
@Immutable
data class UsageRow(
    val id: String,
    val model: String,
    val provider: String?,
    val tokens: Long,
    val credits: String,
    val date: String?,
    val time: String?,
)

/** A Billing row (§5.5): date, "$" amount, "+" credits. */
@Immutable
data class PaymentRow(val key: String, val date: String?, val amount: String, val credits: String)

/** customize-and-rest.md §5.8 `AccountUiState`, with the reduced (no helper) page's signed-in check. */
@Immutable
data class AccountUiState(
    val tab: AccountTab = AccountTab.Overview,
    val helper: HelperState = HelperState.Idle,
    val signedIn: SignedIn = SignedIn.Unknown,
    val overview: Loadable<AccountOverviewUi> = Loadable.Idle,
    val usage: Loadable<List<UsageRow>> = Loadable.Idle,
    val billing: Loadable<List<PaymentRow>> = Loadable.Idle,
    /** The org id being switched to, "" = personal, null = none. */
    val switchingTo: String? = null,
    val signingOut: Boolean = false,
    val signIn: SignInState = SignInState.Idle,
    val kernel: KernelGate = KernelGate.Busy(KernelGate.BusyKind.Connecting, null, slow = false),
    /** Pull to refresh in progress. */
    val refreshing: Boolean = false,
    /** Without the helper: signed in per `providers.json` (`hasOAuth` for `cline`). */
    val fallbackSignedIn: Loadable<Boolean> = Loadable.Idle,
) {
    /** RUN_COMMAND is unusable, so neither the helper nor sign-in can run (§5.4 "Kernel blocked"). */
    val blockedFailure: HelperFailure?
        get() {
            val reason = (helper as? HelperState.Unavailable)?.reason
            return reason?.takeIf { it in BLOCKING }
        }

    /** The helper is down for any other reason: the reduced page (§5.4 "Helper unavailable"). */
    val helperUnavailable: HelperState.Unavailable?
        get() = (helper as? HelperState.Unavailable)?.takeIf { it.reason !in BLOCKING }

    /** The tab row and the overflow menu show only when signed in (§5.3). */
    val showTabs: Boolean get() = helperUnavailable == null && blockedFailure == null && signedIn == SignedIn.Yes

    val busy: Boolean get() = signingOut

    /**
     * Whether an Organizations radio row is enabled (§5.10, DS §8). The active row stays enabled,
     * so TalkBack reads "selected" rather than "selected, disabled"; tapping it is a no-op. Only
     * a switch in progress or sign-out disables the rows.
     */
    fun rowEnabled(row: AccountRow): Boolean = (row.active || switchingTo == null) && !busy

    companion object {
        val BLOCKING = setOf(HelperFailure.TermuxMissing, HelperFailure.PermissionMissing, HelperFailure.ExternalAppsBlocked)
    }
}

/** One-off effects (snackbars, §5.5 "Snackbars"). */
sealed interface AccountEvent {
    data object SignedOut : AccountEvent
    data class Switched(val name: String?) : AccountEvent
    data class Failed(val error: AccountError) : AccountEvent
}

/** Pure projections of the repository models (unit-tested). */
object AccountProjection {
    fun overview(overview: AccountOverview, zone: ZoneId): AccountOverviewUi {
        val active = overview.activeOrganization
        val displayed = if (active != null) overview.organizationBalance ?: overview.balance else overview.balance
        val user = overview.user
        val personal = AccountRow(id = null, name = null, subtitle = user.email.ifBlank { null }, active = active == null)
        val orgs = overview.organizations.map { org ->
            AccountRow(id = org.organizationId, name = org.name, subtitle = AccountRepository.formatRoles(org.roles), active = org.active)
        }
        return AccountOverviewUi(
            name = overview.summaryName,
            email = user.email,
            initial = AccountRepository.initial(user),
            memberSince = AccountFormat.date(user.createdAt, zone) ?: user.createdAt?.takeIf { it.isNotBlank() },
            organizationName = active?.name,
            balance = displayed?.let { AccountFormat.creditBalance(it) },
            balanceCredits = displayed?.let { it / 1_000_000.0 },
            personalCredits = if (active != null) overview.balance?.let { AccountFormat.creditBalance(it) } else null,
            accounts = listOf(personal) + orgs,
            activeOrganization = active,
        )
    }

    /** [is24Hour]: the user's "Use 24-hour format" setting for the row times. */
    fun usage(rows: List<UsageTransaction>, zone: ZoneId, is24Hour: Boolean = false): List<UsageRow> = rows.mapIndexed { index, tx ->
        UsageRow(
            id = tx.id.ifEmpty { "usage-$index" },
            model = tx.aiModelName,
            provider = tx.aiInferenceProviderName?.takeIf { it.isNotBlank() },
            tokens = tx.totalTokens,
            credits = AccountFormat.creditBalance(tx.creditsUsed),
            date = AccountFormat.date(tx.createdAt, zone),
            time = AccountFormat.time(tx.createdAt, zone, is24Hour),
        )
    }

    fun payments(rows: List<PaymentTransaction>, zone: ZoneId): List<PaymentRow> = rows.mapIndexed { index, tx ->
        PaymentRow(
            key = "${tx.paidAt}-${tx.amountCents}-${tx.credits}-$index",
            date = AccountFormat.date(tx.paidAt, zone) ?: tx.paidAt,
            amount = AccountRepository.formatAmountCents(tx.amountCents),
            credits = AccountFormat.creditBalance(tx.credits),
        )
    }
}
