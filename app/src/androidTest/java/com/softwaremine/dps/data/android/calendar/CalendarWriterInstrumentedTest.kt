package com.softwaremine.dps.data.android.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.domain.permission.DpsPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Real-device verification of [CalendarWriter.findUpcomingInstances] (M4-B) —
 * the first test coverage [CalendarWriter] has ever had; every prior
 * verification of this class was indirect, through
 * [com.softwaremine.dps.data.android.tool.AndroidCalendarTool]'s own
 * instrumented tests.
 *
 * ## Why a recurring event is inserted via raw `ContentValues`, not [CalendarWriter]
 * [CalendarWriter.insertEvent] only implements non-recurring inserts (its
 * own class doc says so explicitly) — there is no production write path in
 * this codebase for creating a recurring event. The thing under test here is
 * the *read* side ([findUpcomingInstances]), not event creation, so the test
 * fixture is built with the minimum raw `CalendarContract.Events` columns
 * Android's documentation requires for a recurring event
 * (`DTSTART`/`DURATION`/`RRULE`, not `DTEND`) — this does not exercise or
 * claim any new production write capability, only sets up real
 * `CalendarProvider` state for the real read method to be tested against.
 */
@RunWith(AndroidJUnit4::class)
class CalendarWriterInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()
    private val writer = CalendarWriter(context, logger)

    private fun hasCalendarPermission(): Boolean {
        val manager = AndroidPermissionManager(context, logger)
        return manager.state(DpsPermission.READ_CALENDAR).isUsable && manager.state(DpsPermission.WRITE_CALENDAR).isUsable
    }

    /** Inserts a recurring event directly, bypassing [CalendarWriter] entirely — see this file's own class doc for why. */
    private fun insertRecurringEvent(calendarId: Long, title: String, firstStartMillis: Long, rrule: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, firstStartMillis)
            put(CalendarContract.Events.DURATION, "PT30M")
            put(CalendarContract.Events.RRULE, rrule)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Test fixture setup failed: calendar provider rejected the recurring event insert.")
        return ContentUris.parseId(uri)
    }

    private fun deleteEvent(eventId: Long) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null,
        )
    }

    @Test
    fun aOneTimeUpcomingEventIsFoundWithCorrectFields() {
        if (!hasCalendarPermission()) return // calendar permission not granted on this run

        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return // no writable calendar on this run

        val now = System.currentTimeMillis()
        val start = now + TimeUnit.MINUTES.toMillis(30)
        val end = start + TimeUnit.HOURS.toMillis(1)
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B one-time instance check",
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        assertTrue("Expected Created, got $created", created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val outcome = writer.findUpcomingInstances(now, now + TimeUnit.HOURS.toMillis(2), limit = 20)
            assertTrue("Expected Found, got $outcome", outcome is CalendarWriter.InstanceQueryOutcome.Found)
            val match = (outcome as CalendarWriter.InstanceQueryOutcome.Found).occurrences
                .singleOrNull { it.sourceEventId == eventId }
            assertNotNull("The just-created one-time event must appear as an upcoming instance", match)
            assertEquals("M4-B one-time instance check", match!!.title)
            assertEquals(start, match.beginMillis)
            assertEquals(end, match.endMillis)
            assertTrue(!match.allDay)
        } finally {
            deleteEvent(eventId)
        }
    }

    /**
     * M7 timestamp-calibration gate — real-device evidence, not assumption.
     *
     * The M7 implementation plan's own instruction is explicit: do not lock
     * a comparison tolerance for [CalendarWriter.readEventSnapshot] until a
     * real insert-then-read-back round trip against this device's actual
     * Calendar Provider proves whether millisecond precision on `DTSTART`/
     * `DTEND` survives exactly. This test **is** that proof — asserting
     * exact equality (not "close enough") is the test itself failing loudly
     * the moment the provider does not preserve precision, which is exactly
     * the signal M7's design needs before any tolerance decision is made.
     *
     * This exercises the base `Events` table directly (via
     * [CalendarWriter.readEventSnapshot]) rather than
     * [CalendarWriter.findUpcomingInstances]'s own `Instances` view, which
     * [aOneTimeUpcomingEventIsFoundWithCorrectFields] above already shows
     * preserves exact millis — `Instances` is a provider-computed
     * expansion of `Events`, not independent evidence for the raw table
     * `readEventSnapshot` actually reads.
     */
    @Test
    fun readEventSnapshotPreservesExactStartAndEndMillisOnRealProviderRoundTrip() {
        if (!hasCalendarPermission()) return // calendar permission not granted on this run

        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return // no writable calendar on this run

        val now = System.currentTimeMillis()
        // Deliberately not round numbers — a provider that truncates to the
        // nearest second/minute would still pass an equality check against
        // an already-round millis value, hiding exactly the behavior this
        // test exists to surface.
        val start = now + TimeUnit.MINUTES.toMillis(45) + 137L
        val end = start + TimeUnit.MINUTES.toMillis(37) + 891L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M7 timestamp calibration",
            description = null,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = false,
            timezone = TimeZone.getDefault().id,
        )
        assertTrue("Expected Created, got $created", created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val outcome = writer.readEventSnapshot(eventId)
            assertTrue("Expected Found, got $outcome", outcome is CalendarEventReader.EventSnapshotOutcome.Found)
            val snapshot = (outcome as CalendarEventReader.EventSnapshotOutcome.Found).snapshot

            assertEquals("M7 timestamp calibration", snapshot.title)
            assertEquals(
                "Calendar Provider must preserve exact millisecond precision on DTSTART for M7's " +
                    "exact-equality comparison to be valid — see this test's own class doc",
                start,
                snapshot.startMillis,
            )
            assertEquals(
                "Calendar Provider must preserve exact millisecond precision on DTEND for M7's " +
                    "exact-equality comparison to be valid — see this test's own class doc",
                end,
                snapshot.endMillis,
            )
        } finally {
            deleteEvent(eventId)
        }
    }

    @Test
    fun anAllDayEventIsReportedAsAllDay() {
        if (!hasCalendarPermission()) return
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return

        // All-day events are conventionally stored as UTC-midnight boundaries.
        val utcMidnightTomorrow = (System.currentTimeMillis() / 86_400_000L + 1) * 86_400_000L
        val created = writer.insertEvent(
            calendarId = target.calendarId,
            title = "M4-B all-day instance check",
            description = null,
            location = null,
            startMillis = utcMidnightTomorrow,
            endMillis = utcMidnightTomorrow + 86_400_000L,
            allDay = true,
            timezone = "UTC",
        )
        assertTrue(created is CalendarWriter.InsertOutcome.Created)
        val eventId = (created as CalendarWriter.InsertOutcome.Created).eventId

        try {
            val outcome = writer.findUpcomingInstances(
                System.currentTimeMillis(),
                utcMidnightTomorrow + 2 * 86_400_000L,
                limit = 20,
            )
            assertTrue(outcome is CalendarWriter.InstanceQueryOutcome.Found)
            val match = (outcome as CalendarWriter.InstanceQueryOutcome.Found).occurrences
                .singleOrNull { it.sourceEventId == eventId }
            assertNotNull("The all-day event must still be readable (exclusion is the evaluator's job, not this method's)", match)
            assertTrue("ALL_DAY must be read back correctly as true", match!!.allDay)
        } finally {
            deleteEvent(eventId)
        }
    }

    @Test
    fun aFutureOccurrenceOfARecurringEventIsFoundEvenThoughTheSeriesStartedInThePast() {
        if (!hasCalendarPermission()) return
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return

        val now = System.currentTimeMillis()
        // The series' own DTSTART is in the past — the exact condition
        // findEvents() cannot handle (see this class's own KDoc) — with a
        // daily recurrence, so "now" always falls within an active series.
        val seriesStart = now - TimeUnit.DAYS.toMillis(10)
        val eventId = insertRecurringEvent(
            calendarId = target.calendarId,
            title = "M4-B recurring instance check",
            firstStartMillis = seriesStart,
            rrule = "FREQ=DAILY;COUNT=30",
        )

        try {
            val outcome = writer.findUpcomingInstances(now, now + TimeUnit.DAYS.toMillis(2), limit = 20)
            assertTrue("Expected Found, got $outcome", outcome is CalendarWriter.InstanceQueryOutcome.Found)
            val matches = (outcome as CalendarWriter.InstanceQueryOutcome.Found).occurrences
                .filter { it.sourceEventId == eventId }

            assertTrue(
                "A future occurrence of a recurring series that began in the past must still be found — this is the entire reason findUpcomingInstances exists instead of reusing findEvents()",
                matches.isNotEmpty(),
            )
            // Not "every match begins after now": the provider's window is an
            // overlap query, so today's occurrence can legitimately appear
            // even if it began slightly before `now` but has not ended yet.
            // Filtering to strictly-future is deliberately
            // ProactiveRuleEvaluator's job, not this method's — the property
            // actually under test here is that at least one genuinely future
            // occurrence is present.
            assertTrue(
                "At least one occurrence must genuinely start after now",
                matches.any { it.beginMillis > now },
            )
        } finally {
            deleteEvent(eventId)
        }
    }

    @Test
    fun twoDistinctFutureOccurrencesOfTheSameRecurringEventAreBothFoundWithDifferentBeginTimes() {
        if (!hasCalendarPermission()) return
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return

        val now = System.currentTimeMillis()
        val seriesStart = now - TimeUnit.DAYS.toMillis(3)
        val eventId = insertRecurringEvent(
            calendarId = target.calendarId,
            title = "M4-B two-occurrence check",
            firstStartMillis = seriesStart,
            rrule = "FREQ=DAILY;COUNT=10",
        )

        try {
            val outcome = writer.findUpcomingInstances(now, now + TimeUnit.DAYS.toMillis(3), limit = 20)
            assertTrue(outcome is CalendarWriter.InstanceQueryOutcome.Found)
            val matches = (outcome as CalendarWriter.InstanceQueryOutcome.Found).occurrences
                .filter { it.sourceEventId == eventId }

            assertTrue(
                "Expected at least two distinct future occurrences within a 3-day window of a daily series, got ${matches.size}",
                matches.size >= 2,
            )
            val distinctBeginTimes = matches.map { it.beginMillis }.toSet()
            assertEquals(
                "Every occurrence of the same recurring event must have its own distinct begin time",
                matches.size,
                distinctBeginTimes.size,
            )
        } finally {
            deleteEvent(eventId)
        }
    }

    @Test
    fun resultsAreOrderedByBeginTimeAscendingAndBoundedByLimit() {
        if (!hasCalendarPermission()) return
        val target = writer.findWritableCalendar()
        if (target !is CalendarWriter.CalendarTarget.Found) return

        val now = System.currentTimeMillis()
        val seriesStart = now - TimeUnit.DAYS.toMillis(1)
        val eventId = insertRecurringEvent(
            calendarId = target.calendarId,
            title = "M4-B ordering check",
            firstStartMillis = seriesStart,
            rrule = "FREQ=DAILY;COUNT=10",
        )

        try {
            val outcome = writer.findUpcomingInstances(now, now + TimeUnit.DAYS.toMillis(5), limit = 2)
            assertTrue(outcome is CalendarWriter.InstanceQueryOutcome.Found)
            val occurrences = (outcome as CalendarWriter.InstanceQueryOutcome.Found).occurrences

            assertTrue("limit=2 must be respected", occurrences.size <= 2)
            assertEquals(occurrences.sortedBy { it.beginMillis }, occurrences)
        } finally {
            deleteEvent(eventId)
        }
    }
}
