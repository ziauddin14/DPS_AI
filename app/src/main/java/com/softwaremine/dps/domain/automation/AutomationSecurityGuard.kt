package com.softwaremine.dps.domain.automation

/**
 * A hard, code-enforced refusal for sensitive UI targets (M9) — mirrors
 * [com.softwaremine.dps.ai.memory.MemoryPrivacyGuard]'s own established
 * shape: a deny-list checked at the point of action, never merely trusted
 * to classification or softened into a confirmation.
 *
 * ## Refused, never merely confirmation-gated
 * A node matching this guard is refused outright — [com.softwaremine.dps.domain.secretary.RiskPolicy]
 * is never consulted for it, because there is no risk *level* that makes a
 * password/OTP/PIN/financial field acceptable to act on. This is a
 * security boundary, not a risk tier.
 *
 * ## Dependencies
 * None. Pure Kotlin.
 */
object AutomationSecurityGuard {

    /** `true` when [node] must never be tapped, typed into, or read for its value. */
    fun isSensitive(node: AutomationNode): Boolean {
        if (node.isPassword) return true

        val haystack = listOfNotNull(node.resourceId, node.contentDescription, node.text)
            .joinToString(" ")
            .lowercase()

        return SENSITIVE_KEYWORDS.any { haystack.contains(it) }
    }

    private val SENSITIVE_KEYWORDS = listOf(
        "password", "passwd", "otp", "one-time code", "one time code",
        "pin", "cvv", "cvc", "card number", "account number", "iban",
        "security code", "recovery code", "2fa", "two-factor",
    )
}
