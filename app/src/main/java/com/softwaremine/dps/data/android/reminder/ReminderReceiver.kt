package com.softwaremine.dps.data.android.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.notification.DpsNotificationChannel
import com.softwaremine.dps.data.android.notification.NotificationPresenter

/**
 * Receives a fired alarm and shows the reminder.
 *
 * ## Purpose
 * The other end of [ReminderScheduler]. `AlarmManager` delivers a broadcast at
 * the scheduled time; this turns it into a notification the user actually sees.
 *
 * ## Why a BroadcastReceiver rather than a Service
 * Showing a notification is short, synchronous work. A receiver's `onReceive`
 * runs on the main thread with roughly ten seconds before the system considers
 * it stuck — ample for building and posting a notification, and far cheaper
 * than starting a service, which on modern Android is additionally constrained
 * by background execution limits.
 *
 * **Nothing slow may be added to `onReceive`.** Anything that could block —
 * disk beyond a preference read, network, inference — must move to
 * `goAsync()` or WorkManager. Blocking here produces an ANR.
 *
 * ## Why the presenter is constructed here rather than injected
 * A `BroadcastReceiver` is instantiated by the system with a no-argument
 * constructor; there is nowhere to inject into. The process may also be started
 * *by* this broadcast, with no Activity and no warm object graph.
 *
 * Constructing [NotificationPresenter] directly is cheap and stateless. The
 * important property — that reminder notifications look identical to every
 * other DPS notification — is preserved because the same presenter class is
 * used, not a second copy of its logic.
 *
 * ## Dependencies
 * `android.content.BroadcastReceiver`, [NotificationPresenter].
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()

        val logger = AndroidDpsLogger()

        if (id < 0 || title.isBlank()) {
            // A malformed broadcast is not worth crashing over. Receivers can
            // be triggered by stale PendingIntents after an upgrade.
            logger.w(TAG, "Ignoring malformed reminder broadcast (id=$id)")
            return
        }

        logger.i(TAG, "Reminder fired id=$id")

        // Same presenter class as the notification tool, so channel handling,
        // icon and tap behaviour cannot diverge between the two paths.
        val presenter = NotificationPresenter(context.applicationContext, logger)
        presenter.post(
            id = id,
            channel = DpsNotificationChannel.REMINDERS,
            title = title,
            body = body.ifBlank { "Reminder from DPS" },
            autoCancel = true,
        )

        // Clear the record now the alarm has fired, so listings show only what
        // is still pending. Cheap: a single preference read and write.
        runCatching {
            ReminderStore(context.applicationContext, logger).remove(id)
        }.onFailure {
            logger.w(TAG, "Could not clear fired reminder id=$id", it)
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"

        private const val EXTRA_ID = "com.softwaremine.dps.extra.REMINDER_ID"
        private const val EXTRA_TITLE = "com.softwaremine.dps.extra.REMINDER_TITLE"
        private const val EXTRA_BODY = "com.softwaremine.dps.extra.REMINDER_BODY"

        /**
         * Builds the Intent this receiver expects.
         *
         * Explicit — naming the component rather than an action — because
         * implicit broadcasts to background receivers are blocked from API 26
         * and would silently never arrive.
         */
        fun intent(context: Context, id: Int, title: String, body: String): Intent =
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
    }
}
