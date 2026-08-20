package com.softwaremine.dps.data.android.preferences

import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verification of [PersistentPreferenceStore] (M3-C).
 *
 * ## Why [FakeSharedPreferences], not Robolectric
 * Mirrors
 * [com.softwaremine.dps.data.android.memory.PersistentMemoryStoreTest]'s own
 * reasoning exactly: no store in this codebase has a JVM test against a real
 * `Context`, and this project has no Robolectric dependency to construct
 * one. `SharedPreferences` is an interface, so a minimal in-memory fake
 * exercises the real encode/decode/validation/error-handling logic in
 * [PersistentPreferenceStore] without either.
 */
class PersistentPreferenceStoreTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun freshStore(prefs: FakeSharedPreferences = FakeSharedPreferences()): PersistentPreferenceStore =
        PersistentPreferenceStore(prefs, silentLogger)

    // -----------------------------------------------------------------
    // Test 1 — empty store
    // -----------------------------------------------------------------

    @Test
    fun `a store with nothing saved yet loads as EMPTY`() {
        val store = freshStore()

        assertEquals(UserPreferences.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 2 — full round-trip
    // -----------------------------------------------------------------

    @Test
    fun `a representative non-default preference survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = UserPreferences(defaultReminderLeadMinutes = 15)

        store.save(original)
        val loaded = store.load()

        assertEquals(original, loaded)
    }

    // -----------------------------------------------------------------
    // Test 3 — nullable field
    // -----------------------------------------------------------------

    @Test
    fun `a null defaultReminderLeadMinutes survives a save-then-load round-trip as null`() {
        val store = freshStore()
        val original = UserPreferences(defaultReminderLeadMinutes = null)

        store.save(original)
        val loaded = store.load()

        assertEquals(original, loaded)
        assertNull(loaded.defaultReminderLeadMinutes)
    }

    // -----------------------------------------------------------------
    // Test 4 — corrupt storage
    // -----------------------------------------------------------------

    @Test
    fun `corrupt storage recovers to EMPTY rather than crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("preferences", "{not valid json at all").apply()
        val store = PersistentPreferenceStore(prefs, silentLogger)

        assertEquals(UserPreferences.EMPTY, store.load())
    }

    @Test
    fun `storage holding valid JSON of the wrong shape also recovers to EMPTY`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("preferences", """{"unexpected":"shape"}""").apply()
        val store = PersistentPreferenceStore(prefs, silentLogger)

        assertEquals(UserPreferences.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 5 — overwrite, not merge
    // -----------------------------------------------------------------

    @Test
    fun `saving a second preference set replaces the first entirely`() {
        val store = freshStore()

        store.save(UserPreferences(defaultReminderLeadMinutes = 15))
        store.save(UserPreferences(defaultReminderLeadMinutes = 10))

        assertEquals(10, store.load().defaultReminderLeadMinutes)
    }

    // -----------------------------------------------------------------
    // Test 6 — clear
    // -----------------------------------------------------------------

    @Test
    fun `clear erases persisted preferences so a subsequent load returns EMPTY`() {
        val store = freshStore()
        store.save(UserPreferences(defaultReminderLeadMinutes = 15))

        store.clear()

        assertEquals(UserPreferences.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 7 — invalid values are rejected, never persisted
    // -----------------------------------------------------------------

    @Test
    fun `positive lead-minute values are accepted`() {
        val store = freshStore()

        for (minutes in listOf(1, 15, 30, 120)) {
            store.save(UserPreferences(defaultReminderLeadMinutes = minutes))
            assertEquals(minutes, store.load().defaultReminderLeadMinutes)
        }
    }

    @Test
    fun `a zero or negative lead time is rejected with IllegalArgumentException and never persisted`() {
        val store = freshStore()
        store.save(UserPreferences(defaultReminderLeadMinutes = 15)) // known-good baseline

        for (invalid in listOf(0, -1, -30)) {
            assertThrows(IllegalArgumentException::class.java) {
                store.save(UserPreferences(defaultReminderLeadMinutes = invalid))
            }
        }

        assertEquals(
            "A rejected save must never overwrite or corrupt what was already stored",
            15,
            store.load().defaultReminderLeadMinutes,
        )
    }

    /**
     * Minimal in-memory fake of [SharedPreferences] — mirrors
     * [com.softwaremine.dps.data.android.memory.PersistentMemoryStoreTest]'s
     * own fake exactly; only what [PersistentPreferenceStore] actually calls
     * needs to behave correctly.
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
