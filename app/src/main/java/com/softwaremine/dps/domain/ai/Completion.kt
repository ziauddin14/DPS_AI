package com.softwaremine.dps.domain.ai

import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.domain.model.ModelConfig

/**
 * A single request for text generation.
 *
 * ## Purpose
 * The unit of work handed to a [com.softwaremine.dps.domain.runtime.RuntimeProvider].
 *
 * ## Why [prompt] is a rendered string, not a list of messages
 * This is deliberate and load-bearing for ADR-001's central claim.
 *
 * The obvious design is `List<ChatMessage>`, letting each runtime apply its own
 * chat template. That is exactly what must be avoided: Ollama would apply the
 * template baked into its model manifest while llama.cpp applied ours, and the
 * two runtimes would silently receive *different prompts*. Behaviour would then
 * diverge between development and production in a way that is nearly impossible
 * to attribute — the classic "works on my machine" bug, except the machine is a
 * dev laptop and the failure is subtly worse answers in the field.
 *
 * By rendering the template once, above the runtime seam (in
 * [com.softwaremine.dps.ai.prompt.PromptManager]), both runtimes are guaranteed
 * byte-identical input. Ollama is therefore driven through `/api/generate` with
 * `raw = true`, which bypasses its own templating entirely.
 *
 * ## Dependencies
 * [ModelConfig]. Pure Kotlin.
 */
data class CompletionRequest(

    /**
     * The fully rendered prompt, with the model's chat template already applied.
     * Runtimes must pass this through verbatim and apply no templating.
     */
    val prompt: String,

    /** Sampling and resource parameters for this request. */
    val config: ModelConfig,

    /**
     * Sequences that end generation, from
     * [com.softwaremine.dps.domain.model.ModelDescriptor.stopSequences].
     */
    val stopSequences: List<String> = emptyList(),
)

/**
 * One event in a generation stream.
 *
 * ## Purpose
 * Streaming is the primitive in this architecture, not an enhancement layered
 * on later (ADR-004). A blocking call is trivially derived from a stream; the
 * reverse requires rewriting every layer. Since Phase F explicitly requires
 * "future streaming support", building non-streaming first would guarantee that
 * rewrite.
 *
 * It also matters for the product: `offliceLLM_guide.md` targets voice response
 * beginning within 2–3 seconds. On-device generation of a full paragraph takes
 * longer than that, so the first *token* must be what starts the clock. Only a
 * streaming pipeline can deliver it.
 *
 * ## Why failure is a chunk rather than a thrown exception
 * A `Flow` that throws terminates abruptly and discards tokens already emitted.
 * When generation fails at token 200 of 300, the user should keep the partial
 * answer and see that it was cut short — not watch it vanish. Modelling failure
 * as a terminal *value* preserves what was produced.
 *
 * ## Dependencies
 * [DpsError]. Pure Kotlin.
 */
sealed interface CompletionChunk {

    /** A fragment of generated text. Emitted many times per response. */
    data class Token(val text: String) : CompletionChunk

    /** Terminal success. Always the last element when generation completes. */
    data class Completed(val completion: AiCompletion) : CompletionChunk

    /** Terminal failure. Tokens emitted before this remain valid. */
    data class Failed(val error: DpsError) : CompletionChunk
}

/**
 * The finished result of a generation.
 *
 * [text] is the full concatenation of every [CompletionChunk.Token] emitted, so
 * callers that ignored the stream still receive the complete response.
 */
data class AiCompletion(
    val text: String,
    val finishReason: FinishReason,
    val usage: TokenUsage,
    val durationMillis: Long,
) {
    /**
     * Tokens per second — the headline performance metric against the
     * < 2 second response KPI. Measured, never estimated.
     */
    val tokensPerSecond: Double
        get() = if (durationMillis <= 0) 0.0
        else usage.completionTokens * MILLIS_PER_SECOND / durationMillis

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Why generation stopped.
 *
 * Worth inspecting rather than ignoring: [MAX_TOKENS] means the response was
 * truncated mid-thought, which for a secretary confirming a meeting time is a
 * correctness problem, not a cosmetic one.
 */
enum class FinishReason {
    /** The model emitted its end-of-turn token. The normal, healthy case. */
    END_OF_TURN,

    /** A configured stop sequence was hit. */
    STOP_SEQUENCE,

    /** [ModelConfig.maxOutputTokens] was reached. The response is truncated. */
    MAX_TOKENS,

    /** The caller cancelled, e.g. the user pressed Stop. */
    CANCELLED,
}

/**
 * Token accounting for one generation.
 *
 * [promptTokens] is what drives context-window management: when it approaches
 * [ModelConfig.contextLength], history must be trimmed before the next turn.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    companion object {
        val EMPTY = TokenUsage(promptTokens = 0, completionTokens = 0)
    }
}
