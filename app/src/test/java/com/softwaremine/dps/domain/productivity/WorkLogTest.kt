package com.softwaremine.dps.domain.productivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verification of [WorkLog.effectiveMinutes] — the Day 06 "never fabricate a
 * duration" rule in its smallest testable form.
 */
class WorkLogTest {

    private fun log(
        durationMinutes: Long? = null,
        startAtMillis: Long? = null,
        endAtMillis: Long? = null,
    ) = WorkLog(
        id = 1,
        dateMillis = 0L,
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        durationMinutes = durationMinutes,
        activity = "DBPMS",
        createdAtMillis = 0L,
    )

    @Test
    fun `an explicit duration is used directly`() {
        assertEquals(180L, log(durationMinutes = 180L).effectiveMinutes)
    }

    @Test
    fun `a start and end span is converted to minutes`() {
        val start = 10 * 3_600_000L
        val end = 12 * 3_600_000L
        assertEquals(120L, log(startAtMillis = start, endAtMillis = end).effectiveMinutes)
    }

    @Test
    fun `an explicit duration wins over a start-end span when both are present`() {
        val start = 10 * 3_600_000L
        val end = 12 * 3_600_000L
        assertEquals(45L, log(durationMinutes = 45L, startAtMillis = start, endAtMillis = end).effectiveMinutes)
    }

    @Test
    fun `only an activity name, nothing is fabricated`() {
        assertNull(log().effectiveMinutes)
    }

    @Test
    fun `a start with no end is not turned into a guessed duration`() {
        assertNull(log(startAtMillis = 10 * 3_600_000L).effectiveMinutes)
    }

    @Test
    fun `an end before start is not treated as negative work`() {
        assertNull(log(startAtMillis = 12 * 3_600_000L, endAtMillis = 10 * 3_600_000L).effectiveMinutes)
    }
}
