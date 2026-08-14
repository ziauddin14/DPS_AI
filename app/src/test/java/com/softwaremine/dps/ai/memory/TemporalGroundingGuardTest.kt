package com.softwaremine.dps.ai.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of [TemporalGroundingGuard] (Day 08-E follow-up).
 *
 * ## What this proves
 * That a model-generated `raw_when` is only trusted when it is demonstrably
 * grounded in the user's own words — the exact gap the real-device
 * investigation found: `raw_when="kal shaam 7 baje"` reliably produced for
 * "Buy milk ka task bana do", reproduced with a fresh model reload and zero
 * session history, ruling out cache/session contamination as the cause.
 */
class TemporalGroundingGuardTest {

    private val guard = TemporalGroundingGuard()

    // -----------------------------------------------------------------
    // Must accept — a faithful extraction, even with dropped filler words
    // -----------------------------------------------------------------

    @Test
    fun `accepts an exact date phrase actually present`() {
        assertTrue(guard.isGrounded("20 August ko reminder lagao", "20 August"))
    }

    @Test
    fun `accepts a bare day word actually present`() {
        assertTrue(guard.isGrounded("kal reminder lagao", "kal"))
    }

    @Test
    fun `accepts raw_when that dropped the ko particle`() {
        // The user said "kal shaam ko 7 baje"; the model extracted
        // "kal shaam 7 baje" — every word still traces back to the message,
        // "ko" is simply a grammatical particle the model chose not to echo.
        assertTrue(guard.isGrounded("kal shaam ko 7 baje reminder lagao", "kal shaam 7 baje"))
    }

    @Test
    fun `accepts a night time phrase actually present`() {
        assertTrue(guard.isGrounded("kal raat 11 baje reminder lagao", "kal raat 11 baje"))
    }

    // -----------------------------------------------------------------
    // Must reject — the exact real-device hallucination pattern
    // -----------------------------------------------------------------

    @Test
    fun `rejects a fabricated phrase absent from a task message`() {
        assertFalse(guard.isGrounded("Buy milk ka task bana do", "kal shaam 7 baje"))
    }

    @Test
    fun `rejects a fabricated phrase absent from a conversational message`() {
        assertFalse(guard.isGrounded("Salam DPS, kya haal hai?", "kal shaam 7 baje"))
    }

    @Test
    fun `rejects a fabricated absolute date absent from the message`() {
        assertFalse(guard.isGrounded("Buy milk ka task bana do", "20 August"))
    }

    @Test
    fun `rejects a fabricated day word absent from a differently-worded task`() {
        assertFalse(guard.isGrounded("Meeting with Ali ka task bana do", "kal"))
    }

    // -----------------------------------------------------------------
    // Partial grounding is not enough — no credit for half-invented phrases
    // -----------------------------------------------------------------

    @Test
    fun `rejects when only part of raw_when is grounded`() {
        // "kal" was said; "raat 11 baje" was not — the whole quote is untrustworthy.
        assertFalse(guard.isGrounded("kal reminder lagao", "kal raat 11 baje"))
    }

    // -----------------------------------------------------------------
    // Punctuation, spacing, and case variations
    // -----------------------------------------------------------------

    @Test
    fun `is tolerant of punctuation`() {
        assertTrue(guard.isGrounded("20 August ko reminder lagao.", "20 August"))
        assertTrue(guard.isGrounded("kal, shaam 7 baje reminder lagao!", "kal shaam 7 baje"))
    }

    @Test
    fun `is tolerant of extra whitespace`() {
        assertTrue(guard.isGrounded("kal   shaam    7   baje  reminder lagao", "kal shaam 7 baje"))
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(guard.isGrounded("KAL SHAAM 7 BAJE REMINDER LAGAO", "kal shaam 7 baje"))
        assertTrue(guard.isGrounded("kal shaam 7 baje reminder lagao", "KAL SHAAM 7 BAJE"))
    }

    // -----------------------------------------------------------------
    // Fail closed on degenerate input
    // -----------------------------------------------------------------

    @Test
    fun `rejects a blank raw_when`() {
        assertFalse(guard.isGrounded("kal shaam 7 baje reminder lagao", ""))
        assertFalse(guard.isGrounded("kal shaam 7 baje reminder lagao", "   "))
    }

    @Test
    fun `rejects raw_when against a blank user message`() {
        assertFalse(guard.isGrounded("", "kal shaam 7 baje"))
    }
}
