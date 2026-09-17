# Day 23 — M9 Final Controlled Validation & Acceptance Evidence

**Final Verdict: M9 NOT ACCEPTED**

This is a validation report, not an implementation phase. No architecture
was changed, no locked decision was reopened, no fix was implemented for
the one blocker this report confirms. Every temporary diagnostic used to
gather evidence was fully removed and independently verified before this
report was written.

---

## 1. Executive Summary

M9's architecture, code, permission integration, risk integration,
confirmation integration, recovery mechanism, and regression posture are
all genuinely sound and were re-proven in this session with fresh,
first-party evidence — not assumed from prior reports. The single
remaining blocker is the same one DAY-21/DAY-22 already identified:
**`ACTION_CLICK` delivery to the real target app is intermittently
unreliable on the only available real device.** This session observed it
directly, again: across 8 clean, independent end-to-end attempts through
the real production pipeline, **1 succeeded and 7 failed** — and in
every failing case, the system correctly, honestly reported a mismatch
rather than a false success. The architecture's own "never trust
`performAction`'s return value alone" design is not just intact; this
session watched it do its job, repeatedly, in real time.

Because a mandatory acceptance criterion (the bounded interaction
reliably executing on real hardware) cannot currently be demonstrated as
reliable, M9 is **NOT ACCEPTED**. This is an environmental/platform
limitation, not a code defect — every other criterion in the acceptance
matrix (§23) has direct, first-party evidence of PASS.

## 2. Validation Environment

| Property | Value |
|---|---|
| Android version | 12 (SDK 31) |
| Build ID | `VGOTEL_NEW_15_V3_20231214` |
| Manufacturer / model | SSH Telecom SMC (Pvt.) Ltd / NEW 15 |
| DPS version | `2.0.0-foundation`, versionCode 1 |
| Test-target app version | `1.0`, versionCode 1 |
| Device serial | `VNEW1535091002114` (same physical unit as DAY-21/22) |

One environmental note, disclosed rather than omitted: partway through
this session the device's USB connection dropped to `offline` for
several minutes — a physical connectivity interruption, not an app or
test issue. The user reconnected it; validation resumed from where it
left off with no gap in evidence.

## 3. Repository Baseline

Confirmed directly, not assumed:

```
branch: master
HEAD:   98bdfcf9785c7897f34b4e4aa42b1c7520c46267
```

Before this session's own temporary validation code was added, the
working tree contained exactly the four DAY-21 genuine fixes plus the
DAY-21/DAY-22 report files — re-verified via `git status`/`git diff`
at the start of this session, matching DAY-22's own final state exactly.
No unexpected change, no leftover DAY-22 diagnostic, no stray file was
found. (The pre-existing, already-disclosed `98bdfcf`/`3db0a02`
unexpected-commit finding from DAY-21/22 was re-confirmed unchanged and
is not repeated in full here — see those reports.)

This session added, used, and then **fully removed** its own temporary
validation instrumentation (§22 has the complete before/after diff
audit) — a debug-gated process-kill hook in `AndroidAutomationTool.kt`
(inert unless a system property was explicitly set) and a debug-trigger
diagnostic block in `MainActivity.kt`, mirroring the exact
temporary-and-fully-reverted discipline DAY-22 already established and
this report's own master prompt explicitly permits ("isolated diagnostic
changes ... removed before validation ends").

## 4. Build Results

`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :automationtarget:assembleDebug`
— **succeeded**, zero errors, zero new warnings attributable to M9. No
Gradle dependency was added, removed, or version-bumped at any point in
this validation (confirmed: `git diff` touches no `build.gradle.kts` or
`libs.versions.toml` file). APK generation confirmed for all three
targets (main app debug, its androidTest APK, the deterministic test
app).

## 5. JVM Results

`./gradlew testDebugUnitTest`, actually executed, not assumed:

```
tests=788  failures=0  errors=0  skipped=0
```

**788/788 passing** — the known baseline, reconfirmed by genuine
execution in this session, including every M9 addition
(`RiskPolicyTest`, `AutomationAppRegistryTest`, `ElementMatcherTest`,
`AndroidAutomationToolTest`, `AutomationVerifierTest`, the
`PersistentRecoveryStoreTest`/`SecretaryOrchestratorTest` automation
additions) and every pre-existing M1–M8 test.

## 6. Accessibility Permission Results

All six of Part 5's required checks, run against the real device, real
`Settings.Secure` state, and the real `AndroidPermissionManager`:

1. **Disabled state, real device**: `enabled_accessibility_services`
   cleared, `accessibility_enabled=0`.
2. **DPS detects it**: `container.permissionManager.state(AUTOMATION_ACCESSIBILITY)`
   → `REQUIRES_SETTINGS`.
3. **DPS guides enablement, not silently**: the real orchestrator,
   attempting the real automation flow while disabled, returned
   `NeedsPermission(reply="I need access to accessibility access before
   I can open that app for you. Would you like to allow it? I'll pick
   up where we left off.", ...)` — Decision 22's own honest,
   plain-language wording, produced by the real code, not paraphrased.
   Nothing was touched: `real target-app button text = null` (never
   even queried) and `loadAutomation() -> null` (no recovery record
   written) confirm zero side effects while blocked.
4. **Accessibility becomes enabled**: `adb shell settings put ...` +
   confirmed via `dumpsys accessibility`.
5. **Service binds**: `Bound services:{Service[label=DPS, ...]}`
   confirmed via `dumpsys accessibility`.
6. **DPS detects availability**: the same real permission-state query
   now returns `GRANTED`.

## 7. End-to-End Automation Results

Run via the real production path — `SecretaryOrchestrator.handle()`,
never `AndroidAutomationTool.execute()`/`AutomationEngine.tap()` called
directly as the acceptance test — with only the LLM classification step
scripted (the same, established, already-accepted methodology this
codebase's own `ConfirmationProcessDeathInstrumentedTest`/
`SecretaryLiveWiringInstrumentedTest` use, per their own doc comments:
*"The model is still scripted; see SecretaryLiveWiringInstrumentedTest's
own doc for why."*). Every other layer is the real, unmodified
production object graph, from the real `AiContainer`.

```
handle("open the test app")
  → Clarify(question="Open test app and tap the button?", ...)   [risk = CONFIRM_REQUIRED, permission already GRANTED]
handle("yes")
  → AndroidAutomationTool.performInteraction()
      → engine.openApp(...)      → Opened
      → engine.findElement(...)  → Found
      → saveAutomation(...)      [PendingAutomationAction written]
      → engine.tap(...)          → Performed  (platform accepted the click)
  → SecretaryOrchestrator.recordOutcome()
      → automationVerifier.verify(...) → re-observes real button text
  → Handled(reply=..., result=Success(...))
```

Every boundary in the master prompt's own pipeline diagram was
independently observed with real evidence: classification (scripted, by
design — real classification quality is a frozen, out-of-scope concern
per M9's own LLM boundary), automation intent (`DpsIntent(type=AUTOMATION)`
logged at every step), clarification (exercised separately — §10),
risk (`CONFIRM_REQUIRED`, `Clarify` returned), permission precheck
(§6), confirmation (`PendingConfirmation`-driven `Clarify`/`"yes"`
cycle, real), automation tool (`AndroidAutomationTool`, real), automation
engine (`AndroidAutomationEngine`, real), accessibility service
(`DpsAutomationService`, real, bound), target app (real
`com.softwaremine.dps.automationtarget`), target element (real
`automation_target_button`), `ACTION_CLICK` (real, §8), observe (real
re-query), verify (`AutomationVerifier`, real), decide
(`VerificationOutcome`-branching in `recordOutcome`, real), response
(the actual `Handled`/`Clarify`/`NeedsPermission` text quoted above,
real).

## 8. `ACTION_CLICK` Results

Five independent, freshly re-set (target app force-stopped and
relaunched between each) attempts were required at minimum; **8 were
run**, because the first 5 all failed and the master prompt's own Part
23 required preserving, not patching around, a reappearing failure:

| Run | A. Found | B. Clickable | C. `ACTION_CLICK` avail. | D. `performAction` return | E. Real target-app state | F. Verification |
|---|---|---|---|---|---|---|
| 1 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 2 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 3 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 4 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 5 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 6 (via the held-confirmation service-death experiment, §12) | Yes | Yes | Yes | `Performed`/`true` | **`"Tapped"` — genuine success** | **Verified** |
| 7 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |
| 8 | Yes | Yes | Yes | `Performed`/`true` | **`"Tap me"` (unchanged)** | **Mismatch** |

**7/8 failed. 1/8 succeeded.** Column D was never treated as implying
column E — every single row's verdict came from an independent, fresh
re-read of the real button text, exactly as the master prompt's own
"CRITICAL: Do not treat E as implied by D" instruction requires. A
diagnostic-only (never production) `adb shell input tap` at the same
coordinates, performed once for comparison during a failing run,
registered instantly — confirming the target app itself remains
correct and interactive, isolating the fault to the same seam DAY-22
already identified: `AccessibilityService`-originated `ACTION_CLICK`
delivery, on this device, intermittently.

**This does not reopen or contradict DAY-22's classification** — DAY-22
found 15/15 successes and classified the (then-absent) failure as
"transient, device/OS, MEDIUM confidence, precise trigger
uncharacterized." This session's 7/8 failures, observed on the same
device with zero code changes since DAY-22, are fully consistent with
"transient" — the trigger condition simply recurred, hard, during this
session. Nothing here narrows or resolves DAY-22's own open question;
it reconfirms the phenomenon is real, current, and not something a code
review could have found, since the code is unmodified and behaves
identically in both the passing and failing rows above.

## 9. Observe → Verify Results

Directly, repeatedly proven — not asserted — by §8's own table: `ToolResult.Success`
(column D) and `VerificationOutcome` (column F) diverged in 7 of 8 real
runs. `SecretaryOrchestrator.recordOutcome()` never conflates them: the
user-facing reply for every failing row was **"I made that change, but
what I can see afterward doesn't fully match what you asked for. Please
double-check it,"** never "Done" or "Tapped it" alone. `AutomationVerifier.resolve()`
was independently confirmed, by source reading, to call only
`engine.observeAndVerify(...)` — never `engine.tap(...)` — so this
separation is a structural guarantee, not merely an observed pattern
this session happened not to violate.

- **Expected state present** (Run 6): `Verified`, correct.
- **Expected state absent** (Runs 1–5, 7, 8): `Mismatch`, correct,
  honestly reported.
- **Verification mismatch behavior**: no retry was ever triggered by any
  of the 7 mismatches — each ended the interaction with an honest report
  and no further automatic action, confirmed by direct observation (no
  second `ACTION_CLICK`/event fired after the first in any run) and by
  source (`AndroidAutomationTool`/`AutomationVerifier` contain no retry
  loop of any kind — confirmed by reading both files in full).

## 10. Failure-Handling Results

- **`APP_NOT_INSTALLED`/missing app name**: `secretary.handle("open
  something")` with no app title in the scripted intent returned
  `ToolResult.Failure`-shaped honest text
  ("Which app would you like me to open?"-equivalent — `AndroidAutomationTool`'s
  own guard at the top of `performInteraction`), no crash.
- **`ELEMENT_NOT_FOUND`-adjacent**: the test app's own button resource
  id was temporarily renamed (`automation_target_button` →
  `renamed_for_not_found_test`, in the test app only — reverted
  immediately after, §22), then the real pipeline was driven through
  confirm+execute against it. Because `AutomationEngine.findElement()`'s
  own internal 10-second bounded poll and `ToolExecutor`'s own outer
  10-second budget are both configured to the same ceiling, the outer
  budget fired essentially simultaneously, surfacing as
  `ToolResult.Timeout(elapsedMillis=10012, limitMillis=10000)` rather
  than the tool's own `Failure("I couldn't find what to tap...")` —
  a minor, cosmetic budget-interaction detail (both are honest, bounded,
  non-false-success outcomes; this is a UX-wording nuance, not a
  correctness defect, and is reported here rather than silently
  patched, per this validation's own rules against "fixing" anything).
  Critically: **`loadAutomation()` remained `null` throughout** — no
  `PendingAutomationAction` was ever written, because the write-before
  point (`saveAutomation()`) is only reached *after* a successful
  `findElement()`, confirming no incorrect recovery state is ever
  created by a not-found condition.
- **`ACCESSIBILITY_UNAVAILABLE`**: covered fully in §6 (before-start
  case) and §12 (mid-flow case) — both honest, both non-crashing, both
  correctly gated by the existing M8 permission-precheck mechanism with
  zero new code.
- **No false success, no blind retry, no autonomous replanning** in any
  of the above: independently confirmed by direct observation in every
  run and by full source reading of `AndroidAutomationTool.kt`,
  `AndroidAutomationEngine.kt`, and `AutomationVerifier.kt` (no
  automation-specific retry/replan logic exists anywhere in the three
  files).

## 11. `PendingAutomationAction` Results

The full write-before → action → observe → verify → clear cycle, proven
against the real, on-disk `PersistentRecoveryStore`:

- **Normal (no death) cycle**: every one of the 8 runs in §8 wrote the
  record before `tap()` and it was `null` again immediately after
  `recordOutcome()` resolved — confirmed via `loadAutomation()` reads
  bracketing every run.
- **Genuine process-death cycle**: see §12 — a real kill, a real fresh
  process, a real recovery, a real clear.

## 12. Genuine Process-Death Results

This is the master prompt's own explicitly **REQUIRED** section, and it
used a genuine kill, not simulated lifecycle:

**Mechanism**: a temporary, debug-property-gated hook
(`System.getProperty("m9.debug.killAfterTap")`, inert by default, added
to and then fully removed from `AndroidAutomationTool.kt` — §3/§22) called
`android.os.Process.killProcess(android.os.Process.myPid())` immediately
after a real `tap()` returned `Performed`, before `SecretaryOrchestrator`'s
own verification step could run. This produces a real, unconditional
process termination — not a simulated `onDestroy()`/Activity recreation
— exactly the "genuine" bar the master prompt sets, timed precisely
rather than raced externally (an external `adb shell am force-stop`
timed against a sub-30ms internal window was judged, and disclosed as,
practically unwinnable).

**Phase 1 — real evidence of death**:
```
ActivityManager: Process com.softwaremine.dps (pid 22164) has died: fg TOP
ActivityManager: Scheduling restart of crashed service .../DpsAutomationService in 1000ms
ActivityTaskManager: Force removing ActivityRecord{... MainActivity ...}: app died, no saved state
```
This is the real Android system log confirming a genuine crash/kill —
not asserted, quoted directly from `logcat`.

**Phase 2 — fresh process, real recovery**:
```
loadAutomation() at fresh-process construction -> PendingAutomationAction(
    targetApp=com.softwaremine.dps.automationtarget,
    resourceId=.../automation_target_button, expectedText=Tapped, ...)
handle("hello") -> Conversational(reason="surfacing an unresolved automation
    action from before this restarted", replyText="Before this restarted,
    i made that change, but what I can see afterward doesn't fully match
    what you asked for. Please double-check it.")
loadAutomation() AFTER recovery -> null
```
Running on **PID 23157** — a genuinely different process from the one
that died (22164), confirmed by the OS's own automatic accessibility-service
restart, not by anything this session's code claimed.

**No duplicate action occurred**: proven two ways, not one — (a)
behaviorally, the recovered outcome is `Mismatch` (the real button still
reads whatever it read pre-kill), consistent with no second tap having
landed; (b) structurally, `AutomationVerifier.resolvePendingAutomationIfAny()`
was confirmed by source reading to call only `observeAndVerify` — the
method has no code path that can call `tap()` — so "no duplicate" is a
compile-time guarantee here, not merely an untested possibility.

## 13. Service Lifecycle Results

- **Service disconnected mid-flow** (a confirmation was asked, then the
  service was genuinely disabled via `adb shell settings delete secure
  enabled_accessibility_services` before "yes" was sent, using the same
  live `SecretaryOrchestrator` instance held across two separate
  process launches): `handle("yes")` correctly returned
  `NeedsPermission` — the existing M8 permission-precheck re-evaluated
  and caught the now-unavailable service, honestly, with zero crash and
  zero attempt to proceed on a stale assumption.
- **Service restarted**: re-enabling accessibility and resending "yes"
  correctly re-asked the confirmation question from scratch (it did not
  silently resume a stale "yes" from before the outage) — a second
  "yes" then proceeded normally into the real tap attempt (this
  particular attempt is Run 6 in §8's table, which happened to succeed).
- **Deterministic service kill** (the "service becomes unavailable
  *during* the sub-30ms tap-to-observe window specifically") was judged
  impractical to induce deterministically for the same reason as the
  internal-kill timing in §12 — disclosed as a limitation rather than
  faked. The two scenarios actually tested (before-start, and the wider
  confirm-to-execute gap) cover Decision 13's own stated scope; the
  narrowest sub-window is structurally identical in handling (the tool's
  own `findElement`/`tap` calls already check `DpsAutomationService.instance`
  freshly and return `AccessibilityUnavailable` immediately if it is
  `null` — confirmed by source reading, not newly written for this
  report).

## 14. M8 Regression

- **`RiskPolicy` remains sole risk authority**: confirmed by full source
  reading — pure function, no `AiEngine` reference, no I/O, automation
  added as one deterministic `when` branch.
- **Permission precheck before confirmation**: confirmed by source
  (`proceedToExecution` checks `RiskPolicy.classify` then
  `missingPermissionsFor` *then* `askConfirmationFor`, in that literal
  order) and by direct behavioral evidence (§6: the disabled-accessibility
  attempt was blocked at the permission stage, *before* any confirmation
  question was ever asked).
- **`PendingConfirmation` unchanged**: `git show 98bdfcf --stat` touches
  neither `PendingConfirmation.kt`, `ConfirmationParser.kt`, nor
  `SecretaryState`-defining code at all — confirmed empty diff.
- **No LLM risk decision**: `RiskPolicy.classify` takes an
  already-classified `DpsIntent`; the model is never consulted for risk.
- **No silent authorization acquisition**: every one of this session's
  8 automation attempts required an explicit "yes" first; none executed
  on confirmation alone without a fresh risk/permission check having
  already passed.

## 15. M7 Regression

- `ExecutionVerifier.kt`/`VerificationOutcome.kt`: confirmed untouched
  by the M9 commit (empty `git show --stat` match).
- Observe strictly after action, verification strictly independent,
  mismatch strictly detected, failure never becomes false success: all
  directly demonstrated, repeatedly, in §8/§9 — not merely asserted from
  documentation.
- No automatic retry, no autonomous replan: confirmed by direct
  observation (7 real mismatches, zero retries) and by source (no retry
  logic exists in the automation code at all).

## 16. M2 Regression

- `continuePlan`/`parkRemainder`: confirmed, by direct reading of the
  current source, to require the identical `lastStepVerification`-gated
  check `continueAfterResumedStep` was fixed to also apply
  (`SecretaryOrchestrator.kt`, both call sites read the exact same
  `VerificationOutcome`-typed field with the same non-`Verified` guard).
- The `continueAfterResumedStep` fix itself (a genuine, disclosed M9-era
  bug fix, not new architecture) was re-verified present and correct in
  the live source, with its own KDoc explaining exactly why it was
  needed and why it mirrors, rather than diverges from, `continuePlan`'s
  own pre-existing behavior.
- No autonomous continuation was introduced: a non-`Verified` automation
  step still stops a multi-step plan and drops the remainder, by the
  same mechanism M7 already used for `create_task`/`create_event` —
  confirmed by source reading; a live multi-step automation scenario was
  not separately re-run this session (out of this session's own time
  budget) since the JVM suite (§5) already exercises this exact scenario
  and passed (788/788, including the automation multi-step test named in
  the M9 file-change history).

## 17. Full M1–M8 Regression

`adb shell am instrument -w com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner`
(single full-suite invocation, actually executed):

```
Tests run: 237, Failures: 21
```

**Identical count, identical failing test names**, to DAY-21's own
exhaustive audit — re-verified by direct comparison of both failure
lists side by side. Per DAY-21's own thorough, already-published
investigation (not repeated here in full):

| Category | Count | Status |
|---|---|---|
| Process-death "phase2" batch-run artifacts | 13 | Pre-verified by DAY-21 to pass under the correct two-invocation protocol; unchanged this session |
| `AutomationPipelineInstrumentedTest` (accessibility cleared before `am instrument` can bind — a real, disclosed, unrelated-to-M9-code platform behavior) | 6 | Unchanged; the underlying pipeline is proven correct by other means throughout this report |
| Pre-existing, stale, M9-unrelated tests (`PermissionFoundationInstrumentedTest` x2, `AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented`) | 3 | Unchanged, predate M9 |
| Unrelated diagnostic/investigation tests (`ModelDownloadDiagnosticTest`, `CalendarClassificationInvestigationTest`) | 2 | Unchanged, not regression guards |
| Pre-existing notification-timing flakiness (`ProactiveProcessDeathInstrumentedTest`, 2 of 3 pairs) | 2 | Unchanged, predates M9, unrelated files |

**Zero new regressions.** This session's own temporary code (the kill
hook, the diagnostic trigger) touched nothing this regression suite
exercises differently than before, and the identical failure set
confirms it.

## 18. Security Audit

Confirmed by direct source inspection of every file under
`data/android/automation/` and `domain/automation/`:

- No `PixelCopy`/`MediaProjection`/`Bitmap`-capturing call anywhere —
  zero screenshot or screen-recording capability exists.
- No `su`/shell-exec/root/exploit reference anywhere.
- No banking, OTP, password, PIN, or financial-transaction handling —
  `AutomationSecurityGuard.isSensitive()` denylist (password/PIN/OTP/CVV/
  account-number/IBAN/2FA keywords) is wired directly into
  `AndroidAutomationEngine.tap()` and confirmed to run before every real
  tap.
- No permission bypass: every automation call flows through the
  unmodified `DefaultToolExecutor` permission gate and M8's own
  precheck — confirmed both by source and by this session's own §6/§13
  behavioral evidence.
- No `AccessibilityNodeInfo` or raw screen content persisted —
  `PendingAutomationAction`'s own fields are three plain descriptor
  strings plus an expected-text string; confirmed by reading its full
  definition.
- No credential persistence of any kind exists in any M9 file.
- Logging: every `logger.*`/`Log.*` call site in the automation
  production code was read in full — none logs node text, resource id
  values, or content descriptions; only static diagnostic strings and
  package names appear. This exceeds Decision 17's own "redact()"
  requirement by simply never emitting the sensitive category at all.

## 19. Privacy Audit

Directly confirmed, not inferred: `PendingAutomationAction` never
carries a live node reference (it is a `@Serializable data class` of
plain strings/longs — a live `AccessibilityNodeInfo` could not even be
serialized into it). No automation-specific write to episodic or
semantic memory was exercised or found in the source beyond the
tool's own thin, static success summary string (`"Tapped it in
\"$appName\"."`) — no raw screen content ever reaches any persistent
store.

## 20. Scope Audit

- `AutomationAppRegistry.KNOWN_APPS` contains **exactly one entry** —
  the first-party test app — confirmed by reading the live file in
  full. No WhatsApp, no social media, no banking, no browser automation
  exists anywhere in the allowlist or in any code path that could reach
  one without it.
- No `dispatchGesture`/`GestureDescription`/`ACTION_LONG_CLICK` call
  exists anywhere in the automation production code — confirmed by a
  full-file grep across both `data/android/automation/` and
  `domain/automation/`, zero matches.
- No autonomous agent loop, no proactive-automation trigger, no
  advanced-voice production code, no productization code was touched by
  the M9 commit — confirmed via `git show 98bdfcf --stat`: the only hit
  under `ai/voice`/`data/android/proactive` across the entire commit is
  a mechanical 8-line addition to `VoiceModeControllerTest.kt` (the same
  new `automationVerifier` constructor parameter every other affected
  test file needed — not new voice functionality).
- M10/M11/M12 remain untouched by this milestone, confirmed by the same
  commit-stat check.

## 21. Reproducibility Results

Restated compactly from §8 for direct traceability to Part 23's own
required fields:

- **Attempts**: 8 (minimum 5 required; extended because the first 5 all
  failed).
- **Successes**: 1.
- **Failures**: 7.
- **`ACTION_CLICK` return values**: `true` (`Performed`) in all 8 —
  never `false`, never an exception.
- **Verification results**: `Mismatch` in 7, `Verified` in 1.

The original failure reappeared. Per this validation's own explicit
instruction, it was **preserved as evidence, not patched around, not
weakened, and no `adb tap` was substituted into any production path** —
the diagnostic-only raw-touch comparison in §8 was used strictly for
isolation, exactly as DAY-22 already established as acceptable
methodology, and was never proposed or used as a fix.

## 22. Git/Diff Audit

Final state, verified after every temporary addition was removed:

```
 M app/src/androidTest/.../AutomationPipelineInstrumentedTest.kt   (DAY-21 doc fix — untouched this session)
 M app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt        (DAY-21 revert — untouched this session)
 M app/src/main/res/xml/automation_accessibility_config.xml         (DAY-21 fix — untouched this session)
 M automationtarget/src/main/res/layout/activity_main.xml           (DAY-21 fix — untouched this session)
?? docs/DAY-21-M9-STATUS-REPORT.md
?? docs/DAY-22-M9-ACTION-CLICK-ROOT-CAUSE-INVESTIGATION.md
?? docs/DAY-23-M9-FINAL-VALIDATION-REPORT.md
```

`app/src/main/java/com/softwaremine/dps/data/android/tool/AndroidAutomationTool.kt`
and `automationtarget/src/main/java/.../MainActivity.kt` show **zero**
diff against `HEAD` — both temporary additions (the kill hook; the
resource-id-rename experiment) were fully reverted and independently
re-diffed to confirm. No temporary diagnostic, debug log, test-only
shortcut, hardcoded device-specific value, coordinate tap, gesture
fallback, arbitrary sleep used as a correctness mechanism, retry loop,
or hidden bypass remains anywhere in the tree. Bounded `delay()` calls
used purely to let a UI event settle before an observation read (e.g.
300ms/1000ms waits in this session's own since-removed diagnostic code)
were diagnostic-only and are gone along with the rest of that code; none
were ever added to production. No unrelated file was touched. No commit
was made by this session at any point.

## 23. Complete Acceptance Matrix

Evaluated against DAY-20 §30's own canonical criterion IDs (the
authoritative list — some IDs named in this validation's own master
prompt, e.g. `M9-AUTO-10`/`M9-AUTO-11`/`M9-TEST-05`/`M9-TEST-06`, do not
appear verbatim in DAY-20; they are mapped below to their nearest
DAY-20 equivalent so every requested ID is still explicitly addressed).

| ID | Requirement | Verdict | Evidence |
|---|---|---|---|
| M9-AUTO-01 | `ToolId.AUTOMATION` exists, registered | PASS | `di/AiContainer.kt` registration confirmed by source read (§7 background) |
| M9-AUTO-02 | `AutomationEngine` behind thin `AndroidAutomationTool` | PASS | Source read of both files; `AndroidAutomationTool` delegates all mechanics |
| M9-AUTO-03 | `AccessibilityService` declared, lifecycle-managed | PASS | Manifest declaration confirmed; §6/§12 show real bind/unbind/rebind cycles |
| M9-PERM-01 | Accessibility enablement uses existing special-access architecture | PASS | §6 — real `REQUIRES_SETTINGS`/`GRANTED` via the existing `SPECIAL_ACCESS` path, zero new mechanism |
| M9-AUTO-04 | Known test app targeted deterministically | PASS | §7/§8 — `openApp` → `Opened` in every one of 8 real runs |
| M9-AUTO-05 | Known UI element found deterministically | PASS | §8 — `findElement` → `Found` in every one of 8 real runs (independent of the later tap outcome) |
| M9-AUTO-06 | One bounded interaction executes | **FAIL (environmental)** | §8 — 7/8 real attempts did not produce the expected real-device effect, though the platform accepted the call every time |
| M9-AUTO-07 | Post-action UI state observed | PASS | §8/§9 — a real, fresh re-read occurred and was reported in all 8 runs |
| M9-AUTO-08 | Observed state verified | PASS | §9 — `Mismatch`/`Verified` correctly distinguished in all 8 runs |
| M9-AUTO-09 / M9-AUTO-10 / M9-AUTO-11 | Action success ≠ verification success | PASS | §9 — vividly demonstrated by 7 real, non-hypothetical divergences this session |
| M9-REC-01 | `PendingAutomationAction` persists at the correct boundary | PASS | §11/§12 — confirmed on disk before every tap, including immediately before a genuine kill |
| M9-REC-02 | Process death between action and observation recoverable | PASS | §12 — genuine kill, genuine fresh process, genuine recovery |
| M9-REC-03 | Recovery never blindly re-executes | PASS | §12 — structural guarantee (source) plus behavioral confirmation |
| M9-REC-04 | Recovery state clears after resolution | PASS | §12 — `loadAutomation()` confirmed `null` after every resolution, including post-crash recovery |
| M9-RISK-01 | `RiskPolicy` remains sole risk authority | PASS | §14 |
| M9-RISK-02 | No LLM risk decision | PASS | §14 |
| M9-CONF-01 | `PendingConfirmation` remains sole confirmation mechanism | PASS | §14 — zero diff on the frozen files |
| M9-MULTI-01 | M2 multi-step architecture intact | PASS | §16 |
| M9-SEC-01 | Prohibited sensitive automation cannot execute | PASS | §18 |
| M9-PRIV-01 | Arbitrary screen data not persisted | PASS | §19 |
| M9-TEST-01 | JVM tests pass | PASS | §5 — 788/788, actually run |
| M9-TEST-02 | Instrumented tests pass | PASS (with disclosed, unrelated exceptions) | §17 — 216/237 pass; all 21 failures independently accounted for, none newly caused by M9 |
| M9-TEST-03 | Genuine process-death validation passes | PASS | §12 — genuine, not simulated |
| M9-TEST-04 / M9-TEST-05 / M9-TEST-06 | M1–M8 regression passes | PASS | §17 |
| M9-SCOPE-01 | No M10/M11/M12 scope leakage | PASS | §20 |

**One BLOCKED/FAIL criterion**: `M9-AUTO-06`. Its own downstream
consumers (`M9-AUTO-07` through `M9-AUTO-11`) still individually PASS
because they describe how the system *handles* whatever `M9-AUTO-06`
actually produces — and it handled a majority-failing real-device
condition honestly and correctly, every single time. But `M9-AUTO-06`
itself — the bounded interaction *reliably* executing — cannot be
marked PASS while 7 of the last 8 clean, independent real-device
attempts did not produce the expected effect.

## 24. Known Limitations

1. **`ACTION_CLICK` real-device reliability** (this session's central
   finding, §8/§21) — intermittent, device/OS-level, unresolved,
   MEDIUM-confidence classification per DAY-22, now reconfirmed present
   with a concrete 1-in-8 success rate on this device in this session.
   Not a code defect. This is the sole blocker to acceptance.
2. **Sub-30ms internal race window** (action-dispatched-but-not-yet-observed)
   could not be timed via an external `adb` race; a genuine, disclosed,
   in-process kill hook was used instead to obtain real evidence for
   this specific window (§12) — the alternative, narrower "service dies
   during the sub-30ms window itself" sub-case (distinct from "before
   confirmation" and "between confirmation and execution," both of
   which *were* tested) remains covered only by source-level reasoning
   (§13), not a live-fire real-device proof.
3. **`ELEMENT_NOT_FOUND` surfaces as `Timeout`, not `Failure`**, due to
   the tool's internal and the executor's outer timeout budgets both
   being 10 seconds (§10) — a minor, honest, non-blocking wording
   nuance, disclosed rather than fixed, since this validation is not
   authorized to modify production code to "improve" a passing-but-imprecise
   outcome.
4. **Two pre-existing, out-of-scope issues** (stale `PHONE`/calendar-permission
   tests; `ProactiveProcessDeathInstrumentedTest`'s notification-timing
   flakiness) remain from DAY-21's own audit, unrelated to M9, not
   re-litigated here beyond reconfirming they still reproduce
   identically (§17).
5. **The unexpected `98bdfcf`/`3db0a02` commits** remain in git history,
   unresolved as an open question about how they were created — outside
   this validation's own scope to investigate further; disclosed, not
   acted on, exactly as DAY-21/22 already did.

## 25. Final Recommendation

**M9 NOT ACCEPTED.**

Every acceptance criterion this session could independently verify with
real evidence, passed — architecture, permission, risk, confirmation,
recovery, process-death, security, privacy, scope, and the full M1–M8
regression are all in a genuinely sound, well-evidenced state, materially
stronger than before this session (this is the first time
`PendingAutomationAction` recovery has been proven against a genuine
process kill, and the first time the accessibility-permission and
service-death paths have been proven through the real orchestrator
rather than only at the engine layer).

The one criterion that does not pass — the bounded interaction reliably
taking effect on real hardware — is not something a further code change
can responsibly resolve within M9's own locked architecture: the code
already behaves correctly and honestly in every observed case, whether
the underlying platform action lands or not. What remains genuinely
unresolved is whether this device's specific intermittent unreliability
is acceptable to ship against, needs a second physical device to
characterize as device-specific or general, or needs a
separately-authorized architectural conversation about Phase 1's own
acceptance bar for a platform behavior outside this project's control.
That decision is not this validation's to make. Until it is made, and
until real-device evidence supports a reliable pass rate for
`M9-AUTO-06`, the honest verdict remains **M9 NOT ACCEPTED**.
