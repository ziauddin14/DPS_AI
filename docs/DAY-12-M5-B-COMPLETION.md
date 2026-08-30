# Day 12 — M5-B: Persisted, User-Gated Execution Recovery

## Scope

M5-B implements the M5-A-approved direction: persist enough of an
interrupted, user-initiated request to detect it on a fresh process, and
ask the user whether to continue — never executing anything until they
explicitly say yes. M5-C was not started.

---

## 1. Exact files changed

**New:**
- [`domain/secretary/ExecutionRecoveryState.kt`](android/app/src/main/java/com/softwaremine/dps/domain/secretary/ExecutionRecoveryState.kt) — the persisted domain model (`ExecutionRecoveryState`, `PersistedPendingState` sealed interface with `Clarification`/`ContactSelection`/`TypeDisambiguation`/`Confirmation` variants, `PersistedPendingPlan`, `PersistedDisambiguationCandidate`).
- [`data/android/secretary/PersistentRecoveryStore.kt`](android/app/src/main/java/com/softwaremine/dps/data/android/secretary/PersistentRecoveryStore.kt) — the dedicated store, mirroring `PersistentPreferenceStore`'s exact `SharedPreferences`-plus-JSON pattern.
- [`test/.../data/android/secretary/PersistentRecoveryStoreTest.kt`](android/app/src/test/java/com/softwaremine/dps/data/android/secretary/PersistentRecoveryStoreTest.kt) — 11 JVM tests.
- [`androidTest/.../ai/secretary/SecretaryExecutionRecoveryInstrumentedTest.kt`](android/app/src/androidTest/java/com/softwaremine/dps/ai/secretary/SecretaryExecutionRecoveryInstrumentedTest.kt) — 2 genuine two-phase process-death pairs.

**Modified:**
- [`ai/secretary/SecretaryOrchestrator.kt`](android/app/src/main/java/com/softwaremine/dps/ai/secretary/SecretaryOrchestrator.kt) — new constructor parameter, `handle()` split into a thin wrapper (`handleInternal` renamed from the old body) plus the recovery-detection gate, `onPermissionResult()`/`reset()` updated, and a new self-contained "M5-B" section (`resolveRecoveryPrompt`, `restorePendingState`, `syncRecoveryPersistence`, `currentPersistablePendingState`, `recoveryQuestionOutcome`). The pre-existing M2-C plan/clarification/confirmation/contact/type-disambiguation logic is otherwise byte-for-byte unchanged.
- [`di/AiContainer.kt`](android/app/src/main/java/com/softwaremine/dps/di/AiContainer.kt) — one new store wired in, exactly like `persistentMemoryStore`/`persistentPreferenceStore`.
- Six existing test files updated only to pass the new required `SecretaryOrchestrator` constructor parameter (`SecretaryOrchestratorTest.kt`, `SecretaryOrchestratorProductivityTest.kt`, `AiSessionManagerReplyShortcutTest.kt`, `AiSessionManagerInterruptionTest.kt`, `VoiceModeControllerTest.kt`, `ProcessDeathPersistenceInstrumentedTest.kt`, `SecretaryLiveWiringInstrumentedTest.kt`, `SecretaryLiveWiringProductivityInstrumentedTest.kt`, `CalendarClassificationInvestigationTest.kt`, `GgufInferenceInstrumentedTest.kt`) — mechanical, no logic changes, except `ProcessDeathPersistenceInstrumentedTest.kt`, which additionally gained a local `FakeSharedPreferences` fake (see §11).
- `SecretaryOrchestratorTest.kt` also gained 4 new JVM tests for the recovery flow itself.

## 2. Exact files untouched

Every forbidden file — `ToolExecutor.kt`, `DefaultToolExecutor.kt`,
`ToolResult.kt`, `AndroidTaskTool.kt`, `AndroidReminderTool.kt`,
`AndroidCalendarTool.kt`, `CalendarWriter.kt`, `AndroidTaskStore.kt`,
`ReminderStore.kt`, `ConversationMemory.kt`, `ConversationMemoryUpdater.kt`,
`ReferenceResolver.kt`, `DpsApplication.kt`, and every M4 proactive
production file — confirmed byte-identical to the pre-M5-B baseline via
`git diff --stat` (same insertion/deletion counts as the M4-D end state).
No M4 file was found necessary and none was modified.

## 3. Persisted recovery schema

```kotlin
ExecutionRecoveryState(
    pending: PersistedPendingState,   // exactly one of the four below
    plan: PersistedPendingPlan?,      // null when nothing follows the blocked step
    schemaVersion: Int = 1,
)

sealed interface PersistedPendingState {
    val requestedAtMillis: Long
    data class Clarification(intent, question, missing, partial, requestedAtMillis)
    data class ContactSelection(originalIntent, candidates, requestedAtMillis)
    data class TypeDisambiguation(originalIntent, candidates, requestedAtMillis)
    data class Confirmation(intent, requestedAtMillis)
}

PersistedPendingPlan(remainingSteps, remainingOffsets, completedReplies, lastEventStartMillis, requestedAtMillis)
```

Every field is built from types already `@Serializable` for unrelated
reasons (`DpsIntent`, `IntentParameters`, `IntentField`, `Contact`) — no
existing production type needed retrofitting. Stored as one JSON blob in
its own `dps_execution_recovery` `SharedPreferences` file, matching every
other M3/M4 store's convention exactly.

## 4. Which pending states are supported

All four that can coexist with a `PendingPlan` remainder today:
`pendingClarification`, `pendingContactSelection`,
`pendingTypeDisambiguation`, `pendingConfirmation`. Single-step blocks
(no plan remainder) are covered too — `plan` is simply `null` in that case.

## 5. Permission-block recovery: deferred, not included

Per M5-A's own finding, a permission block never parks a `PendingPlan` —
that is an existing, unchanged M2-C scope boundary — and the held action
(`ToolOrchestrator`'s private `pendingPermission`) has no getter exposing
its contents to `SecretaryOrchestrator`. Persisting it would require adding
new surface area to `ToolOrchestrator` beyond what M5-B's brief authorized
touching. Per the brief's own instruction to stop and report rather than
expand scope, this was **not implemented** and is explicitly deferred.

## 6. Exact user-gated resume flow

```
Fresh process constructs SecretaryOrchestrator
  → loadFreshRecoveryOrNull() reads the store; discards silently if stale (>5 min)
  → restoredRecovery set (or null)

handle(anyMessage)                         [1st message of the fresh process]
  → restoredRecovery != null, prompt not yet shown
  → "A previous request was interrupted (...). Would you like to continue it?"
    (Conversational — userMessage itself is never classified or inspected)

handle(answer)                             [2nd message]
  → yes  → restorePendingState() sets exactly one pending* field (+ plan)
         → re-asks the ORIGINAL question via pendingQuestion()
         → still zero tool calls
  → no / unclear → discards restoredRecovery + the persisted record
                 → "Alright, I've dropped that." (no / unclear→also processes
                   the message as the fresh request it is, mirroring the
                   existing resolveConfirmation UNCLEAR philosophy)

handle(realAnswer)                         [3rd message, only on yes]
  → dispatches through the EXACT existing resume paths
    (resolveContactSelection / resolveConfirmation / resolveTypeDisambiguation /
     handleSingleStep's merge) — no second resume implementation
  → on success: syncRecoveryPersistence() clears the store
```

`syncRecoveryPersistence()` runs once, at the end of `handle()` and
`onPermissionResult()`, rather than at each of the dozen individual call
sites that set or clear one of the four pending fields.

## 7. Duplicate-side-effect safety behavior

No automatic retry/replay was implemented for `create_task`,
`create_reminder`, or `create_event` — none was needed. Because nothing
executes until an explicit "yes" *and* the user's own follow-up answer,
and because a completed step's own effect (e.g. a created task) is never
re-parked into the plan remainder in the first place, M5-B cannot
duplicate an already-completed side effect. Verified directly on-device:
step 1 of an interrupted plan (a real `create_task`) survives a genuine
process kill and is never re-executed when step 2 resumes after restart —
its call count stays at exactly 1 throughout. No idempotency key was
added; this remains an explicit limitation (see M5-A's own Level 4
finding) for any *future* milestone that might attempt automatic replay —
M5-B never attempts it.

## 8. JVM test total

**626/626, 0 failures** (611 pre-M5-B baseline + 11 new `PersistentRecoveryStoreTest` + 4 new `SecretaryOrchestratorTest` recovery tests).

## 9. Instrumented test total

- `SecretaryExecutionRecoveryInstrumentedTest`: 4/4, genuine two-phase process death for both pairs (resume-with-no-duplication, and decline-and-discard).
- `ProcessDeathPersistenceInstrumentedTest` (M3-D regression): 2/2, genuine process death, re-verified after the isolation fix (§11).
- `SecretaryLiveWiringInstrumentedTest`: 18/18.
- `SecretaryLiveWiringProductivityInstrumentedTest`: 3/21 → 21/21 (run together with the above in the final pass).
- `ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest` (M4 regression): 20/20, unaffected.

## 10. Genuine process-death result

Both `SecretaryExecutionRecoveryInstrumentedTest` pairs were validated with
real `adb shell am force-stop`, confirmed dead via `pidof` returning
nothing, before running phase 2 in a fresh process:

- **Resume pair**: step 1 (`create_task`) persisted before the kill; after
  restart, the recovery question appeared before any classification; an
  explicit "yes" re-asked the original delete confirmation without
  executing anything; the second "yes" resumed step 2 (`cancel_task`)
  exactly once; step 1's task was never duplicated; the store cleared on
  completion.
- **Decline pair**: a blocked clarification persisted before the kill;
  after restart, the recovery question appeared first; an explicit "no"
  cleared the record without ever executing the interrupted reminder; a
  subsequent, unrelated request (`create_task`) worked normally.

## 11. Known limitations / test-isolation findings

- **A genuine, fixed test-isolation bug was found and fixed during this
  milestone**, not merely worked around: `ProcessDeathPersistenceInstrumentedTest.kt`
  constructs several independent `SecretaryOrchestrator` instances within
  one test method that were never meant to share state (that file's own
  `persistentMemoryStore`/`persistentPreferenceStore` parameters are
  required, forcing an explicit choice, precisely to prevent this). The
  initial M5-B change defaulted the new `persistentRecoveryStore` parameter
  to a real, `Context`-backed store, which — unlike the other two — pointed
  every instance at the same on-disk file, letting one instance's own
  leftover pending state (a real follow-up suggestion) silently intercept a
  *different* instance's next message. Fixed by adding the same isolated
  `FakeSharedPreferences` default this file's sibling test files already
  use for exactly this reason. Confirmed fixed via a clean genuine
  process-death re-run.
- Permission-block recovery is deferred (§5) — a real, reported scope
  boundary, not an oversight.
- The `pendingClarification` freshness asymmetry M5-A flagged was
  addressed **only at the persistence boundary**: a persisted
  `Clarification` record is timestamped at each sync and discarded if
  stale on load, exactly like the other three types. The live in-memory
  `pendingClarification` field's own behavior (no freshness check within
  one process) is deliberately untouched — changing that was a separate,
  out-of-scope refactor per the milestone's own instruction.
- No idempotency key or automatic retry was added anywhere (§7) —
  intentional, per the milestone's explicit duplicate-safety boundary.

## 12. M4 status

**M4 remains frozen and unmodified.** All M4 production files verified
byte-identical to the M4-D baseline. `ProactiveCheckWorkerInstrumentedTest`
and `CalendarWriterInstrumentedTest` (20/20) confirm zero behavioral
regression.

## 13. M5-C status

**Not started.** No autonomous execution, no idempotency logic, no
background recovery, no new UI, and no Level 4 reconciliation were
implemented, per the milestone's explicit forbidden-scope list.

---

M5-B is complete. All required validation — JVM regression, instrumented
regression, and genuine two-phase real process-death testing for both the
resume and decline paths — actually passed. M5-C was not started.
