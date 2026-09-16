package com.softwaremine.dps.data.android.automation

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.intent.IntentLauncher
import com.softwaremine.dps.domain.automation.AutomationEngine
import com.softwaremine.dps.domain.automation.AutomationNode
import com.softwaremine.dps.domain.automation.AutomationOutcome
import com.softwaremine.dps.domain.automation.AutomationSecurityGuard
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.automation.ElementMatch
import com.softwaremine.dps.domain.automation.ElementMatcher
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real, `AccessibilityService`-backed [AutomationEngine] (M9).
 *
 * ## Never a stale reference
 * [DpsAutomationService.instance] is read fresh at the start of every
 * method — never cached across calls or across a suspend boundary. A
 * disconnect between two calls is always observed correctly.
 *
 * ## Bounded, event-informed waiting, never a fixed sleep
 * [openApp]/[findElement] poll on a short, fixed interval up to a bounded
 * timeout — mirrors the exact "bounded wait, not a fixed sleep" shape
 * [com.softwaremine.dps.data.android.reminder.ReminderTriggerInstrumentedTest]'s
 * own real-device methodology already established for this codebase. Each
 * iteration re-reads [android.accessibilityservice.AccessibilityService.getRootInActiveWindow]
 * fresh — never reuses a node reference across iterations.
 *
 * ## Security boundary, enforced here, not merely documented
 * [tap] refuses to act on any node
 * [com.softwaremine.dps.domain.automation.AutomationSecurityGuard] flags
 * as sensitive — a hard refusal, never softened into a risk tier.
 *
 * ## Dependencies
 * [Context] (for launching the target app and reading its live
 * foreground window), [IntentLauncher] (reused, not duplicated, for the
 * install check and the actual launch), [DpsLogger].
 */
class AndroidAutomationEngine(
    private val context: Context,
    private val intentLauncher: IntentLauncher,
    private val logger: DpsLogger,
) : AutomationEngine {

    override suspend fun openApp(packageName: String): AutomationOutcome.OpenApp {
        if (!intentLauncher.isPackageInstalled(packageName)) {
            return AutomationOutcome.OpenApp.NotInstalled
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return AutomationOutcome.OpenApp.OpenFailed

        try {
            context.startActivity(Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (throwable: Throwable) {
            logger.w(TAG, "Failed to launch $packageName", throwable)
            return AutomationOutcome.OpenApp.OpenFailed
        }

        val foreground = withTimeoutOrNull(FOREGROUND_TIMEOUT_MILLIS) {
            while (currentForegroundPackage() != packageName) {
                delay(POLL_INTERVAL_MILLIS)
            }
            true
        }
        return if (foreground == true) AutomationOutcome.OpenApp.Opened else AutomationOutcome.OpenApp.OpenFailed
    }

    override suspend fun findElement(descriptor: ElementDescriptor): AutomationOutcome.Find {
        if (DpsAutomationService.instance == null) return AutomationOutcome.Find.AccessibilityUnavailable

        val match = withTimeoutOrNull(FIND_TIMEOUT_MILLIS) {
            var last: ElementMatch = ElementMatch.NotFound
            while (true) {
                val root = DpsAutomationService.instance?.rootInActiveWindow
                if (root != null) {
                    last = ElementMatcher.find(root.toAutomationNode(), descriptor)
                    if (last !is ElementMatch.NotFound) return@withTimeoutOrNull last
                }
                delay(POLL_INTERVAL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE")
            last
        } ?: ElementMatch.NotFound

        return when (match) {
            is ElementMatch.Found -> AutomationOutcome.Find.Found(descriptor)
            is ElementMatch.Ambiguous -> AutomationOutcome.Find.Ambiguous(match.count)
            ElementMatch.NotFound -> AutomationOutcome.Find.NotFound
        }
    }

    override suspend fun tap(descriptor: ElementDescriptor): AutomationOutcome.Action {
        val root = DpsAutomationService.instance?.rootInActiveWindow
            ?: return AutomationOutcome.Action.AccessibilityUnavailable

        // Fresh query, immediately before acting — never a node reference
        // held from an earlier findElement() call.
        val node = when (val match = ElementMatcher.find(root.toAutomationNode(), descriptor)) {
            is ElementMatch.Found -> match.node
            else -> return AutomationOutcome.Action.Rejected
        }

        if (AutomationSecurityGuard.isSensitive(node)) {
            logger.w(TAG, "Refused to tap a sensitive node")
            return AutomationOutcome.Action.Rejected
        }

        val rawNode = (node as? AccessibilityNodeInfoNode)?.raw
            ?: return AutomationOutcome.Action.Rejected

        val performed = try {
            rawNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (throwable: Throwable) {
            logger.w(TAG, "performAction(ACTION_CLICK) threw", throwable)
            false
        }

        return if (performed) AutomationOutcome.Action.Performed else AutomationOutcome.Action.Rejected
    }

    override suspend fun observeAndVerify(descriptor: ElementDescriptor, expectedText: String): VerificationOutcome {
        val root = DpsAutomationService.instance?.rootInActiveWindow
            ?: return VerificationOutcome.ObservationFailed("Accessibility access is not currently available.")

        return when (val match = ElementMatcher.find(root.toAutomationNode(), descriptor)) {
            is ElementMatch.Found -> {
                val observedText = match.node.text.orEmpty()
                if (observedText == expectedText) {
                    VerificationOutcome.Verified
                } else {
                    VerificationOutcome.Mismatch(
                        expected = mapOf("text" to expectedText),
                        observed = mapOf("text" to observedText),
                    )
                }
            }

            ElementMatch.NotFound -> VerificationOutcome.NotFound

            is ElementMatch.Ambiguous -> VerificationOutcome.ObservationFailed(
                "More than one matching element was found.",
            )
        }
    }

    private fun currentForegroundPackage(): String? =
        DpsAutomationService.instance?.rootInActiveWindow?.packageName?.toString()

    /** Adapts a real [AccessibilityNodeInfo] to the pure [AutomationNode] shape [ElementMatcher] searches. */
    private class AccessibilityNodeInfoNode(val raw: AccessibilityNodeInfo) : AutomationNode {
        override val resourceId: String? get() = raw.viewIdResourceName
        override val contentDescription: String? get() = raw.contentDescription?.toString()
        override val text: String? get() = raw.text?.toString()
        override val isPassword: Boolean get() = raw.isPassword
        override val children: List<AutomationNode>
            get() = (0 until raw.childCount).mapNotNull { index ->
                raw.getChild(index)?.let(::AccessibilityNodeInfoNode)
            }
    }

    private fun AccessibilityNodeInfo.toAutomationNode(): AutomationNode = AccessibilityNodeInfoNode(this)

    private companion object {
        const val TAG = "AndroidAutomationEngine"

        // Short enough to keep a request fast, generous enough for a real
        // app launch/foreground transition or a re-render — mirrors
        // ToolExecutor.DEFAULT_TIMEOUT_MILLIS, no separate, larger budget
        // is justified by any evidence gathered during the M9 investigation.
        const val POLL_INTERVAL_MILLIS = 200L
        const val FOREGROUND_TIMEOUT_MILLIS = 10_000L
        const val FIND_TIMEOUT_MILLIS = 10_000L
    }
}
