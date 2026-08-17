package com.softwaremine.dps.data.android.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager

/**
 * Reinstates reminders after a device restart.
 *
 * ## Purpose
 * `AlarmManager` alarms do not survive a reboot — Android clears them
 * unconditionally, confirmed empirically during the Reminder Reboot Survival
 * investigation (`dumpsys alarm` showed the pre-reboot alarm gone immediately
 * after boot). This is the other end of that gap: on `BOOT_COMPLETED`, every
 * reminder [ReminderStore] still has on record is re-armed through the exact
 * same [ReminderScheduler] path a fresh `create_reminder` call already uses.
 *
 * ## Why BOOT_COMPLETED, not LOCKED_BOOT_COMPLETED
 * `LOCKED_BOOT_COMPLETED` fires before the user unlocks and only reaches
 * direct-boot-aware components with access to device-protected storage.
 * [ReminderStore] lives in ordinary credential-encrypted storage, so reading
 * it that early isn't possible without a much larger persistence change.
 * `BOOT_COMPLETED` fires once that storage is available — later, but without
 * having to move anything to get there.
 *
 * ## Why running this twice for one reminder is safe
 * [ReminderScheduler.schedule] keys its `PendingIntent` by the reminder's id
 * with `FLAG_UPDATE_CURRENT`; `AlarmManager` replaces an existing alarm
 * registered against a matching `PendingIntent` rather than adding a second
 * one. No extra "already ran this boot" bookkeeping is needed here — the
 * idempotency already exists one layer down, in the scheduler this reuses.
 *
 * ## Why a receiver rather than a Service
 * Same reasoning as [ReminderReceiver]: `onReceive` has roughly ten seconds,
 * ample for looping over what is, by design, a handful of reminders and
 * making one synchronous `AlarmManager` call each.
 *
 * ## What this deliberately does not do
 * Reminders whose time has already passed are pruned, not fired late — the
 * same policy [ReminderStore.pruneExpired] already enforces everywhere else.
 * No catch-up notification, no recurrence: neither exists in the reminder
 * model this store persists.
 *
 * ## Dependencies
 * `android.content.BroadcastReceiver`, [ReminderScheduler], [ReminderStore].
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val logger = AndroidDpsLogger()
        val scheduler = ReminderScheduler(appContext, logger, AndroidPermissionManager(appContext, logger))
        val store = ReminderStore(appContext, logger)

        val now = System.currentTimeMillis()
        val prunedCount = store.pruneExpired(now)
        if (prunedCount > 0) {
            logger.i(TAG, "Pruned $prunedCount expired reminder(s) on boot")
        }

        val pending = store.all()
        logger.i(TAG, "Rescheduling ${pending.size} reminder(s) after boot")

        pending.forEach { reminder ->
            when (
                val outcome = scheduler.schedule(
                    reminder.id,
                    reminder.title,
                    reminder.body,
                    reminder.triggerAtMillis,
                )
            ) {
                is ReminderScheduler.ScheduleOutcome.Scheduled ->
                    store.put(reminder.copy(wasExact = outcome.exact))

                ReminderScheduler.ScheduleOutcome.Unavailable ->
                    logger.e(TAG, "AlarmManager unavailable; could not reschedule id=${reminder.id} after boot")

                is ReminderScheduler.ScheduleOutcome.Failed ->
                    logger.e(TAG, "Failed to reschedule id=${reminder.id} after boot: ${outcome.reason}")
            }
        }
    }

    private companion object {
        const val TAG = "ReminderBootReceiver"
    }
}
