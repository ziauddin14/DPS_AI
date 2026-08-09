package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.data.android.reminder.ReminderScheduler
import com.softwaremine.dps.data.android.reminder.ReminderStore
import com.softwaremine.dps.data.android.reminder.StoredReminder
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Schedules, updates, lists and cancels reminders.
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `create_reminder` | `title` (required), `time` (required), `body` | `reminder_id`, `exact` |
 * | `update_reminder` | `id` (required), `time`, `title`, `body` | updated fields |
 * | `cancel_reminder` | `id` (required) | confirmation |
 * | `list_reminders` | – | pending reminders |
 *
 * ## Permissions
 * Declares [DpsPermission.POST_NOTIFICATIONS] only.
 *
 * [DpsPermission.SCHEDULE_EXACT_ALARM] is deliberately **not** declared as
 * required, and that is the single most consequential decision in this class.
 * It is special access that is *not auto-granted*, so declaring it required
 * would make the executor return `PermissionRequired` for every reminder on
 * every device where the user has not visited a settings screen — which is most
 * of them. DPS would be unable to set any reminder at all.
 *
 * Instead the reminder is always scheduled, exactly where permitted and
 * approximately otherwise, and the result says which. A reminder accurate to a
 * few minutes is vastly better than no reminder; refusing outright would fail
 * the product's core promise in order to be pedantic about precision.
 *
 * ## Why exactness is reported rather than hidden
 * "At 4pm" and "around 4pm" are different promises. An assistant that says the
 * former while scheduling the latter is lying to the user, in a way they only
 * discover when it matters.
 *
 * ## Dependencies
 * [ReminderScheduler], [ReminderStore], [ToolArguments]. No direct Android
 * imports — platform work lives in the scheduler.
 */
class AndroidReminderTool(
    private val scheduler: ReminderScheduler,
    private val store: ReminderStore,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.REMINDER

    override val operations: Set<String> = setOf(
        OP_CREATE, OP_UPDATE, OP_CANCEL, OP_LIST,
    )

    // See the class documentation: SCHEDULE_EXACT_ALARM is intentionally absent.
    override val requiredPermissions: Set<DpsPermission> =
        setOf(DpsPermission.POST_NOTIFICATIONS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_CREATE -> create(call)
        OP_UPDATE -> update(call)
        OP_CANCEL -> cancel(call)
        OP_LIST -> list()
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun create(call: ToolCall): ToolResult {
        val zone = ToolArguments.zone(call)

        val title = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val triggerAt = when (val parsed = ToolArguments.time(call, ARG_TIME, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        // Unlike a calendar event, a reminder in the past is meaningless — it
        // would either fire immediately or never.
        when (val future = ToolArguments.requireFuture(triggerAt, now(), ARG_TIME)) {
            is ToolArguments.Parsed.Invalid -> return future.failure
            is ToolArguments.Parsed.Value -> Unit
        }

        val body = call.argumentOr(ARG_BODY, title)
        val reminderId = store.nextId()

        return when (val outcome = scheduler.schedule(reminderId, title, body, triggerAt)) {
            is ReminderScheduler.ScheduleOutcome.Scheduled -> {
                store.put(
                    StoredReminder(
                        id = reminderId,
                        title = title,
                        body = body,
                        triggerAtMillis = triggerAt,
                        createdAtMillis = now(),
                        wasExact = outcome.exact,
                    ),
                )
                store.pruneExpired(now())

                val when_ = ToolArguments.describe(triggerAt, zone)
                ToolResult.Success(
                    // Wording follows the accuracy actually achieved.
                    summary = if (outcome.exact) {
                        "Reminder set for $when_."
                    } else {
                        "Reminder set for around $when_. Exact timing needs " +
                            "permission in system settings."
                    },
                    data = mapOf(
                        "reminder_id" to reminderId.toString(),
                        "trigger_at" to triggerAt.toString(),
                        "exact" to outcome.exact.toString(),
                    ),
                )
            }

            ReminderScheduler.ScheduleOutcome.Unavailable -> ToolResult.Unsupported(
                reason = "This device cannot schedule alarms.",
            )

            is ReminderScheduler.ScheduleOutcome.Failed -> ToolResult.Failure(
                reason = outcome.reason,
                retryable = true,
            )
        }
    }

    private fun update(call: ToolCall): ToolResult {
        val zone = ToolArguments.zone(call)

        val reminderId = call.argument(ARG_ID)?.trim()?.toIntOrNull()
            ?: return ToolResult.Failure("'id' must be a number.", retryable = false)

        val existing = store.find(reminderId)
            ?: return ToolResult.Failure(
                reason = "No pending reminder with that id.",
                retryable = false,
            )

        val newTime = when (val parsed = ToolArguments.optionalTime(call, ARG_TIME, zone)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        } ?: existing.triggerAtMillis

        when (val future = ToolArguments.requireFuture(newTime, now(), ARG_TIME)) {
            is ToolArguments.Parsed.Invalid -> return future.failure
            is ToolArguments.Parsed.Value -> Unit
        }

        val newTitle = call.argumentOr(ARG_TITLE, existing.title)
        val newBody = call.argumentOr(ARG_BODY, existing.body)

        // Re-scheduling the same id replaces the alarm: the PendingIntent
        // request code is the id, and FLAG_UPDATE_CURRENT updates in place
        // rather than creating a duplicate. Cancelling first is unnecessary and
        // would leave a window in which the reminder does not exist.
        return when (val outcome = scheduler.schedule(reminderId, newTitle, newBody, newTime)) {
            is ReminderScheduler.ScheduleOutcome.Scheduled -> {
                store.put(
                    existing.copy(
                        title = newTitle,
                        body = newBody,
                        triggerAtMillis = newTime,
                        wasExact = outcome.exact,
                    ),
                )
                ToolResult.Success(
                    summary = "Reminder updated to ${ToolArguments.describe(newTime, zone)}.",
                    data = mapOf(
                        "reminder_id" to reminderId.toString(),
                        "trigger_at" to newTime.toString(),
                        "exact" to outcome.exact.toString(),
                    ),
                )
            }

            ReminderScheduler.ScheduleOutcome.Unavailable ->
                ToolResult.Unsupported("This device cannot schedule alarms.")

            is ReminderScheduler.ScheduleOutcome.Failed ->
                ToolResult.Failure(outcome.reason, retryable = true)
        }
    }

    private fun cancel(call: ToolCall): ToolResult {
        val reminderId = call.argument(ARG_ID)?.trim()?.toIntOrNull()
            ?: return ToolResult.Failure("'id' must be a number.", retryable = false)

        val hadRecord = store.remove(reminderId)
        val hadAlarm = scheduler.cancel(reminderId)

        return if (hadRecord || hadAlarm) {
            ToolResult.Success(
                summary = "Reminder cancelled.",
                data = mapOf("reminder_id" to reminderId.toString()),
            )
        } else {
            // Nothing to cancel is a legitimate outcome, not a defect — the
            // reminder may simply have already fired.
            ToolResult.Failure(
                reason = "No pending reminder with that id.",
                retryable = false,
            )
        }
    }

    private fun list(): ToolResult {
        store.pruneExpired(now())
        val pending = store.all()

        if (pending.isEmpty()) {
            return ToolResult.Success(summary = "No reminders are set.")
        }

        // Flattened to string keys because ToolResult payloads are
        // Map<String, String> — they must survive being rendered into a prompt.
        val data = buildMap {
            put("count", pending.size.toString())
            pending.forEachIndexed { index, reminder ->
                put("reminder_${index}_id", reminder.id.toString())
                put("reminder_${index}_title", reminder.title)
                put("reminder_${index}_trigger_at", reminder.triggerAtMillis.toString())
                put("reminder_${index}_exact", reminder.wasExact.toString())
            }
        }

        return ToolResult.Success(
            summary = "You have ${pending.size} reminder${if (pending.size == 1) "" else "s"} set.",
            data = data,
        )
    }

    private companion object {
        const val OP_CREATE = "create_reminder"
        const val OP_UPDATE = "update_reminder"
        const val OP_CANCEL = "cancel_reminder"
        const val OP_LIST = "list_reminders"

        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_BODY = "body"
        const val ARG_TIME = "time"
    }
}
