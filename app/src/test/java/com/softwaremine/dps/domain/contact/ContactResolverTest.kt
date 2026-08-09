package com.softwaremine.dps.domain.contact

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact matching (Day 05 Phase C).
 *
 * ## Why this is tested exhaustively
 * This class decides **who receives a private message**. A wrong answer is not
 * a degraded result — it sends someone's words to a person they did not choose,
 * and that cannot be undone.
 *
 * Pure Kotlin, so every rule is verified on the JVM with no device.
 */
class ContactResolverTest {

    private val resolver = ContactResolver()

    private fun contact(id: String, name: String, vararg phones: String) =
        Contact(id = id, displayName = name, phoneNumbers = phones.toList())

    private val abdul = contact("1", "Abdul", "+923001234567")
    private val abdulRahman = contact("2", "Abdul Rahman", "+923009999999")
    private val fatima = contact("3", "Fatima Khan", "+923005555555")

    // -----------------------------------------------------------------
    // Ranking
    // -----------------------------------------------------------------

    /**
     * The central rule: an exact match wins outright.
     *
     * "Abdul" also *starts* "Abdul Rahman". Returning both would make DPS ask a
     * needless question about an unambiguous request.
     */
    @Test
    fun `exact match wins over weaker matches on the same query`() {
        val match = resolver.resolveByName("Abdul", listOf(abdul, abdulRahman))

        assertTrue("Expected Single, got $match", match is ContactMatch.Single)
        match as ContactMatch.Single
        assertEquals(abdul, match.contact)
        assertEquals(MatchStrategy.EXACT, match.strategy)
    }

    @Test
    fun `case-insensitive match is found and reported as such`() {
        val match = resolver.resolveByName("abdul", listOf(abdul, fatima))

        assertTrue(match is ContactMatch.Single)
        assertEquals(MatchStrategy.CASE_INSENSITIVE, (match as ContactMatch.Single).strategy)
    }

    @Test
    fun `startsWith matches a prefix`() {
        val match = resolver.resolveByName("Fat", listOf(abdul, fatima))

        assertTrue(match is ContactMatch.Single)
        assertEquals(fatima, (match as ContactMatch.Single).contact)
        assertEquals(MatchStrategy.STARTS_WITH, match.strategy)
    }

    @Test
    fun `contains matches a substring when nothing stronger does`() {
        val match = resolver.resolveByName("Khan", listOf(abdul, fatima))

        assertTrue(match is ContactMatch.Single)
        assertEquals(MatchStrategy.CONTAINS, (match as ContactMatch.Single).strategy)
    }

    @Test
    fun `surrounding whitespace does not defeat matching`() {
        val match = resolver.resolveByName("  Abdul  ", listOf(abdul))
        assertTrue(match is ContactMatch.Single)
    }

    // -----------------------------------------------------------------
    // Ambiguity
    // -----------------------------------------------------------------

    /** Two people equally matched must never be silently narrowed to one. */
    @Test
    fun `several equal matches are reported as ambiguous`() {
        val aliRaza = contact("4", "Ali Raza", "+923001111111")
        val aliHassan = contact("5", "Ali Hassan", "+923002222222")

        val match = resolver.resolveByName("Ali", listOf(aliRaza, aliHassan))

        assertTrue("Expected Ambiguous, got $match", match is ContactMatch.Ambiguous)
        assertEquals(2, (match as ContactMatch.Ambiguous).candidates.size)
        assertEquals(MatchStrategy.STARTS_WITH, match.strategy)
    }

    @Test
    fun `ambiguous candidates are ordered predictably`() {
        val zain = contact("6", "Zain Ali")
        val amir = contact("7", "Amir Ali")

        val match = resolver.resolveByName("Ali", listOf(zain, amir)) as ContactMatch.Ambiguous

        assertEquals(listOf("Amir Ali", "Zain Ali"), match.candidates.map { it.displayName })
    }

    /**
     * A provider can return the same person on several rows when multiple data
     * fields match. Reporting that as ambiguity would ask the user to choose
     * between one person and themselves.
     */
    @Test
    fun `duplicate rows for one person do not create false ambiguity`() {
        val match = resolver.resolveByName("Abdul", listOf(abdul, abdul.copy()))

        assertTrue("Expected Single, got $match", match is ContactMatch.Single)
    }

    // -----------------------------------------------------------------
    // No match
    // -----------------------------------------------------------------

    @Test
    fun `no match returns None`() {
        assertEquals(ContactMatch.None, resolver.resolveByName("Nobody", listOf(abdul, fatima)))
    }

    @Test
    fun `empty query and empty candidate list both return None`() {
        assertEquals(ContactMatch.None, resolver.resolveByName("", listOf(abdul)))
        assertEquals(ContactMatch.None, resolver.resolveByName("   ", listOf(abdul)))
        assertEquals(ContactMatch.None, resolver.resolveByName("Abdul", emptyList()))
    }

    // -----------------------------------------------------------------
    // Phone matching
    // -----------------------------------------------------------------

    @Test
    fun `phone matches exactly`() {
        val match = resolver.resolveByPhone("+923001234567", listOf(abdul, fatima))

        assertTrue(match is ContactMatch.Single)
        assertEquals(abdul, (match as ContactMatch.Single).contact)
        assertEquals(MatchStrategy.PHONE_NUMBER, match.strategy)
    }

    /**
     * The same number is stored many ways. Treating those as different numbers
     * would fail to find a contact that is plainly present.
     */
    @Test
    fun `formatting differences do not defeat phone matching`() {
        listOf("+92 300 1234567", "0300-1234567", "(0300) 123 4567", "923001234567").forEach { form ->
            val match = resolver.resolveByPhone(form, listOf(abdul))
            assertTrue("Form '$form' should match", match is ContactMatch.Single)
        }
    }

    /**
     * The overlap floor exists so two unrelated numbers sharing a few trailing
     * digits are not treated as the same person.
     */
    @Test
    fun `short digit overlap does not produce a false phone match`() {
        val match = resolver.resolveByPhone("4567", listOf(abdul))
        assertEquals(ContactMatch.None, match)
    }

    @Test
    fun `unknown number returns None`() {
        assertEquals(
            ContactMatch.None,
            resolver.resolveByPhone("+15550000000", listOf(abdul, fatima)),
        )
    }

    @Test
    fun `two contacts sharing a number are reported as ambiguous`() {
        val shared = contact("8", "Shared Line", "+923001234567")

        val match = resolver.resolveByPhone("+923001234567", listOf(abdul, shared))

        assertTrue(match is ContactMatch.Ambiguous)
        assertEquals(2, (match as ContactMatch.Ambiguous).candidates.size)
    }

    // -----------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------

    @Test
    fun `contact and match types survive a serialization round trip`() {
        val json = Json { ignoreUnknownKeys = true }

        val samples = listOf<ContactMatch>(
            ContactMatch.Single(abdul, MatchStrategy.EXACT),
            ContactMatch.Ambiguous(listOf(abdul, abdulRahman), MatchStrategy.STARTS_WITH),
            ContactMatch.None,
        )

        samples.forEach { original ->
            val encoded = json.encodeToString(ContactMatch.serializer(), original)
            assertEquals(original, json.decodeFromString(ContactMatch.serializer(), encoded))
        }
    }

    @Test
    fun `contact convenience accessors reflect stored data`() {
        assertEquals("+923001234567", abdul.primaryPhone)
        assertTrue(abdul.hasPhone)
        assertTrue(!abdul.hasEmail)

        val emailOnly = Contact("9", "Email Only", emailAddresses = listOf("a@b.com"))
        assertEquals("a@b.com", emailOnly.primaryEmail)
        assertTrue(!emailOnly.hasPhone)
    }
}
