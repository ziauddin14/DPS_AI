package com.softwaremine.dps.domain.voice

/**
 * The visible state of DPS's voice conversation mode (Day 07 Phase 3).
 *
 * Owned by [com.softwaremine.dps.ai.voice.VoiceModeController] and rendered
 * directly by the chat UI, so the user is never left guessing whether DPS
 * heard them, is thinking, or is speaking — "the user should not have to
 * guess whether DPS heard them" is the Day 07 brief's own requirement.
 */
sealed interface VoiceMode {
    /** No voice interaction in progress. */
    data object Idle : VoiceMode

    /** Actively capturing speech. */
    data object Listening : VoiceMode

    /** Speech recognized; the secretary pipeline is producing a reply. */
    data object Processing : VoiceMode

    /** Speaking the assistant's reply aloud. */
    data object Speaking : VoiceMode

    /** Something went wrong; [message] is safe to show the user directly. */
    data class Error(val message: String) : VoiceMode
}
