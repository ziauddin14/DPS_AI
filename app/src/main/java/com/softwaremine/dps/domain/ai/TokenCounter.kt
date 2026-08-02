package com.softwaremine.dps.domain.ai

import com.softwaremine.dps.core.result.DpsResult

/**
 * Counts tokens using the active model's tokenizer.
 *
 * ## Purpose
 * Exists as its own tiny interface to break what would otherwise be a circular
 * dependency: [com.softwaremine.dps.ai.prompt.PromptManager] must know how many
 * tokens a prompt occupies in order to trim history to fit, but it must not
 * depend on [AiEngine] — the engine is what *calls* the prompt manager.
 *
 * Depending on this narrow capability instead of the whole engine keeps the
 * dependency acyclic and makes the prompt manager trivially unit-testable with
 * a deterministic counting stub.
 *
 * ## Why a real tokenizer, not a character heuristic
 * The common shortcut is `length / 4`. It is calibrated on English and is
 * badly wrong for the input this product is actually built for: Roman Urdu and
 * mixed Urdu-English — the interaction style shown throughout
 * `user_journey.md` — tokenise substantially less efficiently. A heuristic
 * tuned on English will under-count, and under-counting means silently
 * overflowing the context window and dropping the system prompt.
 *
 * ## Dependencies
 * [DpsResult] only.
 */
fun interface TokenCounter {

    /**
     * Counts tokens in [text].
     *
     * Fails with [com.softwaremine.dps.core.error.DpsError.Runtime.NotLoaded]
     * when no model is loaded — token counts are tokenizer-specific and cannot
     * be transferred between models.
     */
    suspend fun count(text: String): DpsResult<Int>
}
