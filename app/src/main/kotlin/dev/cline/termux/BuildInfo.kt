package dev.cline.termux

/**
 * Build facts the kernel layer and the About screen read (docs/07 §1.1).
 *
 * [MIN_PORT_RELEASE] is the first Termux port whose Hub daemon starts at all
 * (docs/02 "Prerequisites"); the kernel probe compares the `release=` line of
 * `$PREFIX/opt/cline-termux/current/VERSION` against it (onboarding-kernel.md §0.4).
 * [MIN_PORT_RELEASE_RESTORE] gates the atomic `session.restore` path
 * (changes-tools-checkpoints.md §1.5.1); `null` until a port release carries kernel fix K1.
 */
object BuildInfo {
    val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val VERSION_CODE: Int = BuildConfig.VERSION_CODE
    const val MIN_PORT_RELEASE: String = "3.0.68-termux.2"
    val MIN_PORT_RELEASE_RESTORE: String? = null
}
