package com.softwaremine.dps.domain.contact

/**
 * Reads contacts from the device.
 *
 * ## Purpose
 * Separates *finding* contacts from *choosing between* them
 * ([ContactResolver]). The provider narrows; the resolver ranks. Splitting them
 * keeps all ranking logic on the JVM-testable side, and means a future contacts
 * source — a work directory, a cached snapshot — plugs in without touching how
 * matching works.
 *
 * ## Why results are capped
 * A bare filter query against a large address book can return hundreds of rows,
 * and every one of them is personal data crossing into the app's memory.
 * Implementations cap results: reading the minimum needed to answer is both
 * faster and the privacy-correct default.
 *
 * ## Dependencies
 * [Contact] only. Pure Kotlin — the implementation is Android.
 */
interface ContactRepository {

    /** Outcome of a provider read. */
    sealed interface Outcome {
        /** Query succeeded. May be empty. */
        data class Found(val contacts: List<Contact>) : Outcome

        /** The device exposes no contacts provider. */
        data object NoProvider : Outcome

        /** The provider refused. Distinct from "found nothing". */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Contacts whose name or associated data matches [query].
     *
     * Deliberately broad — the provider's own filter is permissive and may
     * match on more than the display name. Narrowing is [ContactResolver]'s
     * job, and doing it here would duplicate ranking in two places.
     *
     * @param limit maximum contacts to return.
     */
    suspend fun searchByName(query: String, limit: Int = DEFAULT_LIMIT): Outcome

    /**
     * Contacts holding [phoneNumber].
     *
     * Implementations use the provider's reverse-lookup URI, which already
     * understands formatting differences between stored and queried numbers.
     */
    suspend fun searchByPhone(phoneNumber: String, limit: Int = DEFAULT_LIMIT): Outcome

    companion object {
        /**
         * Default cap on returned contacts.
         *
         * Ten is well past the point where asking a user to choose is useful —
         * beyond that the right response is to ask for a more specific name,
         * not to list more people.
         */
        const val DEFAULT_LIMIT = 10
    }
}
