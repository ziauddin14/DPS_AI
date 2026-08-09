package com.softwaremine.dps.ai.plan

/** What a reply to a yes/no question meant. */
enum class Confirmation { YES, NO, UNCLEAR }

/**
 * Reads a yes/no answer to a question DPS itself just asked — a permission
 * rationale, a follow-up suggestion, or a destructive-action confirmation.
 *
 * ## Why this never calls the model
 * The question was templated by DPS a moment ago; the only thing left to
 * understand is whether the next three words mean yes or no. Spending an
 * inference pass on that would be exactly the "unnecessary LLM inference" the
 * Stage 2 brief calls out (requirement 12) — the same reasoning
 * [ContactSelectionParser] and [com.softwaremine.dps.ai.memory.ReferenceResolver]
 * already apply to their own bounded vocabularies.
 *
 * ## [Confirmation.UNCLEAR] is a real outcome, not an error
 * A reply that is neither a recognised yes nor no — a new, unrelated
 * request, say — must not be forced into either bucket. The caller decides
 * what UNCLEAR means for the state it was asked in (Stage 2's
 * [com.softwaremine.dps.ai.secretary.SecretaryOrchestrator] treats it as "ask
 * once more, plainly").
 *
 * ## Dependencies
 * None. Pure Kotlin, no Android, no model call.
 */
class ConfirmationParser {

    fun parse(reply: String): Confirmation {
        val normalized = reply.trim().lowercase()

        return when {
            YES_WORDS.any { normalized == it || normalized.startsWith("$it ") } -> Confirmation.YES
            NO_WORDS.any { normalized == it || normalized.startsWith("$it ") } -> Confirmation.NO
            else -> Confirmation.UNCLEAR
        }
    }

    private companion object {
        val YES_WORDS = setOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "please", "do it",
            "haan", "han", "ji", "ji haan", "theek hai", "thik hai", "bilkul",
        )

        // Deliberately not "cancel" or "stop" — both read equally naturally as
        // the start of a genuine request ("cancel the reminder"), and a false
        // NO here would silently swallow that request as a declined
        // suggestion instead of acting on it.
        val NO_WORDS = setOf(
            "no", "nope", "nah", "don't", "dont",
            "nahi", "nahin", "mat karo", "rehne do",
        )
    }
}
