package dev.cline.termux.ui.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/**
 * `advanceUntilIdle` stops once only background work is left, so the `backgroundScope`
 * collectors of the picker tests would not see the last effects; `runCurrent` delivers them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceUntilIdle()
    runCurrent()
}
