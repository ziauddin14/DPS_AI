package com.softwaremine.dps.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verification of the M6 default-lead-time recognizer.
 *
 * ## The invariant these tests exist to hold
 * A plain one-off "remind me 15 minutes before" must never be mistaken for a
 * request to change the *default* — only an explicit "hamesha"/"by
 * default"/"always" cue may write
 * [com.softwaremine.dps.domain.preferences.UserPreferences.defaultReminderLeadMinutes].
 * See [PreferenceStatementRecognizer]'s own doc for why.
 */
class PreferenceStatementRecognizerTest {

    private val resolver = ReferenceResolver()

    @Test
    fun `an explicit hamesha statement is recognized`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "hamesha 15 minute pehle reminder dena",
            resolver,
        )

        assertEquals(15, minutes)
    }

    @Test
    fun `an explicit always statement is recognized`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "always remind me 30 minutes before",
            resolver,
        )

        assertEquals(30, minutes)
    }

    @Test
    fun `an explicit by default statement in hours is converted to minutes`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "by default remind me 1 hour before",
            resolver,
        )

        assertEquals(60, minutes)
    }

    @Test
    fun `a plain one-off lead time with no default cue is left alone`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "remind me 15 minutes before the meeting",
            resolver,
        )

        assertNull(minutes)
    }

    @Test
    fun `a default cue with an after-offset is not a lead time`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "hamesha 10 minutes baad remind karna",
            resolver,
        )

        assertNull(minutes)
    }

    @Test
    fun `a default cue with no offset phrase at all is left alone`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "hamesha reminder set karo",
            resolver,
        )

        assertNull(minutes)
    }

    @Test
    fun `neither a cue nor an offset returns null`() {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(
            "kal ka schedule dikhao",
            resolver,
        )

        assertNull(minutes)
    }
}
