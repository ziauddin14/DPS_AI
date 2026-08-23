package com.softwaremine.dps.data.android.proactive

import android.content.Context
import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists [ProactiveCheckWorker]'s own bookkeeping (M4-A): when it last ran,
 * and which overdue notifications it has already sent.
 *
 * ## Purpose
 * Deliberately its own store, its own prefs file — not
 * [com.softwaremine.dps.data.android.memory.PersistentMemoryStore] (recent
 * conversational/entity context) and not
 * [com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore]
 * (durable user configuration). This is neither: it is the background
 * check's own internal bookkeeping, never surfaced to the user and never
 * written by anything the user said. Keeping the M3 persistence boundary
 * intact means clearing conversational memory or a user preference can never
 * accidentally clear — or be cleared by — this.
 *
 * ## What this deliberately does NOT store
 * No task titles, no conversation content, no full [com.softwaremine.dps.domain.productivity.Task]
 * objects, no AI context, no transcripts — only the marker strings
 * [com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator.markerKey]
 * already documents (`task:<id>:overdue:<dateKey>`) and one timestamp.
 * [com.softwaremine.dps.domain.productivity.TaskRepository] remains the one
 * source of truth for task content; this store never duplicates it.
 *
 * ## Storage choice
 * The same `SharedPreferences`-plus-one-JSON-blob pattern every other M3/M4
 * store in this codebase already uses
 * ([PersistentMemoryStore][com.softwaremine.dps.data.android.memory.PersistentMemoryStore],
 * [PersistentPreferenceStore][com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore],
 * [ReminderStore][com.softwaremine.dps.data.android.reminder.ReminderStore]) —
 * one small, fixed-shape value, no map/id/next-id scheme needed. The
 * constructor takes [SharedPreferences] directly rather than [Context] for
 * the identical JVM-testability reason those stores' own docs explain;
 * [create] preserves the familiar `Context`-taking construction for
 * production use (the worker itself, not `AiContainer` — see
 * [ProactiveCheckWorker]'s own doc for why).
 */
class ProactiveStateStore(
    private val prefs: SharedPreferences,
    private val logger: DpsLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The persisted state, or [ProactiveState.EMPTY] when nothing has been
     * saved yet or the stored value is unreadable — corrupt storage must not
     * crash the application, the same reasoning every other store in this
     * codebase already follows.
     */
    fun load(): ProactiveState {
        val raw = prefs.getString(KEY_STATE, null) ?: return ProactiveState.EMPTY
        return runCatching {
            json.decodeFromString(ProactiveState.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted proactive state unreadable; resetting", it)
            ProactiveState.EMPTY
        }
    }

    /**
     * Persists [state], replacing whatever was stored before — a single
     * value overwritten in place, never merged, exactly like every other
     * store's own `save`.
     */
    fun save(state: ProactiveState) {
        val encoded = json.encodeToString(ProactiveState.serializer(), state)
        prefs.edit().putString(KEY_STATE, encoded).apply()
        logger.d(TAG, "Persisted proactive state (${state.notifiedMarkers.size} marker(s))")
    }

    /** Erases the persisted state outright, so a subsequent [load] returns [ProactiveState.EMPTY]. */
    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
        logger.d(TAG, "Cleared persisted proactive state")
    }

    companion object {
        private const val TAG = "ProactiveStateStore"
        private const val PREFS_NAME = "dps_proactive_state"
        private const val KEY_STATE = "state"

        /** Real, `Context`-backed construction — matches every other store's call shape. */
        fun create(context: Context, logger: DpsLogger): ProactiveStateStore =
            ProactiveStateStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), logger)
    }
}

/**
 * [lastCheckedAtMillis] is diagnostic only — nothing currently reads it back
 * to make a decision; it exists so a future investigation can tell "the
 * check never ran" apart from "the check ran and found nothing eligible."
 *
 * [notifiedMarkers] holds exactly the strings
 * [com.softwaremine.dps.domain.proactive.ProactiveRuleEvaluator.markerKey]
 * produces — opaque to this class, meaningful only to the evaluator that
 * created and the worker that reads them.
 */
@Serializable
data class ProactiveState(
    val lastCheckedAtMillis: Long? = null,
    val notifiedMarkers: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = ProactiveState()
    }
}
