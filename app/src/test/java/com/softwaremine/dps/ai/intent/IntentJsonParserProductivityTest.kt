package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verification that the Day 06 wire vocabulary round-trips through [IntentJsonParser]. */
class IntentJsonParserProductivityTest {

    private val parser = IntentJsonParser()

    @Test
    fun `parses the task intent type`() {
        val intent = parser.parse("""{"intent":"task","parameters":{"title":"DBPMS docs"}}""")

        assertEquals(IntentType.TASK, intent.type)
        assertEquals("DBPMS docs", intent.parameters.title)
    }

    @Test
    fun `parses complete and list action types`() {
        assertEquals(
            IntentAction.COMPLETE,
            parser.parse("""{"intent":"task","action_type":"complete"}""").action,
        )
        assertEquals(
            IntentAction.LIST,
            parser.parse("""{"intent":"task","action_type":"list"}""").action,
        )
    }

    @Test
    fun `parses duration and period fields`() {
        val workLog = parser.parse("""{"intent":"work_log","parameters":{"duration":"3 ghante"}}""")
        assertEquals("3 ghante", workLog.parameters.duration)

        val report = parser.parse("""{"intent":"report","parameters":{"period":"week"}}""")
        assertEquals("week", report.parameters.period)
    }

    @Test
    fun `parses every new Day 06 intent type`() {
        assertEquals(IntentType.WORK_LOG, parser.parse("""{"intent":"work_log"}""").type)
        assertEquals(IntentType.MEETING_NOTE, parser.parse("""{"intent":"meeting_note"}""").type)
        assertEquals(IntentType.ACTION_ITEM, parser.parse("""{"intent":"action_item"}""").type)
        assertEquals(IntentType.REPORT, parser.parse("""{"intent":"report"}""").type)
    }

    @Test
    fun `an unrecognised intent still falls back to conversation, never a guess`() {
        assertEquals(IntentType.CONVERSATION, parser.parse("""{"intent":"nonsense"}""").type)
    }
}
