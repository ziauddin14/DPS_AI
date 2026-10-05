package com.softwaremine.dps.data.android.secretary

import android.content.SharedPreferences
import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.automation.AutomationEngine
import com.softwaremine.dps.domain.automation.AutomationOutcome
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.secretary.PendingAutomationAction
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of [AutomationVerifier] (M9) — mirrors [ExecutionVerifierTest]'s
 * own established shape and hand-written-fake convention.
 */
class AutomationVerifierTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val immediateDispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val inference = Dispatchers.Unconfined
    }

    /**
     * Counts every call rather than throwing from the ones the verifier
     * must not make: [AutomationVerifier] wraps its engine call in a
     * catch-all, so a thrown "not used" would be swallowed into
     * `ObservationFailed` and prove nothing.
     */
    private open class FakeAutomationEngine(private val outcome: VerificationOutcome) : AutomationEngine {
        var openCalls = 0
            private set
        var findCalls = 0
            private set
        var tapCalls = 0
            private set
        val observed = mutableListOf<ElementDescriptor>()
        val observeCalls get() = observed.size

        override suspend fun openApp(packageName: String): AutomationOutcome.OpenApp {
            openCalls++
            return AutomationOutcome.OpenApp.Opened
        }

        override suspend fun findElement(descriptor: ElementDescriptor): AutomationOutcome.Find {
            findCalls++
            return AutomationOutcome.Find.Found(descriptor)
        }

        override suspend fun tap(descriptor: ElementDescriptor): AutomationOutcome.Action {
            tapCalls++
            return AutomationOutcome.Action.Performed
        }

        override suspend fun observeAndVerify(descriptor: ElementDescriptor, expectedText: String): VerificationOutcome {
            observed += descriptor
            return outcomeFor(expectedText)
        }

        protected open fun outcomeFor(expectedText: String): VerificationOutcome = outcome
    }

    /** An app whose button currently shows [currentText] — verification is whatever a live read of it says. */
    private class LiveAppEngine(var currentText: String) : FakeAutomationEngine(VerificationOutcome.NotFound) {
        override fun outcomeFor(expectedText: String): VerificationOutcome =
            if (currentText == expectedText) {
                VerificationOutcome.Verified
            } else {
                VerificationOutcome.Mismatch(mapOf("text" to expectedText), mapOf("text" to currentText))
            }
    }

    /** Mirrors every other store test's own minimal fake exactly. */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
            override fun putInt(key: String?, value: Int) = apply {}
            override fun putLong(key: String?, value: Long) = apply {}
            override fun putFloat(key: String?, value: Float) = apply {}
            override fun putBoolean(key: String?, value: Boolean) = apply {}
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVE_MARKER }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                pending.forEach { (key, value) -> if (value === REMOVE_MARKER) values.remove(key) else values[key] = value }
                pending.clear()
                return true
            }
            override fun apply() {
                commit()
            }
        }

        private companion object {
            val REMOVE_MARKER = Any()
        }
    }

    private val pending = PendingAutomationAction(
        targetApp = "com.softwaremine.dps.automationtarget",
        resourceId = "app:id/button",
        expectedText = "Tapped",
        requestedAtMillis = 1_000L,
    )

    private val automationIntent = DpsIntent(IntentType.AUTOMATION)
    private val unrelatedIntent = DpsIntent(IntentType.TASK)
    private val success = ToolResult.Success("Tapped it.")

    private fun verifier(engine: AutomationEngine?, store: PersistentRecoveryStore) =
        AutomationVerifier(engine, store, immediateDispatchers, silentLogger)

    @Test
    fun `verify returns null for a non-automation intent, never a fifth outcome`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val v = verifier(FakeAutomationEngine(VerificationOutcome.Verified), store)

        assertNull(v.verify(unrelatedIntent, success))
        // Never attempted — the record must not have been touched.
        assertEquals(pending, store.loadAutomation())
    }

    @Test
    fun `verify returns null when no pending automation action is on disk`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        val v = verifier(FakeAutomationEngine(VerificationOutcome.Verified), store)

        assertNull(v.verify(automationIntent, success))
    }

    @Test
    fun `verify resolves Verified and clears the pending record`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val engine = FakeAutomationEngine(VerificationOutcome.Verified)
        val v = verifier(engine, store)

        val outcome = v.verify(automationIntent, success)

        assertEquals(VerificationOutcome.Verified, outcome)
        assertEquals(1, engine.observeCalls)
        assertNull("Resolved — the record must be cleared", store.loadAutomation())
    }

    @Test
    fun `verify resolves Mismatch and still clears the pending record`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val mismatch = VerificationOutcome.Mismatch(mapOf("text" to "Tapped"), mapOf("text" to "Tap me"))
        val v = verifier(FakeAutomationEngine(mismatch), store)

        val outcome = v.verify(automationIntent, success)

        assertEquals(mismatch, outcome)
        assertNull("A mismatch is still a resolution — the record must clear", store.loadAutomation())
    }

    @Test
    fun `verify resolves NotFound and still clears the pending record`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val v = verifier(FakeAutomationEngine(VerificationOutcome.NotFound), store)

        assertEquals(VerificationOutcome.NotFound, v.verify(automationIntent, success))
        assertNull(store.loadAutomation())
    }

    @Test
    fun `a null engine reports ObservationFailed rather than crashing, and still clears the record`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val v = verifier(null, store)

        val outcome = v.verify(automationIntent, success)

        assertTrue(outcome is VerificationOutcome.ObservationFailed)
        assertNull(store.loadAutomation())
    }

    @Test
    fun `resolvePendingAutomationIfAny mirrors verify - resolves and clears a leftover record`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val engine = FakeAutomationEngine(VerificationOutcome.Verified)
        val v = verifier(engine, store)

        val outcome = v.resolvePendingAutomationIfAny()

        assertEquals(VerificationOutcome.Verified, outcome)
        assertEquals(1, engine.observeCalls)
        assertNull(store.loadAutomation())
    }

    @Test
    fun `resolvePendingAutomationIfAny returns null when nothing is pending - never re-taps`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        val engine = FakeAutomationEngine(VerificationOutcome.Verified)
        val v = verifier(engine, store)

        assertNull(v.resolvePendingAutomationIfAny())
        assertEquals("No pending record means observeAndVerify must never be called", 0, engine.observeCalls)
    }

    @Test
    fun `verification reports the app's state after the action, looked up by the persisted resource id`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        val app = LiveAppEngine(currentText = "Tap me")
        val v = verifier(app, store)

        // The tool's own sequence: checkpoint written, then the tap lands.
        store.saveAutomation(pending)
        app.currentText = "Tapped"

        assertEquals(VerificationOutcome.Verified, v.verify(automationIntent, success))
        // The resource id is what lets the engine ask the app directly
        // instead of walking a cached tree — it must arrive intact.
        assertEquals(listOf(ElementDescriptor(resourceId = "app:id/button")), app.observed)
    }

    @Test
    fun `a tap that changed nothing is still a mismatch - the fix does not turn failures into passes`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val app = LiveAppEngine(currentText = "Tap me")

        val outcome = verifier(app, store).verify(automationIntent, success)

        assertEquals(
            VerificationOutcome.Mismatch(mapOf("text" to "Tapped"), mapOf("text" to "Tap me")),
            outcome,
        )
    }

    @Test
    fun `verify observes exactly once and never taps, opens or searches - whatever it finds`() = runTest {
        val outcomes = listOf(
            VerificationOutcome.Verified,
            VerificationOutcome.Mismatch(mapOf("text" to "Tapped"), mapOf("text" to "Tap me")),
            VerificationOutcome.NotFound,
            VerificationOutcome.ObservationFailed("unreadable"),
        )

        for (outcome in outcomes) {
            val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
            store.saveAutomation(pending)
            val engine = FakeAutomationEngine(outcome)

            assertEquals(outcome, verifier(engine, store).verify(automationIntent, success))

            assertEquals("One observation, no retry, for $outcome", 1, engine.observeCalls)
            assertEquals("A non-Verified outcome must never trigger another tap ($outcome)", 0, engine.tapCalls)
            assertEquals(0, engine.openCalls)
            assertEquals(0, engine.findCalls)
        }
    }

    @Test
    fun `recovering a leftover record observes exactly once and never taps`() = runTest {
        val store = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)
        store.saveAutomation(pending)
        val app = LiveAppEngine(currentText = "Tapped")

        assertEquals(VerificationOutcome.Verified, verifier(app, store).resolvePendingAutomationIfAny())

        assertEquals(1, app.observeCalls)
        assertEquals("Recovery re-observes; it must never re-tap", 0, app.tapCalls)
        assertEquals(0, app.openCalls)
    }
}
