package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of parameter extraction from real model output (Day 05 Phase D).
 *
 * ## Why these strings look untidy
 * Every input below is a shape a small instruction-tuned model actually
 * produces: markdown fences, a preamble, an explanation afterwards, an unquoted
 * number where a string was asked for, the literal text `"null"`. A parser
 * verified only against clean JSON is verified against input this project never
 * receives.
 *
 * ## The invariant these tests exist to hold
 * Tolerance recovers **structure**, never **content**. Every unparseable case
 * must land on [IntentType.CONVERSATION] with empty parameters — never on a
 * guessed action, because acting on a misread request is the one failure this
 * product cannot afford.
 */
class IntentJsonParserTest {

    private val parser = IntentJsonParser()

    // -----------------------------------------------------------------
    // The clean case
    // -----------------------------------------------------------------

    @Test
    fun `parses a well-formed classification`() {
        val intent = parser.parse(
            """{"intent":"reminder","parameters":{"title":"call the bank","time":"16:00"}}""",
        )

        assertEquals(IntentType.REMINDER, intent.type)
        assertEquals("call the bank", intent.parameters.title)
        assertEquals("16:00", intent.parameters.time)
    }

    @Test
    fun `reads confidence when the model supplies it`() {
        val intent = parser.parse(
            """{"intent":"notification","confidence":0.82,"parameters":{"message":"hi"}}""",
        )

        assertEquals(0.82f, intent.confidence, 0.001f)
    }

    @Test
    fun `defaults confidence to zero when absent`() {
        val intent = parser.parse("""{"intent":"notification","parameters":{"message":"hi"}}""")

        assertEquals(0f, intent.confidence, 0.0f)
    }

    // -----------------------------------------------------------------
    // Structure recovery
    // -----------------------------------------------------------------

    @Test
    fun `recovers JSON wrapped in a markdown fence`() {
        val intent = parser.parse(
            """
            ```json
            {"intent":"calendar_event","parameters":{"title":"standup","date":"2026-08-07"}}
            ```
            """.trimIndent(),
        )

        assertEquals(IntentType.CALENDAR_EVENT, intent.type)
        assertEquals("standup", intent.parameters.title)
    }

    @Test
    fun `recovers JSON surrounded by prose the model added anyway`() {
        val intent = parser.parse(
            "Sure! Here is the classification:\n" +
                """{"intent":"contact_lookup","parameters":{"person":"Abdul"}}""" +
                "\nLet me know if you need anything else.",
        )

        assertEquals(IntentType.CONTACT_LOOKUP, intent.type)
        assertEquals("Abdul", intent.parameters.person)
    }

    /** The parameters block is nested, so brace counting must survive nesting. */
    @Test
    fun `handles a nested parameters object`() {
        val extracted = parser.extractJsonObject(
            """noise {"intent":"reminder","parameters":{"time":"09:00"}} trailing""",
        )

        assertEquals(
            """{"intent":"reminder","parameters":{"time":"09:00"}}""",
            extracted,
        )
    }

    /** A brace inside a message body must not terminate the scan early. */
    @Test
    fun `a brace inside a string literal does not end the object`() {
        val intent = parser.parse(
            """{"intent":"whatsapp_message","parameters":{"person":"Sara","message":"meet me at {the cafe}"}}""",
        )

        assertEquals(IntentType.WHATSAPP_MESSAGE, intent.type)
        assertEquals("meet me at {the cafe}", intent.parameters.message)
    }

    @Test
    fun `an escaped quote inside a string does not end the object`() {
        val intent = parser.parse(
            """{"intent":"notification","parameters":{"message":"he said \"hello\" twice"}}""",
        )

        assertEquals("""he said "hello" twice""", intent.parameters.message)
    }

    @Test
    fun `accepts parameters flattened into the top level`() {
        // Small models drop the nesting roughly as often as they honour it.
        val intent = parser.parse("""{"intent":"reminder","title":"pay rent","time":"08:00"}""")

        assertEquals(IntentType.REMINDER, intent.type)
        assertEquals("pay rent", intent.parameters.title)
        assertEquals("08:00", intent.parameters.time)
    }

    @Test
    fun `accepts action as an alias for intent`() {
        val intent = parser.parse("""{"action":"reminder","parameters":{"time":"10:00"}}""")

        assertEquals(IntentType.REMINDER, intent.type)
    }

    // -----------------------------------------------------------------
    // Field aliases
    // -----------------------------------------------------------------

    @Test
    fun `accepts the alternative names models use for a person`() {
        listOf("person", "contact", "name").forEach { key ->
            val intent = parser.parse("""{"intent":"contact_lookup","parameters":{"$key":"Bilal"}}""")
            assertEquals("alias '$key' was not accepted", "Bilal", intent.parameters.person)
        }
    }

    @Test
    fun `accepts the alternative names models use for a message body`() {
        listOf("message", "body", "text").forEach { key ->
            val intent = parser.parse("""{"intent":"notification","parameters":{"$key":"stand up"}}""")
            assertEquals("alias '$key' was not accepted", "stand up", intent.parameters.message)
        }
    }

    @Test
    fun `accepts the alternative names models use for a title`() {
        listOf("title", "subject", "summary").forEach { key ->
            val intent = parser.parse("""{"intent":"calendar_event","parameters":{"$key":"review"}}""")
            assertEquals("alias '$key' was not accepted", "review", intent.parameters.title)
        }
    }

    // -----------------------------------------------------------------
    // Value coercion
    // -----------------------------------------------------------------

    @Test
    fun `coerces an unquoted number rather than discarding it`() {
        // A model asked for a time emits 1600 unquoted often enough to matter,
        // and throwing away a value plainly present forces a needless question.
        val intent = parser.parse("""{"intent":"reminder","parameters":{"time":1600,"title":"x"}}""")

        assertEquals("1600", intent.parameters.time)
    }

    @Test
    fun `treats JSON null and the literal string null as absent`() {
        val intent = parser.parse(
            """{"intent":"reminder","parameters":{"time":null,"person":"null","title":"x"}}""",
        )

        assertNull(intent.parameters.time)
        assertNull(intent.parameters.person)
        assertEquals("x", intent.parameters.title)
    }

    @Test
    fun `treats a blank value as absent`() {
        val intent = parser.parse("""{"intent":"reminder","parameters":{"time":"   ","title":"x"}}""")

        assertNull(intent.parameters.time)
    }

    @Test
    fun `ignores fields it does not model`() {
        val intent = parser.parse(
            """{"intent":"reminder","parameters":{"title":"x","time":"09:00","colour":"blue"}}""",
        )

        assertEquals(IntentType.REMINDER, intent.type)
        assertEquals("x", intent.parameters.title)
    }

    // -----------------------------------------------------------------
    // Failing safe
    // -----------------------------------------------------------------

    @Test
    fun `output with no JSON at all becomes conversation`() {
        val intent = parser.parse("I'd be happy to help with that!")

        assertEquals(IntentType.CONVERSATION, intent.type)
        assertNull(intent.parameters.title)
        assertNotNull(parser.lastFallbackReason)
    }

    @Test
    fun `output truncated mid-object becomes conversation`() {
        // Happens when the classification token budget runs out.
        val intent = parser.parse("""{"intent":"reminder","parameters":{"title":"call the""")

        assertEquals(IntentType.CONVERSATION, intent.type)
    }

    @Test
    fun `an unknown intent name becomes conversation rather than a guess`() {
        val intent = parser.parse("""{"intent":"order_pizza","parameters":{"person":"Ali"}}""")

        assertEquals(IntentType.CONVERSATION, intent.type)
    }

    @Test
    fun `malformed JSON becomes conversation`() {
        val intent = parser.parse("""{"intent": reminder, "parameters": {{}}""")

        assertEquals(IntentType.CONVERSATION, intent.type)
    }

    @Test
    fun `empty output becomes conversation`() {
        assertEquals(IntentType.CONVERSATION, parser.parse("").type)
        assertEquals(IntentType.CONVERSATION, parser.parse("   \n  ").type)
    }

    /** No input may escape as an exception; the caller has no way to recover. */
    @Test
    fun `never throws whatever the model produced`() {
        listOf(
            "{", "}", "{}", "[]", "\"", "\\", "```json```", "{\"intent\":", "null",
            "{\"parameters\":[]}", "{\"intent\":{\"nested\":true}}",
        ).forEach { input ->
            val intent = parser.parse(input)
            assertNotNull("parse($input) returned nothing", intent)
        }
    }

    // -----------------------------------------------------------------
    // action_type (Day 05 Phase E)
    // -----------------------------------------------------------------

    @Test
    fun `defaults action to create when the field is absent`() {
        val intent = parser.parse("""{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}""")

        assertEquals(IntentAction.CREATE, intent.action)
    }

    @Test
    fun `reads action_type when the model supplies it`() {
        val intent = parser.parse(
            """{"intent":"reminder","action_type":"cancel","parameters":{}}""",
        )

        assertEquals(IntentAction.CANCEL, intent.action)
    }

    @Test
    fun `accepts update as well as cancel`() {
        val intent = parser.parse(
            """{"intent":"reminder","action_type":"update","parameters":{}}""",
        )

        assertEquals(IntentAction.UPDATE, intent.action)
    }

    @Test
    fun `an unrecognised action_type defaults to create rather than guessing`() {
        val intent = parser.parse(
            """{"intent":"reminder","action_type":"launch_rocket","parameters":{}}""",
        )

        assertEquals(IntentAction.CREATE, intent.action)
    }

    @Test
    fun `the top-level action alias for intent type is not confused with action_type`() {
        // "action" here means the intent TYPE alias Phase D already supports —
        // it must not be misread as the create/update/cancel verb.
        val intent = parser.parse("""{"action":"reminder","parameters":{"time":"10:00"}}""")

        assertEquals(IntentType.REMINDER, intent.type)
        assertEquals(IntentAction.CREATE, intent.action)
    }

    @Test
    fun `strips fences with and without a language tag`() {
        assertTrue(!parser.stripCodeFences("```json\n{}\n```").contains("`"))
        assertTrue(!parser.stripCodeFences("```\n{}\n```").contains("`"))
        assertTrue(!parser.stripCodeFences("```JSON\n{}\n```").contains("`"))
    }

    // -----------------------------------------------------------------
    // parsePlan — multi-step (Day 05 Phase E Stage 2)
    // -----------------------------------------------------------------

    @Test
    fun `a bare object is a one-step plan`() {
        val steps = parser.parsePlan("""{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}""")

        assertEquals(1, steps.size)
        assertEquals(IntentType.REMINDER, steps.single().type)
    }

    @Test
    fun `a steps array becomes one intent per element in order`() {
        val steps = parser.parsePlan(
            """{"steps":[
                {"intent":"calendar_event","parameters":{"title":"meeting","date":"2026-08-07","time":"16:00"}},
                {"intent":"reminder","parameters":{"title":"meeting","time":"15:30"}}
            ]}""",
        )

        assertEquals(2, steps.size)
        assertEquals(IntentType.CALENDAR_EVENT, steps[0].type)
        assertEquals("meeting", steps[0].parameters.title)
        assertEquals(IntentType.REMINDER, steps[1].type)
        assertEquals("15:30", steps[1].parameters.time)
    }

    @Test
    fun `each step keeps its own action_type`() {
        val steps = parser.parsePlan(
            """{"steps":[
                {"intent":"calendar_event","action_type":"create","parameters":{"title":"x","date":"2026-08-07","time":"16:00"}},
                {"intent":"reminder","action_type":"cancel","parameters":{}}
            ]}""",
        )

        assertEquals(IntentAction.CREATE, steps[0].action)
        assertEquals(IntentAction.CANCEL, steps[1].action)
    }

    @Test
    fun `parse ignores a steps array and only ever returns the outer object`() {
        // A caller that only wants one intent must not silently receive the
        // first step of a plan it never asked for.
        val intent = parser.parse(
            """{"steps":[{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}]}""",
        )

        assertEquals(IntentType.CONVERSATION, intent.type)
    }

    @Test
    fun `an empty steps array falls back to the outer object as one step`() {
        val steps = parser.parsePlan(
            """{"intent":"reminder","action_type":"create","steps":[],"parameters":{"title":"x","time":"16:00"}}""",
        )

        assertEquals(1, steps.size)
        assertEquals(IntentType.REMINDER, steps.single().type)
    }

    @Test
    fun `a steps entry that is not an object is skipped rather than crashing`() {
        val steps = parser.parsePlan(
            """{"steps":[123,"not an object",{"intent":"reminder","parameters":{"title":"x","time":"16:00"}}]}""",
        )

        assertEquals(1, steps.size)
        assertEquals(IntentType.REMINDER, steps.single().type)
    }

    @Test
    fun `a steps array where every entry is unparseable falls back safely`() {
        val steps = parser.parsePlan("""{"steps":[123,"nope"]}""")

        assertEquals(1, steps.size)
        assertEquals(IntentType.CONVERSATION, steps.single().type)
    }

    @Test
    fun `a malformed plan never throws`() {
        listOf(
            """{"steps":""", "{\"steps\":[}", """{"steps":null}""", """{"steps":{}}""",
        ).forEach { input ->
            val steps = parser.parsePlan(input)
            assertTrue("parsePlan($input) returned an empty list", steps.isNotEmpty())
        }
    }
}
