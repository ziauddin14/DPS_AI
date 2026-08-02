package com.softwaremine.dps.domain.ai

import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The AI subsystem's single entry point.
 *
 * ## Purpose
 * Everything above this interface — session management, the conversation
 * pipeline, ViewModels, and every feature built from Day 03 onward — depends on
 * [AiEngine] and on nothing beneath it. It is the line past which the rest of
 * the application cannot see.
 *
 * Concretely, no caller of this interface knows:
 * - which runtime is in use (Ollama, llama.cpp, or something not yet written);
 * - which model is loaded, beyond its id;
 * - that models are files, that files have checksums, or that JNI exists.
 *
 * That opacity is the entire point. `offliceLLM_guide.md` states that the model
 * is not the product and the workflow around it is; this interface is where
 * that principle stops being a slogan and becomes a compile-time boundary.
 *
 * ## Responsibilities
 * - Own model lifecycle: select, load, unload.
 * - Expose a single coherent [AiState].
 * - Generate completions as a stream.
 * - Count tokens so callers can respect the context window.
 * - Release resources under memory pressure.
 *
 * ## Explicit non-responsibilities
 * [AiEngine] knows nothing about conversations, roles, chat templates, memory,
 * or the secretary domain. It receives a rendered prompt and returns tokens.
 * Conversation semantics live one layer up, in
 * [com.softwaremine.dps.ai.session.AiSessionManager].
 *
 * This separation is what will let Day 03 add tool calling without touching the
 * runtime, and Day 05 add voice without touching either.
 *
 * ## Threading contract
 * All members are safe to call from any dispatcher. Implementations move work
 * to [com.softwaremine.dps.core.concurrency.DispatcherProvider.inference]
 * internally, so callers never need to think about it.
 *
 * ## Dependencies
 * `domain` + `core` types and coroutines.
 */
interface AiEngine {

    /** The subsystem's current state. The UI's single source of truth. */
    val state: StateFlow<AiState>

    /** The model currently loaded, or `null`. */
    val activeModel: ModelDescriptor?

    /**
     * Prepares the engine: selects a runtime and confirms a verified model is
     * present. Does **not** load the model.
     *
     * The separation from [loadModel] is what protects the < 7 s cold-start KPI.
     * Initialisation is cheap and can run at app start; loading costs 5–8 seconds
     * and is deferred until the AI is actually needed (ADR-002, lazy load).
     *
     * Ends in [AiState.Ready] only if a model is already loaded; otherwise
     * [AiState.ModelRequired] or an idle-but-initialised state.
     */
    suspend fun initialize(): DpsResult<Unit>

    /**
     * Loads [descriptor] into the runtime. Expect 5–8 seconds.
     *
     * The model's checksum is verified before loading. Per ADR-002 at most one
     * model is resident, so any previously loaded model is unloaded first.
     *
     * @param config sampling and resource parameters; defaults to
     *   [ModelConfig.SECRETARY].
     */
    suspend fun loadModel(
        descriptor: ModelDescriptor,
        config: ModelConfig = ModelConfig.SECRETARY,
    ): DpsResult<Unit>

    /**
     * Releases the loaded model and its native memory.
     *
     * Idempotent. Called on memory pressure and at session end.
     */
    suspend fun unloadModel(): DpsResult<Unit>

    /**
     * Generates a completion for a fully rendered prompt.
     *
     * The prompt must already have its chat template applied — see
     * [CompletionRequest] for why templating happens above the runtime seam.
     *
     * Returns a cold [Flow]; collection drives generation, and cancelling
     * collection cancels the underlying native work.
     */
    fun generate(request: CompletionRequest): Flow<CompletionChunk>

    /**
     * Convenience wrapper that collects [generate] into a single result.
     *
     * For callers that genuinely do not need incremental output. Prefer
     * [generate] anywhere a user is waiting: on-device generation is slow
     * enough that time-to-first-token is what the user perceives as speed.
     */
    suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion>

    /**
     * Counts tokens using the loaded model's real tokenizer.
     *
     * Fails with [com.softwaremine.dps.core.error.DpsError.Runtime.NotLoaded]
     * when no model is resident — token counts are model-specific and cannot be
     * approximated across tokenizers.
     */
    suspend fun tokenCount(text: String): DpsResult<Int>

    /**
     * Releases everything and returns to [AiState.Idle].
     *
     * Called from `onTrimMemory` and at process teardown. Must never throw:
     * memory-pressure callbacks run when the system is already unhappy, and
     * failing there converts a recoverable trim into a crash.
     */
    suspend fun shutdown()
}
