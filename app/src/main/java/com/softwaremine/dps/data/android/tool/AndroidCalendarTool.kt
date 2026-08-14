package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.calendar.CalendarWriter
import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import java.time.LocalDate
import java.time.ZoneId

/**
 * Creates, updates, deletes and lists calendar events.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_event` | `title` (required), `start` (required), `end`, `description`, `location`, `all_day`, `timezone` | [ToolResult.Success] with `event_id` |
 * | `update_event` | `id` (required), `title`, `start`, `end`, `timezone` | [ToolResult.Success] with `event_id` |
 * | `delete_event` | `id` (required) | [ToolResult.Success] with `event_id`, or [ToolResult.Failure] if no such event |
 * | `list_events` | `date` (optional, ISO) | [ToolResult.Success] with flattened `event_N_*` entries — never a fabricated one |
 *
 * ## `list_events` is read-only, and never guesses a date
 * With `date`, results are bounded to that one calendar day. Without it —
 * or with one that fails to parse — it falls back to the next handful of
 * upcoming events from now, exactly the same "widen rather than invent"
 * rule [com.softwaremine.dps.data.android.tool.AndroidWorkLogTool]'s own
 * `list_work_logs` already follows: a date DPS cannot confidently resolve is
 * never silently guessed at, only omitted from the filter. `date` itself is
 * never computed here — it arrives already resolved, deterministically, by
 * [com.softwaremine.dps.ai.memory.TemporalPhraseResolver] long before this
 * tool ever runs (Day 08-E); this class only ever reads it.
 *
 * ## Failure modes handled explicitly
 * The brief calls for graceful detection of three conditions, each of which
 * needs a different response from the assistant:
 *
 * | Condition | Result | Why distinct |
 * |---|---|---|
 * | No calendar provider | [ToolResult.Unsupported] | Nothing the user can do; do not offer to retry |
 * | Permission denied | [ToolResult.PermissionRequired] | Recoverable by asking — handled by the executor before dispatch |
 * | Invalid date | [ToolResult.Failure] | The model can correct itself and retry |
 * | No writable calendar | [ToolResult.Failure] | User action required, but not a permission problem |
 *
 * Collapsing these into one "couldn't create event" would leave the assistant
 * unable to say anything useful about any of them.
 *
 * ## End time
 * `DTEND` is mandatory for non-recurring events. When the caller omits it a
 * one-hour event is assumed, which is the conventional default and far more
 * useful than refusing — a model asked to "put lunch at 1pm in my calendar"
 * will routinely not supply an end.
 *
 * ## Dependencies
 * [CalendarWriter], [ToolArguments]. No direct Android imports.
 */
class AndroidCalendarTool(
    private val writer: CalendarWriter,
) : AndroidTool {

    override val id: ToolId = ToolId.CALENDAR

    override val operations: Set<String> = setOf(OP_CREATE_EVENT, OP_UPDATE_EVENT, OP_DELETE_EVENT, OP_LIST_EVENTS)

    override val requiredPermissions: Set<DpsPermission> = setOf(
        DpsPermission.READ_CALENDAR,   // required to find a writable calendar, and to list
        DpsPermission.WRITE_CALENDAR,  // required to insert/update/delete
    )

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE_EVENT -> createEvent(call)
        OP_UPDATE_EVENT -> updateEvent(call)
        OP_DELETE_EVENT -> deleteEvent(call)
        OP_LIST_EVENTS -> listEvents(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented yet.")
    }

    private fun createEvent(call: ToolCall): ToolResult {
        val zone = ToolArguments.zone(call)

        val title = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val start = when (val parsed = ToolArguments.time(call, ARG_START, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val explicitEnd = when (val parsed = ToolArguments.optionalTime(call, ARG_END, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }
        val end = explicitEnd ?: (start + DEFAULT_DURATION_MILLIS)

        when (val ordered = ToolArguments.requireOrdered(start, end)) {
            is ToolArguments.Parsed.Invalid -> return ordered.failure
            is ToolArguments.Parsed.Value -> Unit
        }

        // Calendar events may legitimately be created in the past — recording
        // a meeting that already happened is a normal thing to want — so no
        // future check here, unlike reminders.

        val target = when (val found = writer.findWritableCalendar()) {
            is CalendarWriter.CalendarTarget.Found -> found

            CalendarWriter.CalendarTarget.NoProvider -> return ToolResult.Unsupported(
                reason = "This device has no calendar app available.",
            )

            CalendarWriter.CalendarTarget.NoWritableCalendar -> return ToolResult.Failure(
                reason = "No calendar on this device accepts new events. " +
                    "Add an account in the Calendar app first.",
                retryable = false,
            )

            is CalendarWriter.CalendarTarget.Failed -> return ToolResult.Failure(
                reason = found.reason,
                retryable = true,
            )
        }

        val outcome = writer.insertEvent(
            calendarId = target.calendarId,
            title = title,
            description = call.argument(ARG_DESCRIPTION),
            location = call.argument(ARG_LOCATION),
            startMillis = start,
            endMillis = end,
            allDay = ToolArguments.boolean(call, ARG_ALL_DAY, default = false),
            timezone = zone.id,
        )

        return when (outcome) {
            is CalendarWriter.InsertOutcome.Created -> ToolResult.Success(
                summary = "Added \"$title\" to your calendar.",
                data = mapOf(
                    "event_id" to outcome.eventId.toString(),
                    "calendar" to target.displayName,
                    "start" to ToolArguments.describe(start, zone),
                    "end" to ToolArguments.describe(end, zone),
                ),
            )

            CalendarWriter.InsertOutcome.NoProvider -> ToolResult.Unsupported(
                reason = "This device has no calendar app available.",
            )

            is CalendarWriter.InsertOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )
        }
    }

    /**
     * Changes an existing event's title and/or time.
     *
     * Unlike [createEvent], nothing here is required except [ARG_ID] — a
     * reschedule ("us meeting ko 5 baje kar do") supplies only `start`, and
     * [CalendarWriter.updateEvent] leaves any field the caller omitted
     * untouched rather than requiring the whole event be restated.
     */
    private fun updateEvent(call: ToolCall): ToolResult {
        val id = call.argument(ARG_ID)?.trim()?.toLongOrNull()
            ?: return ToolResult.Failure(reason = "'id' must be a number.", retryable = false)

        val zone = ToolArguments.zone(call)
        val title = call.argument(ARG_TITLE)

        val start = when (val parsed = ToolArguments.optionalTime(call, ARG_START, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }
        val end = when (val parsed = ToolArguments.optionalTime(call, ARG_END, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        if (title == null && start == null && end == null) {
            return ToolResult.Failure(
                reason = "Nothing to change — say what should be different about the event.",
                retryable = false,
            )
        }

        // A reschedule that only names a new start ("us meeting ko 5 baje kar
        // do") must not leave DTEND at its old value — that would shrink or
        // invert the event. Preserve the original length when the caller
        // didn't also say how long it should now run.
        val resolvedEnd = if (start != null && end == null) {
            writer.readEvent(id)?.let { existing ->
                start + (existing.endMillis - existing.startMillis)
            }
        } else {
            end
        }

        return when (val outcome = writer.updateEvent(id, title, start, resolvedEnd)) {
            CalendarWriter.MutationOutcome.Updated -> ToolResult.Success(
                summary = "Updated the event.",
                data = mapOf("event_id" to id.toString()),
            )

            CalendarWriter.MutationOutcome.NotFound -> ToolResult.Failure(
                reason = "I couldn't find that event anymore — it may already have changed.",
                retryable = false,
            )

            CalendarWriter.MutationOutcome.NoProvider -> ToolResult.Unsupported(
                reason = "This device has no calendar app available.",
            )

            is CalendarWriter.MutationOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )

            CalendarWriter.MutationOutcome.Deleted -> ToolResult.Error(
                message = "Something went wrong on my side and I couldn't finish that.",
                cause = "updateEvent returned Deleted",
            )
        }
    }

    /**
     * Removes an event.
     *
     * ## No confirmation gate here
     * Deleting is irreversible from DPS's side, but asking "are you sure?"
     * before every destructive action belongs one layer up — in the
     * orchestration that decides whether a message already carries the user's
     * confirmation ("delete kar do" following "us meeting ko" is itself the
     * confirmation) or needs to ask first. A tool has no conversational
     * context to make that judgement with; forcing it here would mean asking
     * twice whenever the orchestrator already asked once.
     */
    private fun deleteEvent(call: ToolCall): ToolResult {
        val id = call.argument(ARG_ID)?.trim()?.toLongOrNull()
            ?: return ToolResult.Failure(reason = "'id' must be a number.", retryable = false)

        return when (val outcome = writer.deleteEvent(id)) {
            CalendarWriter.MutationOutcome.Deleted -> ToolResult.Success(
                summary = "Deleted the event.",
                data = mapOf("event_id" to id.toString()),
            )

            CalendarWriter.MutationOutcome.NotFound -> ToolResult.Failure(
                reason = "I couldn't find that event anymore — it may already be gone.",
                retryable = false,
            )

            CalendarWriter.MutationOutcome.NoProvider -> ToolResult.Unsupported(
                reason = "This device has no calendar app available.",
            )

            is CalendarWriter.MutationOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )

            CalendarWriter.MutationOutcome.Updated -> ToolResult.Error(
                message = "Something went wrong on my side and I couldn't finish that.",
                cause = "deleteEvent returned Updated",
            )
        }
    }

    /**
     * Lists events, optionally filtered to a single calendar day.
     *
     * With [ARG_DATE], bounded to `[startOfDay, startOfNextDay)`. Without
     * it — or with one that does not parse as an ISO date — bounded to
     * `[now, ∞)`, capped at [MAX_LISTED_EVENTS]: the next handful of
     * upcoming events is the useful default answer to "what are my
     * meetings", not an arbitrary or unbounded dump of the whole calendar.
     */
    private fun listEvents(call: ToolCall): ToolResult {
        val zone = ToolArguments.zone(call)
        val dateFilter = call.argument(ARG_DATE)?.let(::parseIsoDate)

        val fromMillis: Long
        val toMillis: Long?
        if (dateFilter != null) {
            fromMillis = dateFilter.atStartOfDay(zone).toInstant().toEpochMilli()
            toMillis = dateFilter.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            fromMillis = System.currentTimeMillis()
            toMillis = null
        }

        return when (val outcome = writer.findEvents(fromMillis, toMillis, MAX_LISTED_EVENTS)) {
            is CalendarWriter.QueryOutcome.Found -> {
                val events = outcome.events
                if (events.isEmpty()) {
                    ToolResult.Success(summary = "No events found.", data = mapOf("count" to "0"))
                } else {
                    ToolResult.Success(
                        summary = "You have ${events.size} event${if (events.size == 1) "" else "s"}.",
                        data = buildMap {
                            put("count", events.size.toString())
                            events.forEachIndexed { index, event ->
                                put("event_${index}_id", event.id.toString())
                                put("event_${index}_title", event.title)
                                put("event_${index}_start", ToolArguments.describe(event.startMillis, zone))
                                put("event_${index}_end", ToolArguments.describe(event.endMillis, zone))
                            }
                        },
                    )
                }
            }

            CalendarWriter.QueryOutcome.NoProvider -> ToolResult.Unsupported(
                reason = "This device has no calendar app available.",
            )

            is CalendarWriter.QueryOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )
        }
    }

    /** `null` on anything that isn't a parseable ISO date — never guessed, only omitted. See [listEvents]. */
    private fun parseIsoDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

    private companion object {
        const val OP_CREATE_EVENT = "create_event"
        const val OP_UPDATE_EVENT = "update_event"
        const val OP_DELETE_EVENT = "delete_event"
        const val OP_LIST_EVENTS = "list_events"

        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_START = "start"
        const val ARG_END = "end"
        const val ARG_DESCRIPTION = "description"
        const val ARG_LOCATION = "location"
        const val ARG_ALL_DAY = "all_day"
        const val ARG_DATE = "date"

        /** Mirrors AndroidWorkLogTool's MAX_UNFILTERED_RESULTS convention. */
        const val MAX_LISTED_EVENTS = 20

        /** Assumed event length when no end time is supplied. */
        const val DEFAULT_DURATION_MILLIS = 60L * 60L * 1000L

        /** Unused today; kept so the zone default is stated in one place. */
        val DEFAULT_ZONE: ZoneId = ZoneId.systemDefault()
    }
}
