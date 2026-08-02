package com.softwaremine.dps.domain.model

/**
 * Runtime and sampling parameters for a loaded model.
 *
 * ## Purpose
 * Separates *how a model is run* from *which model is run* ([ModelDescriptor]).
 * The same artifact may be loaded with different configurations — a short
 * context for a quick reply, a longer one for summarisation later — without
 * touching the descriptor.
 *
 * ## Responsibilities
 * - Carry sampling parameters ([temperature], [topP], [topK], [repeatPenalty]).
 * - Carry runtime resource parameters ([contextLength], [threadCount], [gpuLayers]).
 * - Provide a default tuned for the executive-secretary role.
 *
 * ## Future extensions
 * Grammar-constrained sampling (GBNF) on Day 03: forcing tool calls to be
 * syntactically valid JSON at the sampler level is far more reliable on a 1.5B
 * model than asking politely in the prompt and parsing hopefully.
 *
 * ## Dependencies
 * None. Pure Kotlin.
 */
data class ModelConfig(

    /**
     * Context window in tokens.
     *
     * Must not exceed [ModelDescriptor.contextLength]. Directly drives KV-cache
     * memory: on a 3 GB budget (ADR-002) this is the main lever available.
     */
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,

    /** Upper bound on tokens generated per response. */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,

    /**
     * Sampling temperature. Lower is more deterministic.
     *
     * See [SECRETARY] for why the default is low.
     */
    val temperature: Float = DEFAULT_TEMPERATURE,

    /** Nucleus sampling threshold. */
    val topP: Float = DEFAULT_TOP_P,

    /** Top-K sampling cutoff. */
    val topK: Int = DEFAULT_TOP_K,

    /** Penalty applied to repeated tokens. Guards against loops. */
    val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,

    /**
     * CPU threads for inference.
     *
     * More is not better. Exceeding the count of *performance* cores drags
     * work onto efficiency cores and can make generation slower while draining
     * more battery. Resolved per-device at construction, never hardcoded.
     */
    val threadCount: Int = DEFAULT_THREAD_COUNT,

    /**
     * Layers offloaded to GPU.
     *
     * Zero for the MVP. Android GPU offload via Vulkan is real but
     * device-fragmented, and a wrong answer here is a hard crash rather than a
     * slowdown. Revisit with measurement, not optimism.
     */
    val gpuLayers: Int = 0,

    /** Fixed RNG seed, or `null` for random. Set this in tests. */
    val seed: Int? = null,
) {
    init {
        require(contextLength > 0) { "contextLength must be positive" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(maxOutputTokens < contextLength) {
            "maxOutputTokens ($maxOutputTokens) must be less than contextLength ($contextLength)"
        }
        require(temperature >= 0f) { "temperature must be non-negative" }
        require(topP in 0f..1f) { "topP must be within 0..1" }
        require(threadCount > 0) { "threadCount must be positive" }
        require(gpuLayers >= 0) { "gpuLayers must be non-negative" }
    }

    companion object {
        const val DEFAULT_CONTEXT_LENGTH: Int = 8192
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 512
        const val DEFAULT_TEMPERATURE: Float = 0.3f
        const val DEFAULT_TOP_P: Float = 0.9f
        const val DEFAULT_TOP_K: Int = 40
        const val DEFAULT_REPEAT_PENALTY: Float = 1.1f
        const val DEFAULT_THREAD_COUNT: Int = 4

        /**
         * The default configuration for the executive-secretary role.
         *
         * Temperature is 0.3, not the conventional 0.7. This is a product
         * decision, not a tuning preference.
         *
         * `ai_architecture.md` specifies an assistant that is professional,
         * confident, short and precise, and explicitly *not* creative — the
         * model is told to specialise in scheduling and planning, not poetry.
         * For that role, sampling variance is pure downside: a creative
         * secretary invents a meeting time. Determinism is the feature.
         *
         * Raise this only for a future summarisation or drafting mode, and do
         * it there rather than changing this default.
         */
        val SECRETARY: ModelConfig = ModelConfig()

        /**
         * Fully deterministic configuration for tests and prompt-format
         * regression checks — the harness that detects Ollama/llama.cpp
         * divergence (ADR-001).
         */
        val DETERMINISTIC: ModelConfig = ModelConfig(temperature = 0f, topK = 1, seed = 0)
    }
}
