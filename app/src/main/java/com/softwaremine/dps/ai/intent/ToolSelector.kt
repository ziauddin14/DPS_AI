package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Turns an intent into the tool call that carries it out.
 *
 * ## Purpose
 * The mapping layer. Intents are what the *user* wants; tool calls are what the
 * *tool layer* accepts. Keeping them apart means tool argument names can change
 * without touching the prompt, and the model never has to know that a reminder
 * is `reminder.create_reminder` with a `time` argument in epoch milliseconds.
 *
 * ## Date and time handling
 * The model emits `date` and `time` as separate human-shaped strings; every
 * tool wants a single instant. Combining them is this class's main real work,
 * and the rules are deliberate:
 *
 * - **Both present** → combined directly.
 * - **Time only** → the next occurrence of that time. "Remind me at 4" said at
 *   2pm means today; said at 6pm it means tomorrow. This is a convention every
 *   user shares, not a guess about ambiguous input.
 * - **Date only** → 09:00 on that date, a conventional default for an all-day
 *   commitment.
 * - **Neither** → not this class's problem. The clarification engine catches it
 *   first, because a reminder with no time cannot exist.
 *
 * ## Why this is pure Kotlin
 * Every rule above is a decision worth testing exactly, and none of it needs
 * Android. `java.time` is available from API 26, our minSdk.
 *
 * ## Dependencies
 * Domain intent and tool types, `java.time`. No Android.
 */
class ToolSelector(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> LocalDateTime = { LocalDateTime.now(zone) },
) {

    /**
     * Builds the call for [intent], or `null` when it does not map to a tool.
     *
     * `null` is returned for [IntentType.CONVERSATION] only. Everything else is
     * routable — completeness has already been established by the clarification
     * engine before this is reached.
     */
    fun select(intent: DpsIntent): ToolCall? {
        val p = intent.parameters

        return when (intent.type) {
            IntentType.REMINDER -> reminderCall(intent.action, p)

            IntentType.CALENDAR_EVENT -> calendarCall(intent.action, p)

            IntentType.NOTIFICATION -> notificationCall(intent.action, p)

            IntentType.CONTACT_LOOKUP -> ToolCall(
                toolId = ToolId.CONTACTS,
                operation = "find_contact",
                arguments = buildMap {
                    p.value(IntentField.PERSON)?.let { put("name", it) }
                    p.value(IntentField.PHONE)?.let { put("phone", it) }
                },
            )

            IntentType.WHATSAPP_MESSAGE -> ToolCall(
                toolId = ToolId.WHATSAPP,
                // The preparing name, not the alias. Nothing is sent.
                operation = "prepare_message",
                arguments = buildMap {
                    p.value(IntentField.PERSON)?.let { put("contact", it) }
                    p.value(IntentField.PHONE)?.let { put("phone", it) }
                    put("message", p.value(IntentField.MESSAGE).orEmpty())
                },
            )

            IntentType.EMAIL_MESSAGE -> ToolCall(
                toolId = ToolId.GMAIL,
                operation = "compose_email",
                arguments = buildMap {
                    p.value(IntentField.EMAIL)?.let { put("to", it) }
                    p.value(IntentField.PERSON)?.let { put("contact", it) }
                    p.value(IntentField.TITLE)?.let { put("subject", it) }
                    put("body", p.value(IntentField.MESSAGE).orEmpty())
                },
            )

            // The preparing operation, never a dialing one — see
            // AndroidCallTool's doc for why ACTION_DIAL is the only
            // capability this codebase has. No "message" argument: a call
            // carries no body.
            IntentType.CALL_CONTACT -> ToolCall(
                toolId = ToolId.PHONE,
                operation = "place_call",
                arguments = buildMap {
                    p.value(IntentField.PERSON)?.let { put("contact", it) }
                    p.value(IntentField.PHONE)?.let { put("phone", it) }
                },
            )

            IntentType.TASK -> taskCall(intent.action, p)
            IntentType.WORK_LOG -> workLogCall(intent.action, p)
            IntentType.MEETING_NOTE -> meetingNoteCall(intent.action, p)
            IntentType.ACTION_ITEM -> actionItemCall(intent.action, p)
            IntentType.REPORT -> reportCall(p)

            IntentType.CONVERSATION -> null
        }
    }

    /**
     * Builds the task call for [action] (Day 06).
     *
     * ## Why `title` means different things by action
     * For [IntentAction.COMPLETE]/[IntentAction.CANCEL] it is an **address** —
     * "DBPMS wala task complete kar do" names the task by its content rather
     * than a remembered id, so [com.softwaremine.dps.data.android.tool.AndroidTaskTool]
     * falls back to a title match when `id` is absent. For
     * [IntentAction.UPDATE] it is the **new** title (a rename); addressing an
     * update always goes through `id`, exactly like [reminderCall].
     */
    private fun taskCall(action: IntentAction, p: IntentParameters): ToolCall = when (action) {
        IntentAction.LIST -> ToolCall(toolId = ToolId.TASK, operation = "list_tasks")

        IntentAction.COMPLETE -> ToolCall(
            toolId = ToolId.TASK,
            operation = "complete_task",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
            },
        )

        IntentAction.CANCEL -> ToolCall(
            toolId = ToolId.TASK,
            operation = "cancel_task",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
            },
        )

        IntentAction.UPDATE -> ToolCall(
            toolId = ToolId.TASK,
            operation = "update_task",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
                p.value(IntentField.DESCRIPTION)?.let { put("notes", it) }
                p.value(IntentField.PRIORITY)?.let { put("priority", it) }
                resolveInstant(p)?.let { put("due", it.toString()) }
            },
        )

        IntentAction.CREATE -> ToolCall(
            toolId = ToolId.TASK,
            operation = "create_task",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE).orEmpty())
                p.value(IntentField.DESCRIPTION)?.let { put("notes", it) }
                p.value(IntentField.PRIORITY)?.let { put("priority", it) }
                resolveInstant(p)?.let { put("due", it.toString()) }
            },
        )
    }

    /**
     * Builds the work log call (Day 06).
     *
     * ## Why `duration` is passed through untouched
     * [IntentParameters.duration] is a raw phrase ("3 ghante"). Parsing it
     * into minutes is [com.softwaremine.dps.data.android.tool.AndroidWorkLogTool]'s
     * job, mirroring how every other numeric argument in this codebase is
     * validated at the tool boundary rather than here. This class only
     * decides *which* tool call to build, never converts free-form units.
     *
     * ## Why an unstated date defaults to today
     * "Aaj ka work log add karo" and "10 se 12 baje tak DBPMS par kaam kiya"
     * both describe today's work without necessarily saying the word "today".
     * A work log needs some date to file under; defaulting to today is a
     * conventional default, not an invented fact — mirroring [DEFAULT_TIME_OF_DAY]'s
     * own reasoning for calendar events.
     */
    private fun workLogCall(action: IntentAction, p: IntentParameters): ToolCall {
        if (action == IntentAction.LIST) {
            return ToolCall(
                toolId = ToolId.WORK_LOG,
                operation = "list_work_logs",
                arguments = buildMap { p.value(IntentField.DATE)?.let { put("date", it) } },
            )
        }

        return ToolCall(
            toolId = ToolId.WORK_LOG,
            operation = "create_work_log",
            arguments = buildMap {
                put("activity", p.value(IntentField.TITLE) ?: p.value(IntentField.MESSAGE).orEmpty())
                p.value(IntentField.DESCRIPTION)?.let { put("notes", it) }
                put("date", p.value(IntentField.DATE) ?: todayIso())
                p.value(IntentField.TIME)?.let { put("start_time", it) }
                p.value(IntentField.DURATION)?.let { put("duration", it) }
            },
        )
    }

    /** Builds the meeting note call (Day 06). See [workLogCall] for the today-default reasoning. */
    private fun meetingNoteCall(action: IntentAction, p: IntentParameters): ToolCall {
        if (action == IntentAction.LIST) {
            return ToolCall(
                toolId = ToolId.MEETING,
                operation = "list_meetings",
                arguments = buildMap {
                    p.value(IntentField.DATE)?.let { put("date", it) }
                    p.value(IntentField.PERSON)?.let { put("person", it) }
                },
            )
        }

        return ToolCall(
            toolId = ToolId.MEETING,
            operation = "create_meeting",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE) ?: p.value(IntentField.DESCRIPTION).orEmpty())
                p.value(IntentField.DESCRIPTION)?.let { put("notes", it) }
                p.value(IntentField.PERSON)?.let { put("participants", it) }
                put("date", p.value(IntentField.DATE) ?: todayIso())
            },
        )
    }

    /** Builds the action item call (Day 06). See [taskCall] for why `title` addresses a COMPLETE. */
    private fun actionItemCall(action: IntentAction, p: IntentParameters): ToolCall = when (action) {
        IntentAction.LIST -> ToolCall(toolId = ToolId.ACTION_ITEM, operation = "list_action_items")

        IntentAction.COMPLETE -> ToolCall(
            toolId = ToolId.ACTION_ITEM,
            operation = "complete_action_item",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
            },
        )

        else -> ToolCall(
            toolId = ToolId.ACTION_ITEM,
            operation = "create_action_item",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE).orEmpty())
                resolveInstant(p)?.let { put("due", it.toString()) }
            },
        )
    }

    /**
     * Builds the report call (Day 06).
     *
     * Unstated [IntentParameters.period] defaults to a daily report — showing
     * more real, stored data than the user strictly asked for is harmless,
     * unlike guessing a contact or a time would be.
     */
    private fun reportCall(p: IntentParameters): ToolCall {
        val period = p.value(IntentField.PERIOD)?.lowercase()
        val operation = if (period != null && period.startsWith("week")) "weekly_report" else "daily_report"
        return ToolCall(toolId = ToolId.REPORT, operation = operation)
    }

    /** Today's date, ISO-formatted, in [zone]. See [workLogCall]'s doc for why this default is safe. */
    private fun todayIso(): String = now().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * Builds the reminder call for [action].
     *
     * ## Why CANCEL and UPDATE only ever set the fields they were told about
     * [CREATE][IntentAction.CREATE] synthesizes a title from `message` when
     * none was given, because a brand-new reminder needs one from somewhere.
     * [UPDATE][IntentAction.UPDATE] must not do that: the user asking to
     * reschedule a reminder said nothing about its title, and
     * [com.softwaremine.dps.data.android.tool.AndroidReminderTool]'s
     * `update_reminder` already keeps the existing title when the argument is
     * absent — inventing one here would silently overwrite it with `""`.
     *
     * ## Where the id comes from
     * Never from the model — [IntentParameters.targetId] is filled by
     * [com.softwaremine.dps.ai.memory.ReferenceResolver] from conversation
     * memory before this runs. If it is still `null` here (nothing to
     * remember, or the reference did not match), the built call omits `id`
     * entirely; the tool then fails honestly with "No pending reminder with
     * that id" rather than guessing one.
     */
    private fun reminderCall(action: IntentAction, p: IntentParameters): ToolCall = when (action) {
        // AndroidReminderTool already implements list_reminders (Phase B) —
        // a query intent classified against REMINDER reuses it directly.
        IntentAction.LIST -> ToolCall(toolId = ToolId.REMINDER, operation = "list_reminders")

        // Reminders have no "complete" concept in this tool — the operation
        // name is deliberately one AndroidReminderTool does not implement, so
        // the executor's existing Unsupported fallback reports that honestly
        // rather than this class guessing a CANCEL or UPDATE was meant.
        IntentAction.COMPLETE -> ToolCall(toolId = ToolId.REMINDER, operation = "complete_reminder")

        IntentAction.CANCEL -> ToolCall(
            toolId = ToolId.REMINDER,
            operation = "cancel_reminder",
            arguments = buildMap { p.targetId?.let { put("id", it) } },
        )

        IntentAction.UPDATE -> ToolCall(
            toolId = ToolId.REMINDER,
            operation = "update_reminder",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
                p.value(IntentField.MESSAGE)?.let { put("body", it) }
                resolveInstant(p)?.let { put("time", it.toString()) }
            },
        )

        IntentAction.CREATE -> ToolCall(
            toolId = ToolId.REMINDER,
            operation = "create_reminder",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE) ?: p.value(IntentField.MESSAGE).orEmpty())
                p.value(IntentField.MESSAGE)?.let { put("body", it) }
                resolveInstant(p)?.let { put("time", it.toString()) }
            },
        )
    }

    /**
     * Builds the notification call for [action].
     *
     * ## Why CANCEL is the only other branch, not a mirror of [reminderCall]
     * [IntentType.NOTIFICATION] is not in [com.softwaremine.dps.ai.intent.ClarificationEngine]'s
     * `TARGETABLE_TYPES` and has no slot in [com.softwaremine.dps.domain.memory.ConversationMemory]
     * — nothing anywhere grounds a notification id the way
     * [com.softwaremine.dps.ai.memory.ReferenceResolver] grounds a reminder or
     * calendar event's. So `p.targetId` is always absent here today, and
     * `cancel` always fails honestly with "Missing required argument 'id'"
     * from [com.softwaremine.dps.data.android.tool.AndroidNotificationTool]
     * itself — exactly the same "omit and let the tool say so" shape
     * [reminderCall]'s own CANCEL branch already uses, not a new failure mode.
     * That is still strictly better than before: a wrong or unactionable
     * result, never a fabricated one.
     *
     * UPDATE, COMPLETE and LIST have no concept in
     * [com.softwaremine.dps.data.android.tool.AndroidNotificationTool] at all
     * — deliberately routed to an operation name it does not implement, so
     * the executor's existing Unsupported fallback reports that honestly
     * rather than this class guessing CREATE was meant and posting something
     * the user never asked for (the same [reminderCall] `COMPLETE` shape).
     */
    private fun notificationCall(action: IntentAction, p: IntentParameters): ToolCall = when (action) {
        IntentAction.CANCEL -> ToolCall(
            toolId = ToolId.NOTIFICATION,
            operation = "cancel",
            arguments = buildMap { p.targetId?.let { put("id", it) } },
        )

        IntentAction.UPDATE, IntentAction.COMPLETE, IntentAction.LIST -> ToolCall(
            toolId = ToolId.NOTIFICATION,
            operation = "update",
        )

        IntentAction.CREATE -> ToolCall(
            toolId = ToolId.NOTIFICATION,
            operation = "notify",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE) ?: p.value(IntentField.MESSAGE).orEmpty())
                p.value(IntentField.MESSAGE)?.let { put("body", it) }
                p.value(IntentField.PRIORITY)?.let { put("channel", channelFor(it)) }
            },
        )
    }

    /**
     * Builds the calendar call for [action], mirroring [reminderCall].
     *
     * CANCEL needs no confirmation logic here — see
     * [com.softwaremine.dps.data.android.tool.AndroidCalendarTool]'s own
     * `deleteEvent` doc: that judgement belongs to the orchestration deciding
     * whether the message already carries consent, not to this pure mapping.
     */
    private fun calendarCall(action: IntentAction, p: IntentParameters): ToolCall = when (action) {
        // The only argument list_events reads — already resolved
        // deterministically by TemporalPhraseResolver before this runs, so
        // this never computes or guesses a date itself (Phase 5).
        IntentAction.LIST -> ToolCall(
            toolId = ToolId.CALENDAR,
            operation = "list_events",
            arguments = buildMap { p.value(IntentField.DATE)?.let { put("date", it) } },
        )

        // No "complete" concept for a calendar event; see reminderCall's doc.
        IntentAction.COMPLETE -> ToolCall(toolId = ToolId.CALENDAR, operation = "complete_event")

        IntentAction.CANCEL -> ToolCall(
            toolId = ToolId.CALENDAR,
            operation = "delete_event",
            arguments = buildMap { p.targetId?.let { put("id", it) } },
        )

        IntentAction.UPDATE -> ToolCall(
            toolId = ToolId.CALENDAR,
            operation = "update_event",
            arguments = buildMap {
                p.targetId?.let { put("id", it) }
                p.value(IntentField.TITLE)?.let { put("title", it) }
                resolveInstant(p)?.let { put("start", it.toString()) }
            },
        )

        IntentAction.CREATE -> ToolCall(
            toolId = ToolId.CALENDAR,
            operation = "create_event",
            arguments = buildMap {
                put("title", p.value(IntentField.TITLE) ?: p.value(IntentField.MESSAGE).orEmpty())
                p.value(IntentField.DESCRIPTION)?.let { put("description", it) }
                resolveInstant(p)?.let { put("start", it.toString()) }
                // End is left unset: AndroidCalendarTool defaults to one hour,
                // which is more useful than refusing an event because the
                // user did not say when it finishes.
            },
        )
    }

    /**
     * Combines `date` and `time` into epoch milliseconds.
     *
     * Returns `null` when neither is usable, which the clarification engine has
     * already ruled out for intents that need one.
     */
    internal fun resolveInstant(parameters: IntentParameters): Long? {
        val date = parameters.value(IntentField.DATE)?.let(::parseDate)
        val time = parameters.value(IntentField.TIME)?.let(::parseTime)
        val reference = now()

        val target = when {
            date != null && time != null -> LocalDateTime.of(date, time)

            // Next occurrence. Said at 2pm, "at 4" means today; said at 6pm it
            // means tomorrow — the reading every user shares.
            time != null -> {
                val todayAt = LocalDateTime.of(reference.toLocalDate(), time)
                if (todayAt.isAfter(reference)) todayAt else todayAt.plusDays(1)
            }

            date != null -> LocalDateTime.of(date, DEFAULT_TIME_OF_DAY)

            else -> return null
        }

        return target.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Parses a date from the shapes this pipeline actually produces.
     *
     * ISO first, then a small set of common alternatives. Relative expressions —
     * "tomorrow", "kal shaam 7 baje" — are deliberately **not** handled here:
     * by the time a [DpsIntent] reaches this class, `date`/`time` are already
     * absolute, resolved deterministically by
     * [com.softwaremine.dps.ai.memory.TemporalPhraseResolver] from the model's
     * verbatim `raw_when` quote (Day 08-E) — this class never sees, and does
     * not need to parse, a relative phrase at all.
     */
    internal fun parseDate(raw: String): LocalDate? {
        val trimmed = raw.trim()
        DATE_FORMATS.forEach { formatter ->
            runCatching { return LocalDate.parse(trimmed, formatter) }
        }
        return null
    }

    /** Parses a time, accepting 24-hour, seconds, and bare-hour forms. */
    internal fun parseTime(raw: String): LocalTime? {
        val trimmed = raw.trim()

        TIME_FORMATS.forEach { formatter ->
            runCatching { return LocalTime.parse(trimmed, formatter) }
        }

        // A bare hour: models emit "16" for four o'clock often enough to matter.
        trimmed.toIntOrNull()?.let { hour ->
            if (hour in 0..23) return LocalTime.of(hour, 0)
        }

        // A combined date-time in the time field, which also happens.
        runCatching { return LocalDateTime.parse(trimmed).toLocalTime() }
            .onFailure { if (it !is DateTimeParseException) throw it }

        return null
    }

    /** Maps an advisory priority onto a notification channel. */
    private fun channelFor(priority: String): String =
        if (priority.trim().lowercase() in HIGH_PRIORITY_WORDS) "reminders" else "assistant"

    private companion object {
        /** 09:00 — the conventional start of a working commitment. */
        val DEFAULT_TIME_OF_DAY: LocalTime = LocalTime.of(9, 0)

        val DATE_FORMATS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        )

        val TIME_FORMATS = listOf(
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm"),
        )

        val HIGH_PRIORITY_WORDS = setOf("high", "urgent", "important")
    }
}
