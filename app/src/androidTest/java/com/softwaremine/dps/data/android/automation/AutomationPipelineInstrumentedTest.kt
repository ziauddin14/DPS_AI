package com.softwaremine.dps.data.android.automation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.core.logging.AndroidDpsLogger
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.data.android.permission.AndroidPermissionManager
import com.softwaremine.dps.domain.automation.AutomationOutcome
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionState
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device proof of the M9 Phase 1 pipeline against the genuine
 * `AccessibilityService` and the real, first-party
 * [com.softwaremine.dps.automationtarget] test app — see
 * `docs/DAY-20-M9-IMPLEMENTATION-PLAN.md`'s own "Real-Device Test Plan".
 *
 * ## Prerequisite (not automatable — a genuine, deliberate M9 property)
 * The accessibility service must already be enabled via the OS's own
 * settings flow (or, for this test run, `adb shell settings put secure
 * enabled_accessibility_services ...`) before this class runs — see
 * `AndroidPermissionManager`'s own `specialAccessState()` doc for why no
 * dialog can grant this.
 *
 * ## Known, disclosed limitation: cannot currently pass via `am instrument`
 * Real-device diagnosis (M9) found that force-stopping the app that owns an
 * enabled `AccessibilityService` — `adb shell am force-stop
 * com.softwaremine.dps`, confirmed via `dumpsys accessibility` — clears
 * both `enabled_accessibility_services` and, if it was the sole enabled
 * service, `accessibility_enabled` itself. This is genuine OS behavior
 * (a force-stop revokes the app's active accessibility binding), not a
 * DPS defect. Standard instrumented-test execution (`connectedAndroidTest`
 * / `am instrument`) force-stops the target app as part of its own,
 * unavoidable setup — before this class's own `@Before` ever runs — so
 * the accessibility service is never enabled by the time any test body
 * executes, regardless of what `resetTargetAppAndEnsureDpsIsForeground`
 * itself does. This was previously misdiagnosed as "`am instrument`
 * processes never receive the bind"; the real cause is this force-stop
 * side effect. The underlying pipeline this class exercises was instead
 * proven correct via a temporary, disclosed diagnostic driven by a normal
 * `adb shell am start` launch (which does not force-stop DPS) — see the
 * M9 completion report for the full, itemized real-device evidence.
 */
@RunWith(AndroidJUnit4::class)
class AutomationPipelineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = AndroidDpsLogger()
    private val intentLauncher = IntentLauncher(context, logger)
    private val engine = AndroidAutomationEngine(context, intentLauncher, logger)
    private val permissionManager = AndroidPermissionManager(context, logger)

    private val buttonDescriptor = ElementDescriptor(
        resourceId = "$TARGET_PACKAGE:id/automation_target_button",
    )

    /**
     * Resets the target app to its own deterministic "Tap me" state before
     * every test — see its own doc — and launches a real, live DPS
     * foreground Activity, mirroring the normal (non-instrumentation)
     * launch path a genuine accessibility bind was proven against.
     *
     * ## Why this still cannot bind — see the class-level doc
     * This launch alone is not sufficient in an instrumented run: the test
     * runner's own force-stop of `com.softwaremine.dps`, which happens
     * before this method ever executes, has already cleared the
     * accessibility service's enabled state for the OS. The `withTimeout`
     * below will time out in a standard `am instrument` run for that
     * reason, not because launching `MainActivity` here is itself wrong.
     */
    @Before
    fun resetTargetAppAndEnsureDpsIsForeground() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am force-stop $TARGET_PACKAGE")
            .close()

        context.startActivity(
            Intent().apply {
                setClassName(context.packageName, "com.softwaremine.dps.ui.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        withTimeout(SERVICE_CONNECT_TIMEOUT_MILLIS) {
            while (DpsAutomationService.instance == null) {
                delay(200)
            }
        }
    }

    @Test
    fun accessibilityPermissionIsGenuinelyGranted() {
        assertEquals(
            "Enable the DPS accessibility service before running this suite",
            PermissionState.GRANTED,
            permissionManager.state(DpsPermission.AUTOMATION_ACCESSIBILITY),
        )
    }

    @Test
    fun theServiceIsGenuinelyConnected() {
        assertNotNull(
            "DpsAutomationService.instance must be set once genuinely enabled and connected",
            DpsAutomationService.instance,
        )
    }

    @Test
    fun opensTheRealTestAppAndFindsTheRealButton(): Unit = runBlocking {
        val opened = engine.openApp(TARGET_PACKAGE)
        assertEquals(AutomationOutcome.OpenApp.Opened, opened)

        val found = engine.findElement(buttonDescriptor)
        assertTrue("Expected Found, got $found", found is AutomationOutcome.Find.Found)
    }

    @Test
    fun anUnknownResourceIdIsGenuinelyNotFound(): Unit = runBlocking {
        engine.openApp(TARGET_PACKAGE)

        val found = engine.findElement(ElementDescriptor(resourceId = "$TARGET_PACKAGE:id/does_not_exist"))

        assertEquals(AutomationOutcome.Find.NotFound, found)
    }

    @Test
    fun tapsTheRealButtonAndVerifiesTheRealResultingText(): Unit = runBlocking {
        engine.openApp(TARGET_PACKAGE)
        engine.findElement(buttonDescriptor)

        val tapped = engine.tap(buttonDescriptor)
        assertEquals(AutomationOutcome.Action.Performed, tapped)

        val outcome = engine.observeAndVerify(buttonDescriptor, EXPECTED_TEXT)

        assertEquals(
            "The real test app's button must now genuinely read '$EXPECTED_TEXT'",
            VerificationOutcome.Verified,
            outcome,
        )
    }

    @Test
    fun observingBeforeTapReportsTheRealPreTapText(): Unit = runBlocking {
        engine.openApp(TARGET_PACKAGE)
        engine.findElement(buttonDescriptor)

        // Comparing against the post-tap text before ever tapping proves
        // this reads real, live state rather than an assumed value.
        val outcome = engine.observeAndVerify(buttonDescriptor, EXPECTED_TEXT)

        assertTrue("Expected Mismatch before any tap occurred, got $outcome", outcome is VerificationOutcome.Mismatch)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.softwaremine.dps.automationtarget"
        const val EXPECTED_TEXT = "Tapped"
        const val SERVICE_CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
