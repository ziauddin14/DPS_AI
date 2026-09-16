package com.softwaremine.dps.ai.secretary

import com.softwaremine.dps.ai.intent.ClarificationEngine
import com.softwaremine.dps.ai.intent.ToolOrchestrator
import com.softwaremine.dps.ai.intent.ToolResponseGenerator
import com.softwaremine.dps.ai.memory.ActionDetector
import com.softwaremine.dps.ai.memory.ConversationMemoryUpdater
import com.softwaremine.dps.ai.memory.EpisodicMemoryRecorder
import com.softwaremine.dps.ai.memory.PreferenceStatementRecognizer
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
import com.softwaremine.dps.data.android.secretary.AutomationVerifier
import com.softwaremine.dps.data.android.secretary.ExecutionVerifier
import com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore
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
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.permission.PermissionManager
import com.softwaremine.dps.domain.secretary.DisambiguationCandidate
import com.softwaremine.dps.domain.secretary.ExecutionRecoveryState
import com.softwaremine.dps.domain.secretary.OperationCheckpoint
import com.softwaremine.dps.domain.secretary.OperationType
import com.softwaremine.dps.domain.secretary.PendingAutomationAction
import com.softwaremine.dps.domain.secretary.PendingConfirmation
import com.softwaremine.dps.domain.secretary.PendingContactSelection
import com.softwaremine.dps.domain.secretary.PendingPlan
import com.softwaremine.dps.domain.secretary.PendingVerification
import com.softwaremine.dps.domain.secretary.PendingTypeDisambiguation
import com.softwaremine.dps.domain.secretary.PersistedDisambiguationCandidate
import com.softwaremine.dps.domain.secretary.PersistedPendingPlan
import com.softwaremine.dps.domain.secretary.PersistedPendingState
import com.softwaremine.dps.domain.secretary.RiskLevel
import com.softwaremine.dps.domain.secretary.RiskPolicy
import com.softwaremine.dps.domain.secretary.SecretaryEvent
import com.softwaremine.dps.domain.secretary.SecretaryState
import com.softwaremine.dps.domain.secretary.SecretaryStateMachine
import com.softwaremine.dps.domain.secretary.VerificationOutcome
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolRegistry
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
    private val persistentRecoveryStore: PersistentRecoveryStore,
    private val episodicMemoryRecorder: EpisodicMemoryRecorder,
    private val executionVerifier: ExecutionVerifier,
    private val automationVerifier: AutomationVerifier,
    private val permissionManager: PermissionManager,
    private val toolRegistry: ToolRegistry,
    private val responses: ToolResponseGenerator,
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
     * A [RiskLevel.CONFIRM_REQUIRED] intent (M8) whose own tool needs a
     * permission it does not currently have — held only until that
     * permission is resolved. See [blockOnActionPermission]'s own doc for
     * why this cannot be [pendingIntentAwaitingContactPermission] itself,
     * nor route through [ToolOrchestrator]'s own `pendingPermission`:
     * resuming either of those two existing seams re-executes the original
     * call directly, which for a confirm-required intent would silently
     * skip asking for confirmation the moment the permission is granted —
     * exactly the outcome M8's locked contract forbids. Resuming this field
     * instead re-enters [proceedToExecution] from the top (see
     * [onPermissionResult]), so confirmation is asked fresh, only once the
     * permission is actually usable.
     */
    private var pendingIntentAwaitingActionPermission: DpsIntent? = null

    /**
     * The verification outcome for whichever step [recordOutcome] most
     * recently ran, or `null` when the last outcome had nothing to verify
     * (M7). Read once, immediately, by [continuePlan]'s own multi-step
     * gate — never left to carry across turns: [recordOutcome] itself
     * assigns this on *every* call it makes, including to `null`, so a
     * step that never reaches verification (a different intent type, a
     * failed tool call) cannot inherit a stale value from an earlier,
     * unrelated step. See [ExecutionVerifier]'s own doc for why `null`
     * ("never attempted") is kept distinct from
     * [VerificationOutcome.ObservationFailed] ("attempted and
     * inconclusive").
     */
    private var lastStepVerification: VerificationOutcome? = null

    /**
     * A durable recovery record restored from [persistentRecoveryStore] at
     * construction (M5-B) — a request one of the four `pending*` fields
     * above was blocking when an earlier process died, still awaiting the
     * user's explicit yes/no before anything is restored into those live
     * fields. `null` once resolved either way, or from construction when
     * nothing was pending or what was pending had already gone stale.
     *
     * ## Never a trigger for execution
     * Its mere presence causes [handle] to ask a question
     * ([resolveRecoveryPrompt]) — never to call [proceedToExecution] or any
     * tool. See [ExecutionRecoveryState]'s own doc for the full safety
     * reasoning.
     */
    private var restoredRecovery: ExecutionRecoveryState? = loadFreshRecoveryOrNull()

    /**
     * Whether the recovery question itself has already been shown for
     * [restoredRecovery] — distinguishes "the user's next message is an
     * answer to that question" from "this is the very first message of the
     * fresh process, which cannot possibly be one." See [handle]'s own doc.
     */
    private var recoveryPromptShown: Boolean = false

    /**
     * Loads a persisted recovery record, discarding it outright (and
     * clearing the store) if it has already gone stale — a record surviving
     * on disk for days before the app is reopened must never surface a
     * "continue where you left off?" prompt about something the user has
     * long since moved on from. Reuses [PendingPlan.FRESHNESS_WINDOW_MILLIS]
     * (five minutes) rather than inventing a second constant, applied here
     * to the moment the record was last synced to disk — see
     * [ExecutionRecoveryState]'s own doc for why this is a genuine,
     * intentional difference from the live `pendingClarification` field,
     * which has no freshness check of its own at all.
     */
    private fun loadFreshRecoveryOrNull(): ExecutionRecoveryState? {
        val state = persistentRecoveryStore.load() ?: return null
        val requestedAtMillis = when (val pending = state.pending) {
            is PersistedPendingState.Clarification -> pending.requestedAtMillis
            is PersistedPendingState.ContactSelection -> pending.requestedAtMillis
            is PersistedPendingState.TypeDisambiguation -> pending.requestedAtMillis
            is PersistedPendingState.Confirmation -> pending.requestedAtMillis
        }
        if (now() - requestedAtMillis > PendingPlan.FRESHNESS_WINDOW_MILLIS) {
            logger.i(TAG, "Discarding a stale persisted recovery record")
            persistentRecoveryStore.clear()
            return null
        }
        return state
    }

    /**
     * An outstanding [OperationCheckpoint] restored from
     * [persistentRecoveryStore] at construction (M5-C; extended to
     * `create_event` in M5-E) — a `create_task`, `create_reminder`, or
     * `create_event` dispatch whose outcome is genuinely unknown because an
     * earlier process died between the checkpoint being written and it being
     * cleared. `null` once surfaced to the user, or from construction when
     * no create was ever left mid-flight.
     *
     * ## Never a trigger for execution, and never expires
     * Unlike [restoredRecovery], this carries no freshness check: there is
     * no safe default to fall back to for "did this create actually
     * happen" the way there is for "is this stale question worth re-asking"
     * — silently discarding an old checkpoint would just as silently drop
     * the one honest signal that an operation's outcome is unresolved. It
     * is always surfaced exactly once (see [handle]'s own precedence over
     * [restoredRecovery]) and then cleared — never re-executed, never
     * re-shown. See [OperationCheckpoint]'s own doc for the full reasoning.
     */
    private var pendingCheckpoint: OperationCheckpoint? = persistentRecoveryStore.loadCheckpoint()

    /**
     * A [PendingVerification] restored from [persistentRecoveryStore] at
     * construction (M7) — a create's [com.softwaremine.dps.domain.tool.ToolResult.Success]
     * was already confirmed, but verification itself never ran because an
     * earlier process died in the window between that confirmation and
     * [ExecutionVerifier.verify] clearing the record. `null` once resolved,
     * or from construction when nothing was left pending.
     *
     * ## Why only the *record* loads here, not the resolution
     * Unlike [pendingCheckpoint] (a cheap `SharedPreferences` read either
     * way), actually resolving this means reading back a real task or
     * calendar record — for a calendar event, a genuine `ContentResolver`
     * query. Running that inside a constructor would risk blocking
     * whichever thread first touches this class (a real rule this
     * project's own coding standards state explicitly: no blocking calls
     * on the main thread). Only the durable *fact* that something is
     * pending is loaded eagerly here; [handle]'s own first call resolves
     * it, inside a suspend context, exactly once.
     */
    private var pendingVerification: PendingVerification? = persistentRecoveryStore.loadVerification()

    /**
     * A [PendingAutomationAction] restored from [persistentRecoveryStore]
     * at construction (M9) — a bounded UI action was dispatched, but
     * observation and verification never ran because an earlier process
     * died in the window between the action and
     * [AutomationVerifier] resolving it. `null` once resolved, or from
     * construction when nothing was left pending. Mirrors
     * [pendingVerification]'s own shape exactly — see that field's own doc
     * for why only the record loads eagerly here, never the resolution.
     */
    private var pendingAutomation: PendingAutomationAction? = persistentRecoveryStore.loadAutomation()

    /**
     * Handles one user message.
     *
     * Never throws — every path resolves to a [ToolOrchestrator.Outcome],
     * mirroring [ToolOrchestrator.handle]'s own guarantee, since a thrown
     * exception here would surface to the user as a crash.
     *
     * ## M5-B: the recovery prompt comes first
     * When [restoredRecovery] is non-null — a persisted record survived from
     * an earlier process — this message is never classified as a fresh
     * request at all. It is offered to [resolveRecoveryPrompt] instead,
     * which asks a plain yes/no question and does not touch
     * [toolOrchestrator] or any tool until a later turn's explicit "yes"
     * restores the exact pending state that was interrupted. See
     * [ExecutionRecoveryState]'s own doc for the full scope and safety
     * reasoning.
     *
     * Every other line below is exactly [handleInternal]'s pre-M5-B body,
     * with one addition: [syncRecoveryPersistence] at the very end, so
     * whichever `pending*` field this turn leaves set (if any) reaches disk
     * before this suspend function returns control to the caller.
     *
     * @param recentContext see [com.softwaremine.dps.ai.intent.IntentPromptBuilder.build]
     *   (Day 08-B) — the last exchange, pre-rendered as plain text, or `null`
     *   when there is none. Threaded through only so the classification pass
     *   has enough context to answer directly when it turns out to be
     *   conversation; nothing else here reads it.
     */
    suspend fun handle(userMessage: String, recentContext: String? = null): ToolOrchestrator.Outcome {
        // M5-C: takes precedence over M5-B's own recovery prompt — an
        // operation whose outcome is unknown must never be silently
        // resumed or asked to "continue", the way a merely-blocked
        // question can be. It is surfaced once, unconditionally, and
        // cleared — userMessage is never classified or inspected here
        // either, for the identical reason restoredRecovery's own
        // first-turn branch below never inspects it.
        pendingCheckpoint?.let { checkpoint ->
            pendingCheckpoint = null
            persistentRecoveryStore.clearCheckpoint()
            return outstandingCheckpointNotice(checkpoint)
        }
        // M7: unlike the checkpoint above, this is always fully resolvable
        // by DPS itself — no question needs asking. When it resolves as
        // Verified, nothing is shown and this turn's real message
        // proceeds normally below; only a genuine mismatch/not-found/
        // observation-failure interrupts the first message with a notice,
        // exactly once. userMessage is never inspected for this decision.
        if (pendingVerification != null) {
            pendingVerification = null
            resolveOutstandingVerificationNotice()?.let { return it }
        }
        // M9: identical shape to the M7 check above — always fully
        // resolvable by DPS itself (re-observe, never re-tap), so no
        // question is asked; only a genuine mismatch/not-found/
        // observation-failure interrupts the first message with a notice.
        if (pendingAutomation != null) {
            pendingAutomation = null
            resolveOutstandingAutomationNotice()?.let { return it }
        }
        restoredRecovery?.let { restored ->
            return if (recoveryPromptShown) {
                // This message is the user's actual answer to the question
                // shown below, on some earlier turn.
                resolveRecoveryPrompt(userMessage, restored)
            } else {
                // The very first message of a fresh process cannot possibly
                // be an answer to a question the user has not seen yet —
                // there is no proactive, unprompted way to show anything in
                // this app's existing UI (no settings screen, no on-launch
                // banner), so the *first* real message after restart is
                // intercepted and replaced with the recovery question
                // itself, exactly as this milestone's own brief describes.
                // userMessage is deliberately never classified or otherwise
                // inspected here.
                recoveryPromptShown = true
                recoveryQuestionOutcome(restored)
            }
        }
        val outcome = handleInternal(userMessage, recentContext)
        syncRecoveryPersistence()
        return outcome
    }

    /** [handle]'s own pre-M5-B body — see that function's doc for why this split exists. */
    private suspend fun handleInternal(userMessage: String, recentContext: String? = null): ToolOrchestrator.Outcome {
        recordDefaultLeadMinutesIfStated(userMessage)

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

    /**
     * M6: detects and persists an explicit "always remind me N minutes
     * before" statement, independent of whatever [userMessage] otherwise
     * classifies as. See [PreferenceStatementRecognizer]'s own doc for why
     * this is narrow and deterministic — a plain one-off "remind me 15
     * minutes before" (no "default"/"hamesha" cue) returns `null` here and
     * leaves classification and every pending flow below completely
     * unaffected.
     */
    private fun recordDefaultLeadMinutesIfStated(userMessage: String) {
        val minutes = PreferenceStatementRecognizer.recognizeDefaultLeadMinutes(userMessage, referenceResolver)
            ?: return
        val current = persistentPreferenceStore.load()
        persistentPreferenceStore.save(current.copy(defaultReminderLeadMinutes = minutes))
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
     *
     * A permission block never parks a [PendingPlan] and is not itself
     * covered by M5-B's persisted recovery (see [ExecutionRecoveryState]'s
     * own doc for that scope boundary) — but resuming here can still lead
     * to [continueAfterContactLookup]/[attachSuggestionIfApplicable] setting
     * [pendingConfirmation] (a follow-up suggestion after a successful
     * create), which *is* covered, so [syncRecoveryPersistence] runs here
     * too rather than only after [handle].
     *
     * ## M8: [pendingIntentAwaitingActionPermission] takes precedence
     * Checked first, and handled entirely separately from
     * [ToolOrchestrator]'s own resume path below — see that field's own
     * doc for why. Nothing was ever dispatched to [toolOrchestrator] for
     * this case, so there is nothing to resume there; [proceedToExecution]
     * is re-entered from the top instead, which asks for confirmation
     * fresh once the permission is actually usable.
     */
    suspend fun onPermissionResult(): ToolOrchestrator.Outcome? {
        _state.value = transition(SecretaryEvent.PermissionGranted)

        pendingIntentAwaitingActionPermission?.let { intent ->
            pendingIntentAwaitingActionPermission = null
            val outcome = continueAfterResumedStep(proceedToExecution(intent))
            syncRecoveryPersistence()
            return outcome
        }

        val outcome = toolOrchestrator.resumeAfterPermissionGrant()
            ?: run {
                pendingIntentAwaitingContactPermission = null
                _state.value = transition(SecretaryEvent.Reset)
                return null
            }

        val awaitingContact = pendingIntentAwaitingContactPermission
        val result = if (awaitingContact != null && outcome is ToolOrchestrator.Outcome.Handled) {
            pendingIntentAwaitingContactPermission = null
            continueAfterContactLookup(awaitingContact, outcome)
        } else {
            recordOutcome(outcome)
        }
        syncRecoveryPersistence()
        return result
    }

    /** Discards any pending question or held action, and forgets the conversation so far. */
    fun reset() {
        pendingClarification = null
        pendingContactSelection = null
        pendingTypeDisambiguation = null
        pendingConfirmation = null
        pendingPlan = null
        pendingIntentAwaitingContactPermission = null
        pendingIntentAwaitingActionPermission = null
        restoredRecovery = null
        recoveryPromptShown = false
        // M5-C: pendingCheckpoint is deliberately NOT cleared here. It
        // represents a real-world ambiguity about whether a create
        // operation actually happened — not conversational context — and
        // "forget the conversation" must not also silently forget that a
        // task or reminder might need checking. It still surfaces on the
        // next handle() call, exactly as it would have without this reset.
        toolOrchestrator.reset()
        _state.value = transition(SecretaryEvent.Reset)
        _memory.value = ConversationMemory.EMPTY
        // M3-B: an explicit "forget the conversation" action, not an
        // ordinary write — clears the durable copy outright rather than
        // going through updateMemory/save(EMPTY), mirroring the distinction
        // the M3-B spec draws between the two.
        persistentMemoryStore.clear()
        // M5-B: mirrors persistentMemoryStore's own clear() above exactly —
        // an explicit "forget everything" action must also discard any
        // interrupted request still waiting to be resumed.
        persistentRecoveryStore.clear()
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

                    // M7: a step whose tool call succeeded but whose outcome
                    // could not be verified is treated the same way a failed
                    // step already is — stop the plan, run no further steps,
                    // park nothing to resume. lastStepVerification was set by
                    // recordOutcome() inside the proceedToExecution() call
                    // just above, for this exact step, in this same suspend
                    // call chain — read immediately, before the next loop
                    // iteration could run and overwrite it for a different
                    // step. Previously completed steps are untouched: their
                    // own replies are already folded into `replies` above.
                    val verification = lastStepVerification
                    if (verification != null && verification !is VerificationOutcome.Verified) {
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

                // M9 fix: mirrors continuePlan's own per-iteration
                // verification gate exactly (`:854`-region) — a resumed step
                // whose tool call succeeded but whose outcome could not be
                // verified must stop the plan here too, never continue into
                // the remainder. Unreachable before M9: no M8 confirm-required
                // type was ever also an M7-verified CREATE, so this branch
                // never fired in practice until an automation step (verified,
                // and confirm-required) could itself be the resumed step —
                // caught by this milestone's own regression tests, not a
                // theoretical concern.
                val verification = lastStepVerification
                if (verification != null && verification !is VerificationOutcome.Verified) {
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
     * [ToolOrchestrator.executeIntent] — where a risk/permission precheck
     * (M8), contact grounding, a destructive-action confirmation, or a
     * follow-up suggestion can each insert themselves without
     * [ToolOrchestrator] ever needing to know any of them exist.
     *
     * ## M8: RISK → PERMISSION PRECHECK → CONFIRMATION, ahead of everything else
     * [RiskPolicy.classify] is checked first, before contact grounding —
     * deliberately, so a missing permission is discovered (and reported)
     * before a `find_contact` lookup for [IntentType.CALL_CONTACT] is even
     * attempted, and so a [RiskLevel.CONFIRM_REQUIRED] action is never
     * asked to confirm when it is already known it cannot run. See
     * [missingPermissionsFor]/[blockOnActionPermission]'s own docs.
     *
     * [IntentType.CALL_CONTACT] is the one confirm-required type asked
     * about *later*, in [proceedAfterContactResolved] — its question needs
     * a grounded phone number this function does not have yet. Every other
     * confirm-required type ([askConfirmationFor]) has nothing left to
     * resolve and is asked immediately, once permission is confirmed
     * present.
     */
    private suspend fun proceedToExecution(intent: DpsIntent): ToolOrchestrator.Outcome {
        if (RiskPolicy.classify(intent) == RiskLevel.CONFIRM_REQUIRED) {
            val missing = missingPermissionsFor(intent)
            if (missing.isNotEmpty()) {
                return blockOnActionPermission(intent, missing)
            }
            if (intent.type != IntentType.CALL_CONTACT) {
                return askConfirmationFor(intent)
            }
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
     * resolution funnels through, so [IntentType.CALL_CONTACT]'s
     * confirmation is asked about exactly once, in exactly one place,
     * regardless of which of the three routes (direct phone, single
     * resolved match, or a disambiguation pick) got here. Permission for
     * this type was already prechecked in [proceedToExecution], before
     * contact grounding ever began.
     */
    private suspend fun proceedAfterContactResolved(intent: DpsIntent): ToolOrchestrator.Outcome =
        if (intent.type == IntentType.CALL_CONTACT && intent.parameters.value(IntentField.PHONE) != null) {
            askConfirmationFor(intent)
        } else {
            finishExecution(intent)
        }

    /**
     * The permissions [intent]'s own tool would need but does not currently
     * have (M8) — a pure, synchronous read via the exact same
     * [PermissionManager.missing] call
     * [com.softwaremine.dps.ai.tool.DefaultToolExecutor] itself makes
     * before dispatch, reached here through the already-existing
     * [com.softwaremine.dps.domain.intent.toolId] mapping and [toolRegistry]
     * rather than a second permission system. Empty for
     * [IntentType.CONVERSATION] (no tool) and for any tool declaring no
     * required permissions (e.g. `cancel_task`, `forget_fact`) — the
     * precheck is then a correct no-op.
     */
    private fun missingPermissionsFor(intent: DpsIntent): Set<DpsPermission> {
        val toolId = intent.type.toolId ?: return emptySet()
        val required = toolRegistry.find(toolId)?.requiredPermissions ?: emptySet()
        return permissionManager.missing(required)
    }

    /**
     * Blocks [intent] on a permission its tool needs but does not have,
     * before ever asking for confirmation (M8 locked decision 6). Mirrors
     * [resolveContactThenExecute]'s own [pendingIntentAwaitingContactPermission]
     * shape, generalized from "a find_contact pre-resolution step" to "any
     * confirm-required intent whose own tool needs a permission it doesn't
     * have yet" — see [pendingIntentAwaitingActionPermission]'s own doc for
     * why a new field is needed rather than reusing an existing one.
     *
     * [ToolResult.PermissionRequired.rationale] is never read by
     * [ToolResponseGenerator.describe] — that phrasing is derived entirely
     * from `permissions` and the intent's own type — so no duplicate of
     * [com.softwaremine.dps.ai.tool.DefaultToolExecutor]'s private
     * rationale-building logic is needed here; a short, honest placeholder
     * is sufficient.
     */
    private fun blockOnActionPermission(
        intent: DpsIntent,
        missing: Set<DpsPermission>,
    ): ToolOrchestrator.Outcome.NeedsPermission {
        pendingIntentAwaitingActionPermission = intent
        _state.value = transition(SecretaryEvent.PermissionNeeded)
        val result = ToolResult.PermissionRequired(
            permissions = missing.toList(),
            rationale = "DPS needs this permission before that action can be confirmed.",
        )
        return ToolOrchestrator.Outcome.NeedsPermission(responses.describe(result, intent), result)
    }

    /**
     * Dispatches to the right confirmation question for a
     * [RiskLevel.CONFIRM_REQUIRED] intent (M8) — the three shapes
     * [RiskPolicy.classify] currently recognizes. Whichever type is added
     * to [RiskPolicy] in the future must get a branch here too; there is no
     * compiler-enforced link between the two, so this coupling is
     * deliberate and documented, not silent.
     */
    private fun askConfirmationFor(intent: DpsIntent): ToolOrchestrator.Outcome = when (intent.type) {
        IntentType.CALL_CONTACT -> askCallConfirmation(intent)
        IntentType.FORGET_FACT -> askForgetFactConfirmation(intent)
        IntentType.AUTOMATION -> askAutomationConfirmation(intent)
        else -> askDeleteConfirmation(intent)
    }

    /**
     * Asks before performing the one bounded UI interaction (M9) — the
     * exact same "ask before doing something to another app" boundary
     * [askCallConfirmation] already enforces, extended here to a genuine
     * accessibility-driven action rather than merely opening a dialer.
     * [com.softwaremine.dps.data.android.tool.AndroidAutomationTool]
     * itself performs the tap unconditionally once called; this is the
     * only gate.
     */
    private fun askAutomationConfirmation(intent: DpsIntent): ToolOrchestrator.Outcome {
        val appName = intent.parameters.value(IntentField.TITLE) ?: "that app"
        val question = "Open $appName and tap the button?"

        pendingConfirmation = PendingConfirmation(intent, now())
        _state.value = transition(SecretaryEvent.ConfirmationRequested)
        return ToolOrchestrator.Outcome.Clarify(
            question,
            IntentResolution.NeedsClarification(intent, question, emptySet(), intent.parameters),
        )
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

    /**
     * Asks before forgetting a remembered fact (M8) — the same "ask before
     * doing something irreversible" boundary [askDeleteConfirmation]
     * already enforces for calendar/task/reminder, extended to
     * [IntentType.FORGET_FACT] per M8's own locked contract:
     * `remember_fact`/`forget_fact` are otherwise reversible of each other,
     * but a `forget_fact` DPS was never asked to reconsider is not.
     * [com.softwaremine.dps.data.android.tool.AndroidMemoryTool] itself
     * deletes unconditionally once called; this is the only gate.
     */
    private fun askForgetFactConfirmation(intent: DpsIntent): ToolOrchestrator.Outcome {
        val subject = intent.parameters.value(IntentField.TITLE) ?: "that"
        val question = "Forget what I remember about \"$subject\"? This can't be undone."

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
        // M7: a step whose own outcome could not be confirmed must never
        // offer a confident-sounding follow-up about it ("...a reminder
        // for this too?") — see ExecutionVerifier's own doc.
        val verification = lastStepVerification
        if (verification != null && verification !is VerificationOutcome.Verified) return outcome
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
    // M5-B — persisted, user-gated execution recovery
    //
    // Everything below this line is new for M5-B. Nothing here executes a
    // tool, classifies a request, or calls proceedToExecution — the sole
    // job of this section is to (a) mirror the four pending*/pendingPlan
    // fields to durable storage as they change, and (b) on a fresh process
    // that finds one, ask the user a plain yes/no question before restoring
    // it back into those exact same fields. A "yes" only re-asks the
    // question that was already pending; the user's *next* message answers
    // it through the ordinary resolveContactSelection/resolveConfirmation/
    // resolveTypeDisambiguation/handleSingleStep paths above, unchanged.
    //
    // Scope boundary, documented not silent: a permission block
    // (ToolOrchestrator's own pendingPermission) is not covered here — see
    // ExecutionRecoveryState's own doc for why. Also not covered: a process
    // death between two plan steps that were never blocked at all (nothing
    // is ever parked for "step 2 is up next," only for "step N is blocked
    // on the user") — PendingPlan itself has never modeled that case, on
    // any process, and M5-B does not change what PendingPlan represents.
    // -----------------------------------------------------------------

    /**
     * Answers the recovery prompt raised for [restored] — the one thing
     * [handle] does before anything else once a fresh process has detected
     * persisted execution-recovery state. Reuses [confirmationParser]
     * exactly like [resolveConfirmation] does for every other yes/no this
     * class asks, rather than inventing new parsing.
     *
     * ## No execution before an explicit yes
     * A "yes" only calls [restorePendingState] — which sets exactly one of
     * [pendingClarification]/[pendingContactSelection]/[pendingTypeDisambiguation]/
     * [pendingConfirmation] (plus [pendingPlan] when the record carried one)
     * — and re-asks the same question via [pendingQuestion]. It never calls
     * [proceedToExecution], [finishExecution], or [toolOrchestrator] at all.
     * The tool layer is only ever reached by the user's *next* message,
     * through the exact same resume paths that already exist.
     *
     * ## Declining or an unclear reply
     * Both discard the persisted record and the [restoredRecovery] field
     * outright — no operation ever runs — mirroring
     * [resolveConfirmation]'s own established "not an answer, so it never
     * gets to insist" philosophy for [Confirmation.UNCLEAR]: the user's
     * message is processed as the fresh request it then genuinely is.
     */
    private suspend fun resolveRecoveryPrompt(
        userMessage: String,
        restored: ExecutionRecoveryState,
    ): ToolOrchestrator.Outcome {
        return when (confirmationParser.parse(userMessage)) {
            Confirmation.YES -> {
                restoredRecovery = null
                restorePendingState(restored)
                val question = pendingQuestion()
                syncRecoveryPersistence()
                if (question == null) {
                    // Defensive: restorePendingState always sets exactly one
                    // of the four fields pendingQuestion() reads directly
                    // afterward, so this branch is unreachable in practice.
                    logger.w(TAG, "Recovery restored but produced no question to re-ask")
                    ToolOrchestrator.Outcome.Conversational(
                        reason = "recovery restore produced nothing to ask",
                        replyText = "Something went wrong resuming that — could you repeat the request?",
                    )
                } else {
                    val prefixed = "Continuing where we left off. $question"
                    ToolOrchestrator.Outcome.Clarify(
                        prefixed,
                        IntentResolution.NeedsClarification(restoredIntent(restored), prefixed, emptySet(), restoredIntent(restored).parameters),
                    )
                }
            }

            Confirmation.NO -> {
                restoredRecovery = null
                persistentRecoveryStore.clear()
                logger.i(TAG, "User declined to resume a persisted recovery record; discarding")
                ToolOrchestrator.Outcome.Conversational(
                    reason = "user declined execution recovery",
                    replyText = "Alright, I've dropped that.",
                )
            }

            Confirmation.UNCLEAR -> {
                restoredRecovery = null
                persistentRecoveryStore.clear()
                handle(userMessage, null)
            }
        }
    }

    /**
     * The recovery question itself — shown exactly once, on the first
     * message of a fresh process that restored a persisted record, before
     * that message (or anything else) is ever classified.
     */
    /**
     * The one-time, informational notice shown when [pendingCheckpoint]
     * survived from an earlier process (M5-C). Deliberately not a yes/no
     * question — there is no safe "yes" here, since re-running the create
     * could duplicate an operation that already succeeded. The user is
     * told plainly what may have happened and left to check for
     * themselves; nothing in this class attempts to verify it on their
     * behalf, matching the milestone's own explicit "no content-based
     * reconciliation" boundary.
     */
    /**
     * ## `create_event` (M5-E): the same notice, no reconciliation
     * An earlier version of this milestone reconciled a `create_event`
     * checkpoint against the real Calendar Provider before composing this
     * reply, so a confirmed match could report success instead of
     * uncertainty. That required tagging the created event via
     * `CalendarContract.ExtendedProperties`, which is a platform-enforced,
     * sync-adapter-only write no ordinary app — including this one — can
     * ever perform (confirmed on-device, not merely undocumented). See
     * [OperationCheckpoint]'s own doc for the full account. `create_event`
     * therefore falls through to exactly the same uncertain notice
     * [OperationType.CREATE_TASK]/[OperationType.CREATE_REMINDER] already
     * use, unchanged.
     */
    private fun outstandingCheckpointNotice(checkpoint: OperationCheckpoint): ToolOrchestrator.Outcome.Conversational {
        val kind = when (checkpoint.operationType) {
            OperationType.CREATE_TASK -> "task"
            OperationType.CREATE_REMINDER -> "reminder"
            OperationType.CREATE_EVENT -> "event"
        }
        return ToolOrchestrator.Outcome.Conversational(
            reason = "surfacing an outstanding operation checkpoint",
            replyText = "Before this restarted, I may have started creating a $kind (\"${checkpoint.title}\") " +
                "but couldn't confirm it finished. I haven't repeated it automatically — " +
                "please check your ${kind}s, and let me know if you'd still like me to create it.",
        )
    }

    /**
     * Resolves whatever [PendingVerification] survived a restart (M7),
     * returning a one-time honest notice only when it did not come back
     * [VerificationOutcome.Verified] — `null` means either nothing was
     * pending or resolution came back clean, and [handle] falls through to
     * the user's real message unchanged either way.
     */
    private suspend fun resolveOutstandingVerificationNotice(): ToolOrchestrator.Outcome.Conversational? {
        val outcome = executionVerifier.resolvePendingVerificationIfAny() ?: return null
        if (outcome is VerificationOutcome.Verified) return null

        return ToolOrchestrator.Outcome.Conversational(
            reason = "surfacing an unresolved verification from before this restarted",
            replyText = "Before this restarted, ${responses.describeVerification(outcome).replaceFirstChar(Char::lowercase)}",
        )
    }

    /**
     * M9's exact mirror of [resolveOutstandingVerificationNotice] — see
     * that function's own doc. [AutomationVerifier.resolvePendingAutomationIfAny]
     * re-observes real UI state; it never re-taps.
     */
    private suspend fun resolveOutstandingAutomationNotice(): ToolOrchestrator.Outcome.Conversational? {
        val outcome = automationVerifier.resolvePendingAutomationIfAny() ?: return null
        if (outcome is VerificationOutcome.Verified) return null

        return ToolOrchestrator.Outcome.Conversational(
            reason = "surfacing an unresolved automation action from before this restarted",
            replyText = "Before this restarted, ${responses.describeVerification(outcome).replaceFirstChar(Char::lowercase)}",
        )
    }

    private fun recoveryQuestionOutcome(restored: ExecutionRecoveryState): ToolOrchestrator.Outcome.Conversational {
        val what = when (val pending = restored.pending) {
            is PersistedPendingState.Clarification -> describeIntent(pending.intent)
            is PersistedPendingState.ContactSelection -> describeIntent(pending.originalIntent)
            is PersistedPendingState.TypeDisambiguation -> describeIntent(pending.originalIntent)
            is PersistedPendingState.Confirmation -> describeIntent(pending.intent)
        }
        return ToolOrchestrator.Outcome.Conversational(
            reason = "offering execution recovery",
            replyText = "A previous request was interrupted$what. Would you like to continue it?",
        )
    }

    /** A short, optional " (...)" clause naming what the interrupted request was, when a title is known. */
    private fun describeIntent(intent: DpsIntent): String =
        intent.parameters.value(IntentField.TITLE)?.let { " (\"$it\")" } ?: ""

    /** The intent inside [state]'s pending record, regardless of which of the four it is. */
    private fun restoredIntent(state: ExecutionRecoveryState): DpsIntent = when (val pending = state.pending) {
        is PersistedPendingState.Clarification -> pending.intent
        is PersistedPendingState.ContactSelection -> pending.originalIntent
        is PersistedPendingState.TypeDisambiguation -> pending.originalIntent
        is PersistedPendingState.Confirmation -> pending.intent
    }

    /**
     * Restores [restored] into the exact live fields it was snapshotted
     * from, stamping a fresh [now] as each restored field's own
     * `requestedAtMillis` — re-asking the question right now is, from the
     * user's side, indistinguishable from the assistant asking it for the
     * first time, so it earns a fresh freshness window rather than
     * inheriting however much of the original five minutes happened to
     * remain when the process died.
     */
    private fun restorePendingState(restored: ExecutionRecoveryState) {
        val nowMillis = now()
        when (val pending = restored.pending) {
            is PersistedPendingState.Clarification -> {
                pendingClarification = IntentResolution.NeedsClarification(
                    intent = pending.intent,
                    question = pending.question,
                    missing = pending.missing,
                    partial = pending.partial,
                )
                _state.value = transition(SecretaryEvent.MessageReceived)
                _state.value = transition(SecretaryEvent.ClarificationNeeded)
            }

            is PersistedPendingState.ContactSelection -> {
                pendingContactSelection = PendingContactSelection(pending.originalIntent, pending.candidates, nowMillis)
                _state.value = transition(SecretaryEvent.MessageReceived)
                _state.value = transition(SecretaryEvent.ContactAmbiguous)
            }

            is PersistedPendingState.TypeDisambiguation -> {
                pendingTypeDisambiguation = PendingTypeDisambiguation(
                    pending.originalIntent,
                    pending.candidates.map { DisambiguationCandidate(it.type, it.targetId, it.label) },
                    nowMillis,
                )
                _state.value = transition(SecretaryEvent.MessageReceived)
                _state.value = transition(SecretaryEvent.TypeDisambiguationNeeded)
            }

            is PersistedPendingState.Confirmation -> {
                pendingConfirmation = PendingConfirmation(pending.intent, nowMillis)
                _state.value = transition(SecretaryEvent.MessageReceived)
                _state.value = transition(SecretaryEvent.ConfirmationRequested)
            }
        }
        pendingPlan = restored.plan?.let {
            PendingPlan(
                remainingSteps = it.remainingSteps,
                remainingOffsets = it.remainingOffsets,
                completedReplies = it.completedReplies,
                lastEventStartMillis = it.lastEventStartMillis,
                requestedAtMillis = nowMillis,
            )
        }
    }

    /**
     * Mirrors whichever of [pendingClarification]/[pendingContactSelection]/
     * [pendingTypeDisambiguation]/[pendingConfirmation] is currently set —
     * plus [pendingPlan] when present — to [persistentRecoveryStore], or
     * clears the store when none of the four is set. Called once, at the
     * end of [handle] and [onPermissionResult], rather than at each of the
     * several call sites that individually set or clear one of those
     * fields — by the time either of those two functions returns, the live
     * model has already settled into its final shape for this turn, so one
     * synchronization point covers every call site without duplicating
     * logic at each of them.
     *
     * Never called while [restoredRecovery] is still awaiting the user's
     * own yes/no — that record is [resolveRecoveryPrompt]'s concern alone,
     * and [handle] returns before reaching this function in that case.
     */
    private fun syncRecoveryPersistence() {
        val pending = currentPersistablePendingState()
        if (pending == null) {
            persistentRecoveryStore.clear()
            return
        }
        persistentRecoveryStore.save(
            ExecutionRecoveryState(
                pending = pending,
                plan = pendingPlan?.let {
                    PersistedPendingPlan(
                        remainingSteps = it.remainingSteps,
                        remainingOffsets = it.remainingOffsets,
                        completedReplies = it.completedReplies,
                        lastEventStartMillis = it.lastEventStartMillis,
                        requestedAtMillis = it.requestedAtMillis,
                    )
                },
            ),
        )
    }

    /**
     * A persistable snapshot of whichever of the four `pending*` fields is
     * currently set, or `null` when none is. [pendingClarification] is
     * stamped with [now] here — unlike the other three, its live type
     * carries no `requestedAtMillis` of its own at all (a genuine,
     * intentional asymmetry — see [ExecutionRecoveryState]'s own doc), so
     * "as of this synchronization point, it was still active" is the most
     * accurate timestamp available for it.
     */
    private fun currentPersistablePendingState(): PersistedPendingState? {
        pendingClarification?.let {
            return PersistedPendingState.Clarification(it.intent, it.question, it.missing, it.partial, now())
        }
        pendingContactSelection?.let {
            return PersistedPendingState.ContactSelection(it.originalIntent, it.candidates, it.requestedAtMillis)
        }
        pendingTypeDisambiguation?.let {
            return PersistedPendingState.TypeDisambiguation(
                it.originalIntent,
                it.candidates.map { candidate -> PersistedDisambiguationCandidate(candidate.type, candidate.targetId, candidate.label) },
                it.requestedAtMillis,
            )
        }
        pendingConfirmation?.let {
            return PersistedPendingState.Confirmation(it.intent, it.requestedAtMillis)
        }
        return null
    }

    // -----------------------------------------------------------------

    private fun transition(event: SecretaryEvent): SecretaryState =
        SecretaryStateMachine.transition(_state.value, event)

    /** Applies memory and state consequences for an outcome produced by [ToolOrchestrator]. */
    private suspend fun recordOutcome(outcome: ToolOrchestrator.Outcome): ToolOrchestrator.Outcome {
        // M7: reset on every call, unconditionally, before anything below
        // might set it — see this field's own doc for why a step that
        // never reaches verification must not inherit an earlier step's
        // outcome (Section 11's own stale-state requirement).
        lastStepVerification = null

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
                    // M6: episodic logging is independent of, and never gates,
                    // ConversationMemoryUpdater's own remember() above — see
                    // EpisodicMemoryRecorder's own doc for why a failed/
                    // cancelled outcome leaves no episodic trace either.
                    episodicMemoryRecorder.record(toolId, outcome.result)
                }

                // M7/M9: verification is independent of, and never gates,
                // either memory write above — a mismatch does not undo the
                // fact that a real tool call already happened and is
                // already the most recent task/reminder/event in memory.
                // Only WhatsApp/email/phone/notification/reminder and every
                // non-CREATE task/calendar action are structurally excluded
                // from M7 verification (see ExecutionVerifier's own doc);
                // every other Success return here is checked. M9's
                // automationVerifier is consulted only when executionVerifier
                // itself returned null (never applicable to the same
                // intent — their scopes, TASK/CALENDAR_EVENT create versus
                // AUTOMATION, are mutually exclusive) — see AutomationVerifier's
                // own doc for why it is a separate class, not a new
                // ExecutionVerifier branch.
                val success = outcome.result as? ToolResult.Success
                lastStepVerification = success?.let {
                    executionVerifier.verify(outcome.intent, it) ?: automationVerifier.verify(outcome.intent, it)
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

        // M7: never claims success when verification came back anything
        // but Verified, and never claims failure either — describeVerification's
        // own wording states only what is actually known. The rewrite is
        // the *only* change; result/intent/memory are all untouched.
        val verification = lastStepVerification
        return if (outcome is ToolOrchestrator.Outcome.Handled && verification != null && verification !is VerificationOutcome.Verified) {
            outcome.copy(reply = responses.describeVerification(verification))
        } else {
            outcome
        }
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
         * Fallback noun for [askDeleteConfirmation] when nothing more
         * specific is known. Which types this applies to is now
         * [RiskPolicy]'s concern (M8) — this map only supplies wording for
         * whichever of them [askConfirmationFor] dispatches here.
         */
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
