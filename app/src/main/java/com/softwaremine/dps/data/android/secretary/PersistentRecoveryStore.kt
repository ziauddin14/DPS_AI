package com.softwaremine.dps.data.android.secretary

import android.content.Context
import android.content.SharedPreferences
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.domain.secretary.ExecutionRecoveryState
import com.softwaremine.dps.domain.secretary.OperationCheckpoint
import com.softwaremine.dps.domain.secretary.PendingVerification
import kotlinx.serialization.json.Json

/**
 * Persists [ExecutionRecoveryState] (M5-B).
 *
 * ## Purpose
 * A dedicated store, deliberately separate from every existing one —
 * [com.softwaremine.dps.data.android.memory.PersistentMemoryStore] (recent
 * conversational/entity context), [com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore]
 * (durable user configuration), [com.softwaremine.dps.data.android.productivity.AndroidTaskStore]/
 * [com.softwaremine.dps.data.android.reminder.ReminderStore] (domain records
 * a tool manages) and [com.softwaremine.dps.data.android.proactive.ProactiveStateStore]
 * (M4's own background bookkeeping, frozen). This is none of those: it is a
 * transient-by-nature record of one interrupted user request, expected to be
 * cleared again within minutes in the overwhelming common case — mixing it
 * into any of the above would violate exactly the narrow, single-concern
 * boundary this codebase has kept for every store since M3-A.
 *
 * ## Storage choice
 * The same `SharedPreferences`-plus-one-JSON-blob pattern every M3/M4 store
 * already uses — one small, fixed-shape value (or absent entirely), so no
 * map/id/next-id scheme is needed here either. The constructor takes
 * [SharedPreferences] directly rather than [Context] for the identical
 * JVM-testability reason those stores' own docs explain; [create] preserves
 * the familiar `Context`-taking construction for production use.
 *
 * ## `load()` returns `null`, not an `EMPTY` sentinel
 * Unlike stores that fall back to an `EMPTY` value of their own type
 * ([com.softwaremine.dps.data.android.memory.PersistentMemoryStore],
 * [com.softwaremine.dps.data.android.preferences.PersistentPreferenceStore]),
 * there is no meaningful "nothing pending" *value* of [ExecutionRecoveryState]
 * to construct — either a request was interrupted or it wasn't. `null` says
 * that directly.
 */
class PersistentRecoveryStore(
    private val prefs: SharedPreferences,
    private val logger: DpsLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The persisted recovery record, or `null` when nothing is pending or
     * the stored value is unreadable — corrupt storage must not brick the
     * assistant, the same reasoning every other store in this codebase
     * already follows. A corrupt record is discarded outright (there is
     * nothing sensible to fall back to for "half of an interrupted
     * request"), never surfaced as a recovery prompt.
     */
    fun load(): ExecutionRecoveryState? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching {
            json.decodeFromString(ExecutionRecoveryState.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted execution recovery state unreadable; discarding", it)
            null
        }
    }

    /**
     * Persists [state], replacing whatever was stored before — a single
     * value overwritten in place, never merged, exactly like every other
     * store's own `save`. There is only ever one interrupted request worth
     * remembering at a time (mirrors [PendingPlan][com.softwaremine.dps.domain.secretary.PendingPlan]'s
     * own single-slot, "a newer one replaces an older one" discipline).
     */
    fun save(state: ExecutionRecoveryState) {
        val encoded = json.encodeToString(ExecutionRecoveryState.serializer(), state)
        prefs.edit().putString(KEY_STATE, encoded).apply()
        logger.d(TAG, "Persisted execution recovery state")
    }

    /**
     * Erases the persisted record outright, so a subsequent [load] returns
     * `null` — called once a blocked step resumes to completion, once the
     * user explicitly declines to continue, or once a stale/corrupt record
     * is discarded.
     *
     * ## Why `commit()`, not `apply()` (M5-C — the "window-D" fix)
     * Every other write in this class uses `apply()`, deliberately: losing
     * an *async* `save()` write is safe here (see [save]'s own doc) — the
     * worst case is simply that a genuinely interrupted request fails to
     * surface a recovery prompt next time, which is a missed convenience,
     * not a correctness problem. Losing a `clear()` write is a different
     * category of bug entirely: the M5-C investigation found that a request
     * can complete successfully, call `clear()`, and still leave the old
     * record on disk if the process dies before `apply()`'s async write
     * reaches disk — so a **later** process can find that stale record and
     * offer to "continue" a request that already finished. Answering that
     * resurfaced prompt with "yes" would silently duplicate the completed
     * work. `commit()` blocks the caller until the write is confirmed on
     * disk, closing that window at the one call site where it actually
     * matters, without introducing a new database or a generalized
     * transaction mechanism — the smallest change that makes "cleared"
     * actually mean cleared.
     */
    fun clear() {
        val committed = prefs.edit().remove(KEY_STATE).commit()
        logger.d(TAG, "Cleared persisted execution recovery state (durably, committed=$committed)")
    }

    /**
     * The persisted checkpoint for an in-flight Category C create operation
     * (M5-C), or `null` when none is outstanding or the stored value is
     * unreadable. See [OperationCheckpoint]'s own doc for what finding one
     * means and does not mean.
     */
    fun loadCheckpoint(): OperationCheckpoint? {
        val raw = prefs.getString(KEY_CHECKPOINT, null) ?: return null
        return runCatching {
            json.decodeFromString(OperationCheckpoint.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted operation checkpoint unreadable; discarding", it)
            null
        }
    }

    /**
     * Persists [checkpoint] *before* the create operation's own real side
     * effect begins (M5-C).
     *
     * ## Why `commit()`, not `apply()`
     * The entire point of a checkpoint is to durably record "an attempt is
     * about to happen" before that attempt can possibly succeed. An
     * asynchronous write here would defeat the purpose outright: the
     * process could die between this call returning and the real write
     * landing, with the checkpoint itself never having reached disk —
     * exactly the un-recoverable gap this mechanism exists to close.
     */
    fun saveCheckpoint(checkpoint: OperationCheckpoint) {
        val encoded = json.encodeToString(OperationCheckpoint.serializer(), checkpoint)
        val committed = prefs.edit().putString(KEY_CHECKPOINT, encoded).commit()
        logger.d(
            TAG,
            "Persisted operation checkpoint (durably, committed=$committed) for " +
                "${checkpoint.operationType} id=${checkpoint.operationId}",
        )
    }

    /**
     * Erases the persisted checkpoint outright — called only once the
     * create operation's outcome is *confirmed* (a success, or a failure
     * proven to have no side effect; see [OperationCheckpoint]'s own doc).
     * `commit()` for the identical reason [clear] uses it: a lost async
     * clear here would let a later process treat an already-resolved
     * operation as still ambiguous.
     */
    fun clearCheckpoint() {
        val committed = prefs.edit().remove(KEY_CHECKPOINT).commit()
        logger.d(TAG, "Cleared operation checkpoint (durably, committed=$committed)")
    }

    /**
     * The persisted pending verification (M7) — a tool already confirmed
     * [com.softwaremine.dps.domain.tool.ToolResult.Success] for a create
     * operation, and the resulting real-world state has not yet been
     * independently observed and compared. `null` when none is
     * outstanding or the stored value is unreadable. See
     * [PendingVerification]'s own doc for why this is a third sibling slot
     * in this same class/file rather than a new store.
     */
    fun loadVerification(): PendingVerification? {
        val raw = prefs.getString(KEY_PENDING_VERIFICATION, null) ?: return null
        return runCatching {
            json.decodeFromString(PendingVerification.serializer(), raw)
        }.getOrElse {
            logger.w(TAG, "Persisted pending verification unreadable; discarding", it)
            null
        }
    }

    /**
     * Persists [pending] *before* the observation read begins (M7) —
     * mirrors [saveCheckpoint]'s own reasoning exactly: the entire point is
     * to durably record "an outcome still needs checking" before that
     * check can possibly run, so a process death between this call and the
     * read is itself detectable on restart, not silently lost.
     *
     * `commit()`, not `apply()`, for the identical "window-D" reason
     * [saveCheckpoint] already documents.
     */
    fun saveVerification(pending: PendingVerification) {
        val encoded = json.encodeToString(PendingVerification.serializer(), pending)
        val committed = prefs.edit().putString(KEY_PENDING_VERIFICATION, encoded).commit()
        logger.d(TAG, "Persisted pending verification (durably, committed=$committed)")
    }

    /**
     * Erases the persisted pending verification outright — called once
     * observation and comparison have actually run and produced a
     * [com.softwaremine.dps.domain.secretary.VerificationOutcome], whichever
     * one it was. `commit()` for the same reason [clearCheckpoint] uses it:
     * a lost async clear here would let a later process re-resolve an
     * already-resolved verification, which is harmless in effect (the read
     * is idempotent) but would defeat the point of ever clearing it.
     */
    fun clearVerification() {
        val committed = prefs.edit().remove(KEY_PENDING_VERIFICATION).commit()
        logger.d(TAG, "Cleared pending verification (durably, committed=$committed)")
    }

    companion object {
        private const val TAG = "PersistentRecoveryStore"
        private const val PREFS_NAME = "dps_execution_recovery"
        private const val KEY_STATE = "state"
        private const val KEY_CHECKPOINT = "checkpoint"
        private const val KEY_PENDING_VERIFICATION = "pending_verification"

        /** Real, `Context`-backed construction — matches every other store's call shape. */
        fun create(context: Context, logger: DpsLogger): PersistentRecoveryStore =
            PersistentRecoveryStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), logger)
    }
}
