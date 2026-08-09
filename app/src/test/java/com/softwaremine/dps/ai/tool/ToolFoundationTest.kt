package com.softwaremine.dps.ai.tool

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionKind
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the Android tool foundation (Day 05 Phase 1).
 *
 * ## Why these run on the JVM
 * The registry, the executor and every domain type are pure Kotlin by design
 * (ADR-005), so the entire control flow of the tool layer — resolution,
 * permission gating, timeouts, failure containment — is testable without a
 * device. That matters concretely here: the reference handset is the only
 * hardware available and emulators are excluded by policy (ADR-009).
 *
 * Only `AndroidPermissionManager` genuinely needs Android, and it is verified
 * separately by an instrumented test.
 */
class ToolFoundationTest {

    // -----------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val immediateDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val inference: CoroutineDispatcher = Dispatchers.Unconfined
    }

    /** Permission manager whose answers are dictated by the test. */
    private class FakePermissions(
        private val states: Map<DpsPermission, PermissionState> = emptyMap(),
        private val default: PermissionState = PermissionState.GRANTED,
    ) : PermissionManager {
        override fun state(permission: DpsPermission) = states[permission] ?: default
        override fun states(permissions: Set<DpsPermission>) = permissions.associateWith(::state)
        override fun missing(permissions: Set<DpsPermission>) =
            permissions.filterNot { state(it).isUsable }.toSet()
        override suspend fun request(permissions: Set<DpsPermission>) = states(permissions)
    }

    /** Tool whose behaviour the test controls. */
    private class StubTool(
        override val id: ToolId,
        override val operations: Set<String> = setOf("run"),
        override val requiredPermissions: Set<DpsPermission> = emptySet(),
        override val minApiLevel: Int? = null,
        private val behaviour: suspend () -> ToolResult = {
            ToolResult.Success(summary = "done")
        },
    ) : AndroidTool {
        override suspend fun execute(call: ToolCall): ToolResult = behaviour()
    }

    private fun registry(vararg tools: AndroidTool) =
        DefaultToolRegistry(silentLogger).apply { tools.forEach(::register) }

    private fun executor(
        registry: DefaultToolRegistry,
        permissions: PermissionManager = FakePermissions(),
        apiLevel: Int = 35,
    ) = DefaultToolExecutor(
        registry = registry,
        permissionManager = permissions,
        dispatchers = immediateDispatchers,
        logger = silentLogger,
        apiLevel = apiLevel,
    )

    // -----------------------------------------------------------------
    // Tool Registry
    // -----------------------------------------------------------------

    @Test
    fun `registry resolves a registered tool by id`() {
        val tool = StubTool(ToolId.CALENDAR)
        val registry = registry(tool)

        assertEquals(tool, registry.find(ToolId.CALENDAR))
        assertTrue(ToolId.CALENDAR in registry)
    }

    @Test
    fun `registry resolves by wire name as an LLM would produce it`() {
        val registry = registry(StubTool(ToolId.CALENDAR))

        assertNotNull(registry.findByName("calendar"))
        // Models are not reliably careful about case or whitespace.
        assertNotNull(registry.findByName("  CALENDAR  "))
    }

    @Test
    fun `registry returns null for an unknown tool rather than throwing`() {
        val registry = registry(StubTool(ToolId.CALENDAR))

        assertNull(registry.find(ToolId.GMAIL))
        assertNull(registry.findByName("does_not_exist"))
    }

    @Test
    fun `registry preserves registration order for stable prompt output`() {
        val registry = registry(
            StubTool(ToolId.CALENDAR),
            StubTool(ToolId.CONTACTS),
            StubTool(ToolId.PHONE),
        )

        assertEquals(
            listOf(ToolId.CALENDAR, ToolId.CONTACTS, ToolId.PHONE),
            registry.all().map { it.id },
        )
    }

    /** A shadowed tool is near-undiagnosable, so duplicates must fail loudly. */
    @Test(expected = IllegalStateException::class)
    fun `registry rejects duplicate registration`() {
        registry(StubTool(ToolId.CALENDAR), StubTool(ToolId.CALENDAR))
    }

    // -----------------------------------------------------------------
    // Executor — declining cleanly
    // -----------------------------------------------------------------

    @Test
    fun `unknown tool returns Unsupported not an exception`() = runTest {
        val result = executor(registry()).execute(
            ToolCall(ToolId.GMAIL, "send_email"),
        )

        assertTrue("Expected Unsupported, got $result", result is ToolResult.Unsupported)
        assertTrue((result as ToolResult.Unsupported).reason.contains("gmail"))
    }

    @Test
    fun `unsupported operation returns Unsupported`() = runTest {
        val registry = registry(StubTool(ToolId.CALENDAR, operations = setOf("create_event")))

        val result = executor(registry).execute(ToolCall(ToolId.CALENDAR, "launch_rocket"))

        assertTrue(result is ToolResult.Unsupported)
        assertTrue((result as ToolResult.Unsupported).reason.contains("launch_rocket"))
    }

    @Test
    fun `tool below required API level reports minApiLevel`() = runTest {
        val registry = registry(StubTool(ToolId.ALARM, minApiLevel = 31))

        val result = executor(registry, apiLevel = 26).execute(ToolCall(ToolId.ALARM, "run"))

        assertTrue(result is ToolResult.Unsupported)
        assertEquals(31, (result as ToolResult.Unsupported).minApiLevel)
    }

    @Test
    fun `tool at or above required API level proceeds`() = runTest {
        val registry = registry(StubTool(ToolId.ALARM, minApiLevel = 31))

        val result = executor(registry, apiLevel = 31).execute(ToolCall(ToolId.ALARM, "run"))

        assertTrue("Expected Success, got $result", result is ToolResult.Success)
    }

    // -----------------------------------------------------------------
    // Executor — permission gate
    // -----------------------------------------------------------------

    @Test
    fun `missing permission returns PermissionRequired and does not run the tool`() = runTest {
        var executed = false
        val tool = StubTool(
            id = ToolId.CALENDAR,
            requiredPermissions = setOf(DpsPermission.WRITE_CALENDAR),
            behaviour = { executed = true; ToolResult.Success("should not happen") },
        )
        val permissions = FakePermissions(
            mapOf(DpsPermission.WRITE_CALENDAR to PermissionState.DENIED),
        )

        val result = executor(registry(tool), permissions).execute(
            ToolCall(ToolId.CALENDAR, "run"),
        )

        assertTrue("Expected PermissionRequired, got $result", result is ToolResult.PermissionRequired)
        assertEquals(
            listOf(DpsPermission.WRITE_CALENDAR),
            (result as ToolResult.PermissionRequired).permissions,
        )
        assertTrue("Tool must not run without permission", !executed)
    }

    /** NOT_REQUIRED means the OS does not gate it — that is usable, not missing. */
    @Test
    fun `permission that is NOT_REQUIRED on this OS does not block execution`() = runTest {
        val tool = StubTool(
            id = ToolId.NOTIFICATION,
            requiredPermissions = setOf(DpsPermission.POST_NOTIFICATIONS),
        )
        val permissions = FakePermissions(
            mapOf(DpsPermission.POST_NOTIFICATIONS to PermissionState.NOT_REQUIRED),
        )

        val result = executor(registry(tool), permissions).execute(
            ToolCall(ToolId.NOTIFICATION, "run"),
        )

        assertTrue("Expected Success, got $result", result is ToolResult.Success)
    }

    @Test
    fun `rationale never leaks the raw android permission string`() = runTest {
        val tool = StubTool(
            id = ToolId.CALENDAR,
            requiredPermissions = setOf(DpsPermission.WRITE_CALENDAR),
        )
        val permissions = FakePermissions(
            mapOf(DpsPermission.WRITE_CALENDAR to PermissionState.PERMANENTLY_DENIED),
        )

        val result = executor(registry(tool), permissions).execute(
            ToolCall(ToolId.CALENDAR, "run"),
        ) as ToolResult.PermissionRequired

        // AI Rules 1 and 2: never expose internal architecture to the user.
        assertTrue(
            "Rationale leaked an internal identifier: ${result.rationale}",
            !result.rationale.contains("android.permission"),
        )
    }

    // -----------------------------------------------------------------
    // Executor — failure containment
    // -----------------------------------------------------------------

    @Test
    fun `a throwing tool is contained as Error and never propagates`() = runTest {
        val tool = StubTool(ToolId.CONTACTS, behaviour = { error("tool exploded") })

        val result = executor(registry(tool)).execute(ToolCall(ToolId.CONTACTS, "run"))

        assertTrue("Expected Error, got $result", result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).cause!!.contains("tool exploded"))
        // The user-facing message must not carry the raw exception text.
        assertTrue(!result.message.contains("exploded"))
    }

    @Test
    fun `a hanging tool is bounded by the timeout`() = runTest {
        val tool = StubTool(
            ToolId.WORK_MANAGER,
            behaviour = { delay(60_000); ToolResult.Success("never reached") },
        )

        val result = executor(registry(tool)).execute(
            call = ToolCall(ToolId.WORK_MANAGER, "run"),
            timeoutMillis = 50,
        )

        assertTrue("Expected Timeout, got $result", result is ToolResult.Timeout)
        assertEquals(50L, (result as ToolResult.Timeout).limitMillis)
    }

    @Test
    fun `a tool returning Failure passes it through unchanged`() = runTest {
        val failure = ToolResult.Failure(reason = "no such contact", retryable = false)
        val tool = StubTool(ToolId.CONTACTS, behaviour = { failure })

        val result = executor(registry(tool)).execute(ToolCall(ToolId.CONTACTS, "run"))

        assertEquals(failure, result)
    }

    // -----------------------------------------------------------------
    // ToolResult serialization
    //
    // These results are fed back to the LLM as observations, so every variant
    // must survive a round trip.
    // -----------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(result: ToolResult): ToolResult {
        val encoded = json.encodeToString(ToolResult.serializer(), result)
        return json.decodeFromString(ToolResult.serializer(), encoded)
    }

    @Test
    fun `every ToolResult variant survives a serialization round trip`() {
        val samples = listOf(
            ToolResult.Success("created", mapOf("id" to "42")),
            ToolResult.Failure("no such contact", retryable = true),
            ToolResult.PermissionRequired(
                listOf(DpsPermission.READ_CALENDAR, DpsPermission.WRITE_CALENDAR),
                "DPS needs your permission to access your calendar.",
            ),
            ToolResult.Cancelled("user stopped"),
            ToolResult.Unsupported("not implemented", minApiLevel = 31),
            ToolResult.Timeout(elapsedMillis = 10_500, limitMillis = 10_000),
            ToolResult.Error("The tool failed unexpectedly.", cause = "IllegalStateException: x"),
        )

        samples.forEach { original ->
            assertEquals(
                "Round trip changed ${original::class.simpleName}",
                original,
                roundTrip(original),
            )
        }
    }

    @Test
    fun `serialized ToolResult carries a stable discriminator for the LLM`() {
        val encoded = json.encodeToString(
            ToolResult.serializer(),
            ToolResult.Success("ok"),
        )

        // The type tag is what lets a model tell outcomes apart; renaming it
        // would silently change the observation format.
        assertTrue("Missing type discriminator in: $encoded", encoded.contains("success"))
    }

    @Test
    fun `isSuccess reflects only Success`() {
        assertTrue(ToolResult.Success("ok").isSuccess)
        assertTrue(!ToolResult.Failure("nope").isSuccess)
        assertTrue(!ToolResult.Timeout(1, 1).isSuccess)
        assertTrue(!ToolResult.Error("boom").isSuccess)
    }

    // -----------------------------------------------------------------
    // Domain permission model
    // -----------------------------------------------------------------

    @Test
    fun `POST_NOTIFICATIONS is absent below API 33 and present at or above`() {
        // Verified against official documentation: introduced in Android 13.
        assertTrue(!DpsPermission.POST_NOTIFICATIONS.existsOn(32))
        assertTrue(DpsPermission.POST_NOTIFICATIONS.existsOn(33))
        assertTrue(DpsPermission.POST_NOTIFICATIONS.existsOn(35))
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM is modelled as special access not runtime`() {
        // It carries normal protection level and is granted via a settings
        // screen; the runtime dialog cannot grant it.
        assertEquals(
            PermissionKind.SPECIAL_ACCESS,
            DpsPermission.SCHEDULE_EXACT_ALARM.kind,
        )
    }

    @Test
    fun `calendar and contacts permissions are runtime`() {
        listOf(
            DpsPermission.READ_CALENDAR,
            DpsPermission.WRITE_CALENDAR,
            DpsPermission.READ_CONTACTS,
            DpsPermission.CALL_PHONE,
        ).forEach {
            assertEquals("$it should be RUNTIME", PermissionKind.RUNTIME, it.kind)
        }
    }

    @Test
    fun `PermissionState classifies usability requestability and settings correctly`() {
        assertTrue(PermissionState.GRANTED.isUsable)
        assertTrue(PermissionState.NOT_REQUIRED.isUsable)
        assertTrue(!PermissionState.DENIED.isUsable)
        assertTrue(!PermissionState.UNKNOWN.isUsable)

        assertTrue(PermissionState.DENIED.isRequestable)
        assertTrue(!PermissionState.PERMANENTLY_DENIED.isRequestable)

        assertTrue(PermissionState.PERMANENTLY_DENIED.needsSettings)
        assertTrue(PermissionState.REQUIRES_SETTINGS.needsSettings)
    }
}
