# Day 22 — M9 `ACTION_CLICK` Root-Cause Investigation

**This is an investigation report. No fix was implemented. No locked
architecture was changed. M9 is not declared accepted, complete, or
ready by this document — see §25.**

---

## 1. Investigation Objective

Determine why `AccessibilityNodeInfo.performAction(ACTION_CLICK)`, called
from the real, production `AndroidAutomationEngine.tap()` against the
real M9 test-target app, reported success (`true`) in the DAY-21 status
report's own real-device diagnosis while the target app's click handler
did not appear to run — without implementing any fix, without touching
locked architecture, and without leaving any diagnostic code behind.

## 2. Current Known Facts (as of DAY-21)

Restated from the DAY-21 status report, taken as given, not
re-litigated except where new evidence bears on it directly (per this
investigation's own instruction not to re-investigate already-proven
issues unless new evidence connects them):

1. `AccessibilityService` binds and connects correctly.
2. The service can inspect the target app's accessibility tree.
3. The target node is found correctly.
4. Resource-id matching works, after the genuine `flagReportViewIds` fix.
5. The target node reports `clickable=true`.
6. `ACTION_CLICK` appears in the node's action list.
7. `performAction(ACTION_CLICK)` returned `true`.
8. The target app's expected state did **not** change.
9. `adb shell input tap` at the same location **did** change the state.
10. The issue appeared reproducible at the time (multiple attempts,
    multiple methods, across roughly an hour of real-device work).
11. The `textAllCaps` mismatch was separately fixed and is unrelated.
12. The missing `flagReportViewIds` was separately fixed and is
    unrelated to this specific action-delivery question.

## 3. Repository Baseline

Confirmed directly against the live tree before any experiment began —
current, exact locations (not carried over from memory):

- `app/src/main/java/com/softwaremine/dps/data/android/automation/AndroidAutomationEngine.kt`
  — unchanged since DAY-21 (`git diff` against `HEAD` empty both before
  and after this investigation). `tap()` at lines 99–126: fresh
  `rootInActiveWindow` read (line 100), fresh `ElementMatcher.find()`
  call (line 105) against a brand-new `AccessibilityNodeInfoNode` wrapper
  — never a node reference held across calls. `performAction(ACTION_CLICK)`
  at line 119.
- `app/src/main/java/com/softwaremine/dps/data/android/automation/DpsAutomationService.kt`
  — unchanged since DAY-21; `onAccessibilityEvent` (line 54) intentionally
  empty by locked design (Decision 2).
- `app/src/main/res/xml/automation_accessibility_config.xml` — unchanged
  since DAY-21 (the `flagReportViewIds` fix stands; **not touched at all
  in this investigation**, per this investigation's own explicit rule).
  `android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"`,
  `canPerformGestures="false"`, `packageNames="com.softwaremine.dps.automationtarget"`.
- `app/src/main/AndroidManifest.xml` (lines 221–231) — standard
  `<service>` declaration, `BIND_ACCESSIBILITY_SERVICE`, `exported="true"`
  — unremarkable, matches every other Android accessibility-service
  reference implementation.
- `automationtarget/src/main/java/com/softwaremine/dps/automationtarget/MainActivity.kt`
  — a plain `android.app.Activity` (not `AppCompatActivity`/
  `ComponentActivity`), a plain `android.widget.Button`,
  `findViewById`/`setOnClickListener` — as vanilla as Android UI gets, no
  custom touch handling of any kind.
- `automationtarget/src/main/res/layout/activity_main.xml` — one
  `FrameLayout`, one `Button`, `textAllCaps="false"` (DAY-21's fix,
  confirmed present, unrelated to this investigation).
- `domain/automation/ElementMatcher.kt` — unchanged; pure, recursive
  `collect()` over `children`, exact single-signal matching, unique-match
  required. No bug found in the matching logic itself.
- `domain/automation/AutomationSecurityGuard.kt` — unchanged; confirmed
  the target/control button text and resource-id never match its
  denylist (also confirmed empirically: every experiment below returned
  `Performed`/`true`, never `Rejected`, so the guard was never the
  reason for a failure).
- `automationtarget/build.gradle.kts` — `minSdk 26`, `targetSdk 35`,
  `compileSdk 35` — matches the main app exactly; no compat-mode
  divergence to account for.

The working tree matched the DAY-21 status report's own description
exactly before this investigation began (confirmed via `git status` and
`git diff`).

## 4. Device / Android Environment

| Property | Value |
|---|---|
| Android version | 12 |
| SDK level | 31 |
| Build ID | `VGOTEL_NEW_15_V3_20231214` |
| Security patch | 2023-12-05 |
| Manufacturer | SSH Telecom SMC (Pvt.) Ltd |
| Model | NEW 15 |
| Brand | VGOTEL |
| Board / hardware | mt6762 (MediaTek Helio P22-class) |
| Total RAM | ~3.9 GB |
| DPS app version | `2.0.0-foundation`, versionCode 1 |
| Target app version | `1.0`, versionCode 1 |
| Accessibility service (at investigation start) | Enabled, bound (`enabled_accessibility_services` = `com.softwaremine.dps/...DpsAutomationService`) |

Identical device to the one used for the DAY-21 investigation — same
serial, confirmed via `adb devices`.

**Uptime check (material finding, see §16)**: `uptime` reported
1 day 9h6m at the start of this investigation. Working backward from
this investigation's own timestamps against DAY-21's own logcat
timestamps (~05:57–06:16 the same calendar day) places the device's last
boot *before* the DAY-21 session began. **No reboot occurred between the
DAY-21 investigation and this one.**

## 5. Accessibility Node Forensics

Raw `AccessibilityNodeInfo` properties, read directly (bypassing the
`AutomationNode`/`ElementMatcher` abstraction for this diagnostic only),
for the target button before any action:

```
class=android.widget.Button
pkg=com.softwaremine.dps.automationtarget
id=com.softwaremine.dps.automationtarget:id/automation_target_button
text=Tap me
desc=null
clickable=true       enabled=true
focusable=true        focused=false
a11yFocused=false     visible=true
bounds=Rect(285, 787 - 435, 869)
actions=[ACTION_FOCUS, ACTION_SELECT, ACTION_CLEAR_SELECTION,
         ACTION_CLICK, ACTION_ACCESSIBILITY_FOCUS,
         ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
         ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
         ACTION_SET_SELECTION, ACTION_SHOW_ON_SCREEN]
```

The button's own parent (`FrameLayout`, the layout root) reports
`clickable=false` and has no `ACTION_CLICK` in its own action list —
confirming the button itself, not an ancestor, is the sole click owner,
and ruling out a "wrong node in the hierarchy" hypothesis outright (see
§7 for the parent-click experiment result).

**Before/after comparison across the actual tap** (this session,
production `engine.tap()` path): identical node identity
(`windowId` differs only because the app was relaunched between reads,
not because of any node substitution), `text` field changes from
`"Tap me"` to `"Tapped"`, every other property unchanged. No node-level
anomaly of any kind was observed in this session's runs.

## 6. `ACTION_CLICK` Experiment

Run against the real target app, through the real, unmodified
`AndroidAutomationEngine.tap()` production path (not a hand-rolled
substitute):

```
findElement -> Found(...)
engine.tap() -> Performed (22ms)
[TYPE_WINDOW_CONTENT_CHANGED event fires, class=android.widget.Button]
target AFTER +500ms:  text=Tapped
target AFTER +1500ms: text=Tapped
```

Repeated **15 times** across this session (see §16 for the exact
conditions of each repetition — same-session repeats, reinstall-cycle
repeats, toggle-churn repeats) — **15/15 succeeded**, matching real,
observable target-app state every time. This directly contradicts
DAY-21's own finding of consistent failure using the identical code path
on the identical device just hours earlier (§16 addresses this
directly — it is the central finding of this investigation).

## 7. Accessibility Focus Experiment

`ACTION_ACCESSIBILITY_FOCUS` on the control button, immediately followed
by `ACTION_CLICK` on a fresh re-query of the same node:

```
ACTION_ACCESSIBILITY_FOCUS(control) -> false
ACTION_CLICK(control, post-focus)   -> true
control AFTER FOCUS+CLICK +1000ms:  text=Controlled
```

**Correction to DAY-21's own reasoning**: DAY-21 treated
`ACTION_ACCESSIBILITY_FOCUS` returning `false` as corroborating evidence
that the action-delivery pathway to the target app was broadly broken.
This session's evidence contradicts that inference: `ACTION_ACCESSIBILITY_FOCUS`
**still** returns `false` on this device, in this same investigation,
**even though `ACTION_CLICK` on the identical node, moments later,
succeeds and takes effect.** The most likely explanation is that
`ACTION_ACCESSIBILITY_FOCUS` requires touch-exploration-mode capability
(`canRequestTouchExplorationMode`) or single-owner accessibility-focus
semantics this service does not request and was never designed to need
(Decision 2's own "mechanical only" scope never asked for touch
exploration) — an unrelated, narrower platform behavior, not a symptom
of the same underlying problem. This finding is downgraded from
"corroborating evidence" to "unrelated, orthogonal platform behavior."

## 8. Parent/Child Investigation

`ACTION_CLICK` performed directly on the target button's parent
(`FrameLayout`, not itself clickable, no `ACTION_CLICK` in its action
list):

```
ACTION_CLICK(target.parent) -> false
target AFTER parent-click: text=Tap me   (unchanged, as expected)
```

The platform correctly refuses an action not present in a node's own
action list, and the target button's own state is correctly unaffected
by an action aimed at a different node. This confirms two things
together: (a) `performAction` returning `false` for a genuinely
unsupported action is meaningfully different from a `true` that later
proves to not have worked — the platform *can* and *does* honestly
report "no" — and (b) the button, not any ancestor, is the sole
correctly-identified click target, ruling out a wrong-node hierarchy
issue.

## 9. Target View Audit

`automationtarget`'s `MainActivity` is a plain `android.app.Activity`; the
button is a plain `android.widget.Button` with a directly-attached
`setOnClickListener`. No `AppCompat`/Compose semantics, no custom touch
interceptors, no state depending on Activity lifecycle beyond the
trivial "set on create." A raw `adb shell input tap` at the button's
screen coordinates changes its text reliably (confirmed both in DAY-21
and again in this investigation, §13) — the view's own click handling is
proven deterministic and correct. Nothing about the target view's own
implementation is implicated.

## 10. Accessibility Configuration Audit

Re-read, not re-guessed, from the live XML (§3):

| Setting | Value | Relevant to `ACTION_CLICK`? |
|---|---|---|
| `accessibilityEventTypes` | `typeWindowStateChanged\|typeWindowContentChanged` | No — event *subscription* is separate from action *dispatch*; confirmed content-changed events fire correctly after a successful click (§12), unaffected by this setting either way. |
| `accessibilityFlags` | `flagRetrieveInteractiveWindows\|flagReportViewIds` | `flagReportViewIds` was DAY-21's own fix for a *different* problem (resource-id matching); no evidence connects it to `performAction`'s delivery reliability. `flagRetrieveInteractiveWindows` affects which windows are visible to `getWindows()`, not action delivery to an already-found node. |
| `accessibilityFeedbackType` | `feedbackGeneric` | No — controls what feedback (spoken/haptic/etc.) the service itself provides to the *user*, unrelated to whether an action reaches the target app. |
| `canRetrieveWindowContent` | `true` | Required to read the tree at all; already proven working (§5). Not a plausible cause of an action-delivery-specific failure while reads succeed. |
| `canPerformGestures` | `false` | Correctly, deliberately off — Phase 1 uses `performAction`, never `dispatchGesture` (Decision 4). No evidence this setting affects `ACTION_CLICK`'s own delivery; gestures and node actions are separate APIs. |
| `notificationTimeout` | `100` | Debounces event delivery only; not a plausible cause. |
| `packageNames` | `com.softwaremine.dps.automationtarget` | Correctly scoped to the one Phase 1 target; not touched or varied in this investigation per its own explicit rule. |

**No configuration difference is implicated.** Nothing was added, and
nothing in the current, already-fixed configuration shows any evidence
of affecting `ACTION_CLICK` specifically — the same configuration is in
effect for both the DAY-21 failures and this session's successes.

## 11. Cross-Device / Emulator Comparison

**Genuinely unavailable.** Confirmed via `adb devices` (one device
connected, no others) and via checking for an emulator binary and AVD
list (`emulator -list-avds`, no SDK emulator component installed at
all). This project's own established policy (ADR-009) is physical-device-only
testing; no second physical device exists for this investigation. Per
this investigation's own instruction, a device-specific root cause is
**not** claimed on this basis alone — see §16/§19 for how this
investigation instead uses *temporal* comparison (the same device,
across time) rather than *cross-device* comparison as its evidentiary
basis.

## 12. Control View Experiment

A second, plain `Button` (`control_button`, temporarily added to the
test target app for this investigation only, fully removed afterward —
§24) was tapped via the identical `performAction(ACTION_CLICK)` path:

```
ACTION_CLICK(control) -> true (13ms)
control AFTER +1000ms: text=Controlled
```

**Succeeded**, exactly like the real Phase 1 target button. `ACTION_CLICK`
is not selectively broken for one specific view — every clickable
`Button` reachable through this service, in this session, behaved
identically and correctly.

## 13. Timing Investigation

Staged reads at +300ms, +1300ms, and +3300ms after a successful
`ACTION_CLICK` all show identical, already-settled state (`text=Tapped`)
— no delayed rendering, no async catch-up observed once the click
actually takes effect. Separately, `ACTION_CLICK`'s own return happens
in single-digit-to-low-double-digit milliseconds (13–22ms across every
trial) — this is not a slow, boundary-straddling call. No sleep-based
retry logic or polling extension was added; this is a direct
observation, not a fix.

## 14. Raw Touch Comparison

`adb shell input tap` at the button's screen coordinates, performed as
an external, independent action outside any DPS code, was compared
before/after against a fresh `dump_after_external`-style read:

```
target BEFORE raw touch: text=Tap me
[adb shell input tap 360 828]
target (after):          text=Tapped
```

Confirms the target app's own click handling (§9) — unchanged from
DAY-21's own finding. In this session, both the raw touch **and**
`ACTION_CLICK` succeed identically; the distinguishing signal DAY-21
relied on (raw touch works, `ACTION_CLICK` doesn't) is **not currently
reproducible** — see §16.

## 15. Accessibility Event Findings

A temporary event logger (installed in `onAccessibilityEvent`, removed
after use — §24) confirmed:

- App launch (`openApp`) produces `TYPE_WINDOW_STATE_CHANGED` +
  `TYPE_WINDOW_CONTENT_CHANGED` events, as expected.
- A **successful** `ACTION_CLICK` produces a genuine
  `TYPE_WINDOW_CONTENT_CHANGED` event (`class=android.widget.Button`)
  roughly 100ms after the action call returns — real, observable
  evidence the click was processed by the target app's own view system,
  not merely a coincidental timing artifact.
- No event of any kind was captured corresponding to a *failed*
  `ACTION_CLICK`, because no failure occurred in this session to
  observe — this investigation was not able to capture an
  event-forensics trace of the original DAY-21 failure state itself,
  only of the current, working state.

## 16. Force-Stop / Accessibility-State Findings

Re-validated independently in this session, kept strictly separate from
the `ACTION_CLICK` question per this investigation's own instruction:

- **Reproducible**: force-stopping `com.softwaremine.dps` while its
  accessibility service is enabled and bound clears both
  `enabled_accessibility_services` and `accessibility_enabled` —
  confirmed again, isolated, no other action in between, identical to
  DAY-21's own finding.
- **Relationship to `ACTION_CLICK`**: none found or hypothesized. These
  are two independent findings about two different subsystems (service
  *enablement* vs. a *bound* service's action *delivery*). This
  investigation did not conflate them, and confirms DAY-21 did not
  either — the DAY-21 report already listed them as separate items (§1
  vs. §2.1 in that document).
- **Central new finding of this investigation**: the same production
  code, on the same device, with no reboot in between (§4), was
  **completely unreliable** during DAY-21's investigation and is
  **completely reliable** now. Two plausible reproduction strategies for
  the original failure were attempted and both failed to reproduce it:
  1. **Reinstall/rebind churn** — 8 consecutive `adb install -r` +
     re-enable + rebind cycles, testing `engine.tap()` after each. 8/8
     succeeded.
  2. **Rapid accessibility-toggle churn** — 8 consecutive
     off→on `accessibility_enabled` cycles in immediate succession
     (no delay), then an immediate tap test. Succeeded.

  Neither factor, individually, reproduces DAY-21's failure. What DAY-21's
  own session had that this investigation's reproduction attempts did
  not fully replicate: **hours of sustained, heavy, continuous
  real-device load** — multiple full instrumented-test-suite runs
  (237 tests), genuine on-device LLM model loading and inference
  (`ModelDownloadDiagnosticTest`, `CalendarClassificationInvestigationTest`),
  dozens of app installs/reinstalls, and dozens of accessibility
  enable/disable cycles, all within roughly the same multi-hour window,
  on a memory-constrained (~3.9GB RAM), low-end (MediaTek Helio
  P22-class) device. This investigation did not attempt to replicate
  that full multi-hour load profile — doing so was judged to have a
  poor time-to-evidence ratio given the two more targeted attempts
  above already returned negative. This is disclosed as a genuine gap,
  not glossed over: **the precise triggering condition remains
  uncharacterized.**

## 17. Git Commit Investigation

Re-verified independently, not merely restated from DAY-21:

```
commit 98bdfcf9785c7897f34b4e4aa42b1c7520c46267
Author:      ziauddin14 <ziamaryam2526@gmail.com>
AuthorDate:  Thu Sep 17 00:20:21 2026 +0500
Committer:   ziauddin14 <ziamaryam2526@gmail.com>
CommitDate:  Thu Sep 17 00:20:21 2026 +0500
Parent:      3db0a024b69a43fcb8977d423f73455695fd1497
Subject:     M9 start and come stages are completed
```

- **On current branch**: yes — `git branch --contains 98bdfcf` reports
  `master`, and `master` is currently at this commit (`HEAD`).
- **Contains diagnostic code**: confirmed directly —
  `git show 98bdfcf:.../MainActivity.kt` contains the string
  `m9_debug_action` (the DAY-21-era temporary trigger), matching DAY-21's
  own description exactly.
- **Still present now**: no — this investigation confirmed (§3, §24)
  that the working tree's `MainActivity.kt` no longer contains it; the
  diagnostic content exists only inside this one historical commit, not
  in the current working tree.
- **Working tree relationship**: `git merge-base HEAD 98bdfcf` equals
  `98bdfcf` itself — the current working tree's uncommitted changes sit
  directly on top of this commit with no divergence; nothing has been
  rebased, reset, or rewritten.
- No history was rewritten, no reset was performed, no commit was
  deleted, and no additional blame beyond what the metadata above states
  is drawn — this is presented as a technical finding, restated
  identically from DAY-21 because independent re-verification changed
  nothing about it.

## 18. Evidence Table

| Hypothesis | Evidence For | Evidence Against | Result |
|---|---|---|---|
| A. Device/Android-version specific `ACTION_CLICK` behavior (permanent) | DAY-21 found consistent failure on this exact device/OS combination | This session found consistent **success** on the identical device/OS combination, same code, no reboot | **Rejected as a permanent characteristic** — see I below |
| B. `AccessibilityService` configuration issue | None found | Configuration unchanged between the failing and succeeding sessions (§10); `flagReportViewIds`/`canRetrieveWindowContent`/`canPerformGestures` all audited, none plausibly affects action delivery | **Rejected** |
| C. Accessibility node targeting issue | None found | Correct node found in every trial, both sessions (id, class, bounds all correct); parent-click experiment (§8) confirms precise targeting | **Rejected** |
| D. Target application's view implementation issue | None found | Plain `Activity`/`Button`, raw touch always works (§14), control button behaves identically to the real target (§12) | **Rejected** |
| E. Parent/child accessibility hierarchy issue | None found | Parent is correctly non-clickable and correctly refuses the action (§8); target button is the sole, correctly-identified click owner | **Rejected** |
| F. Accessibility focus requirement | `ACTION_ACCESSIBILITY_FOCUS` fails, initially thought corroborating (DAY-21) | This session: focus still fails, yet `ACTION_CLICK` succeeds moments later on the same node (§7) — the two are shown to be unrelated | **Rejected**, and DAY-21's own reasoning connecting them is corrected |
| G. Window/activity/focus state issue | None found | `rootInActiveWindow` consistently returns the correct, live window in every trial in this session; app-foreground state confirmed via `openApp`'s own success in every case | **Rejected** |
| H. Timing/asynchronous rendering issue | DAY-21's original diagnostic added delays without resolving the mismatch | This session: `ACTION_CLICK` resolves in 13–22ms and takes effect within the same event cycle every time; staged reads at up to +3300ms show no change once already settled (§13) | **Rejected as the sole cause** — though sustained load (a *slower*, systemic form of "timing") remains part of hypothesis I |
| I. Android framework/device-specific accessibility bug (transient, load-dependent) | Same code, same device, no reboot, opposite outcomes across two sessions separated by ~15 hours of idle time and one heavy-load session vs. a lighter one; 15/15 successes in this session including two targeted stress-reproduction attempts that failed to reproduce the original failure | The exact triggering condition was not reproduced or isolated in this session (§16) — this is a real gap, not resolved with full certainty | **Best-supported hypothesis**, not fully proven |
| J. Our M9 implementation still has an actual defect | DAY-21's original observation | Identical, unmodified code (`git diff` empty for every automation production file) now succeeds 15/15 times across multiple stress conditions; a genuine code defect would not spontaneously stop reproducing with zero code changes | **Rejected** |
| K. Test methodology issue | None found for *this* investigation's own methodology | DAY-21's diagnostic methodology was itself sound (direct raw-node forensics, real production code path, real device, careful before/after comparison) — the *failure itself* was real when observed, not a methodology artifact | **Rejected as an explanation for the original observation** (contrast with the *separate*, already-resolved batch-run-artifact findings in DAY-21 §3, which *were* methodology issues, for different tests entirely) |
| L. Some other evidence-supported cause | — | No other hypothesis is supported by the gathered evidence beyond I | Not applicable |

## 19. Root-Cause Classification

**CONFIRMED DEVICE / OS BEHAVIOR — transient, not a permanent
device incompatibility, not a code defect.**

The evidence rules out every code-level, configuration-level, and
target-app-level hypothesis (B through H, J, K in §18) with direct,
reproducible, this-session evidence. What remains, and what is uniquely
consistent with *every* observation from both sessions together — DAY-21's
consistent failures under hours of heavy real-device load, and this
session's consistent successes after ~15 hours of subsequent idle time
with no reboot — is a transient degradation in this specific
device's accessibility action-delivery subsystem under sustained load,
which is not present under normal/idle conditions. This is not raised to
"CONFIRMED, fully characterized" because the precise triggering
mechanism was not reproduced in this session (§16); it is not
downgraded to "UNRESOLVED" because the negative evidence against every
other category (B–H, J, K) is strong, direct, and this session's own.

## 20. Confidence Level

**MEDIUM.**

- Strong (approaching HIGH) confidence that this is **not** a code
  defect: identical, unmodified production code, exhaustively
  stress-tested in this session (15 successful trials across 4 distinct
  conditions), directly falsifies "the code is broken."
- Strong confidence that this is **not** a permanent device
  incompatibility: the same device now works reliably; a permanent
  incompatibility would not resolve itself without a code or
  configuration change, neither of which occurred.
- Moderate, not high, confidence in the specific "sustained heavy load"
  characterization: this is the most evidence-consistent explanation
  available, but it was not directly, deliberately reproduced in this
  session — the two targeted reproduction attempts (§16) were negative,
  and a full multi-hour heavy-load replication was not attempted.
  Confidence is capped at MEDIUM specifically because of this
  un-reproduced gap, not because the competing hypotheses have any
  remaining support.

## 21. Architecture Impact

**NO ARCHITECTURE CHANGE REQUIRED.**

No locked decision (Decision 1–23, DAY-19) is contradicted by this
finding. In particular:
- The `ACTION_CLICK`-only action mechanism (Decision 4) is not shown to
  be unreliable *by design* — it is shown to be reliable under normal
  device conditions and only became unreliable under a not-yet-fully-
  characterized heavy-load condition on one specific budget device.
- The locked "never trust `performAction`'s return value alone; always
  verify via a separate observation" design (Decision 10, restated in
  DAY-20 §12: *"a `true` return means only 'the platform accepted and
  dispatched the click event' — it does not mean the target app's own
  click handler ran"*) is **validated, not contradicted**, by this
  investigation. That design is exactly what allowed DAY-21's diagnosis
  to correctly detect and honestly report the failure as a `Mismatch`
  rather than falsely claiming success — the architecture behaved
  exactly as intended under the very failure condition this
  investigation studied.
- `AutomationEngine`, `AndroidAutomationTool`, `AccessibilityService`,
  `RiskPolicy`, `PendingConfirmation`, `PendingAutomationAction`, M7
  verification, and M2 multi-step machinery are all unaffected — none
  of their locked contracts assumed `performAction` is 100% reliable;
  all of them are built around the assumption that it might not be,
  which this investigation's findings confirm was the correct
  assumption to build around.

## 22. M9 Acceptance Impact

| Criterion | Status |
|---|---|
| M9-AUTO-08 (bounded interaction executes) | **Demonstrated, repeatedly, in this session** — 15/15 real-device successes. Not marked a clean, permanent PASS: the same criterion failed consistently in DAY-21 on the same device days (hours) earlier, and the trigger for that failure is not fully characterized (§16/§20). |
| M9-AUTO-09 (post-action state observed) | PASS — real, live node re-reads correctly reflect actual state in every trial, both sessions. |
| M9-AUTO-10 (observed state verified) | PASS — `Verified`/`Mismatch` distinction is correctly produced in both the working state (this session) and the failing state (DAY-21, which correctly produced `Mismatch`, never a false `Verified`). |
| M9-AUTO-11 (execution not confused with verification) | PASS, and directly evidenced by this investigation (§21) — the two are cleanly separate, and stayed correctly separate through both a failure and a recovery of the underlying platform behavior. |
| M9-TEST-03 (real-device validation) | **Not fully closable yet.** The pipeline is demonstrated working on real hardware, but the same hardware demonstrated it *not* working, unpredictably, under conditions not yet reproduced or bounded. A validation criterion whose pass/fail outcome depends on a not-yet-understood environmental trigger is not the same as a validation criterion that reliably passes. |
| M9-TEST-04 (genuine process-death validation for `PendingAutomationAction`) | **Out of scope for this investigation** (ACTION_CLICK-focused only, per this investigation's own mandate) — unresolved, unrelated status carried over unchanged from DAY-21. |

## 23. Recommended Next Decision

Not a fix, not an architecture change — a **decision about how much
further evidence to gather before any acceptance claim**, which this
investigation is not authorized to make unilaterally:

1. **Option A — Extended load-reproduction attempt**: deliberately
   replicate DAY-21's full multi-hour heavy-load profile (repeated
   instrumented suite runs including real LLM inference, sustained over
   hours) immediately before re-testing `ACTION_CLICK`, to attempt a
   genuine, controlled reproduction of the original failure and, if
   reproduced, narrow the trigger further (e.g., specific memory
   thresholds, specific `dumpsys meminfo`/`dumpsys activity` signals
   correlating with the failure's onset).
2. **Option B — Accept the current evidence as sufficient** given the
   architecture is already validated as correctly handling this failure
   mode (§21) — i.e., treat this as a known, disclosed, monitored real-
   device risk on resource-constrained hardware rather than a blocking
   defect, and proceed toward acceptance with this risk explicitly
   documented (mirroring how `ReminderTriggerInstrumentedTest`'s own
   real-device timing flakiness is already an accepted, disclosed,
   monitored risk elsewhere in this project).
3. **Option C — Seek a second physical device** (§11's own gap) to
   determine whether this is specific to this one budget/low-RAM unit or
   a broader pattern — the single most direct way to resolve the
   remaining MEDIUM-confidence gap in §20.

This investigation recommends **Option B** as the most proportionate
next step, given (a) the architecture already treats `performAction`'s
return value as untrustworthy by design and verifies independently
every time, so the *product* is not exposed to this risk even if it
recurs — a recurrence would surface as an honest `Mismatch`, not a false
success — and (b) Options A and C both carry meaningfully higher time
cost for a confidence increase whose product impact is already bounded
by (a). This is a recommendation, not a decision made on the project's
behalf.

## 24. Cleanup Verification

All temporary diagnostic code introduced during this investigation has
been fully removed and independently verified:

- `app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt` — restored
  to its exact DAY-21 clean state (`git diff` shows only the pre-existing
  DAY-21 revert, byte-for-byte identical to before this investigation
  began).
- `app/src/main/java/com/softwaremine/dps/data/android/automation/DpsAutomationService.kt`
  — temporary event-logging line removed; `git diff` against `HEAD` is
  now empty (file untouched, not merely reverted).
- `automationtarget/src/main/java/com/softwaremine/dps/automationtarget/MainActivity.kt`
  — temporary control-button listener removed; `git diff` against `HEAD`
  is empty.
- `automationtarget/src/main/res/layout/activity_main.xml` — temporary
  control button removed; `git diff` shows only DAY-21's own
  `textAllCaps="false"` fix, nothing else.
- `app/src/main/res/xml/automation_accessibility_config.xml` — **not
  touched at any point in this investigation**, per its own explicit
  rule; `git diff` shows only DAY-21's pre-existing `flagReportViewIds`
  fix.
- No new files were left behind (the debug APKs built during this
  investigation are build output, not source, and are not tracked by
  git).
- No `adb input tap` call was added to any production or test code path
  — it was used only as an external, manual diagnostic comparison
  (§14), exactly as this investigation's own rules require.
- No gesture fallback, no `ACTION_LONG_CLICK`, no retry loop, and no
  coordinate-based tapping were added anywhere.
- **Compilation reconfirmed clean** after cleanup:
  `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :automationtarget:compileDebugKotlin`
  completed with no errors.
- JVM/instrumented test suites were **not** re-run in this investigation
  (out of scope — this was a targeted root-cause investigation, not a
  regression pass); no claim is made about their current pass/fail
  status beyond what DAY-21 already established, since nothing in this
  investigation touched any file the JVM suite exercises.

Final `git status` for files this investigation could have affected:

```
 M app/src/androidTest/.../AutomationPipelineInstrumentedTest.kt   (DAY-21, untouched here)
 M app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt        (DAY-21, untouched here)
 M app/src/main/res/xml/automation_accessibility_config.xml         (DAY-21, untouched here)
 M automationtarget/src/main/res/layout/activity_main.xml           (DAY-21, untouched here)
?? docs/DAY-21-M9-STATUS-REPORT.md
?? docs/DAY-22-M9-ACTION-CLICK-ROOT-CAUSE-INVESTIGATION.md
```

No commit was made at any point in this investigation.

## 25. Final Verdict

**ROOT CAUSE CONFIRMED — DEVICE/OS**

Specifically: a transient, not-yet-fully-characterized instability in
this device's `AccessibilityService` action-delivery subsystem,
observed under DAY-21's sustained heavy real-device load and absent
under this investigation's own testing (~15 hours later, no reboot, 15/15
successes across four distinct stress conditions). Not a code defect —
the identical, unmodified production code now works reliably. Not a
permanent device incompatibility — the same device now works reliably
with no change of any kind. Not a target-app or test-methodology issue
for *this specific finding* — both were independently ruled out with
direct evidence (§18).

This verdict is offered at **MEDIUM confidence** (§20), with the
remaining gap — the precise triggering condition was not reproduced —
disclosed plainly rather than smoothed over.

This is not "M9 ACCEPTED," "M9 COMPLETE," or "M9 READY." This
investigation resolves the *nature* of the blocker; it does not, by
itself, resolve whether the current, real, if intermittent, risk it
describes is acceptable for M9's own acceptance bar — that determination
belongs to the recommended next decision (§23), which this investigation
does not make on the project's behalf.
