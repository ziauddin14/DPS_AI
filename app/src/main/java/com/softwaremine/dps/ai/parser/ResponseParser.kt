package com.softwaremine.dps.ai.parser

import com.softwaremine.dps.domain.prompt.PromptFormat

/**
 * Cleans raw model output into text fit to show a user.
 *
 * ## Purpose
 * The "Response Parser" stage of the Phase G pipeline, sitting between the
 * runtime and the conversation.
 *
 * ## Why this class exists on Day 02 despite doing very little
 * Today it strips control tokens and trims whitespace — perhaps thirty lines of
 * real work. It is created now anyway, and that is a deliberate architectural
 * decision rather than speculative scaffolding.
 *
 * On Day 03 the model begins emitting tool calls. Those arrive interleaved in
 * the same token stream as prose and must be extracted before the remaining
 * text reaches the user. If no parsing stage exists at that point, the
 * extraction gets wedged into whichever layer is nearest — typically the
 * session manager or, worse, the ViewModel — and the pipeline acquires a stage
 * that is not visible in its own structure.
 *
 * Establishing the seam while it is trivial costs almost nothing. Retrofitting
 * it into a working stream costs a great deal. This is the cheapest possible
 * moment to place it.
 *
 * ## Responsibilities
 * - Remove control tokens that leak past stop-sequence handling.
 * - Normalise whitespace without damaging intentional formatting.
 * - Clean partial text during streaming without flicker.
 *
 * ## Future extensions
 * Day 03: extract tool calls into a structured result alongside the prose.
 * Later: separate reasoning traces from user-facing output for models that emit
 * them.
 *
 * ## Dependencies
 * [PromptFormat] only. Pure Kotlin, fully unit-testable.
 */
class ResponseParser {

    /**
     * Cleans a completed response.
     *
     * @param raw the model's full output.
     * @param format the model's prompt format, which determines the control
     *   tokens that may appear.
     */
    fun parse(raw: String, format: PromptFormat): ParsedResponse {
        val withoutControlTokens = stripControlTokens(raw, format)
        val normalised = normaliseWhitespace(withoutControlTokens)
        return ParsedResponse(text = normalised)
    }

    /**
     * Cleans a partial response mid-stream.
     *
     * ## The trailing-fragment problem
     * Streaming delivers tokens one at a time, so a control token arrives in
     * pieces: `<`, then `<|im`, then `<|im_end|>`. Rendering each intermediate
     * state shows the user a control token briefly appearing and then vanishing.
     *
     * Rather than attempt partial-token matching, this withholds any trailing
     * fragment that *could* be the start of a control token. The withheld text
     * is at most a few characters and is emitted on the next token or by
     * [parse] at completion, so nothing is lost — the user simply never sees
     * the flicker.
     */
    fun parsePartial(rawSoFar: String, format: PromptFormat): ParsedResponse {
        val withoutControlTokens = stripControlTokens(rawSoFar, format)
        val withheld = withholdPossibleControlTokenPrefix(withoutControlTokens, format)
        return ParsedResponse(text = withheld.trimStart())
    }

    private fun stripControlTokens(text: String, format: PromptFormat): String {
        var result = text
        controlTokensFor(format).forEach { token ->
            result = result.replace(token, "")
        }
        return result
    }

    /**
     * Drops a trailing substring that is a proper prefix of any control token.
     */
    private fun withholdPossibleControlTokenPrefix(
        text: String,
        format: PromptFormat,
    ): String {
        val tokens = controlTokensFor(format)
        val maxPrefix = tokens.maxOfOrNull { it.length - 1 } ?: return text

        for (length in minOf(maxPrefix, text.length) downTo 1) {
            val tail = text.takeLast(length)
            if (tokens.any { it.startsWith(tail) }) {
                return text.dropLast(length)
            }
        }
        return text
    }

    /**
     * Collapses runs of three or more blank lines and trims the edges.
     *
     * Deliberately conservative: single and double newlines are preserved
     * because Markdown depends on them, and the chat surface renders Markdown.
     */
    private fun normaliseWhitespace(text: String): String =
        text.replace(EXCESS_BLANK_LINES, "\n\n").trim()

    private fun controlTokensFor(format: PromptFormat): List<String> = when (format) {
        PromptFormat.QWEN_CHAT_ML -> listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
        PromptFormat.LLAMA_3 -> listOf(
            "<|eot_id|>",
            "<|end_of_text|>",
            "<|begin_of_text|>",
            "<|start_header_id|>",
            "<|end_header_id|>",
        )
        PromptFormat.PHI_3 -> listOf("<|end|>", "<|user|>", "<|assistant|>", "<|system|>")
        PromptFormat.GEMMA -> listOf("<end_of_turn>", "<start_of_turn>", "<eos>")
        PromptFormat.MISTRAL -> listOf("</s>", "<s>", "[INST]", "[/INST]")
        PromptFormat.PLAIN -> emptyList()
    }

    private companion object {
        val EXCESS_BLANK_LINES = Regex("\\n{3,}")
    }
}

/**
 * A cleaned model response.
 *
 * Currently carries only [text]. Day 03 adds an extracted tool-call payload;
 * because the type already exists, that addition does not change any signature
 * in the pipeline.
 */
data class ParsedResponse(
    /** User-facing text with control tokens removed. */
    val text: String,
)
