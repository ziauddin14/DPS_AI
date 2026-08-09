package com.softwaremine.dps.data.android.productivity

import android.content.Context
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.productivity.ActionItem
import com.softwaremine.dps.domain.productivity.ActionItemRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists [ActionItem]s. See [AndroidTaskStore]'s doc for the storage
 * rationale shared by every Day 06 productivity store.
 */
class AndroidActionItemStore(
    context: Context,
    private val logger: DpsLogger,
) : ActionItemRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun all(): List<ActionItem> = readAll().values.sortedByDescending { it.createdAtMillis }

    override fun find(id: Int): ActionItem? = readAll()[id.toString()]

    override fun save(item: ActionItem): ActionItem {
        val current = readAll().toMutableMap()
        current[item.id.toString()] = item
        write(current)
        logger.d(TAG, "Stored action item id=${item.id}")
        return item
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

    private fun readAll(): Map<String, ActionItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ActionItem>>(raw)
        }.getOrElse {
            logger.w(TAG, "Action item store unreadable; resetting", it)
            emptyMap()
        }
    }

    private fun write(items: Map<String, ActionItem>) {
        val encoded = json.encodeToString(MAP_SERIALIZER, items)
        prefs.edit().putString(KEY_ITEMS, encoded).apply()
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), ActionItem.serializer())

        const val TAG = "AndroidActionItemStore"
        const val PREFS_NAME = "dps_action_items"
        const val KEY_ITEMS = "action_items"
        const val KEY_NEXT_ID = "next_id"
        const val FIRST_ID = 1
        const val MAX_ID = 1_000_000
    }
}
