package com.softwaremine.dps.domain.secretary

/**
 * Where a single request stands in the secretary's handling of it.
 *
 * ## Purpose
 * [ai.secretary.SecretaryOrchestrator] does more per message than Phase D's
 * [ai.intent.ToolOrchestrator] did — a request can now be blocked on a
 * permission, on choosing between several contacts, or on a yes/no answer to a
 * follow-up suggestion, in addition to the missing-information case Phase D
 * already had. Naming each of those as a state — rather than inferring "what
 * are we waiting for" from which nullable field happens to be set — is what
 * makes the handling deterministic and directly testable (Phase E brief,
 * requirement 2).
 *
 * ## Dependencies
 * None. Pure Kotlin, no reference to intents, tools or the model.
 */
enum class SecretaryState {
    /** Nothing in flight. Ready for a new message. */
    IDLE,

    /** A tool needs a permission the app does not currently hold. */
    WAITING_PERMISSION,

    /** A follow-up suggestion was made; waiting on a yes/no answer. */
    WAITING_CONFIRMATION,

    /** Several contacts matched; waiting on the user to pick one. */
    WAITING_CONTACT_SELECTION,

    /** The request is understood but missing a required detail. */
    WAITING_MISSING_INFORMATION,

    /** A tool call (or plan of several) is running. */
    EXECUTING,

    /** The request finished successfully. */
    COMPLETED,

    /** The request could not be carried out. */
    FAILED,
}

/**
 * Something that happened while handling a request.
 *
 * Firing one of these is [ai.secretary.SecretaryOrchestrator]'s job; deciding
 * what it means for [SecretaryState] is [SecretaryStateMachine]'s.
 * Interpreting a *user's* raw reply — is "haan" a [ConfirmationAccepted] or is
 * "2" a [ContactSelected] — happens above this type, using whichever
 * [SecretaryState] the conversation is currently in to know which
 * interpretation applies. The state machine itself never reads message text.
 */
sealed interface SecretaryEvent {
    /** A new, unprompted message arrived. Only meaningful when nothing is in flight. */
    data object MessageReceived : SecretaryEvent

    /** The classified request is missing a required field. */
    data object ClarificationNeeded : SecretaryEvent

    /** The missing field was supplied. */
    data object InformationProvided : SecretaryEvent

    /** A tool reported it needs a permission the app does not hold. */
    data object PermissionNeeded : SecretaryEvent

    /** The user granted the permission. */
    data object PermissionGranted : SecretaryEvent

    /** The user declined the permission, or the OS denied it. */
    data object PermissionDenied : SecretaryEvent

    /** Contact resolution found more than one plausible match. */
    data object ContactAmbiguous : SecretaryEvent

    /** The user picked one of the offered contacts. */
    data object ContactSelected : SecretaryEvent

    /** A follow-up suggestion was offered ("...bhi bana doon?"). */
    data object ConfirmationRequested : SecretaryEvent

    /** The user agreed to the suggestion. */
    data object ConfirmationAccepted : SecretaryEvent

    /** The user declined the suggestion. */
    data object ConfirmationDeclined : SecretaryEvent

    /** The tool call (or plan) completed successfully. */
    data object ExecutionSucceeded : SecretaryEvent

    /** The tool call (or plan) did not complete successfully. */
    data object ExecutionFailed : SecretaryEvent

    /** Discard whatever is in flight and return to [SecretaryState.IDLE]. */
    data object Reset : SecretaryEvent
}

/**
 * The transition table for [SecretaryState].
 *
 * ## Design
 * [transition] is a pure function, total over every `(state, event)` pair —
 * there is no combination it cannot answer and nothing here ever throws. An
 * event that does not apply to the current state (e.g. [SecretaryEvent.ContactSelected]
 * while [SecretaryState.IDLE]) is a **no-op**: the state is returned unchanged
 * rather than treated as an error. That choice matters for how this is used —
 * [ai.secretary.SecretaryOrchestrator] fires events as a direct consequence of
 * what a tool or the model actually reported, so an "invalid" combination
 * means the caller's own bookkeeping is wrong, not that the user did anything
 * wrong; silently holding the current state is safer than crashing mid-request.
 *
 * ## Reset
 * [SecretaryEvent.Reset] is handled before anything else and returns
 * [SecretaryState.IDLE] from **every** state, including [SecretaryState.IDLE]
 * itself — it is how a new conversation, or an explicitly abandoned request,
 * always has one unambiguous way back to the start.
 */
object SecretaryStateMachine {

    fun transition(current: SecretaryState, event: SecretaryEvent): SecretaryState {
        if (event is SecretaryEvent.Reset) return SecretaryState.IDLE

        return when (current) {
            SecretaryState.IDLE -> when (event) {
                SecretaryEvent.MessageReceived -> SecretaryState.EXECUTING
                else -> current
            }

            SecretaryState.EXECUTING -> when (event) {
                SecretaryEvent.ClarificationNeeded -> SecretaryState.WAITING_MISSING_INFORMATION
                SecretaryEvent.PermissionNeeded -> SecretaryState.WAITING_PERMISSION
                SecretaryEvent.ContactAmbiguous -> SecretaryState.WAITING_CONTACT_SELECTION
                SecretaryEvent.ConfirmationRequested -> SecretaryState.WAITING_CONFIRMATION
                SecretaryEvent.ExecutionSucceeded -> SecretaryState.COMPLETED
                SecretaryEvent.ExecutionFailed -> SecretaryState.FAILED
                else -> current
            }

            SecretaryState.WAITING_MISSING_INFORMATION -> when (event) {
                SecretaryEvent.InformationProvided -> SecretaryState.EXECUTING
                else -> current
            }

            SecretaryState.WAITING_PERMISSION -> when (event) {
                SecretaryEvent.PermissionGranted -> SecretaryState.EXECUTING
                SecretaryEvent.PermissionDenied -> SecretaryState.FAILED
                else -> current
            }

            SecretaryState.WAITING_CONTACT_SELECTION -> when (event) {
                SecretaryEvent.ContactSelected -> SecretaryState.EXECUTING
                else -> current
            }

            SecretaryState.WAITING_CONFIRMATION -> when (event) {
                SecretaryEvent.ConfirmationAccepted -> SecretaryState.EXECUTING
                // Declining a suggestion does not undo the request it followed
                // — that already completed. Declining just means "not that too."
                SecretaryEvent.ConfirmationDeclined -> SecretaryState.COMPLETED
                else -> current
            }

            SecretaryState.COMPLETED -> when (event) {
                SecretaryEvent.MessageReceived -> SecretaryState.EXECUTING
                // A follow-up suggestion is offered only after the request it
                // follows has already succeeded (Day 05 Phase E Stage 2) — the
                // request is genuinely complete, and a suggestion about it is
                // a separate, later thing to wait on, not a reopening of it.
                SecretaryEvent.ConfirmationRequested -> SecretaryState.WAITING_CONFIRMATION
                else -> current
            }

            SecretaryState.FAILED -> when (event) {
                SecretaryEvent.MessageReceived -> SecretaryState.EXECUTING
                else -> current
            }
        }
    }
}
