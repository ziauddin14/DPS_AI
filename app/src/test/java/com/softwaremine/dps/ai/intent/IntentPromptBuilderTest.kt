package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
 *
 * ## Why there is no fixed clock here anymore (Day 08-E)
 * Every earlier version of this file constructed [IntentPromptBuilder] with an
 * injected `now`. That parameter — and `Now:` itself — is gone: see
 * [IntentPromptBuilder]'s class doc for why asking the model to compute a date
 * or time was the actual defect Day 08-D traced, not where `Now:` sat in the
 * prompt. [com.softwaremine.dps.ai.memory.TemporalPhraseResolver] owns that
 * arithmetic now, entirely outside this class, and has its own fixed-clock
 * tests. This class has nothing time-dependent left to inject.
 */
class IntentPromptBuilderTest {

    private val builder = IntentPromptBuilder()

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

    /**
     * `date`/`time` are deliberately excluded here (Day 08-E) — they are
     * still real [IntentField] entries used to gate clarification, but the
     * model is never asked to fill them; see
     * [com.softwaremine.dps.ai.intent.IntentJsonParser.toParameters]'s doc
     * for why they are not even read from the model's JSON if it emits
     * them anyway. Everything else the parser reads directly must still be
     * named.
     */
    @Test
    fun `every field the parser reads directly is named in the schema`() {
        val prompt = builder.build("anything")

        (IntentField.entries - IntentField.DATE - IntentField.TIME).forEach { field ->
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
    // No date/time arithmetic asked of the model (Day 08-E)
    // -----------------------------------------------------------------

    /**
     * The regression this whole redesign exists to prevent: `Now:` must
     * never reappear in this prompt, in any position. See
     * [IntentPromptBuilder]'s class doc for the Day 08-D history.
     */
    @Test
    fun `Now is never injected into the prompt`() {
        val prompt = builder.build("remind me tomorrow at 4")

        assertTrue("'Now:' must never appear in the classification prompt", !prompt.contains("Now:"))
    }

    /** The schema offers a quote field, not fields the model would have to compute. */
    @Test
    fun `the schema asks for raw_when instead of date or time`() {
        val prompt = builder.build("anything")

        assertTrue("Schema is missing 'raw_when'", prompt.contains("raw_when"))
        assertTrue("Schema must not ask the model for a 'date' key", !prompt.contains("\"date\":"))
        assertTrue("Schema must not ask the model for a 'time' key", !prompt.contains("\"time\":"))
    }

    /** The model is told what to do with raw_when: quote, never compute. */
    @Test
    fun `the model is told to quote raw_when verbatim, never compute it`() {
        val prompt = builder.build("anything").lowercase()

        assertTrue(prompt.contains("raw_when"))
        assertTrue("Rule must forbid computing a date/time", prompt.contains("never compute"))
    }

    @Test
    fun `the model is told not to invent details`() {
        val prompt = builder.build("anything")

        // The counterpart of the clarification flow: a model that invents a time
        // means the user is never asked for the right one.
        assertTrue(prompt.lowercase().contains("never invent"))
    }

    // -----------------------------------------------------------------
    // Prompt stability — the whole point of removing Now: (Day 08-E)
    // -----------------------------------------------------------------

    /**
     * With no injected clock left anywhere in this class, the prompt for a
     * given [userMessage] is now **byte-for-byte deterministic** — not just
     * "shares a long prefix", the way the (now-rejected) Day 08-D version
     * could only claim. Two builds of the same message, however far apart
     * in real time, must be identical.
     */
    @Test
    fun `the same message always produces the exact same prompt`() {
        val first = builder.build("remind me to call the bank at 4")
        val second = builder.build("remind me to call the bank at 4")

        assertEquals(first, second)
    }

    /**
     * Two different messages still share the entire static block — the
     * whole prompt except the message itself (and the optional
     * pendingQuestion/recentContext blocks, which are absent here) — as one
     * unbroken, byte-identical prefix ending exactly at "User: ".
     */
    @Test
    fun `two different messages share the entire static prefix up to User`() {
        val first = builder.build("remind me to call the bank at 4")
        val second = builder.build("what's the weather like tomorrow")

        val marker = "User: "
        val firstPrefix = first.substring(0, first.indexOf(marker) + marker.length)
        val secondPrefix = second.substring(0, second.indexOf(marker) + marker.length)

        assertEquals(firstPrefix, secondPrefix)
        assertTrue(firstPrefix.length > 700)
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
        // duration/period fields the productivity secretary needs) → 1250 →
        // 1320 (Day 08-B: the `reply` field) → 1400 (Day 08-E: `Now:` and the
        // `date`/`time` schema keys are gone, but the new raw_when rule —
        // spelling out three example phrases and an explicit "never
        // compute" — is longer than the "resolve relative times against
        // Now" line it replaced, so the net change is a small *increase*,
        // not the decrease a naive count of removed characters would
        // suggest. Worth paying: the removed `Now:` line was dynamic on
        // every call and defeated KV-cache reuse entirely, while this
        // slightly larger prompt is now byte-identical on every call —
        // each move is one deliberate, named change, not drift; a
        // regression here should still fail loudly. → 1500 (Day 09: the
        // model never once produced a `steps` array across 30 real compound
        // requests when it was named only in a `Rules:` bullet — `Shape:`
        // now shows the single-object and steps-array forms as equally
        // prominent, schema-only entries, still with no field-value example,
        // so this is width the model needs, not drift). This whole block is
        // still fully static across every call, so the growth is a one-time
        // recache cost, not a per-request one — see IntentPromptBuilder's
        // class doc.
        assertTrue(
            "Classification prompt has grown to ${prompt.length} characters",
            prompt.length < 1500,
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
