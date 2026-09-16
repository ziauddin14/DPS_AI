package com.softwaremine.dps.domain.secretary

import kotlinx.serialization.Serializable

/**
 * A durable record that a bounded UI automation action (M9) is about to be
 * dispatched, written *before* the action's own real side effect begins —
 * modeled directly on [OperationCheckpoint]'s own write-before/
 * clear-on-resolve discipline.
 *
 * ## Why this is a sibling type, not a fourth [OperationType] case
 * [OperationCheckpoint.title] is "enough to name the operation in a
 * user-facing notice" for a task/reminder/event's own title — an
 * automation action ("tapped a button in an app") does not fit that shape
 * cleanly, and [PendingVerification] already established the right
 * precedent for exactly this situation: a new persisted concern gets its
 * own sibling type in the same [com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore]
 * file, not a case shoehorned onto an existing type whose every other case
 * means something narrower.
 *
 * ## Why this has no freshness/expiry, mirroring [PendingVerification] exactly
 * Resolving a leftover record means re-observing real state and comparing
 * it — read-only, implying no renewed consent, the identical reasoning
 * [PendingVerification]'s own doc gives for having no `isFresh()`. The
 * passage of time does not make reading and comparing real UI state less
 * safe or less honest.
 *
 * ## What is deliberately never persisted here
 * No `AccessibilityNodeInfo`, no raw screen content beyond the three
 * descriptor strings already needed to re-find the same element, and no
 * "current phase" field — a leftover record's mere existence already means
 * "action dispatched, not yet resolved"; [com.softwaremine.dps.domain.automation.AutomationEngine.observeAndVerify]'s
 * own single atomic call leaves no further sub-phase to distinguish.
 *
 * ## Recovery, never re-execution
 * Finding a leftover record on restart means observing and verifying —
 * exactly once, read-only — never re-performing the tap. See
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s own M9
 * recovery doc for the full sequence.
 */
@Serializable
data class PendingAutomationAction(
    /** The resolved package name (never a spoken app name) — see [com.softwaremine.dps.domain.automation.AutomationAppRegistry]. */
    val targetApp: String,

    /** Mirrors [com.softwaremine.dps.domain.automation.ElementDescriptor]'s own three fields, for re-observation. */
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,

    /** The expected post-action text, per Phase 1's own deterministic verification contract. */
    val expectedText: String,

    val requestedAtMillis: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
