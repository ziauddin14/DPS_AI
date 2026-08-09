package com.softwaremine.dps.data.android.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.voice.SpeechOutcome
import com.softwaremine.dps.domain.voice.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The Android [SpeechSynthesizer] — Day 07's dedicated voice-output owner.
 *
 * ## Offline behaviour
 * `android.speech.tts.TextToSpeech` synthesises using whichever engine is
 * set as the device default (Google Speech Services on this device) and its
 * already-downloaded voice data — there is no separate "offline mode" flag
 * to set, unlike [AndroidSpeechRecognizer]'s recognition path. Whether a
 * given utterance actually stays on-device depends on that voice data being
 * present, which is a device/engine property this class cannot force; it is
 * verified empirically in `DAY-07-COMPLETION.md`'s offline test rather than
 * assumed here.
 *
 * ## APIs used — verified against official documentation before use
 * | API | Purpose |
 * |---|---|
 * | `TextToSpeech(Context, OnInitListener)` | async engine init; callback reports `SUCCESS`/`ERROR` |
 * | `TextToSpeech.setLanguage(Locale)` | returns `LANG_AVAILABLE`/`LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED`/... |
 * | `TextToSpeech.speak(CharSequence, Int, Bundle?, String)` | the non-deprecated, API21+ overload used here |
 * | `UtteranceProgressListener.onStart/onDone/onError` | per-utterance completion, keyed by the utterance id passed to `speak` |
 * | `TextToSpeech.stop()` / `shutdown()` | interrupt / release |
 *
 * ## Lifecycle
 * Constructed once, in [com.softwaremine.dps.di.AiContainer] (process
 * lifetime), never per-Activity — `TextToSpeech`'s own documentation warns
 * against leaking an Activity context into it, and a process-scoped instance
 * survives Activity recreation for free without needing to re-initialise on
 * every configuration change.
 *
 * ## Dependencies
 * `android.speech.tts.*`, [DpsLogger]. The only file in this app importing
 * `android.speech.tts.TextToSpeech`.
 */
class AndroidTextToSpeech(
    private val context: Context,
    private val logger: DpsLogger,
) : SpeechSynthesizer {

    private val initMutex = Mutex()

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initialized: Boolean = false

    override fun isAvailable(): Boolean = initialized

    override suspend fun speak(text: String): SpeechOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SpeechOutcome.Completed

        val tts = ensureInitialized()
            ?: return SpeechOutcome.Unavailable("No text-to-speech engine is available on this device.")

        if (!selectUsableLanguage(tts)) {
            return SpeechOutcome.Unavailable("No usable voice language is installed on this device.")
        }

        val utteranceId = UUID.randomUUID().toString()

        return try {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (continuation.isActive) continuation.resume(SpeechOutcome.Completed)
                        }

                        @Deprecated("Superseded by onError(String, int); kept because some engines still call it.")
                        override fun onError(utteranceId: String?) {
                            if (continuation.isActive) {
                                continuation.resume(SpeechOutcome.Error("Speech failed."))
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            logger.w(TAG, "TTS error code=$errorCode")
                            if (continuation.isActive) {
                                continuation.resume(SpeechOutcome.Error("Speech failed."))
                            }
                        }
                    },
                )

                continuation.invokeOnCancellation { runCatching { tts.stop() } }

                // QUEUE_FLUSH: a new reply interrupts whatever DPS was still
                // saying rather than queuing behind it — the user is looking
                // at the newest answer, not the previous one.
                val dispatched = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (dispatched == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resume(SpeechOutcome.Error("Couldn't start speaking."))
                }
            }
        } catch (cancellation: CancellationException) {
            tts.stop()
            throw cancellation
        }
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
        initialized = false
    }

    /** Initialises the engine at most once; concurrent callers share the same attempt. */
    private suspend fun ensureInitialized(): TextToSpeech? {
        engine?.let { if (initialized) return it }

        return initMutex.withLock {
            engine?.let { if (initialized) return@withLock it }

            val status = suspendCancellableCoroutine { continuation ->
                // applicationContext, never the Activity — TextToSpeech's own
                // documentation warns a leaked Activity context here outlives
                // the screen that created it.
                val instance = TextToSpeech(context.applicationContext) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                engine = instance
            }

            initialized = status == TextToSpeech.SUCCESS
            if (!initialized) {
                logger.w(TAG, "TextToSpeech initialisation failed (status=$status)")
            }
            if (initialized) engine else null
        }
    }

    /**
     * Selects a speakable language, preferring the device default and
     * falling back to US English — the voice data virtually every Android
     * device ships with — rather than failing outright when the default
     * locale (e.g. `ur`) has no installed voice.
     */
    private fun selectUsableLanguage(tts: TextToSpeech): Boolean {
        val preferred = tts.setLanguage(Locale.getDefault())
        if (preferred.isUsable()) return true

        val fallback = tts.setLanguage(Locale.US)
        return fallback.isUsable()
    }

    private fun Int.isUsable(): Boolean = this != TextToSpeech.LANG_MISSING_DATA &&
        this != TextToSpeech.LANG_NOT_SUPPORTED

    private companion object {
        const val TAG = "VoiceOutput"
    }
}
