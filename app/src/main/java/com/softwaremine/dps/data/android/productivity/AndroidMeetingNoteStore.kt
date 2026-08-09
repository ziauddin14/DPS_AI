package com.softwaremine.dps.data.android.productivity

import android.content.Context
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.productivity.MeetingNote
import com.softwaremine.dps.domain.productivity.MeetingNoteRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists [MeetingNote]s. See [AndroidTaskStore]'s doc for the storage
 * rationale shared by every Day 06 productivity store.
 */
class AndroidMeetingNoteStore(
    context: Context,
    private val logger: DpsLogger,
) : MeetingNoteRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun all(): List<MeetingNote> = readAll().values.sortedByDescending { it.dateMillis }

    override fun find(id: Int): MeetingNote? = readAll()[id.toString()]

    override fun save(note: MeetingNote): MeetingNote {
        val current = readAll().toMutableMap()
        current[note.id.toString()] = note
        write(current)
        logger.d(TAG, "Stored meeting note id=${note.id}")
        return note
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

    private fun readAll(): Map<String, MeetingNote> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, MeetingNote>>(raw)
        }.getOrElse {
            logger.w(TAG, "Meeting note store unreadable; resetting", it)
            emptyMap()
        }
    }

    private fun write(notes: Map<String, MeetingNote>) {
        val encoded = json.encodeToString(MAP_SERIALIZER, notes)
        prefs.edit().putString(KEY_ITEMS, encoded).apply()
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), MeetingNote.serializer())

        const val TAG = "AndroidMeetingNoteStore"
        const val PREFS_NAME = "dps_meetings"
        const val KEY_ITEMS = "meetings"
        const val KEY_NEXT_ID = "next_id"
        const val FIRST_ID = 1
        const val MAX_ID = 1_000_000
    }
}
