package com.softwaremine.dps.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.softwaremine.dps.ai.session.AiSessionManager
import com.softwaremine.dps.ai.session.SessionState
import com.softwaremine.dps.ai.voice.VoiceModeController
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.conversation.ConversationState
import com.softwaremine.dps.domain.voice.VoiceMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the chat surface.
 *
 * ## Purpose
 * Adapts [AiSessionManager] to Compose. It is intentionally thin: it combines
 * two flows, forwards four user intents, and translates errors into display
 * text. All behaviour lives below it.
 *
 * ## Why it is this thin
 * Everything meaningful — the pipeline, the state machine, the runtime
 * selection — sits in `ai/` and `domain/`, which are pure Kotlin and testable on
 * the JVM without an emulator. That matters concretely on this project's
 * hardware (ADR-009), where emulator testing is not a realistic loop.
 *
 * A fat ViewModel would drag that logic into a class requiring Robolectric or a
 * device to test, so keeping it thin is not stylistic tidiness. It is what keeps
 * the AI layer verifiable.
 *
 * ## Responsibilities
 * - Expose a single [ChatUiState].
 * - Forward send / cancel / reset / start-session intents.
 * - Map [DpsError] to user-facing wording.
 *
 * ## Dependencies
 * [AiSessionManager] only.
 */
class ChatViewModel(
    private val sessionManager: AiSessionManager,
    private val voiceModeController: VoiceModeController,
) : ViewModel() {

    /**
     * The single state the UI renders.
     *
     * [SharingStarted.WhileSubscribed] with a five-second grace period: the
     * upstream stays active across configuration changes without restarting,
     * but is released when the screen is genuinely gone.
     */
    val uiState: StateFlow<ChatUiState> = combine(
        sessionManager.conversation,
        sessionManager.aiState,
        sessionManager.sessionState,
        voiceModeController.mode,
    ) { conversation, aiState, sessionState, voiceMode ->
        ChatUiState(
            conversation = conversation,
            aiState = aiState,
            sessionState = sessionState,
            voiceMode = voiceMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = ChatUiState(),
    )

    /**
     * Starts the AI session, loading the model.
     *
     * Called when the chat surface appears rather than at app start, which is
     * what keeps cold start inside its budget.
     */
    fun startSession() {
        viewModelScope.launch { sessionManager.startSession() }
    }

    /** Sends [text]. Progress is observed through [uiState]. */
    fun sendMessage(text: String) {
        sessionManager.sendMessage(text)
    }

    /** Stops the in-flight generation, keeping whatever text has arrived. */
    fun cancelGeneration() {
        sessionManager.cancelGeneration()
    }

    /** Clears the conversation, keeping the model resident. */
    fun newConversation() {
        sessionManager.resetConversation()
    }

    /** Starts one tap-to-talk voice turn (Day 07). Progress is observed through [uiState]. */
    fun startVoiceTurn() {
        voiceModeController.startListening()
    }

    /** Cancels the in-progress voice turn — stops listening or stops speaking. */
    fun cancelVoiceTurn() {
        voiceModeController.cancel()
    }

    /**
     * Factory.
     *
     * Manual rather than annotation-driven, consistent with ADR-006. Replacing
     * this with Hilt's `@HiltViewModel` later touches only this object.
     */
    class Factory(
        private val sessionManager: AiSessionManager,
        private val voiceModeController: VoiceModeController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return ChatViewModel(sessionManager, voiceModeController) as T
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Everything the chat surface needs to render.
 *
 * One state object rather than several flows, so the UI can never observe an
 * inconsistent combination — "generating" while the session reports inactive,
 * for instance.
 */
data class ChatUiState(
    val conversation: ConversationState = ConversationState.empty("initial"),
    val aiState: AiState = AiState.Idle,
    val sessionState: SessionState = SessionState.Inactive,
    val voiceMode: VoiceMode = VoiceMode.Idle,
) {
    /** `true` when the composer should accept input. */
    val canSend: Boolean
        get() = aiState is AiState.Ready && !conversation.isGenerating

    /**
     * `true` while a voice turn genuinely occupies the microphone or the
     * speaker — [VoiceMode.Error] is deliberately excluded: an error has
     * already ended the turn, so the mic control should immediately offer a
     * retry rather than keep showing "stop" for a turn that is over (found
     * via real-device testing — Day 07).
     */
    val isVoiceBusy: Boolean
        get() = voiceMode == VoiceMode.Listening || voiceMode == VoiceMode.Processing || voiceMode == VoiceMode.Speaking

    /** `true` when the microphone control should be tappable to *start* a turn. */
    val canStartVoice: Boolean
        get() = aiState is AiState.Ready && !conversation.isGenerating && !isVoiceBusy

    /**
     * A short line describing the current voice state, or `null` when voice
     * mode is idle — "the user should not have to guess whether DPS heard
     * them" (Day 07 Phase 3).
     */
    val voiceStatusLabel: String?
        get() = when (val mode = voiceMode) {
            VoiceMode.Idle -> null
            VoiceMode.Listening -> "Listening…"
            VoiceMode.Processing -> "Thinking…"
            VoiceMode.Speaking -> "Speaking…"
            is VoiceMode.Error -> mode.message
        }

    /** `true` while a response is streaming. */
    val isGenerating: Boolean get() = conversation.isGenerating

    /**
     * A short status line, or `null` when the AI is simply ready.
     *
     * Wording deliberately avoids exposing models, runtimes or internals
     * (AI Rules 1 and 2). "Waking up" rather than "loading GGUF into llama.cpp".
     */
    val statusLabel: String?
        get() = when {
            sessionState is SessionState.Suspended -> "Paused — tap to resume"
            aiState is AiState.CheckingModel -> "Checking…"
            aiState is AiState.ModelRequired -> "Setup required"
            aiState is AiState.PreparingModel -> "Preparing…"
            aiState is AiState.LoadingModel -> "Waking up…"
            aiState is AiState.Unavailable -> "Unavailable"
            aiState is AiState.Idle -> "Tap to start"
            else -> null
        }

    /** User-facing text for a blocking error, or `null`. */
    val errorLabel: String?
        get() = when (val state = aiState) {
            is AiState.Unavailable -> state.error.toUserMessage()
            else -> null
        }
}

/**
 * Maps a [DpsError] to text safe to show a user.
 *
 * [DpsError.message] is a developer diagnostic and must never be rendered
 * directly — AI Rules 1 and 2 forbid exposing internal architecture, and
 * "checksum mismatch for qwen2.5-1.5b-instruct-q4_k_m" tells a CEO nothing
 * actionable while revealing exactly what those rules exist to hide.
 *
 * Each message here names something the user can actually do.
 */
private fun DpsError.toUserMessage(): String = when (this) {
    is DpsError.Model.NotInstalled -> "DPS needs to finish setting up."
    is DpsError.Model.InsufficientStorage -> "Not enough free space on this device."
    is DpsError.Model.InsufficientMemory -> "This device doesn't have enough memory for DPS."
    is DpsError.Model.DownloadFailed -> "Setup couldn't finish. Check your connection."
    is DpsError.Model.ChecksumMismatch,
    is DpsError.Model.Corrupted,
    -> "Setup files are damaged. DPS will download them again."

    is DpsError.Runtime.Unavailable -> "DPS can't run on this device yet."
    is DpsError.Runtime.ContextOverflow -> "That conversation is too long. Start a new one."
    else -> "Something went wrong."
}
