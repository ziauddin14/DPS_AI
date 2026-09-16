package com.softwaremine.dps.data.android.secretary

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.automation.AutomationEngine
import com.softwaremine.dps.domain.automation.ElementDescriptor
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.secretary.PendingAutomationAction
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.withContext

/**
 * Independently observes and compares a bounded UI automation action's
 * claimed outcome against the real UI state it was supposed to produce
 * (M9) — reuses [VerificationOutcome]'s existing four cases by name and by
 * meaning, exactly [com.softwaremine.dps.data.android.secretary.ExecutionVerifier]
 * already does for `create_task`/`create_event`, per M9's own locked
 * contract not to redesign M7's vocabulary.
 *
 * ## Why this is a separate class, not an extension of [ExecutionVerifier]
 * [ExecutionVerifier] re-reads a real repository/content-provider record
 * by a stable id ([com.softwaremine.dps.domain.productivity.TaskRepository]/
 * [com.softwaremine.dps.data.android.calendar.CalendarEventReader]) — an
 * automation action has no such id, only a node descriptor and an
 * expected text, resolved through [AutomationEngine.observeAndVerify]
 * instead. Entangling a structurally different read mechanism into M7's
 * own frozen class was explicitly avoided — see the M9 implementation
 * plan's own "Verification Plan".
 *
 * ## Why this does not *write* [PendingAutomationAction] itself
 * Unlike [ExecutionVerifier.verify] (which writes
 * [com.softwaremine.dps.domain.secretary.PendingVerification] itself,
 * *after* the tool already reported success), the automation checkpoint
 * must be durable *before* the tap is dispatched — see
 * [com.softwaremine.dps.data.android.tool.AndroidAutomationTool]'s own
 * doc for why it, not this class, owns the write, mirroring
 * [com.softwaremine.dps.data.android.tool.AndroidTaskTool]'s own
 * pre-write [com.softwaremine.dps.domain.secretary.OperationCheckpoint]
 * precedent instead. This class only ever *reads* an already-persisted
 * record, resolves it, and clears it — the identical clear-ownership
 * split the M9 implementation plan locks.
 *
 * ## Why [engine] is nullable
 * Mirrors [ExecutionVerifier]'s own [com.softwaremine.dps.domain.productivity.TaskRepository]/
 * [com.softwaremine.dps.data.android.calendar.CalendarEventReader]
 * nullability exactly, for the identical reason: existing test harnesses
 * built for unrelated milestones construct fake tools with no real
 * automation engine behind them; `null` lets this class opt out cleanly
 * rather than needing a hand-built fake kept artificially in sync.
 *
 * ## Dependencies
 * [AutomationEngine] (interface, JVM-fakeable), [PersistentRecoveryStore],
 * [DispatcherProvider] (the engine's own real implementation performs
 * real accessibility-tree I/O, dispatched on [DispatcherProvider.io], the
 * same dispatcher every other tool call already uses).
 */
class AutomationVerifier(
    private val engine: AutomationEngine?,
    private val persistentRecoveryStore: PersistentRecoveryStore,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) {

    /**
     * Verifies the automation action a just-returned [result] claims to
     * have performed, when [intent] is [IntentType.AUTOMATION] and a
     * [PendingAutomationAction] record is actually on disk (written by
     * [com.softwaremine.dps.data.android.tool.AndroidAutomationTool]
     * itself, before the tap) — otherwise `null` (see this class's own
     * doc for why `null` is "never attempted," not a fifth outcome).
     */
    suspend fun verify(intent: DpsIntent, result: ToolResult.Success): VerificationOutcome? {
        if (intent.type != IntentType.AUTOMATION) return null
        val pending = persistentRecoveryStore.loadAutomation() ?: return null

        val outcome = resolve(pending)
        persistentRecoveryStore.clearAutomation()
        return outcome
    }

    /**
     * Resolves whatever [PendingAutomationAction] a previous process left
     * behind, or `null` when nothing was pending. Read-only, re-observing
     * real UI state — never re-performs the tap, regardless of how much
     * time has passed since it was requested.
     */
    suspend fun resolvePendingAutomationIfAny(): VerificationOutcome? {
        val pending = persistentRecoveryStore.loadAutomation() ?: return null

        val outcome = resolve(pending)
        persistentRecoveryStore.clearAutomation()
        return outcome
    }

    private suspend fun resolve(pending: PendingAutomationAction): VerificationOutcome {
        val realEngine = engine
            ?: return VerificationOutcome.ObservationFailed("Automation verification is not available.")

        val descriptor = ElementDescriptor(
            resourceId = pending.resourceId,
            contentDescription = pending.contentDescription,
            text = pending.text,
        )

        return try {
            withContext(dispatchers.io) { realEngine.observeAndVerify(descriptor, pending.expectedText) }
        } catch (throwable: Throwable) {
            logger.w(TAG, "Automation observation failed for ${pending.targetApp}", throwable)
            VerificationOutcome.ObservationFailed(throwable.message ?: "Could not check the result.")
        }
    }

    private companion object {
        const val TAG = "AutomationVerifier"
    }
}
