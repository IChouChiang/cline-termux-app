// Adapted from cline-termux-voice (same owner): android/app/src/main/kotlin/dev/cline/voice/VoiceService.kt
package dev.cline.termux.ui.composer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * Voice input (design/screens/composer-approvals.md C7): on-device `SpeechRecognizer` dictation
 * into the composer. No kernel involvement. [VoiceInputController] holds the session rules
 * (continuous dictation, restart delays, error mapping, the 3 s processing cap) over a
 * [SpeechEngine], so they are unit-tested without a recogniser; [VoiceInsertion] is the port of
 * the desktop's streaming-transcript insertion handlers.
 */

/** Recogniser failures, already mapped from `SpeechRecognizer.ERROR_*`. */
sealed interface SpeechFailure {
    data object NoMatch : SpeechFailure
    data object SpeechTimeout : SpeechFailure
    data object Busy : SpeechFailure
    data object Audio : SpeechFailure
    data object Network : SpeechFailure
    data object Permission : SpeechFailure
    data class Other(val name: String) : SpeechFailure
}

/** Callbacks from the engine (on the main thread). */
interface SpeechListener {
    fun onReady()
    fun onRms(rmsDb: Float)
    fun onPartial(text: String)
    fun onResult(text: String?)
    fun onFailure(failure: SpeechFailure)
}

/** The recogniser edge. */
interface SpeechEngine {
    /** Starts one utterance; false when the recogniser could not be created or started. */
    fun start(listener: SpeechListener): Boolean

    /** Ends the utterance; the final result follows through [SpeechListener.onResult]. */
    fun stop()

    /** Drops the utterance without a result. */
    fun cancel()

    /** Releases the recogniser (the next [start] creates a new one). */
    fun destroy()
}

/** One-shot facts for the composer. */
sealed interface VoiceEvent {
    /** A new utterance begins: remember the insertion range. */
    data object UtteranceStarted : VoiceEvent

    /** Cumulative text of the current utterance (partial or final). */
    data class Transcript(val text: String) : VoiceEvent

    /** The utterance is over; the next one inserts at the new cursor. */
    data object UtteranceEnded : VoiceEvent

    /** Listening started or stopped (TalkBack "Listening", haptics). */
    data class Active(val active: Boolean) : VoiceEvent

    data class Failed(val error: VoiceError) : VoiceEvent
}

class VoiceInputController(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope,
    available: Boolean,
    private val config: Config = Config(),
) {
    data class Config(
        val restartDelayMs: Long = 250,
        val busyRetryDelayMs: Long = 900,
        val recreateDelayMs: Long = 1_500,
        val processingTimeoutMs: Long = 3_000,
        /** Two silent timeouts in a row (≈ 10 s) end the session. */
        val maxSilentTimeouts: Int = 2,
    )

    private val _state = MutableStateFlow<VoiceState>(if (available) VoiceState.Idle else VoiceState.Unavailable)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private var session = false
    private var stopRequested = false
    private var silentTimeouts = 0
    private var busyRetried = false
    private var recreated = false
    private var level = 0f
    private var partial = ""
    private var pending: Job? = null

    /** Mic tap with the permission granted: start continuous dictation. */
    fun start() {
        if (_state.value != VoiceState.Idle) return
        session = true
        stopRequested = false
        silentTimeouts = 0
        busyRetried = false
        recreated = false
        _events.tryEmit(VoiceEvent.Active(true))
        beginUtterance()
    }

    /** Mic tap while listening: the final result commits, then Idle (max 3 s). */
    fun stop() {
        if (!session) return
        stopRequested = true
        pending?.cancel()
        if (_state.value is VoiceState.Listening) {
            _state.value = VoiceState.Processing
            engine.stop()
            pending = scope.launch {
                delay(config.processingTimeoutMs)
                engine.cancel()
                finish()
            }
        } else {
            finish()
        }
    }

    /** ON_STOP, leaving the chat, a send or a session switch: stop at once and release. */
    fun cancel() {
        pending?.cancel()
        if (session) {
            engine.cancel()
            finish()
        }
        engine.destroy()
    }

    private fun beginUtterance() {
        pending = null
        partial = ""
        level = 0f
        _state.value = VoiceState.Listening(0f, "")
        _events.tryEmit(VoiceEvent.UtteranceStarted)
        if (!engine.start(listener)) {
            fail(VoiceError.Unavailable)
        }
    }

    private fun schedule(delayMs: Long) {
        _events.tryEmit(VoiceEvent.UtteranceEnded)
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            if (session && !stopRequested) beginUtterance()
        }
    }

    private fun finish() {
        val wasSession = session
        session = false
        stopRequested = false
        pending = null
        if (_state.value != VoiceState.Unavailable) _state.value = VoiceState.Idle
        if (wasSession) {
            _events.tryEmit(VoiceEvent.UtteranceEnded)
            _events.tryEmit(VoiceEvent.Active(false))
        }
    }

    private fun fail(error: VoiceError) {
        engine.cancel()
        finish()
        _events.tryEmit(VoiceEvent.Failed(error))
    }

    private val listener = object : SpeechListener {
        override fun onReady() = Unit

        override fun onRms(rmsDb: Float) {
            if (!session || stopRequested) return
            level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
            _state.value = VoiceState.Listening(level, partial)
        }

        override fun onPartial(text: String) {
            if (!session) return
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            partial = trimmed
            if (!stopRequested) _state.value = VoiceState.Listening(level, partial)
            _events.tryEmit(VoiceEvent.Transcript(trimmed))
        }

        override fun onResult(text: String?) {
            if (!session) return
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isNotEmpty()) {
                silentTimeouts = 0
                _events.tryEmit(VoiceEvent.Transcript(trimmed))
            }
            if (stopRequested) {
                pending?.cancel()
                finish()
            } else {
                schedule(config.restartDelayMs)
            }
        }

        override fun onFailure(failure: SpeechFailure) {
            if (!session) return
            if (stopRequested) {
                pending?.cancel()
                finish()
                return
            }
            when (failure) {
                SpeechFailure.NoMatch, SpeechFailure.SpeechTimeout -> {
                    if (partial.isEmpty()) silentTimeouts++ else silentTimeouts = 0
                    if (silentTimeouts >= config.maxSilentTimeouts) finish() else schedule(config.restartDelayMs)
                }
                SpeechFailure.Busy -> if (!busyRetried) {
                    busyRetried = true
                    schedule(config.busyRetryDelayMs)
                } else {
                    fail(VoiceError.Busy)
                }
                SpeechFailure.Audio -> fail(VoiceError.Audio)
                SpeechFailure.Network -> fail(VoiceError.Network)
                SpeechFailure.Permission -> fail(VoiceError.Permission)
                is SpeechFailure.Other -> if (!recreated) {
                    recreated = true
                    engine.destroy()
                    schedule(config.recreateDelayMs)
                } else {
                    fail(VoiceError.Generic(failure.name))
                }
            }
        }
    }
}

/**
 * The desktop's streaming insertion (`handleStreamingTranscriptionStart/Change/End`): the
 * live range is replaced by `leadingSpace + text + trailingSpace`; if anything else changed
 * the draft since the last update the range is dropped and later text of this utterance is
 * ignored.
 */
class VoiceInsertion {
    private data class Range(val start: Int, val end: Int, val expected: String)

    private var range: Range? = null

    val active: Boolean get() = range != null

    /** `handleStreamingTranscriptionStart`: the selection at the start of the utterance. */
    fun begin(text: String, selectionStart: Int, selectionEnd: Int) {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        range = Range(start, end, text)
    }

    /** `handleStreamingTranscriptionChange`: the new text and cursor, or null when stale. */
    fun apply(current: String, transcript: String): Pair<String, Int>? {
        val text = transcript.trim()
        val r = range ?: return null
        if (text.isEmpty()) return null
        if (current != r.expected) {
            range = null
            return null
        }
        val before = current.substring(0, r.start)
        val after = current.substring(r.end)
        val leading = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val trailing = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
        val insertion = leading + text + trailing
        val next = before + insertion + after
        val end = r.start + insertion.length
        range = Range(r.start, end, next)
        // Desktop: the cursor lands at the end of the inserted range (`nextEnd`).
        return next to end
    }

    /** `handleStreamingTranscriptionEnd`. */
    fun end() {
        range = null
    }
}

/**
 * C7.4 availability: `isRecognitionAvailable` (needs the `<queries>` entry); a false result
 * is re-checked once by creating a recogniser, because the system recogniser may still work.
 * Call [check] on the main thread.
 */
class SpeechAvailability(private val context: Context) {
    private var probed: Boolean? = null

    val permissionDeclared: Boolean
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        }.getOrDefault(false)

    fun check(): Boolean {
        if (!permissionDeclared) return false
        if (SpeechRecognizer.isRecognitionAvailable(context)) return true
        probed?.let { return it }
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context).destroy() }.isSuccess
        probed = created
        return created
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** The Android recogniser (C7.6 intent extras; main thread only). */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: SpeechListener): Boolean {
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also { recognizer = it }
            ?: return false
        r.setRecognitionListener(adapter(listener))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag())
        }
        return try {
            r.startListening(intent)
            true
        } catch (_: RuntimeException) {
            destroy()
            false
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    override fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun currentLanguageTag(): String {
        val locales = context.resources.configuration.locales
        return (if (locales.isEmpty) Locale.getDefault() else locales[0]).toLanguageTag()
    }

    private fun adapter(listener: SpeechListener) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = listener.onRms(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            listener.onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() })
        }

        override fun onError(error: Int) = listener.onFailure(mapError(error))
    }

    companion object {
        fun mapError(error: Int): SpeechFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> SpeechFailure.NoMatch
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.SpeechTimeout
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure.Busy
            SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.Audio
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.Permission
            SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.Other("ERROR_CLIENT")
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechFailure.Other("ERROR_LANGUAGE_NOT_SUPPORTED")
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure.Other("ERROR_LANGUAGE_UNAVAILABLE")
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Other("ERROR_TOO_MANY_REQUESTS")
            else -> SpeechFailure.Other("ERROR_$error")
        }
    }
}
