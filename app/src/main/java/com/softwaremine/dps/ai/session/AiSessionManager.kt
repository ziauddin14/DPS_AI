package com.softwaremine.dps.ai.session

import com.softwaremine.dps.ai.conversation.ConversationManager
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.parser.ResponseParser
import com.softwaremine.dps.ai.prompt.PromptManager
import com.softwaremine.dps.ai.secretary.SecretaryOrchestrator
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.logging.redact
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.TokenCounter
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.conversation.MessageStatus
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelManager
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.runtime.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
 * ## Routing (Day 05 Phase E)
 * Every message is offered to [SecretaryOrchestrator] first. A tool outcome —
 * an action taken, a clarifying question, a permission request — is appended
 * to the conversation directly, without a model generation pass at all
 * (Phase D's "one inference pass" decision extends unchanged: classification
 * *is* that one pass). Only when the orchestrator decides a message is
 * ordinary conversation does [runGeneration] run, exactly as it always has —
 * Day 02–04's streaming chat pipeline is untouched below this routing point.
 *
 * A [ToolOrchestrator.Outcome.NeedsPermission] additionally drives a real,
 * live permission request through [permissionManager] before the reply is
 * shown to have been resolved one way or the other — see [deliver].
 *
 * ## Explicit non-responsibilities
 * No intent classification, no tool selection, no memory of its own — all of
 * that is [SecretaryOrchestrator]'s. This class only decides *whether* to ask
 * it, and what to do with what it returns.
 *
 * ## Concurrency
 * One generation (or tool turn) at a time, tracked by [generationJob]. A
 * second [sendMessage] while one is in flight is rejected rather than queued:
 * two concurrent turns would interleave into the same conversation, and
 * queueing would let a user stack up minutes of work they can no longer see or
 * cancel.
 *
 * ## Dependencies
 * [AiEngine], [ConversationManager], [PromptManager], [ResponseParser],
 * [SecretaryOrchestrator], [PermissionManager], [DispatcherProvider],
 * [DpsLogger]. No Android framework.
 */
class AiSessionManager(
    private val engine: AiEngine,
    private val modelManager: ModelManager,
    private val conversationManager: ConversationManager,
    private val promptManager: PromptManager,
    private val responseParser: ResponseParser,
    private val secretaryOrchestrator: SecretaryOrchestrator,
    private val permissionManager: PermissionManager,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
    private val scope: CoroutineScope,
    private val config: ModelConfig = ModelConfig.SECRETARY,
    /**
     * Inactivity limit before a generation is treated as stalled (Phase E).
     * Injectable so tests can drive the timeout path without waiting a minute.
     */
    private val stallTimeoutMillis: Long = DEFAULT_STALL_TIMEOUT_MILLIS,
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
        secretaryOrchestrator.reset()
        logger.i(TAG, "Conversation reset.")
    }

    /**
     * Releases all AI memory while leaving the session record intact.
     *
     * Called from `onTrimMemory`. The session is marked [SessionState.Suspended]
     * rather than [SessionState.Inactive] so the UI can distinguish "the system
     * reclaimed memory, tap to resume" from "no session was started" — the
     * former is recoverable with one tap and no lost context.
     *
     * ## Why an in-flight turn gets a visible trace (Day 07)
     * Day 06's on-device verification found that a turn cut off here left
     * *nothing* on screen: no error, no stuck bubble the user could point
     * to, just a message that was sent and never answered. That reads as a
     * frozen or dead app, not the deliberate, one-tap-recoverable pause this
     * actually is — Phase 7's "the user should not encounter... a dead
     * session" requirement. [notifyInterruptedTurn] is the fix: it runs only
     * when a generation was genuinely in flight, so an idle app backgrounding
     * normally is unaffected.
     */
    suspend fun releaseMemory() {
        val wasGenerating = generationJob?.isActive == true
        cancelGeneration()
        engine.unloadModel()
        val active = _sessionState.value as? SessionState.Active
        _sessionState.value = SessionState.Suspended(active?.modelId)
        logger.i(TAG, "Memory released; session suspended.")
        if (wasGenerating) notifyInterruptedTurn()
    }

    /**
     * Leaves a visible trace of a turn [releaseMemory] cut off mid-flight.
     *
     * A streaming assistant bubble is marked failed in place, exactly like a
     * stall timeout already does. A turn interrupted *before* any bubble
     * existed — cancelled during classification, which posts nothing until
     * it has an outcome — instead gets a short assistant note, so the user's
     * message is never left silently unanswered.
     */
    private fun notifyInterruptedTurn() {
        val lastMessage = conversationManager.current.messages.lastOrNull() ?: return
        when {
            lastMessage.role == MessageRole.ASSISTANT && lastMessage.status is MessageStatus.Streaming ->
                conversationManager.failAssistantMessage(lastMessage.id, DpsError.Session.Interrupted)

            lastMessage.role == MessageRole.USER ->
                postAssistantMessage("Paused to free up memory before answering. Please try again.")

            else -> Unit
        }
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
        generationJob = scope.launch(dispatchers.default) { routeMessage(trimmed) }
        return DpsResult.Ok
    }

    /**
     * Offers [text] to [secretaryOrchestrator] first; falls back to the
     * ordinary streaming chat pipeline only when it decides the message is not
     * an action, and even then only when that same classification pass did
     * not already produce a usable reply (Day 08-B).
     */
    private suspend fun routeMessage(text: String) {
        when (val outcome = secretaryOrchestrator.handle(text, recentContext())) {
            is ToolOrchestrator.Outcome.Conversational -> {
                val reply = outcome.replyText
                if (reply != null) {
                    // The classification pass already answered — this is the
                    // whole point of Day 08-B: skip the second, streaming
                    // generation pass entirely rather than discard a working
                    // answer and redo the work from scratch.
                    postAssistantMessage(reply)
                } else {
                    runGeneration()
                }
            }
            else -> deliver(outcome)
        }
    }

    /**
     * The last exchange, rendered as plain text for the classification prompt
     * (Day 08-B — see [com.softwaremine.dps.ai.intent.IntentPromptBuilder.build]).
     *
     * Deliberately small — the last user/assistant pair, not the full history
     * budget [PromptManager] uses for real generation. This exists only to
     * give a same-pass conversational reply enough context for an immediate
     * follow-up to make sense; it is not a replacement for the full pipeline,
     * which still runs whenever the classification pass does not produce a
     * reply itself.
     */
    private fun recentContext(): String? {
        // The current user turn was already appended by sendMessage() before
        // this coroutine was launched, so it is always the last entry here —
        // drop it to leave only what came *before* this message.
        val prior = conversationManager.current.messages.dropLast(1)
            .filter { it.role != MessageRole.SYSTEM && it.isPromptEligible }
        if (prior.isEmpty()) return null

        return buildString {
            for (message in prior.takeLast(RECENT_CONTEXT_TURNS)) {
                val label = if (message.role == MessageRole.USER) "User" else "DPS"
                append(label).append(": ").append(message.content).append('\n')
            }
        }
    }

    /**
     * Appends a tool outcome's reply as a complete (non-streamed) assistant
     * message, then — for exactly one round — actually asks the OS for a
     * needed permission and shows whatever resuming produces.
     *
     * @param allowPermissionRequest bounds the recursion to one retry. A
     *   second [ToolOrchestrator.Outcome.NeedsPermission] means the permission
     *   is still denied after the live dialog resolved (or was permanently
     *   denied and the OS showed nothing at all) — repeating the request
     *   automatically at that point would loop forever without the user
     *   having asked again, so it is shown instead and left there.
     */
    private suspend fun deliver(
        outcome: ToolOrchestrator.Outcome,
        allowPermissionRequest: Boolean = true,
    ) {
        val reply = replyText(outcome) ?: return
        postAssistantMessage(reply)

        if (outcome is ToolOrchestrator.Outcome.NeedsPermission && allowPermissionRequest) {
            permissionManager.request(outcome.result.permissions.toSet())
            val resumed = secretaryOrchestrator.onPermissionResult() ?: return
            deliver(resumed, allowPermissionRequest = false)
        }
    }

    private fun replyText(outcome: ToolOrchestrator.Outcome): String? = when (outcome) {
        is ToolOrchestrator.Outcome.Handled -> outcome.reply
        is ToolOrchestrator.Outcome.Clarify -> outcome.question
        is ToolOrchestrator.Outcome.NeedsPermission -> outcome.reply
        // Never reached: routeMessage only calls deliver() for a non-Conversational
        // outcome, but the branch must exist for this `when` to be exhaustive.
        is ToolOrchestrator.Outcome.Conversational -> null
    }

    /** Posts [text] as a complete assistant turn — no streaming, the text is already final. */
    private fun postAssistantMessage(text: String) {
        val message = conversationManager.beginAssistantMessage()
        conversationManager.completeAssistantMessage(message.id, text)
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

        // --- stall watchdog (Day 04, Phase E) ---
        //
        // Measures inactivity *between* tokens rather than total duration. A
        // total cap is the obvious design and the wrong one: on-device
        // generation of a long answer legitimately takes tens of seconds, so a
        // duration limit aborts healthy work. What actually indicates a wedged
        // native runtime is silence.
        //
        // The watchdog cancels the enclosing scope, which propagates into the
        // flow and through awaitClose to LlamaCppBridge.cancel, stopping native
        // work rather than leaving it running behind a dead collector.
        var lastChunkAtMillis = System.currentTimeMillis()
        var stalled = false

        try {
            kotlinx.coroutines.coroutineScope {
                val watchdog = launch {
                    while (true) {
                        kotlinx.coroutines.delay(STALL_POLL_INTERVAL_MILLIS)
                        val idle = System.currentTimeMillis() - lastChunkAtMillis
                        if (idle > stallTimeoutMillis) {
                            stalled = true
                            logger.e(TAG, "Generation stalled for ${idle}ms; cancelling.")
                            this@coroutineScope.cancel()
                        }
                    }
                }

                engine.generate(completionRequest).collect { chunk ->
                    lastChunkAtMillis = System.currentTimeMillis()
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

                // Generation finished on its own; stand the watchdog down so it
                // does not hold the scope open.
                watchdog.cancel()
            }
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            if (!stalled) {
                // A genuine cancellation — the user pressed Stop, or the scope
                // was torn down. Propagate so structured concurrency behaves.
                throw cancellation
            }

            val idle = System.currentTimeMillis() - lastChunkAtMillis
            terminated = true
            val error = DpsError.Runtime.Timeout(
                idleMillis = idle,
                limitMillis = stallTimeoutMillis,
            )
            if (raw.isEmpty()) {
                conversationManager.removeMessage(assistantMessage.id)
                conversationManager.clearGenerating()
            } else {
                // Partial text is kept. A stall after 200 of 300 tokens still
                // leaves the user something they have already read.
                conversationManager.failAssistantMessage(assistantMessage.id, error)
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

        /**
         * Messages kept in [recentContext] — one exchange (Day 08-B).
         *
         * Deliberately small: this rides along on *every* classification
         * call, tool requests included, so it must stay cheap. See
         * [recentContext]'s own doc for why a short window is an acceptable
         * trade rather than a compromise.
         */
        const val RECENT_CONTEXT_TURNS = 2

        /**
         * How often the watchdog checks for silence.
         *
         * Cheap enough to poll frequently, coarse enough not to wake the CPU
         * needlessly during a healthy generation.
         */
        const val STALL_POLL_INTERVAL_MILLIS = 1_000L

        /**
         * Default inactivity limit before a generation is declared stalled.
         *
         * Sized against measured device behaviour rather than guessed: on the
         * reference hardware the *slowest* legitimate gap between tokens is the
         * first one, which includes prompt ingestion. 60 s leaves generous room
         * above that while still catching a wedged runtime long before a user
         * would give up and kill the app.
         */
        const val DEFAULT_STALL_TIMEOUT_MILLIS = 60_000L
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
