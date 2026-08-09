package com.softwaremine.dps.domain.contact

import kotlinx.serialization.Serializable

/**
 * A person DPS can reach.
 *
 * ## Purpose
 * The domain's view of a contact — deliberately far smaller than what
 * `ContactsContract` exposes. DPS needs to identify someone and reach them; it
 * does not need addresses, photos, organisations or custom fields, and reading
 * them would mean touching more of the user's data than the task requires.
 *
 * That restraint is a privacy decision, not an oversight. `PRD.md` puts privacy
 * first, and the cheapest way to keep data safe is not to read it.
 *
 * ## Dependencies
 * kotlinx.serialization only. Pure Kotlin.
 */
@Serializable
data class Contact(
    /** Provider-assigned identifier. Stable for the lifetime of the contact. */
    val id: String,

    /** Name as the user has it stored. */
    val displayName: String,

    /**
     * Known phone numbers, in provider order.
     *
     * A list because a contact commonly has several — mobile, work, home — and
     * silently picking one would send a WhatsApp message to a landline.
     */
    val phoneNumbers: List<String> = emptyList(),

    /** Known email addresses. */
    val emailAddresses: List<String> = emptyList(),
) {
    /** Primary phone number, or `null` when none is stored. */
    val primaryPhone: String? get() = phoneNumbers.firstOrNull()

    /** Primary email address, or `null`. */
    val primaryEmail: String? get() = emailAddresses.firstOrNull()

    val hasPhone: Boolean get() = phoneNumbers.isNotEmpty()
    val hasEmail: Boolean get() = emailAddresses.isNotEmpty()
}

/**
 * The outcome of resolving a name to a person.
 *
 * ## Why this is not simply `Contact?`
 * Three outcomes need three different responses from the assistant, and a
 * nullable contact can only express two of them.
 *
 * The one that matters is [Ambiguous]. If the user says "message Ali" and three
 * contacts are named Ali, silently choosing the first is not a minor
 * inaccuracy — it sends a private message to the wrong person. That failure is
 * irreversible and is exactly the kind a secretary must never make, so
 * ambiguity is a first-class outcome that forces the caller to ask.
 *
 * ## Dependencies
 * [Contact]. Pure Kotlin.
 */
@Serializable
sealed interface ContactMatch {

    /** Exactly one plausible match. */
    @Serializable
    data class Single(
        val contact: Contact,
        val strategy: MatchStrategy,
    ) : ContactMatch

    /**
     * Several plausible matches. The caller must disambiguate with the user.
     *
     * Candidates are ordered best-first by [MatchStrategy].
     */
    @Serializable
    data class Ambiguous(
        val candidates: List<Contact>,
        val strategy: MatchStrategy,
    ) : ContactMatch

    /** Nobody matched. */
    @Serializable
    data object None : ContactMatch
}

/**
 * How a match was made.
 *
 * Ordered by decreasing confidence. Reported so the assistant can phrase itself
 * proportionately — "I found Abdul" for an exact match reads very differently
 * from "did you mean Abdul Rahman?" for a substring hit, and overstating
 * confidence is how the wrong person receives a message.
 */
@Serializable
enum class MatchStrategy {
    /** Identical, including case. */
    EXACT,

    /** Identical ignoring case and surrounding whitespace. */
    CASE_INSENSITIVE,

    /** The stored name begins with the query. "Abd" → "Abdul Rahman". */
    STARTS_WITH,

    /** The query appears somewhere in the name. Weakest signal. */
    CONTAINS,

    /** Matched by phone number rather than by name. */
    PHONE_NUMBER,
}
