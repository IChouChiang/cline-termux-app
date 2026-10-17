package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.AccountOrganization
import dev.cline.termux.data.account.AccountOverview
import dev.cline.termux.data.account.AccountResult
import dev.cline.termux.data.account.AccountUser
import dev.cline.termux.data.account.PaymentTransaction
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.account.UsageTransaction
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.ui.account.AccountSource
import dev.cline.termux.ui.account.SignInPort
import kotlinx.coroutines.flow.MutableStateFlow

/** Scripted [AccountSource]: each call answers with the current field and is counted. */
class FakeAccountSource(
    var overview: AccountResult<AccountOverview> = AccountResult.Ok(overview()),
    var usage: AccountResult<List<UsageTransaction>> = AccountResult.Ok(emptyList()),
    var billing: AccountResult<List<PaymentTransaction>> = AccountResult.Ok(emptyList()),
    var switchResult: AccountResult<Unit> = AccountResult.Ok(Unit),
    var signOutResult: AccountResult<Unit> = AccountResult.Ok(Unit),
    var signedInWithoutHelper: Outcome<Boolean> = Outcome.Ok(true),
) : AccountSource {
    var overviewCalls = 0
    var usageCalls = 0
    var billingCalls = 0
    var fallbackCalls = 0
    val switches = mutableListOf<String?>()
    var signOuts = 0

    /** Hook run after an overview call answered (e.g. to change the next answer). */
    var afterOverview: () -> Unit = {}

    override suspend fun overview(): AccountResult<AccountOverview> {
        overviewCalls++
        return overview.also { afterOverview() }
    }

    override suspend fun usage(activeOrganization: AccountOrganization?): AccountResult<List<UsageTransaction>> {
        usageCalls++
        return usage
    }

    override suspend fun billing(): AccountResult<List<PaymentTransaction>> {
        billingCalls++
        return billing
    }

    override suspend fun switchAccount(organizationId: String?): AccountResult<Unit> {
        switches += organizationId
        return switchResult
    }

    override suspend fun signOut(): AccountResult<Unit> {
        signOuts++
        return signOutResult
    }

    override suspend fun signedInWithoutHelper(): Outcome<Boolean> {
        fallbackCalls++
        return signedInWithoutHelper
    }

    companion object {
        fun user(email: String = "ada@example.com", name: String = "Ada", createdAt: String? = "2025-03-04T10:00:00Z") =
            AccountUser(id = "u1", email = email, displayName = name, createdAt = createdAt, organizations = emptyList())

        fun org(id: String, name: String, active: Boolean = false, roles: List<String> = listOf("member")) =
            AccountOrganization(organizationId = id, name = name, roles = roles, active = active, memberId = "m-$id")

        fun overview(
            balance: Double? = 12_500_000.0,
            organizations: List<AccountOrganization> = emptyList(),
            organizationBalance: Double? = null,
        ) = AccountOverview(user = user(), balance = balance, organizations = organizations, organizationBalance = organizationBalance)
    }
}

/** [HelperLease] whose state the test drives; [onRetry] runs on every `retry()`. */
class FakeHelperLease(initial: HelperState = HelperState.Ready) : HelperLease {
    override val state = MutableStateFlow(initial)
    val acquired = mutableListOf<String>()
    val released = mutableListOf<String>()
    var retries = 0
    var onRetry: () -> Unit = {}

    override suspend fun acquire(owner: String) {
        acquired += owner
    }

    override suspend fun release(owner: String) {
        released += owner
    }

    override suspend fun retry() {
        retries++
        onRetry()
    }
}

/** [SignInPort] recording intents; the test sets [state]. */
class FakeSignInPort : SignInPort {
    override val state = MutableStateFlow<SignInState>(SignInState.Idle)
    var starts = 0
    var cancels = 0
    var acknowledgements = 0
    var termuxOpened = 0

    override fun start() {
        starts++
    }

    override fun openTermux(): Boolean {
        termuxOpened++
        return true
    }

    override fun cancel() {
        cancels++
    }

    override fun acknowledge() {
        acknowledgements++
    }
}
