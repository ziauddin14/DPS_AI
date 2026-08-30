# Day 14 — M5-D: Ambiguous-Outcome Recovery (Verification + create_event Deferral)

## Scope

M5-D was approved to implement "the smallest safe solution for ambiguous-outcome
recovery," per ten explicit requirements. This document reports what was found,
what was (and was not) implemented, and why.

**Headline finding: items 1–4 of the approved scope were already fully
implemented, verified, and shipped in M5-C.** No new production code was
required or written for them. Item 5 (`create_event`) was investigated and
explicitly deferred, per the milestone's own conditional rule, because it
cannot be done without modifying files frozen since M5-C. **This session
therefore made zero changes to any production or test file** — it re-verified
existing behavior and formally recorded the `create_event` deferral decision.

---

## 1. Phase 0 — Repository state

Confirmed before any other work: repo root `D:\Own Project\DPS`, branch
`day-05-android-tool-foundation`, `git status`/`git diff --stat` byte-identical
to the confirmed M5-C end state (35 files, 3432 insertions / 128 deletions,
unchanged). **No drift detected.**

## 2. Item-by-item disposition

| # | Requirement | Disposition |
|---|---|---|
| 1 | Persist an operation checkpoint before Category-C create operations | **Already implemented (M5-C)** — `OperationCheckpoint`/`PersistentRecoveryStore.saveCheckpoint()`, called immediately before the real write in `AndroidTaskTool.create()`/`AndroidReminderTool.create()`. |
| 2 | Record/clear the checkpoint only after a confirmed outcome | **Already implemented (M5-C)** — `clearCheckpoint()` runs only after `repository.save()` (task) or on every one of the three confirmed `ScheduleOutcome` branches (reminder). Both use `.commit()` (synchronous), not `.apply()` — the M5-C window-D fix. |
| 3 | Recover safely after process death without blindly retrying a potentially completed create | **Already implemented (M5-C)** — `SecretaryOrchestrator.pendingCheckpoint`, checked before anything else in `handle()`, surfaces a one-time informational notice and clears the checkpoint; never calls a tool. |
| 4 | Cover `create_task` and `create_reminder` first, using their existing client-side IDs | **Already implemented (M5-C)** — both reuse `TaskRepository.nextId()`/`ReminderStore.nextId()`'s already-reserved id as the checkpoint's `operationId`; no second identity is ever generated. |
| 5 | `create_event` correlation/reconciliation — only if safe without touching frozen files, otherwise defer | **Investigated and explicitly deferred.** See §3. |
| 6 | Never introduce blind automatic retries | **Preserved** — nothing in this codebase retries a create automatically, before or after this session; no code changed. |
| 7 | Preserve all existing M1–M5-C behavior | **Verified**, not merely assumed — full JVM and instrumented regression re-run this session (§4). |
| 8 | Do not modify M4 proactive behavior | **Preserved** — zero M4 files touched; `ProactiveCheckWorkerInstrumentedTest`/`CalendarWriterInstrumentedTest` re-run clean (20/20). |
| 9 | Add JVM tests and genuine real-device/process-death tests | **No new tests added** — since no new production code path exists to test, there is nothing new to cover. Instead, the existing M5-C JVM suite and the existing genuine two-phase process-death scenarios (A/B/C) were re-run fresh, on-device, this session, to produce a current, honest confirmation rather than relying on a prior session's results. See §4–5. |
| 10 | Run the full regression suite before completion | **Done** — JVM (638/638) and the full instrumented regression sweep (§4). |

## 3. The `create_event` deferral decision

Per the M5-D investigation (Phase 5/9/11 of that report), a checkpoint +
reconciliation mechanism for `create_event` requires:

- Minting a synthetic, DPS-generated correlation id before dispatch (no
  client-side id exists for a calendar event — the provider assigns one only
  on a successful `ContentResolver.insert()`).
- Writing that id into the created event's own `CalendarContract.ExtendedProperties`
  inside `CalendarWriter.insertEvent()`, so a future reconciliation pass has
  something to query against.
- Adding the checkpoint write/clear call sites inside `AndroidCalendarTool.createEvent()`.

Both `CalendarWriter.kt` and `AndroidCalendarTool.kt` were explicitly frozen
for M5-C ("Do NOT touch Calendar/Event reconciliation in this milestone") and
the M5-D investigation's own Phase 11 explicitly recommended **not** lifting
that freeze without a separate approval. Since the mechanism cannot be built
without modifying both files, and this milestone's own instruction reads
*"only implement... if it can be done safely without violating the
frozen-file boundaries. Otherwise explicitly defer it,"* **it is deferred in
full** — not partially implemented, and no alternate, unvalidated design (e.g.
a notify-only checkpoint at the `SecretaryOrchestrator` layer without
reconciliation, which would stay within already-editable files but was never
investigated or safety-checked) was substituted in its place. Inventing such
a variant mid-implementation would itself have been an unapproved
architectural expansion; per this milestone's own instruction ("if an
architectural decision is necessary, stop and report it before implementing
it"), it is recorded here as a **possible future option for a separately
approved milestone**, not implemented now:

> A `SecretaryOrchestrator`-level checkpoint for `create_event` — writing a
> title-only `OperationCheckpoint` (with a synthetic, non-reconciling id)
> immediately around the `toolOrchestrator.executeIntent()` call for a
> `CALENDAR_EVENT` `CREATE` intent — would deliver the same one-time,
> never-auto-retried user notice M5-C already provides for task/reminder,
> without touching `CalendarWriter.kt`/`AndroidCalendarTool.kt` at all. It
> would **not** provide the correlation/reconciliation capability (no way to
> definitively confirm the event exists), only the safety notice. This was
> not implemented because it was not part of the approved M5-D investigation
> and represents a new design choice, not a restatement of an already-vetted
> one.

**No production code was written for `create_event` in this session.**

## 4. JVM regression (fresh run, this session)

`./gradlew.bat :app:testDebugUnitTest --rerun` — **638/638, 0 failures, 0
errors.** Identical to the M5-C end-state total; no source file changed, so
no count movement was expected or observed.

## 5. Instrumented regression (fresh run, this session, on-device)

All re-run this session on the connected physical device, after installing
the current (unchanged) debug + test APKs:

- **`CheckpointRecoveryInstrumentedTest`** — all 4 single-invocation lifecycle
  tests: pass. All 3 genuine two-phase process-death scenarios (A: `create_task`,
  B: `create_reminder`, C: successful completion does not resurrect), each
  with a real `adb shell am force-stop` + `pidof`-confirmed kill between
  phases: **pass**, identical outcome to M5-C's own verification.
- **`SecretaryExecutionRecoveryInstrumentedTest`** (M5-B regression) — both
  pairs, genuine process death: **4/4 pass.**
- **`ProcessDeathPersistenceInstrumentedTest`** (M3-D regression) — genuine
  process death: **2/2 pass.**
- **`ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest`**
  (M4 regression): **20/20 pass** — confirms `AndroidCalendarTool.kt`/
  `CalendarWriter.kt` remain fully functional and untouched.
- **`SecretaryLiveWiringInstrumentedTest` + `SecretaryLiveWiringProductivityInstrumentedTest`**:
  **21/21 pass.**
- **`AndroidToolsInstrumentedTest` + `ProductivityInstrumentedTest`**: 32
  tests, **2 failures, both pre-existing and unrelated** — see §6.
- **`ReminderTriggerInstrumentedTest`**: **1 failure, pre-existing and
  unrelated** — see §6 (already documented identically in the M5-C
  completion doc).

## 6. Pre-existing, unrelated failures observed (not caused by this session — zero code changed)

Three failures were observed during the regression sweep. All three were
investigated and confirmed unrelated to M5-D (and, for two of them, already
documented as pre-existing in the M5-C completion doc):

- **`AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented`** — the
  same stale `PHONE` assertion already documented in M5-C (§14 of that doc).
  Unchanged, unrelated.
- **`AndroidToolsInstrumentedTest#calendarListingFindsControlledEventsForASpecificDateThenCleansUp`**
  — new observation this session: failed expecting 2 events on its computed
  test date (`today + 5 days`) but found 7. Investigated directly via
  `adb shell content query` against `content://com.android.calendar/events`:
  the extra 5 rows are all titled **"Janmashtami"** (a real, synced holiday
  calendar entry — 5 duplicate rows, likely from multiple sync sources on
  this physical device), which happened to fall on the exact date this test
  computes today. The test's own `finally` block correctly deleted both of
  its own created events (confirmed: no `"DPS Phase 5 list test"` titled rows
  remain). This is a genuine but date-coincidental pre-existing test
  fragility — `list_events` queries every calendar visible to the device, and
  this test's fixed relative-day offset has no way to exclude real,
  independently-synced calendar content. Zero code was changed this session
  that could affect this; not fixed, as it is unrelated to M5-D's scope.
- **`ReminderTriggerInstrumentedTest#scheduledReminderActuallyFiresNotifiesAndClearsItsRecord`**
  — the same real-device alarm-delivery flake already documented in M5-C
  (§14 of that doc): the alarm was scheduled correctly but never fired within
  the 30-second window on this device. Unchanged, unrelated.

## 7. Device cleanup verification

After the full regression sweep, on-device state was inspected directly:

- `dps_execution_recovery.xml`: empty (`<map />`) — no stale checkpoint or
  recovery record.
- `dps_tasks.xml`: empty.
- `dps_reminders.xml`: found two stray records left over from **pre-existing,
  unrelated test files this session did not modify** —
  `SecretaryLiveWiringInstrumentedTest`'s own `"call the bank"` reminder
  fixture, and `ReminderTriggerInstrumentedTest`'s own reminder (never
  cleared because the alarm never fired, so that test's "fired" cleanup path
  never ran, and its `finally` block's `store.remove()` — an async `.apply()`
  write — did not flush before the process was force-stopped). Both are the
  same "async-write-before-process-death" class of residue already
  documented in M5-C, just surfacing in two established test files this
  session happened to re-run, not in anything new. Removed directly (data
  hygiene only; no test/production code touched).
- No armed `AlarmManager` alarms remained for the app afterward.
- No active notifications for the package.

## 8. Final git audit

`git status --short` and `git diff --stat` after all work: **byte-identical**
to the Phase 0 snapshot taken before any verification began. Explicitly
re-checked: `AndroidTaskTool.kt`, `AndroidReminderTool.kt`,
`SecretaryOrchestrator.kt`, `PersistentRecoveryStore.kt` carry only their
pre-existing M5-C diff, unchanged. `AndroidCalendarTool.kt` shows zero diff
(untouched, matching its committed state). `CalendarWriter.kt` carries only
its pre-existing, pre-M5-C diff (from earlier list/find-events work),
unchanged by this session. Nothing is staged.

## 9. M1–M5-C behavior

**Fully preserved** — trivially, since no code was modified, and confirmed
non-trivially by a fresh, full regression run rather than assumed.

## 10. M4 status

**Untouched.** Zero M4 files appear in this session's diff;
`ProactiveCheckWorkerInstrumentedTest`/`CalendarWriterInstrumentedTest`
confirm 20/20 with no behavioral change.

## 11. Automatic retry

**None exists, none was added.** Every ambiguous outcome this milestone's
scope covers (task/reminder checkpoints) continues to resolve to a one-time,
user-facing notice — never an automatic tool call.

---

M5-D complete. Items 1–4 confirmed already implemented and freshly
re-verified (JVM + genuine on-device process-death testing). Item 5
(`create_event`) explicitly deferred — requires modifying frozen files; a
possible narrower alternative (notify-only, no reconciliation) is recorded
in §3 for a future, separately-approved milestone to consider, not
implemented here. M5-E not started. Waiting for explicit approval.
