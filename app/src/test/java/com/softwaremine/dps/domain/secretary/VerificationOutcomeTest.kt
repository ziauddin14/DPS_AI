package com.softwaremine.dps.domain.secretary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the M7 domain shapes themselves (Phase A) — plain data,
 * no logic of their own, so what matters here is that the four outcomes
 * are exhaustively distinguishable and that the two `PendingVerification`
 * variants carry exactly the fields their own doc promises. Persistence
 * (JSON round-trip through the real store) is [PersistentRecoveryStoreTest][com.softwaremine.dps.data.android.secretary.PersistentRecoveryStoreTest]'s
 * job (Phase B), matching how [OperationCheckpoint]/[ExecutionRecoveryState]
 * are already tested exclusively at the store layer, not a separate
 * domain-level serialization test.
 */
class VerificationOutcomeTest {

    @Test
    fun `every VerificationOutcome case is reachable and distinguishable in an exhaustive when`() {
        val outcomes: List<VerificationOutcome> = listOf(
            VerificationOutcome.Verified,
            VerificationOutcome.Mismatch(expected = mapOf("title" to "a"), observed = mapOf("title" to "b")),
            VerificationOutcome.NotFound,
            VerificationOutcome.ObservationFailed("boom"),
        )

        val described = outcomes.map { outcome ->
            when (outcome) {
                VerificationOutcome.Verified -> "verified"
                is VerificationOutcome.Mismatch -> "mismatch"
                VerificationOutcome.NotFound -> "not_found"
                is VerificationOutcome.ObservationFailed -> "observation_failed"
            }
        }

        assertEquals(listOf("verified", "mismatch", "not_found", "observation_failed"), described)
    }

    @Test
    fun `Mismatch carries independent expected and observed maps under the same keys`() {
        val mismatch = VerificationOutcome.Mismatch(
            expected = mapOf("title" to "Submit report", "due_millis" to "1000"),
            observed = mapOf("title" to "Submit report", "due_millis" to "2000"),
        )

        assertEquals(mismatch.expected.keys, mismatch.observed.keys)
        assertNotEquals(mismatch.expected["due_millis"], mismatch.observed["due_millis"])
    }

    @Test
    fun `ObservationFailed carries a diagnostic reason distinct from NotFound`() {
        val failed: VerificationOutcome = VerificationOutcome.ObservationFailed("Calendar access was denied.")

        assertTrue(VerificationOutcome.NotFound != failed)
        assertTrue((failed as VerificationOutcome.ObservationFailed).reason.isNotBlank())
    }

    @Test
    fun `PendingVerification Task carries only explicitly-requested optional fields`() {
        val titleOnly = PendingVerification.Task(
            taskId = 7,
            expectedTitle = "Submit report",
            requestedAtMillis = 1_000L,
        )

        assertEquals("Submit report", titleOnly.expectedTitle)
        assertEquals(null, titleOnly.expectedNotes)
        assertEquals(null, titleOnly.expectedPriority)
        assertEquals(null, titleOnly.expectedDueMillis)

        val fullySpecified = titleOnly.copy(
            expectedNotes = "call the vendor first",
            expectedPriority = "HIGH",
            expectedDueMillis = 5_000L,
        )

        assertEquals("call the vendor first", fullySpecified.expectedNotes)
        assertEquals("HIGH", fullySpecified.expectedPriority)
        assertEquals(5_000L, fullySpecified.expectedDueMillis)
    }

    @Test
    fun `PendingVerification CalendarEvent carries the identity and every expected field as non-null`() {
        val pending = PendingVerification.CalendarEvent(
            eventId = 42L,
            expectedTitle = "Team sync",
            expectedStartMillis = 1_000L,
            expectedEndMillis = 4_600_000L,
            requestedAtMillis = 999L,
        )

        assertEquals(42L, pending.eventId)
        assertEquals(1_000L, pending.expectedStartMillis)
        assertEquals(4_600_000L, pending.expectedEndMillis)
    }
}
