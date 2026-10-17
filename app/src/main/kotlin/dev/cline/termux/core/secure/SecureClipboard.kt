package dev.cline.termux.core.secure

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The only way a secret reaches the clipboard (SP §0.6, docs/07 §14): the clip is flagged
 * `EXTRA_IS_SENSITIVE` (API 33+ hides the preview) and cleared unconditionally after
 * [clearAfterMs]: when the timer fires while the process is resumed, otherwise at the next
 * `ON_RESUME` after that time has passed. The app cannot check whether its clip is still
 * the primary one from the background (API 29+ returns null), so a later non-secret clip
 * may be cleared too; that is the accepted cost. Plain clips (paths, ids) bypass this class.
 */
class SecureClipboard(
    context: Context,
    private val scope: CoroutineScope,
    lifecycle: Lifecycle? = null,
    private val clearAfterMs: Long = DEFAULT_CLEAR_AFTER_MS,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {
    private val clipboard: ClipboardManager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    /** Memory-only (`secretCopiedAtMs`, SP §0.6); never persisted. */
    @Volatile private var secretCopiedAtMs: Long? = null

    @Volatile private var resumed: Boolean = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    private var clearJob: Job? = null

    init {
        lifecycle?.let { owner ->
            if (Looper.myLooper() == Looper.getMainLooper()) owner.addObserver(this) else Handler(Looper.getMainLooper()).post { owner.addObserver(this) }
        }
    }

    /** True between a [copySecret] and its clear. */
    val holdsSecret: Boolean get() = secretCopiedAtMs != null

    /** [label] is the clip's `ClipDescription` label (a resource string owned by the caller). */
    fun copySecret(label: CharSequence, secret: String) {
        val clip = ClipData.newPlainText(label, secret)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        secretCopiedAtMs = elapsedMs()
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(clearAfterMs)
            if (resumed) clearNow()
        }
    }

    /** Clears the primary clip now if a secret was copied and not yet cleared. */
    fun clearNow() {
        if (secretCopiedAtMs == null) return
        secretCopiedAtMs = null
        clearJob?.cancel()
        clearJob = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
            // Some OEM clipboard services throw from the background; the next resume retries.
            secretCopiedAtMs = elapsedMs() - clearAfterMs
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        val copiedAt = secretCopiedAtMs ?: return
        if (elapsedMs() - copiedAt >= clearAfterMs) clearNow()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
    }

    companion object {
        const val DEFAULT_CLEAR_AFTER_MS = 60_000L
    }
}
