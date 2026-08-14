package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of [TemporalStepAttributor] (Day 08-E multi-step follow-up).
 *
 * ## What this proves
 * That each step's `raw_when` is only kept when it corresponds to a genuine,
 * not-already-claimed occurrence in the original message — the fix for the
 * vulnerability [com.softwaremine.dps.ai.secretary.SecretaryOrchestratorTest]'s
 * old "KNOWN LIMITATION" test proved: a hallucinated `raw_when` on one step
 * accepted merely because a sibling step's genuine phrase existed somewhere
 * in the same message.
 */
class TemporalStepAttributorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val fixedNow = LocalDateTime.of(2026, 8, 11, 9, 0)
    private val resolver = TemporalPhraseResolver(zone = zone, now = { fixedNow })
    private val attributor = TemporalStepAttributor(
        spanFinder = TemporalPhraseSpanFinder(resolver = resolver),
        groundingGuard = TemporalGroundingGuard(),
    )

    private fun reminderWithRawWhen(rawWhen: String?) =
        DpsIntent(type = IntentType.REMINDER, parameters = IntentParameters(title = "x", rawWhen = rawWhen))

    private fun taskWithRawWhen(rawWhen: String?) =
        DpsIntent(type = IntentType.TASK, parameters = IntentParameters(title = "y", rawWhen = rawWhen))

    @Test
    fun `a genuine phrase on step 1 is kept, step 2 without one is untouched`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), taskWithRawWhen(null))

        val result = attributor.attribute(
            "kal shaam 7 baje reminder laga do aur milk khareedne ka task bana do",
            steps,
        )

        assertEquals("kal shaam 7 baje", result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    @Test
    fun `a genuine phrase on step 2 is kept, step 1 without one is untouched`() {
        val steps = listOf(taskWithRawWhen(null), reminderWithRawWhen("kal shaam 7 baje"))

        val result = attributor.attribute(
            "milk khareedne ka task bana do aur kal shaam 7 baje reminder laga do",
            steps,
        )

        assertNull(result[0].parameters.rawWhen)
        assertEquals("kal shaam 7 baje", result[1].parameters.rawWhen)
    }

    @Test
    fun `two distinct genuine phrases are each attributed to their own step`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), reminderWithRawWhen("kal raat 11 baje"))

        val result = attributor.attribute(
            "kal shaam 7 baje reminder laga do aur kal raat 11 baje doosra reminder laga do",
            steps,
        )

        assertEquals("kal shaam 7 baje", result[0].parameters.rawWhen)
        assertEquals("kal raat 11 baje", result[1].parameters.rawWhen)
    }

    @Test
    fun `no temporal phrase anywhere leaves both steps without one`() {
        val steps = listOf(taskWithRawWhen(null), taskWithRawWhen(null))

        val result = attributor.attribute("meeting ka task bana do aur milk ka task bana do", steps)

        assertNull(result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    @Test
    fun `a hallucinated phrase absent from the whole message is rejected on every step`() {
        val steps = listOf(taskWithRawWhen("kal shaam 7 baje"), taskWithRawWhen("kal shaam 7 baje"))

        val result = attributor.attribute("meeting ka task bana do aur milk ka task bana do", steps)

        assertNull(result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    /** The core regression case: a hallucinated sibling-step phrase must not be accepted. */
    @Test
    fun `a hallucinated raw_when copying a sibling step's genuine phrase is rejected`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), taskWithRawWhen("kal shaam 7 baje"))

        val result = attributor.attribute(
            "kal shaam 7 baje Ali ko call karna aur milk khareedna",
            steps,
        )

        assertEquals("Step 1's genuine phrase must survive", "kal shaam 7 baje", result[0].parameters.rawWhen)
        assertNull("Step 2's identical claim on an already-consumed occurrence must be rejected", result[1].parameters.rawWhen)
    }

    @Test
    fun `the same phrase hallucinated into both steps rejects every claim beyond the first`() {
        // Same scenario as above, phrased as the task's own "same phrase
        // into both steps" example.
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), taskWithRawWhen("kal shaam 7 baje"))

        val result = attributor.attribute("kal shaam 7 baje reminder laga do aur milk ka task bana do", steps)

        assertEquals("kal shaam 7 baje", result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    @Test
    fun `punctuation around the phrase does not prevent attribution`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), taskWithRawWhen(null))

        val result = attributor.attribute(
            "Kal, shaam 7 baje, Ali ko call karne ka reminder laga do — aur milk khareedne ka task bana do.",
            steps,
        )

        assertEquals("kal shaam 7 baje", result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    @Test
    fun `a raw_when that dropped a ko filler word still attributes when the message has it`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"), taskWithRawWhen(null))

        val result = attributor.attribute(
            "kal shaam ko 7 baje Ali ko call karne ka reminder laga do aur milk khareedne ka task bana do",
            steps,
        )

        assertEquals("kal shaam 7 baje", result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    /**
     * The deliberately unsolved case: a phrase genuinely meant for two
     * steps occurs only once in the message, so only the first claimant
     * keeps it — fails closed rather than silently sharing the meaning.
     */
    @Test
    fun `a phrase intended for two steps is not silently shared`() {
        // "kal subah" alone (no hour) is itself an incomplete phrase — like
        // a bare "4 baje", it never independently resolves a time, so the
        // span finder would not include "subah" in what it finds either.
        // Using the full "kal subah 9 baje" isolates the behavior this test
        // actually exists to prove: a genuinely-shared *complete* phrase is
        // still claimed by only the first step.
        val steps = listOf(reminderWithRawWhen("kal subah 9 baje"), taskWithRawWhen("kal subah 9 baje"))

        val result = attributor.attribute(
            "Kal subah 9 baje dono kaam karne hain: Ali ko call karo aur uska task bana do",
            steps,
        )

        assertEquals("kal subah 9 baje", result[0].parameters.rawWhen)
        assertNull(result[1].parameters.rawWhen)
    }

    @Test
    fun `a single-step list is unaffected`() {
        val steps = listOf(reminderWithRawWhen("kal shaam 7 baje"))

        val result = attributor.attribute("kal shaam 7 baje reminder laga do", steps)

        assertEquals("kal shaam 7 baje", result.single().parameters.rawWhen)
    }

    @Test
    fun `an already-resolved date or time is left untouched regardless of raw_when`() {
        val step = DpsIntent(
            type = IntentType.REMINDER,
            parameters = IntentParameters(title = "x", date = "2026-08-12", time = "19:00", rawWhen = "kal shaam 7 baje"),
        )

        val result = attributor.attribute("kal shaam 7 baje reminder laga do", listOf(step))

        // Attribution only ever touches rawWhen, never date/time.
        assertEquals("2026-08-12", result.single().parameters.date)
        assertEquals("19:00", result.single().parameters.time)
    }
}
