package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.TaskMemory
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verification of the Day 06 TASK/MEETING branches of [ConversationMemoryUpdater]. */
class ConversationMemoryUpdaterProductivityTest {

    private val updater = ConversationMemoryUpdater()

    private fun intent(
        type: IntentType,
        parameters: IntentParameters = IntentParameters(),
        action: IntentAction = IntentAction.CREATE,
    ) = DpsIntent(type = type, parameters = parameters, action = action)

    @Test
    fun `creating a task remembers its id and title`() {
        val memory = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.TASK, IntentParameters(title = "DBPMS docs")),
            ToolId.TASK,
            ToolResult.Success("Task added.", mapOf("task_id" to "5")),
            nowMillis = 1L,
        )

        assertEquals(TaskMemory(id = 5, title = "DBPMS docs"), memory.lastTask)
    }

    @Test
    fun `completing a task keeps it remembered rather than clearing it`() {
        val existing = ConversationMemory(lastTask = TaskMemory(5, "DBPMS docs"))
        val memory = updater.remember(
            existing,
            intent(IntentType.TASK, IntentParameters(targetId = "5"), action = IntentAction.COMPLETE),
            ToolId.TASK,
            ToolResult.Success("Marked as done.", mapOf("task_id" to "5")),
            nowMillis = 2L,
        )

        assertEquals(5, memory.lastTask?.id)
    }

    @Test
    fun `cancelling a task clears it — the record no longer exists`() {
        val existing = ConversationMemory(lastTask = TaskMemory(5, "DBPMS docs"))
        val memory = updater.remember(
            existing,
            intent(IntentType.TASK, IntentParameters(targetId = "5"), action = IntentAction.CANCEL),
            ToolId.TASK,
            ToolResult.Success("Deleted."),
            nowMillis = 3L,
        )

        assertNull(memory.lastTask)
    }

    @Test
    fun `creating a meeting note remembers its id and title`() {
        val memory = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.MEETING_NOTE, IntentParameters(title = "DBPMS meeting")),
            ToolId.MEETING,
            ToolResult.Success("Saved.", mapOf("meeting_id" to "9")),
            nowMillis = 1L,
        )

        assertEquals(9, memory.lastMeeting?.id)
        assertEquals("DBPMS meeting", memory.lastMeeting?.title)
    }

    @Test
    fun `a task result without a task_id key never crashes and leaves memory unchanged`() {
        val memory = updater.remember(
            ConversationMemory.EMPTY,
            intent(IntentType.TASK, IntentParameters(title = "x"), action = IntentAction.LIST),
            ToolId.TASK,
            ToolResult.Success("You have no pending tasks."),
            nowMillis = 1L,
        )

        assertNull(memory.lastTask)
    }
}
