package com.softwaremine.dps.data.android.secretary

import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.secretary.ExecutionRecoveryState
import com.softwaremine.dps.domain.secretary.OperationCheckpoint
import com.softwaremine.dps.domain.secretary.OperationType
import com.softwaremine.dps.domain.secretary.PersistedDisambiguationCandidate
import com.softwaremine.dps.domain.secretary.PersistedPendingPlan
import com.softwaremine.dps.domain.secretary.PersistedPendingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verification of [PersistentRecoveryStore] (M5-B).
 *
 * ## Why [FakeSharedPreferences], not Robolectric
 * Mirrors every other M3/M4/M5 persistence-store test in this codebase
 * exactly — no store here has a JVM test against a real `Context`, and this
 * project has no Robolectric dependency to construct one. `SharedPreferences`
 * is an interface, so a minimal in-memory fake exercises the real
 * encode/decode/error-handling logic in [PersistentRecoveryStore] without
 * either.
 */
class PersistentRecoveryStoreTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun freshStore(prefs: FakeSharedPreferences = FakeSharedPreferences()): PersistentRecoveryStore =
        PersistentRecoveryStore(prefs, silentLogger)

    private val sampleIntent = DpsIntent(
        type = IntentType.REMINDER,
        action = IntentAction.CREATE,
        parameters = IntentParameters(title = "Submit report", person = null),
    )

    private val sampleContact = Contact(
        id = "42",
        displayName = "Ali Khan",
        phoneNumbers = listOf("+923001234567"),
    )

    // -----------------------------------------------------------------
    // Test 1 — empty store
    // -----------------------------------------------------------------

    @Test
    fun `a store with nothing saved yet loads as null`() {
        val store = freshStore()

        assertNull(store.load())
    }

    // -----------------------------------------------------------------
    // Test 2 — Clarification round-trip, no plan
    // -----------------------------------------------------------------

    @Test
    fun `a Clarification record with no plan survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = ExecutionRecoveryState(
            pending = PersistedPendingState.Clarification(
                intent = sampleIntent,
                question = "What time?",
                missing = setOf(IntentField.TIME),
                partial = sampleIntent.parameters,
                requestedAtMillis = 1_000L,
            ),
            plan = null,
        )

        store.save(original)

        assertEquals(original, store.load())
    }

    // -----------------------------------------------------------------
    // Test 3 — ContactSelection round-trip, with a plan remainder
    // -----------------------------------------------------------------

    @Test
    fun `a ContactSelection record with a plan remainder survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = ExecutionRecoveryState(
            pending = PersistedPendingState.ContactSelection(
                originalIntent = sampleIntent,
                candidates = listOf(sampleContact, sampleContact.copy(id = "43", displayName = "Ali Raza")),
                requestedAtMillis = 2_000L,
            ),
            plan = PersistedPendingPlan(
                remainingSteps = listOf(sampleIntent.copy(type = IntentType.TASK)),
                remainingOffsets = listOf(null, -1_800_000L),
                completedReplies = listOf("Task \"Submit report\" added."),
                lastEventStartMillis = 123_456L,
                requestedAtMillis = 2_000L,
            ),
        )

        store.save(original)

        assertEquals(original, store.load())
    }

    // -----------------------------------------------------------------
    // Test 4 — TypeDisambiguation round-trip
    // -----------------------------------------------------------------

    @Test
    fun `a TypeDisambiguation record survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = ExecutionRecoveryState(
            pending = PersistedPendingState.TypeDisambiguation(
                originalIntent = sampleIntent,
                candidates = listOf(
                    PersistedDisambiguationCandidate(IntentType.CALENDAR_EVENT, "7", "Team sync"),
                    PersistedDisambiguationCandidate(IntentType.TASK, "9", "Submit report"),
                ),
                requestedAtMillis = 3_000L,
            ),
            plan = null,
        )

        store.save(original)

        assertEquals(original, store.load())
    }

    // -----------------------------------------------------------------
    // Test 5 — Confirmation round-trip
    // -----------------------------------------------------------------

    @Test
    fun `a Confirmation record survives a save-then-load round-trip exactly`() {
        val store = freshStore()
        val original = ExecutionRecoveryState(
            pending = PersistedPendingState.Confirmation(
                intent = sampleIntent.copy(action = IntentAction.CANCEL),
                requestedAtMillis = 4_000L,
            ),
            plan = null,
        )

        store.save(original)

        assertEquals(original, store.load())
    }

    // -----------------------------------------------------------------
    // Test 6 — schemaVersion round-trips with its default
    // -----------------------------------------------------------------

    @Test
    fun `schemaVersion defaults to CURRENT_SCHEMA_VERSION and survives round-trip`() {
        val store = freshStore()
        val original = ExecutionRecoveryState(
            pending = PersistedPendingState.Confirmation(sampleIntent, 5_000L),
            plan = null,
        )

        assertEquals(ExecutionRecoveryState.CURRENT_SCHEMA_VERSION, original.schemaVersion)

        store.save(original)

        assertEquals(ExecutionRecoveryState.CURRENT_SCHEMA_VERSION, store.load()!!.schemaVersion)
    }

    // -----------------------------------------------------------------
    // Test 7 — corrupt storage
    // -----------------------------------------------------------------

    @Test
    fun `corrupt storage recovers to null rather than crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("state", "{not valid json at all").apply()
        val store = PersistentRecoveryStore(prefs, silentLogger)

        assertNull(store.load())
    }

    @Test
    fun `storage holding valid JSON of the wrong shape also recovers to null`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("state", """{"unexpected":"shape"}""").apply()
        val store = PersistentRecoveryStore(prefs, silentLogger)

        assertNull(store.load())
    }

    // -----------------------------------------------------------------
    // Test 8 — overwrite, not merge
    // -----------------------------------------------------------------

    @Test
    fun `saving a second record replaces the first entirely`() {
        val store = freshStore()

        store.save(ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null))
        val second = ExecutionRecoveryState(
            PersistedPendingState.Confirmation(sampleIntent.copy(action = IntentAction.CANCEL), 2_000L),
            null,
        )
        store.save(second)

        assertEquals(second, store.load())
    }

    // -----------------------------------------------------------------
    // Test 9 — clear
    // -----------------------------------------------------------------

    @Test
    fun `clear erases the persisted record so a subsequent load returns null`() {
        val store = freshStore()
        store.save(ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null))

        store.clear()

        assertNull(store.load())
    }

    // -----------------------------------------------------------------
    // Test 10 — fresh store reconstruction over the same SharedPreferences
    // -----------------------------------------------------------------

    @Test
    fun `a saved record survives a fresh PersistentRecoveryStore reconstruction over the same SharedPreferences`() {
        val prefs = FakeSharedPreferences()
        val original = ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null)
        PersistentRecoveryStore(prefs, silentLogger).save(original)

        // A brand-new store instance over the same backing SharedPreferences —
        // the object-level analogue of "a fresh process reads what an earlier
        // process wrote," mirroring this codebase's own established
        // reconstruction-test convention (see ProactiveStateStoreTest,
        // PersistentPreferenceStoreTest).
        val reconstructed = PersistentRecoveryStore(prefs, silentLogger)

        assertEquals(original, reconstructed.load())
    }

    // -----------------------------------------------------------------
    // M5-C — window-D fix: clear() must be durable, not merely applied
    // -----------------------------------------------------------------

    @Test
    fun `after clear, a fresh store reconstruction does not recover the old record`() {
        val prefs = FakeSharedPreferences()
        PersistentRecoveryStore(prefs, silentLogger).save(
            ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null),
        )

        // clear() runs on the SAME instance that wrote it — the scenario
        // window-D actually concerns is a *process* boundary, which the
        // instrumented suite covers genuinely; this proves the object-level
        // contract clear() itself must uphold: once it returns, the write
        // is committed, not merely queued.
        val writer = PersistentRecoveryStore(prefs, silentLogger)
        writer.save(ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null))
        writer.clear()

        val reconstructed = PersistentRecoveryStore(prefs, silentLogger)
        assertNull(
            "A fresh instance over the same backing storage must never see a cleared record",
            reconstructed.load(),
        )
    }

    // -----------------------------------------------------------------
    // M5-C — OperationCheckpoint: serialization, defaults, corruption
    // -----------------------------------------------------------------

    private val taskCheckpoint = OperationCheckpoint(
        operationType = OperationType.CREATE_TASK,
        operationId = 7,
        title = "Submit report",
        requestedAtMillis = 1_000L,
    )

    private val reminderCheckpoint = OperationCheckpoint(
        operationType = OperationType.CREATE_REMINDER,
        operationId = 42,
        title = "Call the bank",
        requestedAtMillis = 2_000L,
    )

    @Test
    fun `a store with no checkpoint saved loads as null`() {
        val store = freshStore()

        assertNull(store.loadCheckpoint())
    }

    @Test
    fun `a create_task checkpoint survives a save-then-load round-trip exactly, preserving its exact id`() {
        val store = freshStore()

        store.saveCheckpoint(taskCheckpoint)

        assertEquals(taskCheckpoint, store.loadCheckpoint())
        assertEquals("The exact pre-generated id must never change", 7, store.loadCheckpoint()!!.operationId)
    }

    @Test
    fun `a create_reminder checkpoint survives a save-then-load round-trip exactly, preserving its exact id`() {
        val store = freshStore()

        store.saveCheckpoint(reminderCheckpoint)

        assertEquals(reminderCheckpoint, store.loadCheckpoint())
        assertEquals("The exact pre-generated id must never change", 42, store.loadCheckpoint()!!.operationId)
    }

    @Test
    fun `schemaVersion defaults to CURRENT_CHECKPOINT_SCHEMA_VERSION and survives round-trip`() {
        val store = freshStore()

        assertEquals(
            OperationCheckpoint.CURRENT_CHECKPOINT_SCHEMA_VERSION,
            taskCheckpoint.schemaVersion,
        )
        store.saveCheckpoint(taskCheckpoint)
        assertEquals(
            OperationCheckpoint.CURRENT_CHECKPOINT_SCHEMA_VERSION,
            store.loadCheckpoint()!!.schemaVersion,
        )
    }

    @Test
    fun `corrupt checkpoint storage recovers to null rather than crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("checkpoint", "{not valid json at all").apply()
        val store = PersistentRecoveryStore(prefs, silentLogger)

        assertNull(store.loadCheckpoint())
    }

    @Test
    fun `checkpoint storage holding valid JSON of the wrong shape also recovers to null`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("checkpoint", """{"unexpected":"shape"}""").apply()
        val store = PersistentRecoveryStore(prefs, silentLogger)

        assertNull(store.loadCheckpoint())
    }

    @Test
    fun `clearing the checkpoint after confirmed success removes it`() {
        val store = freshStore()
        store.saveCheckpoint(taskCheckpoint)

        store.clearCheckpoint()

        assertNull(store.loadCheckpoint())
    }

    @Test
    fun `a fresh store reconstruction detects an uncleared checkpoint left by an earlier instance`() {
        val prefs = FakeSharedPreferences()
        PersistentRecoveryStore(prefs, silentLogger).saveCheckpoint(taskCheckpoint)

        // The object-level analogue of "a fresh process finds what an
        // earlier process left mid-flight" — the genuine process-death
        // version of this exact scenario is the instrumented suite's job.
        val reconstructed = PersistentRecoveryStore(prefs, silentLogger)

        assertEquals(taskCheckpoint, reconstructed.loadCheckpoint())
    }

    @Test
    fun `a fresh store reconstruction after a durable clear finds no checkpoint`() {
        val prefs = FakeSharedPreferences()
        val writer = PersistentRecoveryStore(prefs, silentLogger)
        writer.saveCheckpoint(taskCheckpoint)
        writer.clearCheckpoint()

        val reconstructed = PersistentRecoveryStore(prefs, silentLogger)

        assertNull(
            "A durably cleared checkpoint must never resurface for a fresh instance",
            reconstructed.loadCheckpoint(),
        )
    }

    @Test
    fun `saving a second checkpoint replaces the first entirely`() {
        val store = freshStore()

        store.saveCheckpoint(taskCheckpoint)
        store.saveCheckpoint(reminderCheckpoint)

        assertEquals(reminderCheckpoint, store.loadCheckpoint())
    }

    @Test
    fun `the checkpoint and the pending-state record are independent — clearing one never touches the other`() {
        val store = freshStore()
        val recoveryState = ExecutionRecoveryState(PersistedPendingState.Confirmation(sampleIntent, 1_000L), null)

        store.save(recoveryState)
        store.saveCheckpoint(taskCheckpoint)
        store.clearCheckpoint()

        assertNull("Clearing the checkpoint must not clear the pending-state record", store.loadCheckpoint())
        assertEquals("The pending-state record must survive a checkpoint clear untouched", recoveryState, store.load())

        store.clear()
        store.saveCheckpoint(reminderCheckpoint)
        store.clear()

        assertEquals("Clearing the pending-state record must not clear an outstanding checkpoint", reminderCheckpoint, store.loadCheckpoint())
        assertNull(store.load())
    }

    // -----------------------------------------------------------------
    // M5-E — OperationCheckpoint: CREATE_EVENT
    //
    // No correlation id, no reconciliation — see OperationCheckpoint's own
    // doc for why: CalendarContract.ExtendedProperties writes are a
    // platform-enforced, sync-adapter-only operation, confirmed on a real
    // device. create_event's checkpoint behaves exactly like
    // CREATE_TASK/CREATE_REMINDER's own: detect, notify once, clear, never
    // reconcile.
    // -----------------------------------------------------------------

    private val eventCheckpoint = OperationCheckpoint(
        operationType = OperationType.CREATE_EVENT,
        operationId = OperationCheckpoint.UNUSED_OPERATION_ID,
        title = "Team sync",
        requestedAtMillis = 3_000L,
    )

    @Test
    fun `a create_event checkpoint survives a save-then-load round-trip exactly, using the documented sentinel id`() {
        val store = freshStore()

        store.saveCheckpoint(eventCheckpoint)

        assertEquals(eventCheckpoint, store.loadCheckpoint())
        assertEquals(
            "create_event has no natural id; operationId must stay the documented sentinel",
            OperationCheckpoint.UNUSED_OPERATION_ID,
            store.loadCheckpoint()!!.operationId,
        )
    }

    @Test
    fun `clearing a create_event checkpoint after confirmed success removes it`() {
        val store = freshStore()
        store.saveCheckpoint(eventCheckpoint)

        store.clearCheckpoint()

        assertNull(store.loadCheckpoint())
    }

    @Test
    fun `a fresh store reconstruction detects an uncleared create_event checkpoint`() {
        val prefs = FakeSharedPreferences()
        PersistentRecoveryStore(prefs, silentLogger).saveCheckpoint(eventCheckpoint)

        val reconstructed = PersistentRecoveryStore(prefs, silentLogger)

        assertEquals(eventCheckpoint, reconstructed.loadCheckpoint())
    }

    @Test
    fun `saving a create_event checkpoint after a task checkpoint replaces it entirely`() {
        val store = freshStore()

        store.saveCheckpoint(taskCheckpoint)
        store.saveCheckpoint(eventCheckpoint)

        val loaded = store.loadCheckpoint()
        assertEquals(eventCheckpoint, loaded)
        assertEquals(OperationType.CREATE_EVENT, loaded?.operationType)
    }

    @Test
    fun `a checkpoint persisted before M5-E's CREATE_EVENT case existed still decodes correctly`() {
        // The exact on-disk shape a pre-M5-E checkpoint has — proving the
        // new enum case is purely additive and does not disturb decoding of
        // a record for an operation type that already existed.
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(
            "checkpoint",
            """{"operationType":"CREATE_TASK","operationId":7,"title":"Submit report","requestedAtMillis":1000}""",
        ).apply()
        val store = PersistentRecoveryStore(prefs, silentLogger)

        assertEquals(taskCheckpoint, store.loadCheckpoint())
    }

    /**
     * Minimal in-memory fake of [SharedPreferences] — mirrors every other
     * store test's own fake exactly; only what [PersistentRecoveryStore]
     * actually calls needs to behave correctly.
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
