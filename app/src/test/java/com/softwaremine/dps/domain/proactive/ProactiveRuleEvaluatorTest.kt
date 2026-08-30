package com.softwaremine.dps.domain.proactive

import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Verification of [ProactiveRuleEvaluator] (M4-A) — pure Kotlin, no Android. */
class ProactiveRuleEvaluatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val now = 1_700_000_000_000L // fixed instant, never read from the system clock

    private fun task(
        id: Int,
        dueAtMillis: Long? = null,
        status: TaskStatus = TaskStatus.PENDING,
        title: String = "task $id",
    ) = Task(
        id = id,
        title = title,
        status = status,
        dueAtMillis = dueAtMillis,
        createdAtMillis = now - 10_000,
        updatedAtMillis = now - 10_000,
    )

    // -----------------------------------------------------------------
    // overdueTasks
    // -----------------------------------------------------------------

    @Test
    fun `a pending task past its due date is eligible`() {
        val overdue = task(id = 1, dueAtMillis = now - 1_000)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(overdue), now, emptySet())

        assertEquals(listOf(overdue), result)
    }

    @Test
    fun `a completed task past its due date is never eligible`() {
        val completed = task(id = 1, dueAtMillis = now - 1_000, status = TaskStatus.COMPLETED)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(completed), now, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a cancelled task past its due date is never eligible`() {
        // Deliberately excluded even though the brief only named "incomplete"
        // — see ProactiveRuleEvaluator's own doc for why CANCELLED is not
        // "still pending" either.
        val cancelled = task(id = 1, dueAtMillis = now - 1_000, status = TaskStatus.CANCELLED)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(cancelled), now, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a task due in the future is never eligible`() {
        val future = task(id = 1, dueAtMillis = now + 1_000)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(future), now, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a task with no due date is never eligible`() {
        val noDueDate = task(id = 1, dueAtMillis = null)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(noDueDate), now, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a task due exactly at now is not yet overdue`() {
        // Documented choice: strictly less-than. "Due this instant" has not
        // yet passed its deadline — see the evaluator's own doc.
        val dueNow = task(id = 1, dueAtMillis = now)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(dueNow), now, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a task one millisecond past now is overdue`() {
        val justOverdue = task(id = 1, dueAtMillis = now - 1)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(justOverdue), now, emptySet())

        assertEquals(listOf(justOverdue), result)
    }

    @Test
    fun `only eligible tasks are returned from a mixed list`() {
        val overdue = task(id = 1, dueAtMillis = now - 1_000)
        val completed = task(id = 2, dueAtMillis = now - 1_000, status = TaskStatus.COMPLETED)
        val future = task(id = 3, dueAtMillis = now + 1_000)
        val noDueDate = task(id = 4, dueAtMillis = null)
        val alsoOverdue = task(id = 5, dueAtMillis = now - 2_000)

        val result = ProactiveRuleEvaluator.overdueTasks(
            listOf(overdue, completed, future, noDueDate, alsoOverdue),
            now,
            emptySet(),
        )

        assertEquals(setOf(1, 5), result.map { it.id }.toSet())
    }

    @Test
    fun `an already-notified task is excluded even though it is otherwise eligible`() {
        val overdue = task(id = 1, dueAtMillis = now - 1_000)

        val result = ProactiveRuleEvaluator.overdueTasks(listOf(overdue), now, alreadyNotifiedTaskIds = setOf(1))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `evaluation is deterministic across repeated calls with the same input`() {
        val tasks = listOf(
            task(id = 1, dueAtMillis = now - 1_000),
            task(id = 2, dueAtMillis = now + 1_000),
        )

        val first = ProactiveRuleEvaluator.overdueTasks(tasks, now, emptySet())
        val second = ProactiveRuleEvaluator.overdueTasks(tasks, now, emptySet())

        assertEquals(first, second)
    }

    // -----------------------------------------------------------------
    // dateKeyFor / markerKey / taskIdIfMarkerMatches
    // -----------------------------------------------------------------

    @Test
    fun `dateKeyFor produces the same key for two instants on the same local day`() {
        val morning = ProactiveRuleEvaluator.dateKeyFor(now, zone)
        val sameDayLater = ProactiveRuleEvaluator.dateKeyFor(now + 3_600_000L, zone)

        assertEquals(morning, sameDayLater)
    }

    @Test
    fun `markerKey round-trips through taskIdIfMarkerMatches for the same date`() {
        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, zone)
        val marker = ProactiveRuleEvaluator.markerKey(taskId = 42, dateKey = dateKey)

        assertEquals(42, ProactiveRuleEvaluator.taskIdIfMarkerMatches(marker, dateKey))
    }

    @Test
    fun `a marker from a different day does not match today's dateKey`() {
        val today = ProactiveRuleEvaluator.dateKeyFor(now, zone)
        val yesterday = ProactiveRuleEvaluator.dateKeyFor(now - 86_400_000L, zone)
        val yesterdaysMarker = ProactiveRuleEvaluator.markerKey(taskId = 42, dateKey = yesterday)

        assertEquals(
            "A stale day's marker must not suppress today's notification for the same task",
            null,
            ProactiveRuleEvaluator.taskIdIfMarkerMatches(yesterdaysMarker, today),
        )
    }

    @Test
    fun `a malformed marker string is never treated as a match`() {
        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, zone)

        assertEquals(null, ProactiveRuleEvaluator.taskIdIfMarkerMatches("garbage", dateKey))
        assertEquals(null, ProactiveRuleEvaluator.taskIdIfMarkerMatches("task:abc:overdue:$dateKey", dateKey))
    }

    // -----------------------------------------------------------------
    // pruneMarkersOlderThan
    // -----------------------------------------------------------------

    @Test
    fun `pruneMarkersOlderThan keeps markers on or after the cutoff and drops older ones`() {
        val markers = setOf(
            "task:1:overdue:2026-08-01",
            "task:2:overdue:2026-08-05",
            "task:3:overdue:2026-08-10",
        )

        val pruned = ProactiveRuleEvaluator.pruneMarkersOlderThan(markers, cutoffDateKey = "2026-08-05")

        assertEquals(setOf("task:2:overdue:2026-08-05", "task:3:overdue:2026-08-10"), pruned)
    }

    @Test
    fun `pruneMarkersOlderThan leaves an already-empty set empty`() {
        assertTrue(ProactiveRuleEvaluator.pruneMarkersOlderThan(emptySet(), cutoffDateKey = "2026-08-05").isEmpty())
    }

    @Test
    fun `pruneMarkersOlderThan applies identically to event markers, no change needed for M4-B`() {
        val dateKey = ProactiveRuleEvaluator.dateKeyFor(now, zone)
        val eventMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(sourceEventId = 1L, beginMillis = now, zone = zone)

        val pruned = ProactiveRuleEvaluator.pruneMarkersOlderThan(setOf(eventMarker), cutoffDateKey = dateKey)

        assertEquals(setOf(eventMarker), pruned)
    }

    // -----------------------------------------------------------------
    // upcomingEvents (M4-B)
    // -----------------------------------------------------------------

    private fun occurrence(
        sourceEventId: Long = 1L,
        beginMillis: Long,
        endMillis: Long = beginMillis + 3_600_000L,
        title: String = "event $sourceEventId",
        allDay: Boolean = false,
    ) = UpcomingEventOccurrence(
        sourceEventId = sourceEventId,
        beginMillis = beginMillis,
        endMillis = endMillis,
        title = title,
        allDay = allDay,
    )

    private val windowMillis = 3_600_000L // 60 minutes, matching ProactiveCheckWorker's own EVENT_WINDOW_MILLIS

    @Test
    fun `a one-time event starting within the window is eligible`() {
        val upcoming = occurrence(beginMillis = now + 1_800_000L) // 30 minutes from now

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(upcoming), now, windowMillis, emptySet(), zone)

        assertEquals(listOf(upcoming), result)
    }

    @Test
    fun `an event starting after the window is not eligible`() {
        val tooFar = occurrence(beginMillis = now + windowMillis + 1)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(tooFar), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an event that has already started is not eligible`() {
        val started = occurrence(beginMillis = now - 1_000L)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(started), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an event that has already ended is not eligible`() {
        val ended = occurrence(beginMillis = now - 7_200_000L, endMillis = now - 3_600_000L)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(ended), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an event starting exactly at now is not yet upcoming`() {
        // Documented choice, mirroring overdueTasks' own boundary: the
        // instant belongs to "already started," not "still upcoming."
        val startingNow = occurrence(beginMillis = now)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(startingNow), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an event one millisecond after now is upcoming`() {
        val justStarted = occurrence(beginMillis = now + 1)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(justStarted), now, windowMillis, emptySet(), zone)

        assertEquals(listOf(justStarted), result)
    }

    @Test
    fun `an event starting exactly at the window boundary is eligible`() {
        // Documented choice: inclusive at the far edge, to avoid an
        // obvious missed-event gap against the inexact ~30-minute worker cadence.
        val atBoundary = occurrence(beginMillis = now + windowMillis)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(atBoundary), now, windowMillis, emptySet(), zone)

        assertEquals(listOf(atBoundary), result)
    }

    @Test
    fun `an event one millisecond past the window boundary is not eligible`() {
        val pastBoundary = occurrence(beginMillis = now + windowMillis + 1)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(pastBoundary), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an all-day event is never eligible even if it would otherwise be within the window`() {
        val allDay = occurrence(beginMillis = now + 1_800_000L, allDay = true)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(allDay), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an event with a blank title is never eligible`() {
        val blank = occurrence(beginMillis = now + 1_800_000L, title = "   ")

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(blank), now, windowMillis, emptySet(), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an already-notified occurrence is excluded even though it is otherwise eligible`() {
        val upcoming = occurrence(sourceEventId = 7L, beginMillis = now + 1_800_000L)
        val marker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(7L, now + 1_800_000L, zone)

        val result = ProactiveRuleEvaluator.upcomingEvents(listOf(upcoming), now, windowMillis, setOf(marker), zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `two different occurrences of the same recurring event both distinguished by begin time are independently eligible`() {
        val thisWeek = occurrence(sourceEventId = 42L, beginMillis = now + 600_000L, title = "standup")
        val nextWeek = occurrence(sourceEventId = 42L, beginMillis = now + 604_800_000L, title = "standup")

        // nextWeek is outside the 60-minute window on its own, so widen the
        // window for this test to prove both occurrences of the SAME base
        // event id are independently evaluated, not collapsed into one.
        val result = ProactiveRuleEvaluator.upcomingEvents(
            listOf(thisWeek, nextWeek),
            now,
            windowMillis = 604_800_000L + 1,
            alreadyNotifiedMarkers = emptySet(),
            zone = zone,
        )

        assertEquals(listOf(thisWeek, nextWeek), result)
    }

    @Test
    fun `notifying this week's occurrence of a recurring event does not suppress next week's occurrence`() {
        val thisWeek = occurrence(sourceEventId = 42L, beginMillis = now + 600_000L, title = "standup")
        val nextWeek = occurrence(sourceEventId = 42L, beginMillis = now + 604_800_000L, title = "standup")
        val thisWeeksMarker = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(42L, now + 600_000L, zone)

        val result = ProactiveRuleEvaluator.upcomingEvents(
            listOf(thisWeek, nextWeek),
            now,
            windowMillis = 604_800_000L + 1,
            alreadyNotifiedMarkers = setOf(thisWeeksMarker),
            zone = zone,
        )

        assertEquals(
            "Marking one occurrence notified must never suppress a sibling occurrence of the same recurring event",
            listOf(nextWeek),
            result,
        )
    }

    @Test
    fun `results are returned in chronological order regardless of input order`() {
        val third = occurrence(sourceEventId = 1L, beginMillis = now + 3_000_000L)
        val first = occurrence(sourceEventId = 2L, beginMillis = now + 1_000_000L)
        val second = occurrence(sourceEventId = 3L, beginMillis = now + 2_000_000L)

        val result = ProactiveRuleEvaluator.upcomingEvents(
            listOf(third, first, second),
            now,
            windowMillis = 4_000_000L,
            alreadyNotifiedMarkers = emptySet(),
            zone = zone,
        )

        assertEquals(listOf(first, second, third), result)
    }

    @Test
    fun `an exact duplicate occurrence in the input never produces two results`() {
        val occurrence = occurrence(sourceEventId = 5L, beginMillis = now + 1_800_000L)

        val result = ProactiveRuleEvaluator.upcomingEvents(
            listOf(occurrence, occurrence.copy()),
            now,
            windowMillis,
            emptySet(),
            zone,
        )

        assertEquals(
            "The same (sourceEventId, beginMillis) occurrence must never be returned twice",
            listOf(occurrence),
            result,
        )
    }

    @Test
    fun `only eligible occurrences are returned from a mixed list`() {
        val upcoming = occurrence(sourceEventId = 1L, beginMillis = now + 1_800_000L)
        val started = occurrence(sourceEventId = 2L, beginMillis = now - 1_000L)
        val tooFar = occurrence(sourceEventId = 3L, beginMillis = now + windowMillis + 1)
        val allDay = occurrence(sourceEventId = 4L, beginMillis = now + 1_800_000L, allDay = true)
        val blankTitle = occurrence(sourceEventId = 5L, beginMillis = now + 1_800_000L, title = "")

        val result = ProactiveRuleEvaluator.upcomingEvents(
            listOf(upcoming, started, tooFar, allDay, blankTitle),
            now,
            windowMillis,
            emptySet(),
            zone,
        )

        assertEquals(listOf(upcoming), result)
    }

    @Test
    fun `eventOccurrenceMarkerKey distinguishes occurrences by begin time, not just source event id`() {
        val keyA = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(sourceEventId = 9L, beginMillis = now, zone = zone)
        val keyB = ProactiveRuleEvaluator.eventOccurrenceMarkerKey(sourceEventId = 9L, beginMillis = now + 604_800_000L, zone = zone)

        assertTrue("Two occurrences of the same event at different times must never share a marker key", keyA != keyB)
    }
}
