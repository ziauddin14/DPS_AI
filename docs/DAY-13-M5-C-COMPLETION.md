# Day 13 — M5-C: Window-D Recovery Fix + Pre-Dispatch Checkpointing

## Scope

M5-C implements exactly the two items approved in the M5-C investigation,
and nothing else:

1. **Fix the M5-B window-D recovery flush risk** — `PersistentRecoveryStore.clear()`
   used `SharedPreferences.Editor.apply()`, an asynchronous write. A process
   that died immediately after a successful, fully-resumed request could
   lose that `clear()` before it reached disk, leaving a stale "still
   pending" record that could resurface and re-prompt for an already
   completed operation.
2. **Add safe pre-dispatch checkpointing for `create_task` and
   `create_reminder` only** — a durable, one-time record written
   immediately before the real side effect, so that a process death between
   "about to create" and "confirmed created" is detected on restart and
   surfaced to the user, never auto-retried.

M5-D was **not started**. No idempotency key, no automatic retry, no
`create_event` checkpointing, no Level-4 reconciliation, and no touch to
Calendar/Event code were implemented, per the milestone's explicit
forbidden-scope list.

---

## 1. Exact files changed

**New:**
- [`androidTest/.../ai/secretary/CheckpointRecoveryInstrumentedTest.kt`](android/app/src/androidTest/java/com/softwaremine/dps/ai/secretary/CheckpointRecoveryInstrumentedTest.kt) — 4 single-invocation lifecycle tests + 3 genuine two-phase process-death pairs (6 test methods).

**Modified:**
- [`domain/secretary/ExecutionRecoveryState.kt`](android/app/src/main/java/com/softwaremine/dps/domain/secretary/ExecutionRecoveryState.kt) — appended `OperationCheckpoint` (a separate, independently-clearable record from `ExecutionRecoveryState`) and `OperationType` (`CREATE_TASK`, `CREATE_REMINDER`). No existing declaration touched.
- [`data/android/secretary/PersistentRecoveryStore.kt`](android/app/src/main/java/com/softwaremine/dps/data/android/secretary/PersistentRecoveryStore.kt) — `clear()` changed from `.apply()` to `.commit()` (window-D fix); added `saveCheckpoint()`/`loadCheckpoint()`/`clearCheckpoint()`, all using `.commit()`, under a new `"checkpoint"` key in the same `dps_execution_recovery` file. `save()` is untouched — still `.apply()`, deliberately (losing that write is safe; see §3).
- [`data/android/tool/AndroidTaskTool.kt`](android/app/src/main/java/com/softwaremine/dps/data/android/tool/AndroidTaskTool.kt) — new `recoveryStore` constructor parameter; `create()` now writes a checkpoint (using the id `TaskRepository.nextId()` already reserved) immediately before `repository.save()`, and clears it immediately after.
- [`data/android/tool/AndroidReminderTool.kt`](android/app/src/main/java/com/softwaremine/dps/data/android/tool/AndroidReminderTool.kt) — new `recoveryStore` constructor parameter; `create()` writes a checkpoint (using the id `ReminderStore.nextId()` already reserved) immediately before `scheduler.schedule()`, and clears it on every one of the three confirmed outcomes (`Scheduled`, `Unavailable`, `Failed`).
- [`di/AiContainer.kt`](android/app/src/main/java/com/softwaremine/dps/di/AiContainer.kt) — both tool constructions updated to pass the already-wired `persistentRecoveryStore`.
- [`ai/secretary/SecretaryOrchestrator.kt`](android/app/src/main/java/com/softwaremine/dps/ai/secretary/SecretaryOrchestrator.kt) — new `pendingCheckpoint` field, loaded once from the store at construction; `handle()` checks and consumes it **before** the existing M5-B `restoredRecovery` check; new `outstandingCheckpointNotice()` helper; `reset()` extended with a comment explaining `pendingCheckpoint` is deliberately not cleared there.
- [`test/.../data/android/secretary/PersistentRecoveryStoreTest.kt`](android/app/src/test/java/com/softwaremine/dps/data/android/secretary/PersistentRecoveryStoreTest.kt) — 12 new JVM tests (§6).
- Five ripple-effect test files updated only to pass the new `AndroidTaskTool`/`AndroidReminderTool` constructor parameter (mechanical, no logic changes): `RebootSurvivalDiagnosticTest.kt`, `ReminderTriggerInstrumentedTest.kt`, `AndroidToolsInstrumentedTest.kt` (4 call sites), `ProductivityInstrumentedTest.kt` (3 call sites, one `replace_all` edit).

## 2. Exact files untouched (frozen list)

All M4 proactive production files, `CalendarWriter.kt`, `AndroidCalendarTool.kt`,
`ToolExecutor.kt`, `DefaultToolExecutor.kt`, `ToolResult.kt`,
`AndroidTaskStore.kt`, `ReminderStore.kt`, `ConversationMemory.kt`,
`ConversationMemoryUpdater.kt`, `ReferenceResolver.kt`, `DpsApplication.kt` —
none of these appear in this session's diff. (`CalendarWriter.kt`,
`ReferenceResolver.kt`, `ConversationMemory.kt`, and `DpsApplication.kt` do
carry uncommitted modifications in the working tree, but those predate
M5-C entirely — confirmed already present in `git status` before any M5-C
work began — and were not touched again during this milestone.)

## 3. Window-D fix — why `save()` stays on `.apply()`

Only the writes where a lost update is unsafe were changed to `.commit()`:
`clear()`, `saveCheckpoint()`, `clearCheckpoint()`. `save()` (writing a new
pending-state record) keeps `.apply()` — losing that specific write in a
death-right-after-write race just means recovery detection silently
doesn't happen for that one interrupted request, which is no worse than
M5-B's pre-existing behavior. The asymmetry is deliberate: **the risk is
one-directional** — a lost `clear()`/`clearCheckpoint()` can cause an
already-finished operation to look unfinished (bad: false positive re-prompt
or, for a checkpoint, a false "might not have happened" notice for
something that did happen), while a lost `save()`/`saveCheckpoint()` before
the real work even begins just under-reports risk that never existed
(acceptable: it's the same as if the write raced and lost before this
milestone). `saveCheckpoint()` is `.commit()` too, for the same reason
`clearCheckpoint()` is: the alternative (a lost checkpoint write followed by
process death and then the real, unrecorded create) is exactly the
duplicate-risk scenario this milestone exists to prevent.

## 4. Checkpoint schema

```kotlin
@Serializable
data class OperationCheckpoint(
    val operationType: OperationType,
    val operationId: Int,
    val title: String,
    val requestedAtMillis: Long,
    val schemaVersion: Int = 1,
)

@Serializable
enum class OperationType { CREATE_TASK, CREATE_REMINDER }
```

Stored under a new `"checkpoint"` key in the same `dps_execution_recovery`
`SharedPreferences` file M5-B's own `ExecutionRecoveryState` uses (key
`"state"`) — two orthogonal records, independently saved/loaded/cleared,
confirmed by a dedicated JVM test that clearing one never touches the
other. Unlike `ExecutionRecoveryState`, a checkpoint has **no freshness
window** — there is no safe default for "did this create actually happen,"
so a leftover checkpoint of any age is always surfaced, exactly once.

## 5. `create_task` / `create_reminder` behavior

Both tools now: reserve the id (`nextId()`, already existing behavior) →
durably write a checkpoint carrying that exact id → perform the real side
effect → durably clear the checkpoint. No second id is ever generated. For
`create_reminder`, the checkpoint clears on **all three** `ScheduleOutcome`
branches (`Scheduled`, `Unavailable`, `Failed`) because `ReminderScheduler`'s
own contract guarantees the latter two leave no alarm and no stored record
behind — there is no ambiguity to preserve.

On a fresh process, `SecretaryOrchestrator` loads any leftover checkpoint
at construction and, on the very first `handle()` call — before any
classification, before the M5-B recovery check — returns a plain
conversational notice ("I may have started creating a task/reminder
(\"...\") but couldn't confirm it finished... I haven't repeated it
automatically...") and clears the checkpoint. No automatic retry exists
anywhere in this codebase; the user must explicitly ask again if they still
want it done.

## 6. `create_event` — explicitly deferred

Untouched, as instructed: `CalendarWriter.kt` and `AndroidCalendarTool.kt`
carry zero M5-C changes. The reason is structural, not a scheduling choice:
a calendar event's id is assigned by `ContentResolver.insert()` and is only
known **after** a successful write, so there is no pre-existing stable id
to checkpoint against before dispatch, unlike `create_task`/`create_reminder`
(both already generate their id up front). Checkpointing `create_event`
would require either a separate journal keyed by a client-generated
correlation id, or writing to `CalendarContract.ExtendedProperties` for
provider-side reconciliation — either is a materially different mechanism
than this milestone's "reuse the id already reserved" approach, and both
remain open for a future, separately-approved milestone.

## 7. Ambiguous-outcome behavior (the critical safety rule)

```
CHECKPOINT FOUND → OUTCOME UNKNOWN → DO NOT EXECUTE → SURFACE TO USER
```

Verified directly: a checkpoint left behind by a simulated interruption
(and, separately, by a genuine process kill — §9) never results in an
automatic `create_task`/`create_reminder` call. `SecretaryOrchestrator`'s
`pendingCheckpoint` check runs before the tool layer is ever reached for
that turn; the notice is a `Conversational` outcome, never a `Clarify`
with a yes/no gate, because there is no safe automatic action to gate.

## 8. JVM test total

**638/638, 0 failures** (626 pre-M5-C baseline + 12 new
`PersistentRecoveryStoreTest` tests: 1 window-D reconstruction test + 11
`OperationCheckpoint` tests covering serialization, schema-default
survival, corrupt/wrong-shape recovery, save-then-load round-trips for
both `CREATE_TASK` and `CREATE_REMINDER`, clear-after-success, a fresh
instance detecting an uncleared checkpoint, a fresh instance finding none
after a durable clear, second-save replacing the first, and independence
from the `state` key).

## 9. Instrumented test results

- **`CheckpointRecoveryInstrumentedTest`** (new, this milestone): 4/4
  single-invocation lifecycle tests, and all 3 real two-phase
  process-death pairs (6 methods) — see §10 below.
- **`SecretaryExecutionRecoveryInstrumentedTest`** (M5-B regression): both
  pairs re-verified with **genuine** `adb shell am force-stop` between
  phases (run individually, not as a whole-class shortcut — see the note
  below) — 4/4, unaffected by the new checkpoint precedence check.
- **`ProcessDeathPersistenceInstrumentedTest`** (M3-D regression): 2/2,
  genuine process death, unaffected.
- **`SecretaryLiveWiringInstrumentedTest` + `SecretaryLiveWiringProductivityInstrumentedTest`**: 21/21.
- **`ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest`** (M4 regression): 20/20.
- **`ProductivityInstrumentedTest`**: all green (part of a 32-test combined run with `AndroidToolsInstrumentedTest`; see below for the one failure).

Note on running `SecretaryExecutionRecoveryInstrumentedTest` as a whole
class in one `am instrument` invocation: doing so once produced 2
failures, both `NoSuchElementException`/"record must survive" — this is
exactly the *pre-existing, documented* caveat in that file's own class doc
("still executes both methods as a compile/logic smoke check, but without
a real process death between them"), caused by JUnit not guaranteeing
phase1 runs before its paired phase2 within one invocation. Re-run as the
prescribed genuine two-phase pairs (separate `am instrument` calls with a
real `am force-stop` and a `pidof`-confirmed kill between them), both pairs
passed cleanly. Not a regression.

## 10. Genuine process-death results (the required A/B/C scenarios)

All three run with a real `adb shell am force-stop` between phases,
confirmed dead via `pidof` returning nothing before phase 2 started.

- **Scenario A (`create_task`)**: phase 1 reserves a real id via
  `AndroidTaskStore.nextId()` and writes a checkpoint for it — reproducing,
  via the same production `saveCheckpoint()` API the tool itself calls,
  exactly the on-disk state an interruption between checkpoint-write and
  `repository.save()` would leave (see the honesty note in §11). After a
  genuine kill, phase 2 confirmed: the checkpoint survived; a fresh
  `SecretaryOrchestrator`'s first message surfaced the notice naming the
  task; **no task with that title was ever created**; the checkpoint was
  cleared after being surfaced once.
- **Scenario B (`create_reminder`)**: identical shape, using
  `ReminderStore.nextId()`. After a genuine kill: checkpoint survived, no
  reminder record was ever created, checkpoint cleared after surfacing.
- **Scenario C (successful completion must not resurrect)**: phase 1 runs
  the **real, unmodified** `AndroidTaskTool.create()` to genuine, confirmed
  completion (not a simulated intermediate state). After a genuine kill,
  phase 2 confirmed: no checkpoint and no M5-B execution-recovery record
  resurfaced; a fresh `SecretaryOrchestrator`'s first message behaved as an
  ordinary greeting, not a recovery/checkpoint notice; the task created in
  phase 1 existed **exactly once** — no duplicate from the restart. This is
  the concrete proof for the window-D fix itself, not just the checkpoint
  mechanism.

## 11. Honesty note: how "interrupted before the real write" is reproduced

`AndroidTaskTool.create()` / `AndroidReminderTool.create()` run their
checkpoint-write-then-real-write sequence synchronously, on one thread, in
one function call — there is no window in which an external `adb` command
can land strictly *between* the checkpoint write and the real side effect.
Scenarios A and B therefore reproduce that exact on-disk state directly,
using the same production `PersistentRecoveryStore.saveCheckpoint()` API
the tools themselves call, with an id genuinely reserved via the real
`AndroidTaskStore.nextId()`/`ReminderStore.nextId()` (which — like the
tools' own code — is never called a second time for the same operation, so
the id is permanently skipped exactly as a real interruption would leave
it). What **is** genuine in all three scenarios is the process boundary
itself: a real `adb shell am force-stop`, confirmed via `pidof`, not an
in-process simulation of one. Scenario C uses no simulation at all — it
runs the real tool to real completion.

## 12. A test-authoring bug found and fixed during this milestone

The first run of the new `CheckpointRecoveryInstrumentedTest` left one
stray task behind on the test device (`"M5-C scenario C completed
create_task"`, from Scenario C's phase 2). Cause: `AndroidTaskStore.delete()`
writes via `SharedPreferences.Editor.apply()` (asynchronous), and
`ActivityManager` force-stops the instrumentation process immediately after
a solo `am instrument` invocation's single test method returns — there was
no time for that specific delete to flush before the process died. This is
the *same* class of race the window-D fix addresses in production code,
just showing up in test cleanup instead. Fixed by adding `delay(400)` after
store mutations in every `finally` block, matching the pattern
`SecretaryExecutionRecoveryInstrumentedTest.kt` already established for
exactly this reason. Re-ran the full suite after the fix — clean.

## 13. Device cleanup verification

After the fix, inspected on-device state directly (`run-as` + `cat` on the
raw `SharedPreferences` XML files, plus `dumpsys alarm` and `dumpsys
notification`):

- `dps_execution_recovery.xml`: empty (`<map />`) — no stale checkpoint, no
  stale M5-B recovery record.
- `dps_tasks.xml`: `tasks` empty — no leftover test task.
- No armed `AlarmManager` alarms for the app (checked the active batch
  list, not just history).
- No active notifications for the package.
- Two stray reminder records unrelated to this milestone's own test
  titles (`"M3-D precedence case B"`, `"call the bank"`) were found during
  cleanup verification, left over from regression runs of pre-existing
  suites during this same session; removed directly. Neither was created
  by any M5-C code path or test.

## 14. Known limitations / pre-existing, unrelated failures observed

Two instrumented failures were observed during regression runs. Both were
investigated and confirmed **pre-existing and unrelated to M5-C** — neither
is fixed here, per the milestone's narrow-scope mandate:

- **`AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented`**
  fails asserting `ToolId.PHONE` must not be implemented. `PHONE` was
  actually implemented as `AndroidCallTool` in a separate, already-committed
  milestone (`6148d7a`, "open the dialer for a grounded contact with
  explicit confirmation") that post-dates this test's own last update
  (`03c3359`). Confirmed via `git log`/`git diff`: zero M5-C changes touch
  either file. Stale test assertion, not a regression.
- **`ReminderTriggerInstrumentedTest#scheduledReminderActuallyFiresNotifiesAndClearsItsRecord`**
  fails waiting for a real `AlarmManager`-delivered notification within its
  30-second timeout. Reproduced twice, consistently. Confirmed via logcat:
  the alarm was scheduled correctly (`exact=true`, correct trigger time,
  `SCHEDULE_EXACT_ALARM` granted, screen awake) but never fired within the
  window on this specific physical device. `git diff` confirms zero changes
  to `ReminderScheduler.kt`, `ReminderReceiver`, or any alarm-delivery code
  this session — M5-C's checkpoint write/clear happens entirely before
  `scheduler.schedule()` is even called and cannot affect whether the OS
  later delivers the alarm. This reads as device-level alarm-delivery
  behavior (likely OEM power management on this test device), consistent
  with this same test file's own doc describing real Doze/background
  restriction variability as an established, previously-investigated risk
  class for this exact alarm path. Reported honestly rather than treated
  as passing or silently retried.
- `RebootSurvivalDiagnosticTest` (a documented **throwaway** diagnostic,
  not permanent regression coverage) was not run — it requires a real
  `adb reboot` of the physical test device, which was judged unnecessary
  disruption for a milestone that never touches reboot-survival code.

## 15. M5-B regression result

**Fully green**, including genuine two-phase real process-death re-verification
of both existing pairs (§9). The new checkpoint precedence check in
`handle()` (checkpoint before `restoredRecovery`) does not interfere with
either the resume or decline path.

## 16. M4 regression result

**Fully green**, 20/20, unaffected — no M4 file appears in this session's
diff.

## 17. M5-D status

**Not started.** No idempotency key, no automatic retry mechanism, no
`create_event` checkpointing or reconciliation, no Level-4 work, and no
redesign of the execution engine were implemented, per the milestone's
explicit forbidden-scope list.

---

M5-C complete. M5-D not started. Waiting for explicit approval.
