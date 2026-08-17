package com.softwaremine.dps.domain.secretary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive verification of the secretary state machine (Day 05 Phase E).
 *
 * ## Why exhaustive
 * The brief requires transitions to be "deterministic and fully testable."
 * [SecretaryStateMachine.transition] is total — every `(state, event)` pair
 * has a defined answer, most of them "stay put." Testing only the handful of
 * happy-path transitions would leave the *no-op* half of the contract
 * unverified, and a no-op that silently stopped being a no-op (e.g. a future
 * edit that let [SecretaryEvent.ContactSelected] leak [SecretaryState.IDLE]
 * into [SecretaryState.EXECUTING]) is exactly the kind of bug this table
 * exists to prevent.
 */
class SecretaryStateMachineTest {

    private val allEvents = listOf(
        SecretaryEvent.MessageReceived,
        SecretaryEvent.ClarificationNeeded,
        SecretaryEvent.InformationProvided,
        SecretaryEvent.PermissionNeeded,
        SecretaryEvent.PermissionGranted,
        SecretaryEvent.PermissionDenied,
        SecretaryEvent.ContactAmbiguous,
        SecretaryEvent.ContactSelected,
        SecretaryEvent.TypeDisambiguationNeeded,
        SecretaryEvent.TypeDisambiguationResolved,
        SecretaryEvent.ConfirmationRequested,
        SecretaryEvent.ConfirmationAccepted,
        SecretaryEvent.ConfirmationDeclined,
        SecretaryEvent.ExecutionSucceeded,
        SecretaryEvent.ExecutionFailed,
        SecretaryEvent.Reset,
    )

    // -----------------------------------------------------------------
    // Reset — the universal escape hatch
    // -----------------------------------------------------------------

    @Test
    fun `reset returns to idle from every state`() {
        SecretaryState.entries.forEach { state ->
            assertEquals(
                "Reset from $state",
                SecretaryState.IDLE,
                SecretaryStateMachine.transition(state, SecretaryEvent.Reset),
            )
        }
    }

    // -----------------------------------------------------------------
    // The documented "happy path" transitions
    // -----------------------------------------------------------------

    @Test
    fun `idle to executing on a new message`() {
        assertEquals(
            SecretaryState.EXECUTING,
            SecretaryStateMachine.transition(SecretaryState.IDLE, SecretaryEvent.MessageReceived),
        )
    }

    @Test
    fun `executing branches to each waiting state`() {
        val expected = mapOf(
            SecretaryEvent.ClarificationNeeded to SecretaryState.WAITING_MISSING_INFORMATION,
            SecretaryEvent.PermissionNeeded to SecretaryState.WAITING_PERMISSION,
            SecretaryEvent.ContactAmbiguous to SecretaryState.WAITING_CONTACT_SELECTION,
            SecretaryEvent.TypeDisambiguationNeeded to SecretaryState.WAITING_TYPE_DISAMBIGUATION,
            SecretaryEvent.ConfirmationRequested to SecretaryState.WAITING_CONFIRMATION,
            SecretaryEvent.ExecutionSucceeded to SecretaryState.COMPLETED,
            SecretaryEvent.ExecutionFailed to SecretaryState.FAILED,
        )

        expected.forEach { (event, target) ->
            assertEquals(
                "EXECUTING + $event",
                target,
                SecretaryStateMachine.transition(SecretaryState.EXECUTING, event),
            )
        }
    }

    @Test
    fun `each waiting state returns to executing on its answer`() {
        val answers = mapOf(
            SecretaryState.WAITING_MISSING_INFORMATION to SecretaryEvent.InformationProvided,
            SecretaryState.WAITING_PERMISSION to SecretaryEvent.PermissionGranted,
            SecretaryState.WAITING_CONTACT_SELECTION to SecretaryEvent.ContactSelected,
            SecretaryState.WAITING_TYPE_DISAMBIGUATION to SecretaryEvent.TypeDisambiguationResolved,
            SecretaryState.WAITING_CONFIRMATION to SecretaryEvent.ConfirmationAccepted,
        )

        answers.forEach { (waiting, answer) ->
            assertEquals(
                "$waiting + $answer",
                SecretaryState.EXECUTING,
                SecretaryStateMachine.transition(waiting, answer),
            )
        }
    }

    @Test
    fun `permission denied fails rather than looping back to executing`() {
        assertEquals(
            SecretaryState.FAILED,
            SecretaryStateMachine.transition(SecretaryState.WAITING_PERMISSION, SecretaryEvent.PermissionDenied),
        )
    }

    @Test
    fun `declining a follow-up suggestion completes rather than fails`() {
        // The request the suggestion followed already succeeded; declining the
        // extra offer is not a failure of anything.
        assertEquals(
            SecretaryState.COMPLETED,
            SecretaryStateMachine.transition(SecretaryState.WAITING_CONFIRMATION, SecretaryEvent.ConfirmationDeclined),
        )
    }

    /**
     * A follow-up suggestion is only ever offered *after* the request it
     * follows has already succeeded — the state has already reached
     * COMPLETED by the time [ai.secretary.SecretaryOrchestrator] decides to
     * offer one, so COMPLETED itself must accept ConfirmationRequested, not
     * just EXECUTING.
     */
    @Test
    fun `a follow-up suggestion can be offered after a request has already completed`() {
        assertEquals(
            SecretaryState.WAITING_CONFIRMATION,
            SecretaryStateMachine.transition(SecretaryState.COMPLETED, SecretaryEvent.ConfirmationRequested),
        )
    }

    @Test
    fun `completed and failed both accept a fresh message`() {
        assertEquals(
            SecretaryState.EXECUTING,
            SecretaryStateMachine.transition(SecretaryState.COMPLETED, SecretaryEvent.MessageReceived),
        )
        assertEquals(
            SecretaryState.EXECUTING,
            SecretaryStateMachine.transition(SecretaryState.FAILED, SecretaryEvent.MessageReceived),
        )
    }

    // -----------------------------------------------------------------
    // Totality — every combination answers, none of them throw
    // -----------------------------------------------------------------

    @Test
    fun `every state-event pair is answered without throwing`() {
        SecretaryState.entries.forEach { state ->
            allEvents.forEach { event ->
                SecretaryStateMachine.transition(state, event)
            }
        }
    }

    /**
     * The full expectation table.
     *
     * Anything not named above as a documented transition must be a no-op —
     * this is what actually proves the "deterministic" half of the brief:
     * every one of the 112 cells has exactly one correct answer, not just the
     * dozen a hand-picked set of happy-path tests would cover.
     */
    @Test
    fun `every undocumented transition is a no-op`() {
        val documented: Map<Pair<SecretaryState, SecretaryEvent>, SecretaryState> = buildMap {
            SecretaryState.entries.forEach { put(it to SecretaryEvent.Reset, SecretaryState.IDLE) }
            put(SecretaryState.IDLE to SecretaryEvent.MessageReceived, SecretaryState.EXECUTING)
            put(SecretaryState.EXECUTING to SecretaryEvent.ClarificationNeeded, SecretaryState.WAITING_MISSING_INFORMATION)
            put(SecretaryState.EXECUTING to SecretaryEvent.PermissionNeeded, SecretaryState.WAITING_PERMISSION)
            put(SecretaryState.EXECUTING to SecretaryEvent.ContactAmbiguous, SecretaryState.WAITING_CONTACT_SELECTION)
            put(SecretaryState.EXECUTING to SecretaryEvent.TypeDisambiguationNeeded, SecretaryState.WAITING_TYPE_DISAMBIGUATION)
            put(SecretaryState.EXECUTING to SecretaryEvent.ConfirmationRequested, SecretaryState.WAITING_CONFIRMATION)
            put(SecretaryState.EXECUTING to SecretaryEvent.ExecutionSucceeded, SecretaryState.COMPLETED)
            put(SecretaryState.EXECUTING to SecretaryEvent.ExecutionFailed, SecretaryState.FAILED)
            put(SecretaryState.WAITING_MISSING_INFORMATION to SecretaryEvent.InformationProvided, SecretaryState.EXECUTING)
            put(SecretaryState.WAITING_PERMISSION to SecretaryEvent.PermissionGranted, SecretaryState.EXECUTING)
            put(SecretaryState.WAITING_PERMISSION to SecretaryEvent.PermissionDenied, SecretaryState.FAILED)
            put(SecretaryState.WAITING_CONTACT_SELECTION to SecretaryEvent.ContactSelected, SecretaryState.EXECUTING)
            put(SecretaryState.WAITING_TYPE_DISAMBIGUATION to SecretaryEvent.TypeDisambiguationResolved, SecretaryState.EXECUTING)
            put(SecretaryState.WAITING_CONFIRMATION to SecretaryEvent.ConfirmationAccepted, SecretaryState.EXECUTING)
            put(SecretaryState.WAITING_CONFIRMATION to SecretaryEvent.ConfirmationDeclined, SecretaryState.COMPLETED)
            put(SecretaryState.COMPLETED to SecretaryEvent.MessageReceived, SecretaryState.EXECUTING)
            put(SecretaryState.COMPLETED to SecretaryEvent.ConfirmationRequested, SecretaryState.WAITING_CONFIRMATION)
            put(SecretaryState.FAILED to SecretaryEvent.MessageReceived, SecretaryState.EXECUTING)
        }

        SecretaryState.entries.forEach { state ->
            allEvents.forEach { event ->
                val expected = documented[state to event] ?: state
                assertEquals(
                    "$state + $event",
                    expected,
                    SecretaryStateMachine.transition(state, event),
                )
            }
        }
    }
}
