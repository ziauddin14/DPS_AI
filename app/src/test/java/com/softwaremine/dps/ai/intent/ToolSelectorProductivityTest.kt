package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.tool.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verification of the Day 06 productivity branches of [ToolSelector].
 *
 * Mirrors [ToolSelectorTest]'s own style and clock-pinning rationale.
 */
class ToolSelectorProductivityTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val fixedNow: LocalDateTime = LocalDateTime.of(2026, 8, 6, 14, 30)
    private val selector = ToolSelector(zone = zone, now = { fixedNow })

    private fun intent(type: IntentType, parameters: IntentParameters, action: IntentAction = IntentAction.CREATE) =
        DpsIntent(type = type, parameters = parameters, action = action)

    // -----------------------------------------------------------------
    // Task
    // -----------------------------------------------------------------

    @Test
    fun `creating a task routes to create_task with title`() {
        val call = selector.select(intent(IntentType.TASK, IntentParameters(title = "DBPMS documentation")))!!

        assertEquals(ToolId.TASK, call.toolId)
        assertEquals("create_task", call.operation)
        assertEquals("DBPMS documentation", call.arguments["title"])
    }

    @Test
    fun `listing tasks needs no arguments`() {
        val call = selector.select(intent(IntentType.TASK, IntentParameters(), action = IntentAction.LIST))!!

        assertEquals("list_tasks", call.operation)
    }

    @Test
    fun `completing a task by title passes title, not just id`() {
        val call = selector.select(
            intent(IntentType.TASK, IntentParameters(title = "DBPMS"), action = IntentAction.COMPLETE),
        )!!

        assertEquals("complete_task", call.operation)
        assertEquals("DBPMS", call.arguments["title"])
        assertNull(call.arguments["id"])
    }

    @Test
    fun `completing a task by resolved id passes id`() {
        val call = selector.select(
            intent(IntentType.TASK, IntentParameters(targetId = "42"), action = IntentAction.COMPLETE),
        )!!

        assertEquals("42", call.arguments["id"])
    }

    @Test
    fun `cancelling a task routes to cancel_task`() {
        val call = selector.select(
            intent(IntentType.TASK, IntentParameters(targetId = "7"), action = IntentAction.CANCEL),
        )!!

        assertEquals("cancel_task", call.operation)
        assertEquals("7", call.arguments["id"])
    }

    @Test
    fun `updating a task addresses by id and carries the new title separately`() {
        val call = selector.select(
            intent(
                IntentType.TASK,
                IntentParameters(targetId = "5", title = "renamed title"),
                action = IntentAction.UPDATE,
            ),
        )!!

        assertEquals("update_task", call.operation)
        assertEquals("5", call.arguments["id"])
        assertEquals("renamed title", call.arguments["title"])
    }

    // -----------------------------------------------------------------
    // Work log
    // -----------------------------------------------------------------

    @Test
    fun `a work log with an activity and no date defaults to today`() {
        val call = selector.select(intent(IntentType.WORK_LOG, IntentParameters(title = "DBPMS")))!!

        assertEquals("create_work_log", call.operation)
        assertEquals("DBPMS", call.arguments["activity"])
        assertEquals("2026-08-06", call.arguments["date"])
    }

    @Test
    fun `a work log naming only a duration carries duration through unparsed`() {
        val call = selector.select(intent(IntentType.WORK_LOG, IntentParameters(duration = "3 ghante")))!!

        assertEquals("3 ghante", call.arguments["duration"])
        // Never invented here — parsing/validation is AndroidWorkLogTool's job.
        assertNull(call.arguments["start_time"])
    }

    @Test
    fun `listing work logs for a specific date passes that date`() {
        val call = selector.select(
            intent(IntentType.WORK_LOG, IntentParameters(date = "2026-08-05"), action = IntentAction.LIST),
        )!!

        assertEquals("list_work_logs", call.operation)
        assertEquals("2026-08-05", call.arguments["date"])
    }

    // -----------------------------------------------------------------
    // Meeting note
    // -----------------------------------------------------------------

    @Test
    fun `creating a meeting note carries participants and notes through`() {
        val call = selector.select(
            intent(
                IntentType.MEETING_NOTE,
                IntentParameters(title = "DBPMS meeting", description = "discussed prototype", person = "Hassan bhai"),
            ),
        )!!

        assertEquals("create_meeting", call.operation)
        assertEquals("DBPMS meeting", call.arguments["title"])
        assertEquals("discussed prototype", call.arguments["notes"])
        assertEquals("Hassan bhai", call.arguments["participants"])
    }

    @Test
    fun `listing meetings by person passes the person filter`() {
        val call = selector.select(
            intent(IntentType.MEETING_NOTE, IntentParameters(person = "Hassan"), action = IntentAction.LIST),
        )!!

        assertEquals("list_meetings", call.operation)
        assertEquals("Hassan", call.arguments["person"])
    }

    // -----------------------------------------------------------------
    // Action item
    // -----------------------------------------------------------------

    @Test
    fun `creating an action item routes to create_action_item`() {
        val call = selector.select(intent(IntentType.ACTION_ITEM, IntentParameters(title = "send agenda")))!!

        assertEquals("create_action_item", call.operation)
        assertEquals("send agenda", call.arguments["title"])
    }

    @Test
    fun `completing an action item by title addresses by title`() {
        val call = selector.select(
            intent(IntentType.ACTION_ITEM, IntentParameters(title = "send agenda"), action = IntentAction.COMPLETE),
        )!!

        assertEquals("complete_action_item", call.operation)
        assertEquals("send agenda", call.arguments["title"])
    }

    // -----------------------------------------------------------------
    // Report
    // -----------------------------------------------------------------

    @Test
    fun `a report with no period defaults to daily`() {
        val call = selector.select(intent(IntentType.REPORT, IntentParameters()))!!

        assertEquals(ToolId.REPORT, call.toolId)
        assertEquals("daily_report", call.operation)
    }

    @Test
    fun `a report asking for the week routes to weekly_report`() {
        val call = selector.select(intent(IntentType.REPORT, IntentParameters(period = "week")))!!

        assertEquals("weekly_report", call.operation)
    }
}
