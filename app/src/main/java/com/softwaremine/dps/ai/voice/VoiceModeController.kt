package com.softwaremine.dps.ai.voice

import com.softwaremine.dps.ai.session.AiSessionManager
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.voice.SpeechOutcome
import com.softwaremine.dps.domain.voice.SpeechSynthesizer
import com.softwaremine.dps.domain.voice.VoiceCaptureOutcome
import com.softwaremine.dps.domain.voice.VoiceMode
import com.softwaremine.dps.domain.voice.VoiceRecognizer
import com.softwaremine.dps.core.result.DpsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives one tap-to-talk voice turn: listen → recognize → hand the text to
 * the existing secretary pipeline → speak whatever it replies (Day 07).
 *
 * ## Voice becomes text, never a second AI pipeline (architecture rule 15)
 * A recognized [VoiceCaptureOutcome.FinalResult] is passed to
 * [AiSessionManager.sendMessage] — the exact same entry point the text
 * composer calls. This class never classifies an intent, never calls
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] itself, and
 * never builds a prompt. It only starts a turn and reads back the answer
 * the existing pipeline already produced.
 *
 * ## Finding "the reply" without a second response generator (rule 16)
 * There is no callback for "the assistant finished answering" on
 * [AiSessionManager] today, and adding one would mean touching Day 05/06
 * code for a Day 07 concern. Instead this class *observes*
 * [AiSessionManager.conversation] — already the UI's own source of truth —
 * for the newest assistant [com.softwaremine.dps.domain.conversation.ChatMessage]
 * to leave [MessageStatus.Streaming], and reads its `content` directly. That
 * content is exactly what [com.softwaremine.dps.ai.intent.ToolResponseGenerator]
 * or [com.softwaremine.dps.ai.parser.ResponseParser] already produced —
 * there is exactly one place a reply's wording comes from, and this class is
 * not it.
 *
 * ## Permission
 * Reuses [PermissionManager] — the same authority and the same live
 * `RECORD_AUDIO` request path Day 05 built for tool permissions — rather
 * than a second permission mechanism.
 *
 * ## Never a false "I heard you"
 * Every [VoiceCaptureOutcome] maps to a distinct [VoiceMode], including
 * every failure case; nothing here defaults silently to Idle without the
 * user having a reason why (see [runVoiceTurn]).
 *
 * ## TTS failure never blocks the text reply (Phase 2 requirement)
 * The assistant's reply is already visible in the transcript by the time
 * [SpeechSynthesizer.speak] is even called — speech failing or being
 * unavailable only ends the voice turn quietly; it never hides or retries
 * the text answer, which remains the source of truth.
 *
 * ## Dependencies
 * [VoiceRecognizer], [SpeechSynthesizer], [AiSessionManager], [PermissionManager].
 * No Android imports — those live in the two Day 07 platform-owner classes
 * this composes.
 */
class VoiceModeController(
    private val recognizer: VoiceRecognizer,
    private val synthesizer: SpeechSynthesizer,
    private val sessionManager: AiSessionManager,
    private val permissionManager: PermissionManager,
    private val scope: CoroutineScope,
    private val logger: DpsLogger,
    private val replyTimeoutMillis: Long = DEFAULT_REPLY_TIMEOUT_MILLIS,
) {

    private val _mode = MutableStateFlow<VoiceMode>(VoiceMode.Idle)
    val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

    private var turnJob: Job? = null

    /** The most recently spoken assistant message, so a later reply is not re-spoken. */
    private var lastSpokenMessageId: String? = null

    /**
     * Starts one voice turn: request permission if needed, listen, send what
     * was heard, speak the reply. A no-op while a turn is already running.
     */
    fun startListening() {
        if (turnJob?.isActive == true) {
            logger.d(TAG, "startListening() ignored; a voice turn is already in progress.")
            return
        }
        turnJob = scope.launch { runVoiceTurn() }
    }

    /**
     * Ends the current turn immediately — stops listening, stops any speech,
     * and returns to [VoiceMode.Idle]. Battery safety: nothing keeps the
     * microphone or the TTS engine active after this call.
     */
    fun cancel() {
        turnJob?.cancel()
        turnJob = null
        synthesizer.stop()
        _mode.value = VoiceMode.Idle
    }

    private suspend fun runVoiceTurn() {
        if (!ensureMicrophonePermission()) {
            _mode.value = VoiceMode.Error(
                "Microphone permission required. Allow microphone access to use voice.",
            )
            return
        }

        _mode.value = VoiceMode.Listening

        recognizer.listen().collect { outcome ->
            when (outcome) {
                is VoiceCaptureOutcome.Listening -> _mode.value = VoiceMode.Listening

                // Informational only — Phase 3 does not render an interim
                // transcript, but logging it is useful diagnostic evidence
                // without persisting or exposing full voice content.
                is VoiceCaptureOutcome.PartialResult -> Unit

                is VoiceCaptureOutcome.FinalResult -> handleRecognized(outcome)

                is VoiceCaptureOutcome.NoSpeech ->
                    _mode.value = VoiceMode.Error("I didn't catch that. Please try again.")

                is VoiceCaptureOutcome.PermissionRequired ->
                    _mode.value = VoiceMode.Error(
                        "Microphone permission required. Allow microphone access to use voice.",
                    )

                is VoiceCaptureOutcome.Unavailable ->
                    _mode.value = VoiceMode.Error(outcome.reason)

                is VoiceCaptureOutcome.Error ->
                    _mode.value = VoiceMode.Error(outcome.message)

                is VoiceCaptureOutcome.Cancelled -> _mode.value = VoiceMode.Idle
            }
        }
    }

    private suspend fun handleRecognized(result: VoiceCaptureOutcome.FinalResult) {
        logger.i(TAG, "Recognized speech (offline=${result.offlineRecognition}, chars=${result.text.length})")
        _mode.value = VoiceMode.Processing

        when (sessionManager.sendMessage(result.text)) {
            is DpsResult.Failure -> {
                _mode.value = VoiceMode.Error("DPS couldn't process that. Please try again.")
                return
            }
            is DpsResult.Success -> Unit
        }

        val reply = awaitFinalizedAssistantReply()
        if (reply.isNullOrBlank()) {
            _mode.value = VoiceMode.Idle
            return
        }

        _mode.value = VoiceMode.Speaking
        // Whatever happens here, the reply is already on screen — voice mode
        // simply ends afterward. See class doc: TTS failure must never hide
        // or block the existing text answer. The branches below exist only
        // to log which happened, not to change that outcome.
        when (val speechOutcome = synthesizer.speak(reply)) {
            is SpeechOutcome.Completed -> logger.d(TAG, "Reply spoken.")
            is SpeechOutcome.Started -> Unit
            is SpeechOutcome.Unavailable -> logger.i(TAG, "TTS unavailable: ${speechOutcome.reason}")
            is SpeechOutcome.Error -> logger.w(TAG, "TTS error: ${speechOutcome.message}")
        }
        _mode.value = VoiceMode.Idle
    }

    /**
     * Suspends until the newest assistant message finishes streaming, or
     * [replyTimeoutMillis] elapses (a suspended AI session under memory
     * pressure — Day 06 — could otherwise wait forever).
     */
    private suspend fun awaitFinalizedAssistantReply(): String? =
        withTimeoutOrNull(replyTimeoutMillis) {
            sessionManager.conversation
                .map { state -> state.messages.lastOrNull { it.role == MessageRole.ASSISTANT } }
                .filterNotNull()
                .first { message ->
                    message.id != lastSpokenMessageId && message.status !is MessageStatus.Streaming
                }
        }?.also { message -> lastSpokenMessageId = message.id }?.content

    private suspend fun ensureMicrophonePermission(): Boolean {
        if (permissionManager.state(DpsPermission.RECORD_AUDIO).isUsable) return true
        val result = permissionManager.request(setOf(DpsPermission.RECORD_AUDIO))
        return result[DpsPermission.RECORD_AUDIO]?.isUsable == true
    }

    private companion object {
        const val TAG = "VoiceMode"

        /**
         * On-device classification/generation has measured as long as ~100 s
         * under real device load (Day 06 verification). 3 minutes leaves
         * generous room above that while still recovering from a genuinely
         * stuck session rather than waiting forever.
         */
        const val DEFAULT_REPLY_TIMEOUT_MILLIS = 180_000L
    }
}
