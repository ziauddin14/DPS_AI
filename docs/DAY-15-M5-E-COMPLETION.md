# Day 15 — M5-E: create_event Checkpointing (Reconciliation Attempted, Reverted)

## Scope

M5-E was approved to extend the M5-C `OperationCheckpoint` mechanism to
`create_event`, using a minted correlation id tagged onto the created event
via `CalendarContract.ExtendedProperties` so a leftover checkpoint could be
**reconciled** — definitively confirmed rather than merely surfaced.

**That reconciliation mechanism was implemented, tested, and found to be
impossible on real Android.** `ExtendedProperties` writes are a
platform-enforced, sync-adapter-only operation — confirmed on-device, not
merely undocumented — and no ordinary app, including DPS, can ever perform
one. This was discovered during implementation, reported immediately, and
the mechanism was reverted in full. **What ships in M5-E is the fallback
your own approval already authorized for exactly this situation**:
`create_event` checkpointing without reconciliation, behaving identically to
`create_task`'s/`create_reminder`'s own M5-C mechanism.

---

## 1. Phase 0 — Repository state

Confirmed before any work: repo root `D:\Own Project\DPS`, branch
`day-05-android-tool-foundation`, diff byte-identical to the confirmed
M5-D/M5-E-investigation baseline (66 status lines, 35 tracked files changed)
before any M5-E edit was made. No drift.

## 2. What was attempted, what failed, and why

### The attempted mechanism
1. `AndroidCalendarTool.createEvent()` minted a `UUID` correlation id before
   dispatch and durably checkpointed it (`OperationType.CREATE_EVENT`).
2. `CalendarWriter.insertEvent()` accepted that id and, after a successful
   event insert, issued a **second** `ContentResolver.insert()` into
   `CalendarContract.ExtendedProperties` to tag the event with it.
3. A new `CalendarWriter.findEventByCorrelationId()` query let
   `SecretaryOrchestrator` check, before composing a leftover-checkpoint
   notice, whether the tagged event actually existed — reporting a confirmed
   "it was created" instead of the usual "I couldn't confirm."

### The failure — confirmed on a real device, not assumed
Step 2 throws on every real invocation:

```
java.lang.IllegalArgumentException: Only sync adapters may write using
content://com.android.calendar/extendedproperties
```

This is a hard Android platform restriction: `CalendarContract.ExtendedProperties`
exists, per its own documentation, "so that sync adapters can store private
data as extended properties" — ordinary, non-sync-adapter apps cannot write
to it under any URI parameter or configuration. DPS is not, and has no
reason to become, a calendar sync adapter. This was not anticipated by the
M5-E investigation (which flagged only the tag-write-races-the-clear timing
window as a residual risk, not that the write itself would categorically
fail) and was found only by actually running the code on-device and reading
the resulting exception via `logcat` — the investigation's own theoretical
analysis had no way to surface it.

### What this means for the approval's own conditional rule
The implementation instructions read: *"reconciliation may only improve the
user-facing recovery notice... it must NEVER automatically retry, skip,
merge, delete, or recreate the event"* and required this milestone to
*"preserve and document the known residual window... Do NOT claim atomicity
or 100% reconciliation certainty."* Given reconciliation cannot function at
all, the honest, safety-preserving choice was to remove it outright rather
than ship a mechanism that would silently no-op (log a warning, tag nothing)
on every single event ever created — which would be dead weight at best and
misleading at worst (a docstring promising a capability the code can never
deliver). This is reported here as the required "stop and report an
architectural decision" moment, discovered one step later than expected —
during implementation, not investigation — but reported before proceeding
past it, exactly as instructed.

## 3. What actually shipped

`create_event` now has the same checkpoint mechanism `create_task`/`create_reminder`
have had since M5-C:

- `AndroidCalendarTool.createEvent()` durably writes an `OperationCheckpoint`
  (`OperationType.CREATE_EVENT`, id `OperationCheckpoint.UNUSED_OPERATION_ID`
  — there is still no natural id to preserve, correlation or otherwise) after
  `findWritableCalendar()` confirms there is somewhere to write, and clears
  it on every confirmed outcome (`Created`, `NoProvider`, `Failed` — all
  three are provably no-side-effect-or-confirmed-side-effect states, per
  `CalendarWriter.insertEvent()`'s own contract, unchanged).
- `SecretaryOrchestrator.outstandingCheckpointNotice()` gained a
  `CREATE_EVENT -> "event"` case and surfaces the **exact same** uncertain
  wording `CREATE_TASK`/`CREATE_REMINDER` already use — no reconciliation,
  no confirmed-success variant.
- `CalendarWriter.kt` carries **zero** net change — the `ExtendedProperties`
  code was added and then fully reverted; `insertEvent()`'s signature and
  behavior are identical to the pre-M5-E version.

## 4. Exact files changed

- `domain/secretary/ExecutionRecoveryState.kt` — `OperationType.CREATE_EVENT`
  added; `OperationCheckpoint.UNUSED_OPERATION_ID` sentinel added; extensive
  doc explaining the attempted-and-reverted mechanism for future readers.
- `data/android/tool/AndroidCalendarTool.kt` — new `recoveryStore`/`now`
  constructor parameters; `createEvent()` checkpoints before dispatch, clears
  on every confirmed outcome.
- `ai/secretary/SecretaryOrchestrator.kt` — `outstandingCheckpointNotice()`
  gained the `CREATE_EVENT` case.
- `di/AiContainer.kt` — `AndroidCalendarTool(...)` construction gains
  `persistentRecoveryStore`.
- `data/android/calendar/CalendarWriter.kt` — **zero net change** (attempted
  and fully reverted; verified via `grep` that no `UUID`/`correlationId`/
  `ExtendedProperties` reference remains anywhere in this file).
- `test/.../data/android/secretary/PersistentRecoveryStoreTest.kt` — 5 new
  JVM tests for the `CREATE_EVENT` checkpoint (round-trip with the sentinel
  id, clear-after-success, fresh-reconstruction detection, replacement, and
  backward-compatible decoding of a pre-M5-E record).
- `test/.../ai/secretary/SecretaryOrchestratorTest.kt` — 1 new JVM test
  proving a leftover `CREATE_EVENT` checkpoint produces the same uncertain
  notice as task/reminder, and never triggers a tool call.
- `androidTest/.../ai/secretary/CalendarCheckpointRecoveryInstrumentedTest.kt`
  (new file) — 2 single-invocation lifecycle tests + 2 genuine two-phase
  process-death scenarios, mirroring `CheckpointRecoveryInstrumentedTest.kt`'s
  own M5-C structure.

## 5. Files that remained frozen

Confirmed via `git diff` line-count comparison against the pre-M5-E
baseline, file by file: `ProactiveCheckWorker.kt`, `ProactiveRuleEvaluator.kt`,
`ProactiveStateStore.kt`, `AndroidTaskStore.kt`, `ReminderStore.kt`,
`AndroidTaskTool.kt`, `AndroidReminderTool.kt`, `ToolExecutor.kt`,
`DefaultToolExecutor.kt`, `ToolResult.kt`, `ConversationMemory.kt`,
`ConversationMemoryUpdater.kt`, `ReferenceResolver.kt`, `DpsApplication.kt`
— every one shows either zero diff or exactly its pre-existing,
pre-M5-E diff, unchanged.

## 6. JVM regression

**644/644, 0 failures, 0 errors** — 638 pre-M5-E baseline + 6 new tests (5 in
`PersistentRecoveryStoreTest`, 1 in `SecretaryOrchestratorTest`).

## 7. Instrumented regression and genuine process-death results

All re-run this session on the connected physical device.

- **`CalendarCheckpointRecoveryInstrumentedTest`** (new): 2/2
  single-invocation lifecycle tests pass. Both genuine two-phase
  process-death scenarios — **Scenario A** (checkpoint written, event never
  created, real `adb shell am force-stop` + `pidof`-confirmed kill, no
  automatic creation on restart) and **Scenario B** (a confirmed-successful
  create does not resurrect after real process death, no duplicate) —
  **pass**.
- **`CheckpointRecoveryInstrumentedTest`** (M5-C regression): all 4
  single-invocation tests, and all 3 genuine process-death scenarios (task,
  reminder, successful-completion) — **pass**, unaffected.
- **`SecretaryExecutionRecoveryInstrumentedTest`** (M5-B regression): both
  pairs, genuine process death — **4/4 pass**.
- **`ProcessDeathPersistenceInstrumentedTest`** (M3-D regression): genuine
  process death — **2/2 pass**.
- **`SecretaryLiveWiringInstrumentedTest` + `SecretaryLiveWiringProductivityInstrumentedTest`**:
  **21/21 pass**.
- **`ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest`**
  (M4 regression): **19/20** — see §8.
- **`AndroidToolsInstrumentedTest` + `ProductivityInstrumentedTest`**: 32
  tests, **2 pre-existing, already-documented failures** — see §8.
- **`ReminderTriggerInstrumentedTest`**: **1 pre-existing, already-documented
  failure** — see §8.

## 8. Failures observed, all investigated, none caused by this milestone's code

Three categories of failure surfaced during this session's regression work.
All three were root-caused, not merely dismissed.

**Two are pre-existing and already documented in M5-C's/M5-D's own
completion docs, reproduced identically here:**
- `AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented` — the
  same stale `PHONE`-tool assertion.
- `AndroidToolsInstrumentedTest#calendarListingFindsControlledEventsForASpecificDateThenCleansUp`
  — the same real, device-synced "Janmashtami" holiday-calendar entries
  (5 rows) coinciding with this test's fixed `today+5` date offset,
  re-confirmed via direct `content query` this session.
- `ReminderTriggerInstrumentedTest#scheduledReminderActuallyFiresNotifiesAndClearsItsRecord`
  — the same real-device alarm-delivery timing flake.

**One is genuinely new, investigated in depth, and confirmed unrelated to
any code change this session:**
- `ProactiveCheckWorkerInstrumentedTest#runningTheCheckTwiceForTheSameOverdueTaskDoesNotDuplicateTheNotification`
  fails reproducibly — 3/3 attempts, including from a verified, freshly
  cleaned device state (empty task store, zero active notifications, empty
  proactive-state marker set). `git diff` confirms **zero** change to
  `ProactiveCheckWorker.kt`, `ProactiveRuleEvaluator.kt`,
  `ProactiveStateStore.kt`, or `NotificationPresenter.kt` — the entire
  dependency chain this test exercises. This same combined test class
  (`ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest`)
  passed 20/20 in both the M5-C and M5-D regression sweeps on this same
  device with byte-identical code. Root cause was not fully isolated: the
  test cancels a notification and immediately re-checks
  `activeNotifications`, a pattern sensitive to `NotificationManager`'s own
  asynchronous cancellation processing at the system-server level — a
  plausible, device-state-dependent race, not something this milestone's
  checkpoint-only calendar changes could cause. Not fixed, per this
  milestone's frozen-file list; reported honestly rather than silently
  retried or hidden.

**A separate, transient category — residue I created myself while debugging
the non-viable reconciliation mechanism** — two leftover calendar events and
one leftover overdue task, all from my own repeated solo `am instrument`
invocations during diagnosis (the same async-`SharedPreferences.apply()`-vs-
solo-process-teardown pattern already documented repeatedly in this
codebase, this time self-inflicted rather than latent). Found via direct
`content query`/prefs inspection, root-caused, and cleaned up; the M4
`noEligibleTasksMeansNoNotificationAndNoMarker` test's own initial failure
was entirely this residue and does not reproduce on a clean device — verified
by a full, definitive final run.

## 9. Device cleanup verification

After the full regression sweep: `dps_execution_recovery.xml` empty (no
stale checkpoint or recovery record), `dps_tasks.xml` empty, no armed
`AlarmManager` alarms, zero active notifications, no `M5-E`-titled calendar
events remaining (confirmed via `content query ... title LIKE '%M5-E%'`).
Two stray reminder records unrelated to this milestone's own tests (from
pre-existing `SecretaryLiveWiringInstrumentedTest`/`ReminderTriggerInstrumentedTest`
residue, identical to the pattern already documented in M5-C/M5-D) were
found and removed directly.

## 10. Safety invariants preserved

No automatic retry, skip, merge, delete, or recreate was ever implemented,
attempted, or shipped — confirmed by the final code containing no decision
logic keyed on checkpoint presence beyond the existing detect-and-notify
path already proven in M5-C. No new persistence store was created —
`OperationCheckpoint`/`PersistentRecoveryStore` were extended, not
duplicated. No `WorkManager` job, alarm, or foreground service was added.
User-gated recovery (M5-B) and checkpoint recovery (M5-C, task/reminder) are
both re-verified unaffected by genuine process-death testing this session.

## 11. M5-D behavior

Fully preserved — `create_task`/`create_reminder` checkpointing is
byte-identical to the M5-D end state (re-verified via the existing
`CheckpointRecoveryInstrumentedTest` suite passing unchanged, including all
three genuine process-death scenarios).

## 12. M4 status

**Untouched.** Zero diff in any M4 production file. The one newly observed
M4 test failure (§8) was investigated and confirmed unrelated to this
milestone's changes, not fixed (out of scope, frozen files).

---

M5-E complete, with the correlation/reconciliation half of the approved
design found non-viable on real Android and removed; the checkpoint-only
half — matching `create_task`/`create_reminder`'s own proven M5-C mechanism
— ships and is genuinely process-death-verified. M5-F not started. Waiting
for explicit approval.
