package com.softwaremine.dps.data.android.permission

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionKind
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the permission and tool foundation (Day 05 Phase 1).
 *
 * ## Purpose
 * `AndroidPermissionManager` is the only part of the foundation that genuinely
 * requires Android â€” it calls `ContextCompat.checkSelfPermission` and
 * `AlarmManager.canScheduleExactAlarms()`. Everything else is verified on the
 * JVM by `ToolFoundationTest`.
 *
 * ## What is deliberately not asserted
 * These tests do not assert that any particular permission is *granted*. Grant
 * state depends on the device, the OS version and what the user has done
 * previously, so asserting it would produce a test that passes or fails for
 * reasons unrelated to the code.
 *
 * What is asserted is that the manager answers **correctly and without
 * throwing** for every permission, and that OS-version rules are applied
 * properly â€” which is the behaviour that can actually regress.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFoundationInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()

    private fun manager(apiLevel: Int = Build.VERSION.SDK_INT) =
        AndroidPermissionManager(context = context, logger = logger, apiLevel = apiLevel)

    // -----------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------

    /**
     * Every domain permission must map to a real Android constant.
     *
     * A missing mapping would throw at the moment a tool first needs the
     * permission â€” which is the worst time to discover it.
     */
    @Test
    fun everyDpsPermissionMapsToAnAndroidConstant() {
        DpsPermission.entries.forEach { permission ->
            val androidName = AndroidPermissionMapping.androidName(permission)
            assertTrue(
                "$permission mapped to a suspicious value: '$androidName'",
                androidName.startsWith("android.permission."),
            )
        }
    }

    @Test
    fun androidNameMappingRoundTrips() {
        DpsPermission.entries.forEach { permission ->
            val androidName = AndroidPermissionMapping.androidName(permission)
            assertEquals(permission, AndroidPermissionMapping.fromAndroidName(androidName))
        }
    }

    // -----------------------------------------------------------------
    // State reporting
    // -----------------------------------------------------------------

    /** The manager must answer for every permission without throwing. */
    @Test
    fun stateIsReportedForEveryPermissionWithoutThrowing() {
        val manager = manager()

        DpsPermission.entries.forEach { permission ->
            val state = manager.state(permission)
            assertNotNull("No state returned for $permission", state)
        }
    }

    /**
     * `POST_NOTIFICATIONS` did not exist before API 33, so on an older device
     * it is NOT_REQUIRED â€” the capability is available.
     *
     * Reporting DENIED here would block notifications on every Android 12
     * device, which the platform in fact permits.
     */
    @Test
    fun postNotificationsIsNotRequiredBelowApi33() {
        val state = manager(apiLevel = 32).state(DpsPermission.POST_NOTIFICATIONS)

        assertEquals(PermissionState.NOT_REQUIRED, state)
        assertTrue("NOT_REQUIRED must count as usable", state.isUsable)
    }

    /** At API 33+ it becomes a real runtime permission with a real state. */
    @Test
    fun postNotificationsIsEvaluatedAtApi33AndAbove() {
        val state = manager(apiLevel = 33).state(DpsPermission.POST_NOTIFICATIONS)

        assertTrue(
            "Expected a runtime state, got $state",
            state != PermissionState.NOT_REQUIRED,
        )
    }

    /**
     * Exact alarms are special access, resolved via
     * `AlarmManager.canScheduleExactAlarms()` rather than `checkSelfPermission`.
     *
     * The value depends on the device, so only the *shape* of the answer is
     * asserted â€” the point is that the special-access path runs and returns a
     * state from the correct API rather than crashing or falling through to the
     * runtime path.
     */
    @Test
    fun exactAlarmUsesSpecialAccessPath() {
        assertEquals(
            PermissionKind.SPECIAL_ACCESS,
            DpsPermission.SCHEDULE_EXACT_ALARM.kind,
        )

        val state = manager().state(DpsPermission.SCHEDULE_EXACT_ALARM)

        assertTrue(
            "Unexpected state for special access: $state",
            state in setOf(
                PermissionState.GRANTED,
                PermissionState.REQUIRES_SETTINGS,
                PermissionState.NOT_REQUIRED,
                PermissionState.UNKNOWN,
            ),
        )
        // Special access is never obtainable through the runtime dialog, so it
        // must never be reported as simply "ask again".
        assertTrue(
            "Special access must not be reported as requestable",
            state != PermissionState.DENIED,
        )
    }

    /**
     * Permissions not declared in the manifest cannot be granted.
     *
     * Phase 1 deliberately declares no new manifest permissions â€” the Day 02
     * rule is that a permission appears only when the feature needing it
     * exists. So the calendar permissions must report as not usable, and that
     * is the correct answer rather than a defect.
     */
    @Test
    fun undeclaredPermissionsAreReportedAsNotUsable() {
        val manager = manager()

        val calendar = setOf(DpsPermission.READ_CALENDAR, DpsPermission.WRITE_CALENDAR)
        val missing = manager.missing(calendar)

        assertEquals(
            "Undeclared calendar permissions should all be missing",
            calendar,
            missing,
        )
    }

    // -----------------------------------------------------------------
    // Requesting
    // -----------------------------------------------------------------

    /**
     * A request with no attached UI host must terminate, not hang.
     *
     * The tool executor can run from a background context where no Activity
     * exists. Suspending forever waiting for a host that may never attach would
     * wedge the caller; returning current state is honest and terminates.
     */
    @Test
    fun requestWithoutHostReturnsCurrentStateAndDoesNotHang() = runBlocking {
        val manager = manager()

        val result = manager.request(
            setOf(DpsPermission.READ_CALENDAR, DpsPermission.SCHEDULE_EXACT_ALARM),
        )

        assertEquals(2, result.size)
        assertNotNull(result[DpsPermission.READ_CALENDAR])
        assertNotNull(result[DpsPermission.SCHEDULE_EXACT_ALARM])
    }

    @Test
    fun requestWithEmptySetIsANoOp() = runBlocking {
        assertTrue(manager().request(emptySet()).isEmpty())
    }

    @Test
    fun attachingAndDetachingAHostDoesNotThrow() {
        val manager = manager()
        val host = object : com.softwaremine.dps.domain.permission.PermissionRequestHost {
            override suspend fun requestRuntimePermissions(
                androidPermissions: List<String>,
            ): Map<String, Boolean> = androidPermissions.associateWith { false }

            override fun shouldShowRationale(androidPermission: String): Boolean = false
        }

        manager.attachHost(host)
        manager.detachHost()
        manager.detachHost() // must be idempotent
    }

    // -----------------------------------------------------------------
    // Wiring â€” the foundation as assembled in production
    // -----------------------------------------------------------------

    /**
     * Every tool id must be registered exactly once.
     *
     * Asserted against [ToolId] rather than against
     * `AndroidToolCatalog.declaredTools()`. Since Phase B that function returns
     * only the *unimplemented* remainder unless the real implementations are
     * passed in, so using it as the expectation would silently shrink as tools
     * gain behaviour â€” a test that weakens itself precisely as the system grows.
     *
     * The invariant that actually matters is that the registry can resolve
     * every id the LLM might name.
     */
    @Test
    fun containerRegistersEveryToolId() {
        val container = AiContainer(context)

        assertEquals(ToolId.entries.toSet(), container.toolRegistry.registeredIds())
        assertEquals(ToolId.entries.size, container.toolRegistry.all().size)
    }

    /**
     * The full production path, end to end: registry â†’ executor â†’ tool.
     *
     * `WORK_MANAGER` requires no permissions, so it reaches the tool itself and
     * returns the honest Unsupported that Phase 1 defines â€” proving the
     * executor dispatches correctly rather than declining earlier for some
     * unrelated reason.
     */
    @Test
    fun executorDispatchesThroughToADeclaredToolOnDevice() = runBlocking {
        val container = AiContainer(context)

        val result = container.toolExecutor.execute(
            ToolCall(ToolId.WORK_MANAGER, "schedule_work"),
        )

        assertTrue("Expected Unsupported, got $result", result is ToolResult.Unsupported)
        assertTrue(
            "Reason should identify the tool: ${(result as ToolResult.Unsupported).reason}",
            result.reason.contains("work_manager"),
        )
    }

    /** Permission-gated tools decline before reaching the tool. */
    @Test
    fun executorGatesOnPermissionsOnDevice() = runBlocking {
        val container = AiContainer(context)

        val result = container.toolExecutor.execute(
            ToolCall(ToolId.CALENDAR, "create_event"),
        )

        // Calendar permissions are undeclared in Phase 1, so the gate fires
        // before dispatch.
        assertTrue("Expected PermissionRequired, got $result", result is ToolResult.PermissionRequired)
        assertTrue((result as ToolResult.PermissionRequired).permissions.isNotEmpty())
    }

    @Test
    fun unknownToolDeclinesCleanlyOnDevice() = runBlocking {
        val container = AiContainer(context)

        // GMAIL is declared, so ask for an operation it does not implement.
        val result = container.toolExecutor.execute(
            ToolCall(ToolId.GMAIL, "delete_everything"),
        )

        assertTrue("Expected Unsupported, got $result", result is ToolResult.Unsupported)
    }
}
