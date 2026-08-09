package com.softwaremine.dps.data.android.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic inside the Intent builders (Day 05 Phase C).
 *
 * ## Scope, and why it is split
 * Only the framework-free helpers are tested here. Actual `Intent` construction
 * is verified by `CommunicationToolsInstrumentedTest` on device.
 *
 * That split is forced rather than chosen: `android.net.Uri` and
 * `android.content.Intent` are **stubs** in the unit-test classpath and throw
 * `RuntimeException("Stub!")` when called. A JVM test of Intent construction
 * would therefore test nothing, or would require Robolectric — a substantial
 * dependency added to simulate a framework that a real device is already
 * providing two seconds away.
 *
 * The parts that decide *correctness* — how a phone number is normalised, what
 * counts as a plausible address — are pure, and they are covered here.
 */
class IntentBuilderLogicTest {

    // -----------------------------------------------------------------
    // WhatsApp number normalisation
    // -----------------------------------------------------------------

    /**
     * WhatsApp's `wa.me` links take bare digits. A number passed through with
     * punctuation produces a link that opens WhatsApp to nothing, which the
     * user experiences as the message having vanished.
     */
    @Test
    fun `whatsapp normalisation strips every non-digit`() {
        val cases = mapOf(
            "+92 300 1234567" to "923001234567",
            "0300-1234567" to "03001234567",
            "(0300) 123 4567" to "03001234567",
            "+1 (555) 010-9999" to "15550109999",
            "923001234567" to "923001234567",
        )

        cases.forEach { (input, expected) ->
            assertEquals("normalising '$input'", expected, WhatsAppIntentBuilder.normaliseForWhatsApp(input))
        }
    }

    @Test
    fun `whatsapp normalisation of a number with no digits yields empty`() {
        assertEquals("", WhatsAppIntentBuilder.normaliseForWhatsApp("not a number"))
        assertEquals("", WhatsAppIntentBuilder.normaliseForWhatsApp(""))
    }

    @Test
    fun `whatsapp package constants are the documented ones`() {
        assertEquals("com.whatsapp", WhatsAppIntentBuilder.PACKAGE_NAME)
        assertEquals("com.whatsapp.w4b", WhatsAppIntentBuilder.BUSINESS_PACKAGE_NAME)
    }

    // -----------------------------------------------------------------
    // Email address plausibility
    // -----------------------------------------------------------------

    /**
     * The job is catching a model that passed a *name* where an address
     * belongs, which would otherwise open a composer addressed to nothing.
     */
    @Test
    fun `plausible addresses are accepted`() {
        listOf(
            "a@b.co",
            "abdul@example.com",
            "first.last@sub.domain.org",
            "user+tag@example.co.uk",
            "UPPER@EXAMPLE.COM",
        ).forEach {
            assertTrue("'$it' should be accepted", EmailIntentBuilder.isPlausibleAddress(it))
        }
    }

    @Test
    fun `implausible addresses are rejected`() {
        listOf(
            "Abdul",                 // a name, the case this exists for
            "abdul@",                // no domain
            "@example.com",          // no local part
            "abdul@example",         // no dot in domain
            "abdul@@example.com",    // two separators
            "abdul example@x.com",   // whitespace
            "",
            "a@b",                   // too short and no dot
            "abdul@.com",            // domain starts with a dot
            "abdul@example.",        // domain ends with a dot
        ).forEach {
            assertTrue("'$it' should be rejected", !EmailIntentBuilder.isPlausibleAddress(it))
        }
    }

    /**
     * Deliberately permissive: full RFC 5322 validation rejects addresses that
     * are legal and in daily use, and the email app validates anyway.
     */
    @Test
    fun `validation is permissive rather than strict about unusual local parts`() {
        assertTrue(EmailIntentBuilder.isPlausibleAddress("very.unusual-but+legal@example.com"))
        assertTrue(EmailIntentBuilder.isPlausibleAddress("  padded@example.com  "))
    }
}
