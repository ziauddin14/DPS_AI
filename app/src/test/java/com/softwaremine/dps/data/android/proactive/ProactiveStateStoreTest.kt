package com.softwaremine.dps.data.android.proactive

import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verification of [ProactiveStateStore] (M4-A).
 *
 * ## Why [FakeSharedPreferences], not Robolectric
 * Mirrors every other M3/M4 persistence-store test in this codebase exactly
 * — no store here has a JVM test against a real `Context`, and this project
 * has no Robolectric dependency to construct one. `SharedPreferences` is an
 * interface, so a minimal in-memory fake exercises the real encode/decode/
 * error-handling logic in [ProactiveStateStore] without either.
 */
class ProactiveStateStoreTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun freshStore(prefs: FakeSharedPreferences = FakeSharedPreferences()): ProactiveStateStore =
        ProactiveStateStore(prefs, silentLogger)

    // -----------------------------------------------------------------
    // Test 1 — empty store
    // -----------------------------------------------------------------

    @Test
    fun `a store with nothing saved yet loads as EMPTY`() {
        val store = freshStore()

        assertEquals(ProactiveState.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 2 — write and read a single marker
    // -----------------------------------------------------------------

    @Test
    fun `a single marker survives a save-then-load round-trip`() {
        val store = freshStore()
        val state = ProactiveState(lastCheckedAtMillis = 1_000L, notifiedMarkers = setOf("task:1:overdue:2026-08-23"))

        store.save(state)

        assertEquals(state, store.load())
    }

    // -----------------------------------------------------------------
    // Test 3 — multiple markers
    // -----------------------------------------------------------------

    @Test
    fun `multiple markers all survive a save-then-load round-trip`() {
        val store = freshStore()
        val markers = setOf(
            "task:1:overdue:2026-08-23",
            "task:2:overdue:2026-08-23",
            "task:3:overdue:2026-08-22",
        )
        val state = ProactiveState(lastCheckedAtMillis = 2_000L, notifiedMarkers = markers)

        store.save(state)

        assertEquals(markers, store.load().notifiedMarkers)
    }

    // -----------------------------------------------------------------
    // Test 4 — lastCheckedAtMillis persistence
    // -----------------------------------------------------------------

    @Test
    fun `lastCheckedAtMillis persists independently of whether any marker exists`() {
        val store = freshStore()
        store.save(ProactiveState(lastCheckedAtMillis = 12_345L, notifiedMarkers = emptySet()))

        val loaded = store.load()

        assertEquals(12_345L, loaded.lastCheckedAtMillis)
        assertEquals(emptySet<String>(), loaded.notifiedMarkers)
    }

    // -----------------------------------------------------------------
    // Test 5 — overwrite, not merge
    // -----------------------------------------------------------------

    @Test
    fun `saving a second state replaces the first entirely, never merging stale markers`() {
        val store = freshStore()
        store.save(ProactiveState(lastCheckedAtMillis = 1L, notifiedMarkers = setOf("task:1:overdue:2026-08-22")))
        store.save(ProactiveState(lastCheckedAtMillis = 2L, notifiedMarkers = setOf("task:2:overdue:2026-08-23")))

        val loaded = store.load()

        assertEquals(2L, loaded.lastCheckedAtMillis)
        assertEquals(setOf("task:2:overdue:2026-08-23"), loaded.notifiedMarkers)
    }

    // -----------------------------------------------------------------
    // Test 6 — corrupt storage
    // -----------------------------------------------------------------

    @Test
    fun `corrupt storage recovers to EMPTY rather than crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("state", "{not valid json at all").apply()
        val store = ProactiveStateStore(prefs, silentLogger)

        assertEquals(ProactiveState.EMPTY, store.load())
    }

    @Test
    fun `storage holding valid JSON of the wrong shape also recovers to EMPTY`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("state", """{"unexpected":"shape"}""").apply()
        val store = ProactiveStateStore(prefs, silentLogger)

        assertEquals(ProactiveState.EMPTY, store.load())
    }

    // -----------------------------------------------------------------
    // Test 7 — clear
    // -----------------------------------------------------------------

    @Test
    fun `clear erases persisted state so a subsequent load returns EMPTY`() {
        val store = freshStore()
        store.save(ProactiveState(lastCheckedAtMillis = 1L, notifiedMarkers = setOf("task:1:overdue:2026-08-23")))

        store.clear()

        assertEquals(ProactiveState.EMPTY, store.load())
        assertNull(store.load().lastCheckedAtMillis)
    }

    /**
     * Minimal in-memory fake of [SharedPreferences] — mirrors
     * [com.softwaremine.dps.data.android.preferences.PersistentPreferenceStoreTest]'s
     * own fake exactly; only what [ProactiveStateStore] actually calls needs
     * to behave correctly.
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
