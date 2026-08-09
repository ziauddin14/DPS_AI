package com.softwaremine.dps.domain.productivity.report

import com.softwaremine.dps.domain.productivity.ActionItem
import com.softwaremine.dps.domain.productivity.MeetingNote
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import com.softwaremine.dps.domain.productivity.WorkLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Verification of [ReportGenerator] — the Day 06 report layer.
 *
 * ## What these tests protect
 * "NEVER fabricate: tasks, meetings, work hours, achievements, deadlines,
 * participants" is the brief's hardest constraint for this class. Every test
 * here either proves a record correctly appears when it is genuinely in
 * range, or — just as important — that it does *not* appear when it is not,
 * so a report can never show more than what was actually stored.
 */
class ReportGeneratorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val generator = ReportGenerator(zone = zone)

    private fun millisOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private val today = LocalDate.of(2026, 8, 10)
    private val yesterday = today.minusDays(1)

    private fun task(
        id: Int,
        title: String,
        status: TaskStatus = TaskStatus.PENDING,
        createdAt: LocalDate = today,
        completedAt: LocalDate? = null,
    ) = Task(
        id = id,
        title = title,
        status = status,
        createdAtMillis = millisOf(createdAt),
        updatedAtMillis = millisOf(createdAt),
        completedAtMillis = completedAt?.let(::millisOf),
    )

    // -----------------------------------------------------------------
    // No fabrication: empty in, empty out
    // -----------------------------------------------------------------

    @Test
    fun `an empty day produces an empty summary and an honest message`() {
        val summary = generator.summarize(DateRange.day(today, zone), emptyList(), emptyList(), emptyList(), emptyList())

        assertTrue(summary.isEmpty)
        assertEquals("Nothing recorded for today yet.", generator.renderDaily(summary))
    }

    @Test
    fun `an empty week is reported as empty rather than printing zero-filled sections`() {
        val summary = generator.summarize(DateRange.week(today, zone), emptyList(), emptyList(), emptyList(), emptyList())

        assertTrue(summary.isEmpty)
        assertEquals("Nothing recorded for this week yet.", generator.renderWeekly(summary))
    }

    // -----------------------------------------------------------------
    // Date-range filtering — the "monthly reporting foundation"
    // -----------------------------------------------------------------

    @Test
    fun `a task created yesterday does not appear in today's report`() {
        val tasks = listOf(task(1, "old task", createdAt = yesterday))
        val summary = generator.summarize(DateRange.day(today, zone), tasks, emptyList(), emptyList(), emptyList())

        assertTrue(summary.tasksCreated.isEmpty())
    }

    @Test
    fun `a task completed today appears in today's completed list`() {
        val tasks = listOf(task(1, "finish docs", status = TaskStatus.COMPLETED, createdAt = yesterday, completedAt = today))
        val summary = generator.summarize(DateRange.day(today, zone), tasks, emptyList(), emptyList(), emptyList())

        assertEquals(listOf("finish docs"), summary.tasksCompleted.map { it.title })
    }

    @Test
    fun `pending tasks appear regardless of when they were created`() {
        val tasks = listOf(task(1, "long-standing task", createdAt = today.minusMonths(2)))
        val summary = generator.summarize(DateRange.day(today, zone), tasks, emptyList(), emptyList(), emptyList())

        assertEquals(listOf("long-standing task"), summary.pendingTasks.map { it.title })
    }

    @Test
    fun `a work log outside the week is excluded from the weekly total`() {
        val inWeek = WorkLog(
            id = 1,
            dateMillis = millisOf(today),
            durationMinutes = 120,
            activity = "DBPMS",
            createdAtMillis = 0L,
        )
        val outsideWeek = WorkLog(
            id = 2,
            dateMillis = millisOf(today.minusWeeks(2)),
            durationMinutes = 600,
            activity = "old project",
            createdAtMillis = 0L,
        )

        val summary = generator.summarize(
            DateRange.week(today, zone),
            emptyList(),
            listOf(inWeek, outsideWeek),
            emptyList(),
            emptyList(),
        )

        assertEquals(listOf(inWeek), summary.workLogs)
        assertEquals(120L, summary.totalLoggedMinutes)
    }

    @Test
    fun `total logged minutes never counts an entry with no known duration`() {
        val noDuration = WorkLog(id = 1, dateMillis = millisOf(today), activity = "meeting prep", createdAtMillis = 0L)
        val summary = generator.summarize(DateRange.day(today, zone), emptyList(), listOf(noDuration), emptyList(), emptyList())

        assertEquals(0L, summary.totalLoggedMinutes)
    }

    @Test
    fun `a meeting on a different day is excluded from today's report`() {
        val meeting = MeetingNote(id = 1, title = "standup", dateMillis = millisOf(yesterday), createdAtMillis = 0L)
        val summary = generator.summarize(DateRange.day(today, zone), emptyList(), emptyList(), listOf(meeting), emptyList())

        assertTrue(summary.meetings.isEmpty())
    }

    @Test
    fun `an action item due today is included even if created earlier`() {
        val item = ActionItem(id = 1, title = "send report", dueAtMillis = millisOf(today), createdAtMillis = millisOf(yesterday))
        val summary = generator.summarize(DateRange.day(today, zone), emptyList(), emptyList(), emptyList(), listOf(item))

        assertEquals(listOf("send report"), summary.actionItems.map { it.title })
    }

    // -----------------------------------------------------------------
    // Rendering — sections appear only when there is real data
    // -----------------------------------------------------------------

    @Test
    fun `daily render omits sections with nothing to show`() {
        val tasks = listOf(task(1, "write tests"))
        val summary = generator.summarize(DateRange.day(today, zone), tasks, emptyList(), emptyList(), emptyList())
        val rendered = generator.renderDaily(summary)

        assertTrue(rendered.contains("Pending Tasks"))
        assertFalse(rendered.contains("Work Done"))
        assertFalse(rendered.contains("Meetings"))
        assertFalse(rendered.contains("Action Items"))
        assertFalse(rendered.contains("Completed Tasks"))
    }

    @Test
    fun `weekly render includes the logged-work line only when minutes were actually logged`() {
        val withDuration = WorkLog(id = 1, dateMillis = millisOf(today), durationMinutes = 90, activity = "x", createdAtMillis = 0L)
        val summary = generator.summarize(DateRange.week(today, zone), emptyList(), listOf(withDuration), emptyList(), emptyList())
        val rendered = generator.renderWeekly(summary)

        assertTrue(rendered.contains("Work logged: 1h 30m"))
    }
}
