package com.softwaremine.dps.data.android.common

import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Argument parsing and validation (Day 05 Phase B).
 *
 * ## Why this is worth testing hard
 * These arguments come from a language model. Malformed input is not an edge
 * case here, it is the steady state — and every one of these failures must
 * surface as a `ToolResult.Failure` the assistant can explain, never as an
 * exception through a layer contractually forbidden from throwing.
 *
 * Pure Kotlin, so it runs on the JVM with no device.
 */
class ToolArgumentsTest {

    private fun call(vararg args: Pair<String, String>) =
        ToolCall(ToolId.CALENDAR, "create_event", args.toMap())

    private val utc: ZoneId = ZoneId.of("UTC")

    // -----------------------------------------------------------------
    // Required arguments
    // -----------------------------------------------------------------

    @Test
    fun `required returns the trimmed value`() {
        val parsed = ToolArguments.required(call("title" to "  Lunch  "), "title")
        assertEquals("Lunch", (parsed as ToolArguments.Parsed.Value).value)
    }

    @Test
    fun `missing required argument becomes a Failure naming the key`() {
        val parsed = ToolArguments.required(call(), "title")

        val failure = (parsed as ToolArguments.Parsed.Invalid).failure
        assertTrue(failure.reason.contains("title"))
        // The model can fix this itself, so it is not worth retrying blindly.
        assertTrue(!failure.retryable)
    }

    @Test
    fun `blank required argument is treated as missing`() {
        val parsed = ToolArguments.required(call("title" to "   "), "title")
        assertTrue(parsed is ToolArguments.Parsed.Invalid)
    }

    // -----------------------------------------------------------------
    // Time parsing
    // -----------------------------------------------------------------

    @Test
    fun `epoch milliseconds are accepted verbatim`() {
        val millis = 1_800_000_000_000L
        val parsed = ToolArguments.time(call("start" to millis.toString()), "start", utc)

        assertEquals(millis, (parsed as ToolArguments.Parsed.Value).value)
    }

    @Test
    fun `ISO-8601 local date-time is resolved against the supplied zone`() {
        val parsed = ToolArguments.time(call("start" to "2026-08-06T14:30"), "start", utc)

        val expected = ZonedDateTime.of(2026, 8, 6, 14, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(expected, (parsed as ToolArguments.Parsed.Value).value)
    }

    @Test
    fun `the same ISO time in different zones yields different instants`() {
        val karachi = ZoneId.of("Asia/Karachi")

        val utcMillis = (ToolArguments.time(call("start" to "2026-08-06T14:30"), "start", utc)
            as ToolArguments.Parsed.Value).value
        val karachiMillis = (ToolArguments.time(call("start" to "2026-08-06T14:30"), "start", karachi)
            as ToolArguments.Parsed.Value).value

        // Karachi is UTC+5, so the same wall clock is 5 hours earlier in UTC.
        assertEquals(5 * 60 * 60 * 1000L, utcMillis - karachiMillis)
    }

    /**
     * Epoch *seconds* mistaken for milliseconds would silently schedule in 1970
     * — a reminder that never fires, with nothing to indicate why.
     */
    @Test
    fun `epoch seconds are rejected rather than silently scheduled in 1970`() {
        val parsed = ToolArguments.time(call("start" to "1800000000"), "start", utc)

        val failure = (parsed as ToolArguments.Parsed.Invalid).failure
        assertTrue(
            "Message should explain the units: ${failure.reason}",
            failure.reason.contains("seconds"),
        )
    }

    @Test
    fun `unparseable time explains the accepted formats`() {
        val parsed = ToolArguments.time(call("start" to "next tuesday"), "start", utc)

        val failure = (parsed as ToolArguments.Parsed.Invalid).failure
        assertTrue(failure.reason.contains("ISO-8601"))
    }

    @Test
    fun `optionalTime returns null when absent and parses when present`() {
        val absent = ToolArguments.optionalTime(call(), "end", utc)
        assertNull((absent as ToolArguments.Parsed.Value).value)

        val present = ToolArguments.optionalTime(call("end" to "2026-08-06T15:30"), "end", utc)
        assertTrue((present as ToolArguments.Parsed.Value).value != null)
    }

    @Test
    fun `optionalTime still rejects a malformed value`() {
        val parsed = ToolArguments.optionalTime(call("end" to "garbage"), "end", utc)
        assertTrue(parsed is ToolArguments.Parsed.Invalid)
    }

    // -----------------------------------------------------------------
    // Booleans
    // -----------------------------------------------------------------

    /** Models express affirmatives several ways; rejecting "yes" reads as obtuse. */
    @Test
    fun `boolean accepts the forms a model actually produces`() {
        listOf("true", "TRUE", "yes", "1", "y").forEach {
            assertTrue("'$it' should be true", ToolArguments.boolean(call("all_day" to it), "all_day"))
        }
        listOf("false", "no", "0", "n").forEach {
            assertTrue("'$it' should be false", !ToolArguments.boolean(call("all_day" to it), "all_day"))
        }
    }

    @Test
    fun `boolean falls back to the default for absent or unrecognised values`() {
        assertTrue(ToolArguments.boolean(call(), "all_day", default = true))
        assertTrue(ToolArguments.boolean(call("all_day" to "maybe"), "all_day", default = true))
    }

    // -----------------------------------------------------------------
    // Timezone
    // -----------------------------------------------------------------

    @Test
    fun `zone resolves a valid id`() {
        assertEquals(
            ZoneId.of("Asia/Karachi"),
            ToolArguments.zone(call("timezone" to "Asia/Karachi")),
        )
    }

    /** Falling back beats refusing: the device zone is close to the user's intent. */
    @Test
    fun `unknown zone falls back to the device default rather than failing`() {
        assertEquals(
            ZoneId.systemDefault(),
            ToolArguments.zone(call("timezone" to "Mars/Olympus_Mons")),
        )
        assertEquals(ZoneId.systemDefault(), ToolArguments.zone(call()))
    }

    // -----------------------------------------------------------------
    // Ordering and future checks
    // -----------------------------------------------------------------

    @Test
    fun `end must be strictly after start`() {
        assertTrue(ToolArguments.requireOrdered(1000, 2000) is ToolArguments.Parsed.Value)
        assertTrue(ToolArguments.requireOrdered(2000, 1000) is ToolArguments.Parsed.Invalid)
        assertTrue(ToolArguments.requireOrdered(1000, 1000) is ToolArguments.Parsed.Invalid)
    }

    @Test
    fun `requireFuture rejects the past but tolerates small clock skew`() {
        val now = 1_800_000_000_000L

        assertTrue(ToolArguments.requireFuture(now + 60_000, now, "time") is ToolArguments.Parsed.Value)
        assertTrue(ToolArguments.requireFuture(now - 3_600_000, now, "time") is ToolArguments.Parsed.Invalid)
        // "Now" must still be accepted — a reminder set for this moment is valid.
        assertTrue(ToolArguments.requireFuture(now, now, "time") is ToolArguments.Parsed.Value)
    }
}
