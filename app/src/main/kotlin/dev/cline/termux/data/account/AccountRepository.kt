package dev.cline.termux.data.account

import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.readProviderPresence
import dev.cline.termux.data.chat.firstGrapheme
import dev.cline.termux.data.customize.HelperChannel
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.outcomeOf
import dev.cline.termux.data.customize.toRepoFailure
import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * Cline account (customize-and-rest.md §5.8, §5.9; docs/07 §4.6): every operation is the
 * helper's `cline_account {action:"clineAccount", operation, …}`
 * (`sdk/packages/core/src/account/rpc.ts`), sign-out is `save_provider_settings` with the
 * desktop Account payload, and without the helper the signed-in state comes from
 * `providers.json` (`hasOAuth` for `cline`). Signed-out detection is the desktop's
 * `isAccountAuthError`, nothing broader.
 */

/** `ClineAccountOrganization`. */
data class AccountOrganization(val organizationId: String, val name: String, val roles: List<String>, val active: Boolean, val memberId: String?)

/** `ClineAccountUser` (photoUrl is not loaded: the app shows the initial only). */
data class AccountUser(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: String?,
    val organizations: List<AccountOrganization>,
)

/** `ClineAccountUsageTransaction` fields the Usage tab shows. */
data class UsageTransaction(
    val id: String,
    val aiModelName: String,
    val aiInferenceProviderName: String?,
    val totalTokens: Long,
    val creditsUsed: Double,
    val createdAt: String?,
)

/** `ClineAccountPaymentTransaction`. */
data class PaymentTransaction(val paidAt: String?, val amountCents: Long, val credits: Double, val creatorId: String?)

/** The Overview tab: profile, balances and organisations (desktop `loadOverview`). */
data class AccountOverview(
    val user: AccountUser,
    /** Personal balance (micro-credits); null when `fetchBalance` failed. */
    val balance: Double?,
    val organizations: List<AccountOrganization>,
    /** The active organisation's balance, fetched only when one is active. */
    val organizationBalance: Double?,
) {
    val activeOrganization: AccountOrganization? get() = organizations.firstOrNull { it.active }

    /** SP S1 Account row: `displayName` or `email`. */
    val summaryName: String get() = user.displayName.ifBlank { user.email }

    /** SP S1 Account row scope: the active organisation's name, or "Personal". */
    val summaryScope: String get() = activeOrganization?.name ?: PERSONAL_SCOPE

    companion object {
        const val PERSONAL_SCOPE = "Personal"
    }
}

/** What a call produced: data, the signed-out card, an older helper, or an error. */
sealed interface AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>

    /** `isAccountAuthError` matched (CR §5.4 "Token expired"): reset data, show the signed-out card. */
    data object SignedOut : AccountResult<Nothing>

    /** "unsupported desktop command: cline_account" (CR §5.4 "Older helper"). */
    data object OlderHelper : AccountResult<Nothing>

    data class Failed(val failure: RepoFailure) : AccountResult<Nothing>
}

/** The non-secret SP S1 summary (`account.displayName`, `account.scope`, docs/07 §7.2). */
fun interface AccountSummaryStore {
    suspend fun setAccount(displayName: String?, scope: String?)
}

class AccountRepository(
    private val helper: HelperChannel,
    private val termux: TermuxExecutor,
    private val summary: AccountSummaryStore,
    /** `ProviderCatalogRepository.invalidate()` after a sign-out (CR §5.8). */
    private val onSignedOut: suspend () -> Unit,
) {
    private val usageMutex = Mutex()
    private var usageGeneration = 0L

    /** `fetchMe` first; nothing else is fired when signed out; then balance ‖ organisations; then the org balance. */
    suspend fun overview(): AccountResult<AccountOverview> {
        val user = when (val me = call("fetchMe")) {
            is AccountResult.Ok -> parseUser(me.value) ?: return AccountResult.Failed(RepoFailure.Malformed("fetchMe reply is not a user"))
            is AccountResult.SignedOut -> return signedOut()
            is AccountResult.OlderHelper -> return me
            is AccountResult.Failed -> return me
        }
        val (balance, organizations) = coroutineScope {
            val b = async { call("fetchBalance") }
            val o = async { call("fetchUserOrganizations") }
            b.await() to o.await()
        }
        if (balance is AccountResult.SignedOut || organizations is AccountResult.SignedOut) return signedOut()
        val orgs = (organizations as? AccountResult.Ok)?.value?.let(::parseOrganizations) ?: user.organizations
        val active = orgs.firstOrNull { it.active }
        val orgBalance = active?.let { org ->
            when (val r = call("fetchOrganizationBalance") { put("organizationId", org.organizationId) }) {
                is AccountResult.Ok -> (r.value as? JsonObject)?.get("balance").jsNumber()
                is AccountResult.SignedOut -> return signedOut()
                else -> null
            }
        }
        val overview = AccountOverview(
            user = user,
            balance = ((balance as? AccountResult.Ok)?.value as? JsonObject)?.get("balance").jsNumber(),
            organizations = orgs,
            organizationBalance = orgBalance,
        )
        summary.setAccount(overview.summaryName, overview.summaryScope)
        return AccountResult.Ok(overview)
    }

    /**
     * The Usage tab for the active organisation (or personal). A reply that arrives after a
     * newer request (the organisation changed meanwhile) is discarded as null (desktop
     * `usageGenerationRef`).
     */
    suspend fun usage(activeOrganization: AccountOrganization?): AccountResult<List<UsageTransaction>>? {
        val generation = usageMutex.withLock { ++usageGeneration }
        val result = if (activeOrganization != null) {
            call("fetchOrganizationUsageTransactions") {
                put("organizationId", activeOrganization.organizationId)
                activeOrganization.memberId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("memberId", it) }
            }
        } else {
            call("fetchUsageTransactions")
        }
        if (usageMutex.withLock { generation != usageGeneration }) return null
        return result.map(::parseUsage)
    }

    suspend fun billing(): AccountResult<List<PaymentTransaction>> = call("fetchPaymentTransactions").map(::parsePayments)

    /** `switchAccount {organizationId}` (null = personal); the caller reloads the overview. */
    suspend fun switchAccount(organizationId: String?): AccountResult<Unit> =
        call("switchAccount") { put("organizationId", organizationId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull) }.map { }

    /** The desktop Account sign-out payload; then the provider catalog and the SP S1 summary are cleared. */
    suspend fun signOut(): AccountResult<Unit> {
        val out = outcomeOf { helper.desktopCommand(SAVE_PROVIDER_SETTINGS, SIGN_OUT_PAYLOAD) }
        return when (out) {
            is Outcome.Ok -> {
                onSignedOut()
                summary.setAccount(null, null)
                AccountResult.Ok(Unit)
            }
            is Outcome.Failed -> classify(out.failure)
        }
    }

    /** Helper unavailable (CR §5.4): signed in ⇔ `providers.json` has an OAuth token for `cline`. */
    suspend fun signedInWithoutHelper(): Outcome<Boolean> = outcomeOf {
        termux.readProviderPresence().providersOrEmpty.providers[CLINE_PROVIDER_ID]?.hasOAuth == true
    }

    private suspend fun signedOut(): AccountResult<Nothing> {
        summary.setAccount(null, null)
        return AccountResult.SignedOut
    }

    private suspend fun call(operation: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): AccountResult<JsonElement> {
        val payload = buildJsonObject {
            put("action", ACTION)
            put("operation", operation)
            extra()
        }
        return try {
            AccountResult.Ok(helper.desktopCommand(COMMAND, payload))
        } catch (e: Throwable) {
            classify(e.toRepoFailure())
        }
    }

    private fun classify(failure: RepoFailure): AccountResult<Nothing> {
        val message = (failure as? RepoFailure.Helper)?.message
        return when {
            message != null && isAccountAuthError(message) -> AccountResult.SignedOut
            message != null && isOlderHelperError(message) -> AccountResult.OlderHelper
            else -> AccountResult.Failed(failure)
        }
    }

    companion object {
        const val COMMAND = "cline_account"
        const val ACTION = "clineAccount"
        const val SAVE_PROVIDER_SETTINGS = "save_provider_settings"
        const val CLINE_PROVIDER_ID = "cline"

        /** `{provider:"cline", api_key:"", settings:{auth:{accessToken:"", refreshToken:"", accountId:""}}}`. */
        val SIGN_OUT_PAYLOAD: JsonObject = buildJsonObject {
            put("provider", CLINE_PROVIDER_ID)
            put("api_key", "")
            putJsonObject("settings") {
                putJsonObject("auth") {
                    put("accessToken", "")
                    put("refreshToken", "")
                    put("accountId", "")
                }
            }
        }

        /** Desktop `isAccountAuthError`: only the definitive signed-out signals. */
        fun isAccountAuthError(message: String): Boolean {
            val normalized = message.lowercase()
            return "no cline account auth token found" in normalized ||
                "requires re-authentication" in normalized ||
                "failed with status 401" in normalized
        }

        /** The dashboard predates `cline_account`. */
        fun isOlderHelperError(message: String): Boolean = "unsupported desktop command" in message.lowercase()

        fun parseUser(element: JsonElement): AccountUser? {
            val o = element as? JsonObject ?: return null
            return AccountUser(
                id = o["id"].jsString().orEmpty(),
                email = o["email"].jsString().orEmpty(),
                displayName = o["displayName"].jsString().orEmpty(),
                createdAt = o["createdAt"].jsString(),
                organizations = parseOrganizations(o["organizations"] ?: JsonArray(emptyList())),
            )
        }

        fun parseOrganizations(element: JsonElement): List<AccountOrganization> =
            (element as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o["organizationId"].jsString() ?: return@mapNotNull null
                AccountOrganization(
                    organizationId = id,
                    name = o["name"].jsString().orEmpty(),
                    roles = (o["roles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                    active = o["active"].jsBoolean() == true,
                    memberId = o["memberId"].jsString(),
                )
            }

        fun parseUsage(element: JsonElement): List<UsageTransaction> =
            (element as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                UsageTransaction(
                    id = o["id"].jsString() ?: o["generationId"].jsString().orEmpty(),
                    aiModelName = o["aiModelName"].jsString().orEmpty(),
                    aiInferenceProviderName = o["aiInferenceProviderName"].jsString(),
                    totalTokens = o["totalTokens"].jsNumber()?.toLong() ?: 0,
                    creditsUsed = o["creditsUsed"].jsNumber() ?: 0.0,
                    createdAt = o["createdAt"].jsString(),
                )
            }

        fun parsePayments(element: JsonElement): List<PaymentTransaction> =
            (element as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                PaymentTransaction(
                    paidAt = o["paidAt"].jsString(),
                    amountCents = o["amountCents"].jsNumber()?.toLong() ?: 0,
                    credits = o["credits"].jsNumber() ?: 0.0,
                    creatorId = o["creatorId"].jsString(),
                )
            }

        /** Org roles shown with each word capitalised (desktop CSS `capitalize`). */
        fun formatRoles(roles: List<String>): String =
            roles.joinToString(", ") { role -> role.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) } } }

        /**
         * Avatar initial: the first grapheme of `displayName`, else of `email`, else "?". A
         * grapheme, not a `Char`, so an emoji or a supplementary-plane Han name (𠮷田) is never
         * cut mid surrogate pair (DS §8.2.7).
         */
        fun initial(user: AccountUser): String =
            firstGrapheme(user.displayName).ifEmpty { null } ?: firstGrapheme(user.email).ifEmpty { "?" }

        /** Billing amount: "$" + cents / 100 with two decimals (en-US, a technical unit). */
        fun formatAmountCents(cents: Long): String = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)
    }
}

private inline fun <T, R> AccountResult<T>.map(transform: (T) -> R): AccountResult<R> = when (this) {
    is AccountResult.Ok -> AccountResult.Ok(transform(value))
    is AccountResult.SignedOut -> this
    is AccountResult.OlderHelper -> this
    is AccountResult.Failed -> this
}
