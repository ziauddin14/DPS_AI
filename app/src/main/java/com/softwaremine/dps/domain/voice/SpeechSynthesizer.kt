package com.softwaremine.dps.domain.voice

/** One outcome of a text-to-speech attempt. */
sealed interface SpeechOutcome {
    /** Speech began. */
    data object Started : SpeechOutcome

    /** Speech finished normally. */
    data object Completed : SpeechOutcome

    /** No TTS engine, or no usable language/voice, is available. */
    data class Unavailable(val reason: String) : SpeechOutcome

    /** The engine reported a genuine error mid-speech. */
    data class Error(val message: String) : SpeechOutcome
}

/**
 * Speaks text aloud.
 *
 * ## Purpose
 * The dedicated platform owner for voice output (Day 07 architecture rule
 * 11). Consumes the *existing* assistant response text — see
 * [com.softwaremine.dps.ai.voice.VoiceModeController] — and never generates
 * its own wording. There is exactly one response generator in this app
 * ([com.softwaremine.dps.ai.intent.ToolResponseGenerator] for tool replies,
 * [com.softwaremine.dps.ai.parser.ResponseParser] for conversational ones);
 * this interface's only job is to say what they already produced (Day 07
 * architecture rule 16 — no second response generator).
 *
 * ## Dependencies
 * [SpeechOutcome] only. Implemented in `data/android/voice/` — the only
 * place that imports `android.speech.tts.*`.
 */
interface SpeechSynthesizer {

    /**
     * Speaks [text] aloud, suspending until speech finishes, fails, or is
     * interrupted by [stop].
     */
    suspend fun speak(text: String): SpeechOutcome

    /** Stops any speech in progress. Safe to call when idle. */
    fun stop()

    /** Whether this device can currently speak. */
    fun isAvailable(): Boolean

    /** Releases the underlying engine. Safe to call more than once. */
    fun shutdown()
}
