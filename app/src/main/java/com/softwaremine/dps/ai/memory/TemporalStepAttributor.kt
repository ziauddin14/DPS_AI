package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.DpsIntent

/**
 * Associates each step of a multi-step plan with its own genuine temporal
 * phrase — never a sibling step's — before [TemporalPhraseResolver] ever
 * sees it (Day 08-E multi-step follow-up).
 *
 * ## The vulnerability this closes
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator.handlePlan]
 * originally checked a step's `raw_when` against the *whole* compound
 * message via [TemporalGroundingGuard] — proven, by a JVM regression test,
 * to accept a hallucinated `raw_when` on one step merely because a
 * *different* step of the same message genuinely said those words. This
 * class replaces that whole-message check for multi-step plans with
 * per-occurrence attribution: each genuine temporal phrase
 * [TemporalPhraseSpanFinder] locates in the message may be claimed by at
 * most one step.
 *
 * ## Why first-claim-wins, not "nearest" or "most confident"
 * Steps are attributed in the order they were classified — the same order
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator.handlePlan]
 * already executes them in — against spans found in the order they occur
 * in the message. A step whose `raw_when` matches a span already claimed by
 * an earlier step, or matches no span at all, is rejected: `rawWhen`
 * becomes `null`, exactly what [TemporalGroundingGuard] already does for a
 * wholly ungrounded phrase. No heuristic decides *which* of two claimants
 * "really" owns a span — the first one in classification order does, and
 * every later one that would otherwise reuse the same occurrence is
 * refused rather than guessed at. This is deliberately conservative: it can
 * reject a step that shares a genuine phrase with an earlier step (see
 * below), but it can never let one step's fabrication ride on another's
 * genuine words.
 *
 * ## What this deliberately does not solve
 * A temporal phrase genuinely meant to apply to more than one step — "Kal
 * subah dono kaam karne hain: Ali ko call karo aur uska task bana do" — is
 * not given to both. Each span is claimed by exactly one step; a second
 * step that legitimately shares the same time is, for now, left with no
 * `raw_when` and asked to clarify rather than silently guessed to share it.
 * Inventing that shared-ownership semantics safely is future work, not
 * attempted here — see the class doc history for why.
 *
 * ## Dependencies
 * [TemporalPhraseSpanFinder], [TemporalGroundingGuard]. No Android, no
 * model call.
 */
class TemporalStepAttributor(
    private val spanFinder: TemporalPhraseSpanFinder,
    private val groundingGuard: TemporalGroundingGuard,
) {

    /**
     * Returns [steps] with every step's `raw_when` either confirmed against
     * a genuine, not-yet-claimed occurrence in [userMessage], or discarded
     * (set to `null`) when no such occurrence exists. Never touches
     * `date`/`time` — resolving a confirmed `raw_when` remains
     * [TemporalPhraseResolver]'s job, run afterwards by the caller.
     */
    fun attribute(userMessage: String, steps: List<DpsIntent>): List<DpsIntent> {
        val spans = spanFinder.findSpans(userMessage)
        val claimed = BooleanArray(spans.size)

        return steps.map { step ->
            val rawWhen = step.parameters.rawWhen?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@map step

            val claimIndex = spans.indices.firstOrNull { index ->
                !claimed[index] && groundingGuard.isGrounded(spans[index].text, rawWhen)
            }

            if (claimIndex == null) {
                step.copy(parameters = step.parameters.copy(rawWhen = null))
            } else {
                claimed[claimIndex] = true
                step
            }
        }
    }
}
