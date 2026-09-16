package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
import com.softwaremine.dps.domain.automation.AutomationAppRegistry
import com.softwaremine.dps.domain.automation.AutomationEngine
import com.softwaremine.dps.domain.automation.AutomationOutcome
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.secretary.PendingAutomationAction
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult

/**
 * Performs one bounded, controlled UI interaction in a known Android app
 * (M9). **Never claims the interaction's real-world effect — only that it
 * was dispatched.**
 *
 * ## The confirmation flow is the feature, again
 * Mirrors [PrepareWhatsAppMessageTool]/[AndroidCallTool]'s own established
 * philosophy: this tool's own [ToolResult.Success] says only that a tap
 * was accepted by the platform, never that the target app actually did
 * anything as a result. The real answer comes from
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s own M7-style
 * verification step, run separately, after this call returns — see that
 * class's own M9 doc.
 *
 * ## Why this is a thin adapter
 * All real mechanics — launching the app, finding the element, performing
 * the tap, reading it back — live behind [AutomationEngine]. This class
 * owns none of that, and owns no natural-language understanding, risk
 * decision, or confirmation state either; those live in
 * [com.softwaremine.dps.domain.secretary.RiskPolicy]/
 * [com.softwaremine.dps.domain.secretary.PendingConfirmation]/
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator], entirely
 * unaware this class exists.
 *
 * ## Why this is one operation, not five
 * The M9-locked action vocabulary (`open_app`/`find_element`/`tap`/
 * `wait_for_element`/`observe_ui`) lives at [AutomationEngine]'s own
 * interface layer. This tool exposes exactly one [ToolCall] operation,
 * `perform_interaction`, which internally sequences
 * open→find→checkpoint→tap — mirroring exactly how
 * [AndroidCalendarTool.createEvent] already sequences resolve→build→
 * insert→read-back behind its own single `create_event` operation, never
 * exposing its own sub-steps as separate tool calls.
 *
 * ## [PendingAutomationAction] — write-before, mirroring [AndroidTaskTool]'s own checkpoint precedent
 * Written, durably, immediately before [AutomationEngine.tap] — the exact
 * point an irreversible action is about to happen — and cleared here on
 * every *synchronously known* outcome (the app failed to open, the
 * element was never found, the platform rejected the tap outright). Left
 * **standing** only when [AutomationEngine.tap] reports
 * [AutomationOutcome.Action.Performed] or
 * [AutomationOutcome.Action.AccessibilityUnavailable] (the tap's own
 * outcome is not yet known) — clearing it in either of those two cases is
 * [com.softwaremine.dps.data.android.secretary.AutomationVerifier]'s job,
 * once observation and verification actually resolve it, exactly
 * mirroring how [com.softwaremine.dps.data.android.secretary.ExecutionVerifier]
 * — not the tool that dispatched the original call — owns clearing
 * [com.softwaremine.dps.domain.secretary.PendingVerification].
 *
 * ## Permission
 * [DpsPermission.AUTOMATION_ACCESSIBILITY], checked by the executor before
 * dispatch like every other tool — this class contains no permission
 * logic of its own.
 *
 * ## Dependencies
 * [AutomationEngine], [PersistentRecoveryStore]. No direct Android
 * imports — all platform work lives behind the engine.
 */
class AndroidAutomationTool(
    private val engine: AutomationEngine,
    private val persistentRecoveryStore: PersistentRecoveryStore,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidTool {

    override val id: ToolId = ToolId.AUTOMATION

    override val operations: Set<String> = setOf(OP_PERFORM_INTERACTION)

    override val requiredPermissions: Set<DpsPermission> = setOf(DpsPermission.AUTOMATION_ACCESSIBILITY)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_PERFORM_INTERACTION -> performInteraction(call)
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private suspend fun performInteraction(call: ToolCall): ToolResult {
        val appName = call.argument(ARG_APP)?.trim().orEmpty()
        if (appName.isEmpty()) {
            return ToolResult.Failure(
                reason = "Which app would you like me to open?",
                retryable = false,
            )
        }

        val packageName = AutomationAppRegistry.resolve(appName)
            ?: return ToolResult.Failure(
                reason = "I don't know how to open \"$appName\".",
                retryable = false,
            )

        when (val opened = engine.openApp(packageName)) {
            is AutomationOutcome.OpenApp.NotInstalled -> return ToolResult.Unsupported(
                reason = "\"$appName\" is not installed on this device.",
            )
            is AutomationOutcome.OpenApp.OpenFailed -> return ToolResult.Failure(
                reason = "I couldn't open \"$appName\".",
                retryable = true,
            )
            is AutomationOutcome.OpenApp.Opened -> Unit
        }

        // Phase 1: exactly one known, deterministic target per allowlisted
        // app — no model-supplied element description exists yet (see
        // DpsIntent.kt's own IntentType.AUTOMATION doc for why).
        val descriptor = ElementDescriptor(resourceId = PHASE_1_TARGET_RESOURCE_ID)

        when (val found = engine.findElement(descriptor)) {
            is AutomationOutcome.Find.NotFound -> return ToolResult.Failure(
                reason = "I couldn't find what to tap in \"$appName\".",
                retryable = false,
            )
            is AutomationOutcome.Find.Ambiguous -> return ToolResult.Failure(
                reason = "More than one matching element was found in \"$appName\".",
                retryable = false,
            )
            is AutomationOutcome.Find.AccessibilityUnavailable -> return ToolResult.Failure(
                reason = "Accessibility access is not currently available.",
                retryable = true,
            )
            is AutomationOutcome.Find.Found -> Unit
        }

        // Write-before: the durable marker that this specific, irreversible
        // action is about to be dispatched — see this class's own doc for
        // the exact clear-ownership split with AutomationVerifier.
        persistentRecoveryStore.saveAutomation(
            PendingAutomationAction(
                targetApp = packageName,
                resourceId = descriptor.resourceId,
                contentDescription = descriptor.contentDescription,
                text = descriptor.text,
                expectedText = PHASE_1_EXPECTED_TEXT,
                requestedAtMillis = now(),
            ),
        )

        return when (val tapped = engine.tap(descriptor)) {
            is AutomationOutcome.Action.Rejected -> {
                // Known, synchronous outcome: the tap did not happen.
                persistentRecoveryStore.clearAutomation()
                ToolResult.Failure(reason = "That tap wasn't accepted.", retryable = true)
            }

            is AutomationOutcome.Action.AccessibilityUnavailable -> {
                // Genuinely unknown whether the tap landed — the checkpoint
                // deliberately stays, for AutomationVerifier/recovery to
                // resolve, never re-attempted here.
                ToolResult.Failure(
                    reason = "Accessibility access was lost while tapping — I'm not sure whether it went through.",
                    retryable = false,
                )
            }

            is AutomationOutcome.Action.Performed -> ToolResult.Success(
                // Deliberately thin: this states only that the platform
                // accepted the tap, never that the app's own state actually
                // changed — see this class's own doc.
                summary = "Tapped it in \"$appName\".",
                data = mapOf(
                    "target_app" to packageName,
                    "resource_id" to (descriptor.resourceId ?: ""),
                    "expected_text" to PHASE_1_EXPECTED_TEXT,
                ),
            )
        }
    }

    private companion object {
        const val OP_PERFORM_INTERACTION = "perform_interaction"
        const val ARG_APP = "app"

        // Phase 1's own fixed, deterministic test-app target — see the M9
        // implementation plan's own "Test Application Plan".
        const val PHASE_1_TARGET_RESOURCE_ID = "com.softwaremine.dps.automationtarget:id/automation_target_button"
        const val PHASE_1_EXPECTED_TEXT = "Tapped"
    }
}
