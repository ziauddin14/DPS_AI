package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Verification of the intent classification prompt (Day 05 Phase D).
 *
 * ## Why a prompt is worth testing
 * The prompt and [IntentJsonParser] are two halves of one contract. If the
 * prompt stops naming an intent, or names a field the parser does not read, the
 * failure is silent — the model simply starts classifying badly, and nothing in
 * the build goes red. These tests make that contract explicit.
 *
 * ## The length assertion
 * Prompt ingestion is 76% of inference cost on this hardware
 * (`docs/PERFORMANCE-DAY-04.md`) and scales with length. The bound at the bottom
 * is not stylistic: growth here is paid for on every message the user sends.
 */
class IntentPromptBuilderTest {

    /** Thursday 6 August 2026, 14:30. */
    private val fixedNow = LocalDateTime.of(2026, 8, 6, 14, 30)

    private val builder = IntentPromptBuilder(now = { fixedNow })

    // -----------------------------------------------------------------
    // The contract with the parser
    // -----------------------------------------------------------------

    @Test
    fun `every routable intent is offered to the model by its wire name`() {
        val prompt = builder.build("remind me to call the bank at 4")

        IntentType.entries.forEach { type ->
            assertTrue(
                "Prompt never offers '${type.wireName}', so the model cannot choose it",
                prompt.contains(type.wireName),
            )
        }
    }

    @Test
    fun `every field the parser reads is named in the schema`() {
        val prompt = builder.build("anything")

        IntentField.entries.forEach { field ->
            assertTrue(
                "Prompt never names '${field.displayName}', so it will rarely be extracted",
                prompt.contains(field.displayName),
            )
        }
    }

    @Test
    fun `the model is asked for JSON only`() {
        val prompt = builder.build("anything")

        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("intent"))
        assertTrue(prompt.contains("parameters"))
    }

    // -----------------------------------------------------------------
    // Grounding in time
    // -----------------------------------------------------------------

    /**
     * The model cannot know the date. Without it, "tomorrow at 4" cannot be
     * resolved — and [ToolSelector] deliberately refuses to guess at relative
     * expressions, so this is the only place that information enters.
     */
    @Test
    fun `the current date, time and weekday are injected`() {
        val prompt = builder.build("remind me tomorrow at 4")

        assertTrue("Missing date: $prompt", prompt.contains("2026-08-06"))
        assertTrue("Missing time: $prompt", prompt.contains("14:30"))
        assertTrue("Missing weekday: $prompt", prompt.contains("Thursday"))
    }

    @Test
    fun `the model is told which formats to emit`() {
        val prompt = builder.build("anything")

        assertTrue(prompt.contains("YYYY-MM-DD"))
        assertTrue(prompt.contains("HH:MM"))
    }

    @Test
    fun `the model is told not to invent details`() {
        val prompt = builder.build("anything")

        // The counterpart of the clarification flow: a model that invents a time
        // means the user is never asked for the right one.
        assertTrue(prompt.lowercase().contains("never invent"))
    }

    // -----------------------------------------------------------------
    // Follow-up context
    // -----------------------------------------------------------------

    @Test
    fun `the pending question is included so a bare answer can be understood`() {
        val prompt = builder.build("at 4pm", pendingQuestion = "When should I remind you?")

        assertTrue(prompt.contains("When should I remind you?"))
        assertTrue(prompt.contains("at 4pm"))
    }

    @Test
    fun `no follow-up context appears when nothing was asked`() {
        val prompt = builder.build("remind me to call the bank at 4")

        assertTrue(!prompt.contains("You just asked"))
    }

    @Test
    fun `the user's message is the last thing before the model answers`() {
        val prompt = builder.build("book the dentist")

        val messageAt = prompt.indexOf("book the dentist")
        assertTrue("User message missing", messageAt >= 0)
        assertTrue(
            "Nothing should follow the user message but the answer cue",
            prompt.substring(messageAt).trim().endsWith("JSON:"),
        )
    }

    // -----------------------------------------------------------------
    // Cost
    // -----------------------------------------------------------------

    @Test
    fun `the prompt stays short enough to be affordable on device`() {
        val prompt = builder.build("remind me to call the bank at 4")

        // ~4 characters per token puts this well under 300 prompt tokens, which
        // at the measured ingestion rate is the difference between a responsive
        // assistant and an unusable one. The budget moved 800 (Phase D) → 900
        // (Phase E Stage 2, the steps-array rule) → 1050 (Day 06: five new
        // routable intent types, the complete/list actions, and the
        // duration/period fields the productivity secretary needs) — each move
        // is one deliberate, named addition, not drift; a regression here
        // should still fail loudly.
        assertTrue(
            "Classification prompt has grown to ${prompt.length} characters",
            prompt.length < 1050,
        )
    }

    @Test
    fun `the prompt does not steer the model with a worked example`() {
        val prompt = builder.build("remind me to call the bank at 4")

        // Small models copy an example's *content*, not just its shape. The
        // schema names fields and supplies no sample values for that reason.
        assertTrue(!prompt.contains("example", ignoreCase = true))
    }
}
