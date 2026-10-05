# Day 25 — M9 Targeted Verification Race Fix

**Date:** 2026-10-04 · **Baseline:** `25bd1df` (branch `master`) · **Nothing committed.**

**Verdict: M9 = ACCEPTED** under the DAY-25 acceptance rule — every
mandatory criterion is met with evidence (§15). Three things are not
blockers under that rule but need your decision; they are listed in §14.

The fix in one sentence: after a tap, verification now asks the target
app for the element directly instead of walking a node tree the
platform serves from a cache that still held the pre-tap state.

---

## 1. Problem

After a real, correct tap on the test app's button, the production
pipeline sometimes answered *"I made that change, but what I can see
afterward doesn't fully match…"* although the button already read
`Tapped`. `observeAndVerify()` returned
`Mismatch(expected=Tapped, observed=Tap me)` for an action that had
succeeded. This is the one failing row of DAY-23's acceptance matrix
(M9-AUTO-06).

## 2. DAY-24 evidence

DAY-24 (emulator) established:

- Corrected E2E, 5 runs: the tap landed 5/5, the pipeline reported
  `Mismatch` in 3/5.
- Isolation, 5 runs: straight after the same tap, `observeAndVerify()`
  read `Tap me` 5/5 while a direct
  `findAccessibilityNodeInfosByViewId()` read `Tapped` 5/5.
- A second `observeAndVerify()` call usually self-corrected; all had by
  +300 ms.

Before changing any code I re-measured the unfixed build on both
devices with the corrected methodology (target only force-stopped,
`openApp()` the sole launcher, fresh DPS process per run):

| Device | Runs | Tap landed (real state `Tapped`) | False `Mismatch` | Taps per run |
|---|---|---|---|---|
| Emulator | 5 | 5/5 | 2/5 | 1 |
| Phone | 10 | 10/10 | 3/10 (runs 1, 2, 4) | 1 |

## 3. Root cause

The accessibility **node cache** on the service side.

1. `AccessibilityNodeInfo.getChild()` is answered from a cache kept in
   the service's process whenever the node is already in it.
2. `findElement()` runs just before the tap and walks the tree with
   `getChild()`. That fills the cache with the button in its pre-tap
   state (`Tap me`).
3. The tap's click listener runs inside the `performAction` call, so
   the app already shows `Tapped` when `tap()` returns.
4. The cache is only updated when the app's content-changed event
   reaches the service. That event is batched on the app side and the
   service declares `notificationTimeout="100"`, so it arrives a short
   while later.
5. `observeAndVerify()` walked the tree with `getChild()` again in that
   gap and got the cached pre-tap node.

Probes run on the unfixed build, straight after a real tap:

| Read | Emulator | Phone |
|---|---|---|
| `getChild()` walk (what production did) | `Tap me` in 2 of 3 | `Tap me` in 1 of 1 |
| `refresh()` on that same stale node | `Tapped` | `Tapped` |
| `findAccessibilityNodeInfosByViewId()` | `Tapped` | `Tapped` |
| by-id lookup as the *first* query after the tap | `Tapped` | `Tapped` |

`refresh()` and the by-id lookup both go to the app; neither is served
from the cache. DAY-24's "~300 ms" is simply how long the cache took to
catch up — nothing needs that long.

**Corrections to earlier reports.** With the corrected methodology the
tap landed on the phone 20/20 (10 before the fix, 10 after). DAY-21/23's
"`ACTION_CLICK` does not deliver on this device" and DAY-22's
"device/OS behaviour" classification do not hold. The most consistent
explanation for those sessions is the stale read plus the pre-launch
artifact DAY-24 found (pre-launching the target stacks a second
Activity instance); I did not re-run the old procedure to re-create
them, so that part is inference.

## 4. Chosen fix

The verification read resolves a resource-id descriptor through a live
by-id lookup.

- `ElementMatcher.findFresh()` — used only by `observeAndVerify()`.
  Same exact-id rule and same unique-match rule as `find()`
  (0 → `NotFound`, 1 → `Found`, >1 → `Ambiguous`). Only the source of
  the nodes differs.
- `AutomationNode.findByResourceId()` — implemented on the real node as
  `findAccessibilityNodeInfosByViewId()`.
- One read. No delay, no polling, no second attempt.
- `findElement()` and `tap()` are untouched and still use `find()`.

`AutomationVerifier.kt` itself is unchanged. The brief names it, but it
only delegates to `engine.observeAndVerify()`; the stale read lives one
layer below, in the node lookup.

Limit: a descriptor with no resource id still goes through the cached
walk, because no live lookup exists for content description or text.
The only supported target uses a resource id (§14, item 2).

## 5. Alternatives considered and rejected

| Alternative | Why not |
|---|---|
| Wait ~300 ms before reading | Forbidden (constraint 9), and it guesses at event timing instead of removing the dependency on it. |
| Bounded re-observation (poll until it matches) | The brief allows it only if a fresh lookup is not sufficient. It is sufficient: 20/20. |
| `refresh()` every node during the walk | Works in the probe, but costs one call per node and the cached child list can still be stale. |
| Wait for the content-changed event | Adds event state to the service; a tap that changes nothing would have to time out. |
| Change the service config (`notificationTimeout`, cache flags) | Affects every path, still timing-dependent. |
| Use the by-id lookup in `findElement()`/`tap()` too | Those paths have no defect. Left alone to keep the change narrow. |
| Retry the tap, gestures, coordinates, `adb input` | Forbidden, and none addresses a read problem. |

## 6. Code changes

| File | + / − | What |
|---|---|---|
| `domain/automation/ElementMatcher.kt` | +51 / −5 | `findByResourceId` on `AutomationNode`; `findFresh()`; shared `unique()` |
| `data/android/automation/AndroidAutomationEngine.kt` | +8 / −1 | `observeAndVerify()` calls `findFresh`; real `findByResourceId` |
| `test/.../ElementMatcherTest.kt` | +83 / −1 | 6 new tests |
| `test/.../AutomationVerifierTest.kt` | +103 / −13 | counting fake engine; 4 new tests, 1 weak test removed |
| `test/.../SecretaryOrchestratorTest.kt` | +13 / −4 | tap-count and observe-count assertions on two existing tests |
| `androidTest/.../AutomationPipelineInstrumentedTest.kt` | +127 / −71 | enables/disables the service itself; counts real click events |

Production change: two files, both inside M9's automation package.
`ToolOrchestrator`, `ToolExecutor`, `RiskPolicy`, `PendingConfirmation`,
`ExecutionVerifier`, `AutomationVerifier`, `SecretaryOrchestrator`,
`DpsAutomationService` and `AndroidAutomationTool` have an empty diff.

## 7. JVM results

`./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache`
on the final tree (really executed, not restored from cache):

**797 tests, 0 failures, 0 errors, 0 skipped — 47 classes.**
Previously 788; +9 (`ElementMatcherTest` 8 → 14, `AutomationVerifierTest` 9 → 12).

| Required coverage | Test |
|---|---|
| 1. Successful verification stays PASS | `verify resolves Verified and clears the pending record` |
| 2. Mismatch stays MISMATCH | `verify resolves Mismatch…`; `a tap that changed nothing is still a mismatch` |
| 3. Not found stays NOT_FOUND | `verify resolves NotFound…`; `findFresh is NotFound when the element is really gone, even though the cached tree still lists it` |
| 4. Observation failure stays OBSERVATION_FAILED | `a null engine reports ObservationFailed rather than crashing…` |
| 5. Fresh observation sees the post-action state | `findFresh reads the app's current state, not the pre-tap snapshot` (also asserts `find()` still returns `Tap me` — the defect, pinned); `verification reports the app's state after the action, looked up by the persisted resource id` |
| 6. No path taps twice | `verify observes exactly once and never taps, opens or searches` (all four outcomes); `recovering a leftover record observes exactly once and never taps`; `findFresh asks the app exactly once`; orchestrator tests assert `tapCalls == 1` and `observeCalls == 1` for both a verified and a failed verification |

## 8. Instrumented results

`AutomationPipelineInstrumentedTest`, final build:

| Device | Result |
|---|---|
| Emulator | 6/6 |
| Phone | 6/6 (run three times: twice alone, once inside the full batch) |

What it proves on a real device: permission reported granted, service
connected, target opened by `openApp()` and the button found, an unknown
id is `NotFound`, a tap is followed with no wait by `Verified`, and the
target app emitted **exactly one** `TYPE_VIEW_CLICKED` event (counted
independently of the engine, with a one-second window for a duplicate
that must not come).

Harness changes, and why:

- These six tests had failed in every earlier batch with "service never
  connected". Cause: an instrumented run ends by killing the process;
  a service bound at that moment is recorded under *Crashed services*
  and never rebound. (DAY-21 attributed it to force-stop clearing the
  setting — that was not the mechanism.) The class now enables the
  service from inside the run and disables it in `@AfterClass`.
- It no longer launches the target itself; `openApp()` is the only
  launcher.

**Limit, measured:** this class cannot catch the stale read. With the
fix reverted it still passed 6/6, because the `UiAutomation` connection
in the test process keeps the same node cache current. The regression
guard for the race is the JVM `ElementMatcherTest`; the on-device proof
is §9 and §10.

## 9. Emulator results (Pixel 5 AVD, Android 12 / API 31, x86_64)

Ten contiguous independent runs on the fixed build. Each run: target
force-stopped and never pre-launched, a new DPS process, the real
service, `openApp()` as sole launcher, the "yes" confirmation through
the real tool/executor/verifier.

| Run | DPS pid | `openApp` | `findElement` | Taps | Real state | Verification | Reply | Target Activity starts |
|---|---|---|---|---|---|---|---|---|
| 1 | 8808 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 2 | 8944 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 3 | 9076 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 4 | 9208 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 5 | 9341 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 6 | 9474 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 7 | 9607 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 8 | 9739 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 9 | 9875 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 10 | 10017 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |

**10/10 verified. One observation per run. Pending record `null` after
every run. No duplicate Activity.**

Disclosed: one earlier attempt was void and is not counted — the system
cleared the just-enabled service about a second after the APK install,
the run stopped at `NeedsPermission` with zero taps. The harness got a
read-back precondition and the ten above were then run fresh. Nine
other valid fixed-build runs before that were also all `Verified`.

## 10. Physical-device results (VGOTEL NEW 15, Android 12 / API 31, MediaTek)

Same procedure, fixed build.

| Run | DPS pid | `openApp` | `findElement` | Taps | Real state | Verification | Reply | Target Activity starts |
|---|---|---|---|---|---|---|---|---|
| 1 | 25485 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 2 | 25665 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 3 | 25977 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 4 | 26208 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 5 | 26367 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 6 | 26542 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 7 | 26705 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 8 | 26866 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 9 | 27084 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |
| 10 | 27308 | Opened | Found | 1 | Tapped | Verified | Tapped it in "test app". | 1 |

**10/10 verified, against 7/10 on the same phone minutes earlier
without the fix.** No device-specific code exists.

**How §9–§11 were driven.** There is no way to send a scripted request
through the chat UI without the on-device model, so — as in DAY-23/24 —
a temporary in-app diagnostic drove the real `AndroidAutomationEngine`,
`AndroidAutomationTool`, `DefaultToolExecutor` and `AutomationVerifier`
from a normally launched process, with a pass-through counter around the
engine. It is deleted (§13). The build used for these runs was the final
production source plus that one file; the shipped automation classes are
identical.

## 11. Process-death results

Protocol, both devices: real pipeline → real tap → the process kills
itself before verification → wait → `adb shell am force-stop` → new
process.

| | Emulator | Phone |
|---|---|---|
| Phase 1: tap, then death | tap#1 `Performed`; system log: `Process com.softwaremine.dps (pid 10251) has died` | tap#1 `Performed`; `(pid 27592) has died` |
| Record on disk after death | present (target, resource id, expected text) | present |
| 6 s with no user input | no pipeline activity, no further target start, record still present | same |
| **Cycle A** — target still in front | `observeAndVerify#1 → Verified`; open=0 find=0 **tap=0** observe=1; record cleared | `Verified`; **tap=0** observe=1; record cleared |
| **Cycle B** — DPS's own Activity brought to the front | `ObservationFailed` (no readable window during the switch); honest "couldn't check" notice; **tap=0**; record cleared | `NotFound` (DPS's own window was active); honest "couldn't find it afterward" notice; **tap=0**; record cleared |

- Pending state correct: yes.
- Duplicate action from process death: none.
- Recovery repeats `ACTION_CLICK`: never (0 taps in four recoveries).
- Verification completes after recovery: yes, exactly one observation.
- Unattended execution: none. (The system restarts the dead process to
  rebind the service; that process did nothing until a message arrived.)

Cycle B is the designed fallback: the target cannot be observed from
behind DPS's own window, so the user is told plainly rather than given
a guess.

One observation from Cycle A is in §14, item 1.

## 12. Regression results

### 12.1 JVM
797/797 (§7).

### 12.2 Phone — full instrumented batch, same procedure on both builds

`adb shell am instrument -w com.softwaremine.dps.test/…AndroidJUnitRunner`,
app opened once beforehand, no crashed service mark.

| Build | Tests | Failures |
|---|---|---|
| Baseline (`25bd1df`, no DAY-25 change) | 237 | 25 |
| Final | 237 | 16 |

- **Failing only on the final build: none.**
- Failing on both: 16 (below).
- Failing only on the baseline: 9 — the six
  `AutomationPipelineInstrumentedTest` tests (fixed harness, §8), and
  three that are flaky rather than fixed:
  `ProactiveCheckWorkerInstrumentedTest` ×2 and
  `SecretaryLiveWiringInstrumentedTest.anExplicitOffsetMoves…`. I am
  not claiming those three as improvements.

The 16, each with its cause:

| # | Test(s) | Cause | Evidence |
|---|---|---|---|
| 10 | `CheckpointRecovery` phase2A/2B/2C · `ConfirmationProcessDeath` phase1, phase2 · `SecretaryExecutionRecovery` phase2 ×2 · `ProactiveProcessDeath` phase2 ×2 · `ExecutionVerifierProcessDeath` phase2A | Two-phase tests need a real kill between phases. In one batch process phase 2 runs without it ("must survive real process death"), and `ConfirmationProcessDeath.phase1` meets the checkpoint that `CheckpointRecovery` phase 1 leaves on purpose. | All pass under the real protocol — §12.3, 15/15. |
| 2 | `ModelDownloadDiagnosticTest` · `CalendarClassificationInvestigationTest` | No model on the phone: download fails with `InsufficientStorage(requiredBytes=1385756192, availableBytes=0)`; the second test then reports "Model … is not installed". | Assertion messages; identical on baseline. |
| 3 | `PermissionFoundation.undeclaredPermissionsAreReportedAsNotUsable`, `.executorGatesOnPermissionsOnDevice` · `AndroidTools.outOfScopeToolsRemainUnimplemented` | Outdated expectations: they assert calendar permissions are undeclared and PHONE is unimplemented. The manifest declares both calendar permissions and `AndroidCallTool.kt` exists. | Source; identical on baseline. |
| 1 | `ProactiveIntegratedBehavior.scenariosAThroughD…` | Flaky. It compares two snapshots of active notification ids, and Android posts asynchronously — the extra id is either the test's own just-posted event notification or the system's auto-group summary (`id=2147483647, tag=ranker_group`). | Alone: **final 4 pass / 2 fail of 6, baseline 4 pass / 2 fail of 6.** |

### 12.3 Phone — M5–M8 process-death pairs, real protocol, final build

Phase 1 → `adb shell am force-stop` → `pidof` empty → phase 2, each in
its own invocation. **15/15 pairs passed, both phases:**

| Area | Pairs | Result |
|---|---|---|
| M5 — `ProcessDeathPersistence` (1), `SecretaryExecutionRecovery` (2), `CheckpointRecovery` (3), `CalendarCheckpointRecovery` (2) | 8 | 8/8 |
| M6 — `LongTermMemoryProcessDeath` | 1 | 1/1 |
| M7 — `ExecutionVerifierProcessDeath` (task, calendar) | 2 | 2/2 |
| M8 — `ConfirmationProcessDeath` | 1 | 1/1 |
| Proactive — `ProactiveProcessDeath` | 3 | 3/3 |

For M8 additionally: its JVM tests are in the 797, and `RiskPolicy.kt`
/ `PendingConfirmation.kt` have an empty diff.

### 12.4 Three reminder failures I caused, then removed

My first final-build batch on the phone showed 18 failures; three were
new: `ReminderTrigger…scheduledReminderActuallyFires…` and two
`ReminderBootReceiver…` tests. Cause: my own procedure. I had added an
`adb shell am force-stop com.softwaremine.dps` before the batch, and on
this phone a real force-stop makes the OS skip the app's manifest
receivers until its Activity is opened again.

| Step | Build | Reminder tests (5) | OS log |
|---|---|---|---|
| State left by a force-stop | final | 3 fail | broadcast history: `Skipped` for `ReminderReceiver` |
| App opened once | final | 5 pass | — |
| Again, nothing between | final | 5 pass | — |
| `adb shell am force-stop` | final | 3 fail | 3× `BroadcastQueue: … suppress to start process of staticReceiver for package:com.softwaremine.dps` |
| App opened once | final | 5 pass | — |
| `adb shell am force-stop` | **baseline** | 3 fail | — |
| App opened once | **baseline** | 5 pass | — |

Same behaviour without any DAY-25 code. With the app opened once before
the batch, all reminder tests pass (the 16-failure run above). The test
class's own guidance now says so.

### 12.5 Emulator

Not the project's reference platform (no calendar account, no model),
so it fails more tests on any build.

| Build | Tests | Failures |
|---|---|---|
| Baseline | 237 | 33 |
| Final | 237 | 28 |

- Failing on both: 27 — the same categories as the phone, plus tests
  that need a calendar account.
- Failing only on the baseline: the six automation tests.
- **Failing only on the final build: one** —
  `ProactiveCheckWorker.enablingTheProactiveAssistantPreserves…`,
  `expected:<…016498> but was:<…016500>`. The log shows the app's real
  WorkManager worker starting in the same process one millisecond after
  the test took its timestamp (`19:43:36.499 WM-WorkerWrapper: Starting
  work for …ProactiveCheckWorker`) and both posting the same
  notification; the worker saved its own time 2 ms later. Re-run on the
  final build: 5/5 alone, class 15/15. A race between that test and the
  app's own background worker; no automation code is involved.
- Pairs (final build): 12/15 earlier in the day. Two failures need a
  calendar account. The third, M7's task pair, failed six times that
  afternoon — once on the final build, at least three times on the
  baseline — and later passed 15/15 on the final build. I tested one
  explanation — the task's asynchronous `apply()` write
  losing to the kill — and the data refuted it (the save-to-kill gap
  was 23–77 ms in both the failing and the passing runs). **Cause not
  established.** It is not caused by this change (fails on baseline,
  shares no code with it) and it passed on the phone.

## 13. Cleanup verification

- `git status`: exactly the six modified files of §6 plus this report.
  No stash entries. HEAD still `25bd1df`. Nothing committed.
- Temporary diagnostic (`ui/M9Day25Diagnostic.kt` and a one-line hook in
  `MainActivity.kt`): deleted / restored. `MainActivity.kt` has no diff;
  a search for the diagnostic's names in `app/src` returns nothing.
- No debug logging, experimental code or test-only workaround remains.
- Scan of the production automation code: one `performAction(ACTION_CLICK)`
  call; no `dispatchGesture`, no coordinates, no `input tap`, no sleep.
  The only loops are the pre-existing waits in `openApp()` and
  `findElement()`, unchanged.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest` successful
  on the final tree.
- Devices: final build installed on both; accessibility service
  disabled, nothing under *Crashed services*; DPS opened once on the
  phone so its receivers are not left suppressed.

Disclosed along the way:

- Baseline APKs were built with `git stash` / `git stash pop`; the tree
  was verified identical afterwards.
- A stray `git checkout -p` ran once by mistake and exited at its first
  prompt; `git status` and `git diff --stat` confirmed nothing was
  discarded.
- The phone's USB link dropped three times and then stalled offline.
  With your approval the last part ran over Wi-Fi adb
  (`adb tcpip 5555`); it is switched back (`adb usb`, port reads `0`).
- Test runs leave test notifications and reminders in DPS on the phone
  (e.g. several "call the bank" reminders). I did not delete app data.

## 14. Remaining blockers

**None against the DAY-25 acceptance rule.** Open items — the first
three need your decision:

1. **Recovery offer after a verified tap.** In Cycle A (§11) the tap was
   recovered as `Verified`, and the first reply was still the M5-B
   offer: *"A previous request was interrupted ("test app"). Would you
   like to continue it?"* Nothing runs unless the user says yes and
   then confirms again, so the "never blindly re-executes" rule holds —
   but the offer invites repeating a tap already verified as done. The
   fix belongs in M5-B / `SecretaryOrchestrator`, which this task was
   told not to touch. Recommendation: drop the interrupted-request
   record when automation recovery resolves.
2. **Descriptors without a resource id** still verify through the cached
   walk and can show the same stale read. Nothing supported uses one
   today. This must be solved before any content-description or text
   target is added.
3. **No automated on-device guard for the race.** Instrumentation masks
   it (§8), so the device-level proof is the manual E2E of §9–§10,
   which is not re-runnable from the repository.
4. Existing test debt, unchanged by this work: three outdated tests, two
   model-dependent tests, racy proactive tests (§12.2, §12.5).
5. M7 task pair intermittent on the emulator, cause not established
   (§12.5). Worth its own look: `AndroidTaskStore` persists with
   `apply()`.
6. On this phone a real force-stop suppresses reminders until DPS is
   opened again (OS behaviour, §12.4).
7. Cosmetic: the recovery notice reads "Before this restarted, i
   attempted that…" (lower-case "i").

## Final audit

1. **What stale-state mechanism caused the false Mismatch?**
   The service-side accessibility node cache. `findElement()` filled it
   with the pre-tap node; `getChild()` kept returning that node until
   the content-changed event arrived (§3).
2. **What production code changed?**
   `ElementMatcher.kt` (new `findFresh`, `AutomationNode.findByResourceId`)
   and `AndroidAutomationEngine.kt` (`observeAndVerify` uses it). Nothing
   else in `src/main`.
3. **Why does the fix not repeat `ACTION_CLICK`?**
   It is a read. `tap()` is untouched, and the only `performAction` call
   is still the one inside it. Counted: 1 tap in 20/20 device runs, one
   click event in the instrumented test, 0 taps in four recoveries.
4. **Why is it deterministic?**
   Exact resource-id match, unique match required, one lookup, no
   timing input, no scoring, no model. Same descriptor and same screen
   give the same answer.
5. **Why is the observation window justified?**
   There is none. The app has applied the change by the time the tap
   call returns, and the lookup asks the app directly, so nothing has to
   be waited for.
6. **Does it work on the emulator?** Yes — 10/10 (2/5 false mismatches before).
7. **Does it work on the phone?** Yes — 10/10 (3/10 false mismatches before).
8. **Did process-death recovery remain safe?** Yes — §11, both devices.
   See §14 item 1.
9. **Did M5–M8 behaviour remain unchanged?** Yes. No M5–M8 source file
   changed; 797 JVM tests pass; 15/15 process-death pairs pass on the
   phone; no instrumented test fails on the final build that passes on
   the baseline.
10. **Any remaining M9 blockers?** None against the acceptance rule.
    §14 items 1–3 are yours to rule on.

## 15. Final verdict

| Criterion | Result |
|---|---|
| Targeted verifier fix implemented | Yes |
| JVM tests pass | 797/797 |
| Focused instrumented tests pass | 6/6 emulator, 6/6 phone |
| Emulator clean verified runs | 10/10 |
| Phone clean verified runs | 10/10 |
| `ACTION_CLICK` exactly once | Yes |
| No retry / gesture / ADB workaround | Yes |
| Process-death safety intact | Yes |
| Full regression acceptable | Yes — no failure attributable to the change; every remaining failure also fails on the baseline and is explained. The instrumented suite is not green in absolute terms (16 on the phone), as before M9. |
| Cleanup complete | Yes |
| Final audit finds no blocker | Yes |

**M9 = ACCEPTED.**

DAY-23's one failing row, M9-AUTO-06 ("one bounded interaction
executes"), now passes: the interaction executed and was verified in
20 of 20 runs across two devices.

If you judge §14 item 1 to be a blocker rather than a follow-up, the
verdict becomes NOT ACCEPTED on that single point; everything else
stands.
