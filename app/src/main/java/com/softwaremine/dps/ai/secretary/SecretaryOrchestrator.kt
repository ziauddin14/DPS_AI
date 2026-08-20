package com.softwaremine.dps.ai.secretary

import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.ReferenceResolver
import com.softwaremine.dps.ai.memory.TemporalGroundingGuard
import com.softwaremine.dps.ai.memory.TemporalPhraseResolver
import com.softwaremine.dps.ai.memory.TemporalStepAttributor
import com.softwaremine.dps.ai.plan.Confirmation
import com.softwaremine.dps.ai.plan.ConfirmationParser
import com.softwaremine.dps.ai.plan.ContactSelectionParser
import com.softwaremine.dps.ai.plan.FollowUpSuggestionGenerator
import com.softwaremine.dps.ai.plan.contactCandidatesFrom
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.data.android.memory.PersistentMemoryStore
import com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore
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
import com.softwaremine.dps.domain.secretary.DisambiguationCandidate
import com.softwaremine.dps.domain.secretary.PendingConfirmation
import com.softwaremine.dps.domain.secretary.PendingContactSelection
import com.softwaremine.dps.domain.secretary.PendingPlan
import com.softwaremine.dps.domain.secretary.PendingTypeDisambiguation
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
 * [ToolOrchestrator] and the pure `ai/memory` and `ai/plan` components. The
 * exceptions are [PersistentMemoryStore] (M3-B) and [PersistentPreferenceStore]
 * (M3-C finalization): this class still runs unmodified on the JVM (every
 * existing test needs no Android runtime — see [PersistentMemoryStore]'s own
 * doc for why its constructor takes `SharedPreferences` rather than `Context`
 * for exactly this reason, which [PersistentPreferenceStore] mirrors), but
 * both types live in `data/android`, the only Android-adjacent names this
 * file imports.
 */
class SecretaryOrchestrator(
    private val toolOrchestrator: ToolOrchestrator,
    private val referenceResolver: ReferenceResolver,
    private val temporalPhraseResolver: TemporalPhraseResolver,
    private val temporalGroundingGuard: TemporalGroundingGuard,
    private val temporalStepAttributor: TemporalStepAttributor,
    private val actionDetector: ActionDetector,
    private val clarification: ClarificationEngine,
    private val memoryUpdater: ConversationMemoryUpdater,
    private val contactSelectionParser: ContactSelectionParser,
    private val confirmationParser: ConfirmationParser,
    private val followUpSuggestions: FollowUpSuggestionGenerator,
    private val persistentMemoryStore: PersistentMemoryStore,
    private val persistentPreferenceStore: PersistentPreferenceStore,
    private val logger: DpsLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(SecretaryState.IDLE)
    val state: StateFlow<SecretaryState> = _state.asStateFlow()

    // M3-B: seeded from durable storage rather than always starting EMPTY,
    // so "usko"/"that reminder" still resolves after a process restart.
    // PersistentMemoryStore.load() already falls back to EMPTY itself (on
    // first run or corrupt storage), so no fallback is duplicated here.
    private val _memory = MutableStateFlow(persistentMemoryStore.load())
    val memory: StateFlow<ConversationMemory> = _memory.asStateFlow()

    /** The follow-up DPS last asked, and what it already understood. Local to this class. */
    private var pendingClarification: IntentResolution.NeedsClarification? = null

    /** A request held because a contact lookup found more than one match. */
    private var pendingContactSelection: PendingContactSelection? = null

    /**
     * A request held because its classification named a type with no
     * resolvable target while memory held a plausible alternative it never
     * considered (Day 09, Option 1). See [disambiguationCandidates].
     */
    private var pendingTypeDisambiguation: PendingTypeDisambiguation? = null

    /** A follow-up suggestion, or a destructive action, waiting on a yes/no. */
    private var pendingConfirmation: PendingConfirmation? = null

    /**
     * The unexecuted remainder of a multi-step plan, held alongside whichever
     * of the four pending fields above is currently blocking step N (M2-C).
     * See [PendingPlan]'s own doc for exactly what it does and does not own.
     */
    private var pendingPlan: PendingPlan? = null

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
     *
     * @param recentContext see [com.softwaremine.dps.ai.intent.IntentPromptBuilder.build]
     *   (Day 08-B) — the last exchange, pre-rendered as plain text, or `null`
     *   when there is none. Threaded through only so the classification pass
     *   has enough context to answer directly when it turns out to be
     *   conversation; nothing else here reads it.
     */
    suspend fun handle(userMessage: String, recentContext: String? = null): ToolOrchestrator.Outcome {
        pendingContactSelection?.let { return resolveContactSelection(userMessage, it) }
        pendingConfirmation?.let { return resolveConfirmation(userMessage, it) }
        pendingTypeDisambiguation?.let { return resolveTypeDisambiguation(userMessage, it) }

        val awaiting = pendingClarification

        // A reply while WAITING_MISSING_INFORMATION is an answer, not a fresh
        // request — SecretaryEvent.MessageReceived is a no-op from that state
        // (only InformationProvided leaves it), so firing the wrong event here
        // would strand the state machine in WAITING_MISSING_INFORMATION even
        // after the request goes on to execute successfully.
        _state.value = transition(
            if (awaiting != null) SecretaryEvent.InformationProvided else SecretaryEvent.MessageReceived,
        )

        val steps = toolOrchestrator.classifyPlan(userMessage, awaiting?.question, recentContext)
            ?: run {
                _state.value = transition(SecretaryEvent.Reset)
                return ToolOrchestrator.Outcome.Conversational("classification failed")
            }

        if (steps.size > 1) {
            // A compound request discards any earlier pending question — it is
            // a fresh, self-contained instruction, not an answer to one.
            pendingClarification = null
            return handlePlan(userMessage, steps)
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
        //
        // The merge only fires when the new classification names the *same*
        // intent type the pending question was actually about (or gave up
        // and classified as bare conversation, which carries no fields of
        // its own to conflict with anything). A message that classifies as a
        // genuinely different type is a fresh, self-contained request, not
        // an answer — merging would otherwise let a stale field from the
        // abandoned question (e.g. a reminder's `message`) silently leak
        // into an unrelated new intent (e.g. a notification), which could
        // make that new request look complete when the model supplied
        // nothing of its own. Investigation evidence: a dangling "when
        // should I remind you?" clarification's leftover message field was
        // observed satisfying a completely unrelated NOTIFICATION request's
        // required-field check on a later turn.
        val resolved = when {
            awaiting == null -> classified

            classified.type == IntentType.CONVERSATION ->
                awaiting.intent.copy(parameters = clarification.merge(awaiting.partial, classified.parameters))

            classified.type == awaiting.intent.type ->
                classified.copy(parameters = clarification.merge(awaiting.partial, classified.parameters))

            else -> {
                // M2-C: a message that does not answer the pending
                // clarification abandons whatever plan remainder was
                // parked behind it too — the remainder only ever made
                // sense in the context of the step being abandoned here.
                pendingPlan = null
                classified
            }
        }

        if (resolved.type == IntentType.CONVERSATION) {
            pendingClarification = null
            _state.value = transition(SecretaryEvent.Reset)
            // Day 08-B: the same classification pass that decided this is
            // conversation may have already answered it (parameters.reply
            // — see IntentPromptBuilder). When it did, this reply is used
            // directly and AiSessionManager skips the second, streaming
            // generation pass entirely. When it did not — the common,
            // always-safe case — replyText is null and behaviour is
            // byte-for-byte what it was before this field existed.
            return ToolOrchestrator.Outcome.Conversational(
                reason = "model classified as conversation",
                replyText = usableReply(userMessage, resolved.parameters.reply),
            )
        }

        // The enrichment seam Phase D had no room for: correct the action the
        // model guessed, then fill any gap a reference to memory can answer.
        val referenced = referenceResolver.resolve(
            rawText = userMessage,
            intent = resolved.copy(action = actionDetector.detect(userMessage, resolved.action)),
            memory = _memory.value,
        )
        val enriched = withResolvedTemporalPhrase(userMessage, referenced)

        val check = clarification.check(enriched)

        // Day 09, Option 1: before turning check's own verdict into a
        // question or an execution, see whether memory holds a plausible
        // alternative this classification never got to consider — see
        // disambiguationCandidates's doc for exactly when this fires.
        val candidates = disambiguationCandidates(enriched, check)
        if (candidates.isNotEmpty()) {
            pendingClarification = null
            return askTypeDisambiguation(enriched, candidates)
        }

        return when (check) {
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
                // M2-C: continueAfterResumedStep is a no-op passthrough
                // whenever pendingPlan is null (the ordinary, non-plan
                // case) — see its own doc.
                continueAfterResumedStep(proceedToExecution(enriched))
            }
        }
    }

    /**
     * A same-pass reply worth using directly (Day 08-B), or `null` to fall
     * back to the existing streaming-generation pass.
     *
     * Guards against the one failure mode on-device measurement actually
     * found: a model that, asked to answer in the same breath as
     * classifying, sometimes just restates the user's own words instead of
     * answering them — a bare `intent must be one of...` prompt spends the
     * model's whole attention on the schema and leaves it with little room
     * to actually engage with what the user said, in a way the far larger,
     * fully-conversational prompt [com.softwaremine.dps.ai.session.AiSessionManager.runGeneration]
     * builds does not suffer from. This is a defensive sanity check on the
     * *model's own output* against the *model's own input*, not a guess at
     * what the user meant — it is symmetric and would catch the same
     * degenerate pattern regardless of language or phrasing.
     */
    private fun usableReply(userMessage: String, rawReply: String?): String? {
        val reply = rawReply?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedReply = reply.trim(*TRAILING_PUNCTUATION).lowercase()
        val normalizedUser = userMessage.trim(*TRAILING_PUNCTUATION).lowercase()
        if (normalizedReply == normalizedUser) {
            logger.i(TAG, "Discarding a same-pass reply that only echoed the user's message.")
            return null
        }
        return reply
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
        pendingTypeDisambiguation = null
        pendingConfirmation = null
        pendingPlan = null
        pendingIntentAwaitingContactPermission = null
        toolOrchestrator.reset()
        _state.value = transition(SecretaryEvent.Reset)
        _memory.value = ConversationMemory.EMPTY
        // M3-B: an explicit "forget the conversation" action, not an
        // ordinary write — clears the durable copy outright rather than
        // going through updateMemory/save(EMPTY), mirroring the distinction
        // the M3-B spec draws between the two.
        persistentMemoryStore.clear()
    }

    /**
     * The one place [_memory] is ever written to with a genuinely new value
     * (M3-B) — [reset] is deliberately separate, see its own comment. Every
     * call site already computed [memory] itself (via [memoryUpdater] or a
     * resolved contact); this only ever adds "and persist it", never a
     * second opinion on whether the change should happen at all.
     */
    private fun updateMemory(memory: ConversationMemory) {
        _memory.value = memory
        persistentMemoryStore.save(memory)
    }

    /** The follow-up currently awaiting an answer, if any. */
    fun pendingQuestion(): String? = pendingClarification?.question
        ?: pendingContactSelection?.let { candidateQuestion(it.candidates) }
        ?: pendingTypeDisambiguation?.let { disambiguationQuestion(it.candidates) }
        ?: pendingConfirmation?.let { CONFIRMATION_REPROMPT }

    /** Whether an action is held pending a permission grant. */
    fun hasPendingPermissionAction(): Boolean = toolOrchestrator.hasPendingPermissionAction()

    // -----------------------------------------------------------------
    // Multi-step planning
    // -----------------------------------------------------------------

    /**
     * Runs [steps] in order, stopping at the first one that does not finish
     * ([ClarificationEngine.Check.Missing], a confirmation, contact
     * ambiguity, or a Day 09 Option 1 redirect) or fails outright.
     *
     * ## Scope (documented, not hidden)
     * A block mid-plan resolves the *one* step it blocked — contact chosen,
     * missing detail supplied, confirmation decided, type disambiguated —
     * through exactly the same single-step resume paths Stage 1 already has.
     * Since M2-C, everything *after* that step is preserved as a
     * [com.softwaremine.dps.domain.secretary.PendingPlan] and automatically
     * continues once it resolves — see [continueAfterResumedStep] and
     * [parkRemainder]. The one still-open exception is a step blocked on an
     * Android/tool *permission*: that resumes through
     * [onPermissionResult] — a system callback carrying no user message at
     * all, entirely outside this class's `handle()`-based dispatch, with its
     * own pending state living inside [ToolOrchestrator], not here. Reaching
     * that case too would mean extending a second class's private state,
     * genuinely separate surface area left for a future pass rather than
     * folded in here.
     *
     * ## Why no cue-based enrichment runs per step
     * [ReferenceResolver] and [ActionDetector] both work from the *raw text
     * the user typed*, and a compound message's raw text does not belong to
     * any one step — running either against the whole message for every step
     * risks a cue meant for step 2 misfiring on step 1. Each step therefore
     * carries only what the model itself put in it, plus [anchorToPriorEvent]
     * — the one deterministic cross-step rule this class knows: a bodiless,
     * timeless reminder step immediately following a calendar event step
     * defaults to 30 minutes before that event, unless it stated its own
     * offset instead (M2-A; see [reminderOffsetMillis]).
     */
    private suspend fun handlePlan(userMessage: String, steps: List<DpsIntent>): ToolOrchestrator.Outcome {
        // Day 08-E follow-up: a whole-message grounding check (what
        // withResolvedTemporalPhrase uses for single-step) was proven, by a
        // JVM regression test, to accept a hallucinated raw_when on one step
        // merely because a *different* step of the same compound message
        // genuinely said those words — e.g. "kal shaam 7 baje reminder laga
        // do aur milk ka task bana do" could let the "milk" step inherit the
        // reminder step's real phrase. temporalStepAttributor closes this:
        // it locates every genuine temporal phrase in the message via
        // TemporalPhraseSpanFinder (delegating to temporalPhraseResolver's
        // own vocabulary, never duplicating it) and lets each one be claimed
        // by at most one step, in classification order. A step whose
        // raw_when matches no still-unclaimed occurrence has it discarded —
        // including a step that shares a *genuine* phrase with an earlier
        // step ("kal shaam 7 baje reminder... aur kal shaam 7 baje doosra
        // reminder..." legitimately said twice is the one case this would
        // reject that a smarter model deserves; deliberately conservative,
        // see TemporalStepAttributor's own doc). Steps are already scoped
        // this way before the loop, so per-step resolution below only ever
        // resolves what attribution already confirmed.
        //
        // M2-A: each step's own stated cross-step offset ("15 minutes
        // before") is read from its *pre-attribution* raw_when — see
        // reminderOffsetMillis's doc for why this must happen before
        // temporalStepAttributor runs, which would otherwise strip a
        // relative-offset phrase it cannot itself resolve.
        val offsets = steps.map(::reminderOffsetMillis)
        val attributedSteps = temporalStepAttributor.attribute(userMessage, steps)

        return continuePlan(
            steps = attributedSteps,
            offsets = offsets,
            completedReplies = mutableListOf(),
            initialLastEventStartMillis = null,
            initialLastIntent = steps.first(),
            initialLastResult = ToolResult.Cancelled("No steps were run."),
        )
    }

    /**
     * The shared loop body both [handlePlan] (a fresh compound
     * classification) and [continueAfterResumedStep] (M2-C: resuming after
     * one step blocked) run — so a park-and-resume cycle never duplicates
     * this logic, only re-enters it with a different starting point.
     *
     * @param steps already temporal-attributed and offset-extracted —
     *   [handlePlan] does that once, up front, for the whole compound
     *   message; a resumed continuation reuses exactly the values a
     *   [PendingPlan] preserved rather than re-deriving them (re-attributing
     *   only the remainder against the original message would risk a step
     *   re-claiming a temporal span an already-completed earlier step
     *   legitimately claimed the first time).
     * @param completedReplies replies from steps already finished — empty
     *   for a fresh plan, or seeded with a [PendingPlan]'s own
     *   [PendingPlan.completedReplies] plus the just-resumed step's reply
     *   for a continuation.
     */
    private suspend fun continuePlan(
        steps: List<DpsIntent>,
        offsets: List<Long?>,
        completedReplies: MutableList<String>,
        initialLastEventStartMillis: Long?,
        initialLastIntent: DpsIntent,
        initialLastResult: ToolResult,
    ): ToolOrchestrator.Outcome {
        val replies = completedReplies
        var lastEventStartMillis = initialLastEventStartMillis
        var lastIntent = initialLastIntent
        var lastResult = initialLastResult

        for ((index, raw) in steps.withIndex()) {
            // Unlike ReferenceResolver/ActionDetector (deliberately skipped
            // per-step above — they scan the *raw text*, and a compound
            // message's raw text does not belong to any one step),
            // resolveTemporalPhrase only resolves what attribution above
            // already confirmed belongs to this step — safe to run per step,
            // and must run before anchorToPriorEvent so that fallback only
            // ever fires when nothing else resolved anything.
            val step = anchorToPriorEvent(resolveTemporalPhrase(raw), lastEventStartMillis, offsets[index])

            val check = clarification.check(step)

            // M2-B: the same Day 09 Option 1 redirect handleSingleStep
            // already applies is now reachable from inside a plan too — a
            // second call site for the identical, unmodified mechanism
            // (disambiguationCandidates/askTypeDisambiguation), not a
            // second implementation. Only the reply prefixing below is
            // new, matching every other outcome branch in this loop.
            val candidates = disambiguationCandidates(step, check)
            if (candidates.isNotEmpty()) {
                pendingClarification = null
                // An earlier step in this same plan may have just offered
                // its own follow-up suggestion (attachSuggestionIfApplicable,
                // called from proceedToExecution a loop iteration ago),
                // leaving a stale pendingConfirmation behind — a real bug
                // this test suite caught: without clearing it, the next
                // handle() call would route to resolveConfirmation() first
                // instead of resolveTypeDisambiguation(), misinterpreting
                // the answer to this question as an answer to that
                // abandoned suggestion. This mechanism is only ever reached
                // once per turn from handleSingleStep, where pendingConfirmation
                // is already guaranteed null at entry (see handle()'s own
                // dispatch order) — handlePlan is the one path where a
                // prior step's own execution can set it first, so the clear
                // belongs here, not inside askTypeDisambiguation itself.
                pendingConfirmation = null
                // M2-C: everything after this step is preserved, not dropped.
                parkRemainder(steps, offsets, index, replies, lastEventStartMillis)
                val outcome = askTypeDisambiguation(step, candidates)
                return if (outcome is ToolOrchestrator.Outcome.Clarify) {
                    outcome.copy(question = prefixed(replies, outcome.question))
                } else {
                    outcome
                }
            }

            when (check) {
                is ClarificationEngine.Check.Missing -> {
                    val needs = IntentResolution.NeedsClarification(step, check.question, check.fields, step.parameters)
                    pendingClarification = needs
                    // M2-C discovery: an earlier step in this same plan may
                    // have already offered its own follow-up suggestion
                    // (attachSuggestionIfApplicable), leaving a stale
                    // pendingConfirmation behind — the same class of bug
                    // M2-B found and fixed for the type-disambiguation
                    // redirect below, but for this branch instead. Without
                    // clearing it, handle()'s dispatch checks
                    // pendingConfirmation before pendingClarification, so
                    // the next message would be misread as answering the
                    // abandoned suggestion rather than this question.
                    pendingConfirmation = null
                    parkRemainder(steps, offsets, index, replies, lastEventStartMillis)
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
                        // No PendingPlan is parked: per the existing, unchanged
                        // failure policy (no retry, no rollback), a failed step
                        // is not something a later message resumes.
                        return ToolOrchestrator.Outcome.Handled(replies.joinToString(" "), lastIntent, lastResult)
                    }
                }

                is ToolOrchestrator.Outcome.Clarify -> {
                    // Covers both a destructive/call confirmation and an
                    // ambiguous contact selection — parkRemainder does not
                    // need to know which; the "why" already lives in
                    // whichever pendingConfirmation/pendingContactSelection
                    // proceedToExecution just set.
                    parkRemainder(steps, offsets, index, replies, lastEventStartMillis)
                    return outcome.copy(question = prefixed(replies, outcome.question))
                }

                is ToolOrchestrator.Outcome.NeedsPermission ->
                    // M2-C scope boundary, documented not silent: a step
                    // blocked on a tool/Android permission is not parked
                    // into a PendingPlan. Resuming it goes through
                    // onPermissionResult() — a system callback with no user
                    // message at all, entirely outside this class's
                    // handle()-based dispatch — and the permission itself is
                    // held inside ToolOrchestrator's own private state, not
                    // here. Extending that path was out of this stage's
                    // scope; the remainder is dropped exactly as it already
                    // was before M2-C, not a new limitation.
                    return outcome.copy(reply = prefixed(replies, outcome.reply))

                is ToolOrchestrator.Outcome.Conversational -> return outcome
            }
        }

        pendingPlan = null
        return ToolOrchestrator.Outcome.Handled(replies.joinToString(" "), lastIntent, lastResult)
    }

    /**
     * Parks everything in [steps] after [currentIndex] as a [PendingPlan],
     * alongside whichever `pending*` field the caller is about to set for
     * the step at [currentIndex] itself. Sets nothing when there is no
     * remainder, so a block on the plan's *last* step behaves exactly as it
     * always did — nothing left to continue.
     */
    private fun parkRemainder(
        steps: List<DpsIntent>,
        offsets: List<Long?>,
        currentIndex: Int,
        completedReplies: List<String>,
        lastEventStartMillis: Long?,
    ) {
        val remainingSteps = steps.drop(currentIndex + 1)
        pendingPlan = if (remainingSteps.isEmpty()) {
            null
        } else {
            PendingPlan(
                remainingSteps = remainingSteps,
                remainingOffsets = offsets.drop(currentIndex + 1),
                completedReplies = completedReplies.toList(),
                lastEventStartMillis = lastEventStartMillis,
                requestedAtMillis = now(),
            )
        }
    }

    /**
     * After a single blocked step resolves — clarification answered,
     * confirmation decided, contact chosen, or type disambiguated — continues
     * whatever [PendingPlan] was parked alongside it (M2-C), or passes
     * [outcome] straight through unchanged when none was in flight (the
     * ordinary, single-step case, entirely unaffected).
     *
     * Only a [ToolOrchestrator.Outcome.Handled] outcome — the resumed step
     * having genuinely finished, whether by success, tool failure, or an
     * explicit decline — consumes and clears the plan. A [Clarify] or
     * [NeedsPermission] here means the resumed step blocked again on a
     * *different* reason; the plan is left exactly as it was, still
     * correctly describing what comes after this still-unfinished step.
     */
    private suspend fun continueAfterResumedStep(outcome: ToolOrchestrator.Outcome): ToolOrchestrator.Outcome {
        val plan = pendingPlan ?: return outcome

        return when (outcome) {
            is ToolOrchestrator.Outcome.Handled -> {
                pendingPlan = null
                if (!outcome.result.isSuccess) {
                    // A decline, a cancellation, or a genuine failure — the
                    // existing "no retry, no rollback, report honestly"
                    // policy applies exactly as it does for a fresh plan's
                    // own failed step.
                    return outcome.copy(reply = prefixed(plan.completedReplies, outcome.reply))
                }

                val success = outcome.result as? ToolResult.Success
                val lastEventStartMillis = if (outcome.intent.type == IntentType.CALENDAR_EVENT && success != null) {
                    success.data["start"]?.let(::parseLocalMillis) ?: plan.lastEventStartMillis
                } else {
                    plan.lastEventStartMillis
                }

                continuePlan(
                    steps = plan.remainingSteps,
                    offsets = plan.remainingOffsets,
                    completedReplies = (plan.completedReplies + outcome.reply).toMutableList(),
                    initialLastEventStartMillis = lastEventStartMillis,
                    initialLastIntent = outcome.intent,
                    initialLastResult = outcome.result,
                )
            }

            is ToolOrchestrator.Outcome.Clarify ->
                outcome.copy(question = prefixed(plan.completedReplies, outcome.question))

            is ToolOrchestrator.Outcome.NeedsPermission ->
                outcome.copy(reply = prefixed(plan.completedReplies, outcome.reply))

            is ToolOrchestrator.Outcome.Conversational -> {
                pendingPlan = null
                outcome
            }
        }
    }

    private fun prefixed(completedReplies: List<String>, next: String): String =
        if (completedReplies.isEmpty()) next else "${completedReplies.joinToString(" ")} $next"

    /**
     * Fills `date`/`time` from [DpsIntent.parameters]' `rawWhen` — the
     * model's verbatim quote — via [temporalPhraseResolver] (Day 08-E).
     *
     * Only fires when **both** are still absent, so it never overwrites a
     * value [ReferenceResolver]'s relative-offset logic already wrote (e.g.
     * "30 minute pehle kar do" on an existing reminder) or that a prior step
     * in a plan already resolved — the same "new values win only where
     * present" discipline [ClarificationEngine.merge] already follows.
     * Leaves both `null` when [TemporalPhraseResolver] does not recognise
     * the phrase, which is exactly what [ClarificationEngine] needs to ask
     * a follow-up instead of anything being invented.
     *
     * ## The grounding check (Day 08-E follow-up)
     * Real-device testing found the classification model reliably producing
     * a plausible-looking `raw_when` — "kal shaam 7 baje" — for messages
     * that never mentioned a time at all, reproduced with a fresh model
     * reload and zero session history, which rules out cache/session
     * contamination: it is a genuine, ungrounded extraction. Every
     * `raw_when` is checked against [rawText] via [temporalGroundingGuard]
     * *before* [temporalPhraseResolver] ever sees it — an ungrounded quote
     * is discarded (including from [intent.parameters][DpsIntent.parameters]
     * itself, so it cannot resurface later) rather than resolved, exactly
     * the same "never invent" discipline [TemporalPhraseResolver] itself
     * already follows for a phrase it cannot parse.
     *
     * Single-step only — [handlePlan] uses [temporalStepAttributor] instead,
     * a stricter, per-occurrence check whole-message grounding cannot
     * provide (see that class's doc for why).
     */
    private fun withResolvedTemporalPhrase(rawText: String, intent: DpsIntent): DpsIntent {
        val parameters = intent.parameters
        if (parameters.value(IntentField.DATE) != null || parameters.value(IntentField.TIME) != null) return intent
        val rawWhen = parameters.rawWhen?.trim()?.takeIf { it.isNotEmpty() } ?: return intent

        if (!temporalGroundingGuard.isGrounded(rawText, rawWhen)) {
            logger.i(TAG, "Discarding an ungrounded raw_when (\"$rawWhen\" not found in the user's own message)")
            return intent.copy(parameters = parameters.copy(rawWhen = null))
        }

        return resolveTemporalPhrase(intent)
    }

    /**
     * Resolves an already-grounded `raw_when` into `date`/`time`. No
     * grounding check of its own — the caller ([withResolvedTemporalPhrase]
     * for single-step, [handlePlan] for multi-step) is responsible for
     * having already confirmed `rawWhen` before this runs.
     */
    private fun resolveTemporalPhrase(intent: DpsIntent): DpsIntent {
        val parameters = intent.parameters
        if (parameters.value(IntentField.DATE) != null || parameters.value(IntentField.TIME) != null) return intent
        val rawWhen = parameters.rawWhen?.trim()?.takeIf { it.isNotEmpty() } ?: return intent

        val resolution = temporalPhraseResolver.resolve(rawWhen)
        if (resolution.date == null && resolution.time == null) return intent

        return intent.copy(parameters = parameters.copy(date = resolution.date, time = resolution.time))
    }

    /**
     * The one deterministic cross-step rule — see [handlePlan]'s doc.
     *
     * @param statedOffsetMillis a signed delta read from this step's own
     *   `raw_when` before attribution stripped it (M2-A) — negative for
     *   "before"/"pehle" — via [reminderOffsetMillis]. `null` when the step
     *   named no explicit offset, in which case the three-way precedence
     *   below (M3-C finalization) applies.
     *
     * ## Three-way precedence (M3-C finalization)
     * ```
     * explicitly stated offset on this request
     *         ↓ (if absent)
     * stored user preference (defaultReminderLeadMinutes)
     *         ↓ (if absent)
     * the original fixed 30-minutes-before default
     * ```
     * [statedOffsetMillis] already wins first by construction — it is only
     * ever `null` here when the request stated none — so this only chooses
     * between [preferredLeadMillis] and [DEFAULT_REMINDER_LEAD_MILLIS].
     * [PersistentPreferenceStore] is read, never written, from this path:
     * the fallback default must never be persisted back as though it were a
     * choice the user made (see [UserPreferences][com.softwaremine.dps.domain.preferences.UserPreferences]'s
     * own doc).
     */
    private fun anchorToPriorEvent(step: DpsIntent, priorEventStartMillis: Long?, statedOffsetMillis: Long?): DpsIntent {
        if (priorEventStartMillis == null) return step
        if (step.type != IntentType.REMINDER || step.action != IntentAction.CREATE) return step
        if (step.parameters.value(IntentField.DATE) != null || step.parameters.value(IntentField.TIME) != null) {
            return step
        }

        val offsetMillis = statedOffsetMillis ?: preferredLeadMillis() ?: -DEFAULT_REMINDER_LEAD_MILLIS
        val zoned = Instant.ofEpochMilli(priorEventStartMillis + offsetMillis).atZone(zone)
        return step.copy(
            parameters = step.parameters.copy(
                date = zoned.format(DateTimeFormatter.ISO_LOCAL_DATE),
                time = zoned.format(DateTimeFormatter.ofPattern("HH:mm")),
            ),
        )
    }

    /**
     * The user's stored default lead time (M3-C finalization), as a
     * negative millisecond delta matching [reminderOffsetMillis]'s own sign
     * convention — always "before", the same direction
     * [DEFAULT_REMINDER_LEAD_MILLIS] itself always applies. `null` when no
     * preference is stored, in which case [anchorToPriorEvent] falls back to
     * that fixed default instead.
     */
    private fun preferredLeadMillis(): Long? =
        persistentPreferenceStore.load().defaultReminderLeadMinutes?.let { -(it * 60_000L) }

    /**
     * The signed offset [step] itself named ("15 minutes before", "10 min
     * pehle"), or `null` when it named none (M2-A).
     *
     * ## Why this must run *before* [temporalStepAttributor] does
     * [TemporalStepAttributor] keeps a step's `raw_when` only when it
     * matches a genuine occurrence [TemporalPhraseSpanFinder] found — and
     * that finder only recognises phrases [TemporalPhraseResolver] itself
     * can resolve, which has no concept of a relative delta at all. A
     * step whose `raw_when` is purely a relative offset therefore matches
     * no absolute-time span anywhere in the message and would otherwise be
     * silently nulled out by attribution before [anchorToPriorEvent] ever
     * ran. Reading it here, from the step's own pre-attribution `raw_when`,
     * sidesteps that entirely — this never inspects [handlePlan]'s
     * `userMessage`, only the one field the model already scoped to this
     * one step, so [TemporalStepAttributor]'s cross-step isolation
     * guarantee is untouched by this reading elsewhere.
     *
     * ## Why [ReferenceResolver.findRelativeOffsetMillis], not a new parser
     * Reuses the exact regex/vocabulary already shipped and tested for
     * "30 minute pehle kar do" (single-turn reminder rescheduling) rather
     * than a second, drifting copy of the same pattern — same sign
     * convention (negative for "pehle"/"before"/"earlier"), so adding it to
     * [priorEventStartMillis] in [anchorToPriorEvent] already means
     * "earlier" without any translation here.
     */
    private fun reminderOffsetMillis(step: DpsIntent): Long? {
        val rawWhen = step.parameters.rawWhen?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return referenceResolver.findRelativeOffsetMillis(rawWhen.lowercase())
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
            // Reached when the model gave a phone number directly, with no
            // name to ground — the number is already known, so confirmation
            // (if this type needs it) can be asked immediately rather than
            // after a lookup that would never run.
            proceedAfterContactResolved(intent)
        }
    }

    /**
     * The seam every path that just finished — or skipped — contact
     * resolution funnels through, so [CALL_CONFIRMATION_TYPES] is asked
     * about exactly once, in exactly one place, regardless of which of the
     * three routes (direct phone, single resolved match, or a disambiguation
     * pick) got here.
     */
    private suspend fun proceedAfterContactResolved(intent: DpsIntent): ToolOrchestrator.Outcome =
        if (intent.type in CALL_CONFIRMATION_TYPES && intent.parameters.value(IntentField.PHONE) != null) {
            askCallConfirmation(intent)
        } else {
            finishExecution(intent)
        }

    /**
     * Asks before opening the dialer — the confirmation boundary this
     * milestone exists to build. Mirrors [askDeleteConfirmation]'s shape
     * exactly, reusing the same [PendingConfirmation]/[resolveConfirmation]
     * machinery; the only difference is *why* it fires (a grounded call
     * target, not a destructive verb) and the question shown.
     *
     * Only ever called once [intent.parameters.phone][IntentField.PHONE] is
     * the real, contact-sourced number — see [applyResolvedContactData]'s
     * doc — so the number named here is exactly what [proceedToExecution]
     * eventually hands to [com.softwaremine.dps.data.android.tool.AndroidCallTool].
     */
    private fun askCallConfirmation(intent: DpsIntent): ToolOrchestrator.Outcome {
        val name = intent.parameters.value(IntentField.PERSON)
        val phone = intent.parameters.value(IntentField.PHONE).orEmpty()
        val question = "Call ${if (name != null) "$name ($phone)" else phone}?"

        pendingConfirmation = PendingConfirmation(intent, now())
        _state.value = transition(SecretaryEvent.ConfirmationRequested)
        return ToolOrchestrator.Outcome.Clarify(
            question,
            IntentResolution.NeedsClarification(intent, question, emptySet(), intent.parameters),
        )
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

        // Phase 5 — Reminder Cancel Confirmation. targetId is guaranteed
        // resolved by the time this runs, exactly as for CALENDAR_EVENT
        // above: REMINDER is already in ClarificationEngine's
        // TARGETABLE_TYPES, so a CANCEL with no resolved target never
        // reaches proceedToExecution at all.
        IntentType.REMINDER -> _memory.value.lastReminder
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
                    // Defensive, mirroring the Missing/type-disambiguation
                    // fixes above: handle()'s dispatch already checks
                    // pendingContactSelection before pendingConfirmation, so
                    // this is not reachable as a live bug today, but a
                    // dangling suggestion from an earlier plan step should
                    // not linger past the step that actually replaces it.
                    pendingConfirmation = null
                    _state.value = transition(SecretaryEvent.ContactAmbiguous)
                    val question = candidateQuestion(candidates)
                    ToolOrchestrator.Outcome.Clarify(
                        question,
                        IntentResolution.NeedsClarification(original, question, emptySet(), original.parameters),
                    )
                }
            }

            success != null && success.data["contact_id"] != null -> {
                updateMemory(
                    memoryUpdater.remember(
                        memory = _memory.value,
                        intent = DpsIntent(IntentType.CONTACT_LOOKUP, IntentParameters(person = original.parameters.value(IntentField.PERSON))),
                        toolId = ToolId.CONTACTS,
                        result = success,
                        nowMillis = now(),
                    ),
                )
                proceedAfterContactResolved(applyResolvedContactData(original, success.data))
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
            pendingPlan = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        val chosen = contactSelectionParser.parse(userMessage, selection.candidates)
            ?: run {
                // Unparseable — still re-asking about the same step, so any
                // parked plan remainder is left exactly as it is.
                val question = candidateQuestion(selection.candidates)
                return ToolOrchestrator.Outcome.Clarify(
                    question,
                    IntentResolution.NeedsClarification(selection.originalIntent, question, emptySet(), selection.originalIntent.parameters),
                )
            }

        pendingContactSelection = null
        _state.value = transition(SecretaryEvent.ContactSelected)
        updateMemory(
            _memory.value.copy(
                lastContact = chosen,
                lastReferencedPerson = chosen.displayName,
                updatedAtMillis = now(),
            ),
        )
        return continueAfterResumedStep(proceedAfterContactResolved(applyResolvedContact(selection.originalIntent, chosen)))
    }

    private fun applyResolvedContactData(original: DpsIntent, data: Map<String, String>): DpsIntent {
        val name = data["name"] ?: return original
        var params = original.parameters.copy(person = name)
        when (original.type) {
            IntentType.WHATSAPP_MESSAGE -> data["phone"]?.let { params = params.copy(phone = it) }
            IntentType.EMAIL_MESSAGE -> data["email"]?.let { params = params.copy(email = it) }
            // Unconditional, unlike the two above: a call's phone argument
            // is about to be shown to the user as ground truth and then
            // dialed, so any value the model supplied on its own — real or
            // fabricated — must be replaced outright, even when the
            // resolved contact turns out to have no number at all (`null`
            // clears it rather than leaking the model's original value).
            IntentType.CALL_CONTACT -> params = params.copy(phone = data["phone"])
            else -> Unit
        }
        return original.copy(parameters = params)
    }

    private fun applyResolvedContact(original: DpsIntent, contact: Contact): DpsIntent {
        var params = original.parameters.copy(person = contact.displayName)
        when (original.type) {
            IntentType.WHATSAPP_MESSAGE -> contact.primaryPhone?.let { params = params.copy(phone = it) }
            IntentType.EMAIL_MESSAGE -> contact.primaryEmail?.let { params = params.copy(email = it) }
            // See applyResolvedContactData's doc for why this is unconditional.
            IntentType.CALL_CONTACT -> params = params.copy(phone = contact.primaryPhone)
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
    // Type disambiguation (Day 09, Option 1)
    //
    // The Calendar Delete/Update Classification Reliability investigation
    // found the classification model unreliable specifically for non-CREATE
    // requests naming an existing item: two independent live-device runs
    // both showed 0/14 correct classifications, with the model frequently
    // choosing a type (notification, call_contact) that
    // ai.memory.ReferenceResolver has no grounding branch for at all, or a
    // grounded type (reminder, task) whose own memory slot came up empty
    // while a different one held exactly what the user meant. A targeted
    // prompt change was tried and rigorously A/B tested; it produced zero
    // improvement and introduced a new hallucination (see the Day 09
    // report). This is the deterministic, architecture-level fix that
    // followed: no keyword matching against the user's raw text, no
    // overriding the model's own classification — it only ever recognises a
    // structural signal and asks, exactly the way candidateQuestion already
    // does for ambiguous contacts.
    // -----------------------------------------------------------------

    /**
     * Candidates worth asking the user about before honouring [check]'s own
     * verdict, or empty when nothing here applies.
     *
     * ## The two signals, both structural
     * Never fires for [IntentAction.CREATE] or [IntentAction.LIST] — CREATE
     * is the one path the investigation measured as fully reliable, and LIST
     * is exempted by [ClarificationEngine.check] itself before anything else
     * runs, for the same reason: neither names one existing item to redirect.
     *
     * For [SAFE_COMPLETE_TYPES] — the types [ClarificationEngine] already
     * knows how to target directly (a resolved id, or for
     * TASK/ACTION_ITEM a spoken title) — this only fires when [check] is
     * [ClarificationEngine.Check.Missing] with an *empty* field set, i.e.
     * exactly the "which one do you mean?" shape
     * [ClarificationEngine.questionForMissingTarget] already asks. A content
     * question ("What's the follow-up?") or an already-satisfied [Complete]
     * is left alone — those are not evidence of anything wrong.
     *
     * For every other type, there is no grounding mechanism in
     * [ReferenceResolver] at all — confirmed by reading its `when` branches,
     * which name only REMINDER/CALENDAR_EVENT/TASK — so a non-CREATE request
     * classified into one of them can never resolve a real target regardless
     * of what [check] says, including a [Complete] that would otherwise
     * proceed straight to a guaranteed [ToolResult.Failure]. Redirecting
     * such a case toward a real, already-remembered candidate is a strict
     * improvement: there is no scenario in the investigation's evidence
     * where this type, non-CREATE, with no memory grounding at all, was ever
     * going to succeed on its own.
     *
     * A candidate is only offered from a *different* slot than the one the
     * classified type already owns — a type in [SAFE_COMPLETE_TYPES] whose
     * own resolution is what's missing is not re-offered itself; that is
     * exactly the question [check] itself already asks.
     */
    private fun disambiguationCandidates(intent: DpsIntent, check: ClarificationEngine.Check): List<DisambiguationCandidate> {
        if (intent.action == IntentAction.CREATE || intent.action == IntentAction.LIST) return emptyList()

        val suspicious = if (intent.type in SAFE_COMPLETE_TYPES) {
            check is ClarificationEngine.Check.Missing && check.fields.isEmpty()
        } else {
            true
        }
        if (!suspicious) return emptyList()

        val memory = _memory.value
        return buildList {
            if (intent.type != IntentType.CALENDAR_EVENT) {
                memory.lastCalendarEvent?.let { add(DisambiguationCandidate(IntentType.CALENDAR_EVENT, it.id.toString(), it.title)) }
            }
            if (intent.type != IntentType.REMINDER) {
                memory.lastReminder?.let { add(DisambiguationCandidate(IntentType.REMINDER, it.id.toString(), it.title)) }
            }
            if (intent.type != IntentType.TASK) {
                memory.lastTask?.let { add(DisambiguationCandidate(IntentType.TASK, it.id.toString(), it.title)) }
            }
        }
    }

    private fun askTypeDisambiguation(intent: DpsIntent, candidates: List<DisambiguationCandidate>): ToolOrchestrator.Outcome {
        val question = disambiguationQuestion(candidates)
        pendingTypeDisambiguation = PendingTypeDisambiguation(intent, candidates, now())
        _state.value = transition(SecretaryEvent.TypeDisambiguationNeeded)
        logger.i(TAG, "Asking which of ${candidates.size} remembered item(s) a ${intent.type} ${intent.action} actually meant")
        return ToolOrchestrator.Outcome.Clarify(
            question,
            IntentResolution.NeedsClarification(intent, question, emptySet(), intent.parameters),
        )
    }

    private fun disambiguationQuestion(candidates: List<DisambiguationCandidate>): String {
        if (candidates.size == 1) {
            val only = candidates.single()
            return "Do you mean the ${DISAMBIGUATION_LABELS[only.type]} \"${only.label}\"?"
        }
        val listed = candidates.mapIndexed { index, candidate ->
            "${index + 1}) the ${DISAMBIGUATION_LABELS[candidate.type]} \"${candidate.label}\""
        }.joinToString(", ")
        return "A few things could match: $listed. Which one?"
    }

    /**
     * Resolves the answer to [askTypeDisambiguation]'s question.
     *
     * A single candidate is a yes/no question, answered via
     * [confirmationParser] exactly like [resolveConfirmation]. Several
     * candidates need a pick, answered via a bare 1-based index — no keyword
     * or name matching, since offering a partial-name match here would mean
     * comparing against the user's raw text for a decision this design
     * explicitly keeps structural.
     *
     * A "no", an unparseable reply, or a stale selection all fall through to
     * [handle] as the fresh message it then genuinely is — mirroring
     * [resolveConfirmation]'s own "declining does not mean insisting"
     * rationale, and safe for the same reason [Track A][handleSingleStep]
     * already established: [pendingClarification] is untouched here, so the
     * resumed [handle] call sees no pending question of its own to
     * contaminate with anything this redirect knew.
     */
    private suspend fun resolveTypeDisambiguation(
        userMessage: String,
        pending: PendingTypeDisambiguation,
    ): ToolOrchestrator.Outcome {
        if (!pending.isFresh(now())) {
            pendingTypeDisambiguation = null
            pendingPlan = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        val chosen = if (pending.candidates.size == 1) {
            when (confirmationParser.parse(userMessage)) {
                Confirmation.YES -> pending.candidates.single()
                else -> null
            }
        } else {
            indexChoice(userMessage, pending.candidates.size)?.let { pending.candidates[it] }
        }

        pendingTypeDisambiguation = null

        if (chosen == null) {
            // Declined or unparseable — the answer wasn't to this question,
            // so whatever plan remainder was waiting behind it is abandoned
            // too, the same as a clarification answer that turns out to be
            // a fresh, unrelated request.
            pendingPlan = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        _state.value = transition(SecretaryEvent.TypeDisambiguationResolved)
        val resolved = pending.originalIntent.copy(
            type = chosen.type,
            parameters = pending.originalIntent.parameters.copy(targetId = chosen.targetId),
        )
        // Back through proceedToExecution, not straight to finishExecution —
        // a CANCEL redirected onto CALENDAR_EVENT/TASK/REMINDER must still
        // hit askDeleteConfirmation exactly as a correctly-classified one
        // would; this only replaces "which type/target", never the
        // confirmation gate downstream of it. continueAfterResumedStep then
        // continues any parked plan remainder (M2-C) once this step
        // genuinely finishes.
        return continueAfterResumedStep(proceedToExecution(resolved))
    }

    /** A bare 1-based index into a list of [count] candidates, or `null`. */
    private fun indexChoice(reply: String, count: Int): Int? {
        val number = reply.trim().toIntOrNull() ?: return null
        return (number - 1).takeIf { it in 0 until count }
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
            pendingPlan = null
            _state.value = transition(SecretaryEvent.Reset)
            return handle(userMessage)
        }

        return when (confirmationParser.parse(userMessage)) {
            Confirmation.YES -> {
                pendingConfirmation = null
                _state.value = transition(SecretaryEvent.ConfirmationAccepted)
                continueAfterResumedStep(finishExecution(pending.intent))
            }

            // M2-C: a decline is still a step finishing (as a Cancelled,
            // non-success result) — continueAfterResumedStep's own
            // "not successful" branch stops any parked plan remainder here,
            // exactly like a fresh plan's own failed step would. Declining
            // one destructive step is deliberately not treated as consent
            // to keep running the rest of the plan unattended.
            Confirmation.NO -> {
                pendingConfirmation = null
                _state.value = transition(SecretaryEvent.ConfirmationDeclined)
                continueAfterResumedStep(
                    ToolOrchestrator.Outcome.Handled(
                        reply = "Alright, I've left it as is.",
                        intent = pending.intent,
                        result = ToolResult.Cancelled("Declined."),
                    ),
                )
            }

            Confirmation.UNCLEAR -> {
                pendingConfirmation = null
                // The user said something else entirely — not an answer to
                // this question, so any parked plan remainder is abandoned,
                // the same as an unrelated message during a clarification.
                pendingPlan = null
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
                    updateMemory(
                        memoryUpdater.remember(
                            memory = _memory.value,
                            intent = outcome.intent,
                            toolId = toolId,
                            result = outcome.result,
                            nowMillis = now(),
                        ),
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

        /** Stripped before comparing a reply against the user's message — see [usableReply]. */
        val TRAILING_PUNCTUATION = charArrayOf('.', '?', '!', ' ', '\n')

        /** Intent types where grounding a bare name to a real contact is worth attempting. */
        val PERSON_GROUNDING_TYPES = setOf(
            IntentType.WHATSAPP_MESSAGE,
            IntentType.EMAIL_MESSAGE,
            IntentType.CALENDAR_EVENT,
            IntentType.CALL_CONTACT,
        )

        /** Of [PERSON_GROUNDING_TYPES], the ones that cannot proceed at all without a resolved contact. */
        val REQUIRES_RESOLVED_CONTACT = setOf(
            IntentType.WHATSAPP_MESSAGE,
            IntentType.EMAIL_MESSAGE,
            IntentType.CALL_CONTACT,
        )

        /**
         * Types that ask an explicit "yes" once their target is known, before
         * their tool ever runs (Contacts + Calling milestone) — mirrors
         * [DELETE_CONFIRMATION_TYPES]'s placement, but the trigger is "the
         * phone number is now grounded", not an action verb.
         */
        val CALL_CONFIRMATION_TYPES = setOf(IntentType.CALL_CONTACT)

        /**
         * Intent types whose CANCEL asks for confirmation before deleting
         * (Day 06 adds TASK; Phase 5 — Reminder Cancel Confirmation — adds
         * REMINDER, closing the one destructive action in this codebase that
         * previously ran with no "are you sure").
         */
        val DELETE_CONFIRMATION_TYPES = setOf(IntentType.CALENDAR_EVENT, IntentType.TASK, IntentType.REMINDER)

        /** Fallback noun for [askDeleteConfirmation] when nothing more specific is known. */
        val DELETE_TARGET_LABELS = mapOf(
            IntentType.CALENDAR_EVENT to "event",
            IntentType.TASK to "task",
            IntentType.REMINDER to "reminder",
        )

        /**
         * The intent types [ClarificationEngine.check] already knows how to
         * target directly — a resolved id (REMINDER, CALENDAR_EVENT), or for
         * TASK/ACTION_ITEM a spoken title as the address. See
         * [disambiguationCandidates]'s doc for how this scopes Day 09's
         * redirect to only the "which one" shape of [ClarificationEngine.Check.Missing]
         * for these, never a genuine content question.
         */
        val SAFE_COMPLETE_TYPES = setOf(
            IntentType.REMINDER,
            IntentType.CALENDAR_EVENT,
            IntentType.TASK,
            IntentType.ACTION_ITEM,
        )

        /** Noun used when asking which remembered item a redirect (Day 09) means. */
        val DISAMBIGUATION_LABELS = mapOf(
            IntentType.CALENDAR_EVENT to "calendar event",
            IntentType.REMINDER to "reminder",
            IntentType.TASK to "task",
        )
    }
}
