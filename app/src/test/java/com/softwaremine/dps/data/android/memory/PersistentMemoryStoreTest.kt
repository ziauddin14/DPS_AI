package com.softwaremine.dps.data.android.memory

import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.memory.CalendarEventMemory
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.EmailMemory
import com.softwaremine.dps.domain.memory.MeetingMemory
import com.softwaremine.dps.domain.memory.ReminderMemory
import com.softwaremine.dps.domain.memory.TaskMemory
import com.softwaremine.dps.domain.memory.WhatsAppMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verification of [PersistentMemoryStore] (M3-A).
 *
 * ## Why [FakeSharedPreferences], not Robolectric
 * No store in this codebase — [com.softwaremine.dps.data.android.reminder.ReminderStore]
 * included — has a JVM test today; they are exercised only on-device, and this
 * project has no Robolectric dependency to construct a real `Context` on the
 * JVM. `SharedPreferences` is an interface, so a minimal in-memory fake
 * exercises the real encode/decode/error-handling logic in
 * [PersistentMemoryStore] without either.
 */
class PersistentMemoryStoreTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun freshStore(prefs: FakeSharedPreferences = FakeSharedPreferences()): PersistentMemoryStore =
        PersistentMemoryStore(prefs, silentLogger)

    // -----------------------------------------------------------------
    // Test 1 — empty store
    // -----------------------------------------------------------------

    @Test
    fun `a store with nothing saved yet loads as EMPTY`() {
        val store = freshStore()

        assertEquals(ConversationMemory.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 2 — full round-trip
    // -----------------------------------------------------------------

    @Test
    fun `every existing field survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = ConversationMemory(
            lastContact = Contact(
                id = "42",
                displayName = "Abdul Rahman",
                phoneNumbers = listOf("+923001234567", "+923009876543"),
                emailAddresses = listOf("abdul@example.com"),
            ),
            lastReminder = ReminderMemory(id = 1001, title = "call the bank", triggerAtMillis = 1_700_000_000_000L),
            lastCalendarEvent = CalendarEventMemory(
                id = 500L,
                title = "standup meeting",
                startMillis = 1_700_000_100_000L,
                endMillis = 1_700_003_700_000L,
            ),
            lastEmail = EmailMemory(recipientName = "Ali", recipientAddress = "ali@example.com", subject = "Agenda"),
            lastWhatsAppAction = WhatsAppMemory(recipientName = "Ali", recipientPhone = "+923001112222", message = "on my way"),
            lastTask = TaskMemory(id = 7, title = "send agenda"),
            lastMeeting = MeetingMemory(id = 3, title = "kickoff"),
            lastReferencedPerson = "Ali",
            lastReferencedDateTimeMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_500_000L,
        )

        store.save(original)
        val loaded = store.load()

        assertEquals("The whole structure must round-trip exactly, not merely one field", original, loaded)
    }

    // -----------------------------------------------------------------
    // Test 3 — nullable fields
    // -----------------------------------------------------------------

    @Test
    fun `a mixture of populated and null fields preserves the nulls exactly`() {
        val store = freshStore()
        val original = ConversationMemory(
            lastContact = null,
            lastReminder = ReminderMemory(id = 1001, title = "call the bank", triggerAtMillis = 1_700_000_000_000L),
            lastCalendarEvent = null,
            lastEmail = EmailMemory(recipientName = null, recipientAddress = "ali@example.com", subject = "Agenda"),
            lastWhatsAppAction = null,
            lastTask = null,
            lastMeeting = null,
            lastReferencedPerson = "Ali",
            lastReferencedDateTimeMillis = null,
            updatedAtMillis = 1_700_000_500_000L,
        )

        store.save(original)
        val loaded = store.load()

        assertEquals(original, loaded)
        assertNull(loaded.lastContact)
        assertNull(loaded.lastCalendarEvent)
        assertNull(loaded.lastWhatsAppAction)
        assertNull(loaded.lastTask)
        assertNull(loaded.lastMeeting)
        assertNull(loaded.lastReferencedDateTimeMillis)
        assertNull("A null nested field (recipientName) must stay null, not become an empty string", loaded.lastEmail?.recipientName)
    }

    // -----------------------------------------------------------------
    // Test 4 — corrupt storage
    // -----------------------------------------------------------------

    @Test
    fun `corrupt storage recovers to EMPTY rather than crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("memory", "{not valid json at all").apply()
        val store = PersistentMemoryStore(prefs, silentLogger)

        assertEquals(ConversationMemory.EMPTY, store.load())
    }

    @Test
    fun `storage holding valid JSON of the wrong shape also recovers to EMPTY`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("memory", """{"unexpected":"shape","nested":{"a":1}}""").apply()
        val store = PersistentMemoryStore(prefs, silentLogger)

        assertEquals(ConversationMemory.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 5 — overwrite, not merge
    // -----------------------------------------------------------------

    @Test
    fun `saving a second memory replaces the first entirely, never merging stale fields`() {
        val store = freshStore()
        val memoryA = ConversationMemory(
            lastReminder = ReminderMemory(id = 1, title = "reminder A", triggerAtMillis = 1_000L),
            lastTask = TaskMemory(id = 1, title = "task A"),
            lastReferencedPerson = "Ali",
        )
        val memoryB = ConversationMemory(
            lastCalendarEvent = CalendarEventMemory(id = 2L, title = "event B", startMillis = 2_000L, endMillis = 3_000L),
            lastReferencedPerson = "Hassan",
        )

        store.save(memoryA)
        store.save(memoryB)
        val loaded = store.load()

        assertEquals(memoryB, loaded)
        assertNull("Memory A's reminder must not survive alongside memory B", loaded.lastReminder)
        assertNull("Memory A's task must not survive alongside memory B", loaded.lastTask)
        assertEquals("Hassan", loaded.lastReferencedPerson)
    }

    // -----------------------------------------------------------------
    // clear() (M3-B)
    // -----------------------------------------------------------------

    @Test
    fun `clear erases persisted memory so a subsequent load returns EMPTY`() {
        val store = freshStore()
        store.save(ConversationMemory(lastReferencedPerson = "Ali", updatedAtMillis = 1L))

        store.clear()

        assertEquals(ConversationMemory.EMPTY, store.load())
    }

    /**
     * Minimal in-memory fake of [SharedPreferences] — only what
     * [PersistentMemoryStore] actually calls (`getString`, `edit().putString().apply()`)
     * needs to behave correctly; every other member exists only to satisfy
     * the interface and is never exercised by these tests.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var cleared = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = values }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = REMOVE_MARKER }

            override fun clear(): SharedPreferences.Editor = apply { cleared = true }

            override fun commit(): Boolean {
                applyPending()
                return true
            }

            override fun apply() {
                applyPending()
            }

            private fun applyPending() {
                if (cleared) values.clear()
                pending.forEach { (key, value) ->
                    if (value === REMOVE_MARKER) values.remove(key) else values[key] = value
                }
                pending.clear()
            }
        }

        private companion object {
            /** Distinguishes "remove this key" from "put a null value" in [FakeEditor.pending]. */
            val REMOVE_MARKER = Any()
        }
    }
}
