package dev.cline.termux.core.kernel

import android.os.SystemClock

/** Time as the coordinator sees it: monotonic for budgets, wall for timestamps. Fakeable. */
interface KernelClock {
    /** Milliseconds since boot, unaffected by clock changes. */
    fun elapsedMs(): Long

    /** Epoch milliseconds. */
    fun wallMs(): Long
}

object SystemKernelClock : KernelClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}
