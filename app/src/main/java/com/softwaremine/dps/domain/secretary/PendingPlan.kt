package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent

/**
 * The unexecuted remainder of a multi-step plan, held while one step blocks
 * on clarification, confirmation, contact selection, or type disambiguation
 * (M2-C).
 *
 * ## Why this exists alongside the other four `Pending*` types
 * [PendingContactSelection]/[PendingConfirmation]/[PendingTypeDisambiguation]
 * — and the bare `pendingClarification` field — already answer *why*
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] is waiting; this
 * type never duplicates that. It answers a different question: once that
 * one blocked step is resolved, what work is still left to run. The two are
 * set and cleared together, but neither subsumes the other — see
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s
 * `continueAfterResumedStep`.
 *
 * ## Why [remainingSteps] never includes the blocked step itself
 * The blocked step's own [DpsIntent] already lives inside whichever
 * `Pending*` type is set alongside this one — storing it here a second time
 * would risk it running twice once resolved.
 *
 * ## [remainingOffsets]
 * A parallel list to [remainingSteps] — each entry is the signed cross-step
 * reminder offset (M2-A) that step of the *original* message stated, read
 * from its `raw_when` before `TemporalStepAttributor` ran. That attributor
 * nulls out any `raw_when` it cannot itself resolve — which a relative
 * offset phrase like "15 minutes before" never can, since it names a delta,
 * not an absolute date or time — so by the time a step is parked here, the
 * text itself is already gone from the step; only this pre-extracted value
 * survives to be used once the step is actually reached.
 *
 * ## [lastEventStartMillis]
 * The same cross-step timing context `anchorToPriorEvent` already threads
 * through a single, uninterrupted plan run — preserved here so a block
 * between a calendar-event step and a reminder step that depends on it does
 * not lose that context.
 *
 * ## Freshness
 * Mirrors [PendingContactSelection]/[PendingConfirmation]/
 * [PendingTypeDisambiguation]'s constant and shape for consistency. Not,
 * however, independently enforced as a separate gate: this is always set
 * and consumed in lockstep with exactly one of those three types (or with
 * `pendingClarification`, which — a discovered, pre-existing, otherwise
 * undocumented asymmetry — has no freshness check of its own at all). Since
 * the sibling field's own resume path already decides whether the
 * underlying answer is still honoured, gating the plan remainder separately
 * would only ever produce a confusing partial outcome — the one step
 * resumes but the rest of the plan mysteriously does not — never a safer
 * one.
 */
data class PendingPlan(
    val remainingSteps: List<DpsIntent>,
    val remainingOffsets: List<Long?>,
    val completedReplies: List<String>,
    val lastEventStartMillis: Long?,
    val requestedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - requestedAtMillis <= FRESHNESS_WINDOW_MILLIS

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
