package com.softwaremine.dps.data.android.productivity

import android.content.Context
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.productivity.WorkLog
import com.softwaremine.dps.domain.productivity.WorkLogRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists [WorkLog] entries. See [AndroidTaskStore]'s doc for the storage
 * rationale shared by every Day 06 productivity store.
 */
class AndroidWorkLogStore(
    context: Context,
    private val logger: DpsLogger,
) : WorkLogRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun all(): List<WorkLog> = readAll().values.sortedByDescending { it.dateMillis }

    override fun find(id: Int): WorkLog? = readAll()[id.toString()]

    override fun save(entry: WorkLog): WorkLog {
        val current = readAll().toMutableMap()
        current[entry.id.toString()] = entry
        write(current)
        logger.d(TAG, "Stored work log id=${entry.id}")
        return entry
    }

    override fun delete(id: Int): Boolean {
        val current = readAll().toMutableMap()
        val removed = current.remove(id.toString()) != null
        if (removed) write(current)
        return removed
    }

    override fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, FIRST_ID)
        val following = if (next >= MAX_ID) FIRST_ID else next + 1
        prefs.edit().putInt(KEY_NEXT_ID, following).apply()
        return next
    }

    private fun readAll(): Map<String, WorkLog> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, WorkLog>>(raw)
        }.getOrElse {
            logger.w(TAG, "Work log store unreadable; resetting", it)
            emptyMap()
        }
    }

    private fun write(entries: Map<String, WorkLog>) {
        val encoded = json.encodeToString(MAP_SERIALIZER, entries)
        prefs.edit().putString(KEY_ITEMS, encoded).apply()
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), WorkLog.serializer())

        const val TAG = "AndroidWorkLogStore"
        const val PREFS_NAME = "dps_worklogs"
        const val KEY_ITEMS = "worklogs"
        const val KEY_NEXT_ID = "next_id"
        const val FIRST_ID = 1
        const val MAX_ID = 1_000_000
    }
}
