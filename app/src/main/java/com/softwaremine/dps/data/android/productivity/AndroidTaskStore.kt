package com.softwaremine.dps.data.android.productivity

import android.content.Context
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.productivity.Task
import com.softwaremine.dps.domain.productivity.TaskRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists [Task]s in `SharedPreferences`.
 *
 * ## Storage choice (Day 06 rule 15)
 * Mirrors [com.softwaremine.dps.data.android.reminder.ReminderStore]'s
 * proven SharedPreferences-plus-JSON pattern rather than introducing Room. A
 * personal secretary's task list — read and written whole, dozens to a few
 * hundred records — is nowhere near the volume where that trade-off flips;
 * Room would add a schema, a migration path and a DAO to solve a problem
 * this data does not have.
 *
 * Unlike `ReminderStore`, [Task] is itself `@Serializable` and stored
 * directly rather than through a separate "Stored*" DTO —
 * [com.softwaremine.dps.domain.intent.DpsIntent] already establishes in this
 * codebase that a domain type can carry kotlinx.serialization annotations
 * without pulling in Android, so the extra indirection has nothing left to buy.
 *
 * ## Dependencies
 * `android.content.SharedPreferences`, kotlinx.serialization,
 * [com.softwaremine.dps.domain.productivity.Task]. The only Android import in
 * the Day 06 task feature.
 */
class AndroidTaskStore(
    context: Context,
    private val logger: DpsLogger,
) : TaskRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun all(): List<Task> = readAll().values.sortedByDescending { it.createdAtMillis }

    override fun find(id: Int): Task? = readAll()[id.toString()]

    override fun save(task: Task): Task {
        val current = readAll().toMutableMap()
        current[task.id.toString()] = task
        write(current)
        logger.d(TAG, "Stored task id=${task.id}")
        return task
    }

    override fun delete(id: Int): Boolean {
        val current = readAll().toMutableMap()
        val removed = current.remove(id.toString()) != null
        if (removed) {
            write(current)
            logger.d(TAG, "Removed task id=$id")
        }
        return removed
    }

    override fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, FIRST_ID)
        val following = if (next >= MAX_ID) FIRST_ID else next + 1
        prefs.edit().putInt(KEY_NEXT_ID, following).apply()
        return next
    }

    private fun readAll(): Map<String, Task> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Task>>(raw)
        }.getOrElse {
            // Corrupt storage must not brick task tracking permanently, same
            // reasoning as ReminderStore's own recovery.
            logger.w(TAG, "Task store unreadable; resetting", it)
            emptyMap()
        }
    }

    private fun write(tasks: Map<String, Task>) {
        val encoded = json.encodeToString(MAP_SERIALIZER, tasks)
        prefs.edit().putString(KEY_ITEMS, encoded).apply()
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), Task.serializer())

        const val TAG = "AndroidTaskStore"
        const val PREFS_NAME = "dps_tasks"
        const val KEY_ITEMS = "tasks"
        const val KEY_NEXT_ID = "next_id"

        // Unlike ReminderStore's ids, these are never reused as a
        // PendingIntent request code or notification id, so there is no
        // reason to start above 1.
        const val FIRST_ID = 1
        const val MAX_ID = 1_000_000
    }
}
