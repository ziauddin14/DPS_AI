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
}
