package com.softwaremine.dps.data.android.tool

import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
import com.softwaremine.dps.domain.automation.AutomationEngine
import com.softwaremine.dps.domain.automation.AutomationOutcome
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationToolTest {

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Minimal in-memory fake of [SharedPreferences] — mirrors every other store test's own fake. */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
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

    private class FakeAutomationEngine(
        private val openApp: AutomationOutcome.OpenApp = AutomationOutcome.OpenApp.Opened,
        private val findElement: AutomationOutcome.Find = AutomationOutcome.Find.Found(ElementDescriptor(resourceId = "id")),
        private val tap: AutomationOutcome.Action = AutomationOutcome.Action.Performed,
    ) : AutomationEngine {
        var tapCalls = 0
            private set

        override suspend fun openApp(packageName: String) = openApp
        override suspend fun findElement(descriptor: ElementDescriptor) = findElement
        override suspend fun tap(descriptor: ElementDescriptor): AutomationOutcome.Action {
            tapCalls++
            return tap
        }
        override suspend fun observeAndVerify(descriptor: ElementDescriptor, expectedText: String): VerificationOutcome =
            VerificationOutcome.Verified
    }

    private fun tool(engine: AutomationEngine, store: PersistentRecoveryStore = PersistentRecoveryStore(FakeSharedPreferences(), silentLogger)) =
        AndroidAutomationTool(engine, store) to store

    @Test
    fun `a missing app argument fails honestly without calling the engine`() = runBlocking {
        val engine = FakeAutomationEngine()
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", emptyMap()))

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, engine.tapCalls)
    }

    @Test
    fun `an unknown app name fails honestly, never guessing a package`() = runBlocking {
        val engine = FakeAutomationEngine()
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "some random app")))

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, engine.tapCalls)
    }

    @Test
    fun `an unsupported operation is reported as Unsupported`() = runBlocking {
        val (tool, _) = tool(FakeAutomationEngine())

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "not_a_real_operation", emptyMap()))

        assertTrue(result is ToolResult.Unsupported)
    }

    @Test
    fun `app not installed maps to Unsupported`() = runBlocking {
        val engine = FakeAutomationEngine(openApp = AutomationOutcome.OpenApp.NotInstalled)
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Unsupported)
    }

    @Test
    fun `app open failure is retryable`() = runBlocking {
        val engine = FakeAutomationEngine(openApp = AutomationOutcome.OpenApp.OpenFailed)
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).retryable)
    }

    @Test
    fun `element not found fails without ever writing a pending automation action or tapping`() = runBlocking {
        val engine = FakeAutomationEngine(findElement = AutomationOutcome.Find.NotFound)
        val (tool, store) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, engine.tapCalls)
        assertNull("Nothing irreversible was attempted, so no checkpoint should exist", store.loadAutomation())
    }

    @Test
    fun `ambiguous element fails without tapping`() = runBlocking {
        val engine = FakeAutomationEngine(findElement = AutomationOutcome.Find.Ambiguous(2))
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, engine.tapCalls)
    }

    @Test
    fun `a rejected tap clears the pending automation action - a known, synchronous outcome`() = runBlocking {
        val engine = FakeAutomationEngine(tap = AutomationOutcome.Action.Rejected)
        val (tool, store) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Failure)
        assertNull("A known-rejected tap must not leave a checkpoint behind", store.loadAutomation())
    }

    @Test
    fun `accessibility becoming unavailable mid-tap leaves the checkpoint standing - the outcome is genuinely unknown`() = runBlocking {
        val engine = FakeAutomationEngine(tap = AutomationOutcome.Action.AccessibilityUnavailable)
        val (tool, store) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue(result is ToolResult.Failure)
        assertNotNull(
            "The checkpoint must survive so recovery — never a blind re-tap — can resolve it later",
            store.loadAutomation(),
        )
    }

    @Test
    fun `a successful tap writes the checkpoint before tapping and returns a thin Success`() = runBlocking {
        val engine = FakeAutomationEngine()
        val (tool, store) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app")))

        assertTrue("Expected Success, got $result", result is ToolResult.Success)
        assertEquals(1, engine.tapCalls)
        // Deliberately still present: clearing it is AutomationVerifier's
        // job, once verification actually resolves — not this tool's.
        assertNotNull(store.loadAutomation())
        assertEquals("com.softwaremine.dps.automationtarget", (result as ToolResult.Success).data["target_app"])
    }

    @Test
    fun `ACTION EXECUTED is never confused with ACTION VERIFIED - Success alone proves nothing about the app's real state`() = runBlocking {
        // The tap being "Performed" and the tool reporting Success says only
        // that the platform accepted the click — this test documents that
        // this class itself never claims more than that in its own summary.
        val engine = FakeAutomationEngine()
        val (tool, _) = tool(engine)

        val result = tool.execute(ToolCall(ToolId.AUTOMATION, "perform_interaction", mapOf("app" to "test app"))) as ToolResult.Success

        assertTrue(
            "The summary must not claim the app's own state changed, only that the tap was dispatched",
            !result.summary.contains("verified", ignoreCase = true),
        )
    }
}
