package dev.cline.termux

/** `assertThrows` that also works around suspend calls inside `runTest` (inline lambda). */
inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName} but got ${e.javaClass.name}: ${e.message}", e)
    }
    throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
}

/**
 * The failure of a `runCatching` result as [T]. Tests wrap a call expected to throw in
 * `async { runCatching { … } }` so the failure does not cancel the test scope.
 */
inline fun <reified T : Throwable> Result<*>.failedWith(): T {
    val error = exceptionOrNull() ?: throw AssertionError("expected ${T::class.simpleName} but the call succeeded")
    if (error is T) return error
    throw AssertionError("expected ${T::class.simpleName} but got ${error.javaClass.name}: ${error.message}", error)
}
