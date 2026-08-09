package com.softwaremine.dps.ai.plan

import com.softwaremine.dps.domain.contact.Contact

/**
 * Interprets a reply to "which one?" against a remembered list of candidates.
 *
 * ## Why this never calls the model
 * The candidates are already known — resolving "the second one" against a
 * list of three names is a closed, deterministic problem, and spending a
 * classification pass on it would be exactly the "unnecessary LLM inference"
 * the Stage 2 brief singles out (requirement 12). This mirrors
 * [com.softwaremine.dps.ai.memory.ReferenceResolver]'s own justification for
 * staying out of the model's way wherever a bounded vocabulary suffices.
 *
 * ## What is recognised
 * A bare 1-based number ("2"), an English or Roman Urdu ordinal word ("second",
 * "doosra"), or a case-insensitive name match — exact first, then substring
 * (mirroring [com.softwaremine.dps.domain.contact.ContactResolver]'s own
 * ranked-strategy approach so a partial name like "Rauf" still resolves when
 * it identifies exactly one candidate). Anything that resolves to more than
 * one candidate, or to none, is reported as unresolved rather than guessed —
 * the same rule [candidates] itself exists to enforce.
 *
 * ## Dependencies
 * [Contact]. Pure Kotlin, no Android, no model call.
 */
class ContactSelectionParser {

    /**
     * Resolves [reply] against [candidates], or `null` when it could not be
     * understood as choosing exactly one of them.
     */
    fun parse(reply: String, candidates: List<Contact>): Contact? {
        val normalized = reply.trim().lowercase()
        if (normalized.isEmpty() || candidates.isEmpty()) return null

        indexWord(normalized)?.let { index -> return candidates.getOrNull(index) }

        val exact = candidates.filter { it.displayName.equals(reply.trim(), ignoreCase = true) }
        if (exact.size == 1) return exact.single()

        val contains = candidates.filter { it.displayName.contains(reply.trim(), ignoreCase = true) }
        if (contains.size == 1) return contains.single()

        return null
    }

    /** A 0-based index from a bare number or an ordinal word, or `null`. */
    private fun indexWord(normalized: String): Int? {
        normalized.toIntOrNull()?.let { number -> if (number >= 1) return number - 1 }
        return ORDINALS[normalized]
    }

    private companion object {
        /** 0-based. English and Roman Urdu, first through fifth — a candidate list longer than that needs a better UI, not more words here. */
        val ORDINALS = mapOf(
            "first" to 0, "1st" to 0, "pehla" to 0, "pehli" to 0,
            "second" to 1, "2nd" to 1, "doosra" to 1, "dusra" to 1, "doosri" to 1,
            "third" to 2, "3rd" to 2, "teesra" to 2, "tisra" to 2, "teesri" to 2,
            "fourth" to 3, "4th" to 3, "chautha" to 3, "chothi" to 3,
            "fifth" to 4, "5th" to 4, "paanchwa" to 4, "panjwa" to 4,
        )
    }
}

/**
 * Reconstructs the candidate list [com.softwaremine.dps.data.android.tool.AndroidContactsTool]
 * flattened into [com.softwaremine.dps.domain.tool.ToolResult.Success.data]
 * when a name matched more than one contact.
 *
 * ## Why this lives here and not on `Contact` or the tool itself
 * It is the inverse of `AndroidContactsTool.flatten()` — reconstructing a
 * domain type from a wire-shaped map is orchestration-layer work, not
 * something either the pure domain type or the Android tool should know how
 * to do for a caller three layers away.
 *
 * Returns an empty list if `data` does not carry the expected keys, rather
 * than throwing — malformed data here must resolve to "nothing to choose
 * from" (which the caller then reports honestly), not a crash.
 */
fun contactCandidatesFrom(data: Map<String, String>): List<Contact> {
    val count = data["count"]?.toIntOrNull() ?: return emptyList()
    return (0 until count).mapNotNull { index ->
        val id = data["contact_${index}_id"] ?: return@mapNotNull null
        val name = data["contact_${index}_name"] ?: return@mapNotNull null
        Contact(
            id = id,
            displayName = name,
            phoneNumbers = listOfNotNull(data["contact_${index}_phone"]),
            emailAddresses = listOfNotNull(data["contact_${index}_email"]),
        )
    }
}
