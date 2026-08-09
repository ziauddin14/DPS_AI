package com.softwaremine.dps.domain.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureOutcomeTest {

    @Test
    fun `Listening and PartialResult are not terminal`() {
        assertFalse(VoiceCaptureOutcome.Listening.isTerminal)
        assertFalse(VoiceCaptureOutcome.PartialResult("hel").isTerminal)
    }

    @Test
    fun `every other outcome is terminal`() {
        assertTrue(VoiceCaptureOutcome.FinalResult("hi", offlineRecognition = true).isTerminal)
        assertTrue(VoiceCaptureOutcome.NoSpeech.isTerminal)
        assertTrue(VoiceCaptureOutcome.PermissionRequired.isTerminal)
        assertTrue(VoiceCaptureOutcome.Unavailable("no service").isTerminal)
        assertTrue(VoiceCaptureOutcome.Error("failed", retryable = true).isTerminal)
        assertTrue(VoiceCaptureOutcome.Cancelled.isTerminal)
    }
}
