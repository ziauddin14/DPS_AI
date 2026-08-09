package com.softwaremine.dps.domain.voice

/**
 * One event from a voice-recognition session.
 *
 * ## Purpose
 * The deterministic vocabulary
 * [com.softwaremine.dps.data.android.voice.AndroidSpeechRecognizer] translates
 * every `android.speech.SpeechRecognizer` callback into. Nothing above this
 * type ever sees an Android recognizer error code or a raw result `Bundle` —
 * `domain/` stays free of `android.*` (ADR-005), and every caller gets an
 * exhaustive `when` instead of guessing what a bare integer meant.
 *
 * ## Never claim "I heard you" without meaning it
 * [FinalResult] is the only outcome that means recognition actually produced
 * usable text. Every other case is a distinct, named way of *not* producing
 * text, so a caller can never mistake "still warming up" for "heard nothing"
 * for "no microphone permission" — each has a different, correct recovery
 * action (Day 07 Phase 1 requirement).
 *
 * ## Dependencies
 * None. Pure Kotlin.
 */
sealed interface VoiceCaptureOutcome {

    /** The recognizer is actively capturing audio. Not terminal. */
    data object Listening : VoiceCaptureOutcome

    /**
     * An interim, possibly-incomplete transcript. Not terminal — more
     * [PartialResult]s or a terminal outcome follow. Informational only; no
     * caller should act on it as if it were final.
     */
    data class PartialResult(val text: String) : VoiceCaptureOutcome

    /**
     * Recognition finished with a usable transcript — the only outcome
     * handed to the secretary pipeline.
     *
     * @param offlineRecognition whether this result came from genuinely
     *   on-device recognition or the platform's default recognizer, which —
     *   see `AndroidSpeechRecognizer`'s own doc — is not guaranteed offline.
     *   Carried through so the UI and device-verification evidence can
     *   report which actually happened rather than assuming.
     */
    data class FinalResult(val text: String, val offlineRecognition: Boolean) : VoiceCaptureOutcome

    /** The recognizer listened and heard nothing usable. Terminal. */
    data object NoSpeech : VoiceCaptureOutcome

    /** `RECORD_AUDIO` is not granted. Terminal. */
    data object PermissionRequired : VoiceCaptureOutcome

    /**
     * No recognition service is available on this device at all — neither
     * on-device nor network-backed. Terminal.
     */
    data class Unavailable(val reason: String) : VoiceCaptureOutcome

    /** The recognizer reported a genuine error. Terminal. */
    data class Error(val message: String, val retryable: Boolean) : VoiceCaptureOutcome

    /** The user or caller stopped listening before a result arrived. Terminal. */
    data object Cancelled : VoiceCaptureOutcome
}

/** `true` for outcomes that end a listening session — no further events follow. */
val VoiceCaptureOutcome.isTerminal: Boolean
    get() = when (this) {
        is VoiceCaptureOutcome.Listening, is VoiceCaptureOutcome.PartialResult -> false
        is VoiceCaptureOutcome.FinalResult,
        is VoiceCaptureOutcome.NoSpeech,
        is VoiceCaptureOutcome.PermissionRequired,
        is VoiceCaptureOutcome.Unavailable,
        is VoiceCaptureOutcome.Error,
        is VoiceCaptureOutcome.Cancelled,
        -> true
    }
