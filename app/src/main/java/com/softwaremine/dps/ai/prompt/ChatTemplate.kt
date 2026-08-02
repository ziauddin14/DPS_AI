package com.softwaremine.dps.ai.prompt

import com.softwaremine.dps.domain.conversation.ChatMessage
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.prompt.PromptFormat

/**
 * Renders a conversation into the exact string a model family expects.
 *
 * ## Purpose
 * This is the concrete half of ADR-004. [PromptFormat] declares *which* format
 * a model uses; the implementations here know *how* to produce it.
 *
 * ## Why this is worth getting exactly right
 * A wrong template does not raise an error. The model still responds — it has
 * simply been handed a conversation framed in a syntax it was never trained on,
 * and it degrades: it follows instructions less reliably, ignores the system
 * prompt, and occasionally continues past its turn to hallucinate the user's
 * next message.
 *
 * The symptom therefore points away from the cause. Teams conclude the model is
 * too small and reach for a bigger one, when the actual defect is a missing
 * newline. Isolating templates here, one class per family with the raw format
 * documented above it, is what makes that class of bug findable.
 *
 * ## Rendering contract
 * Every implementation ends its output with the assistant's opening delimiter
 * and nothing more. This *primes* the model to continue as the assistant. Any
 * trailing content after that delimiter — even whitespace, for some families —
 * changes the token sequence and measurably degrades output.
 *
 * ## Responsibilities
 * - Render a message list to a prompt string.
 * - Declare the stop sequences that terminate a turn in that format.
 *
 * ## Future extensions
 * Day 03 tool calling adds a per-family tool-call envelope. Qwen and Llama 3.1+
 * define different ones, so it belongs here rather than in shared code.
 *
 * ## Dependencies
 * `domain` conversation types only. Pure Kotlin, fully unit-testable.
 */
interface ChatTemplate {

    /** The format this template implements. */
    val format: PromptFormat

    /** Sequences that terminate a turn in this format. */
    val stopSequences: List<String>

    /**
     * Renders [messages] into a prompt primed for an assistant turn.
     *
     * @param messages chronological turns, optionally beginning with a
     *   [MessageRole.SYSTEM] message.
     */
    fun render(messages: List<ChatMessage>): String
}

/**
 * ChatML, as used by Qwen 2.x / 2.5 Instruct — the locked MVP model.
 *
 * ```
 * <|im_start|>system
 * You are DPS.<|im_end|>
 * <|im_start|>user
 * Hello<|im_end|>
 * <|im_start|>assistant
 * ```
 */
class QwenChatMlTemplate : ChatTemplate {

    override val format: PromptFormat = PromptFormat.QWEN_CHAT_ML

    override val stopSequences: List<String> = listOf("<|im_end|>", "<|im_start|>")

    override fun render(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
            }
            append("<|im_start|>").append(role).append('\n')
            append(message.content).append("<|im_end|>").append('\n')
        }
        append("<|im_start|>assistant\n")
    }
}

/**
 * Llama 3.x instruct format.
 *
 * ```
 * <|begin_of_text|><|start_header_id|>system<|end_header_id|>
 *
 * You are DPS.<|eot_id|><|start_header_id|>user<|end_header_id|>
 *
 * Hello<|eot_id|><|start_header_id|>assistant<|end_header_id|>
 *
 * ```
 *
 * The blank line after each header is part of the format, not formatting.
 */
class Llama3Template : ChatTemplate {

    override val format: PromptFormat = PromptFormat.LLAMA_3

    override val stopSequences: List<String> = listOf("<|eot_id|>", "<|end_of_text|>")

    override fun render(messages: List<ChatMessage>): String = buildString {
        append("<|begin_of_text|>")
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
            }
            append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
            append(message.content).append("<|eot_id|>")
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }
}

/**
 * Phi-3 instruct format.
 *
 * ```
 * <|system|>
 * You are DPS.<|end|>
 * <|user|>
 * Hello<|end|>
 * <|assistant|>
 * ```
 */
class Phi3Template : ChatTemplate {

    override val format: PromptFormat = PromptFormat.PHI_3

    override val stopSequences: List<String> = listOf("<|end|>", "<|user|>", "<|system|>")

    override fun render(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            val tag = when (message.role) {
                MessageRole.SYSTEM -> "<|system|>"
                MessageRole.USER -> "<|user|>"
                MessageRole.ASSISTANT -> "<|assistant|>"
            }
            append(tag).append('\n')
            append(message.content).append("<|end|>").append('\n')
        }
        append("<|assistant|>\n")
    }
}

/**
 * Gemma / Gemma 2 turn format.
 *
 * ```
 * <start_of_turn>user
 * You are DPS.
 *
 * Hello<end_of_turn>
 * <start_of_turn>model
 * ```
 *
 * **Gemma defines no system role.** The system prompt must be folded into the
 * first user turn, and the assistant role is spelled `model`, not `assistant`.
 * Rendering a separate system turn here produces a prompt Gemma has never seen;
 * it will answer anyway, just worse — exactly the silent failure this class
 * hierarchy exists to prevent.
 */
class GemmaTemplate : ChatTemplate {

    override val format: PromptFormat = PromptFormat.GEMMA

    override val stopSequences: List<String> = listOf("<end_of_turn>", "<start_of_turn>")

    override fun render(messages: List<ChatMessage>): String {
        val system = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val turns = messages.filter { it.role != MessageRole.SYSTEM }

        return buildString {
            turns.forEachIndexed { index, message ->
                val role = if (message.role == MessageRole.ASSISTANT) "model" else "user"
                append("<start_of_turn>").append(role).append('\n')

                // Fold the system prompt into the first user turn.
                if (index == 0 && system != null && message.role == MessageRole.USER) {
                    append(system.content).append("\n\n")
                }

                append(message.content).append("<end_of_turn>").append('\n')
            }
            append("<start_of_turn>model\n")
        }
    }
}

/**
 * Mistral instruct format.
 *
 * ```
 * <s>[INST] You are DPS.
 *
 * Hello [/INST] Hi.</s>[INST] Next question [/INST]
 * ```
 *
 * **Mistral also has no system role**, and unlike Gemma its instruction blocks
 * wrap *pairs* of turns rather than delimiting each one. The system prompt is
 * folded into the first `[INST]` block.
 */
class MistralTemplate : ChatTemplate {

    override val format: PromptFormat = PromptFormat.MISTRAL

    override val stopSequences: List<String> = listOf("</s>", "[INST]")

    override fun render(messages: List<ChatMessage>): String {
        val system = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val turns = messages.filter { it.role != MessageRole.SYSTEM }

        return buildString {
            append("<s>")
            var isFirstUserTurn = true
            turns.forEach { message ->
                when (message.role) {
                    MessageRole.USER -> {
                        append("[INST] ")
                        if (isFirstUserTurn && system != null) {
                            append(system.content).append("\n\n")
                            isFirstUserTurn = false
                        }
                        append(message.content).append(" [/INST]")
                    }

                    MessageRole.ASSISTANT -> append(' ').append(message.content).append("</s>")

                    MessageRole.SYSTEM -> Unit // Already folded above.
                }
            }
        }
    }
}

/**
 * Plain labelled text with no control tokens.
 *
 * A correctness floor for base (non-instruction-tuned) models. Any
 * instruction-tuned model will underperform here — this is not a safe default,
 * only a safe fallback.
 */
class PlainTemplate : ChatTemplate {

    override val format: PromptFormat = PromptFormat.PLAIN

    override val stopSequences: List<String> = listOf("\nUser:", "\nSystem:")

    override fun render(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            val label = when (message.role) {
                MessageRole.SYSTEM -> "System"
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
            }
            append(label).append(": ").append(message.content).append("\n\n")
        }
        append("Assistant:")
    }
}

/**
 * Resolves a [PromptFormat] to its [ChatTemplate].
 *
 * ## Purpose
 * The one place that maps format to implementation. Adding a model family is a
 * single registry entry plus a template class — no orchestration code changes,
 * which is the concrete meaning of ADR-004's swappability guarantee.
 *
 * Templates are stateless and shared; instantiating one per request would be
 * pure waste.
 */
class ChatTemplateRegistry(
    templates: List<ChatTemplate> = defaultTemplates(),
) {
    private val byFormat: Map<PromptFormat, ChatTemplate> =
        templates.associateBy { it.format }

    /**
     * The template for [format].
     *
     * Throws [IllegalStateException] when unregistered. This is a programming
     * error rather than a runtime condition — a [PromptFormat] enum case with
     * no template means a model was added to the catalog without its template,
     * and failing loudly at first use beats degrading silently for every user.
     */
    fun templateFor(format: PromptFormat): ChatTemplate =
        byFormat[format] ?: error("No ChatTemplate registered for format $format")

    companion object {
        fun defaultTemplates(): List<ChatTemplate> = listOf(
            QwenChatMlTemplate(),
            Llama3Template(),
            Phi3Template(),
            GemmaTemplate(),
            MistralTemplate(),
            PlainTemplate(),
        )
    }
}
