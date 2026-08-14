package com.softwaremine.dps.ai.memory

/**
 * Confirms the model's `raw_when` quote is actually grounded in what the
 * user said, before [TemporalPhraseResolver] is trusted to resolve it
 * (Day 08-E follow-up).
 *
 * ## Why this exists
 * The Day 08-E real-device investigation found the classification model
 * reliably producing `raw_when="kal shaam 7 baje"` for messages that never
 * mentioned a time at all — "Buy milk ka task bana do" among them. A
 * controlled reproduction (fresh model reload before every call,
 * `cache_reused=0`, zero session history, confirmed via the exact prompt
 * text sent) ruled out KV-cache and prompt contamination: the phrase simply
 * is not present anywhere in the model's own input for that call.
 * [TemporalPhraseResolver] correctly resolves whatever `raw_when` it is
 * given — the defect is upstream of it, in trusting an ungrounded quote at
 * all. This class is that missing check.
 *
 * ## Matching strategy — why not exact substring matching
 * Every token in `raw_when`, once normalized, must appear among the user
 * text's own tokens. This is deliberately not a literal substring check: a
 * model that drops a Roman Urdu grammatical particle — "kal shaam **ko** 7
 * baje" spoken, `"kal shaam 7 baje"` extracted — is a legitimate, faithful
 * extraction and must still be accepted. The rule only requires that
 * `raw_when` introduce nothing the user never said; it does not require
 * reproducing their exact wording or word order.
 *
 * ## Fail closed
 * Any single token in `raw_when` that is absent from the user's own text
 * rejects the phrase as a whole — there is no partial credit, because a
 * half-grounded quote ("kal" genuinely said, "shaam 7 baje" invented) is
 * exactly as unsafe as a wholly invented one for resolving a real date/time.
 *
 * ## Dependencies
 * None. Deterministic string processing only — no Android, no model call,
 * like [ActionDetector] this never costs a token.
 */
class TemporalGroundingGuard {

    /**
     * `true` when every word in [rawWhen] appears among the words in
     * [userText] — order-independent, tolerant of dropped filler words, but
     * never of an invented one. A blank or empty [rawWhen] is never grounded.
     */
    fun isGrounded(userText: String, rawWhen: String): Boolean {
        val whenTokens = tokenize(rawWhen)
        if (whenTokens.isEmpty()) return false

        val userTokens = tokenize(userText)
        return whenTokens.all { it in userTokens }
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN_PATTERN.findAll(text.lowercase()).map { it.value }.toSet()

    private companion object {
        /** Punctuation and surrounding whitespace are never significant — only the words are. */
        val TOKEN_PATTERN = Regex("[a-z0-9]+")
    }
}
