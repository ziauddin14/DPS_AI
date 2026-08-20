package com.softwaremine.dps.data.android.memory

import android.content.Context
import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.memory.ConversationMemory
import kotlinx.serialization.json.Json

/**
 * Persists [ConversationMemory] (M3-A).
 *
 * ## Purpose
 * [ConversationMemory] currently lives only as an in-memory field on
 * `SecretaryOrchestrator`, which is itself a process-scoped singleton (see the
 * M3 investigation report) — it disappears the moment the process dies, is
 * force-stopped, or the device reboots. This is that missing durability layer,
 * for exactly the same reason
 * [com.softwaremine.dps.data.android.reminder.ReminderStore] exists for
 * reminders: without it, "usko"/"that meeting" stops resolving the instant the
 * app is reopened, even though the user experiences that as one continuous
 * conversation.
 *
 * ## Storage choice
 * `SharedPreferences` holding one JSON blob — the same pattern
 * [com.softwaremine.dps.data.android.reminder.ReminderStore] already uses for
 * [com.softwaremine.dps.data.android.reminder.StoredReminder]. Unlike that
 * store, [ConversationMemory] is not a growing collection of records keyed by
 * id — it is one small, fixed-shape value (nine "last X" slots) — so there is
 * no map/id/next-id scheme here, only a single key holding the whole encoded
 * structure.
 *
 * ## Why the constructor takes [SharedPreferences] directly, unlike `ReminderStore(Context, ...)`
 * The one deliberate deviation from `ReminderStore`'s exact shape. Neither
 * `ReminderStore` nor any other existing store in this codebase has a JVM
 * test — they are exercised only on-device — and this project has no
 * Robolectric dependency, so a real `android.content.Context` cannot be
 * constructed on the JVM. `SharedPreferences` is an interface, not a concrete
 * Android class, so a minimal fake implementing it runs correctly under plain
 * JUnit with no new dependency and no Android runtime. [create] below
 * preserves the familiar `Context`-taking construction for real production use
 * (wired in M3-B).
 *
 * ## Why this is not wired into `SecretaryOrchestrator` yet (M3-A)
 * Scoped deliberately narrow: this class must be independently usable and
 * testable on its own before anything reads or writes through it in
 * production. Wiring — seeding `SecretaryOrchestrator`'s memory at
 * construction and persisting after every successful write — is M3-B.
 *
 * ## Dependencies
 * `android.content.SharedPreferences`, kotlinx.serialization.
 */
class PersistentMemoryStore(
    private val prefs: SharedPreferences,
    private val logger: DpsLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The persisted memory, or [ConversationMemory.EMPTY] when nothing has
     * been saved yet or the stored value is unreadable.
     *
     * Corrupt storage must not brick the assistant's memory permanently, the
     * same reasoning [ReminderStore][com.softwaremine.dps.data.android.reminder.ReminderStore]
     * already follows: losing remembered context is a mild inconvenience —
     * one more "which one do you mean?" — while crashing or wedging on every
     * read is not.
     */
    fun load(): ConversationMemory {
        val raw = prefs.getString(KEY_MEMORY, null) ?: return ConversationMemory.EMPTY
        return runCatching {
            json.decodeFromString(ConversationMemory.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted conversation memory unreadable; resetting", it)
            ConversationMemory.EMPTY
        }
    }

    /**
     * Persists [memory], replacing whatever was stored before — a single
     * value overwritten in place, never merged, so there is exactly one
     * stored truth and no possibility of stale fields from an earlier save
     * surviving alongside a newer one.
     */
    fun save(memory: ConversationMemory) {
        val encoded = json.encodeToString(ConversationMemory.serializer(), memory)
        prefs.edit().putString(KEY_MEMORY, encoded).apply()
        logger.d(TAG, "Persisted conversation memory")
    }

    /**
     * Erases the persisted memory outright (M3-B), so a subsequent [load]
     * returns [ConversationMemory.EMPTY] the same way it does when nothing
     * was ever saved — used by `SecretaryOrchestrator.reset()`, which is a
     * deliberate "forget the conversation" action, not a save of an empty
     * value.
     */
    fun clear() {
        prefs.edit().remove(KEY_MEMORY).apply()
        logger.d(TAG, "Cleared persisted conversation memory")
    }

    companion object {
        private const val TAG = "PersistentMemoryStore"
        private const val PREFS_NAME = "dps_conversation_memory"
        private const val KEY_MEMORY = "memory"

        /** Real, `Context`-backed construction — matches every other store's call shape. */
        fun create(context: Context, logger: DpsLogger): PersistentMemoryStore =
            PersistentMemoryStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), logger)
    }
}
