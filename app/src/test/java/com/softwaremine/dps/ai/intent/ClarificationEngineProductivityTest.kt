package com.softwaremine.dps.ai.intent

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.intent.requiredFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verification of the Day 06 productivity branches of [ClarificationEngine]. */
class ClarificationEngineProductivityTest {

    private val engine = ClarificationEngine()

    private fun check(type: IntentType, parameters: IntentParameters, action: IntentAction = IntentAction.CREATE) =
        engine.check(DpsIntent(type = type, parameters = parameters, action = action))

    // -----------------------------------------------------------------
    // LIST bypasses field requirements entirely
    // -----------------------------------------------------------------

    @Test
    fun `listing tasks with no filters is complete`() {
        assertEquals(ClarificationEngine.Check.Complete, check(IntentType.TASK, IntentParameters(), IntentAction.LIST))
    }

    @Test
    fun `listing work logs with no filters is complete`() {
        assertEquals(ClarificationEngine.Check.Complete, check(IntentType.WORK_LOG, IntentParameters(), IntentAction.LIST))
    }

    // -----------------------------------------------------------------
    // Task creation
    // -----------------------------------------------------------------

    @Test
    fun `a task with a title is complete`() {
        assertEquals(ClarificationEngine.Check.Complete, check(IntentType.TASK, IntentParameters(title = "write docs")))
    }

    @Test
    fun `a task with no title asks what the task should be`() {
        val result = check(IntentType.TASK, IntentParameters())
        assertTrue(result is ClarificationEngine.Check.Missing)
    }

    // -----------------------------------------------------------------
    // Title-addressable completion/cancellation (Day 06)
    // -----------------------------------------------------------------

    @Test
    fun `completing a task by title alone is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.TASK, IntentParameters(title = "DBPMS"), IntentAction.COMPLETE),
        )
    }

    @Test
    fun `completing a task by resolved id alone is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.TASK, IntentParameters(targetId = "3"), IntentAction.COMPLETE),
        )
    }

    @Test
    fun `completing a task with neither id nor title asks which one`() {
        val result = check(IntentType.TASK, IntentParameters(), IntentAction.COMPLETE)
        assertTrue(result is ClarificationEngine.Check.Missing)
        assertEquals("Which task do you mean?", (result as ClarificationEngine.Check.Missing).question)
    }

    @Test
    fun `cancelling a task by title alone is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.TASK, IntentParameters(title = "DBPMS"), IntentAction.CANCEL),
        )
    }

    @Test
    fun `updating a task requires a resolved id even if a new title is given`() {
        // Title here means the *new* title (a rename), not an address — see
        // ToolSelector.taskCall's doc — so it must not satisfy this check.
        val result = check(IntentType.TASK, IntentParameters(title = "renamed"), IntentAction.UPDATE)
        assertTrue(result is ClarificationEngine.Check.Missing)
    }

    @Test
    fun `updating a task with a resolved id is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.TASK, IntentParameters(targetId = "3", title = "renamed"), IntentAction.UPDATE),
        )
    }

    @Test
    fun `completing an action item by title alone is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.ACTION_ITEM, IntentParameters(title = "send agenda"), IntentAction.COMPLETE),
        )
    }

    // -----------------------------------------------------------------
    // Work log / meeting note / action item / report
    // -----------------------------------------------------------------

    @Test
    fun `a work log with only a duration is complete`() {
        assertEquals(ClarificationEngine.Check.Complete, check(IntentType.WORK_LOG, IntentParameters(duration = "3 hours")))
    }

    @Test
    fun `a work log with neither activity nor duration is incomplete`() {
        val result = check(IntentType.WORK_LOG, IntentParameters())
        assertTrue(result is ClarificationEngine.Check.Missing)
    }

    @Test
    fun `a meeting note with only free-form notes is complete`() {
        assertEquals(
            ClarificationEngine.Check.Complete,
            check(IntentType.MEETING_NOTE, IntentParameters(description = "discussed the prototype")),
        )
    }

    @Test
    fun `a report never asks a follow-up question`() {
        assertEquals(ClarificationEngine.Check.Complete, check(IntentType.REPORT, IntentParameters()))
    }

    @Test
    fun `merge preserves duration and period across a clarification round`() {
        val existing = IntentParameters(duration = "3 hours")
        val merged = engine.merge(existing, IntentParameters(title = "DBPMS"))

        assertEquals("3 hours", merged.duration)
        assertEquals("DBPMS", merged.title)
    }

    @Test
    fun `requiredFields for TASK is satisfied by a title`() {
        assertTrue(IntentType.TASK.requiredFields.any { group -> group == setOf(IntentField.TITLE) })
    }
}
