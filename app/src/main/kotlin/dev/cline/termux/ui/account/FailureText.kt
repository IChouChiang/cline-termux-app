package dev.cline.termux.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.customize.RepoFailure

/*
 * Failure copy for this lane. Typed repository failures map through the shared
 * `RepoFailure.toUiText()` (ui/components/FailureCopy.kt); this file keeps the log reason and
 * the sign-in sentences.
 */

/** The log tag reason of a failure (data packages never log; screens log by reason only, never text). */
val RepoFailure.logReason: String
    get() = when (this) {
        is RepoFailure.Hub -> "hub:$code"
        RepoFailure.HubDisconnected -> "hub_disconnected"
        RepoFailure.HubTimeout -> "hub_timeout"
        RepoFailure.HubDraining -> "hub_draining"
        is RepoFailure.Termux -> "termux:$problem"
        is RepoFailure.Command -> "command:$exitCode"
        is RepoFailure.HelperDown -> "helper_down:$reason"
        is RepoFailure.Helper -> "helper_error"
        RepoFailure.HelperLost -> "helper_lost"
        RepoFailure.HelperTimeout -> "helper_timeout"
        is RepoFailure.Malformed -> "malformed"
    }

/** "Sign in failed: …" / "Failed to save API key: …" for [failure] (S7.3), shown by S5's inline sign-in. */
@Composable
fun signInFailureText(failure: SignInFailure, kind: SignInState.Failed.Kind): String {
    val detail = when (failure) {
        is SignInFailure.ExitCode -> stringResource(R.string.signin_failed_exit, failure.code)
        is SignInFailure.Termux -> failure.errmsg
        SignInFailure.TimedOut -> stringResource(R.string.signin_failed_timeout)
        is SignInFailure.KeyNotVerified -> stringResource(R.string.signin_key_not_verified, failure.error)
        is SignInFailure.KeySaveFailed -> failure.message
        SignInFailure.TermuxDidNotAnswer -> stringResource(R.string.kernel_blocked_timeout_title)
    }
    return when (kind) {
        SignInState.Failed.Kind.SignIn -> stringResource(R.string.signin_failed, detail)
        SignInState.Failed.Kind.ApiKey -> stringResource(R.string.signin_key_failed, detail)
    }
}
