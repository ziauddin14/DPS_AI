package com.softwaremine.dps.domain.contact

/**
 * Turns a name the user said into a specific person.
 *
 * ## Purpose
 * The shared matching component. Both the WhatsApp and Gmail tools need to go
 * from "Abdul" to a contact, and without a shared resolver each would grow its
 * own — differing in case sensitivity, in whether "abd" matches "Abdul", and in
 * what happens when two people match.
 *
 * Those differences are not cosmetic. They decide **who receives a private
 * message**, and two tools disagreeing about that is a defect the user
 * experiences as DPS messaging the wrong person from one path and the right one
 * from another.
 *
 * ## Why this is pure Kotlin in `domain/`
 * Matching is the part most worth testing exhaustively and has nothing to do
 * with Android. Keeping it here means every ranking rule below is verified on
 * the JVM, with no device and no provider — which matters on this project,
 * where the reference handset is the only hardware and emulators are excluded
 * (ADR-009).
 *
 * Reading contacts is the Android part, and it lives behind
 * [ContactRepository].
 *
 * ## Ranking
 * Candidates are scored by [MatchStrategy] and only the best tier is returned.
 * A query matching one person exactly and three others loosely is **not**
 * ambiguous — the exact match wins outright. Returning all four would make DPS
 * ask a needless question about an unambiguous request.
 *
 * ## Dependencies
 * [Contact], [ContactMatch]. Pure Kotlin.
 */
class ContactResolver {

    /**
     * Resolves [query] against [candidates].
     *
     * @param candidates contacts to consider, typically already narrowed by the
     *   provider's own filter.
     */
    fun resolveByName(query: String, candidates: List<Contact>): ContactMatch {
        val needle = query.trim()
        if (needle.isEmpty() || candidates.isEmpty()) return ContactMatch.None

        // Evaluate strongest-first and stop at the first tier that matches, so
        // an exact hit is never diluted by weaker ones.
        for (strategy in RANKED_STRATEGIES) {
            val tier = candidates.filter { matches(needle, it.displayName, strategy) }
            if (tier.isEmpty()) continue

            // Distinct by identity: the provider can return the same person
            // more than once when several of their data rows match, and
            // reporting that as ambiguity would ask the user to choose between
            // one person and themselves.
            val unique = tier.distinctBy { it.id }

            return if (unique.size == 1) {
                ContactMatch.Single(unique.first(), strategy)
            } else {
                ContactMatch.Ambiguous(unique.sortedBy { it.displayName }, strategy)
            }
        }

        return ContactMatch.None
    }

    /**
     * Resolves a phone number to a contact.
     *
     * Comparison is on digits alone: the same number is stored as
     * `+92 300 1234567`, `03001234567` and `0300-1234567` by different people
     * and different SIM imports, and treating those as different numbers would
     * fail to find a contact that is plainly present.
     */
    fun resolveByPhone(phoneNumber: String, candidates: List<Contact>): ContactMatch {
        val needle = normalisePhone(phoneNumber)
        if (needle.isEmpty() || candidates.isEmpty()) return ContactMatch.None

        val matched = candidates.filter { contact ->
            contact.phoneNumbers.any { phonesEqualEnough(normalisePhone(it), needle) }
        }.distinctBy { it.id }

        return when {
            matched.isEmpty() -> ContactMatch.None
            matched.size == 1 -> ContactMatch.Single(matched.first(), MatchStrategy.PHONE_NUMBER)
            else -> ContactMatch.Ambiguous(matched, MatchStrategy.PHONE_NUMBER)
        }
    }

    private fun matches(needle: String, displayName: String, strategy: MatchStrategy): Boolean =
        when (strategy) {
            MatchStrategy.EXACT -> displayName == needle
            MatchStrategy.CASE_INSENSITIVE -> displayName.trim().equals(needle, ignoreCase = true)
            MatchStrategy.STARTS_WITH -> displayName.trim().startsWith(needle, ignoreCase = true)
            MatchStrategy.CONTAINS -> displayName.contains(needle, ignoreCase = true)
            // Never reached: phone matching does not go through name tiers.
            MatchStrategy.PHONE_NUMBER -> false
        }

    /** Strips everything but digits, keeping a leading `+` if present. */
    private fun normalisePhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Compares two normalised numbers.
     *
     * ## Why trailing digits rather than a suffix test
     * A suffix test is the obvious implementation and is wrong. The same
     * subscriber is written `+923001234567` internationally and `03001234567`
     * locally: the trunk prefix `0` **replaces** the country code `92` rather
     * than being appended to it, so neither string ends with the other even
     * though they are the same person.
     *
     * Comparing the trailing [MIN_SIGNIFICANT_DIGITS] sidesteps that — country
     * codes and trunk prefixes live at the front, the subscriber number at the
     * back. This is the same approach the platform's own
     * `PhoneNumberUtils.compare` takes, and it is used here rather than that
     * API because this class is pure Kotlin and JVM-testable by design.
     *
     * ## Why a floor rather than any overlap
     * Both numbers must carry at least that many digits. Without the floor,
     * `4567` would match any number ending in those digits — and a private
     * message would reach a stranger.
     */
    private fun phonesEqualEnough(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        val digitsA = a.trimStart('+')
        val digitsB = b.trimStart('+')
        if (digitsA == digitsB) return true

        if (digitsA.length < MIN_SIGNIFICANT_DIGITS || digitsB.length < MIN_SIGNIFICANT_DIGITS) {
            return false
        }

        return digitsA.takeLast(MIN_SIGNIFICANT_DIGITS) == digitsB.takeLast(MIN_SIGNIFICANT_DIGITS)
    }

    private companion object {
        /** Name strategies in decreasing confidence. */
        val RANKED_STRATEGIES = listOf(
            MatchStrategy.EXACT,
            MatchStrategy.CASE_INSENSITIVE,
            MatchStrategy.STARTS_WITH,
            MatchStrategy.CONTAINS,
        )

        /**
         * Digits that must overlap before two numbers are treated as the same.
         *
         * Seven is the length of a local subscriber number in most plans — long
         * enough that a coincidental match is implausible, short enough to
         * still match across country-code and trunk-prefix differences.
         */
        const val MIN_SIGNIFICANT_DIGITS = 7
    }
}
