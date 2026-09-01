package com.softwaremine.dps.ai.memory

/**
 * Refuses to let a semantic fact be stored if it looks like a credential or
 * a sensitive identifier (M6) — "passwords, bank details, sensitive
 * credentials" are already named as never-store categories in this
 * project's own architecture principles; this is the first place that rule
 * is actually enforced in code rather than only stated as policy.
 *
 * ## Why this runs at the persistence boundary, not only at extraction
 * [com.softwaremine.dps.ai.intent.IntentJsonParser]'s own classification
 * step could in principle be trusted to never extract a `REMEMBER_FACT` for
 * a sentence like "my card number is 4111 1111 1111 1111" — but trusting
 * that alone is exactly the single point of failure a "defense in depth"
 * posture avoids. This guard runs immediately before
 * [com.softwaremine.dps.data.android.memory.LongTermMemoryStore] ever calls
 * into Room, regardless of how the fact reached that point.
 *
 * ## Deliberately conservative, deterministic, no model involvement
 * A pattern/keyword check, not a judgment call — matching this codebase's
 * established style everywhere it needs to recognize a *closed* category
 * reliably ([com.softwaremine.dps.ai.memory.ReferenceResolver]'s own cue
 * lists are the same shape). A false positive here (refusing an
 * innocuous fact that merely contains a long number) is an acceptable,
 * safe failure mode; a false negative (storing a real credential) is not.
 */
object MemoryPrivacyGuard {

    /**
     * `true` when [factText] must not be persisted. Checked against the fact
     * content only — [subject] is a name, never itself a credential.
     */
    fun isDisallowed(factText: String): Boolean {
        val normalized = factText.lowercase()
        return DENY_KEYWORDS.any { normalized.contains(it) } ||
            CARD_NUMBER_PATTERN.containsMatchIn(factText) ||
            LONG_DIGIT_SEQUENCE_PATTERN.containsMatchIn(factText)
    }

    private val DENY_KEYWORDS = listOf(
        "password", "passwd", "pin code", "pincode", " otp", "otp ", "cvv", "cvc",
        "iban", "account number", "card number", "cnic", "national id",
    )

    /** A 13-19 digit run, optionally grouped by spaces/dashes in fours — the shape of a payment card number. */
    private val CARD_NUMBER_PATTERN = Regex("""\b(?:\d[ -]?){13,19}\b""")

    /** A bare 9-or-more-digit run with no separators — CNIC/account-number shaped. */
    private val LONG_DIGIT_SEQUENCE_PATTERN = Regex("""\d{9,}""")
}
