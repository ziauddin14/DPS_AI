package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.intent.requiredFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the clarification flow (Day 05 Phase D).
 *
 * ## What is being protected
 * That DPS asks instead of guessing. A reminder with an invented time fires at
 * the wrong moment; a message with an invented recipient goes to the wrong
 * person. Both are worse than a follow-up question, and both are what these
 * tests exist to make impossible.
 *
 * The second concern is that the question is answerable: [ClarificationEngine.merge]
 * must fold a one-word reply into what was already understood, or the user
 * experiences DPS as having forgotten their request the moment it asked about it.
 */
class ClarificationEngineTest {

    private val engine = ClarificationEngine()

    private fun intent(type: IntentType, parameters: IntentParameters) =
        DpsIntent(type = type, parameters = parameters)

    private fun check(type: IntentType, parameters: IntentParameters) =
        engine.check(intent(type, parameters))

    // -----------------------------------------------------------------
    // Completeness
    // -----------------------------------------------------------------

    @Test
    fun `a reminder with a label and a time is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.REMINDER, IntentParameters(title = "call the bank", time = "16:00")),
        )
    }

    /** Either field can carry the label — the two routes are equally complete. */
    @Test
    fun `a reminder labelled by message rather than title is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.REMINDER, IntentParameters(message = "take medicine", time = "20:00")),
        )
    }

    @Test
    fun `a reminder with no time is incomplete`() {
        val result = check(IntentType.REMINDER, IntentParameters(title = "the meeting"))

        assertTrue(result is ClarificationEngine.Check.Missing)
        assertEquals(setOf(IntentField.TIME), (result as ClarificationEngine.Check.Missing).fields)
    }

    @Test
    fun `a message addressed by name is complete and so is one addressed by number`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.WHATSAPP_MESSAGE, IntentParameters(person = "Sara", message = "hi")),
        )
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(
                IntentType.WHATSAPP_MESSAGE,
                IntentParameters(phone = "+923001234567", message = "hi"),
            ),
        )
    }

    @Test
    fun `a message with a recipient but no body is incomplete`() {
        val result = check(IntentType.WHATSAPP_MESSAGE, IntentParameters(person = "Sara"))

        assertTrue(result is ClarificationEngine.Check.Missing)
        assertEquals(
            setOf(IntentField.MESSAGE),
            (result as ClarificationEngine.Check.Missing).fields,
        )
    }

    @Test
    fun `conversation needs nothing`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.CONVERSATION, IntentParameters()),
        )
    }

    @Test
    fun `a blank value counts as missing rather than as present`() {
        val result = check(IntentType.REMINDER, IntentParameters(title = "x", time = "   "))

        assertTrue("A whitespace time must not satisfy a reminder", result is ClarificationEngine.Check.Missing)
    }

    /**
     * When several routes would satisfy the intent, the nearest one is chosen.
     *
     * Asking for the one remaining piece of a nearly-finished route beats asking
     * for two pieces of an untouched one.
     */
    @Test
    fun `the closest route to complete is the one asked about`() {
        val result = check(
            IntentType.EMAIL_MESSAGE,
            IntentParameters(email = "a@b.com"),
        ) as ClarificationEngine.Check.Missing

        // The email route needs only the body; the person route would need both.
        assertEquals(setOf(IntentField.MESSAGE), result.fields)
    }

    // -----------------------------------------------------------------
    // Merging answers
    // -----------------------------------------------------------------

    @Test
    fun `an answer fills the gap without erasing what was already understood`() {
        val known = IntentParameters(title = "the meeting")
        val answer = IntentParameters(time = "16:00")

        val merged = engine.merge(known, answer)

        assertEquals("the meeting", merged.title)
        assertEquals("16:00", merged.time)
    }

    @Test
    fun `an answer that says nothing new leaves the request intact`() {
        val known = IntentParameters(title = "the meeting", time = "16:00")

        assertEquals(known, engine.merge(known, IntentParameters()))
    }

    @Test
    fun `a corrected value replaces the earlier one`() {
        val merged = engine.merge(
            IntentParameters(title = "x", time = "16:00"),
            IntentParameters(time = "17:00"),
        )

        assertEquals("17:00", merged.time)
    }

    @Test
    fun `merging then checking completes a request that was asked about`() {
        val partial = IntentParameters(title = "the meeting")
        val result = check(IntentType.REMINDER, engine.merge(partial, IntentParameters(time = "16:00")))

        assertEquals(ClarificationEngine.Check.Complete, result)
    }

    // -----------------------------------------------------------------
    // The questions themselves
    // -----------------------------------------------------------------

    @Test
    fun `a reminder missing only a time asks when`() {
        val result = check(IntentType.REMINDER, IntentParameters(title = "the meeting"))
            as ClarificationEngine.Check.Missing

        assertEquals("When should I remind you?", result.question)
    }

    @Test
    fun `a reminder missing everything asks one combined question`() {
        val result = check(IntentType.REMINDER, IntentParameters())
            as ClarificationEngine.Check.Missing

        // Asking twice in a row reads as interrogation.
        assertEquals("What should I remind you about, and when?", result.question)
    }

    @Test
    fun `a message missing a recipient asks who`() {
        val result = check(IntentType.WHATSAPP_MESSAGE, IntentParameters(message = "on my way"))
            as ClarificationEngine.Check.Missing

        assertEquals("Who should I message?", result.question)
    }

    /**
     * AI Rules 1 and 2: the user must never see internal architecture.
     *
     * "Missing required field TIME" is not something a secretary says, and the
     * questions are derived from field data — so this is checked across every
     * intent and every field rather than for the cases that happened to be
     * written above.
     */
    @Test
    fun `no question ever leaks a field name, a tool name or an internal term`() {
        val forbidden = listOf(
            "field", "parameter", "null", "intent", "tool", "IntentField",
            "ToolCall", "argument", "json", "permission",
        )

        IntentType.entries.forEach { type ->
            IntentField.entries.forEach { field ->
                val question = engine.questionFor(type, setOf(field))

                assertTrue("Empty question for $type/$field", question.isNotBlank())
                assertTrue(
                    "Question for $type/$field must be a question: $question",
                    question.endsWith("?"),
                )
                forbidden.forEach { term ->
                    assertTrue(
                        "Question for $type/$field leaked '$term': $question",
                        !question.lowercase().contains(term.lowercase()),
                    )
                }
            }
        }
    }

    @Test
    fun `every routable intent produces a question for its own missing fields`() {
        IntentType.entries
            // CONVERSATION is not routed to a tool. REPORT (Day 06) is routed
            // but deliberately declares no required fields at all — an
            // unspecified scope defaults to a daily report in ToolSelector
            // rather than ever being asked about, since showing more real
            // stored data than requested is harmless. See DpsIntent.kt's
            // requiredFields doc for REPORT.
            .filter { it != IntentType.CONVERSATION && it != IntentType.REPORT }
            .forEach { type ->
                val result = engine.check(intent(type, IntentParameters()))

                assertTrue(
                    "$type with no parameters should need clarification",
                    result is ClarificationEngine.Check.Missing,
                )
                assertTrue(
                    "$type must declare required fields",
                    type.requiredFields.isNotEmpty(),
                )
            }
    }

    @Test
    fun `REPORT deliberately needs no fields at all`() {
        assertEquals(ClarificationEngine.Check.Complete, engine.check(intent(IntentType.REPORT, IntentParameters())))
    }

    // -----------------------------------------------------------------
    // UPDATE/CANCEL completeness (Day 05 Phase E)
    // -----------------------------------------------------------------

    private fun intent(type: IntentType, parameters: IntentParameters, action: IntentAction) =
        DpsIntent(type = type, parameters = parameters, action = action)

    @Test
    fun `an update with a resolved target id is complete regardless of title or time`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            engine.check(intent(IntentType.REMINDER, IntentParameters(targetId = "1001"), IntentAction.UPDATE)),
        )
    }

    @Test
    fun `a cancel with no resolved target asks which one, not what and when`() {
        val result = engine.check(intent(IntentType.REMINDER, IntentParameters(), IntentAction.CANCEL))

        assertTrue(result is ClarificationEngine.Check.Missing)
        val missing = result as ClarificationEngine.Check.Missing
        assertEquals("Which reminder do you mean?", missing.question)
        // The missing thing is a referent, not a value any IntentField names.
        assertTrue(missing.fields.isEmpty())
    }

    @Test
    fun `an unresolved calendar update asks which event`() {
        val result = engine.check(intent(IntentType.CALENDAR_EVENT, IntentParameters(), IntentAction.UPDATE))

        assertTrue(result is ClarificationEngine.Check.Missing)
        assertEquals("Which event do you mean?", (result as ClarificationEngine.Check.Missing).question)
    }

    @Test
    fun `create is unaffected by the targetable-type branch`() {
        // A fresh CREATE for REMINDER still goes through the ordinary
        // title+time completeness check, never the target-id one.
        val result = engine.check(intent(IntentType.REMINDER, IntentParameters(), IntentAction.CREATE))

        assertTrue(result is ClarificationEngine.Check.Missing)
        assertEquals(
            "What should I remind you about, and when?",
            (result as ClarificationEngine.Check.Missing).question,
        )
    }
}
