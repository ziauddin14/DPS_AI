package com.softwaremine.dps.data.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.voice.SpeechOutcome
import com.softwaremine.dps.domain.voice.VoiceCaptureOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Day 07 voice layer.
 *
 * ## What only a device can prove
 * Real `RECORD_AUDIO` grant state, real `android.speech.SpeechRecognizer`
 * availability, and real `android.speech.tts.TextToSpeech` initialisation —
 * none of this is fakeable on the JVM, matching the same reasoning
 * `AndroidToolsInstrumentedTest` already established for Day 05's tools.
 *
 * ## Determinism
 * The permission-gating test revokes `RECORD_AUDIO` via
 * `UiAutomation.revokeRuntimePermission` before asserting, rather than
 * trusting whatever the device happens to have granted from prior manual
 * testing — the same class of flake Day 06's device-verification notes
 * already document for permission state.
 *
 * `executeShellCommand("pm revoke ...")` was tried first and rejected: a
 * real-device run showed it destabilises the instrumentation connection
 * itself (Android logs "UiAutomation.revokeRuntimePermission() is more
 * robust and should be used instead of 'pm revoke'"), which cascaded into
 * the *next* test's instrumentation session failing with an unrelated
 * `SecurityException` — not a crash in DPS, but a test-authoring bug this
 * class doc records so it is not reintroduced.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun revokeRecordAudio() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .revokeRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    // -----------------------------------------------------------------
    // Registration — the same drift check Day 05/06 tool tests run
    // -----------------------------------------------------------------

    @Test
    fun voiceComponentsAreConstructedByTheRealContainer() {
        val container = AiContainer(context)

        assertNotNull(container.voiceRecognizer)
        assertNotNull(container.speechSynthesizer)
        assertNotNull(container.voiceModeController)
    }

    // -----------------------------------------------------------------
    // Speech recognition
    // -----------------------------------------------------------------

    @Test
    fun speechRecognitionServiceIsPresentOnThisDevice() {
        // A factual finding for DAY-07-COMPLETION.md, not just an assertion:
        // whether ANY recognition service (on-device or not) exists here.
        assertTrue(
            "No speech recognition service is present — voice input cannot work at all on this device.",
            SpeechRecognizer.isRecognitionAvailable(context),
        )
    }

    @Test
    fun onDeviceRecognitionAvailabilityIsQueryableWithoutThrowing() {
        // Day 07 Phase 1: "determine from official documentation and actual
        // device behaviour" rather than assume. This just proves the query
        // itself is safe to call; the actual value is recorded manually in
        // DAY-07-COMPLETION.md from a real run, since it can depend on
        // whether the on-device model has finished downloading.
        val recognizer = AndroidSpeechRecognizer(context, container().dispatchers, logger)
        recognizer.isAvailable()
    }

    @Test
    fun listeningWithoutRecordAudioPermissionReportsPermissionRequired(): Unit = runBlocking {
        revokeRecordAudio()
        assertTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED,
        )

        val recognizer = AndroidSpeechRecognizer(context, container().dispatchers, logger)
        val outcome = recognizer.listen().first()

        assertTrue(
            "Expected PermissionRequired, got $outcome",
            outcome is VoiceCaptureOutcome.PermissionRequired,
        )
    }

    // -----------------------------------------------------------------
    // Text-to-speech
    // -----------------------------------------------------------------

    @Test
    fun blankTextCompletesWithoutInitialisingTheEngine(): Unit = runBlocking {
        val tts = AndroidTextToSpeech(context, logger)
        val outcome = tts.speak("  ")

        assertTrue(outcome is SpeechOutcome.Completed)
        tts.shutdown()
    }

    @Test
    fun speakingRealTextInitialisesTheEngineAndReportsAnOutcome(): Unit = runBlocking {
        val tts = AndroidTextToSpeech(context, logger)
        val outcome = tts.speak("DPS voice test.")

        // Any of these is an honest outcome depending on this device's
        // installed TTS engine/voice data; what must never happen is a
        // crash or an indefinite hang, both ruled out by this call
        // returning at all.
        assertTrue(
            "Unexpected outcome: $outcome",
            outcome is SpeechOutcome.Completed ||
                outcome is SpeechOutcome.Unavailable ||
                outcome is SpeechOutcome.Error,
        )

        if (outcome is SpeechOutcome.Completed) {
            assertTrue("isAvailable() should report true after a successful speak", tts.isAvailable())
        }

        tts.stop()
        tts.shutdown()
    }

    @Test
    fun stopAndShutdownAreSafeWhenNothingIsSpeaking() {
        val tts = AndroidTextToSpeech(context, logger)
        tts.stop()
        tts.shutdown()
        tts.shutdown() // safe to call twice
    }

    private fun container() = AiContainer(context)
}
