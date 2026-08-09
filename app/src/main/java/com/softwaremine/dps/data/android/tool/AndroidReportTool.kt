package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.productivity.ActionItemRepository
import com.softwaremine.dps.domain.productivity.MeetingNoteRepository
import com.softwaremine.dps.domain.productivity.TaskRepository
import com.softwaremine.dps.domain.productivity.WorkLogRepository
import com.softwaremine.dps.domain.productivity.report.DateRange
import com.softwaremine.dps.domain.productivity.report.ReportGenerator
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.Instant
import java.time.ZoneId

/**
 * Generates daily and weekly productivity reports (Day 06).
 *
 * ## Operations
 * | Operation | Result |
 * |---|---|
 * | `daily_report` | today's report, rendered text |
 * | `weekly_report` | this week's summary, rendered text |
 *
 * ## Why this holds no aggregation logic of its own
 * Every field in the rendered report comes from [ReportGenerator.summarize]
 * over data read straight from the four Day 06 repositories — this class
 * only wires them together and picks the period. Duplicating aggregation
 * here would risk it drifting from the JVM-tested version in `domain/`, and
 * the Day 06 brief's "never fabricate" constraint depends on there being
 * exactly one place that decides what counts as "today's tasks".
 *
 * ## Permissions
 * None.
 *
 * ## Dependencies
 * The four Day 06 repositories, [ReportGenerator]. No direct Android imports.
 */
class AndroidReportTool(
    private val tasks: TaskRepository,
    private val workLogs: WorkLogRepository,
    private val meetings: MeetingNoteRepository,
    private val actionItems: ActionItemRepository,
    private val generator: ReportGenerator = ReportGenerator(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.REPORT

    override val operations: Set<String> = setOf(OP_DAILY, OP_WEEKLY)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_DAILY -> ToolResult.Success(summary = generator.renderDaily(summarize(DateRange.day(today(), zone))))
        OP_WEEKLY -> ToolResult.Success(summary = generator.renderWeekly(summarize(DateRange.week(today(), zone))))
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun today() = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()

    private fun summarize(range: DateRange) =
        generator.summarize(range, tasks.all(), workLogs.all(), meetings.all(), actionItems.all())

    private companion object {
        const val OP_DAILY = "daily_report"
        const val OP_WEEKLY = "weekly_report"
    }
}
