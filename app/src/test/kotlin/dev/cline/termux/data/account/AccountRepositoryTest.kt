package dev.cline.termux.data.account

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRepositoryTest {
    private fun json(text: String): JsonElement = CustomizeJson.parseToJsonElement(text)

    private class Summary : AccountSummaryStore {
        val writes = mutableListOf<Pair<String?, String?>>()
        override suspend fun setAccount(displayName: String?, scope: String?) {
            writes += displayName to scope
        }
    }

    private val me = """{"id":"u1","email":"a@b.c","displayName":"","photoUrl":"","createdAt":"2025-01-02T00:00:00Z","updatedAt":"x",
        "organizations":[{"organizationId":"o1","name":"Acme","roles":["owner"],"active":true,"memberId":"m1"}]}"""

    @Test
    fun `overview fetches me first, then balance and organisations, then the active org balance`() = runTest {
        val helper = FakeHelperChannel()
        val summary = Summary()
        val repo = AccountRepository(helper, FakeTermuxExecutor(), summary) { }
        helper.client.respond(AccountRepository.COMMAND) { args ->
            when (args["operation"]!!.jsonPrimitive.content) {
                "fetchMe" -> json(me)
                "fetchBalance" -> json("""{"balance":12345678,"userId":"u1"}""")
                "fetchUserOrganizations" -> json("""[{"organizationId":"o1","name":"Acme","roles":["owner","admin"],"active":true,"memberId":"m1"}]""")
                "fetchOrganizationBalance" -> json("""{"balance":5000000,"organizationId":"${args["organizationId"]!!.jsonPrimitive.content}"}""")
                else -> error("unexpected")
            }
        }
        val overview = (repo.overview() as AccountResult.Ok).value
        assertEquals(listOf("fetchMe", "fetchBalance", "fetchUserOrganizations", "fetchOrganizationBalance").toSet(), helper.client.calls.map { it.args["operation"]!!.jsonPrimitive.content }.toSet())
        assertEquals("fetchMe", helper.client.calls.first().args["operation"]!!.jsonPrimitive.content)
        assertTrue(helper.client.calls.all { it.args["action"]!!.jsonPrimitive.content == "clineAccount" })
        assertEquals(12345678.0, overview.balance!!, 0.0)
        assertEquals(5000000.0, overview.organizationBalance!!, 0.0)
        assertEquals("Acme", overview.activeOrganization!!.name)
        assertEquals("a@b.c", overview.summaryName)
        assertEquals(listOf<Pair<String?, String?>>("a@b.c" to "Acme"), summary.writes)
        assertEquals("a", AccountRepository.initial(overview.user))
        assertEquals("Owner, Admin", AccountRepository.formatRoles(overview.organizations.single().roles))
    }

    @Test
    fun `avatar initial is the first grapheme, never half a surrogate pair`() {
        fun user(displayName: String, email: String = "a@b.c") = AccountUser("u1", email, displayName, null, emptyList())
        assertEquals("\uD83D\uDE00", AccountRepository.initial(user("\uD83D\uDE00 Ann")))
        assertEquals("\uD842\uDFB7", AccountRepository.initial(user("\uD842\uDFB7\u7530")))
        assertEquals("e\u0301", AccountRepository.initial(user("e\u0301mile")))
        assertEquals("S", AccountRepository.initial(user("Sam Lee")))
        assertEquals("z", AccountRepository.initial(user("", email = "zoe@example.com")))
        assertEquals("?", AccountRepository.initial(user("", email = "")))
    }

    @Test
    fun `signed out is detected only from the desktop auth errors, and nothing else is fired`() = runTest {
        val helper = FakeHelperChannel()
        val summary = Summary()
        val repo = AccountRepository(helper, FakeTermuxExecutor(), summary) { }
        helper.client.fail(AccountRepository.COMMAND, "No Cline account auth token found")
        assertEquals(AccountResult.SignedOut, repo.overview())
        assertEquals(1, helper.client.calls.size)
        assertEquals(listOf<Pair<String?, String?>>(null to null), summary.writes)

        helper.client.fail(AccountRepository.COMMAND, "Request failed with status 500")
        assertEquals(AccountResult.Failed(RepoFailure.Helper("Request failed with status 500")), repo.overview())
        helper.client.fail(AccountRepository.COMMAND, "unauthorized organization access")
        assertTrue("broader auth words stay errors", repo.overview() is AccountResult.Failed)
        helper.client.fail(AccountRepository.COMMAND, "Unsupported desktop command: cline_account")
        assertEquals(AccountResult.OlderHelper, repo.overview())

        assertTrue(AccountRepository.isAccountAuthError("Token requires re-authentication"))
        assertTrue(AccountRepository.isAccountAuthError("GET /me failed with status 401"))
    }

    @Test
    fun `sign out sends the desktop payload, then invalidates and clears the summary`() = runTest {
        val helper = FakeHelperChannel()
        val summary = Summary()
        var invalidated = 0
        val repo = AccountRepository(helper, FakeTermuxExecutor(), summary) { invalidated++ }
        helper.client.respond(AccountRepository.SAVE_PROVIDER_SETTINGS, json("""{"ok":true}"""))
        assertEquals(AccountResult.Ok(Unit), repo.signOut())
        val args = helper.client.callsFor(AccountRepository.SAVE_PROVIDER_SETTINGS).single().args
        assertEquals(AccountRepository.SIGN_OUT_PAYLOAD, args)
        assertEquals(1, invalidated)
        assertEquals(listOf<Pair<String?, String?>>(null to null), summary.writes)
    }

    @Test
    fun `usage picks the org or personal operation and drops a reply overtaken by a newer request`() = runTest {
        val helper = FakeHelperChannel()
        val repo = AccountRepository(helper, FakeTermuxExecutor(), Summary()) { }
        val gate = CompletableDeferred<Unit>()
        helper.client.respond(AccountRepository.COMMAND) { args ->
            when (args["operation"]!!.jsonPrimitive.content) {
                "fetchOrganizationUsageTransactions" -> {
                    gate.await()
                    json("""[{"id":"1","aiModelName":"m","totalTokens":10,"creditsUsed":2,"createdAt":"2026-01-01T00:00:00Z"}]""")
                }
                "fetchUsageTransactions" -> json("""[{"id":"2","aiModelName":"p","aiInferenceProviderName":"anthropic","totalTokens":5,"creditsUsed":1}]""")
                "fetchPaymentTransactions" -> json("""[{"paidAt":"2026-01-01","amountCents":1250,"credits":10000000,"creatorId":"u1"}]""")
                else -> error("unexpected")
            }
        }
        val org = AccountOrganization("o1", "Acme", listOf("member"), active = true, memberId = " m1 ")
        val slow = async { repo.usage(org) }
        runCurrent()
        val personal = repo.usage(null) as AccountResult.Ok
        gate.complete(Unit)
        assertNull("the org reply arrived after the switch", slow.await())
        assertEquals("anthropic", personal.value.single().aiInferenceProviderName)
        val orgCall = helper.client.calls.first()
        assertEquals("m1", orgCall.args["memberId"]!!.jsonPrimitive.content)

        val billing = (repo.billing() as AccountResult.Ok).value.single()
        assertEquals("$12.50", AccountRepository.formatAmountCents(billing.amountCents))
    }

    @Test
    fun `without the helper the signed-in state comes from providers json`() = runTest {
        val helper = FakeHelperChannel(HelperState.Idle)
        val termux = FakeTermuxExecutor()
        termux.on(ClineCommands.Labels.READ_PROVIDER_PRESENCE, TermuxResult.success("""{"providers":{"cline":{"settings":{"auth":{"accessToken":"t"}}}}}"""))
        val repo = AccountRepository(helper, termux, Summary()) { }
        assertEquals(Outcome.Ok(true), repo.signedInWithoutHelper())
        termux.on(ClineCommands.Labels.READ_PROVIDER_PRESENCE, TermuxResult.failure(1, "cat: No such file or directory"))
        assertEquals(Outcome.Ok(false), repo.signedInWithoutHelper())
        assertEquals(AccountResult.Failed(RepoFailure.HelperDown(null)), repo.overview())
    }
}
