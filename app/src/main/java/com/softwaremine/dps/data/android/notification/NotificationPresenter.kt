package com.softwaremine.dps.data.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.softwaremine.dps.R
import com.softwaremine.dps.core.logging.DpsLogger

/**
 * Builds and posts every notification DPS shows.
 *
 * ## Purpose
 * One place that knows how to put a notification on screen. Both
 * [com.softwaremine.dps.data.android.tool.AndroidNotificationTool] and
 * [com.softwaremine.dps.data.android.reminder.ReminderReceiver] post
 * notifications; without a shared presenter they would each build their own
 * channel handling, icon, PendingIntent and flag choices — and drift apart.
 *
 * Reminder notifications and assistant notifications should look and behave
 * identically. Making that structural rather than a convention is the point.
 *
 * ## APIs used — verified against official documentation
 * | API | Notes |
 * |---|---|
 * | `NotificationCompat.Builder` | `androidx.core:core` |
 * | `NotificationManagerCompat.from(context).notify(id, notification)` | posting |
 * | `NotificationChannel` + `NotificationManager.createNotificationChannel` | **mandatory from API 26**, our minSdk |
 * | `PendingIntent.FLAG_IMMUTABLE` | **mandatory from API 31** |
 *
 * ## Channels are created eagerly and idempotently
 * `createNotificationChannel` is documented as a no-op when the channel already
 * exists, so calling it before every post is safe and removes an entire class
 * of "notification silently did not appear because the channel was missing"
 * bug. Channels are cheap; a missing one is invisible and total.
 *
 * ## POST_NOTIFICATIONS
 * On API 33+ posting requires the runtime permission. The tool layer checks it
 * before dispatch, so this class is not the permission gate — but
 * [areNotificationsEnabled] is still exposed, because a user can disable
 * notifications for the app *without* revoking the permission, and a reminder
 * that silently never appears is worse than one that reports it cannot.
 *
 * ## Dependencies
 * `androidx.core`, `android.app`. Android layer only.
 */
class NotificationPresenter(
    private val context: Context,
    private val logger: DpsLogger,
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Posts a notification.
     *
     * @return `true` if handed to the system. `false` means notifications are
     *   disabled for the app, which is reported rather than swallowed.
     */
    fun post(
        id: Int,
        channel: DpsNotificationChannel,
        title: String,
        body: String,
        autoCancel: Boolean = true,
        contentIntent: PendingIntent? = defaultContentIntent(),
    ): Boolean {
        ensureChannel(channel)

        if (!areNotificationsEnabled()) {
            // Distinct from "permission not granted". The user can switch
            // notifications off in settings with the permission still held, and
            // notify() would then no-op with no signal at all.
            logger.w(TAG, "Notifications are disabled for this app; not posting id=$id")
            return false
        }

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_dps_notification)
            .setContentTitle(title)
            .setContentText(body)
            // Long bodies are truncated to one line without this; a reminder
            // whose text is cut off defeats the purpose of sending it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(channel.compatPriority)
            .setAutoCancel(autoCancel)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()

        return try {
            manager.notify(id, notification)
            logger.i(TAG, "Posted notification id=$id channel=${channel.id}")
            true
        } catch (security: SecurityException) {
            // Defensive. Official documentation on whether notify() throws or
            // silently no-ops without POST_NOTIFICATIONS was not fully
            // retrievable, so both outcomes are handled rather than assumed.
            logger.w(TAG, "notify() denied for id=$id", security)
            false
        }
    }

    /** Cancels a previously posted notification. Safe for unknown ids. */
    fun cancel(id: Int) {
        manager.cancel(id)
        logger.d(TAG, "Cancelled notification id=$id")
    }

    /**
     * Whether the app may currently show notifications.
     *
     * Covers the user disabling notifications in settings, which is separate
     * from the POST_NOTIFICATIONS grant.
     */
    fun areNotificationsEnabled(): Boolean =
        runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

    /**
     * Creates [channel] if absent.
     *
     * Guarded on API 26 despite minSdk being 26 — the guard documents the
     * requirement and costs nothing, and minSdk has been lowered before in
     * other projects with exactly this class of breakage as the result.
     */
    fun ensureChannel(channel: DpsNotificationChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val systemManager = context.getSystemService<NotificationManager>() ?: run {
            logger.w(TAG, "NotificationManager unavailable; cannot create channel")
            return
        }

        // Idempotent by contract: re-creating an existing channel does not
        // reset user-modified settings.
        val androidChannel = NotificationChannel(
            channel.id,
            channel.displayName,
            channel.importance,
        ).apply {
            description = channel.description
        }
        systemManager.createNotificationChannel(androidChannel)
    }

    /** Creates every channel. Called once at startup. */
    fun ensureAllChannels() {
        DpsNotificationChannel.entries.forEach(::ensureChannel)
    }

    /**
     * Intent used when a notification is tapped: open DPS.
     *
     * `FLAG_IMMUTABLE` is mandatory from API 31 and correct at every level —
     * nothing outside the app has any business rewriting this intent.
     */
    private fun defaultContentIntent(): PendingIntent? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return null

        return PendingIntent.getActivity(
            context,
            CONTENT_INTENT_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "Notifications"
        const val CONTENT_INTENT_REQUEST_CODE = 1001
    }
}

/**
 * The notification channels DPS uses.
 *
 * ## Why an enum
 * Channels are user-visible: each appears in system settings with its own
 * toggle. That makes the set of channels a product decision, and one worth
 * being able to read in a single place rather than discovering scattered across
 * whichever classes happened to post something.
 *
 * Importance is chosen per channel because a missed reminder is a failure of
 * the product's core promise, whereas a general assistant message is not.
 */
enum class DpsNotificationChannel(
    val id: String,
    val displayName: String,
    val description: String,
    val importance: Int,
    /** Pre-channel priority, used by NotificationCompat below API 26. */
    val compatPriority: Int,
) {

    /**
     * Time-critical reminders.
     *
     * `IMPORTANCE_HIGH` so it can surface as a heads-up notification — a
     * reminder the user does not see has failed entirely, which is the one
     * outcome `PRD.md` singles out as unacceptable.
     */
    REMINDERS(
        id = "dps_reminders",
        displayName = "Reminders",
        description = "Time-sensitive reminders you asked DPS to set.",
        importance = NotificationManager.IMPORTANCE_HIGH,
        compatPriority = NotificationCompat.PRIORITY_HIGH,
    ),

    /**
     * General assistant messages.
     *
     * `IMPORTANCE_DEFAULT`: visible without interrupting.
     */
    ASSISTANT(
        id = "dps_assistant",
        displayName = "Assistant",
        description = "Messages and confirmations from DPS.",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        compatPriority = NotificationCompat.PRIORITY_DEFAULT,
    ),
    ;

    companion object {
        /** Resolves a channel by wire name, defaulting to [ASSISTANT]. */
        fun fromName(name: String?): DpsNotificationChannel = when (name?.lowercase()?.trim()) {
            "reminder", "reminders", REMINDERS.id -> REMINDERS
            else -> ASSISTANT
        }
    }
}
