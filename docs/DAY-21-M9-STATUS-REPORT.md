# Day 21 — M9 Status Report (Controlled Android Device & App Automation)

**Verdict: M9 NOT ACCEPTED**

This is a status report, not a completion document, per the M9 master
prompt's own instruction: `docs/DAY-21-M9-COMPLETION.md` is created only if
every acceptance criterion is genuinely satisfied. One is not, for a
reason documented in detail below. Everything else — architecture, code,
JVM tests, and most of the real-device pipeline — is genuinely complete
and verified.

---

## 1. The blocker

**`AccessibilityNodeInfo.performAction(ACTION_CLICK)` does not reliably
deliver the click to the target app on the only real device available for
this milestone's validation**, even though the framework call reports
success. This is Decision 20's own real-device validation item covering
"the action genuinely occurs" — and it cannot currently be demonstrated.

### Evidence (real device, `am start`-launched processes, never
`am instrument`)

1. `findElement()` locates the real button via the exact, correct
   `resourceId` (`com.softwaremine.dps.automationtarget:id/automation_target_button`),
   confirmed against a genuine `uiautomator dump` of the live screen.
2. The located node reports `isClickable=true`, correct `className`
   (`android.widget.Button`), and `ACTION_CLICK` present in its own
   `actionList`.
3. `rawNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)` returns
   `true`.
4. The button's displayed/accessibility text never changes from
   `"Tap me"` to `"Tapped"` — confirmed via a fresh `uiautomator dump`
   taken seconds after the tap, with no timing pressure.
5. A second, unrelated action — `ACTION_ACCESSIBILITY_FOCUS` — on the
   same node, at the same moment, more honestly reports failure
   (`false`), suggesting the underlying action-delivery pathway to this
   third-party app's window is genuinely broken at the OS/device level,
   not merely a timing race.
6. A real touch, `adb shell input tap <button's screen coordinates>`,
   **does** register — the button visibly changes to `"TAPPED"`
   (all-caps, before a separate fix — see §2.3) — proving the target
   app's own click handling is correct and the button truly is
   interactive.

Together this isolates the failure to one specific seam:
`AccessibilityService`-originated `performAction(ACTION_CLICK)` calls
against a different app's window, on this device. Everything upstream
(service binding, permission integration, app launching, UI tree
observation, deterministic element finding, mismatch verification) is
proven correct on the same device, in the same diagnostic run.

### Why this isn't silently worked around

M9's own locked architecture (Architectural Decision on the action
vocabulary) commits to `performAction(ACTION_CLICK)` as Phase 1's *only*
action mechanism; `canPerformGestures` is deliberately `false`, gesture
dispatch (`dispatchGesture`) explicitly deferred to a later, not-yet-
authorized phase. Routing around the broken `ACTION_CLICK` path by
switching to gesture dispatch would mean reopening that locked decision —
which the master prompt's own rule requires to `STOP and report` rather
than silently resolve. So the implementation stays as designed; this is
reported as a blocker instead.

Whether this is specific to this one physical device (a budget/white-
label unit — manufacturer string `SSH Telecom SMC (Pvt.) Ltd`, model `NEW
15`, brand `VGOTEL`, Android 12 / API 31, MediaTek-based) or a broader
platform characteristic is unknown — only one physical device was
available for this milestone's validation, per the project's own
device-only testing policy (ADR-009, no emulators/Robolectric).

---

## 2. Genuine fixes made during this diagnosis

Real-device testing surfaced three actual bugs, all fixed and verified:

### 2.1 `AutomationPipelineInstrumentedTest` was misdiagnosed, now corrected

Earlier real-device work (this same milestone) concluded that
`am instrument`-driven processes never receive the `AccessibilityService`
bind. That conclusion was **wrong**. The real cause, found this session:

> Force-stopping the app that owns an enabled `AccessibilityService` —
> `adb shell am force-stop com.softwaremine.dps` — clears both
> `enabled_accessibility_services` and, when it was the only enabled
> service, `accessibility_enabled` itself. Confirmed directly via
> `dumpsys accessibility` before/after an isolated force-stop, with no
> other action in between.

This is genuine, intentional Android behavior (a force-stop revokes the
app's active accessibility binding), not a DPS defect. Standard
instrumented-test execution (`connectedAndroidTest`/`am instrument`)
force-stops the target app as part of its own setup, before any test's
`@Before` runs — so the service can never be bound by the time a test
body executes, regardless of what the test itself does. This is why
`AutomationPipelineInstrumentedTest`'s 6 tests still fail today, and why
they structurally cannot pass via the standard test runner on this
device. The class's own doc comments have been corrected to state this
accurately (`app/src/androidTest/.../AutomationPipelineInstrumentedTest.kt`).

The pipeline this class exercises was instead proven correct through a
temporary, disclosed diagnostic: a debug-only trigger added briefly to
`MainActivity.kt`, invoked via a normal `adb shell am start` launch
(which does not force-stop DPS), driving the real
`AiContainer.automationEngine` against the real
`com.softwaremine.dps.automationtarget` app, observed via logcat and
`uiautomator dump`. The diagnostic code has been fully removed from
`MainActivity.kt` after use — the file is byte-for-byte back to its
pre-M9 state.

### 2.2 Missing `flagReportViewIds` — a real implementation bug, fixed

`app/src/main/res/xml/automation_accessibility_config.xml` declared
`android:accessibilityFlags="flagRetrieveInteractiveWindows"` only.
Without `flagReportViewIds`, `AccessibilityNodeInfo.getViewIdResourceName()`
always returns `null` for every node — meaning `ElementMatcher`'s
resourceId-based matching (the locked, primary targeting strategy) could
never succeed, regardless of the real tree's content. This fully
explained an earlier `findElement()` timeout that had been mistakenly
attributed to a foreground-transition race.

**Fixed**: `android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"`,
with the reasoning recorded in the file's own comment. Verified
end-to-end on the real device: `findElement()` now returns `Found`
immediately, and `observeAndVerify()` correctly reports `Mismatch`/
`Verified` against live button text.

### 2.3 Test target's button rendered all-caps, contradicting its own doc

`automationtarget`'s `activity_main.xml` used a plain `Button`, which
applies the platform's default `textAllCaps` style — so the real,
accessibility-visible text read `"TAP ME"`/`"TAPPED"`, silently
diverging from `MainActivity.kt`'s own documented `"Tap me"`/`"Tapped"`
strings. Confirmed via `uiautomator dump` before the fix.

**Fixed**: added `android:textAllCaps="false"` to the button. The
deterministic test app now genuinely shows the text its own
documentation promises.

---

## 3. Test results

### 3.1 JVM (`./gradlew testDebugUnitTest`)

**788/788 passing**, including all M9 additions (`RiskPolicyTest`,
`AutomationAppRegistryTest`, `ElementMatcherTest`,
`AndroidAutomationToolTest`, `AutomationVerifierTest`,
`PersistentRecoveryStoreTest`'s automation additions,
`SecretaryOrchestratorTest`'s automation additions including the
`continueAfterResumedStep` bug-fix regression test) and every pre-existing
M1–M8 JVM test, unaffected by M9's changes.

### 3.2 Instrumented — full suite, single `am instrument` invocation

237 tests, 21 failures. Every one investigated individually; none is a
genuine, previously-undisclosed M9 regression:

| Category | Count | Finding |
|---|---|---|
| Process-death "phase2" tests | 13 | **Batch-run artifact**, not a defect. Verified: all 13 pass when re-run with the established two-invocation protocol (a genuine `am force-stop` between a pair's phase1 and phase2 — and, for classes with multiple phase1/phase2 *pairs*, one pair fully isolated per invocation sequence, not bundled). Classes: `SecretaryExecutionRecoveryInstrumentedTest` (2 pairs), `ConfirmationProcessDeathInstrumentedTest` (1 pair), `CheckpointRecoveryInstrumentedTest` (3 pairs), `ExecutionVerifierProcessDeathInstrumentedTest` (1 pair, 2 sub-cases), `ProactiveProcessDeathInstrumentedTest` (1 of 3 pairs). |
| `AutomationPipelineInstrumentedTest` | 6 | The disclosed, understood blocker in §1/§2.1 — accessibility enablement is cleared before the test body runs. Not a regression; the underlying pipeline is proven correct by other means (§2.1). |
| `PermissionFoundationInstrumentedTest` | 2 | **Pre-existing, stale test**, unrelated to M9. Both tests assert a "Phase 1"/"Day 02"-era premise ("calendar permissions are undeclared") that the manifest has since outgrown — `READ_CALENDAR`/`WRITE_CALENDAR` are genuinely declared and granted on this device. Confirmed via `AndroidManifest.xml` and `dumpsys package`. Predates M9. |
| `AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented` | 1 | **Pre-existing, stale test**, unrelated to M9. Asserts `PHONE` is not yet implemented, but `AndroidCallTool` (`id = ToolId.PHONE`) has been a real implementation since the `508d6d1 "Calendar reminder coding"` commit — long before M9. The test was never updated. Reproduces even in full isolation. |
| `ModelDownloadDiagnosticTest`, `CalendarClassificationInvestigationTest` | 2 | Special-purpose, heavy diagnostic/investigation tests (real model download, real LLM inference) from earlier milestones' own investigation phases — not regression guards, unrelated to M9. |
| `ProactiveProcessDeathInstrumentedTest`, 2 of 3 pairs | 2 (of the 13 above, re-attributed) | On closer isolation, these 2 pairs (notification-posting / upcoming-calendar-event timing) **do fail even when run correctly, in full isolation** — a real, reproducible, but pre-existing issue unrelated to any file M9 touched (proactive assistant uses its own preference store, not `PersistentRecoveryStore`). Same category as the already-disclosed (M8) `ReminderTriggerInstrumentedTest` alarm-delivery timing failure: real-device notification/alarm timing flakiness. Not fixed, per M8's own precedent of disclosing rather than fixing issues outside the current milestone's scope. |

Net: the *only* finding that is both new and M9-scoped is the
`ACTION_CLICK` delivery blocker in §1. Everything else is either a
verified batch-run artifact or a pre-existing issue that predates M9.

---

## 4. What is genuinely proven, on the real device, right now

- `DpsAutomationService` binds and connects correctly once accessibility
  is enabled and the app is genuinely foregrounded (never via
  `am instrument`) — confirmed via `dumpsys accessibility` and logcat's
  `DpsAutomationService connected`.
- `AndroidPermissionManager.specialAccessState()` correctly reports
  `AUTOMATION_ACCESSIBILITY`'s state by reading
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
- `AndroidAutomationEngine.openApp()` correctly launches the real target
  app and confirms it reached the foreground.
- `AndroidAutomationEngine.findElement()` correctly locates the real,
  deterministic button by `resourceId` against the live accessibility
  tree (post §2.2 fix).
- `AndroidAutomationEngine.observeAndVerify()` correctly distinguishes
  `Mismatch` from `Verified` against live, real button text (post §2.3
  fix).
- `AndroidAutomationTool`, `AutomationVerifier`, `PendingAutomationAction`,
  `RiskPolicy`'s automation branch, and `SecretaryOrchestrator`'s
  automation dispatch/confirmation/recovery paths are all exhaustively
  JVM-tested against the real production classes (fakes only replace
  `AutomationEngine`, exactly mirroring M7/M8's own established pattern).
- The `continueAfterResumedStep()` bug (a genuine, previously-latent gap
  where a parked multi-step plan could resume past an unverified
  automation step) is fixed and covered by a regression test.

## 5. What is not proven

- The tap action itself taking effect end-to-end on real hardware (§1).
- Process-death recovery specifically for `PendingAutomationAction` on a
  real device (Phase 17/20D) — not attempted this session; blocked
  behind §1 in practice, since a meaningful process-death scenario for
  automation requires a tap to have actually occurred first.
- Service-death/disconnect real-device validation (Phase 20E) — not
  attempted this session, for the same reason.

---

## 6. An unexpected commit — disclosed, not acted on

While reviewing `git diff` to confirm the diagnostic cleanup was clean,
a commit was found on `master` that this session did not make:

```
commit 98bdfcf9785c7897f34b4e4aa42b1c7520c46267
Author:     ziauddin14 <ziamaryam2526@gmail.com>
AuthorDate: Thu Sep 17 00:20:21 2026 +0500
Subject:    M9 start and come stages are completed
```

It captures essentially all of M9's work-in-progress at that moment —
including, in `MainActivity.kt`, the temporary diagnostic trigger this
session added and later removed (§2.1). This mirrors the earlier,
already-disclosed `3db0a02 "some random commit"` from M8: authored by the
real account owner, not by this session, with an informally-phrased
message. No commit has been made by this session at any point; per
standing instructions, none will be, unless asked. The current working
tree sits, uncommitted, on top of `98bdfcf` and contains the real, final
state — diagnostic code removed, both genuine fixes (§2.2, §2.3) applied,
test documentation corrected.

Worth asking about directly: something (an IDE auto-commit setting, a
git hook, or manual action) is committing mid-session work under this
account without this session's involvement, twice now across two
consecutive milestones.

---

## 7. Git / worktree state

- Branch: `master` (up to date with `origin/master`).
- No commits made by this session.
- Unstaged changes (4 files, all reviewed above):
  - `app/src/androidTest/.../AutomationPipelineInstrumentedTest.kt` — doc corrections only (§2.1).
  - `app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt` — temporary diagnostic fully removed; identical to its pre-M9 content.
  - `app/src/main/res/xml/automation_accessibility_config.xml` — `flagReportViewIds` fix (§2.2).
  - `automationtarget/src/main/res/layout/activity_main.xml` — `textAllCaps="false"` fix (§2.3).
- `app/src/main/java/com/softwaremine/dps/data/android/automation/AndroidAutomationEngine.kt` — touched repeatedly during diagnosis, confirmed byte-for-byte identical to its committed state afterward (`git diff` empty).

---

## 8. Blockers to reaching "M9 ACCEPTED"

1. **Primary blocker**: the real-device `ACTION_CLICK` delivery issue
   (§1). Needs either (a) access to a second physical device to
   determine whether this is device-specific, or (b) a deliberate,
   separately-authorized architectural decision about how to proceed if
   it is not device-specific (this session will not make that call
   unilaterally, per the master prompt's own rule on reopening locked
   decisions).
2. Phases 17 (process-death recovery for `PendingAutomationAction`) and
   20D/20E (process-death and service-death real-device validation) are
   untested, practically blocked behind the above.
3. Phase 21's full regression sweep is complete in substance (§3) but
   surfaced two pre-existing, out-of-scope issues worth a maintainer's
   attention eventually: the stale `PHONE`/calendar-permission tests
   (§3, row 3–4) and the notification-timing flakiness in two
   `ProactiveProcessDeathInstrumentedTest` pairs (§3, last row) — neither
   blocks M9 itself, both predate it.

Everything else in the master prompt's 21-phase plan is genuinely
complete: architecture, production code, JVM tests, permission and risk
integration, multi-step integration (including the `continueAfterResumedStep`
fix), the deterministic test app, and the read-side of the real-device
pipeline (bind, observe, find, verify).
