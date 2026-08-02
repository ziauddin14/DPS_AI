package com.softwaremine.dps.ai.prompt

import com.softwaremine.dps.domain.conversation.ChatMessage
import com.softwaremine.dps.domain.conversation.MessageRole
import com.softwaremine.dps.domain.prompt.PromptFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat template regression tests.
 *
 * ## Why these exist and why they are the highest-value tests in the project
 * A wrong chat template does not throw. The model answers anyway — just worse,
 * ignoring instructions and occasionally hallucinating the user's next turn.
 * Because the symptom is degraded quality rather than an error, this class of
 * bug is invisible to every other kind of test and is routinely misdiagnosed as
 * "the model is too small".
 *
 * Asserting exact rendered strings is what makes such a regression a red test
 * instead of a slow-burning mystery.
 *
 * These run on the JVM with no emulator, no device and no Android framework —
 * which is the practical payoff of keeping `domain` pure (ADR-005), and matters
 * concretely on a machine that cannot comfortably run an emulator (ADR-009).
 */
class ChatTemplateTest {

    private val system = ChatMessage(
        id = "s",
        role = MessageRole.SYSTEM,
        content = "You are DPS.",
        timestampEpochMillis = 0,
    )
    private val user = ChatMessage(
        id = "u",
        role = MessageRole.USER,
        content = "Hello",
        timestampEpochMillis = 1,
    )
    private val assistant = ChatMessage(
        id = "a",
        role = MessageRole.ASSISTANT,
        content = "Hi.",
        timestampEpochMillis = 2,
    )

    @Test
    fun `qwen renders ChatML and primes the assistant turn`() {
        val rendered = QwenChatMlTemplate().render(listOf(system, user))

        assertEquals(
            "<|im_start|>system\n" +
                "You are DPS.<|im_end|>\n" +
                "<|im_start|>user\n" +
                "Hello<|im_end|>\n" +
                "<|im_start|>assistant\n",
            rendered,
        )
    }

    @Test
    fun `llama3 emits begin_of_text once and closes each turn with eot`() {
        val rendered = Llama3Template().render(listOf(system, user))

        assertEquals(1, Regex("<\\|begin_of_text\\|>").findAll(rendered).count())
        assertEquals(2, Regex("<\\|eot_id\\|>").findAll(rendered).count())
        assertTrue(rendered.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    /**
     * Gemma defines no system role. Emitting one produces a prompt shape Gemma
     * has never seen, which degrades output silently.
     */
    @Test
    fun `gemma folds the system prompt into the first user turn`() {
        val rendered = GemmaTemplate().render(listOf(system, user))

        assertFalse("Gemma must not emit a system turn", rendered.contains("system"))
        assertTrue("System text must survive", rendered.contains("You are DPS."))
        assertTrue("Assistant role is spelled 'model'", rendered.endsWith("<start_of_turn>model\n"))

        val systemIndex = rendered.indexOf("You are DPS.")
        val userIndex = rendered.indexOf("Hello")
        assertTrue("System text must precede the user turn", systemIndex < userIndex)
    }

    /** Mistral likewise has no system role; it folds into the first `[INST]`. */
    @Test
    fun `mistral folds the system prompt into the first instruction block`() {
        val rendered = MistralTemplate().render(listOf(system, user, assistant))

        assertTrue(rendered.startsWith("<s>[INST] You are DPS."))
        assertTrue(rendered.contains("[/INST]"))
        assertTrue("Assistant turn must be closed", rendered.contains("Hi.</s>"))
    }

    @Test
    fun `phi3 wraps every turn in its own tag`() {
        val rendered = Phi3Template().render(listOf(system, user))

        assertTrue(rendered.startsWith("<|system|>\n"))
        assertTrue(rendered.contains("<|user|>\n"))
        assertTrue(rendered.endsWith("<|assistant|>\n"))
    }

    @Test
    fun `multi-turn history preserves chronological order`() {
        val second = user.copy(id = "u2", content = "And then?", timestampEpochMillis = 3)
        val rendered = QwenChatMlTemplate().render(listOf(system, user, assistant, second))

        val order = listOf("You are DPS.", "Hello", "Hi.", "And then?")
            .map { rendered.indexOf(it) }

        assertTrue("All turns must be present", order.none { it < 0 })
        assertEquals("Turns must stay in order", order.sorted(), order)
    }

    @Test
    fun `registry resolves every declared format`() {
        val registry = ChatTemplateRegistry()

        PromptFormat.entries.forEach { format ->
            val template = registry.templateFor(format)
            assertEquals(
                "Registry returned the wrong template for $format",
                format,
                template.format,
            )
        }
    }

    /**
     * Every format must declare stop sequences. A model with none will run past
     * its own turn and begin generating the user's next message.
     */
    @Test
    fun `every template declares stop sequences`() {
        val registry = ChatTemplateRegistry()

        PromptFormat.entries
            .filter { it != PromptFormat.PLAIN }
            .forEach { format ->
                assertTrue(
                    "$format must declare stop sequences",
                    registry.templateFor(format).stopSequences.isNotEmpty(),
                )
            }
    }
}
