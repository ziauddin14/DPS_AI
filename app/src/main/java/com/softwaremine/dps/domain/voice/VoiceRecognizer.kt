package com.softwaremine.dps.domain.voice

import kotlinx.coroutines.flow.Flow

/**
 * Converts spoken audio into text.
 *
 * ## Purpose
 * The dedicated platform owner for voice input (Day 07 architecture rule
 * 10 — "Voice recognition must have a dedicated platform owner"). Everything
 * above this interface — [com.softwaremine.dps.ai.voice.VoiceModeController]
 * and the secretary pipeline it feeds — deals only in [VoiceCaptureOutcome]
 * and plain text.
 *
 * ## Voice becomes text, never a second AI pipeline
 * A [VoiceCaptureOutcome.FinalResult]'s text is hand ed to
 * [com.softwaremine.dps.ai.session.AiSessionManager.sendMessage] exactly as
 * if it had been typed. This interface has no notion of intents, tools, or
 * conversation — it only turns audio into a string (Day 07 architecture
 * rule 15).
 *
 * ## Dependencies
 * [VoiceCaptureOutcome] only. Implemented in `data/android/voice/` — the
 * only place that imports `android.speech.*` for input.
 */
interface VoiceRecognizer {

    /**
     * Starts one listening session and emits [VoiceCaptureOutcome]s until a
     * terminal one — see [isTerminal]. Collecting again after a terminal
     * outcome starts a fresh session.
     *
     * Cancelling collection stops audio capture and releases the recognizer
     * immediately; a session must never be left listening after its
     * collector is gone (Day 07 privacy requirement — release microphone
     * resources immediately after recognition).
     */
    fun listen(): Flow<VoiceCaptureOutcome>

    /** Whether this device can plausibly attempt recognition (permission and hardware aside). */
    fun isAvailable(): Boolean
}
