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

    /**
     * CAT5 (Calendar Delete/Update Classification Reliability, Day 09
     * follow-up): the model reliably classified "Move that meeting to
     * tomorrow evening." as reminder/create — see the investigation's raw
     * evidence — because bare "move" matched nothing here. These are the
     * anchored phrases that now close that gap.
     */
    @Test
    fun `anchored move and change phrases override the model's action`() {
        listOf(
            "Move that meeting to tomorrow evening.",
            "move the meeting to 11am",
            "move that event",
            "move the event to next week",
            "move that reminder",
            "move the reminder to 5pm",
            "change that meeting to tomorrow evening",
            "change the meeting time",
            "change that event",
            "change the event to Friday",
            "change that reminder",
            "change the reminder time",
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

    /**
     * The false-positive guard the CAT5 fix's own design explicitly requires:
     * "move"/"change" are common enough as ordinary CREATE *content* that a
     * bare-verb match would misroute a brand-new request — exactly the
     * failure the anchored phrasing (see the "anchored move and change"
     * test above) exists to avoid. None of these contain an anchored phrase.
     */
    @Test
    fun `move and change are never reclassified when unanchored to an existing meeting, event or reminder`() {
        listOf(
            "remind me to move the couch this weekend",
            "remind me to change the oil in my car",
            "set a reminder to update my resume",
            "yaad dila do ke saman move karna hai",
            "remind me to change my flight booking",
            "create a task to move the boxes",
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
