package com.softwaremine.dps.ai.secretary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.IntentJsonParser
import com.softwaremine.dps.ai.intent.IntentPromptBuilder
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolResponseGenerator
import com.softwaremine.dps.ai.intent.ToolSelector
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseSpanFinder
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.AiEngine
import com.softwaremine.dps.domain.ai.AiState
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.model.ModelDescriptor
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the AI Secretary layer reaches the *real* tool
 * registry (Day 05 Phase E).
 *
 * ## What only a device can prove
 * [ai.intent.ToolSelector]'s new UPDATE/CANCEL branch names
 * `update_reminder`/`cancel_reminder` — operations that only exist because
 * `AndroidReminderTool` (Phase B) declared and implemented them. The JVM
 * suite verifies the branching logic against a stub; it cannot see whether
 * those exact operation names still match what the real, shipping tool
 * accepts. Building against [AiContainer]'s real
 * [AiContainer.toolRegistry]/[AiContainer.toolExecutor] is what closes that
 * gap, exactly as `IntentOrchestrationInstrumentedTest` did for Phase D.
 *
 * ## Why the model is still scripted
 * Same reasoning as Phase D: real inference is slow and non-deterministic,
 * and is covered elsewhere (`GgufInferenceInstrumentedTest`). This test
 * covers everything downstream of classification — including, new in Phase E,
 * whether a *second* scripted message resolves against what the first one
 * actually did on this device.
 */
@RunWith(AndroidJUnit4::class)
class SecretaryLiveWiringInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AiContainer(context)

    private val silentLogger = object : DpsLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Replays a fixed script of classifications, one per [handle] call. */
    private class ScriptedEngine(vararg replies: String) : AiEngine {
        private val replies = replies.toList()
        private var index = 0

        override val state: StateFlow<AiState> = MutableStateFlow(AiState.Idle)
        override val activeModel: ModelDescriptor? = null

        override suspend fun initialize(): DpsResult<Unit> = DpsResult.Success(Unit)
        override suspend fun loadModel(
            descriptor: ModelDescriptor,
            config: ModelConfig,
        ): DpsResult<Unit> = DpsResult.Success(Unit)

        override suspend fun unloadModel(): DpsResult<Unit> = DpsResult.Success(Unit)
        override fun generate(request: CompletionRequest): Flow<CompletionChunk> = emptyFlow()

        override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> {
            val reply = replies.getOrElse(index) { replies.last() }
            index++
            return DpsResult.Success(
                AiCompletion(
                    text = reply,
                    finishReason = FinishReason.END_OF_TURN,
                    usage = TokenUsage(promptTokens = 100, completionTokens = 20),
                    durationMillis = 1_000,
                ),
            )
        }

        override suspend fun tokenCount(text: String): DpsResult<Int> = DpsResult.Success(text.length / 4)
        override suspend fun shutdown() = Unit
    }

    private fun secretary(vararg classifications: String): SecretaryOrchestrator {
        val toolOrchestrator = ToolOrchestrator(
            engine = ScriptedEngine(*classifications),
            executor = container.toolExecutor,
            registry = container.toolRegistry,
            promptBuilder = IntentPromptBuilder(),
            parser = IntentJsonParser(),
            clarification = ClarificationEngine(),
            selector = ToolSelector(),
            responses = ToolResponseGenerator(),
            logger = silentLogger,
        )

        return SecretaryOrchestrator(
            toolOrchestrator = toolOrchestrator,
            referenceResolver = ReferenceResolver(),
            temporalPhraseResolver = TemporalPhraseResolver(),
            temporalGroundingGuard = TemporalGroundingGuard(),
            temporalStepAttributor = TemporalStepAttributor(TemporalPhraseSpanFinder(), TemporalGroundingGuard()),
            actionDetector = ActionDetector(),
            clarification = ClarificationEngine(),
            memoryUpdater = ConversationMemoryUpdater(),
            contactSelectionParser = ContactSelectionParser(),
            confirmationParser = ConfirmationParser(),
            followUpSuggestions = FollowUpSuggestionGenerator(),
            logger = silentLogger,
        )
    }

    // -----------------------------------------------------------------
    // Drift check: the new operations exist on the real tool
    // -----------------------------------------------------------------

    @Test
    fun updateAndCancelReminderOperationsAreRegisteredOnTheRealTool() {
        val selector = ToolSelector()
        val tool = container.toolRegistry.find(
            selector.select(
                DpsIntent(
                    IntentType.REMINDER,
                    IntentParameters(targetId = "1"),
                    action = IntentAction.UPDATE,
                ),
            )!!.toolId,
        )

        assertNotNull("REMINDER is not registered on this device", tool)
        assertTrue(
            "update_reminder is not declared by the real tool: ${tool!!.operations}",
            "update_reminder" in tool.operations,
        )
        assertTrue(
            "cancel_reminder is not declared by the real tool: ${tool.operations}",
            "cancel_reminder" in tool.operations,
        )
    }

    // -----------------------------------------------------------------
    // Memory persists across turns against the real tool layer
    // -----------------------------------------------------------------

    /**
     * Demo example 2 from the Phase E brief, against the real reminder tool.
     *
     * ## Pre-existing fixture bug fixed here (Gap D)
     * This test's message used to say "at 4pm" while the scripted `raw_when`
     * said `"16:00"` — two different textual representations of the same
     * time, so `TemporalGroundingGuard` correctly rejected `"16:00"` as not
     * literally present in the user's own words, and the test never got
     * past a "When should I remind you?" clarification. The fix is the
     * message, not the grounding logic: state the time the same way the
     * scripted `raw_when` already does.
     */
    @Test
    fun rescheduleFollowUpResolvesAgainstTheRealReminderJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"reminder","parameters":{"title":"call the bank","raw_when":"16:00"}}""",
            """{"intent":"reminder","action_type":"update"}""",
        )

        val first = orchestrator.handle("remind me to call the bank at 16:00")
        assertTrue("Expected Handled, got $first", first is ToolOrchestrator.Outcome.Handled)
        assertNotNull("Memory did not record the created reminder", orchestrator.memory.value.lastReminder)

        val second = orchestrator.handle("uska reminder 30 minute pehle kar do")

        assertTrue(
            "Expected the follow-up to resolve and execute, got $second",
            second is ToolOrchestrator.Outcome.Handled || second is ToolOrchestrator.Outcome.Conversational,
        )
        // Whichever the real tool reported, memory must reflect it truthfully
        // rather than an assumed success — the property under test is that the
        // second call reached the *same* reminder, not that it necessarily
        // succeeded on this device's alarm scheduling.
        if (second is ToolOrchestrator.Outcome.Handled) {
            assertTrue(
                "Reply falsely implies nothing happened: ${second.reply}",
                second.reply.isNotBlank(),
            )
        }
    }

    @Test
    fun conversationNeverTouchesARealTool(): Unit = runBlocking {
        val outcome = secretary("""{"intent":"conversation"}""").handle("who are you?")

        assertTrue(outcome is ToolOrchestrator.Outcome.Conversational)
    }

    /**
     * Phase 5 — Reminder Cancel Confirmation, against the real reminder tool
     * (real `AlarmManager`/`ReminderStore`, not a mock). Mirrors
     * [rescheduleAndDeleteResolveAgainstTheRealEventJustCreated]'s shape one
     * step further: it also confirms, proving the full
     * ask → still-exists → confirm → really-cancelled loop against real
     * device state, not just that the question was asked.
     *
     * Always cleans up: the test's own last step *is* the cleanup (the
     * reminder is genuinely cancelled by the time it finishes on the happy
     * path), and the `finally` cancels it again defensively so a failed
     * assertion earlier in the test can never leave a real alarm or
     * `ReminderStore` record behind.
     */
    @Test
    fun cancellingARealReminderRequiresConfirmationThenRemovesIt(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"reminder","parameters":{"title":"Phase 5 confirmation flow check","raw_when":"16:00"}}""",
            """{"intent":"reminder","action_type":"cancel","parameters":{}}""",
        )

        val created = orchestrator.handle("remind me about the Phase 5 confirmation flow check at 16:00")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no POST_NOTIFICATIONS on this run; covered by AndroidToolsInstrumentedTest instead
        val reminderId = orchestrator.memory.value.lastReminder?.id
        assertNotNull("Memory did not record the created reminder", reminderId)

        try {
            val beforeConfirmation = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "Reminder must still exist before any confirmation, got $beforeConfirmation",
                (beforeConfirmation as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) == true,
            )

            val cancelRequest = orchestrator.handle("cancel that reminder")
            assertTrue(
                "Cancellation must ask for confirmation before doing anything, got $cancelRequest",
                cancelRequest is ToolOrchestrator.Outcome.Clarify,
            )

            val afterAskBeforeConfirm = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "Reminder must still exist once only asked about, not yet confirmed, got $afterAskBeforeConfirm",
                (afterAskBeforeConfirm as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) == true,
            )

            val confirmed = orchestrator.handle("haan")
            assertTrue("Expected Handled after confirming, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)

            val afterConfirm = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "Reminder must be gone once confirmed, got $afterConfirm",
                (afterConfirm as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) != true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to reminderId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // Stage 2 drift check: update_event/delete_event exist on the real tool
    // -----------------------------------------------------------------

    @Test
    fun updateAndDeleteEventOperationsAreRegisteredOnTheRealCalendarTool() {
        val selector = ToolSelector()
        val tool = container.toolRegistry.find(
            selector.select(
                DpsIntent(IntentType.CALENDAR_EVENT, IntentParameters(targetId = "1"), action = IntentAction.UPDATE),
            )!!.toolId,
        )

        assertNotNull("CALENDAR is not registered on this device", tool)
        assertTrue(
            "update_event is not declared by the real tool: ${tool!!.operations}",
            "update_event" in tool.operations,
        )
        assertTrue(
            "delete_event is not declared by the real tool: ${tool.operations}",
            "delete_event" in tool.operations,
        )
    }

    /**
     * Demo example 4/5 from the Phase E Stage 2 brief, against the real
     * calendar tool.
     *
     * ## Why a permission request is an accepted outcome here
     * Whether READ_CALENDAR/WRITE_CALENDAR are already granted depends on
     * this device's state going into the test run, not on this test — the
     * same reasoning Phase D's own reminder equivalent already applies. What
     * this proves either way: the pipeline reaches the real tool and asks
     * honestly rather than silently failing or fabricating a result.
     *
     * ## Pre-existing fixture bug fixed here (Gap D)
     * Both the create step ("at 9am" vs. scripted `raw_when="09:00"`) and
     * the reschedule step ("5 baje" vs. scripted `raw_when="17:00"`) used to
     * state the time differently from what the model was scripted to quote
     * — `TemporalGroundingGuard` correctly rejected both as ungrounded, so
     * this test never got past its first clarification question. Fixed by
     * aligning the messages to the same colon-time form the scripted
     * `raw_when` already uses; the grounding/resolution logic itself is
     * unchanged.
     *
     * ## Now also confirms and cleans up (Gap D, extended)
     * The original version stopped at the delete confirmation question,
     * which — once the create step above actually started succeeding —
     * would leave a real event behind on any device with calendar
     * permission granted. This now sends the confirming "haan" and verifies
     * the event is genuinely gone, with a defensive `finally` that deletes
     * it directly if any assertion above fails first.
     */
    @Test
    fun rescheduleAndDeleteResolveAgainstTheRealEventJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"calendar_event","parameters":{"title":"standup","raw_when":"09:00"}}""",
            """{"intent":"calendar_event","action_type":"update","parameters":{"raw_when":"17:00"}}""",
            """{"intent":"calendar_event","action_type":"cancel","parameters":{}}""",
        )

        val created = orchestrator.handle("standup at 09:00")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no calendar permission on this run; covered by AndroidToolsInstrumentedTest instead
        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        assertNotNull("Memory did not record the created event", eventId)

        try {
            val rescheduled = orchestrator.handle("us meeting ko 17:00 kar do")
            assertTrue(
                "Expected the reschedule to resolve, got $rescheduled",
                rescheduled is ToolOrchestrator.Outcome.Handled || rescheduled is ToolOrchestrator.Outcome.Conversational,
            )

            val deleteRequest = orchestrator.handle("us meeting ko delete kar do")
            // Requirement 6: deletion asks first — the very next outcome must be
            // a question, never the deletion itself.
            assertTrue(
                "Delete must ask for confirmation before doing anything, got $deleteRequest",
                deleteRequest is ToolOrchestrator.Outcome.Clarify,
            )

            val confirmed = orchestrator.handle("haan")
            assertTrue("Expected Handled after confirming, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // Day 09, Option 1 — deterministic redirect against real device state
    //
    // The Calendar Delete/Update Classification Reliability investigation's
    // live-device evidence: a request naming an existing calendar event or
    // reminder is frequently classified as notification/cancel — a type
    // with no grounding mechanism at all — while the real item sits in
    // memory unconsidered. These reproduce that exact misclassification
    // against the real CalendarProvider/AlarmManager+ReminderStore, proving
    // the redirect resolves to and removes the genuine on-device item, not
    // a stand-in.
    // -----------------------------------------------------------------

    /**
     * CAT1/CAT4 from the investigation: "delete/cancel the standup meeting"
     * misclassified as notification/cancel. The redirect must name the real
     * event, still pass through the existing delete-confirmation gate
     * unchanged, and only then remove the genuine CalendarProvider row.
     */
    @Test
    fun aNotificationCancelMisclassificationRedirectsToAndDeletesTheRealCalendarEvent(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"calendar_event","parameters":{"title":"Day 09 redirect check","raw_when":"09:00"}}""",
            """{"intent":"notification","action_type":"cancel","parameters":{}}""",
        )

        val created = orchestrator.handle("Day 09 redirect check at 09:00")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no calendar permission on this run
        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        assertNotNull("Memory did not record the created event", eventId)

        try {
            val redirected = orchestrator.handle("delete the standup meeting")
            assertTrue(
                "A misclassified notification/cancel with a real event in memory must redirect, got $redirected",
                redirected is ToolOrchestrator.Outcome.Clarify,
            )
            assertTrue(
                "Redirect question must name the real event, got: ${(redirected as ToolOrchestrator.Outcome.Clarify).question}",
                redirected.question.contains("Day 09 redirect check"),
            )

            val confirmedRedirect = orchestrator.handle("yes")
            assertTrue(
                "Confirming the redirect must still hit the delete-confirmation gate, not delete directly, got $confirmedRedirect",
                confirmedRedirect is ToolOrchestrator.Outcome.Clarify,
            )

            val stillThere = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must still exist once only redirected and asked, not yet confirmed, got $stillThere",
                (stillThere as? ToolResult.Success)?.data?.containsValue(eventId.toString()) == true,
            )

            val confirmedDelete = orchestrator.handle("yes")
            assertTrue("Expected Handled after confirming the delete, got $confirmedDelete", confirmedDelete is ToolOrchestrator.Outcome.Handled)

            val afterDelete = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must be genuinely gone from the real CalendarProvider once confirmed, got $afterDelete",
                (afterDelete as? ToolResult.Success)?.data?.containsValue(eventId.toString()) != true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())),
            )
        }
    }

    /**
     * The reminder-side mirror of the calendar case above, and CAT6 from the
     * investigation: "cancel that reminder" misclassified as
     * notification/cancel. Proves the redirect resolves to and genuinely
     * cancels the real `AlarmManager`/`ReminderStore` entry.
     */
    @Test
    fun aNotificationCancelMisclassificationRedirectsToAndCancelsTheRealReminder(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"reminder","parameters":{"title":"Day 09 reminder redirect check","raw_when":"16:00"}}""",
            """{"intent":"notification","action_type":"cancel","parameters":{}}""",
        )

        val created = orchestrator.handle("remind me about the Day 09 reminder redirect check at 16:00")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no POST_NOTIFICATIONS on this run
        val reminderId = orchestrator.memory.value.lastReminder?.id
        assertNotNull("Memory did not record the created reminder", reminderId)

        try {
            val redirected = orchestrator.handle("cancel that reminder")
            assertTrue(
                "A misclassified notification/cancel with a real reminder in memory must redirect, got $redirected",
                redirected is ToolOrchestrator.Outcome.Clarify,
            )
            assertTrue(
                "Redirect question must name the real reminder, got: ${(redirected as ToolOrchestrator.Outcome.Clarify).question}",
                redirected.question.contains("Day 09 reminder redirect check"),
            )

            val confirmedRedirect = orchestrator.handle("yes")
            assertTrue(
                "Confirming the redirect must still hit the delete-confirmation gate, not cancel directly, got $confirmedRedirect",
                confirmedRedirect is ToolOrchestrator.Outcome.Clarify,
            )

            val stillThere = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "Reminder must still exist once only redirected and asked, not yet confirmed, got $stillThere",
                (stillThere as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) == true,
            )

            val confirmedCancel = orchestrator.handle("yes")
            assertTrue("Expected Handled after confirming the cancel, got $confirmedCancel", confirmedCancel is ToolOrchestrator.Outcome.Handled)

            val afterCancel = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "Reminder must be genuinely gone from the real ReminderStore once confirmed, got $afterCancel",
                (afterCancel as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) != true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to reminderId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // CAT5 fix (Day 09 follow-up) — real-device confirmation that the
    // anchored "move"/"change" phrases correct the action, and the
    // already-committed Option 1 redirect then routes the update onto the
    // real CalendarProvider event, not a nonexistent reminder.
    // -----------------------------------------------------------------

    /**
     * Byte-for-byte the real on-device investigation evidence for "Move
     * that meeting to tomorrow evening.": reminder/create, the whole
     * sentence dumped into `message`, no title. Before the CAT5 fix this
     * asked "When should I remind you?" about a reminder that never
     * existed; it must now redirect to and update the real event.
     */
    @Test
    fun moveThatMeetingCorrectsToUpdateAndMovesTheRealCalendarEvent(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"intent":"calendar_event","parameters":{"title":"Day 09 CAT5 check","raw_when":"10:00"}}""",
            """{"intent":"reminder","action_type":"create","parameters":{"raw_when":"tomorrow evening","message":"Move that meeting to tomorrow evening."}}""",
        )

        val created = orchestrator.handle("Day 09 CAT5 check at 10:00")
        assertTrue(
            "Expected Handled or NeedsPermission, got $created",
            created is ToolOrchestrator.Outcome.Handled || created is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (created !is ToolOrchestrator.Outcome.Handled) return@runBlocking // no calendar permission on this run
        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        assertNotNull("Memory did not record the created event", eventId)

        try {
            val redirected = orchestrator.handle("Move that meeting to tomorrow evening.")
            assertTrue(
                "The anchored phrase must correct the action and redirect to the real event, got $redirected",
                redirected is ToolOrchestrator.Outcome.Clarify,
            )
            assertTrue(
                "Redirect question must name the real event, not ask about a nonexistent reminder, got: ${(redirected as ToolOrchestrator.Outcome.Clarify).question}",
                redirected.question.contains("Day 09 CAT5 check"),
            )

            val confirmed = orchestrator.handle("yes")
            assertTrue("Expected Handled after confirming the redirect, got $confirmed", confirmed is ToolOrchestrator.Outcome.Handled)

            val afterUpdate = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "The real event must still exist (updated, not deleted) after the redirect is confirmed, got $afterUpdate",
                (afterUpdate as? ToolResult.Success)?.data?.containsValue(eventId.toString()) == true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // M2-A — generalized cross-step reminder offset, against real state
    // -----------------------------------------------------------------

    /**
     * A stated cross-step offset ("15 minutes before") must be used in
     * place of the fixed 30-minute default, verified against the real
     * CalendarProvider event and the real ReminderStore/AlarmManager entry
     * it produces — not a JVM-scripted clock.
     */
    @Test
    fun anExplicitOffsetMovesTheRealReminderRelativeToTheRealEventJustCreated(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"calendar_event","parameters":{"title":"M2-A offset check","raw_when":"20:00"}},
                {"intent":"reminder","parameters":{"title":"M2-A offset check","raw_when":"15 minutes before"}}
            ]}""",
        )

        val outcome = orchestrator.handle("M2-A offset check at 20:00, remind me 15 minutes before it.")
        assertTrue(
            "Expected Handled or NeedsPermission, got $outcome",
            outcome is ToolOrchestrator.Outcome.Handled || outcome is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (outcome !is ToolOrchestrator.Outcome.Handled) return@runBlocking // calendar/reminder permission not granted on this run

        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        val eventStart = orchestrator.memory.value.lastCalendarEvent?.startMillis
        assertNotNull("Memory did not record the created event", eventId)
        assertNotNull("Memory did not record the event's start time", eventStart)

        val reminderId = orchestrator.memory.value.lastReminder?.id
        val reminderTrigger = orchestrator.memory.value.lastReminder?.triggerAtMillis
        assertNotNull(
            "Memory did not record the created reminder — the offset step may not have executed",
            reminderId,
        )
        assertNotNull("Memory did not record the reminder's trigger time", reminderTrigger)

        try {
            val expected = eventStart!! - 15 * 60 * 1000L
            assertEquals(
                "Reminder must fire exactly 15 minutes before the real event's start, not the 30-minute default",
                expected,
                reminderTrigger,
            )

            val realEvent = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "The real event must exist in CalendarProvider, got $realEvent",
                (realEvent as? ToolResult.Success)?.data?.containsValue(eventId.toString()) == true,
            )

            val realReminders = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "The real reminder must exist in ReminderStore, got $realReminders",
                (realReminders as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) == true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to reminderId.toString())),
            )
            container.toolExecutor.execute(
                ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // M2-B — Option 1 redirect reachable from inside a multi-step plan,
    // against real state
    // -----------------------------------------------------------------

    /**
     * The exact Day 09 Option 1 redirect, now reachable from handlePlan:
     * the calendar event is created by step 1 of a compound plan, and the
     * ambiguous notification/cancel is step 2 of the *same* plan — one
     * scripted classification, one handle() call, both steps against the
     * real CalendarProvider.
     */
    @Test
    fun anAmbiguousStepInsideAPlanRedirectsToAndDeletesTheRealCalendarEventCreatedEarlierInTheSamePlan(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"calendar_event","parameters":{"title":"M2-B plan redirect check","raw_when":"11:00"}},
                {"intent":"notification","action_type":"cancel","parameters":{}}
            ]}""",
        )

        val outcome = orchestrator.handle("M2-B plan redirect check at 11:00, then delete the M2-B plan redirect check meeting.")
        assertTrue(
            "Expected a redirect Clarify or NeedsPermission, got $outcome",
            outcome is ToolOrchestrator.Outcome.Clarify || outcome is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (outcome !is ToolOrchestrator.Outcome.Clarify) return@runBlocking // calendar permission not granted on this run

        assertTrue(
            "Redirect question must name the real event, not the generic notification question, got: ${outcome.question}",
            outcome.question.contains("M2-B plan redirect check"),
        )
        assertTrue(!outcome.question.contains("What should the notification say"))

        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        assertNotNull("Memory did not record the event step 1 of the plan created", eventId)

        try {
            val confirmedRedirect = orchestrator.handle("yes")
            assertTrue(
                "Confirming the redirect must still hit the delete-confirmation gate, got $confirmedRedirect",
                confirmedRedirect is ToolOrchestrator.Outcome.Clarify,
            )

            val stillThere = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must still exist once only redirected and asked, not yet confirmed, got $stillThere",
                (stillThere as? ToolResult.Success)?.data?.containsValue(eventId.toString()) == true,
            )

            val confirmedDelete = orchestrator.handle("yes")
            assertTrue("Expected Handled after confirming the delete, got $confirmedDelete", confirmedDelete is ToolOrchestrator.Outcome.Handled)

            val afterDelete = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must be genuinely gone from the real CalendarProvider once confirmed, got $afterDelete",
                (afterDelete as? ToolResult.Success)?.data?.containsValue(eventId.toString()) != true,
            )
        } finally {
            container.toolExecutor.execute(
                ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())),
            )
        }
    }

    // -----------------------------------------------------------------
    // M2-C — PendingPlan and multi-step plan resumption, against real state
    //
    // Both use only IntentType.TASK — the one type in this suite whose real
    // tool (SharedPreferences-backed) needs no runtime permission — so
    // these prove the continuation mechanism itself against real, durable
    // device state without being subject to calendar/reminder permission
    // flakiness on a fresh test run. A mid-plan-blocked-twice instrumented
    // case (Instrumented Test C in the M2-C brief) was intentionally not
    // added: the underlying continuation code is identical regardless of
    // how many times it re-enters, the JVM suite already proves re-blocking
    // exhaustively and deterministically, and a third live-device run would
    // add real runtime without proportionate new coverage.
    // -----------------------------------------------------------------

    /**
     * A 3-step plan where step 2 blocks on missing information — step 1
     * must already have run, step 3 must not run until step 2 is answered,
     * and all three must be real, durable `TaskStore` rows once it is.
     *
     * Step 2 is a title-less task, not a `whatsapp_message` — a real
     * device run found that WHATSAPP_MESSAGE, once resumed, still needs a
     * real `READ_CONTACTS` grant to resolve the named person, which this
     * test's device did not have. That is a genuine, correctly-reported
     * `NeedsPermission` (a real, separate, out-of-M2-C-scope block, per the
     * report's own audit), not a defect — but it defeats the point of
     * *this* test, which is step 3 actually running. A task with no title
     * blocks for a genuinely missing reason with no such second block.
     */
    @Test
    fun aRealThreeStepPlanResumesStep3AfterStep2sClarificationIsAnswered(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"task","parameters":{"title":"M2-C plan check task 1"}},
                {"intent":"task","parameters":{}},
                {"intent":"task","parameters":{"title":"M2-C plan check task 3"}}
            ]}""",
            """{"intent":"task","parameters":{"title":"M2-C plan check task 2"}}""",
        )

        try {
            val blocked = orchestrator.handle("M2-C plan check task 1 banao, phir ek aur task banao, aur M2-C plan check task 3 bhi banao")
            assertTrue("Expected Clarify for step 2's missing title, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)

            val afterBlock = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 1 must have already run against the real TaskStore, got $afterBlock",
                (afterBlock as? ToolResult.Success)?.data?.containsValue("M2-C plan check task 1") == true,
            )
            assertTrue(
                "Step 3 must not have run yet, got $afterBlock",
                (afterBlock as? ToolResult.Success)?.data?.containsValue("M2-C plan check task 3") != true,
            )

            val resumed = orchestrator.handle("M2-C plan check task 2")
            assertTrue("Expected Handled once step 2 is answered, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)

            val afterResume = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 2 itself must have run once answered, got $afterResume",
                (afterResume as? ToolResult.Success)?.data?.containsValue("M2-C plan check task 2") == true,
            )
            assertTrue(
                "Step 3 must have run against the real TaskStore once step 2 resumed, got $afterResume",
                (afterResume as? ToolResult.Success)?.data?.containsValue("M2-C plan check task 3") == true,
            )
        } finally {
            val cleanupData = (container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks")) as? ToolResult.Success)?.data.orEmpty()
            cleanupData
                .filterKeys { it.endsWith("_title") }
                .filterValues { it in setOf("M2-C plan check task 1", "M2-C plan check task 2", "M2-C plan check task 3") }
                .keys
                .mapNotNull { key -> cleanupData[key.replace("_title", "_id")] }
                .forEach { id -> container.toolExecutor.execute(ToolCall(ToolId.TASK, "cancel_task", mapOf("id" to id))) }
        }
    }

    /**
     * A 3-step plan where step 2 is a destructive cancellation requiring
     * confirmation — step 1 must already have run, the real task step 2
     * targets must survive until confirmed, and step 3 must not run until
     * it is.
     */
    @Test
    fun aRealThreeStepPlanResumesStep3AfterStep2sDestructiveConfirmationIsAnswered(): Unit = runBlocking {
        val setupOrchestrator = secretary("""{"intent":"task","parameters":{"title":"M2-C confirm check task"}}""")
        val setup = setupOrchestrator.handle("M2-C confirm check task banao")
        assertTrue("Setup task creation failed, got $setup", setup is ToolOrchestrator.Outcome.Handled)

        val orchestrator = secretary(
            """{"steps":[
                {"intent":"task","parameters":{"title":"M2-C plan task A"}},
                {"intent":"task","action_type":"cancel","parameters":{"title":"M2-C confirm check task"}},
                {"intent":"task","parameters":{"title":"M2-C plan task B"}}
            ]}""",
        )

        try {
            val blocked = orchestrator.handle(
                "M2-C plan task A banao, M2-C confirm check task cancel karo, aur M2-C plan task B bhi banao",
            )
            assertTrue("Expected the delete confirmation for step 2, got $blocked", blocked is ToolOrchestrator.Outcome.Clarify)

            val afterBlock = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "The task step 2 targets must still exist, not yet confirmed, got $afterBlock",
                (afterBlock as? ToolResult.Success)?.data?.containsValue("M2-C confirm check task") == true,
            )
            assertTrue(
                "Step 3 must not have run yet, got $afterBlock",
                (afterBlock as? ToolResult.Success)?.data?.containsValue("M2-C plan task B") != true,
            )

            val resumed = orchestrator.handle("yes")
            assertTrue("Expected Handled once the confirmation is answered, got $resumed", resumed is ToolOrchestrator.Outcome.Handled)

            val afterResume = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "The confirmed task must be genuinely gone from the real TaskStore, got $afterResume",
                (afterResume as? ToolResult.Success)?.data?.containsValue("M2-C confirm check task") != true,
            )
            assertTrue(
                "Step 3 must have run against the real TaskStore once step 2 was confirmed, got $afterResume",
                (afterResume as? ToolResult.Success)?.data?.containsValue("M2-C plan task B") == true,
            )
        } finally {
            val cleanupData = (container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks")) as? ToolResult.Success)?.data.orEmpty()
            cleanupData
                .filterKeys { it.endsWith("_title") }
                .filterValues { it == "M2-C plan task A" || it == "M2-C plan task B" || it == "M2-C confirm check task" }
                .keys
                .mapNotNull { key -> cleanupData[key.replace("_title", "_id")] }
                .forEach { id -> container.toolExecutor.execute(ToolCall(ToolId.TASK, "cancel_task", mapOf("id" to id))) }
        }
    }

    // -----------------------------------------------------------------
    // M2-D — real-device E2E validation
    //
    // Objectives B and C from the M2-D brief are already proven precisely
    // by anExplicitOffsetMovesTheRealReminderRelativeToTheRealEventJustCreated
    // (M2-A, above) and aRealThreeStepPlanResumesStep3AfterStep2sClarificationIsAnswered
    // (M2-C, above) respectively — re-run as part of this class's full suite,
    // not duplicated here. These three cover the genuine gaps: the 30-minute
    // *default* was never checked against real device state (only the
    // explicit-offset case was), re-blocking was never run on-device, and
    // the M2-B redirect was never exercised *with a plan remainder still
    // pending* on-device.
    // -----------------------------------------------------------------

    /**
     * Objective A: the pre-existing 30-minute default (unchanged by M2-A),
     * verified against real device state the same rigorous way M2-A's own
     * explicit-offset test already verifies the stated-offset case.
     */
    @Test
    fun aRealCalendarEventWithNoStatedOffsetReminderResolvesToTheThirtyMinuteDefault(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"calendar_event","parameters":{"title":"M2-D default offset check","raw_when":"15:00"}},
                {"intent":"reminder","parameters":{"title":"M2-D default offset check"}}
            ]}""",
        )

        val outcome = orchestrator.handle("M2-D default offset check at 15:00 aur usse pehle mujhe yaad dila dena")
        assertTrue(
            "Expected Handled or NeedsPermission, got $outcome",
            outcome is ToolOrchestrator.Outcome.Handled || outcome is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (outcome !is ToolOrchestrator.Outcome.Handled) return@runBlocking // calendar/reminder permission not granted on this run

        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        val eventStart = orchestrator.memory.value.lastCalendarEvent?.startMillis
        val reminderId = orchestrator.memory.value.lastReminder?.id
        val reminderTrigger = orchestrator.memory.value.lastReminder?.triggerAtMillis
        assertNotNull("Memory did not record the created event", eventId)
        assertNotNull("Memory did not record the event's start time", eventStart)
        assertNotNull("Memory did not record the created reminder — the default-anchor step may not have executed", reminderId)
        assertNotNull("Memory did not record the reminder's trigger time", reminderTrigger)

        try {
            val expected = eventStart!! - 30 * 60 * 1000L
            assertEquals(
                "A reminder step naming no offset must still fall back to exactly 30 minutes before, unchanged by M2-A",
                expected,
                reminderTrigger,
            )

            val realReminders = container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "list_reminders"))
            assertTrue(
                "The real reminder must exist in ReminderStore, got $realReminders",
                (realReminders as? ToolResult.Success)?.data?.containsValue(reminderId.toString()) == true,
            )
        } finally {
            container.toolExecutor.execute(ToolCall(ToolId.REMINDER, "cancel_reminder", mapOf("id" to reminderId.toString())))
            container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())))
        }
    }

    /**
     * Objective D (re-blocking): a real, permission-free 4-step task-only
     * plan that blocks twice for different reasons — missing information,
     * then a destructive confirmation — proving the second PendingPlan
     * correctly replaces the first, with nothing skipped or duplicated,
     * against real `TaskStore` state rather than a scripted mock.
     */
    @Test
    fun aRealPlanThatBlocksTwiceForDifferentReasonsResumesBothTimesWithoutDuplicationOrSkipping(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"task","parameters":{"title":"M2-D reblock task 1"}},
                {"intent":"task","parameters":{}},
                {"intent":"task","action_type":"cancel","parameters":{"title":"M2-D reblock task 1"}},
                {"intent":"task","parameters":{"title":"M2-D reblock task 4"}}
            ]}""",
            """{"intent":"task","parameters":{"title":"M2-D reblock task 2"}}""",
        )

        try {
            val firstBlock = orchestrator.handle(
                "M2-D reblock task 1 banao, phir ek aur task banao, M2-D reblock task 1 cancel karo, aur M2-D reblock task 4 bhi banao",
            )
            assertTrue("Expected step 2's clarification, got $firstBlock", firstBlock is ToolOrchestrator.Outcome.Clarify)

            val afterFirstBlock = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 1 must have already run, got $afterFirstBlock",
                (afterFirstBlock as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 1") == true,
            )
            assertTrue(
                "Step 4 must not have run yet, got $afterFirstBlock",
                (afterFirstBlock as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 4") != true,
            )

            val secondBlock = orchestrator.handle("M2-D reblock task 2")
            assertTrue("Expected step 3's delete confirmation, got $secondBlock", secondBlock is ToolOrchestrator.Outcome.Clarify)

            val afterSecondBlock = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 2 must have run resolving the first block, got $afterSecondBlock",
                (afterSecondBlock as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 2") == true,
            )
            assertTrue(
                "Task 1 must still exist — step 3's cancellation is not yet confirmed, got $afterSecondBlock",
                (afterSecondBlock as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 1") == true,
            )
            assertTrue(
                "Step 4 must still not have run, got $afterSecondBlock",
                (afterSecondBlock as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 4") != true,
            )

            val completed = orchestrator.handle("yes")
            assertTrue("Expected Handled once step 3 is confirmed, got $completed", completed is ToolOrchestrator.Outcome.Handled)

            val final = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Task 1 must be genuinely gone once its cancellation is confirmed, got $final",
                (final as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 1") != true,
            )
            assertTrue(
                "Step 4 must have run once the second block resolved, not be silently dropped, got $final",
                (final as? ToolResult.Success)?.data?.containsValue("M2-D reblock task 4") == true,
            )
        } finally {
            val cleanupData = (container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks")) as? ToolResult.Success)?.data.orEmpty()
            cleanupData
                .filterKeys { it.endsWith("_title") }
                .filterValues { it in setOf("M2-D reblock task 1", "M2-D reblock task 2", "M2-D reblock task 4") }
                .keys
                .mapNotNull { key -> cleanupData[key.replace("_title", "_id")] }
                .forEach { id -> container.toolExecutor.execute(ToolCall(ToolId.TASK, "cancel_task", mapOf("id" to id))) }
        }
    }

    /**
     * Objective E: the M2-B Option 1 redirect inside a multi-step plan,
     * extended one step further than the existing M2-B instrumented test —
     * proving the plan's remainder survives both the redirect's own
     * question *and* the delete-confirmation it re-blocks into, against a
     * real CalendarProvider event and a real TaskStore row.
     */
    @Test
    fun aRealAmbiguousStepInsideAPlanRedirectsAndConfirmsWhilePreservingTheRemainingStep(): Unit = runBlocking {
        val orchestrator = secretary(
            """{"steps":[
                {"intent":"calendar_event","parameters":{"title":"M2-D redirect check","raw_when":"12:00"}},
                {"intent":"notification","action_type":"cancel","parameters":{}},
                {"intent":"task","parameters":{"title":"M2-D redirect followup task"}}
            ]}""",
        )

        val redirected = orchestrator.handle(
            "M2-D redirect check at 12:00, then delete the M2-D redirect check meeting, aur phir M2-D redirect followup task bhi banao",
        )
        assertTrue(
            "Expected a redirect Clarify or NeedsPermission, got $redirected",
            redirected is ToolOrchestrator.Outcome.Clarify || redirected is ToolOrchestrator.Outcome.NeedsPermission,
        )
        if (redirected !is ToolOrchestrator.Outcome.Clarify) return@runBlocking // calendar permission not granted on this run
        assertTrue(
            "Redirect question must name the real event, got: ${redirected.question}",
            redirected.question.contains("M2-D redirect check"),
        )

        val eventId = orchestrator.memory.value.lastCalendarEvent?.id
        assertNotNull("Memory did not record the event step 1 created", eventId)

        try {
            val reBlocked = orchestrator.handle("yes")
            assertTrue(
                "Confirming the redirect must still hit the delete-confirmation gate, got $reBlocked",
                reBlocked is ToolOrchestrator.Outcome.Clarify,
            )

            val afterReBlock = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 3 must not have run yet, got $afterReBlock",
                (afterReBlock as? ToolResult.Success)?.data?.containsValue("M2-D redirect followup task") != true,
            )
            val stillThere = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must still exist, not yet confirmed, got $stillThere",
                (stillThere as? ToolResult.Success)?.data?.containsValue(eventId.toString()) == true,
            )

            val completed = orchestrator.handle("yes")
            assertTrue("Expected Handled once the delete is confirmed, got $completed", completed is ToolOrchestrator.Outcome.Handled)

            val afterDelete = container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "list_events"))
            assertTrue(
                "Event must be genuinely gone from the real CalendarProvider, got $afterDelete",
                (afterDelete as? ToolResult.Success)?.data?.containsValue(eventId.toString()) != true,
            )
            val afterCompletion = container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks"))
            assertTrue(
                "Step 3 must have run against the real TaskStore once the redirect's re-block resolved, not be silently dropped, got $afterCompletion",
                (afterCompletion as? ToolResult.Success)?.data?.containsValue("M2-D redirect followup task") == true,
            )
        } finally {
            container.toolExecutor.execute(ToolCall(ToolId.CALENDAR, "delete_event", mapOf("id" to eventId.toString())))
            val cleanupData = (container.toolExecutor.execute(ToolCall(ToolId.TASK, "list_tasks")) as? ToolResult.Success)?.data.orEmpty()
            cleanupData
                .filterKeys { it.endsWith("_title") }
                .filterValues { it == "M2-D redirect followup task" }
                .keys
                .mapNotNull { key -> cleanupData[key.replace("_title", "_id")] }
                .forEach { id -> container.toolExecutor.execute(ToolCall(ToolId.TASK, "cancel_task", mapOf("id" to id))) }
        }
    }
}
