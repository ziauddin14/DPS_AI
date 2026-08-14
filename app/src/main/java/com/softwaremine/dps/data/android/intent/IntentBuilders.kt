package com.softwaremine.dps.data.android.intent

import android.content.Intent
import android.net.Uri

/**
 * Builds a WhatsApp conversation Intent.
 *
 * ## Purpose
 * Construction only. No contact lookup, no permission checks, no launching —
 * those belong to the tool and to [IntentLauncher]. Keeping this class free of
 * decisions is what makes it verifiable on the JVM against a plain `Intent`.
 *
 * ## ⚠ Source of the URL format — stated plainly
 * The `https://wa.me/<number>?text=<encoded>` format is published by **WhatsApp**,
 * not by Android. It is vendor documentation, and WhatsApp can change it.
 *
 * The Android half *is* documented: an `ACTION_VIEW` Intent carrying an `https`
 * URI, with availability determined by `resolveActivity` rather than assumed.
 * If WhatsApp ever stops claiming these links, `resolveActivity` returns null
 * and the tool reports the app as unavailable — it degrades to an honest
 * "can't do that" rather than to a crash or a message sent nowhere.
 *
 * ## Why `wa.me` rather than the `whatsapp://` scheme
 * `wa.me` is an `https` link WhatsApp registers as a deep link. Two consequences
 * matter: package visibility can be declared for it with a standard `https`
 * intent filter, and on a device without WhatsApp it degrades to a web page
 * instead of failing to resolve at all.
 *
 * ## Number format
 * WhatsApp requires digits only — no `+`, spaces, or punctuation. A number
 * passed through unnormalised produces a link that opens WhatsApp to nothing,
 * which looks to the user like the message vanished.
 *
 * ## Dependencies
 * `android.content.Intent`, `android.net.Uri`.
 */
object WhatsAppIntentBuilder {

    /** WhatsApp's package name, used for visibility declaration and diagnostics. */
    const val PACKAGE_NAME = "com.whatsapp"

    /** WhatsApp Business, which registers the same links. */
    const val BUSINESS_PACKAGE_NAME = "com.whatsapp.w4b"

    /**
     * Builds an Intent opening a WhatsApp conversation with [message] pre-filled.
     *
     * The message is **not sent**. WhatsApp opens the chat with the text in the
     * input field and the user presses send — which is the confirmation step
     * `development_rule.md` Rule 8 requires and which this design exists to
     * guarantee rather than merely intend.
     *
     * @param phoneNumber any format; normalised here.
     * @param message pre-filled text. May be blank to open the chat only.
     */
    fun buildConversationIntent(phoneNumber: String, message: String): Intent {
        val digits = normaliseForWhatsApp(phoneNumber)

        val uri = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(digits)
            // appendQueryParameter encodes the value, so a message containing
            // '&' or '#' cannot truncate or corrupt the link.
            .apply { if (message.isNotBlank()) appendQueryParameter("text", message) }
            .build()

        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Strips a number to the digits WhatsApp expects.
     *
     * A leading `+` is removed along with all punctuation: `wa.me` takes the
     * international number as bare digits.
     */
    fun normaliseForWhatsApp(phoneNumber: String): String =
        phoneNumber.filter { it.isDigit() }
}

/**
 * Builds an email composition Intent.
 *
 * ## Purpose
 * Construction only, for the same reasons as [WhatsAppIntentBuilder].
 *
 * ## APIs used — verified against official documentation
 * | Choice | Reason |
 * |---|---|
 * | `Intent.ACTION_SENDTO` | **only email apps** respond |
 * | `mailto:` URI | the scheme that produces that filtering |
 * | `Intent.EXTRA_EMAIL` | recipients, as a `String[]` |
 * | `Intent.EXTRA_SUBJECT` | subject |
 * | `Intent.EXTRA_TEXT` | body |
 *
 * `ACTION_SEND` is deliberately **not** used. Documentation is explicit that it
 * is answered by messaging and social apps as well as email clients, so a user
 * asking DPS to email someone would be offered a share sheet full of
 * unrelated apps — and could send private correspondence to the wrong medium
 * entirely.
 *
 * ## Nothing is sent
 * This opens a composer. The user reviews and sends. Rule 8 again, and the same
 * reasoning as WhatsApp: an email sent without the user seeing it is not
 * recoverable.
 *
 * ## Dependencies
 * `android.content.Intent`, `android.net.Uri`.
 */
object EmailIntentBuilder {

    /**
     * Builds an Intent opening an email composer.
     *
     * @param recipients one or more addresses. May be empty for a blank composer.
     */
    fun buildComposeIntent(
        recipients: List<String>,
        subject: String?,
        body: String?,
    ): Intent = Intent(Intent.ACTION_SENDTO).apply {
        // The bare mailto: scheme is what restricts resolution to email apps.
        // Recipients travel in EXTRA_EMAIL rather than in the URI so that
        // addresses needing encoding cannot malform it.
        data = Uri.parse("mailto:")

        if (recipients.isNotEmpty()) {
            putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
        }
        subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        body?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
    }

    /**
     * Whether [address] is plausibly an email address.
     *
     * Deliberately permissive. Full RFC 5322 validation rejects addresses that
     * are legal and in use, and the email app performs its own validation
     * anyway; the only job here is to catch a model emitting something that is
     * obviously not an address, such as a contact's name.
     */
    fun isPlausibleAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.length < MIN_ADDRESS_LENGTH || trimmed.any { it.isWhitespace() }) return false

        val at = trimmed.indexOf('@')
        if (at <= 0 || at != trimmed.lastIndexOf('@')) return false

        val domain = trimmed.substring(at + 1)
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }

    /** `a@b.co` is the shortest realistic address. */
    private const val MIN_ADDRESS_LENGTH = 6
}

/**
 * Builds an Intent that opens the system dialer. **Never dials.**
 *
 * ## `ACTION_DIAL`, never `ACTION_CALL` — stated plainly
 * `ACTION_DIAL` opens the dialer app with the number pre-filled; the user
 * still has to press the call button themselves. `ACTION_CALL` places the
 * call immediately and requires the dangerous `CALL_PHONE` runtime
 * permission. This codebase deliberately has no code path that places a
 * call — the same "the user presses the final button" guarantee
 * [WhatsAppIntentBuilder] and [EmailIntentBuilder] already give, and for the
 * same reason: a call placed on someone's behalf cannot be recalled.
 *
 * ## Number format
 * Passed through mostly as-is — `tel:` URIs conventionally accept `+`,
 * spaces, hyphens and parentheses, unlike `wa.me`'s digits-only requirement.
 * [Uri.encode] only protects the URI's own syntax; it does not alter the
 * number a user or contact record actually has.
 *
 * ## Dependencies
 * `android.content.Intent`, `android.net.Uri`.
 */
object DialIntentBuilder {

    /**
     * Builds an Intent opening the dialer with [phoneNumber] pre-filled.
     *
     * Nothing is dialed. The dialer opens and the user presses call.
     */
    fun build(phoneNumber: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber.trim())}"))
}
