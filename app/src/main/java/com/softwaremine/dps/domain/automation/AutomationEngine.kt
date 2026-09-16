package com.softwaremine.dps.domain.automation

import com.softwaremine.dps.domain.secretary.VerificationOutcome

/**
 * The controlled Android UI automation capability (M9).
 *
 * ## Purpose
 * The one seam between [com.softwaremine.dps.data.android.tool.AndroidAutomationTool]
 * (thin, owns no Android specifics) and the real, `AccessibilityService`-backed
 * mechanics. Mirrors [com.softwaremine.dps.domain.productivity.TaskRepository]/
 * [com.softwaremine.dps.data.android.calendar.CalendarEventReader]'s own
 * shape — a plain domain interface a tool depends on, implemented by a class
 * that does own the real platform surface.
 *
 * ## Why exactly four methods, not the full five-action vocabulary
 * The M9 architectural decisions document locks a five-entry action
 * vocabulary (`open_app`/`observe_ui`/`find_element`/`tap`/`wait_for_element`)
 * at this interface's own layer, not as five separate [com.softwaremine.dps.domain.tool.ToolCall]
 * operations — see [com.softwaremine.dps.data.android.tool.AndroidAutomationTool]'s
 * own doc for that layering. `observe_ui` and `wait_for_element` are folded
 * into [findElement]'s own bounded internal wait (there is no
 * observe-without-a-target need for Phase 1); [observeAndVerify] is the one
 * method that performs both the post-action observation and the M7-style
 * verification in a single atomic suspend call, since there is no persisted
 * intermediate state between "read the tree" and "compare it" worth
 * checkpointing separately (see [com.softwaremine.dps.domain.secretary.PendingAutomationAction]'s
 * own doc for why the write-before boundary sits *before* [tap], not
 * between [tap] and [observeAndVerify]).
 *
 * ## What this deliberately does not own
 * No natural-language understanding, no risk decision, no confirmation
 * state, no long-term planning — every one of those lives above this
 * interface, in [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]/
 * [com.softwaremine.dps.domain.secretary.RiskPolicy]/
 * [com.softwaremine.dps.domain.secretary.PendingConfirmation], entirely
 * unaware this interface exists.
 *
 * ## Never a stale reference
 * Every method reads the live accessibility service state fresh, at call
 * time — never caches a service or node reference across calls. See the
 * real implementation's own doc for exactly how.
 */
interface AutomationEngine {

    /**
     * Launches [packageName] and waits (bounded) for it to become the
     * foreground app.
     */
    suspend fun openApp(packageName: String): AutomationOutcome.OpenApp

    /**
     * Finds the one node matching [descriptor], waiting (bounded,
     * re-observing only, never re-acting) if it is not immediately
     * present — see [com.softwaremine.dps.domain.automation.ElementMatcher]'s
     * own doc for the matching rule itself.
     */
    suspend fun findElement(descriptor: ElementDescriptor): AutomationOutcome.Find

    /**
     * Performs one bounded click on the node matching [descriptor],
     * re-querying it fresh immediately before acting (never a cached
     * reference from an earlier [findElement] call).
     *
     * ## What a `true`/successful result means — and does not mean
     * Only that the platform accepted and dispatched the click. It proves
     * nothing about whether the target app's own click handler ran or
     * changed anything observable — that is [observeAndVerify]'s job,
     * never inferred from this method's own return value.
     */
    suspend fun tap(descriptor: ElementDescriptor): AutomationOutcome.Action

    /**
     * Re-reads the node matching [descriptor] and compares its text
     * against [expectedText], producing one of
     * [VerificationOutcome]'s existing four cases — reused by name and by
     * meaning, per M9's own locked contract not to redesign M7's
     * vocabulary. Never re-performs [tap] itself, under any circumstance.
     */
    suspend fun observeAndVerify(
        descriptor: ElementDescriptor,
        expectedText: String,
    ): VerificationOutcome
}

/**
 * The deterministic signals [com.softwaremine.dps.domain.automation.ElementMatcher]
 * uses to find one node, in priority order — resource id first, then
 * content description, then exact visible text (M9's own locked targeting
 * strategy). At least one field should be non-null for a match to ever
 * succeed; which one(s) are supplied is decided once, by whoever
 * constructs this descriptor for a given target (Phase 1: always
 * [resourceId], per the deterministic test app's own design).
 */
data class ElementDescriptor(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
)

/**
 * Bounded, execution-time outcomes [AutomationEngine] itself can produce —
 * deliberately separate from [com.softwaremine.dps.domain.tool.ToolResult]
 * (which [com.softwaremine.dps.data.android.tool.AndroidAutomationTool] maps
 * these into) and from [VerificationOutcome] (the post-action, M7-shaped
 * result [observeAndVerify] produces directly). Keeping the three apart is
 * itself a locked M9 decision — see the M9 implementation plan's own
 * failure-model section.
 */
sealed interface AutomationOutcome {

    sealed interface OpenApp : AutomationOutcome {
        data object Opened : OpenApp
        data object NotInstalled : OpenApp
        data object OpenFailed : OpenApp
    }

    sealed interface Find : AutomationOutcome {
        data class Found(val descriptor: ElementDescriptor) : Find
        data object NotFound : Find
        data class Ambiguous(val count: Int) : Find
        data object AccessibilityUnavailable : Find
    }

    sealed interface Action : AutomationOutcome {
        data object Performed : Action
        data object Rejected : Action
        data object AccessibilityUnavailable : Action
    }
}
