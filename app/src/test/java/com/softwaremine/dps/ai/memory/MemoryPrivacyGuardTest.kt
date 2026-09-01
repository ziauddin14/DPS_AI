package com.softwaremine.dps.ai.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the M6 privacy deny-list.
 *
 * ## What is being protected
 * That a credential or sensitive identifier never reaches
 * [com.softwaremine.dps.data.android.memory.LongTermMemoryStore], regardless
 * of how it was phrased. A false positive (refusing an innocuous fact) is an
 * acceptable failure mode here; a false negative (storing a real credential)
 * is not — see [MemoryPrivacyGuard]'s own doc.
 */
class MemoryPrivacyGuardTest {

    @Test
    fun `an ordinary fact is allowed`() {
        assertFalse(MemoryPrivacyGuard.isDisallowed("mera developer hai"))
    }

    @Test
    fun `keyword matches are refused regardless of case`() {
        listOf(
            "the password is hunter2",
            "PASSWORD: hunter2",
            "his pin code is 4321",
            "your otp is 8842",
            "the cvv is 123",
            "iban PK36SCBL0000001123456702",
            "the account number is ABC123",
            "the card number is on the back",
            "his cnic is on file",
            "her national id is on file",
        ).forEach { fact ->
            assertTrue("expected \"$fact\" to be disallowed", MemoryPrivacyGuard.isDisallowed(fact))
        }
    }

    @Test
    fun `a grouped card-like digit run is refused`() {
        assertTrue(MemoryPrivacyGuard.isDisallowed("card is 4111 1111 1111 1111"))
        assertTrue(MemoryPrivacyGuard.isDisallowed("card is 4111-1111-1111-1111"))
    }

    @Test
    fun `an ungrouped long digit run is refused`() {
        assertTrue(MemoryPrivacyGuard.isDisallowed("his cnic number is 3520112345671"))
    }

    @Test
    fun `a short number is allowed`() {
        assertFalse(MemoryPrivacyGuard.isDisallowed("apartment number is 42"))
        assertFalse(MemoryPrivacyGuard.isDisallowed("born in 1998"))
    }

    @Test
    fun `an empty fact is allowed`() {
        assertFalse(MemoryPrivacyGuard.isDisallowed(""))
    }
}
