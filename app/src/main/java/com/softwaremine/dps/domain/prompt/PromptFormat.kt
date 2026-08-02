package com.softwaremine.dps.domain.prompt

/**
 * The chat template a model family was instruction-tuned against.
 *
 * ## Purpose — and why this type is the crux of ADR-004
 * The brief requires that swapping Qwen → Llama → Phi → Gemma → Mistral needs
 * only a provider change. A [com.softwaremine.dps.domain.runtime.RuntimeProvider]
 * abstraction alone does **not** achieve that, because model families differ in
 * a second, quieter dimension: the exact control tokens that delimit a turn.
 *
 * ```
 * QWEN_CHAT_ML  <|im_start|>system\n…<|im_end|>
 * LLAMA_3       <|start_header_id|>system<|end_header_id|>\n…<|eot_id|>
 * PHI_3         <|system|>\n…<|end|>
 * GEMMA         <start_of_turn>user\n…<end_of_turn>
 * MISTRAL       [INST] … [/INST]
 * ```
 *
 * Format the prompt wrong and the model does not error — it answers, just
 * measurably worse. That makes this the most expensive category of bug in the
 * system, because the symptom ("the model is dumb") points away from the cause
 * ("we framed the conversation in a syntax it was never trained on"). Teams
 * lose weeks to it, and typically respond by trying a bigger model.
 *
 * Making the format an explicit, data-carried property of each model closes that
 * failure mode structurally. Adding Mistral becomes: one catalog entry, one
 * enum case, one template. No orchestration code changes.
 *
 * ## Responsibilities
 * Identify a template family. Nothing more — this is a tag.
 *
 * ## Design note
 * Rendering deliberately lives elsewhere
 * ([com.softwaremine.dps.ai.prompt.ChatTemplate]). `domain` declares *which*
 * format a model uses; the `ai` layer knows *how* to render it. That keeps
 * `domain` free of string-building logic and keeps templates unit-testable in
 * isolation.
 *
 * ## Future extensions
 * When Day 03 introduces tool calling, each family also gains a tool-call
 * envelope (Qwen and Llama 3.1+ define different ones). That extends
 * [ChatTemplate], not this enum.
 *
 * ## Dependencies
 * None.
 */
enum class PromptFormat {

    /**
     * ChatML, as used by Qwen 2.x/2.5 Instruct — the locked MVP model
     * (`offliceLLM_guide.md`).
     */
    QWEN_CHAT_ML,

    /** Llama 3.x instruct header format. */
    LLAMA_3,

    /** Phi-3 instruct format. */
    PHI_3,

    /** Gemma / Gemma 2 turn format. */
    GEMMA,

    /**
     * Mistral instruct format.
     *
     * Note for whoever implements this: Mistral has no dedicated system-role
     * token. The system prompt must be folded into the first `[INST]` block.
     * Rendering it as a separate turn is silently wrong.
     */
    MISTRAL,

    /**
     * Plain alternating text with no control tokens.
     *
     * A correctness floor for base (non-instruct) models, not a default. Any
     * instruction-tuned model will underperform here.
     */
    PLAIN,
}
