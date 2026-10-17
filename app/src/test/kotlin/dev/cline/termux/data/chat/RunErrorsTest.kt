package dev.cline.termux.data.chat

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ports of `run-error.test.ts` and the hint/action cases of `helpers.test.ts`, against the real resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunErrorsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val strings = RunErrorStrings.from(context.resources)

    private val providers = "Check your model connection in Settings → Providers (or sign in with Cline), then try again."
    private val cline = "Sign in to Cline again in Settings → Account, then try again."
    private val unknown = "Sign in again using your provider's authentication method, then try again."
    private val codex = ProviderAuthInfo("openai-codex", listOf("oauth"), LocalCli("codex"))

    @Test
    fun `prefixes kernel errors and appends the providers hint for credential failures`() {
        assertEquals(
            "The run failed: 401 Unauthorized $providers",
            formatRunError("401 Unauthorized", "anthropic", ProviderAuthInfo("anthropic", listOf("api-key")), strings),
        )
    }

    @Test
    fun `does not prefix a detail that already starts with The run failed`() {
        assertEquals("The run failed: boom", formatRunError("The run failed: boom", "anthropic", null, strings))
    }

    @Test
    fun `non-credential errors get no hint`() {
        assertEquals("The run failed: context window exceeded (token limit)", formatRunError("context window exceeded (token limit)", "anthropic", null, strings))
    }

    @Test
    fun `empty details read as a failure before any response, with the hint`() {
        assertEquals("The run failed before a response was produced. $unknown", formatRunError("  ", "anthropic", null, strings))
    }

    @Test
    fun `local CLI providers point at their CLI`() {
        assertEquals(
            "The run failed: invalid token Sign in again with the `codex` CLI in a terminal, then try again.",
            formatRunError("invalid token", "openai-codex", codex, strings),
        )
        assertNull(resolveCredentialFailureAction("openai-codex", codex))
    }

    @Test
    fun `cline points at the account page`() {
        assertEquals(cline, resolveCredentialFailureHint("cline", null, strings))
        assertEquals(CredentialAction.Account, resolveCredentialFailureAction(" cline ", null))
        assertEquals(R.string.cred_action_cline, CredentialAction.Account.labelRes)
        assertEquals("Sign in to Cline", context.getString(CredentialAction.Account.labelRes))
    }

    @Test
    fun `auth facts for another provider are ignored`() {
        assertEquals(unknown, resolveCredentialFailureHint("anthropic", codex, strings))
        assertNull(resolveCredentialFailureAction("anthropic", codex))
        assertEquals(CredentialAction.Providers, resolveCredentialFailureAction("openai", ProviderAuthInfo("openai-native")))
    }

    @Test
    fun `the hint is not repeated when the detail already contains it`() {
        assertEquals("The run failed: 401 $providers", formatRunError("401 $providers", "anthropic", ProviderAuthInfo("anthropic"), strings))
    }

    @Test
    fun `credential detection never matches a bare token`() {
        assertTrue(isCredentialFailure("403 Forbidden"))
        assertTrue(isCredentialFailure("Please run /login"))
        assertTrue(isCredentialFailure("Your session expired"))
        assertFalse(isCredentialFailure("maximum context length is 200000 tokens"))
        assertTrue(looksCredentialRelated(""))
    }

    @Test
    fun `credential meta carries only the matching auth`() {
        val meta = credentialFailureMeta("anthropic", codex)
        assertEquals(REASON_CREDENTIALS, meta.reason)
        assertNull(meta.providerAuth)
        assertEquals(codex, credentialFailureMeta("openai-codex", codex).providerAuth)
    }

    @Test
    fun `fork title strings keep the leading space`() {
        val fork = ForkTitleStrings.from(context.resources)
        assertEquals(" (fork)", fork.suffix)
        assertEquals("Untitled", fork.untitled)
    }

}
