package com.softwaremine.dps.data.android.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager

/**
 * Schedules and cancels reminder alarms.
 *
 * ## Purpose
 * The only class that talks to `AlarmManager`. Keeps alarm type selection,
 * PendingIntent flags and the exact-versus-inexact decision in one place.
 *
 * ## APIs used â€” verified against official documentation
 * | API | Notes |
 * |---|---|
 * | `setExactAndAllowWhileIdle(int, long, PendingIntent)` | exact, fires in Doze |
 * | `setAndAllowWhileIdle(int, long, PendingIntent)` | inexact fallback, fires in Doze |
 * | `canScheduleExactAlarms()` | API 31+; whether exact is permitted |
 * | `AlarmManager.RTC_WAKEUP` | wall-clock time, wakes the device |
 * | `PendingIntent.getBroadcast(...)` | delivery target |
 * | `PendingIntent.FLAG_IMMUTABLE` | **mandatory from API 31** |
 *
 * ## Why the fallback is mandatory, not optional
 * `SCHEDULE_EXACT_ALARM` is documented as **not auto-granted**. With
 * `targetSdk = 35`, DPS will frequently run without it â€” so a design that only
 * schedules exact alarms would simply fail to set reminders for a large share
 * of users, which is a total failure of the product's core promise.
 *
 * The fallback uses `setAndAllowWhileIdle` rather than plain `set` because Doze
 * would otherwise defer the alarm indefinitely on an idle phone, which is
 * exactly when an overnight reminder needs to fire.
 *
 * The distinction is surfaced to the caller rather than hidden: an inexact
 * alarm may drift by minutes, and telling the user "around 4pm" is honest where
 * implying "at 4pm" is not.
 *
 * ## Why RTC_WAKEUP
 * Reminders are wall-clock events â€” "4pm" means 4pm, not "in six hours".
 * `ELAPSED_REALTIME_WAKEUP` measures uptime and would drift against the clock.
 * `_WAKEUP` because a reminder that waits for the user to next unlock their
 * phone is not a reminder.
 *
 * ## Dependencies
 * `android.app.AlarmManager`, `android.app.PendingIntent`. Android layer only.
 */
class ReminderScheduler(
    private val context: Context,
    private val logger: DpsLogger,
    /**
     * The single authority on permission state.
     *
     * Injected rather than calling `canScheduleExactAlarms()` here. An earlier
     * revision did call it directly, which meant the exact-alarm rule existed
     * in two places — this class and [PermissionManager] — free to disagree
     * about API-level gating. Development Rule 5 exists for exactly that.
     */
    private val permissionManager: PermissionManager,
) {

    /** How an alarm was actually scheduled. */
    sealed interface ScheduleOutcome {
        /** Scheduled. [exact] is false when the inexact fallback was used. */
        data class Scheduled(val exact: Boolean) : ScheduleOutcome

        /** `AlarmManager` unavailable â€” should not occur on a real device. */
        data object Unavailable : ScheduleOutcome

        data class Failed(val reason: String) : ScheduleOutcome
    }

    /**
     * Whether exact alarms may currently be scheduled.
     *
     * `canScheduleExactAlarms()` exists from API 31. Below that, exact alarms
     * need no special grant, so the answer is unconditionally yes.
     */
    fun canScheduleExact(): Boolean =
        permissionManager.state(DpsPermission.SCHEDULE_EXACT_ALARM).isUsable

    /**
     * Schedules a reminder, falling back to inexact when exact is unavailable.
     *
     * @param id becomes the PendingIntent request code, so re-scheduling the
     *   same id replaces the existing alarm â€” which is what makes update work.
     */
    fun schedule(
        id: Int,
        title: String,
        body: String,
        triggerAtMillis: Long,
    ): ScheduleOutcome {
        val alarmManager = context.getSystemService<AlarmManager>()
            ?: return ScheduleOutcome.Unavailable.also {
                logger.e(TAG, "AlarmManager unavailable")
            }

        val pendingIntent = broadcastIntent(id, title, body, PendingIntent.FLAG_UPDATE_CURRENT)

        return try {
            val exact = canScheduleExact()
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                // Permission absent. Still schedule â€” an approximate reminder
                // is enormously better than none â€” and report the difference.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
            logger.i(TAG, "Scheduled reminder id=$id exact=$exact at=$triggerAtMillis")
            ScheduleOutcome.Scheduled(exact = exact)
        } catch (security: SecurityException) {
            // Defensive: the permission can be revoked between the check above
            // and the call. Retry inexactly rather than losing the reminder.
            logger.w(TAG, "Exact alarm denied; retrying inexact", security)
            retryInexact(alarmManager, pendingIntent, triggerAtMillis, id)
        } catch (throwable: Throwable) {
            logger.e(TAG, "Failed to schedule reminder id=$id", throwable)
            ScheduleOutcome.Failed(throwable.message ?: "Could not schedule the reminder.")
        }
    }

    private fun retryInexact(
        alarmManager: AlarmManager,
        pendingIntent: PendingIntent,
        triggerAtMillis: Long,
        id: Int,
    ): ScheduleOutcome = try {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        logger.i(TAG, "Scheduled reminder id=$id inexact after denial")
        ScheduleOutcome.Scheduled(exact = false)
    } catch (throwable: Throwable) {
        logger.e(TAG, "Inexact fallback also failed for id=$id", throwable)
        ScheduleOutcome.Failed("Could not schedule the reminder.")
    }

    /**
     * Cancels a scheduled reminder.
     *
     * `FLAG_NO_CREATE` returns `null` when no matching PendingIntent exists,
     * which is how an already-fired or never-scheduled alarm is detected
     * without creating one purely in order to cancel it.
     */
    fun cancel(id: Int): Boolean {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return false

        val existing = PendingIntent.getBroadcast(
            context,
            id,
            ReminderReceiver.intent(context, id, title = "", body = ""),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: run {
            logger.d(TAG, "No pending alarm for id=$id")
            return false
        }

        alarmManager.cancel(existing)
        existing.cancel()
        logger.i(TAG, "Cancelled reminder id=$id")
        return true
    }

    /**
     * Builds the PendingIntent delivering this reminder.
     *
     * `FLAG_IMMUTABLE` is mandatory from API 31 and correct everywhere â€” no
     * other app has any business rewriting a reminder's contents.
     *
     * `FLAG_UPDATE_CURRENT` means re-scheduling an existing id replaces its
     * extras rather than creating a duplicate, which is precisely the semantics
     * an update needs.
     */
    private fun broadcastIntent(
        id: Int,
        title: String,
        body: String,
        mutabilityFlags: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        ReminderReceiver.intent(context, id, title, body),
        mutabilityFlags or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "ReminderScheduler"
    }
}
