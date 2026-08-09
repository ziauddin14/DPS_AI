package com.softwaremine.dps.ai.memory

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.memory.TaskMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verification of the Day 06 TASK cue handling in [ReferenceResolver]. */
class ReferenceResolverProductivityTest {

    private val resolver = ReferenceResolver()

    @Test
    fun `us task ko resolves against the last remembered task`() {
        val memory = ConversationMemory(lastTask = TaskMemory(id = 7, title = "DBPMS docs"))
        val intent = DpsIntent(IntentType.TASK, IntentParameters(), action = IntentAction.COMPLETE)

        val resolved = resolver.resolve("us task ko complete kar do", intent, memory)

        assertEquals("7", resolved.parameters.targetId)
    }

    @Test
    fun `a task named explicitly by title is not overridden by a stale memory reference`() {
        val memory = ConversationMemory(lastTask = TaskMemory(id = 7, title = "some other task"))
        val intent = DpsIntent(IntentType.TASK, IntentParameters(title = "DBPMS docs"), action = IntentAction.COMPLETE)

        val resolved = resolver.resolve("DBPMS wala task complete kar do", intent, memory)

        // Title addressing wins — AndroidTaskTool resolves it directly, and
        // this class must not substitute a different, stale task's id.
        assertNull(resolved.parameters.targetId)
        assertEquals("DBPMS docs", resolved.parameters.title)
    }

    @Test
    fun `a bare task completion with no cue and no memory resolves nothing`() {
        val intent = DpsIntent(IntentType.TASK, IntentParameters(), action = IntentAction.COMPLETE)

        val resolved = resolver.resolve("complete kar do", intent, ConversationMemory.EMPTY)

        assertNull(resolved.parameters.targetId)
    }

    @Test
    fun `creating a new task never resolves against a remembered task`() {
        val memory = ConversationMemory(lastTask = TaskMemory(id = 7, title = "DBPMS docs"))
        val intent = DpsIntent(IntentType.TASK, IntentParameters(title = "new task"), action = IntentAction.CREATE)

        val resolved = resolver.resolve("us task ko add karo", intent, memory)

        assertNull(resolved.parameters.targetId)
    }
}
