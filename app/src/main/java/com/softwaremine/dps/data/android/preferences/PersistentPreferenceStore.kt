package com.softwaremine.dps.data.android.preferences

import android.content.Context
import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.preferences.UserPreferences
import kotlinx.serialization.json.Json

/**
 * Persists [UserPreferences] (M3-C).
 *
 * ## Purpose
 * Durable user configuration, kept in its own store — deliberately separate
 * from [com.softwaremine.dps.data.android.memory.PersistentMemoryStore],
 * which persists recent conversational/entity context, a different concept
 * entirely (see [UserPreferences]'s own doc). A distinct prefs file/key
 * namespace means clearing conversational memory
 * (`SecretaryOrchestrator.reset()`) can never accidentally clear a
 * preference the user explicitly configured, or vice versa.
 *
 * ## Storage choice
 * The same `SharedPreferences`-plus-one-JSON-blob pattern as
 * [PersistentMemoryStore][com.softwaremine.dps.data.android.memory.PersistentMemoryStore]
 * and [ReminderStore][com.softwaremine.dps.data.android.reminder.ReminderStore] —
 * one small, fixed-shape value, so no map/id/next-id scheme is needed here
 * either. The constructor takes [SharedPreferences] directly rather than
 * [Context] for the same JVM-testability reason
 * [PersistentMemoryStore]'s own doc explains; [create] preserves the
 * familiar `Context`-taking construction for production use.
 *
 * ## Validation
 * [save] rejects (via [IllegalArgumentException]) a non-null
 * [UserPreferences.defaultReminderLeadMinutes] that isn't a positive number
 * of minutes — a zero or negative "lead time" is not a meaningful default
 * for a reminder that fires *before* an event, and nothing is written when
 * rejected, so an invalid attempt can never leave a partially-invalid value
 * behind.
 */
class PersistentPreferenceStore(
    private val prefs: SharedPreferences,
    private val logger: DpsLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The persisted preferences, or [UserPreferences.EMPTY] when nothing has
     * been saved yet or the stored value is unreadable — corrupt storage
     * must not crash the application, the same reasoning
     * [PersistentMemoryStore][com.softwaremine.dps.data.android.memory.PersistentMemoryStore]
     * already follows.
     */
    fun load(): UserPreferences {
        val raw = prefs.getString(KEY_PREFERENCES, null) ?: return UserPreferences.EMPTY
        return runCatching {
            json.decodeFromString(UserPreferences.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted user preferences unreadable; resetting", it)
            UserPreferences.EMPTY
        }
    }

    /**
     * Persists [preferences], replacing whatever was stored before — a
     * single value overwritten in place, never merged, exactly like
     * [PersistentMemoryStore.save][com.softwaremine.dps.data.android.memory.PersistentMemoryStore.save].
     *
     * @throws IllegalArgumentException if [UserPreferences.defaultReminderLeadMinutes]
     *   is non-null and not a positive number of minutes.
     */
    fun save(preferences: UserPreferences) {
        val minutes = preferences.defaultReminderLeadMinutes
        require(minutes == null || minutes > 0) {
            "Invalid default reminder lead time \"$minutes\" — expected a positive number of minutes."
        }
        val encoded = json.encodeToString(UserPreferences.serializer(), preferences)
        prefs.edit().putString(KEY_PREFERENCES, encoded).apply()
        logger.d(TAG, "Persisted user preferences")
    }

    /** Erases the persisted preferences outright, so a subsequent [load] returns [UserPreferences.EMPTY]. */
    fun clear() {
        prefs.edit().remove(KEY_PREFERENCES).apply()
        logger.d(TAG, "Cleared persisted user preferences")
    }

    companion object {
        private const val TAG = "PersistentPreferenceStore"
        private const val PREFS_NAME = "dps_user_preferences"
        private const val KEY_PREFERENCES = "preferences"

        /** Real, `Context`-backed construction — matches every other store's call shape. */
        fun create(context: Context, logger: DpsLogger): PersistentPreferenceStore =
            PersistentPreferenceStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), logger)
    }
}
