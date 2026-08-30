package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.contact.Contact
import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentField
import com.softwaremine.dps.domain.intent.IntentParameters
import com.softwaremine.dps.domain.intent.IntentType
import kotlinx.serialization.Serializable

/**
 * A durable snapshot of a user-initiated request [ai.secretary.SecretaryOrchestrator]
 * was in the middle of handling — one blocked step, plus whatever
 * [PersistedPendingPlan] remainder was parked behind it — persisted so a
 * fresh process can detect and offer to continue it (M5-B).
 *
 * ## Why this is a separate type from [PendingPlan]/[PendingConfirmation]/etc.
 * Those four types (plus the bare `pendingClarification` field) are the
 * *live*, in-memory execution model — see [PendingPlan]'s own doc for why
 * they intentionally never duplicate one another. This is a *persisted
 * snapshot* of exactly one of them, taken at the moment it was set, with its
 * own serialization concerns (schema evolution, corrupt-storage recovery)
 * that have nothing to do with how the live model behaves turn to turn.
 * Mirroring the M4-B precedent ([com.softwaremine.dps.domain.proactive.UpcomingEventOccurrence]
 * staying deliberately separate from [com.softwaremine.dps.data.android.calendar.CalendarWriter.EventOccurrence])
 * rather than retrofitting `@Serializable` onto the live types themselves.
 *
 * ## Every field here is already `@Serializable`
 * [DpsIntent], [IntentParameters], [IntentField] and [Contact] all already
 * carry `kotlinx.serialization` annotations for unrelated reasons (M3-A,
 * Day 05 Phase D) — nothing new needed serializing to make this possible.
 *
 * ## Scope: which pending states this covers
 * [pendingClarification][PersistedPendingState.Clarification],
 * [pendingContactSelection][PersistedPendingState.ContactSelection],
 * [pendingTypeDisambiguation][PersistedPendingState.TypeDisambiguation] and
 * [pendingConfirmation][PersistedPendingState.Confirmation] — the four
 * blocked-step reasons that can coexist with a [PersistedPendingPlan]
 * remainder today. A permission block (`ToolOrchestrator`'s own
 * `pendingPermission`) is deliberately **not** covered: M2-C already never
 * parks a [PendingPlan] remainder behind one (see [PendingPlan]'s own doc),
 * and the held action itself lives inside `ToolOrchestrator`'s private
 * state, not `SecretaryOrchestrator`'s — persisting it would mean either
 * exposing new internals from `ToolOrchestrator` or duplicating its resume
 * logic here, both outside M5-B's approved scope. Deferred, not solved.
 *
 * ## Why [PersistedPendingState.Clarification] carries its own [requestedAtMillis]
 * where the live `pendingClarification` field (bare [com.softwaremine.dps.domain.intent.IntentResolution.NeedsClarification])
 * carries none at all — a genuine, intentional asymmetry with the in-memory
 * model, not an oversight. In memory, an unbounded-age clarification is a
 * live conversation the user is presumably still in; on disk, the same
 * record could sit for days before the app is reopened, and prompting
 * "want to finish creating that thing you mentioned last week?" is exactly
 * the stale-resume risk M5-A flagged. The freshness guard therefore applies
 * only at the persistence boundary — restoring a stale record silently
 * discards it rather than surfacing a prompt — and the live field's own
 * behavior is untouched.
 *
 * ## No automatic execution, ever
 * Nothing in this type or its store executes anything. It is inert data.
 * Turning a restored record back into a live resume is
 * [ai.secretary.SecretaryOrchestrator]'s own job, gated on an explicit user
 * "yes" — see that class's `resolveRecoveryPrompt`.
 */
@Serializable
data class ExecutionRecoveryState(
    val pending: PersistedPendingState,
    val plan: PersistedPendingPlan?,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        /**
         * Bumped only if [PersistedPendingState] or [PersistedPendingPlan]'s
         * shape changes in a way `ignoreUnknownKeys` cannot absorb. Not read
         * anywhere yet — present so a future migration has somewhere to
         * branch on, the same "schema/version information if appropriate"
         * discipline this milestone's own brief calls for.
         */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Which single blocked step [ExecutionRecoveryState] is a snapshot of, and
 * why. One-to-one with exactly one of `SecretaryOrchestrator`'s own four
 * `pending*` fields — see [ExecutionRecoveryState]'s own doc for the scope
 * boundary.
 */
@Serializable
sealed interface PersistedPendingState {
    val requestedAtMillis: Long

    /** Mirrors the live, bare `pendingClarification` field exactly, field for field. */
    @Serializable
    data class Clarification(
        val intent: DpsIntent,
        val question: String,
        val missing: Set<IntentField>,
        val partial: IntentParameters,
        override val requestedAtMillis: Long,
    ) : PersistedPendingState

    /** Mirrors [PendingContactSelection] exactly, field for field. */
    @Serializable
    data class ContactSelection(
        val originalIntent: DpsIntent,
        val candidates: List<Contact>,
        override val requestedAtMillis: Long,
    ) : PersistedPendingState

    /** Mirrors [PendingTypeDisambiguation] exactly, field for field. */
    @Serializable
    data class TypeDisambiguation(
        val originalIntent: DpsIntent,
        val candidates: List<PersistedDisambiguationCandidate>,
        override val requestedAtMillis: Long,
    ) : PersistedPendingState

    /** Mirrors [PendingConfirmation] exactly, field for field. */
    @Serializable
    data class Confirmation(
        val intent: DpsIntent,
        override val requestedAtMillis: Long,
    ) : PersistedPendingState
}

/** Mirrors [DisambiguationCandidate] exactly — that type has no `@Serializable` of its own. */
@Serializable
data class PersistedDisambiguationCandidate(
    val type: IntentType,
    val targetId: String,
    val label: String,
)

/** Mirrors [PendingPlan] exactly, field for field — see [ExecutionRecoveryState]'s own doc for why this is a separate type rather than annotating [PendingPlan] itself. */
@Serializable
data class PersistedPendingPlan(
    val remainingSteps: List<DpsIntent>,
    val remainingOffsets: List<Long?>,
    val completedReplies: List<String>,
    val lastEventStartMillis: Long?,
    val requestedAtMillis: Long,
)

/**
 * A durable record that a Category C create operation was about to be
 * dispatched, written *before* the operation's own real side effect begins
 * (M5-C; extended to `create_event` in M5-E).
 *
 * ## Why this is a completely separate concern from [ExecutionRecoveryState]
 * [ExecutionRecoveryState] answers "what step is blocked, waiting on the
 * user" — a question about *conversation*. [OperationCheckpoint] answers "did
 * this specific create call actually finish" — a question about *one tool
 * dispatch*, unrelated to whether anything was ever blocked at all. A plan
 * that runs straight through with no clarification, confirmation or
 * ambiguity never touches [ExecutionRecoveryState], yet every one of its
 * create steps still passes through exactly one
 * [OperationCheckpoint] write-then-clear. The two mechanisms are stored
 * under separate keys in the same [com.softwaremine.dps.data.android.secretary.PersistentRecoveryStore]
 * file (see that class's own doc) rather than nested inside one another, so
 * either can evolve without the other's schema changing.
 *
 * ## Scope: `create_task`, `create_reminder`, and `create_event`
 * `create_task`/`create_reminder` already generate a stable, client-side id
 * *before* the destructive write (`TaskRepository.nextId()` /
 * `ReminderStore.nextId()`), which is exactly what [operationId] preserves —
 * the checkpoint never invents a second identity for either.
 *
 * `create_event` has no such id: the Calendar Provider assigns one only
 * *after* a successful insert, so [operationId] carries a fixed, unused
 * sentinel ([UNUSED_OPERATION_ID]) for this operation type instead — there
 * is nothing to preserve.
 *
 * ## Why `create_event` has no reconciliation, unlike a first M5-E draft attempted
 * An earlier version of this milestone also minted a correlation id for
 * `create_event` and asked [com.softwaremine.dps.data.android.calendar.CalendarWriter]
 * to tag the created event with it via
 * [android.provider.CalendarContract.ExtendedProperties], so a leftover
 * checkpoint could be definitively reconciled against the real provider
 * state rather than merely surfaced. That write is a platform-enforced,
 * sync-adapter-only operation — confirmed on a real device
 * (`IllegalArgumentException: Only sync adapters may write using
 * content://com.android.calendar/extendedproperties`), not merely
 * undocumented or unreliable. No ordinary app, including this one, can ever
 * perform it. Reconciliation was therefore removed, not deferred as a
 * convenience or left half-built: `create_event`'s checkpoint behaves
 * exactly like `create_task`'s/`create_reminder`'s own — detect a leftover
 * checkpoint, surface a one-time notice, clear it, never reconcile, never
 * auto-retry.
 *
 * ## What finding one on restart means — and does not mean
 * A leftover, uncleared checkpoint means the operation's outcome is
 * genuinely **unknown** — the write may have landed, may not have, or may
 * have partially landed. It is never treated as permission to retry: nothing
 * in this codebase re-executes a create automatically from a checkpoint. See
 * [ai.secretary.SecretaryOrchestrator]'s own handling for the one-time,
 * informational notice this produces instead.
 */
@Serializable
data class OperationCheckpoint(
    val operationType: OperationType,
    /**
     * The exact id [AndroidTaskTool]/[AndroidReminderTool] already reserved
     * before dispatch — never regenerated. For [OperationType.CREATE_EVENT],
     * this carries [UNUSED_OPERATION_ID] instead. See this class's own doc.
     */
    val operationId: Int,
    /** Enough to name the operation in a user-facing notice, without re-deriving anything from the tool layer. */
    val title: String,
    val requestedAtMillis: Long,
    val schemaVersion: Int = CURRENT_CHECKPOINT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_CHECKPOINT_SCHEMA_VERSION = 1

        /** [operationId]'s value for [OperationType.CREATE_EVENT], which has no natural id to reserve. Never a real id. */
        const val UNUSED_OPERATION_ID = -1
    }
}

/** The three Category C operations M5-C/M5-E checkpoint. */
@Serializable
enum class OperationType {
    CREATE_TASK,
    CREATE_REMINDER,
    CREATE_EVENT,
}
