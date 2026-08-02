package com.softwaremine.dps.ai.session

import com.softwaremine.dps.ai.conversation.ConversationManager
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.PromptManager
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.logging.redact
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.TokenCounter
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.runtime.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates an AI session and drives the conversation pipeline.
 *
 * ## Purpose
 * The component the UI layer talks to. It composes the Phase G pipeline from
 * parts that each know nothing of the others:
 *
 * ```
 * User input
 *    ↓
 * ConversationManager.appendUserMessage()
 *    ↓
 * PromptManager.build()          ← chat template + context trimming
 *    ↓
 * AiEngine.generate()            ← RuntimeProvider → tokens
 *    ↓
 * ResponseParser.parsePartial()  ← strip control tokens
 *    ↓
 * ConversationManager.appendToken()
 *    ↓
 * StateFlow → ViewModel → Compose
 * ```
 *
 * Each stage is independently testable; this class owns only the sequencing and
 * the lifecycle around it.
 *
 * ## Responsibilities (Phase F)
 * - Start and end sessions, including model load and release.
 * - Reset conversation context.
 * - Release memory on demand.
 * - Report runtime health.
 * - Run generation as a cancellable stream.
 *
 * ## Explicit non-responsibilities
 * No secretary logic, no tools, no memory. It moves data between stages and
 * manages a lifecycle. When Day 03 adds tool calling, the loop below gains a
 * branch after parsing — nothing else in the pipeline changes shape.
 *
 * ## Concurrency
 * One generation at a time, tracked by [generationJob]. A second [sendMessage]
 * while one is in flight is rejected rather than queued: two concurrent
 * generations would interleave tokens into the same conversation, and queueing
 * would let a user stack up minutes of work they can no longer see or cancel.
 *
 * ## Dependencies
 * [AiEngine], [ConversationManager], [PromptManager], [ResponseParser],
 * [DispatcherProvider], [DpsLogger]. No Android framework.
 */
class AiSessionManager(
    private val engine: AiEngine,
    private val modelManager: ModelManager,
    private val conversationManager: ConversationManager,
    private val promptManager: PromptManager,
    private val responseParser: ResponseParser,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    private val scope: CoroutineScope,
    private val config: ModelConfig = ModelConfig.SECRETARY,
) {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Inactive)

    /** Observable session lifecycle. */
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /** The conversation being conducted. Observed directly by the UI. */
    val conversation = conversationManager.state

    /** The AI subsystem's state, forwarded from the engine. */
    val aiState: StateFlow<AiState> = engine.state

    private var generationJob: Job? = null

    /** Adapts the engine's tokenizer to the narrow interface prompt building needs. */
    private val tokenCounter = TokenCounter { text -> engine.tokenCount(text) }

    // -----------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------

    /**
     * Starts a session: initialises the engine and loads the model.
     *
     * This is where the 5–8 second model load is paid. It is triggered by the
     * user opening the AI surface rather than at app start, which is what keeps
     * cold start within its < 7 second budget (ADR-002).
     */
    suspend fun startSession(): DpsResult<Unit> {
        if (_sessionState.value is SessionState.Active) {
            logger.w(TAG, "startSession called while already active; ignoring.")
            return DpsResult.Failure(DpsError.Session.AlreadyActive)
        }

        _sessionState.value = SessionState.Starting

        val initialised = engine.initialize()
        if (initialised is DpsResult.Failure) {
            _sessionState.value = SessionState.Failed(initialised.error)
            return initialised
        }

        // The engine reports ModelRequired when no verified model is present.
        // Downloading is never begun here: it costs ~1.5 GB of the user's
        // bandwidth and requires their explicit consent first.
        val state = engine.state.value
        if (state is AiState.ModelRequired) {
            logger.i(TAG, "Session cannot start: model '${state.descriptor.id}' required.")
            _sessionState.value = SessionState.AwaitingModel(state.descriptor.id)
            return DpsResult.Ok
        }

        // Reuse an already-resident model rather than paying the load again —
        // this is the path taken when resuming from SessionState.Suspended.
        val alreadyLoaded = engine.activeModel
        if (alreadyLoaded != null) {
            _sessionState.value = SessionState.Active(alreadyLoaded.id)
            logger.i(TAG, "Session resumed with resident model '${alreadyLoaded.id}'.")
            return DpsResult.Ok
        }

        val descriptor = modelManager.defaultModel()
        val loaded = engine.loadModel(descriptor, config)
        if (loaded is DpsResult.Failure) {
            _sessionState.value = SessionState.Failed(loaded.error)
            return loaded
        }

        _sessionState.value = SessionState.Active(descriptor.id)
        logger.i(TAG, "Session active with model '${descriptor.id}'.")
        return DpsResult.Ok
    }

    /**
     * Ends the session: cancels any generation and releases the model.
     *
     * The conversation is deliberately *not* cleared. Ending a session is a
     * resource decision; discarding what the user was reading is a separate
     * decision that belongs to them ([resetConversation]).
     */
    suspend fun endSession() {
        cancelGeneration()
        engine.unloadModel()
        _sessionState.value = SessionState.Inactive
        logger.i(TAG, "Session ended.")
    }

    /**
     * Clears the conversation, keeping the model resident.
     *
     * The common "start a new chat" action. Keeping the model loaded means the
     * next message is answered immediately rather than after another 5–8 second
     * load.
     */
    fun resetConversation() {
        cancelGeneration()
        conversationManager.reset()
        logger.i(TAG, "Conversation reset.")
    }

    /**
     * Releases all AI memory while leaving the session record intact.
     *
     * Called from `onTrimMemory`. The session is marked [SessionState.Suspended]
     * rather than [SessionState.Inactive] so the UI can distinguish "the system
     * reclaimed memory, tap to resume" from "no session was started" — the
     * former is recoverable with one tap and no lost context.
     */
    suspend fun releaseMemory() {
        cancelGeneration()
        engine.unloadModel()
        val active = _sessionState.value as? SessionState.Active
        _sessionState.value = SessionState.Suspended(active?.modelId)
        logger.i(TAG, "Memory released; session suspended.")
    }

    /** Reports whether the AI is currently able to answer. */
    fun runtimeHealth(): RuntimeHealth {
        val state = engine.state.value
        return RuntimeHealth(
            isReady = state is AiState.Ready,
            modelId = engine.activeModel?.id,
            runtimeId = (state as? AiState.Ready)?.runtimeId?.label,
            status = when (state) {
                is AiState.Ready -> RuntimeStatus.Loaded(state.modelId, 0L)
                is AiState.LoadingModel -> RuntimeStatus.Loading(state.modelId)
                is AiState.Unavailable -> RuntimeStatus.Failed(state.error)
                else -> RuntimeStatus.Available
            },
        )
    }

    // -----------------------------------------------------------------
    // Conversation pipeline
    // -----------------------------------------------------------------

    /**
     * Sends a user message and streams the response into the conversation.
     *
     * Returns immediately; progress is observed through [conversation]. The
     * launched work is cancellable via [cancelGeneration].
     */
    fun sendMessage(text: String): DpsResult<Unit> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return DpsResult.Failure(DpsError.Unexpected("Cannot send an empty message."))
        }
        if (generationJob?.isActive == true) {
            return DpsResult.Failure(DpsError.Session.GenerationInProgress)
        }
        if (engine.state.value !is AiState.Ready) {
            return DpsResult.Failure(DpsError.Runtime.NotLoaded)
        }

        conversationManager.appendUserMessage(trimmed)
        generationJob = scope.launch(dispatchers.default) { runGeneration() }
        return DpsResult.Ok
    }

    /**
     * Cancels the in-flight generation.
     *
     * Cancellation propagates through the flow into the runtime, which stops
     * native work. That matters beyond responsiveness: a generation left
     * running after the user stopped reading burns battery producing tokens
     * nobody will see.
     */
    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        conversationManager.clearGenerating()
    }

    /** Runs one full turn of the pipeline. */
    private suspend fun runGeneration() {
        val descriptor = engine.activeModel ?: run {
            logger.e(TAG, "Generation requested with no active model.")
            return
        }

        val request = promptManager.build(
            conversation = conversationManager.current,
            descriptor = descriptor,
            config = config,
            tokenCounter = tokenCounter,
        )

        if (request is DpsResult.Failure) {
            logger.e(TAG, "Prompt build failed: ${request.error.message}")
            val placeholder = conversationManager.beginAssistantMessage()
            conversationManager.failAssistantMessage(placeholder.id, request.error)
            return
        }

        val completionRequest = (request as DpsResult.Success).value
        val assistantMessage = conversationManager.beginAssistantMessage()

        // Raw tokens are accumulated here rather than in ConversationManager
        // because ResponseParser needs the complete raw string to withhold
        // partial control tokens. One accumulator, one source of truth.
        val raw = StringBuilder()
        var terminated = false

        engine.generate(completionRequest).collect { chunk ->
            when (chunk) {
                is CompletionChunk.Token -> {
                    raw.append(chunk.text)
                    val parsed = responseParser.parsePartial(
                        rawSoFar = raw.toString(),
                        format = descriptor.promptFormat,
                    )
                    conversationManager.appendToken(assistantMessage.id, parsed.text)
                }

                is CompletionChunk.Completed -> {
                    terminated = true
                    val parsed = responseParser.parse(
                        raw = chunk.completion.text.ifEmpty { raw.toString() },
                        format = descriptor.promptFormat,
                    )
                    conversationManager.completeAssistantMessage(
                        messageId = assistantMessage.id,
                        text = parsed.text,
                    )
                    logger.i(
                        TAG,
                        "Generated ${redact(parsed.text)} in " +
                            "${chunk.completion.durationMillis}ms " +
                            "(${"%.1f".format(chunk.completion.tokensPerSecond)} tok/s, " +
                            "finish=${chunk.completion.finishReason})",
                    )
                }

                is CompletionChunk.Failed -> {
                    terminated = true
                    logger.e(TAG, "Generation failed: ${chunk.error.message}")
                    if (raw.isEmpty()) {
                        // Nothing was produced, so an empty failed bubble would
                        // be noise. Remove the placeholder entirely.
                        conversationManager.removeMessage(assistantMessage.id)
                        conversationManager.clearGenerating()
                    } else {
                        conversationManager.failAssistantMessage(
                            assistantMessage.id,
                            chunk.error,
                        )
                    }
                }
            }
        }

        // Defensive: a runtime that ends its stream without a terminal chunk
        // would otherwise leave the message stuck in Streaming forever, which
        // presents as a permanently frozen typing indicator.
        if (!terminated) {
            val parsed = responseParser.parse(raw.toString(), descriptor.promptFormat)
            conversationManager.completeAssistantMessage(assistantMessage.id, parsed.text)
            logger.w(TAG, "Stream ended without a terminal chunk; completed defensively.")
        }
    }

    private companion object {
        const val TAG = "AiSessionManager"
    }
}

/**
 * Lifecycle state of an AI session.
 *
 * Distinct from [AiState]: that describes whether the *engine* can run, this
 * describes whether the *user* has an active working session. The pair
 * "engine ready, session inactive" is normal and meaningful — it is the state
 * after the app starts but before the user opens the chat surface.
 */
sealed interface SessionState {

    /** No session. The state at app start. */
    data object Inactive : SessionState

    /** Starting up; the model is being loaded. */
    data object Starting : SessionState

    /** Running, with [modelId] resident. */
    data class Active(val modelId: String) : SessionState

    /** Blocked until the user consents to installing a model. */
    data class AwaitingModel(val modelId: String) : SessionState

    /**
     * Memory was reclaimed under system pressure. Recoverable in one tap, with
     * conversation context intact.
     */
    data class Suspended(val modelId: String?) : SessionState

    /** The session could not start. */
    data class Failed(val error: DpsError) : SessionState
}

/** A point-in-time health snapshot, for diagnostics and settings UI. */
data class RuntimeHealth(
    val isReady: Boolean,
    val modelId: String?,
    val runtimeId: String?,
    val status: RuntimeStatus,
)
