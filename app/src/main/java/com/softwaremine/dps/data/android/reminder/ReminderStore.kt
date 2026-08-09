package com.softwaremine.dps.data.android.reminder

import android.content.Context
import com.softwaremine.dps.core.logging.DpsLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists scheduled reminders.
 *
 * ## Purpose
 * `AlarmManager` is write-only: there is no API to ask it what alarms are
 * pending. Listing, updating and cancelling a reminder by id therefore require
 * the app to keep its own record.
 *
 * ## Why persistence rather than an in-memory map
 * Alarms outlive the process â€” that is the entire point of one. A reminder set
 * on Tuesday must still be cancellable on Wednesday after the app has been
 * killed and restarted a dozen times. An in-memory registry would lose track of
 * every alarm the moment the process died, leaving alarms that fire but cannot
 * be listed or cancelled.
 *
 * ## Storage choice
 * `SharedPreferences` holding JSON. Room would be the answer for a growing
 * dataset with queries; a handful of reminders read whole and written whole
 * does not justify a schema, a migration path and a DAO. Room arrives when the
 * secretary's data model does.
 *
 * ## Note on reboots
 * Alarms do **not** survive a device restart â€” Android clears them. This store
 * retains the records, so a future `BOOT_COMPLETED` receiver can reinstate
 * them. That receiver is deliberately not part of Phase B; without it,
 * reminders are lost on reboot, and that is recorded as a known risk rather
 * than quietly ignored.
 *
 * ## Dependencies
 * `android.content.SharedPreferences`, kotlinx.serialization.
 */
class ReminderStore(
    context: Context,
    private val logger: DpsLogger,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Every stored reminder, soonest first. */
    fun all(): List<StoredReminder> = readAll().values.sortedBy { it.triggerAtMillis }

    /** One reminder, or `null`. */
    fun find(id: Int): StoredReminder? = readAll()[id.toString()]

    /** Stores or replaces [reminder]. */
    fun put(reminder: StoredReminder) {
        val current = readAll().toMutableMap()
        current[reminder.id.toString()] = reminder
        write(current)
        logger.d(TAG, "Stored reminder id=${reminder.id}")
    }

    /** Removes a reminder. Returns whether one was present. */
    fun remove(id: Int): Boolean {
        val current = readAll().toMutableMap()
        val removed = current.remove(id.toString()) != null
        if (removed) {
            write(current)
            logger.d(TAG, "Removed reminder id=$id")
        }
        return removed
    }

    /**
     * Deletes reminders whose time has passed.
     *
     * Called opportunistically. Without it the store grows without bound, since
     * a fired alarm leaves its record behind.
     */
    fun pruneExpired(nowMillis: Long): Int {
        val current = readAll()
        val live = current.filterValues { it.triggerAtMillis > nowMillis }
        val pruned = current.size - live.size
        if (pruned > 0) {
            write(live.toMutableMap())
            logger.d(TAG, "Pruned $pruned expired reminder(s)")
        }
        return pruned
    }

    /** Next free reminder id. */
    fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, FIRST_ID)
        // Wrap well below Int.MAX_VALUE: ids become PendingIntent request codes
        // and notification ids, both of which are Int.
        val following = if (next >= MAX_ID) FIRST_ID else next + 1
        prefs.edit().putInt(KEY_NEXT_ID, following).apply()
        return next
    }

    private fun readAll(): Map<String, StoredReminder> {
        val raw = prefs.getString(KEY_REMINDERS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, StoredReminder>>(raw)
        }.getOrElse {
            // Corrupt storage must not brick reminders permanently. Logged and
            // reset â€” losing records is bad, but an unrecoverable crash loop on
            // every read is worse.
            logger.w(TAG, "Reminder store unreadable; resetting", it)
            emptyMap()
        }
    }

    private fun write(reminders: Map<String, StoredReminder>) {
        // The serializer is named explicitly rather than relying on the reified
        // `encodeToString(value)` extension, which resolves to the member
        // overload `encodeToString(serializer, value)` when that extension is
        // not imported and fails with a confusing type mismatch.
        val encoded = json.encodeToString(MAP_SERIALIZER, reminders)
        prefs.edit().putString(KEY_REMINDERS, encoded).apply()
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), StoredReminder.serializer())

        const val TAG = "ReminderStore"
        const val PREFS_NAME = "dps_reminders"
        const val KEY_REMINDERS = "reminders"
        const val KEY_NEXT_ID = "next_id"
        const val FIRST_ID = 1000
        const val MAX_ID = 1_000_000
    }
}

/**
 * A reminder as persisted.
 *
 * [wasExact] records how it was actually scheduled, not how it was requested.
 * When exact-alarm permission is unavailable the reminder is still set, just
 * inexactly, and the user is entitled to be told which they got â€” "around 4pm"
 * and "at 4pm" are materially different promises.
 */
@Serializable
data class StoredReminder(
    val id: Int,
    val title: String,
    val body: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long,
    val wasExact: Boolean,
)
