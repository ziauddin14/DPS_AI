package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.productivity.WorkLog
import com.softwaremine.dps.domain.productivity.WorkLogRepository
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Creates and lists work-log entries (Day 06).
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_work_log` | `activity`, `notes`, `date`, `start_time`, `duration` | `log_id` |
 * | `list_work_logs` | `date` (optional) | matching entries |
 *
 * ## No fabricated duration
 * [WorkLog.durationMinutes] is only ever set when the caller supplied
 * `duration` and it parses as a recognisable amount+unit — "3 ghante", "2
 * hours". A start/end span is only stored when *both* `start_time` and a
 * parseable `duration` are present; `start_time` alone is kept with no
 * invented end, exactly mirroring [WorkLog]'s own doc.
 *
 * ## Why a range like "10 se 12 baje tak" is only partly captured
 * The shared intent schema (`domain.intent.IntentParameters`) has one `time`
 * field, not a start/end pair, so "10 se 12" is captured as a start time
 * only unless the user *also* states a duration. This is a known,
 * documented schema limitation, not a fabricated fix — see `DAY-06-COMPLETION.md`.
 *
 * ## Permissions
 * None.
 *
 * ## Dependencies
 * [WorkLogRepository], `java.time`. No direct Android imports.
 */
class AndroidWorkLogTool(
    private val repository: WorkLogRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.WORK_LOG

    override val operations: Set<String> = setOf(OP_CREATE, OP_LIST)

    override val requiredPermissions: Set<DpsPermission> = emptySet()

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE -> create(call)
        OP_LIST -> list(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun create(call: ToolCall): ToolResult {
        val activity = call.argument(ARG_ACTIVITY)?.trim().orEmpty()
        val durationMinutes = parseDurationMinutes(call.argument(ARG_DURATION))

        if (activity.isEmpty() && durationMinutes == null) {
            return ToolResult.Failure(
                reason = "I need either what you worked on or how long, to log it.",
                retryable = false,
            )
        }

        val date = call.argument(ARG_DATE)?.let(::parseIsoDate) ?: LocalDate.now(zone)
        val dateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val startAtMillis = call.argument(ARG_START_TIME)?.let(::parseLocalTime)
            ?.let { LocalDateTime.of(date, it).atZone(zone).toInstant().toEpochMilli() }
        val endAtMillis = if (startAtMillis != null && durationMinutes != null) {
            startAtMillis + durationMinutes * MILLIS_PER_MINUTE
        } else {
            null
        }

        val id = repository.nextId()
        repository.save(
            WorkLog(
                id = id,
                dateMillis = dateMillis,
                startAtMillis = startAtMillis,
                endAtMillis = endAtMillis,
                // Stored as an explicit duration only when it did not already
                // become a start/end span above — WorkLog.effectiveMinutes
                // reads whichever is present, never both.
                durationMinutes = if (endAtMillis == null) durationMinutes else null,
                activity = activity.ifEmpty { "Work" },
                notes = call.argument(ARG_NOTES),
                createdAtMillis = now(),
            ),
        )

        return ToolResult.Success(
            summary = "Logged.",
            data = mapOf("log_id" to id.toString()),
        )
    }

    private fun list(call: ToolCall): ToolResult {
        val dateFilter = call.argument(ARG_DATE)?.let(::parseIsoDate)
        val all = repository.all()

        val filtered = if (dateFilter != null) {
            val start = dateFilter.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = dateFilter.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            all.filter { it.dateMillis in start until end }
        } else {
            all.take(MAX_UNFILTERED_RESULTS)
        }

        if (filtered.isEmpty()) return ToolResult.Success(summary = "No work logged for that.")

        val totalMinutes = filtered.mapNotNull { it.effectiveMinutes }.sum()
        val lines = filtered.joinToString("; ") { entry ->
            val minutes = entry.effectiveMinutes
            if (minutes != null) "${entry.activity} (${minutes}m)" else entry.activity
        }
        val totalPart = if (totalMinutes > 0) " Total: ${totalMinutes}m." else ""

        val data = buildMap {
            put("count", filtered.size.toString())
            filtered.forEachIndexed { index, entry ->
                put("log_${index}_id", entry.id.toString())
                put("log_${index}_activity", entry.activity)
            }
        }

        return ToolResult.Success(summary = "$lines.$totalPart", data = data)
    }

    private fun parseIsoDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

    private fun parseLocalTime(raw: String): LocalTime? =
        runCatching { LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
            ?: raw.trim().toIntOrNull()?.takeIf { it in 0..23 }?.let { LocalTime.of(it, 0) }

    /** Extracts an amount+unit ("3 ghante", "2 hours") from free text. `null` when none is found — never guessed. */
    private fun parseDurationMinutes(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = DURATION_PATTERN.find(raw.trim().lowercase()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        return if (unit in HOUR_UNITS) (amount * 60).toLong() else amount.toLong()
    }

    private companion object {
        const val OP_CREATE = "create_work_log"
        const val OP_LIST = "list_work_logs"

        const val ARG_ACTIVITY = "activity"
        const val ARG_NOTES = "notes"
        const val ARG_DATE = "date"
        const val ARG_START_TIME = "start_time"
        const val ARG_DURATION = "duration"

        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_UNFILTERED_RESULTS = 20

        val HOUR_UNITS = setOf("hour", "hours", "hr", "hrs", "ghanta", "ghante", "ghanton")
        val DURATION_PATTERN = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|ghanta|ghante|ghanton|minutes?|mins?)",
        )
    }
}
