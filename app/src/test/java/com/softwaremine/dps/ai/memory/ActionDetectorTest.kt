package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.IntentAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verification of the create/update/cancel safety net (Day 05 Phase E).
 *
 * The property that matters most here is the negative one: "kar do" alone —
 * present in "yaad dila do" (remind me), an ordinary CREATE — must never flip
 * to UPDATE. A false positive here would misroute a brand-new request into
 * [com.softwaremine.dps.ai.intent.ToolSelector]'s update/cancel branch, which
 * requires a target id nothing in a fresh request supplies.
 */
class ActionDetectorTest {

    private val detector = ActionDetector()

    @Test
    fun `cancel keywords override the model's action`() {
        listOf(
            "cancel my reminder",
            "delete that reminder",
            "remove it please",
            "usko hata do",
            "reminder khatam kar do",
            "mita do please",
            "band kar do",
        ).forEach { text ->
            assertEquals("'$text'", IntentAction.CANCEL, detector.detect(text, IntentAction.CREATE))
        }
    }

    @Test
    fun `update keywords override the model's action`() {
        listOf(
            "reschedule the meeting",
            "uska reminder 30 minute pehle kar do",
            "pehle yaad dila do",
            "jaldi kar do",
            "meeting ko baad kar do",
            "waqt badal do",
            "time badal do",
            "date badal do",
            "move kar do",
            "shift kar do",
        ).forEach { text ->
            assertEquals("'$text'", IntentAction.UPDATE, detector.detect(text, IntentAction.CREATE))
        }
    }

    @Test
    fun `cancel takes priority when both cues somehow appear`() {
        assertEquals(
            IntentAction.CANCEL,
            detector.detect("cancel it, don't reschedule", IntentAction.CREATE),
        )
    }

    @Test
    fun `an ordinary create request is never reclassified`() {
        listOf(
            "remind me to call the bank at 4",
            "yaad dila do meeting ke liye",
            "Abdul ko WhatsApp kar do",
            "message Sara ke liye kar do",
            "set a reminder for tomorrow",
            "kal 4 baje meeting rakh do",
        ).forEach { text ->
            assertEquals("'$text'", IntentAction.CREATE, detector.detect(text, IntentAction.CREATE))
        }
    }

    @Test
    fun `the model's own action is trusted when no keyword cue is present`() {
        assertEquals(
            IntentAction.UPDATE,
            detector.detect("something with no special words", IntentAction.UPDATE),
        )
        assertEquals(
            IntentAction.CANCEL,
            detector.detect("something with no special words", IntentAction.CANCEL),
        )
    }

    @Test
    fun `detection is case and whitespace insensitive`() {
        assertEquals(IntentAction.CANCEL, detector.detect("  CANCEL the reminder  ", IntentAction.CREATE))
        assertEquals(IntentAction.UPDATE, detector.detect("RESCHEDULE it", IntentAction.CREATE))
    }
}
