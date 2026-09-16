package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType

/**
 * Whether an intent may run without asking, or needs an explicit yes first
 * (M8).
 *
 * Exactly two values, per M8's own locked contract — no third level. Every
 * action in the current registry is fully describable by one of the two;
 * see [RiskPolicy]'s own doc for the classification itself.
 */
enum class RiskLevel { SAFE_AUTO, CONFIRM_REQUIRED }

/**
 * Deterministic risk classification (M8).
 *
 * ## Why this exists
 * Before M8, [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] asked
 * for confirmation based on two private, hardcoded `Set<IntentType>`
 * constants (`DELETE_CONFIRMATION_TYPES`/`CALL_CONFIRMATION_TYPES`) baked
 * directly into `proceedToExecution`. This type is a direct,
 * behavior-preserving relocation of that same logic into a dedicated, pure,
 * independently testable object — plus the one addition M8's locked
 * contract requires: [IntentType.FORGET_FACT] is now [RiskLevel.CONFIRM_REQUIRED],
 * closing a real gap (a semantic-memory delete that previously ran with no
 * confirmation at all, despite being irreversible).
 *
 * ## Why this takes a [DpsIntent], not a bare [IntentType]
 * A calendar/task/reminder is only risky when it is being cancelled — an
 * ordinary create/update of the same type is not. The action must be
 * inspected too, exactly as the pre-M8 code already did (gating on
 * `intent.action == IntentAction.CANCEL`).
 *
 * ## Determinism (M8's own locked requirement)
 * A pure function of already-classified data. No model call, no
 * [com.softwaremine.dps.domain.ai.AiEngine] reference anywhere in this
 * file, no I/O, no clock, no randomness — the same [DpsIntent] classifies
 * identically every time.
 *
 * ## What this deliberately does not decide
 * Permission and confirmation are separate concepts from risk (see M8's own
 * investigation report, Section 4). This type answers only "should DPS ask
 * first" — not "can DPS even do this right now" (permission) and not "has
 * the user actually said yes" (confirmation lifecycle, unchanged,
 * [PendingConfirmation]).
 */
object RiskPolicy {

    fun classify(intent: DpsIntent): RiskLevel = when {
        intent.type == IntentType.FORGET_FACT -> RiskLevel.CONFIRM_REQUIRED

        intent.type in DELETE_CONFIRMATION_TYPES && intent.action == IntentAction.CANCEL ->
            RiskLevel.CONFIRM_REQUIRED

        intent.type == IntentType.CALL_CONTACT -> RiskLevel.CONFIRM_REQUIRED

        // M9: every automation intent asks first, unconditionally, for
        // Phase 1 — no target/action-aware heuristic yet (locked,
        // evidence-based: Phase 1 has exactly one possible interaction, so
        // building a richer classifier now would be speculative).
        intent.type == IntentType.AUTOMATION -> RiskLevel.CONFIRM_REQUIRED

        else -> RiskLevel.SAFE_AUTO
    }

    /**
     * Intent types whose CANCEL is destructive and irreversible — exactly
     * the pre-M8 `DELETE_CONFIRMATION_TYPES` constant, unchanged.
     */
    private val DELETE_CONFIRMATION_TYPES = setOf(
        IntentType.CALENDAR_EVENT,
        IntentType.TASK,
        IntentType.REMINDER,
    )
}
