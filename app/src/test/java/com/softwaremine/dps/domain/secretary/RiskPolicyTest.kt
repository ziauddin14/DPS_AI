package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M8: [RiskPolicy] is a pure relocation of the pre-M8
 * `DELETE_CONFIRMATION_TYPES`/`CALL_CONFIRMATION_TYPES` constants
 * ([com.softwaremine.dps.ai.secretary.SecretaryOrchestrator]'s own private
 * companion object, before this milestone) plus one addition
 * ([IntentType.FORGET_FACT]) — every case below proves the relocation
 * preserved every existing outcome exactly, and that the one addition
 * behaves as M8's own locked contract requires.
 */
class RiskPolicyTest {

    @Test
    fun `create_task is safe-auto`() {
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.TASK, action = IntentAction.CREATE)))
    }

    @Test
    fun `create_event is safe-auto`() {
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.CALENDAR_EVENT, action = IntentAction.CREATE)))
    }

    @Test
    fun `update actions are safe-auto for calendar, task and reminder`() {
        listOf(IntentType.CALENDAR_EVENT, IntentType.TASK, IntentType.REMINDER).forEach { type ->
            assertEquals(
                "$type UPDATE should be safe-auto",
                RiskLevel.SAFE_AUTO,
                RiskPolicy.classify(DpsIntent(type, action = IntentAction.UPDATE)),
            )
        }
    }

    @Test
    fun `complete_task is safe-auto`() {
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.TASK, action = IntentAction.COMPLETE)))
    }

    @Test
    fun `cancel_task is confirm-required`() {
        assertEquals(RiskLevel.CONFIRM_REQUIRED, RiskPolicy.classify(DpsIntent(IntentType.TASK, action = IntentAction.CANCEL)))
    }

    @Test
    fun `cancel_reminder is confirm-required`() {
        assertEquals(RiskLevel.CONFIRM_REQUIRED, RiskPolicy.classify(DpsIntent(IntentType.REMINDER, action = IntentAction.CANCEL)))
    }

    @Test
    fun `calendar cancel is confirm-required`() {
        assertEquals(RiskLevel.CONFIRM_REQUIRED, RiskPolicy.classify(DpsIntent(IntentType.CALENDAR_EVENT, action = IntentAction.CANCEL)))
    }

    @Test
    fun `a non-cancel calendar action is safe-auto even though the type is in the delete set`() {
        // Proves the action-gate, not just the type-gate, is preserved from
        // the pre-M8 `intent.action == IntentAction.CANCEL` check.
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.CALENDAR_EVENT, action = IntentAction.LIST)))
    }

    @Test
    fun `call_contact is confirm-required regardless of action`() {
        listOf(IntentAction.CREATE, IntentAction.LIST).forEach { action ->
            assertEquals(
                "CALL_CONTACT with action $action should be confirm-required",
                RiskLevel.CONFIRM_REQUIRED,
                RiskPolicy.classify(DpsIntent(IntentType.CALL_CONTACT, action = action)),
            )
        }
    }

    @Test
    fun `forget_fact is confirm-required`() {
        assertEquals(RiskLevel.CONFIRM_REQUIRED, RiskPolicy.classify(DpsIntent(IntentType.FORGET_FACT, action = IntentAction.CANCEL)))
    }

    @Test
    fun `remember_fact and recall_fact are safe-auto`() {
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.REMEMBER_FACT)))
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.RECALL_FACT)))
    }

    @Test
    fun `whatsapp and email composer flows are safe-auto`() {
        // M8 locked decisions 3 and 4 — an explicit regression guard against
        // ever adding a redundant DPS-level confirmation for either.
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.WHATSAPP_MESSAGE)))
        assertEquals(RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(IntentType.EMAIL_MESSAGE)))
    }

    @Test
    fun `notification and internal productivity actions are safe-auto`() {
        listOf(
            IntentType.NOTIFICATION,
            IntentType.WORK_LOG,
            IntentType.MEETING_NOTE,
            IntentType.ACTION_ITEM,
            IntentType.REPORT,
            IntentType.CONTACT_LOOKUP,
            IntentType.CONVERSATION,
        ).forEach { type ->
            assertEquals("$type should be safe-auto", RiskLevel.SAFE_AUTO, RiskPolicy.classify(DpsIntent(type)))
        }
    }

    @Test
    fun `classification is a pure function - the same intent classifies identically every time`() {
        val intent = DpsIntent(IntentType.TASK, action = IntentAction.CANCEL)
        val first = RiskPolicy.classify(intent)
        val second = RiskPolicy.classify(intent)
        assertEquals(first, second)
        assertEquals(RiskLevel.CONFIRM_REQUIRED, first)
    }
}
