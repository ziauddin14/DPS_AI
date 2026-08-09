package com.softwaremine.dps.ai.secretary

import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.plan.Confirmation
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.plan.contactCandidatesFrom
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentResolution
import com.softwaremine.dps.domain.intent.IntentType
import com.softwaremine.dps.domain.intent.PendingPermissionAction
import com.softwaremine.dps.domain.intent.toolId
import com.softwaremine.dps.domain.memory.ConversationMemory
import com.softwaremine.dps.domain.secretary.PendingConfirmation
import com.softwaremine.dps.domain.secretary.PendingContactSelection
import com.softwaremine.dps.domain.secretary.SecretaryEvent
import com.softwaremine.dps.domain.secretary.SecretaryState
import com.softwaremine.dps.domain.secretary.SecretaryStateMachine
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The AI Secretary experience layer (Day 05 Phase E).
 *
 * ## Purpose
 * Where [ai.intent.ToolOrchestrator] classifies and executes one self-contained
 * request, this class is what turns that into a *conversation* — one where
 * "usko 30 minute pehle kar do" means something, where "Ali ko WhatsApp kar
 * do" asks which Ali instead of guessing, and where "kal 4 baje Abdul ke
 * saath meeting schedule kar do aur 30 minutes pehle reminder laga do" runs
 * as two dependent steps and reports honestly on both.
 *
 * ## How it uses `ToolOrchestrator` without bypassing it
 * Classification, permission gating, single-tool execution and response
 * phrasing are exactly [ToolOrchestrator]'s job and stay exactly there — this
 * class calls [ToolOrchestrator.classify]/[ToolOrchestrator.classifyPlan] and
 * [ToolOrchestrator.executeIntent] rather than reimplementing either. Every
 * new Stage 2 capability is a seam *around* those calls: resolving which
 * contact "usko" or a bare name means before executing
 * ([ContactSelectionParser], reusing the same `find_contact` tool Phase C
 * built), deciding whether an action needs a yes first
 * ([ConfirmationParser]), offering a related action afterwards
 * ([FollowUpSuggestionGenerator]), and running more than one step in order
 * when a message asks for more than one thing ([ToolOrchestrator.classifyPlan]).
 * None of it duplicates classification, clarification, execution or response
 * phrasing — all four still live only in [ToolOrchestrator] and the pure
 * classes it composes.
 *
 * ## Dependencies
 * [ToolOrchestrator] and the pure `ai/memory` and `ai/plan` components. No
 * Android.
 */
class SecretaryOrchestrator(
    private val toolOrchestrator: ToolOrchestrator,
    private val referenceResolver: ReferenceResolver,
    private val actionDetector: ActionDetector,
    private val clarification: ClarificationEngine,
    private val memoryUpdater: ConversationMemoryUpdater,
    private val contactSelectionParser: ContactSelectionParser,
    private val confirmationParser: ConfirmationParser,
    private val followUpSuggestions: FollowUpSuggestionGenerator,
    private val logger: DpsLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(SecretaryState.IDLE)
    val state: StateFlow<SecretaryState> = _state.asStateFlow()

    private val _memory = MutableStateFlow(ConversationMemory.EMPTY)
    val memory: StateFlow<ConversationMemory> = _memory.asStateFlow()

    /** The follow-up DPS last asked, and what it already understood. Local to this class. */
    private var pendingClarification: IntentResolution.NeedsClarification? = null

    /** A request held because a contact lookup found more than one match. */
    private var pendingContactSelection: PendingContactSelection? = null

    /** A follow-up suggestion, or a destructive action, waiting on a yes/no. */
    private var pendingConfirmation: PendingConfirmation? = null

    /**
     * The request a `find_contact` pre-resolution step was run on behalf of,
     * held only while that lookup itself is blocked on a permission — see
     * [resolveContactThenExecute] and [onPermissionResult].
     */
    private var pendingIntentAwaitingContactPermission: DpsIntent? = null

    /**
     * Handles one user message.
     *
     * Never throws — every path resolves to a [ToolOrchestrator.Outcome],
     * mirroring [ToolOrchestrator.handle]'s own guarantee, since a thrown
     * exception here would surface to the user as a crash.
     */
    suspend fun handle(userMessage: String): ToolOrchestrator.Outcome {
        pendingContactSelection?.let { return resolveContactSelection(userMessage, it) }
        pendingConfirmation?.let { return resolveConfirmation(userMessage, it) }

        val awaiting = pendingClarification

        // A reply while WAITING_MISSING_INFORMATION is an answer, not a fresh
        // request — SecretaryEvent.MessageReceived is a no-op from that state
        // (only InformationProvided leaves it), so firing the wrong event here
        // would strand the state machine in WAITING_MISSING_INFORMATION even
        // after the request goes on to execute successfully.
        _state.value = transition(
            if (awaiting != null) SecretaryEvent.InformationProvided else SecretaryEvent.MessageReceived,
        )

        val steps = toolOrchestrator.classifyPlan(userMessage, awaiting?.question)
            ?: run {
                _state.value = transition(SecretaryEvent.Reset)
                return ToolOrchestrator.Outcome.Conversational("classification failed")
            }

        if (steps.size > 1) {
            // A compound request discards any earlier pending question — it is
            // a fresh, self-contained instruction, not an answer to one.
            pendingClarification = null
            return handlePlan(steps)
        }

        return handleSingleStep(userMessage, steps.single(), awaiting)
    }

    /** The single-intent path — Stage 1's flow, now feeding into the Stage 2 execution seam. */
    private suspend fun handleSingleStep(
        userMessage: String,
        classified: DpsIntent,
        awaiting: IntentResolution.NeedsClarification?,
    ): ToolOrchestrator.Outcome {
        // Mirrors ToolOrchestrator.handle's own merge step exactly — an answer
        // to a follow-up carries only the missing piece, so it is folded into
        // what was already understood rather than replacing it.
        val resolved = if (awaiting != null && classified.type != IntentType.CONVERSATION) {
            classified.copy(parameters = clarification.merge(awaiting.partial, classified.parameters))
        } else if (awaiting != null) {
            awaiting.intent.copy(parameters = clarification.merge(awaiting.partial, classified.parameters))
        } else {
            classified
        }

        if (resolved.type == IntentType.CONVERSATION) {
            pendingClarification = null
            _state.value = transition(SecretaryEvent.Reset)
            return ToolOrchestrator.Outcome.Conversational("model classified as conversation")
        }

        // The enrichment seam Phase D had no room for: correct the action the
        // model guessed, then fill any gap a reference to memory can answer.
        val enriched = referenceResolver.resolve(
            rawText = userMessage,
            intent = resolved.copy(action = actionDetector.detect(userMessage, resolved.action)),
            memory = _memory.value,
        )

        return when (val check = clarification.check(enriched)) {
            is ClarificationEngine.Check.Missing -> {
                val needs = IntentResolution.NeedsClarification(
                    intent = enriched,
                    question = check.question,
                    missing = check.fields,
                    partial = enriched.parameters,
                )
                pendingClarification = needs
                logger.i(TAG, "Clarifying ${enriched.type}: missing ${check.fields}")
                _state.value = transition(SecretaryEvent.ClarificationNeeded)
                ToolOrchestrator.Outcome.Clarify(check.question, needs)
            }

            ClarificationEngine.Check.Complete -> {
                pendingClarification = null
                proceedToExecution(enriched)
            }
        }
    }

    /**
     * Resumes an action that was held on [ToolOrchestrator] pending a
     * permission — either the original request, or a `find_contact`
     * pre-resolution step run ahead of it (see [resolveContactThenExecute]).
     */
    suspend fun onPermissionResult(): ToolOrchestrator.Outcome? {
        _state.value = transition(SecretaryEvent.PermissionGranted)
        val outcome = toolOrchestrator.resumeAfterPermissionGrant()
            ?: run {
                pendingIntentAwaitingContactPermission = null
                _state.value = transition(SecretaryEvent.Reset)
                return null
            }

        val awaitingContact = pendingIntentAwaitingContactPermission
        return if (awaitingContact != null && outcome is ToolOrchestrator.Outcome.Handled) {
            pendingIntentAwaitingContactPermission = null
            continueAfterContactLookup(awaitingContact, outcome)
        } else {
            recordOutcome(outcome)
        }
    }

    /** Discards any pending question or held action, and forgets the conversation so far. */
    fun reset() {
        pendingClarification = null
        pendingContactSelection = null
        pendingConfirmation = null
        pendingIntentAwaitingContactPermission = null
        toolOrchestrator.reset()
        _state.value = transition(SecretaryEvent.Reset)
        _memory.value = ConversationMemory.EMPTY
    }

    /** The follow-up currently awaiting an answer, if any. */
    fun pendingQuestion(): String? = pendingClarification?.question
        ?: pendingContactSelection?.let { candidateQuestion(it.candidates) }
        ?: pendingConfirmation?.let { CONFIRMATION_REPROMPT }

    /** Whether an action is held pending a permission grant. */
    fun hasPendingPermissionAction(): Boolean = toolOrchestrator.hasPendingPermissionAction()

    // -----------------------------------------------------------------
    // Multi-step planning
    // -----------------------------------------------------------------

    /**
     * Runs [steps] in order, stopping at the first one that does not finish
     * ([ClarificationEngine.Check.Missing], a permission, or contact
     * ambiguity) or fails outright.
     *
     * ## Scope (documented, not hidden)
     * A block mid-plan resolves the *one* step it blocked — permission
     * granted, contact chosen, missing detail supplied — through exactly the
     * same single-step resume paths Stage 1 already has. Steps after the
     * blocked one are not automatically resumed; the report says so
     * explicitly rather than silently dropping them. Reaching a genuinely
     * useful "resume step 3 of 4" would need every one of those single-step
     * resume paths to also carry the rest of the plan, which is real new
     * surface area deferred rather than half-built here.
     *
     * ## Why no cue-based enrichment runs per step
     * [ReferenceResolver] and [ActionDetector] both work from the *raw text
     * the user typed*, and a compound message's raw text does not belong to
     * any one step — running either against the whole message for every step
     * risks a cue meant for step 2 misfiring on step 1. Each step therefore
     * carries only what the model itself put in it, plus [anchorToPriorEvent]
     * — the one deterministic cross-step rule this class knows: a bodiless,
     * timeless reminder step immediately following a calendar event step
     * defaults to 30 minutes before that event, the convention the brief's
     * own worked example names. A different offset in the same compound
     * message is not yet parsed; asking for it as a follow-up message still
     * works, via [ReferenceResolver]'s existing single-turn path.
     */
    private suspend fun handlePlan(steps: List<DpsIntent>): ToolOrchestrator.Outcome {
        val replies = mutableListOf<String>()
        var lastResult: ToolResult = ToolResult.Cancelled("No steps were run.")
        var lastIntent: DpsIntent = steps.first()
        var lastEventStartMillis: Long? = null

        for (raw in steps) {
            val step = anchorToPriorEvent(raw, lastEventStartMillis)

            when (val check = clarification.check(step)) {
                is ClarificationEngine.Check.Missing -> {
                    val needs = IntentResolution.NeedsClarification(step, check.question, check.fields, step.parameters)
                    pendingClarification = needs
                    _state.value = transition(SecretaryEvent.ClarificationNeeded)
                    return ToolOrchestrator.Outcome.Clarify(prefixed(replies, check.question), needs)
                }

                ClarificationEngine.Check.Complete -> Unit
            }

            when (val outcome = proceedToExecution(step)) {
                is ToolOrchestrator.Outcome.Handled -> {
                    replies += outcome.reply
                    lastResult = outcome.result
                    lastIntent = outcome.intent

                    val success = outcome.result as? ToolResult.Success
                    if (step.type == IntentType.CALENDAR_EVENT && success != null) {
                        lastEventStartMillis = success.data["start"]?.let(::parseLocalMillis)
                    }

                    if (!outcome.result.isSuccess) {
                        // A required step failed — stop rather than run later
                        // steps against a plan that has already gone wrong.
                        return ToolOrchestrator.Outcome.Handled(replies.joinToString(" "), lastIntent, lastResult)
                    }
                }

                is ToolOrchestrator.Outcome.Clarify ->
                    return outcome.copy(question = prefixed(replies, outcome.question))

                is ToolOrchestrator.Outcome.NeedsPermission ->
                    return outcome.copy(reply = prefixed(replies, outcome.reply))

                is ToolOrchestrator.Outcome.Conversational -> return outcome
            }
        }

        return ToolOrchestrator.Outcome.Handled(replies.joinToString(" "), lastIntent, lastResult)
    }

    private fun prefixed(completedReplies: List<String>, next: String): String =
        if (completedReplies.isEmpty()) next else "${completedReplies.joinToString(" ")} $next"

    /** The one deterministic cross-step rule — see [handlePlan]'s doc. */
    private fun anchorToPriorEvent(step: DpsIntent, priorEventStartMillis: Long?): DpsIntent {
        if (priorEventStartMillis == null) return step
        if (step.type != IntentType.REMINDER || step.action != IntentAction.CREATE) return step
        if (step.parameters.value(IntentField.DATE) != null || step.parameters.value(IntentField.TIME) != null) {
            return step
        }

        val zoned = Instant.ofEpochMilli(priorEventStartMillis - DEFAULT_REMINDER_LEAD_MILLIS).atZone(zone)
        return step.copy(
            parameters = step.parameters.copy(
                date = zoned.format(DateTimeFormatter.ISO_LOCAL_DATE),
                time = zoned.format(DateTimeFormatter.ofPattern("HH:mm")),
            ),
        )
    }

    // -----------------------------------------------------------------
    // Execution — contact pre-resolution, destructive confirmation, suggestions
    // -----------------------------------------------------------------

    /**
     * The seam between "this request is complete" and actually calling
     * [ToolOrchestrator.executeIntent] — where contact grounding, a
     * destructive-action confirmation, or a follow-up suggestion can each
     * insert themselves without [ToolOrchestrator] ever needing to know any
     * of them exist.
     */
    private suspend fun proceedToExecution(intent: DpsIntent): ToolOrchestrator.Outcome {
        if (intent.type in DELETE_CONFIRMATION_TYPES && intent.action == IntentAction.CANCEL) {
            return askDeleteConfirmation(intent)
        }

        val personName = intent.parameters.value(IntentField.PERSON)
        val needsResolution = personName != null &&
            intent.action == IntentAction.CREATE &&
            intent.type in PERSON_GROUNDING_TYPES

        return if (needsResolution) {
            resolveContactThenExecute(intent, personName!!)
        } else {
            finishExecution(intent)
        }
    }

    private suspend fun finishExecution(intent: DpsIntent): ToolOrchestrator.Outcome {
        val recorded = recordOutcome(toolOrchestrator.executeIntent(intent))
        return attachSuggestionIfApplicable(recorded)
    }

    /**
     * Asks before deleting.
     *
     * Requirement 6: "Require confirmation before destructive deletion if the
     * existing UX does not already provide one." Nothing upstream of this
     * class does — [ToolOrchestrator]'s pipeline has no concept of
     * confirmation at all — so this is that gate. For [IntentType.CALENDAR_EVENT],
     * [intent.parameters.targetId] is guaranteed resolved by the time this
     * runs — [ClarificationEngine]'s `TARGETABLE_TYPES` check already ran in
     * [handleSingleStep]/[handlePlan] before [proceedToExecution] was ever
     * reached. [IntentType.TASK] (Day 06) is different: its own
     * `TITLE_ADDRESSABLE_TYPES` check accepts a spoken title as the address
     * instead, so [describeDeletionTarget] reads whichever of `targetId`/`title`
     * is actually present rather than assuming an id.
     */
    private fun askDeleteConfirmation(intent: DpsIntent): ToolOrchestrator.Outcome {
        val title = describeDeletionTarget(intent) ?: "that ${DELETE_TARGET_LABELS[intent.type]}"
        val question = "Delete \"$title\"? This can't be undone."

        pendingConfirmation = PendingConfirmation(intent, now())
        _state.value = transition(SecretaryEvent.ConfirmationRequested)
        return ToolOrchestrator.Outcome.Clarify(
            question,
            IntentResolution.NeedsClarification(intent, question, emptySet(), intent.parameters),
        )
    }

    /** What to name in the confirmation question. See [askDeleteConfirmation]'s doc. */
    private fun describeDeletionTarget(intent: DpsIntent): String? = when (intent.type) {
        IntentType.CALENDAR_EVENT -> _memory.value.lastCalendarEvent
            ?.takeIf { it.id.toString() == intent.parameters.targetId }
            ?.title

        IntentType.TASK -> intent.parameters.value(IntentField.TITLE)
            ?: _memory.value.lastTask
                ?.takeIf { it.id.toString() == intent.parameters.targetId }
                ?.title

        else -> null
    }

    /** Appends a templated follow-up suggestion after a successful create, and holds it for a yes/no. */
    private fun attachSuggestionIfApplicable(outcome: ToolOrchestrator.Outcome): ToolOrchestrator.Outcome {
        if (outcome !is ToolOrchestrator.Outcome.Handled) return outcome
        val success = outcome.result as? ToolResult.Success ?: return outcome
        val suggestion = followUpSuggestions.suggestionFor(outcome.intent, success) ?: return outcome

        pendingConfirmation = PendingConfirmation(suggestion.intent, now())
        _state.value = transition(SecretaryEvent.ConfirmationRequested)
        return outcome.copy(reply = "${outcome.reply} ${suggestion.text}")
    }

    // -----------------------------------------------------------------
    // Contact disambiguation
    // -----------------------------------------------------------------

    /**
     * Grounds [personName] to a real contact before running [intent] —
     * requirement 2. Reuses the exact `find_contact` tool and `ContactResolver`
     * Phase C built (via [ToolOrchestrator.executeIntent] on a synthesised
     * [IntentType.CONTACT_LOOKUP]), rather than duplicating any resolution
     * logic here.
     */
    private suspend fun resolveContactThenExecute(intent: DpsIntent, personName: String): ToolOrchestrator.Outcome {
        val lookup = DpsIntent(type = IntentType.CONTACT_LOOKUP, parameters = IntentParameters(person = personName))

        return when (val lookupOutcome = toolOrchestrator.executeIntent(lookup)) {
            is ToolOrchestrator.Outcome.NeedsPermission -> {
                pendingIntentAwaitingContactPermission = intent
                _state.value = transition(SecretaryEvent.PermissionNeeded)
                lookupOutcome
            }

            is ToolOrchestrator.Outcome.Handled -> continueAfterContactLookup(intent, lookupOutcome)

            // find_contact always maps to a registered tool; unreachable in
            // practice, but failing open to the bare-name attempt is safer
            // than surfacing an outcome about a lookup the user never asked
            // for by name.
            else -> finishExecution(intent)
        }
    }

    private suspend fun continueAfterContactLookup(
        original: DpsIntent,
        lookupOutcome: ToolOrchestrator.Outcome.Handled,
    ): ToolOrchestrator.Outcome {
        val success = lookupOutcome.result as? ToolResult.Success

        return when {
            success != null && success.data["ambiguous"] == "true" -> {
                val candidates = contactCandidatesFrom(success.data)
                if (candidates.isEmpty()) {
                    finishExecution(original)
                } else {
                    pendingContactSelection = PendingContactSelection(original, candidates, now())
                    _state.value = transition(SecretaryEvent.ContactAmbiguous)
                    val question = candidateQuestion(candidates)
                    ToolOrchestrator.Outcome.Clarify(
                        question,
                        IntentResolution.NeedsClarification(original, question, emptySet(), original.parameters),
                    )
                }
            }

            success != null && success.data["contact_id"] != null -> {
                _memory.value = memoryUpdater.remember(
                    memory = _memory.value,
                    intent = DpsIntent(IntentType.CONTACT_LOOKUP, IntentParameters(person = original.parameters.value(IntentField.PERSON))),
                    toolId = ToolId.CONTACTS,
                    result = success,
                    nowMillis = now(),
                )
                finishExecution(applyResolvedContactData(original, success.data))
            }

            original.type in REQUIRES_RESOLVED_CONTACT ->
                // The tool structurally needs an address; trying the bare name
                // would fail again, less informatively than the lookup already did.
                recordOutcome(ToolOrchestrator.Outcome.Handled(lookupOutcome.reply, original, lookupOutcome.result))

            // Best-effort grounding (a calendar event naming someone) — the
            // tool never needed the resolved contact to function, so proceed
            // with the bare name rather than blocking a request that was
            // otherwise complete.
            else -> finishExecution(original)
        }
    }

    private suspend fun resolveContactSelection(
        userMessage: String,
        selection: PendingContactSelection,
    ): ToolOrchestrator.Outcome {
        if (!selection.isFresh(now())) {
            pendingContactSelection = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        val chosen = contactSelectionParser.parse(userMessage, selection.candidates)
            ?: run {
                val question = candidateQuestion(selection.candidates)
                return ToolOrchestrator.Outcome.Clarify(
                    question,
                    IntentResolution.NeedsClarification(selection.originalIntent, question, emptySet(), selection.originalIntent.parameters),
                )
            }

        pendingContactSelection = null
        _state.value = transition(SecretaryEvent.ContactSelected)
        _memory.value = _memory.value.copy(
            lastContact = chosen,
            lastReferencedPerson = chosen.displayName,
            updatedAtMillis = now(),
        )
        return finishExecution(applyResolvedContact(selection.originalIntent, chosen))
    }

    private fun applyResolvedContactData(original: DpsIntent, data: Map<String, String>): DpsIntent {
        val name = data["name"] ?: return original
        var params = original.parameters.copy(person = name)
        when (original.type) {
            IntentType.WHATSAPP_MESSAGE -> data["phone"]?.let { params = params.copy(phone = it) }
            IntentType.EMAIL_MESSAGE -> data["email"]?.let { params = params.copy(email = it) }
            else -> Unit
        }
        return original.copy(parameters = params)
    }

    private fun applyResolvedContact(original: DpsIntent, contact: Contact): DpsIntent {
        var params = original.parameters.copy(person = contact.displayName)
        when (original.type) {
            IntentType.WHATSAPP_MESSAGE -> contact.primaryPhone?.let { params = params.copy(phone = it) }
            IntentType.EMAIL_MESSAGE -> contact.primaryEmail?.let { params = params.copy(email = it) }
            else -> Unit
        }
        return original.copy(parameters = params)
    }

    private fun candidateQuestion(candidates: List<Contact>): String {
        val listed = candidates.mapIndexed { index, contact -> "${index + 1}) ${contact.displayName}" }
            .joinToString(", ")
        return "Several contacts match: $listed. Which one?"
    }

    // -----------------------------------------------------------------
    // Confirmation (destructive actions and follow-up suggestions)
    // -----------------------------------------------------------------

    /**
     * ## Why [Confirmation.UNCLEAR] falls through instead of re-asking
     * A real secretary who asks "want a reminder for that too?" and gets
     * "actually, also email Sara the agenda" does not block on repeating the
     * question — they help with what was actually just said. Insisting on a
     * literal yes/no here would trap every unrelated next message behind a
     * suggestion the user has, in effect, already moved past. The suggestion
     * is treated as implicitly declined, and [userMessage] is processed as
     * the fresh request it actually is — including a genuinely unclear reply
     * like "maybe", which then reaches the model and comes back as ordinary
     * conversation, an equally honest outcome.
     */
    private suspend fun resolveConfirmation(userMessage: String, pending: PendingConfirmation): ToolOrchestrator.Outcome {
        if (!pending.isFresh(now())) {
            pendingConfirmation = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        return when (confirmationParser.parse(userMessage)) {
            Confirmation.YES -> {
                pendingConfirmation = null
                _state.value = transition(SecretaryEvent.ConfirmationAccepted)
                finishExecution(pending.intent)
            }

            Confirmation.NO -> {
                pendingConfirmation = null
                _state.value = transition(SecretaryEvent.ConfirmationDeclined)
                ToolOrchestrator.Outcome.Handled(
                    reply = "Alright, I've left it as is.",
                    intent = pending.intent,
                    result = ToolResult.Cancelled("Declined."),
                )
            }

            Confirmation.UNCLEAR -> {
                pendingConfirmation = null
                _state.value = transition(SecretaryEvent.ConfirmationDeclined)
                handle(userMessage)
            }
        }
    }

    // -----------------------------------------------------------------

    private fun transition(event: SecretaryEvent): SecretaryState =
        SecretaryStateMachine.transition(_state.value, event)

    /** Applies memory and state consequences for an outcome produced by [ToolOrchestrator]. */
    private fun recordOutcome(outcome: ToolOrchestrator.Outcome): ToolOrchestrator.Outcome {
        when (outcome) {
            is ToolOrchestrator.Outcome.Handled -> {
                outcome.intent.type.toolId?.let { toolId ->
                    _memory.value = memoryUpdater.remember(
                        memory = _memory.value,
                        intent = outcome.intent,
                        toolId = toolId,
                        result = outcome.result,
                        nowMillis = now(),
                    )
                }
                _state.value = transition(
                    if (outcome.result.isSuccess) {
                        SecretaryEvent.ExecutionSucceeded
                    } else {
                        SecretaryEvent.ExecutionFailed
                    },
                )
            }

            is ToolOrchestrator.Outcome.NeedsPermission -> {
                _state.value = transition(SecretaryEvent.PermissionNeeded)
            }

            is ToolOrchestrator.Outcome.Conversational -> {
                _state.value = transition(SecretaryEvent.Reset)
            }

            is ToolOrchestrator.Outcome.Clarify -> {
                // executeIntent()/resumeAfterPermissionGrant() never actually
                // produce this — only handle() does, before executing, and
                // this class never calls handle(). Handled for exhaustiveness.
                logger.w(TAG, "Unexpected Clarify outcome from executeIntent/resume")
                _state.value = transition(SecretaryEvent.Reset)
            }
        }
        return outcome
    }

    /** Parses a [com.softwaremine.dps.data.android.common.ToolArguments.describe] string back to epoch millis. */
    private fun parseLocalMillis(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        const val TAG = "SecretaryOrchestrator"

        const val CONFIRMATION_REPROMPT = "Sorry, yes or no?"

        /** 30 minutes — see [handlePlan]'s doc for why this is a fixed default. */
        const val DEFAULT_REMINDER_LEAD_MILLIS = 30L * 60L * 1000L

        /** Intent types where grounding a bare name to a real contact is worth attempting. */
        val PERSON_GROUNDING_TYPES = setOf(
            IntentType.WHATSAPP_MESSAGE,
            IntentType.EMAIL_MESSAGE,
            IntentType.CALENDAR_EVENT,
        )

        /** Of [PERSON_GROUNDING_TYPES], the ones that cannot proceed at all without a resolved contact. */
        val REQUIRES_RESOLVED_CONTACT = setOf(IntentType.WHATSAPP_MESSAGE, IntentType.EMAIL_MESSAGE)

        /** Intent types whose CANCEL asks for confirmation before deleting (Day 06 adds TASK). */
        val DELETE_CONFIRMATION_TYPES = setOf(IntentType.CALENDAR_EVENT, IntentType.TASK)

        /** Fallback noun for [askDeleteConfirmation] when nothing more specific is known. */
        val DELETE_TARGET_LABELS = mapOf(
            IntentType.CALENDAR_EVENT to "event",
            IntentType.TASK to "task",
        )
    }
}
