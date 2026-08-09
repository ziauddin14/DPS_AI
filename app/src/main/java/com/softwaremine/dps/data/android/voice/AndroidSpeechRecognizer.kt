package com.softwaremine.dps.data.android.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.voice.VoiceCaptureOutcome
import com.softwaremine.dps.domain.voice.VoiceRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * The Android [VoiceRecognizer] — Day 07's dedicated voice-input owner.
 *
 * ## Offline vs network — verified against official documentation, not assumed
 * `android.speech.SpeechRecognizer` has **two** distinct construction paths,
 * and only one of them is actually offline:
 *
 * - `SpeechRecognizer.createSpeechRecognizer(context)` binds to whichever
 *   recognition service is installed as the device default (on this device,
 *   and on most, the Google app). Whether *that* recognizes offline is a
 *   property of the installed service and the user's downloaded language
 *   packs, not something this app can verify or control.
 *   `RecognizerIntent.EXTRA_PREFER_OFFLINE` is documented as a **hint** the
 *   service *may* honour — not a guarantee, and this class never reports a
 *   result from this path as offline.
 * - `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` (API 31+) is
 *   genuinely on-device: no network round-trip, ever. Its actual
 *   availability is queried with `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`,
 *   which can still be `false` even on API 31+ if the on-device model has
 *   not been downloaded by the recognition service.
 *
 * [listen] prefers the on-device path whenever `isOnDeviceRecognitionAvailable`
 * reports it usable, and marks every [VoiceCaptureOutcome.FinalResult] with
 * whether it actually came from that path — see [VoiceCaptureOutcome.FinalResult.offlineRecognition].
 * On devices below API 31, or where on-device recognition is unavailable,
 * the default recognizer is used with the offline hint set, and results are
 * honestly reported as *not* verified offline.
 *
 * ## Threading — verified against official documentation
 * `developer.android.com/reference/android/speech/SpeechRecognizer` states
 * creation and every `RecognitionListener` callback must happen "from the
 * main application thread." [listen] therefore constructs and drives the
 * recognizer via [DispatcherProvider.main]; [kotlinx.coroutines.flow.callbackFlow]'s
 * `trySend`/`close` are channel operations and are safe to call from that
 * thread directly.
 *
 * ## Permission
 * This class does **not** request `RECORD_AUDIO` — that is
 * [com.softwaremine.dps.ai.voice.VoiceModeController]'s job, reusing the
 * exact [com.softwaremine.dps.domain.permission.PermissionManager] machinery
 * Day 05 built for tool permissions, rather than a second permission path.
 * A missing permission is still checked here defensively — belt-and-suspenders
 * against a revoke racing a request — and reported as
 * [VoiceCaptureOutcome.PermissionRequired] without ever touching the
 * platform recognizer.
 *
 * ## Privacy
 * No audio or transcript is written to disk or logged verbatim; only
 * `redact()`-safe diagnostics (booleans, counts) are logged. The recognizer
 * is destroyed the moment a session ends — see [listen]'s `awaitClose` —
 * so nothing keeps listening after its collector goes away.
 *
 * ## Dependencies
 * `android.speech.*`, `androidx.core.content.ContextCompat`,
 * [DispatcherProvider], [DpsLogger]. The only file in this app importing
 * `android.speech.SpeechRecognizer`/`RecognitionListener`.
 */
class AndroidSpeechRecognizer(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : VoiceRecognizer {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun listen(): Flow<VoiceCaptureOutcome> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(VoiceCaptureOutcome.PermissionRequired)
            close()
            return@callbackFlow
        }

        if (!isAvailable()) {
            trySend(
                VoiceCaptureOutcome.Unavailable(
                    "No speech recognition service is present on this device.",
                ),
            )
            close()
            return@callbackFlow
        }

        val preferOnDevice = apiLevel >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

        val recognizer = try {
            withContext(dispatchers.main) {
                if (preferOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }
        } catch (throwable: Throwable) {
            logger.w(TAG, "Could not create a speech recognizer", throwable)
            trySend(VoiceCaptureOutcome.Unavailable("Voice input could not start on this device."))
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceCaptureOutcome.Listening)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                logger.i(TAG, "Recognition error code=$error")
                trySend(mapError(error))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                trySend(
                    if (text.isNullOrEmpty()) {
                        VoiceCaptureOutcome.NoSpeech
                    } else {
                        VoiceCaptureOutcome.FinalResult(text, offlineRecognition = preferOnDevice)
                    },
                )
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    trySend(VoiceCaptureOutcome.PartialResult(text))
                }
            }
        }

        withContext(dispatchers.main) {
            recognizer.setRecognitionListener(listener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Only a hint, and only meaningful on the non-on-device path —
                // see the class doc for why a result from this path is never
                // reported as verified-offline regardless of this flag.
                if (!preferOnDevice) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                // No EXTRA_LANGUAGE is forced: Roman Urdu has no distinct
                // BCP-47 tag, and the device's own default recognition
                // language is a better guess than one this app cannot verify
                // is supported (Day 07 Phase 12 — do not promise perfect
                // Roman Urdu recognition).
            }

            runCatching { recognizer.startListening(intent) }
                .onFailure { failure ->
                    logger.w(TAG, "startListening threw", failure)
                    trySend(VoiceCaptureOutcome.Error("Couldn't start listening.", retryable = true))
                    close()
                }
        }

        logger.i(TAG, "Voice session started (onDeviceRecognition=$preferOnDevice)")

        awaitClose {
            logger.d(TAG, "Voice session ended; releasing recognizer.")
            // Not suspend, so a suspending withContext cannot be used here;
            // Handler.post is the non-suspending way to guarantee this runs on
            // the main thread the documentation requires, regardless of which
            // thread triggered cancellation.
            mainHandler.post {
                runCatching { recognizer.stopListening() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    /**
     * Maps a `SpeechRecognizer.ERROR_*` code to a [VoiceCaptureOutcome].
     *
     * Every code below is named in the official `SpeechRecognizer` reference.
     * `ERROR_CLIENT` has no documented meaning beyond "other client-side
     * error"; it is the closest code Android provides to a self-inflicted
     * stop and is treated as [VoiceCaptureOutcome.Cancelled] on that basis,
     * not because the documentation guarantees that mapping.
     */
    private fun mapError(errorCode: Int): VoiceCaptureOutcome = when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            VoiceCaptureOutcome.NoSpeech

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            VoiceCaptureOutcome.PermissionRequired

        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            VoiceCaptureOutcome.Error(
                "Voice input needs a network connection right now.",
                retryable = true,
            )

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            VoiceCaptureOutcome.Error("Voice input is busy. Try again in a moment.", retryable = true)

        SpeechRecognizer.ERROR_AUDIO ->
            VoiceCaptureOutcome.Error("Couldn't access the microphone.", retryable = true)

        SpeechRecognizer.ERROR_CLIENT ->
            VoiceCaptureOutcome.Cancelled

        else ->
            VoiceCaptureOutcome.Error("Voice recognition failed on this attempt.", retryable = true)
    }

    private companion object {
        const val TAG = "VoiceInput"
    }
}
