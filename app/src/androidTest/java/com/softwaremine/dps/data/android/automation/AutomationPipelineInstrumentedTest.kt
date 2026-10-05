package com.softwaremine.dps.data.android.automation

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityEvent
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.junit.AfterClass
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
 * ## Why this class enables the service itself, and disables it again
 * An instrumented run starts and ends by killing the app's process. A
 * service that was bound at that moment is not rebound afterwards: the
 * system records it as crashed, and keeps skipping it for as long as it
 * stays in that state, whatever the enabled-services setting says. So the
 * service cannot simply be enabled once, by hand, before running this
 * class.
 *
 * Instead [enableServiceAndResetTarget] enables it from inside the run,
 * through the instrumentation's shell — exactly what
 * `adb shell settings put secure ...` does; the app itself still has no
 * way to enable its own service — and [disableService] turns it off
 * again before the process is killed, so the run leaves nothing marked
 * crashed behind it.
 *
 * ## If the service never connects
 * It was already marked crashed before this run began — typically it was
 * enabled when an earlier instrumented run killed the process. Check
 * `adb shell dumpsys accessibility` for it under "Crashed services";
 * `adb shell am force-stop com.softwaremine.dps` clears the mark (a real
 * force-stop, unlike instrumentation's own, resets the service's state).
 * Open the app once afterwards: on the MediaTek test phone a real
 * force-stop also makes the system skip the app's manifest receivers
 * until its Activity has been launched again, which fails the reminder
 * tests in the same run for a reason that has nothing to do with them.
 *
 * ## Why `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`
 * A default `UiAutomation` connection suspends every other accessibility
 * service for as long as it is connected — the service under test would
 * never be bound.
 *
 * ## What this class cannot catch
 * The stale read that `ElementMatcher.findFresh` exists to avoid. In this
 * process the `UiAutomation` connection receives every accessibility
 * event immediately and feeds the same node cache the service reads, so
 * the cache is already current by the time verification runs — measured:
 * with the fix reverted, the tap test here still passed 6 of 6, while the
 * same build misreported 2 of 5 runs from a normally launched app. That
 * race is pinned by `ElementMatcherTest` instead; this class checks that
 * the real pipeline works end to end against a real device.
 */
@RunWith(AndroidJUnit4::class)
class AutomationPipelineInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val logger = AndroidDpsLogger()
    private val intentLauncher = IntentLauncher(context, logger)
    private val engine = AndroidAutomationEngine(context, intentLauncher, logger)
    private val permissionManager = AndroidPermissionManager(context, logger)

    private val buttonDescriptor = ElementDescriptor(
        resourceId = "$TARGET_PACKAGE:id/automation_target_button",
    )

    private fun shell(command: String) = uiAutomation.shell(command)

    /**
     * Leaves the target app stopped — [AndroidAutomationEngine.openApp] is
     * the only thing that launches it, as in real use — and the service
     * enabled and connected.
     *
     * Enablement is re-applied if it does not hold: right after an APK
     * install the system clears a just-updated package's service again
     * about a second later. Already connected (a later test in the same
     * run) means there is nothing to do.
     */
    @Before
    fun enableServiceAndResetTarget() = runBlocking {
        shell("am force-stop $TARGET_PACKAGE")

        repeat(ENABLE_ATTEMPTS) {
            if (DpsAutomationService.instance != null) return@repeat
            shell("settings put secure accessibility_enabled 0")
            shell("settings put secure enabled_accessibility_services $SERVICE_COMPONENT")
            shell("settings put secure accessibility_enabled 1")
            withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MILLIS) {
                while (DpsAutomationService.instance == null) delay(100)
            }
        }
        assertNotNull("The accessibility service never connected", DpsAutomationService.instance)
    }

    @Test
    fun accessibilityPermissionIsGenuinelyGranted() {
        assertEquals(
            "The enabled service must be reported as granted",
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
    fun tapsTheRealButtonExactlyOnceAndVerifiesTheRealResultingText(): Unit = runBlocking {
        // Counted from the target app's own click events, independently of
        // anything the engine reports about itself.
        val clicks = AtomicInteger()
        uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName?.toString() == TARGET_PACKAGE) {
                clicks.incrementAndGet()
            }
        }
        try {
            assertEquals(AutomationOutcome.OpenApp.Opened, engine.openApp(TARGET_PACKAGE))
            assertTrue(engine.findElement(buttonDescriptor) is AutomationOutcome.Find.Found)

            assertEquals(AutomationOutcome.Action.Performed, engine.tap(buttonDescriptor))

            // Straight after the tap, with no wait: the read must already
            // reflect the app's new state.
            assertEquals(
                "The real test app's button must now genuinely read '$EXPECTED_TEXT'",
                VerificationOutcome.Verified,
                engine.observeAndVerify(buttonDescriptor, EXPECTED_TEXT),
            )

            // Click events arrive asynchronously: wait for the first, then
            // leave room for a second one that must never come.
            withTimeout(CLICK_EVENT_TIMEOUT_MILLIS) { while (clicks.get() == 0) delay(50) }
            delay(DUPLICATE_CLICK_WINDOW_MILLIS)
            assertEquals("One tap() must be exactly one click in the target app", 1, clicks.get())
        } finally {
            uiAutomation.setOnAccessibilityEventListener(null)
        }
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

    companion object {
        /** Unbinds the service cleanly so the process kill that ends this run cannot leave it marked crashed. */
        @JvmStatic
        @AfterClass
        fun disableService(): Unit = runBlocking {
            val uiAutomation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            uiAutomation.shell("settings delete secure enabled_accessibility_services")
            uiAutomation.shell("settings put secure accessibility_enabled 0")
            withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MILLIS) {
                while (DpsAutomationService.instance != null) delay(100)
            }
        }

        /** Runs [command] as the shell user and waits for it to finish. */
        private fun UiAutomation.shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { it.readBytes() }
        }

        private const val TARGET_PACKAGE = "com.softwaremine.dps.automationtarget"
        private const val EXPECTED_TEXT = "Tapped"
        private const val SERVICE_COMPONENT =
            "com.softwaremine.dps/com.softwaremine.dps.data.android.automation.DpsAutomationService"
        private const val ENABLE_ATTEMPTS = 3
        private const val SERVICE_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val CLICK_EVENT_TIMEOUT_MILLIS = 5_000L
        private const val DUPLICATE_CLICK_WINDOW_MILLIS = 1_000L
    }
}
