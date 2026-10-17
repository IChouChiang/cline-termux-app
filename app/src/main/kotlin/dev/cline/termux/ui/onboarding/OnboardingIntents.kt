package dev.cline.termux.ui.onboarding

import android.content.Context
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.ui.components.openAppDetails

/*
 * The system intents of the setup screens (A.5, B.5, C.5). Each returns false when nothing
 * could handle it, so the caller can say so instead of failing silently. "Open Termux" is the
 * shared [dev.cline.termux.ui.components.openTermux].
 */
object OnboardingIntents {
    /** "Open Termux settings": `ACTION_APPLICATION_DETAILS_SETTINGS` for `com.termux`. */
    fun openTermuxSettings(context: Context): Boolean = context.openAppDetails(TermuxPaths.PACKAGE)

    /** "Open app settings": the same screen for this app (row 2 permanently denied). */
    fun openOwnAppSettings(context: Context): Boolean = context.openAppDetails()
}
