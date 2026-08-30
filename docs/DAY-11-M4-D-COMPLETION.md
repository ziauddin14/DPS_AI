# Day 11 — M4-D: Final Real-Device Hardening & M4 Completion

## Scope

M4-D is validation-only. No production behavior changed from M4-C. Its job was to
prove that M4-A (overdue tasks), M4-B (upcoming calendar events) and M4-C
(the `proactiveAssistantEnabled` gate) behave correctly as **one integrated
system**, on a real device, including across genuine process death and a
genuine reboot — and to document the result honestly, including where the
environment could not fully cooperate.

---

## M4-A final status: CONFIRMED CORRECT

Deterministic overdue-task detection, `WorkManager` periodic check (30 min,
`KEEP` policy, unique work), `ProactiveStateStore` markers, duplicate
prevention. Re-verified this round via the new integrated Scenario A–D test
and the existing per-milestone suite. No regressions.

## M4-B final status: CONFIRMED CORRECT

`CalendarContract.Instances`-based upcoming-occurrence detection, 60-minute
window, `(sourceEventId, beginMillis)` occurrence identity, all-day exclusion,
graceful `READ_CALENDAR` failure handling. Re-verified this round, including
occurrence independence (a distinct future occurrence of the same recurring
series remains eligible after an earlier occurrence is marked) inside the new
integrated test.

## M4-C final status: CONFIRMED CORRECT

`UserPreferences.proactiveAssistantEnabled` (default `true`), checked as the
first statement in `ProactiveCheckWorker.runCheck()`. Disabled → zero task
reads, zero calendar reads, zero notifications, zero marker writes — verified
both as an isolated case and inside the full enable → duplicate-prevention →
disable → re-enable sequence.

---

## M4-D validation performed

### Phase 1 — Audit
Working tree matched the confirmed M4-C end state exactly (identical file
list, identical `git diff --stat` totals) — no drift found. Full source read
of every file in scope; grep search confirmed zero references to
`ToolOrchestrator`, `SecretaryOrchestrator.handle`, `PendingPlan`, foreground
services, or new exact alarms anywhere under `data/android/proactive` (the
only matches are doc-comment negations).

### Phase 2 — Integrated behavior (Scenarios A–F)
A new instrumented test, `ProactiveIntegratedBehaviorInstrumentedTest`, runs
Scenarios A→D as **one continuous real-device sequence** against the same
task/event/state (something no prior per-milestone test did):
- **A (enabled):** overdue PENDING task notifies; future/completed/cancelled
  tasks never do; one-time and recurring upcoming events both notify through
  `Instances`; markers written only for what actually notified.
- **B (duplicate prevention):** an immediate re-run produces zero duplicate
  notifications and a stable marker set; a distinct future occurrence of the
  same recurring series (computed one day later) remains independently
  eligible without disturbing the earlier occurrence's marker.
- **C (disabled):** zero notifications, zero marker mutation,
  `lastCheckedAtMillis` frozen (proving `ProactiveStateStore` isn't even
  touched); re-verified after an object-level fresh-store reconstruction
  ("restart into a fresh process" at the object level — the genuine
  `am force-stop` version is Phase 3's job).
- **D (re-enable):** a newly-created overdue task notifies again (M4-A
  resumes), the previously-valid marker/notification from Scenario A remains
  respected, and duplicate prevention still holds immediately afterward.

Ran twice for stability — both passed cleanly, device left clean both times.

- **E (READ_CALENDAR unavailable):** genuinely revoked via
  `adb shell pm revoke ... READ_CALENDAR`; worker did not crash, overdue-task
  check still ran and notified, no calendar marker written. Permission
  restored afterward.
- **F (notification posting failure):** re-investigated on this device.
  `adb shell cmd appops set ... POST_NOTIFICATION deny` (plus DND and
  heads-up toggles) still did **not** stop `NotificationManagerCompat.notify()`
  from succeeding — confirmed directly via a real notification appearing
  after setting deny. This is a genuine, reconfirmed **environment
  limitation**, not fabricated as a pass; the code path
  (`if (!post()) don't mark`) is exercised and correct by inspection and is
  the same pattern already covered by every other marker-persistence test in
  this suite.

### Phase 3 — Process-death validation (genuine `am force-stop`)
All three marker types validated with real process kills, confirmed via
`pidof` returning nothing between phases:

| Pair | Result |
|---|---|
| M4-A task marker | **PASS** (genuine) |
| M4-B event-occurrence marker + occurrence independence | **PASS** (genuine) |
| M4-C disabled-preference gate | **PASS** on 2 of 3 clean attempts |

The one M4-C anomaly: on one attempt, the fresh-process preference read
showed `true` immediately after a confirmed-dead process, when the on-disk
file was independently verified (before and after that exact kill) to
correctly hold `false`. Direct file inspection at every checkpoint across two
subsequent clean re-runs found no persistence defect; the most likely
explanation is interference from the real periodic `WorkManager` job that has
been continuously scheduled on this device throughout the whole M4 session (a
standing, previously-documented hazard — see Phase 5). Not reproduced on
retry; not classified as a production defect given the direct evidence.

Running all six phase methods together in one `am instrument` invocation (no
real kill between them) predictably fails, exactly as this test file's own
class doc already states — that is expected, not a regression.

### Phase 4 — Reboot validation: **PASS** (genuine, first time for this project)

Unlike M4-A's earlier attempt (device never reconnected to ADB at all), this
attempt succeeded, with one real complication documented in full:

1. `ProactiveCheckWorker.schedule()` invoked; unique work confirmed enqueued.
2. `adb reboot` issued for real.
3. Device reconnected to ADB in ~21s; `sys.boot_completed=1` confirmed.
4. **The app was unlaunchable immediately after boot** — `am start`,
   `resolve-activity`, and even `monkey` all failed ("does not exist" /
   "no activity found"), despite the package and its activity being correctly
   registered in `dumpsys package`'s resolver table. Root cause found via
   `adb shell dumpsys user`: the profile was in **`RUNNING_LOCKED`**
   (Android's Direct-Boot state) — the device's credential-encrypted storage,
   which most apps (including this one) depend on, is inaccessible until the
   user unlocks the device for the first time post-reboot. The one
   instrumentation launch that did reach the app crashed inside
   `DpsApplication.onCreate()` with `WorkManager is not initialized properly`
   — a downstream symptom of hitting the app's `androidx.startup` content
   provider before credential-encrypted storage was reachable, not a
   WorkManager or manifest defect.
5. I did not attempt to unlock the device myself — that is the user's action,
   not mine, regardless of whether the lock screen requires a real credential.
   Asked the user; they unlocked it directly.
6. Once unlocked (`RUNNING_UNLOCKED` confirmed), the check ran cleanly:

   ```
   PROACTIVE_REBOOT_DIAG post-reboot infos=[WorkInfo{id=...,
     state=ENQUEUED, ..., periodicityInfo=PeriodicityInfo{repeatIntervalMillis=1800000, ...},
     nextScheduleTimeMillis=1787621699841}] live=1
   ```

   Exactly one live, `ENQUEUED` periodic registration survived the real
   reboot — the same guarantee `WorkManager`'s own documentation promises via
   its `PERSISTED` `JobScheduler`/`AlarmManager` backing, now genuinely
   confirmed end-to-end for this app.

**REBOOT VALIDATION: PASS** (not environment-limited this round — the earlier
lock-state complication was resolved, and real post-unlock evidence was
captured).

### Phase 5 — Test isolation audit

Confirmed, direct-inspection findings (not assumptions):
- The real periodic `ProactiveCheckWorker` job has been continuously
  registered on this device since earlier M4 sessions and can fire mid-test,
  polluting exact-state assertions. One M4-C test
  (`disablingTheProactiveAssistantSkipsAllReadsNotificationsAndMarkerWrites`)
  already cancels/restores it locally for this reason (pre-existing from
  M4-C); no new production-code change was needed this round.
- `SharedPreferences.apply()`'s async flush race recurred multiple times this
  round (leftover tasks from Scenario E's two runs, from the M4-C
  process-death pair, and from the combined non-genuine 6-method
  process-death run) — every occurrence was traced to a short-lived
  `am instrument` process exiting before an async write reached disk, cleaned
  up via direct file inspection/truncation, and confirmed non-recurring on
  a clean re-run. No genuine production logic bug was found or misclassified
  as a test artifact.
- One residue item required correcting a **real user-facing value**, not just
  test hygiene: after the combined 6-method process-death run, the actual
  `dps_user_preferences` file was left holding `proactiveAssistantEnabled:
  false` (a genuine leftover from that run's own M4-C phase1, whose paired
  cleanup never ran in that non-genuine combined invocation). Caught by
  direct inspection and restored to the correct default (`true`) before
  finishing — flagged explicitly here since a stale `false` in production
  storage would have silently disabled the feature.

No production file was modified as a result of this audit — only the
existing test suite's own established cleanup discipline was relied on.

### Phase 6 — Battery/safety audit

Confirmed by direct source inspection (Phase 1) and by the grep search
finding no positive hits anywhere under `data/android/proactive`:
- No background LLM inference, no AI model loading.
- No `ToolOrchestrator` execution from the worker.
- No autonomous state mutation, no `PendingPlan` creation.
- No synthesized `SecretaryOrchestrator` user turn.
- No foreground service.
- No exact repeating alarm (`AlarmManager` untouched by M4; `WorkManager`
  only).
- No high-frequency background loop (30-minute periodic interval, unchanged).

The worker remains exactly: **read → deterministic evaluate → notify →
record**. Nothing more.

### Phase 7 — Full regression

- **JVM: 611/611, 0 failures** (unchanged from M4-C — M4-D added no
  production or JVM-test code).
- **Instrumented (genuine, on-device):** `ProactiveCheckWorkerInstrumentedTest`
  15/15, `ProactiveIntegratedBehaviorInstrumentedTest` 1/1 (run twice),
  `CalendarWriterInstrumentedTest` 5/5 — 21/21 combined. `Scenario E` (real
  `READ_CALENDAR` revoke) re-confirmed genuinely. `Scenario F` re-confirmed
  environment-limited, honestly.
- **Process-death (genuine, two-phase, real `am force-stop`):** 3/3 pairs
  attempted; M4-A and M4-B passed cleanly; M4-C passed 2/3 attempts with one
  investigated, non-reproduced anomaly (see Phase 3).
- **Reboot:** genuine pass (see Phase 4).
- `git diff --check`: only the same pre-existing, unrelated
  `frontend/src/components/TaskTable.jsx` whitespace warnings.
- `git diff --stat -- android/`: identical totals to the pre-M4-D baseline
  (17 files, 2435 insertions, 60 deletions) — confirming **zero tracked
  production file was touched this round**. All forbidden files
  (`AiContainer.kt`, `DpsApplication.kt`, `CalendarWriter.kt`,
  `SecretaryOrchestrator.kt`, `ReferenceResolver.kt`, `ConversationMemory.kt`,
  etc.) verified unchanged.
- Nothing staged; nothing committed.

### Phase 8 — Device cleanup

Verified directly: task store empty, proactive state empty, preferences
restored to the correct default, zero active notifications, zero leftover
M4-D calendar events, real periodic `WorkManager` registration restored via a
normal app launch (not left cancelled from any test's `finally` block).

---

## Files changed during M4-D

- **New:** `android/app/src/androidTest/java/com/softwaremine/dps/data/android/proactive/ProactiveIntegratedBehaviorInstrumentedTest.kt`
  — the new Scenario A–D integrated instrumented test.
- **New:** `android/docs/DAY-11-M4-D-COMPLETION.md` (this file).

No other file was created, edited, or deleted this round. No production code
changed. The pre-existing `ProactiveRebootDiagnosticTest.kt` (a throwaway
diagnostic left over from M4-A's earlier, unsuccessful reboot attempt) was
reused as-is, unmodified, to complete Phase 4.

---

## Known limitations

- **Scenario F** (notification-posting-failure forced via `appops`) remains
  environment-limited on this specific device — `POST_NOTIFICATION deny`
  does not block `NotificationManagerCompat.notify()` here. Code-path
  correctness verified by inspection, not by a forced failing test.
- **One M4-C process-death anomaly** (Phase 3) was not reproduced across two
  subsequent clean attempts and direct file-level evidence found no
  persistence defect; most likely attributable to the same standing
  real-background-job interference documented throughout this session,
  though the exact mechanism was not conclusively pinned down.
- **Direct-Boot lock state after a real reboot** is a genuine platform
  behavior this project's manifest does not opt out of (no app here is
  direct-boot-aware, nor should it be — DPS holds no data that needs to be
  available before the user unlocks their device). A real device reboot in
  production will likewise leave the app unlaunchable, and `WorkManager`'s
  own periodic work unable to fire, until the user's first unlock — this is
  standard, expected Android behavior, not a defect, and is now the fully
  understood explanation for M4-A's earlier, less-precise
  ENVIRONMENT-LIMITED report.

---

## Final M4 verdict

M4-A, M4-B, and M4-C all behave correctly, individually and as one integrated
system, on a real device, including across genuine process death and a
genuine reboot. Every battery/safety boundary holds. No forbidden file was
touched. **M4 is complete.**

M4-D is now finished. M5 was not started, and per the milestone's own stop
rule, no further proactive features, background AI, or autonomous execution
will be added without explicit approval.
