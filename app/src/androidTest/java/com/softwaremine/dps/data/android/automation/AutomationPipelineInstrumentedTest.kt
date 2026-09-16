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
     * every test — see its own doc — and ensures a real, live DPS
     * foreground presence exists for the system to bind the accessibility
     * service to.
     *
     * ## Why MainActivity is launched explicitly, every test
     * A bare `am instrument` process, with no Activity of its own, was
     * found (real-device evidence) to never receive the accessibility
     * service bind at all — the system only bound it once this exact
     * class's own manual `adb shell am start` real-device check launched a
     * genuine foreground Activity. This mirrors that finding rather than
     * assuming instrumentation alone is enough.
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
