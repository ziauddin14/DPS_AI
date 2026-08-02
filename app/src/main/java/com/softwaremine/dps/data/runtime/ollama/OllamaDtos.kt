package com.softwaremine.dps.data.runtime.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Ollama HTTP API.
 *
 * ## Purpose
 * Isolates the development runtime's transport format from `domain`. Nothing
 * outside this package may reference these types — an Ollama field name leaking
 * into the AI layer would couple production code to a development-only server.
 *
 * ## Why `/api/generate` with `raw = true`, not `/api/chat`
 * `/api/chat` is the more natural-looking choice and is the wrong one here.
 *
 * It applies the chat template baked into Ollama's model manifest. DPS applies
 * its own template in [com.softwaremine.dps.ai.prompt.PromptManager], so using
 * `/api/chat` would mean the prompt is templated twice — once by us, once by
 * Ollama — and would produce input that differs from what llama.cpp receives in
 * production.
 *
 * That divergence is exactly the failure ADR-001 sets out to prevent: the
 * development runtime and the production runtime would answer differently, and
 * the difference would surface as unreproducible behaviour rather than as an
 * error. `raw = true` disables Ollama's templating entirely, guaranteeing both
 * runtimes see byte-identical prompts.
 *
 * ## Dependencies
 * kotlinx.serialization.
 */
@Serializable
internal data class OllamaGenerateRequest(
    @SerialName("model") val model: String,
    @SerialName("prompt") val prompt: String,

    /** Disables Ollama-side templating. Must always be `true`. See above. */
    @SerialName("raw") val raw: Boolean = true,

    @SerialName("stream") val stream: Boolean = true,
    @SerialName("options") val options: OllamaOptions,
)

/** Sampling and context parameters, mapped from [com.softwaremine.dps.domain.model.ModelConfig]. */
@Serializable
internal data class OllamaOptions(
    @SerialName("num_ctx") val numCtx: Int,
    @SerialName("num_predict") val numPredict: Int,
    @SerialName("temperature") val temperature: Float,
    @SerialName("top_p") val topP: Float,
    @SerialName("top_k") val topK: Int,
    @SerialName("repeat_penalty") val repeatPenalty: Float,
    @SerialName("seed") val seed: Int,
    @SerialName("stop") val stop: List<String>,
)

/**
 * One NDJSON line from a streaming generate response.
 *
 * The stream emits many lines with `done = false` carrying a token in
 * [response], then exactly one with `done = true` carrying the counters. Fields
 * present only on the final line are nullable.
 */
@Serializable
internal data class OllamaGenerateResponse(
    @SerialName("model") val model: String = "",
    @SerialName("response") val response: String = "",
    @SerialName("done") val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,

    /** Prompt tokens. Final line only. Ollama's authoritative count. */
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,

    /** Generated tokens. Final line only. */
    @SerialName("eval_count") val evalCount: Int? = null,

    /** Total nanoseconds. Final line only. */
    @SerialName("total_duration") val totalDuration: Long? = null,
)

/** A single entry from `GET /api/tags`, used for availability probing. */
@Serializable
internal data class OllamaTagsResponse(
    @SerialName("models") val models: List<OllamaModelTag> = emptyList(),
)

@Serializable
internal data class OllamaModelTag(
    @SerialName("name") val name: String = "",
    @SerialName("size") val size: Long = 0L,
)
