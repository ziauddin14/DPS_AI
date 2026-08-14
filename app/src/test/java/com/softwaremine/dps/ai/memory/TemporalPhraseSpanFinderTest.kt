package com.softwaremine.dps.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of [TemporalPhraseSpanFinder] (Day 08-E multi-step follow-up).
 *
 * ## What this proves
 * That the finder locates genuine temporal phrases — and only genuine
 * ones — as whole, non-fragmented spans, using exactly the vocabulary
 * [TemporalPhraseResolver] already knows, without ever deciding which step
 * a span belongs to (that is [TemporalStepAttributor]'s job).
 */
class TemporalPhraseSpanFinderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val fixedNow = LocalDateTime.of(2026, 8, 11, 9, 0)
    private val resolver = TemporalPhraseResolver(zone = zone, now = { fixedNow })
    private val finder = TemporalPhraseSpanFinder(resolver = resolver)

    @Test
    fun `finds a single multi-word phrase as one span, not fragments`() {
        val spans = finder.findSpans("kal shaam 7 baje reminder laga do")

        assertEquals(1, spans.size)
        assertEquals("kal shaam 7 baje", spans.single().text)
    }

    @Test
    fun `finds two distinct phrases in one message, in order`() {
        val spans = finder.findSpans("kal shaam 7 baje reminder laga do aur kal raat 11 baje doosra reminder laga do")

        assertEquals(2, spans.size)
        assertEquals("kal shaam 7 baje", spans[0].text)
        assertEquals("kal raat 11 baje", spans[1].text)
    }

    @Test
    fun `finds nothing in a message with no temporal phrase`() {
        assertTrue(finder.findSpans("meeting follow-up ka task bana do aur milk ka task bana do").isEmpty())
    }

    @Test
    fun `finds a bare day word on its own`() {
        val spans = finder.findSpans("kal reminder lagao")

        assertEquals(1, spans.size)
        assertEquals("kal", spans.single().text)
    }

    @Test
    fun `finds an absolute date phrase`() {
        val spans = finder.findSpans("20 August ko reminder lagao")

        assertEquals(1, spans.size)
        assertEquals("20 august", spans.single().text)
    }

    @Test
    fun `a dropped filler word still finds the phrase when the particle is present in the message`() {
        val spans = finder.findSpans("kal shaam ko 7 baje reminder lagao")

        assertEquals(1, spans.size)
        // The particle sits *inside* the window; TemporalPhraseResolver
        // already tolerates it via its own word-boundary matching, so the
        // finder's window naturally includes it rather than splitting.
        assertEquals("kal shaam ko 7 baje", spans.single().text)
    }

    @Test
    fun `a bare hour with no period word is never found as a span`() {
        // TemporalPhraseResolver correctly refuses to resolve this — the
        // finder must not find a span for something that never resolves.
        assertTrue(finder.findSpans("4 baje reminder lagao").isEmpty())
    }

    @Test
    fun `punctuation does not prevent a phrase from being found`() {
        val spans = finder.findSpans("Kal, shaam 7 baje, reminder laga do.")

        assertEquals(1, spans.size)
        assertEquals("kal shaam 7 baje", spans.single().text)
    }

    @Test
    fun `an empty message finds nothing`() {
        assertTrue(finder.findSpans("").isEmpty())
    }

    @Test
    fun `gibberish never throws and finds nothing`() {
        // "baje" with no digit before it never matches BAJE_PATTERN.
        assertTrue(finder.findSpans("###!!! asdkjfh baje baje baje").isEmpty())
    }

    // -----------------------------------------------------------------
    // Case 5 regression — the exact real-device fragmentation failure
    //
    // A genuine phrase trailing after unrelated words, with no conjunction
    // between them, was previously split into "task bana do kal" plus
    // "shaam 7 baje" — two fragments, neither the real phrase. The trim
    // rule closes this without any word list.
    // -----------------------------------------------------------------

    @Test
    fun `CASE 5 REGRESSION - a trailing phrase after unrelated words is not fragmented`() {
        val spans = finder.findSpans("Buy milk ka task bana do kal shaam 7 baje.")

        assertEquals(1, spans.size)
        assertEquals("kal shaam 7 baje", spans.single().text)
    }

    // -----------------------------------------------------------------
    // Required boundary cases (Day 08-E span-finder redesign)
    // -----------------------------------------------------------------

    @Test
    fun `1 - a phrase trailing after unrelated words resolves cleanly`() {
        assertEquals("kal shaam 7 baje", finder.findSpans("Buy milk ka task bana do kal shaam 7 baje").single().text)
    }

    @Test
    fun `2 - a phrase trailing after a different unrelated clause resolves cleanly`() {
        assertEquals("kal shaam 7 baje", finder.findSpans("Ali ko call karna hai kal shaam 7 baje").single().text)
    }

    @Test
    fun `3 - a night phrase trailing after unrelated words resolves cleanly`() {
        assertEquals("kal raat 11 baje", finder.findSpans("meeting hai kal raat 11 baje").single().text)
    }

    @Test
    fun `4 - an absolute date leading a clause resolves cleanly, excluding the trailing particle`() {
        assertEquals("20 august", finder.findSpans("20 August ko meeting hai").single().text)
    }

    @Test
    fun `5 - kal shaam ko 7 baje is kept whole, filler preserved`() {
        assertEquals("kal shaam ko 7 baje", finder.findSpans("kal shaam ko 7 baje").single().text)
    }

    @Test
    fun `6 - kal raat ko 11 baje is kept whole, filler preserved`() {
        assertEquals("kal raat ko 11 baje", finder.findSpans("kal raat ko 11 baje").single().text)
    }

    @Test
    fun `7 - two independent phrases separated by unrelated text remain independent`() {
        val spans = finder.findSpans("kal shaam 7 baje reminder laga do aur milk ka kaam kal raat 11 baje karna hai")

        assertEquals(2, spans.size)
        assertEquals("kal shaam 7 baje", spans[0].text)
        assertEquals("kal raat 11 baje", spans[1].text)
    }

    @Test
    fun `8 - an identical phrase occurring twice is found as two independent occurrences`() {
        val spans = finder.findSpans("kal shaam 7 baje reminder ek laga do aur kal shaam 7 baje doosra laga do")

        assertEquals(2, spans.size)
        assertEquals("kal shaam 7 baje", spans[0].text)
        assertEquals("kal shaam 7 baje", spans[1].text)
    }

    @Test
    fun `9 - a message with only unrelated words finds no temporal span`() {
        assertTrue(finder.findSpans("Buy milk ka task bana do").isEmpty())
    }

    @Test
    fun `10 - a trailing colon time after unrelated words is found cleanly`() {
        assertEquals("16:00", finder.findSpans("Buy milk ka task bana do 16:00").single().text)
    }

    // -----------------------------------------------------------------
    // Case 9 regression — an absolute date and a later, unrelated
    // day-word phrase are two independent spans, not one.
    //
    // TemporalPhraseResolver.resolveDate() checks DAY_WORDS before
    // parseAbsoluteDate, so resolving a window that spans BOTH phrases at
    // once always reports the day-word's date. The finder must not let
    // that one-call precedence swallow the earlier, genuinely separate
    // absolute-date phrase.
    // -----------------------------------------------------------------

    @Test
    fun `CASE 9 REGRESSION - an absolute date followed later by a different day-word phrase is found as two independent spans`() {
        val spans = finder.findSpans(
            "20 August ko Ali ko call karne ka reminder laga do aur kal shaam 7 baje Buy milk ka task bana do.",
        )

        assertEquals(2, spans.size)
        assertEquals("20 august", spans[0].text)
        assertEquals("kal shaam 7 baje", spans[1].text)
    }

    @Test
    fun `11 - an absolute date followed by a relative phrase with unrelated text between them resolves as two independent spans`() {
        val spans = finder.findSpans("20 August ko meeting hai aur kal shaam 7 baje reminder bhi lagao")

        assertEquals(2, spans.size)
        assertEquals("20 august", spans[0].text)
        assertEquals("kal shaam 7 baje", spans[1].text)
    }

    @Test
    fun `12 - a relative phrase followed by an absolute date resolves as two independent spans`() {
        val spans = finder.findSpans("kal shaam 7 baje reminder lagao aur 20 August ko meeting bhi hai")

        assertEquals(2, spans.size)
        assertEquals("kal shaam 7 baje", spans[0].text)
        assertEquals("20 august", spans[1].text)
    }
}
