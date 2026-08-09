package com.softwaremine.dps.domain.productivity.report

import com.softwaremine.dps.domain.productivity.ActionItem
import com.softwaremine.dps.domain.productivity.MeetingNote
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.WorkLog

/**
 * What a daily, weekly or monthly report all reduce to before rendering.
 *
 * One shape for every period — see [DateRange]'s doc for why aggregation
 * does not special-case the period length. [ReportGenerator] renders this
 * differently for a day than for a week, but never aggregates it differently.
 */
data class PeriodSummary(
    val range: DateRange,
    /** Tasks created within [range]. */
    val tasksCreated: List<Task>,
    /** Tasks completed within [range]. */
    val tasksCompleted: List<Task>,
    /** Every task currently pending, regardless of [range] — a report about "now". */
    val pendingTasks: List<Task>,
    val workLogs: List<WorkLog>,
    val meetings: List<MeetingNote>,
    val actionItems: List<ActionItem>,
) {
    /** Sum of every work log's known duration. Entries with none contribute nothing — never guessed. */
    val totalLoggedMinutes: Long get() = workLogs.mapNotNull { it.effectiveMinutes }.sum()

    val isEmpty: Boolean
        get() = tasksCreated.isEmpty() && tasksCompleted.isEmpty() && pendingTasks.isEmpty() &&
            workLogs.isEmpty() && meetings.isEmpty() && actionItems.isEmpty()
}
