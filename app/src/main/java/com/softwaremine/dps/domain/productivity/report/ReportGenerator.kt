package com.softwaremine.dps.domain.productivity.report

import com.softwaremine.dps.domain.productivity.ActionItem
import com.softwaremine.dps.domain.productivity.MeetingNote
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskStatus
import com.softwaremine.dps.domain.productivity.WorkLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Aggregates stored productivity records into a report and renders it as text.
 *
 * ## No fabrication — the Day 06 hard constraint
 * [summarize] is a straight filter over whatever lists it is given; nothing
 * is invented, estimated or defaulted to a plausible-looking placeholder.
 * [renderDaily]/[renderWeekly] are template-driven: they decide *which*
 * section to print based on whether that section's list is non-empty, never
 * what to put inside one. Neither method calls the model — a report is
 * deterministic on the same stored data, which the Day 06 brief requires
 * explicitly ("Do NOT ask the LLM to invent the report").
 *
 * ## Dependencies
 * Domain productivity types, `java.time`. No Android.
 */
class ReportGenerator(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun summarize(
        range: DateRange,
        tasks: List<Task>,
        workLogs: List<WorkLog>,
        meetings: List<MeetingNote>,
        actionItems: List<ActionItem>,
    ): PeriodSummary {
        val created = tasks.filter { it.createdAtMillis in range }
        val completed = tasks.filter {
            it.status == TaskStatus.COMPLETED && it.completedAtMillis?.let { at -> at in range } == true
        }
        val pending = tasks.filter { it.status == TaskStatus.PENDING }
        val logsInRange = workLogs.filter { it.dateMillis in range }
        val meetingsInRange = meetings.filter { it.dateMillis in range }
        val itemsInRange = actionItems.filter {
            it.createdAtMillis in range || it.dueAtMillis?.let { due -> due in range } == true
        }

        return PeriodSummary(range, created, completed, pending, logsInRange, meetingsInRange, itemsInRange)
    }

    /** One line per day, sections only where there is data. */
    fun renderDaily(summary: PeriodSummary): String {
        if (summary.isEmpty) return "Nothing recorded for today yet."

        return buildString {
            appendLine("Today's report:")
            section("Completed Tasks", summary.tasksCompleted) { "- ${it.title}" }
            section("Pending Tasks", summary.pendingTasks) { "- ${it.title}" }
            section("Work Done", summary.workLogs) { workLogLine(it) }
            section("Meetings", summary.meetings) { meetingLine(it) }
            section("Action Items", summary.actionItems) { actionItemLine(it) }
        }.trim()
    }

    fun renderWeekly(summary: PeriodSummary): String {
        if (summary.isEmpty) return "Nothing recorded for this week yet."

        return buildString {
            appendLine("This week's productivity summary:")
            appendLine("- Tasks created: ${summary.tasksCreated.size}")
            appendLine("- Tasks completed: ${summary.tasksCompleted.size}")
            appendLine("- Pending tasks: ${summary.pendingTasks.size}")
            if (summary.totalLoggedMinutes > 0) {
                appendLine("- Work logged: ${formatMinutes(summary.totalLoggedMinutes)}")
            }
            section("Meetings", summary.meetings) { meetingLine(it) }
            section("Action Items", summary.actionItems) { actionItemLine(it) }
        }.trim()
    }

    private fun <T> StringBuilder.section(label: String, items: List<T>, line: (T) -> String) {
        if (items.isEmpty()) return
        appendLine()
        appendLine("$label:")
        items.forEach { appendLine(line(it)) }
    }

    private fun workLogLine(log: WorkLog): String {
        val minutes = log.effectiveMinutes
        return if (minutes != null) "- ${log.activity} (${formatMinutes(minutes)})" else "- ${log.activity}"
    }

    private fun meetingLine(meeting: MeetingNote): String {
        val who = meeting.participants.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return if (who != null) "- ${meeting.title} ($who)" else "- ${meeting.title}"
    }

    private fun actionItemLine(item: ActionItem): String {
        val due = item.dueAtMillis?.let { " (due ${formatDate(it)})" }.orEmpty()
        return "- ${item.title}$due"
    }

    private fun formatMinutes(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }

    private fun formatDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
