package com.softwaremine.dps.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of deterministic temporal-phrase resolution (Day 08-E).
 *
 * ## What this proves
 * That [TemporalPhraseResolver] — never the classification model — is the
 * only path by which a `date`/`time` reaches
 * [com.softwaremine.dps.ai.intent.ToolSelector], and that it does so
 * correctly for the documented vocabulary and fails closed (both fields
 * `null`, never a guess) for everything else.
 *
 * ## The fixed clock
 * `2026-08-11T09:00` (Tuesday) is the same clock the Day 08-D controlled
 * retest used, chosen so every mandatory case (A–E) has an unambiguous
 * answer: "kal" is tomorrow, 4pm is hours away the same day, tomorrow
 * evening/night are both comfortably future, and 20 August is nine days out.
 */
class TemporalPhraseResolverTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val fixedNow = LocalDateTime.of(2026, 8, 11, 9, 0)
    private val resolver = TemporalPhraseResolver(zone = zone, now = { fixedNow })

    // -----------------------------------------------------------------
    // The five mandatory cases (Day 08-D's controlled retest, Day 08-E's design)
    // -----------------------------------------------------------------

    /** A. "20 August ko meeting ka reminder laga do." */
    @Test
    fun `case A - an explicit future date resolves, with no time`() {
        val resolution = resolver.resolve("20 August")

        assertEquals("2026-08-20", resolution.date)
        assertNull("A bare date must not invent a time", resolution.time)
    }

    /** B. "4 baje mujhe call karne ka reminder laga do." — genuinely ambiguous AM/PM. */
    @Test
    fun `case B - a bare hour with no period word is not resolved`() {
        val resolution = resolver.resolve("4 baje")

        assertNull("Bare 'X baje' must never be guessed", resolution.date)
        assertNull("Bare 'X baje' must never be guessed", resolution.time)
    }

    /** C. "Kal mujhe yaad dila dena ke meeting hai." — day only, no time at all. */
    @Test
    fun `case C - a bare day word resolves the date only`() {
        val resolution = resolver.resolve("kal")

        assertEquals("2026-08-12", resolution.date)
        assertNull("No time was said, so none must be invented", resolution.time)
    }

    /** D. "Kal shaam 7 baje mujhe yaad dila dena." */
    @Test
    fun `case D - day plus evening period plus hour resolves fully`() {
        val resolution = resolver.resolve("kal shaam 7 baje")

        assertEquals("2026-08-12", resolution.date)
        assertEquals("19:00", resolution.time)
    }

    /** E. "Kal raat 11 baje mujhe yaad dila dena." */
    @Test
    fun `case E - day plus night period plus hour resolves fully`() {
        val resolution = resolver.resolve("kal raat 11 baje")

        assertEquals("2026-08-12", resolution.date)
        assertEquals("23:00", resolution.time)
    }

    /** The exact value that must never appear: Now itself, copied verbatim (the Day 08-D failure). */
    @Test
    fun `none of the five mandatory cases resolve to Now's own date and time`() {
        val cases = listOf("20 August", "4 baje", "kal", "kal shaam 7 baje", "kal raat 11 baje")

        cases.forEach { rawWhen ->
            val resolution = resolver.resolve(rawWhen)
            assertEquals(
                "'$rawWhen' must never resolve to Now's own date",
                false,
                resolution.date == "2026-08-11",
            )
            assertEquals(
                "'$rawWhen' must never resolve to Now's own time",
                false,
                resolution.time == "09:00",
            )
        }
    }

    // -----------------------------------------------------------------
    // Day words
    // -----------------------------------------------------------------

    @Test
    fun `aaj and today resolve to the same day`() {
        assertEquals("2026-08-11", resolver.resolve("aaj").date)
        assertEquals("2026-08-11", resolver.resolve("today").date)
    }

    @Test
    fun `parso and parson resolve two days ahead`() {
        assertEquals("2026-08-13", resolver.resolve("parso").date)
        assertEquals("2026-08-13", resolver.resolve("parson").date)
    }

    // -----------------------------------------------------------------
    // Period words + explicit hour
    // -----------------------------------------------------------------

    @Test
    fun `subah with an hour resolves to the morning`() {
        assertEquals("09:00", resolver.resolve("subah 9 baje").time)
        assertEquals("00:00", resolver.resolve("subah 12 baje").time)
    }

    @Test
    fun `dopahar with an hour resolves to midday or early afternoon`() {
        assertEquals("12:00", resolver.resolve("dopahar 12 baje").time)
        assertEquals("14:00", resolver.resolve("dopahar 2 baje").time)
    }

    @Test
    fun `shaam with an hour resolves to the evening`() {
        assertEquals("18:00", resolver.resolve("shaam 6 baje").time)
        assertEquals("12:00", resolver.resolve("shaam 12 baje").time)
    }

    @Test
    fun `raat with an hour resolves to night, wrapping midnight at 12`() {
        assertEquals("23:00", resolver.resolve("raat 11 baje").time)
        assertEquals("00:00", resolver.resolve("raat 12 baje").time)
    }

    // -----------------------------------------------------------------
    // Explicit 24-hour / colon time
    // -----------------------------------------------------------------

    @Test
    fun `an unambiguous colon time is read directly`() {
        assertEquals("16:00", resolver.resolve("16:00").time)
        assertEquals("04:30", resolver.resolve("4:30").time)
    }

    /**
     * Day 08-E audit finding: a colon-time paired with an am/pm marker is a
     * 12-hour value this vocabulary does not convert. Silently keeping the
     * digits and discarding "pm" would resolve "7:30 pm" as 07:30 — a
     * silently wrong time, not a missing one. This must fail closed instead.
     */
    @Test
    fun `a colon time qualified with pm is not silently misread as 24-hour`() {
        val resolution = resolver.resolve("7:30 pm")

        assertNull("7:30 pm must not be silently read as 07:30", resolution.time)
    }

    @Test
    fun `a colon time qualified with am is not silently misread as 24-hour`() {
        val resolution = resolver.resolve("4:00 am")

        assertNull("4:00 am must not be silently read as 04:00 without validating the marker", resolution.time)
    }

    @Test
    fun `a colon time with no am pm marker still resolves as before`() {
        // Regression guard: the am/pm check must not affect the ordinary,
        // already-supported 24-hour colon-time path.
        assertEquals("19:30", resolver.resolve("19:30").time)
        assertEquals("07:30", resolver.resolve("7:30").time)
    }

    // -----------------------------------------------------------------
    // Trailing particles
    // -----------------------------------------------------------------

    @Test
    fun `trailing ko ke ki particles are stripped before matching`() {
        assertEquals("2026-08-20", resolver.resolve("20 August ko").date)
        assertEquals("2026-08-12", resolver.resolve("kal ke").date)
    }

    // -----------------------------------------------------------------
    // Absolute date rollover
    // -----------------------------------------------------------------

    @Test
    fun `an absolute date already passed this year rolls to next year`() {
        // Fixed clock is 2026-08-11; 1 January this year has already passed.
        val resolution = resolver.resolve("1 January")

        assertEquals("2027-01-01", resolution.date)
    }

    @Test
    fun `an absolute date later this year stays in this year`() {
        val resolution = resolver.resolve("20 August")

        assertEquals("2026-08-20", resolution.date)
    }

    // -----------------------------------------------------------------
    // Fail closed — malformed and unsupported input
    // -----------------------------------------------------------------

    @Test
    fun `an unsupported phrase resolves to nothing`() {
        val resolution = resolver.resolve("agle hafte")

        assertNull(resolution.date)
        assertNull(resolution.time)
    }

    @Test
    fun `an invalid day of month resolves to nothing rather than throwing`() {
        val resolution = resolver.resolve("32 August")

        assertNull(resolution.date)
    }

    @Test
    fun `an out-of-range hour resolves to nothing, even with a period word`() {
        val resolution = resolver.resolve("raat 25 baje")

        assertNull("An impossible hour must never be guessed at", resolution.time)
    }

    @Test
    fun `zero baje resolves to nothing`() {
        assertNull(resolver.resolve("0 baje").time)
    }

    @Test
    fun `an empty or blank phrase resolves to nothing`() {
        assertEquals(resolver.unresolved, resolver.resolve(""))
        assertEquals(resolver.unresolved, resolver.resolve("   "))
    }

    @Test
    fun `a null phrase resolves to nothing`() {
        assertEquals(resolver.unresolved, resolver.resolve(null))
    }

    @Test
    fun `gibberish never throws`() {
        listOf("###", "asdkjfh", "baje baje baje", "kal kal kal", "-1 baje", "99 August").forEach { input ->
            resolver.resolve(input) // must not throw
        }
    }

    @Test
    fun `an unknown month name resolves to nothing`() {
        val resolution = resolver.resolve("20 Smarch")

        assertNull(resolution.date)
    }

    // -----------------------------------------------------------------
    // Determinism
    // -----------------------------------------------------------------

    @Test
    fun `the same phrase against the same clock always resolves identically`() {
        val first = resolver.resolve("kal shaam 7 baje")
        val second = resolver.resolve("kal shaam 7 baje")
        val third = resolver.resolve("kal shaam 7 baje")

        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `resolution is case-insensitive`() {
        assertEquals(resolver.resolve("KAL SHAAM 7 BAJE"), resolver.resolve("kal shaam 7 baje"))
    }
}
