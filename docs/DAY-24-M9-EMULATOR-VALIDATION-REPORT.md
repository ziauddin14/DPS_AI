# Day 24 — M9 Emulator Cross-Environment Validation

**This is a validation/investigation report. No fix was implemented, no
locked architecture was changed. M9 status is unaffected by this report
alone — see §15.**

---

## 1. Objective

Determine whether M9's `ACTION_CLICK` anomaly (DAY-21/22/23's own finding
of intermittent real-device failure) follows the device, the code, the
target app, or the test methodology, by running the identical
implementation against a second, independent Android environment — a
locally-installed Android Emulator — and comparing results directly
against the physical device's own already-documented evidence.

## 2. Source Documents

DAY-18 through DAY-23 were treated as authoritative and re-verified
against the live repository before this session's own work began (not
re-summarized here in full — see those reports for their own content).
DAY-19's 23 locked decisions were not reopened. DAY-20's acceptance
criteria were not altered.

## 3. Repository Baseline

Confirmed at the start of this session:

```
branch: master
HEAD:   98bdfcf9785c7897f34b4e4aa42b1c7520c46267
```

Working tree matched DAY-23's own final state exactly — the four
DAY-21 genuine fixes plus the DAY-21/22/23 report files, nothing else.
No temporary diagnostic, no `adb input tap` workaround, no gesture
fallback existed anywhere in the tree before this session's own,
later-added-and-removed, diagnostic code.

This session added, used, and **fully removed** its own temporary
validation instrumentation — a debug-trigger block in `MainActivity.kt`,
mirroring DAY-22/23's own established, disclosed, fully-reverted
methodology exactly (same file, same `-e m9_validate <action>` pattern,
same guarantee of zero production-code changes surviving past this
session). §17 documents the complete before/after diff audit.

## 4. Emulator Environment

A locally-installed Android Studio (`C:\Program Files\Android\Android
Studio`) was found on this machine, but its Emulator component and every
system image had never actually been downloaded — the master prompt's
own assumption that "you have Android Studio and an Android Emulator
available" was only half true at the start of this session. The
`emulator` package (37.1.11) and a matching system image were installed
via `sdkmanager` before any device work could begin (~1.1GB + ~1.4GB
download).

| Property | Value |
|---|---|
| AVD name | `M9_Validation_API31` |
| Device profile | Pixel 5 (Google) |
| Android version | 12 ("S") |
| API level | 31 — **exact match** to the physical device |
| Architecture | x86_64 |
| Image type | Google APIs (not Google Play) |
| Build fingerprint | `sdk_gphone64_x86_64-userdebug 12 SE1A.220826.008 10564458 dev-keys` |
| RAM | ~2.0 GB total (`MemTotal: 2015108 kB`) — smaller than the physical device's ~3.9GB |
| Hardware backend | `ranchu` (standard AOSP emulator), x86_64 hardware-accelerated (Windows Hypervisor Platform detected present on the host) |

API level was deliberately matched exactly to the physical device (12/31)
to isolate environment differences from OS-version differences.

## 5. Accessibility Permission Results

Run identically to DAY-23's own physical-device methodology, via the
real `AndroidPermissionManager`:

1. **Disabled state**: confirmed (`enabled_accessibility_services=null`,
   `accessibility_enabled=0`) — this is the emulator's own genuine
   default; nothing was pre-configured.
2. **DPS detects unavailable**: `state(AUTOMATION_ACCESSIBILITY)` →
   `REQUIRES_SETTINGS` — identical output shape to the physical device.
3. **Accessibility enabled** via the real `Settings.Secure` keys.
4. **Service binds**: confirmed via `dumpsys accessibility` →
   `Bound services:{Service[label=DPS, ...]}`.
5. **DPS detects availability**: `state(...)` → `GRANTED`.

**One notable environment difference**: on the physical device
(DAY-21/22), binding required a specific "toggle" sequence
(`accessibility_enabled` off→on) to force the OS's settings observer to
refresh — a bare `enabled_accessibility_services` write while already
enabled did not reliably rebind. On the emulator, the very first,
single write sequence bound the service immediately, no toggle-retry
needed, every time this was tried. This is a genuine, observed
environment difference in OS/settings-observer behavior, not something
this investigation set out to look for.

## 6. Target App Results

`com.softwaremine.dps.automationtarget`/`MainActivity` launched and
inspected via the real accessibility tree:

```
class=android.widget.Button pkg=com.softwaremine.dps.automationtarget
id=.../automation_target_button text=Tap me desc=null
clickable=true enabled=true visible=true
bounds=Rect(419, 1187 - 661, 1319)
actions=[ACTION_FOCUS, ACTION_SELECT, ACTION_CLEAR_SELECTION,
         ACTION_CLICK, ACTION_ACCESSIBILITY_FOCUS, ...]
```

Identical shape to every physical-device reading in DAY-21/22/23 —
same class, same resource id, same deterministic initial text, same
action list. The test app itself was **not modified** in this session.

## 7. Direct `ACTION_CLICK` Results

A controlled, direct `performAction(ACTION_CLICK)` call (bypassing the
production tool/orchestrator layers, but using the real
`AndroidAutomationEngine`'s own `openApp`, and a raw
`findAccessibilityNodeInfosByViewId` read before/after — mirroring
DAY-22's own physical-device methodology), with a 500ms settle pause
after `openApp` and staged reads at +500ms/+2000ms:

| Run | `performAction` return | Actual state @+500ms | Actual state @+2000ms |
|---|---|---|---|
| 1 | `true` (45ms) | `Tapped` | `Tapped` |
| 2 | `true` (51ms) | `Tapped` | (not separately re-read) |
| 3 | `true` (21ms) | `Tapped` | (not separately re-read) |
| 4 | `true` (22ms) | `Tapped` | (not separately re-read) |
| 5 | `true` (91ms) | `Tapped` | (not separately re-read) |

**5/5 succeeded**, verified by real state, not by the return value alone
(per this validation's own explicit rule). `performAction`'s own latency
(21–91ms) is broadly comparable to, if somewhat more variable than, the
physical device's own typically-tighter 13–22ms range from DAY-22.

## 8. Production E2E Results

This section required two attempts, because the first attempt's own
test methodology was flawed and produced misleading data — disclosed in
full rather than only reporting the corrected numbers.

### 8.1 First attempt (methodology flaw found and corrected)

The initial 5-run sequence manually pre-launched the target app before
triggering the real pipeline (`SecretaryOrchestrator.handle("open the
test app")` → `"yes"`). This produced a striking, novel symptom never
seen on the physical device: the button briefly read `"Tapped"`
immediately after the tap, then reverted to `"Tap me"` roughly 1.5–2
seconds later, with no further tap of any kind. Direct `logcat`
evidence traced the cause precisely:

```
START u0 {...cmp=com.softwaremine.dps.automationtarget/.MainActivity} from uid 2000      <- my own manual pre-launch
Displayed .../.MainActivity: +1s396ms
START u0 {act=MAIN cat=[LAUNCHER] pkg=com.softwaremine.dps.automationtarget ...} from uid 10145   <- AndroidAutomationEngine.openApp()'s OWN call
engine.tap() -> Performed                                                                <- lands on the FIRST (already-open) instance
real target-app button text = Tapped                                                     <- correct, for that instance
Displayed .../.MainActivity: +964ms                                                      <- the SECOND (redundant) instance finishes drawing...
real target-app button text = Tap me                                                     <- ...and visually replaces the first, showing its own fresh "Tap me"
```

`AndroidAutomationEngine.openApp()` unconditionally calls
`context.startActivity()` on every invocation (confirmed by reading its
source — it does not check whether the target package is already
foreground before launching). The test target's own manifest declares
no `android:launchMode` (defaults to `"standard"`), so a second
`startActivity()` call to an already-open instance creates a **second,
independent Activity instance stacked on top of the first**, rather than
reusing it. The tap landed correctly on the first, already-tapped
instance; that instance was then visually superseded once the redundant
second instance finished its own (slower, ~1 second) draw — which, being
freshly created, naturally showed the default `"Tap me"` text.

**This artifact is specific to this test's own methodology** (manually
pre-launching the target app before invoking the pipeline) and does
**not** occur in real production usage — a real user's automation
request never has the target app already open beforehand; `openApp()`
is always the sole launcher. Confirmed directly: repeating the identical
direct-engine test **without** the manual pre-launch (`openApp()` as the
only launcher) produced **5/5 clean, stable successes**, immediate and
at +1.5s, no revert, across five independent repetitions.

### 8.2 Corrected E2E run (no pre-launch, matching real usage)

Five independent runs, target app only force-stopped (never manually
relaunched) before each, through the real
`SecretaryOrchestrator.handle()` → `"yes"` pipeline:

| Run | Immediate re-read (this session's own diagnostic) | Production's own `Handled` reply | Production verification |
|---|---|---|---|
| 1 | `Tapped` | `"Tapped it in \"test app\"."` | `Verified` |
| 2 | `Tapped` | `"I made that change, but what I can see afterward doesn't fully match..."` | `Mismatch` |
| 3 | `Tapped` | `"I made that change, but what I can see afterward doesn't fully match..."` | `Mismatch` |
| 4 | `Tapped` | `"I made that change, but what I can see afterward doesn't fully match..."` | `Mismatch` |
| 5 | `Tapped` | `"Tapped it in \"test app\"."` | `Verified` |

**Every single run's underlying tap genuinely succeeded** (confirmed:
this session's own immediate re-read, using
`findAccessibilityNodeInfosByViewId`, showed `"Tapped"` in all 5 runs,
with zero exceptions) — yet the production pipeline's own internal
verification reported `Mismatch` in 3 of 5. `loadAutomation()` correctly
returned `null` after every resolution regardless of outcome (M9-REC-04
holds). No false success occurred in any run; every `Mismatch` was
reported honestly. This divergence — genuine success, but the
*production verification path specifically* sometimes reporting
otherwise — is investigated directly in §9.

## 9. `observeAndVerify()` Isolation (new finding, not present in DAY-21/22/23)

The divergence in §8.2 was isolated to one exact cause. Two node-query
mechanisms exist side by side in this codebase:

- `AutomationVerifier`/`AndroidAutomationEngine.observeAndVerify()` —
  the real, production verification path — resolves a node via
  `ElementMatcher.find()`, which recurses through
  `AccessibilityNodeInfoNode.children`, itself backed by
  `AccessibilityNodeInfo.getChild(index)`.
- A direct `AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId(...)`
  call — used only by this session's own diagnostic tooling, never by
  production code.

Calling **both**, back to back, immediately after the same real tap,
five independent times:

| Run | `observeAndVerify()` (production, `getChild()` path) | Direct `findAccessibilityNodeInfosByViewId` (same instant) |
|---|---|---|
| 1 | `Mismatch(observed="Tap me")` | `Tapped` |
| 2 | `Mismatch(observed="Tap me")` | `Tapped` |
| 3 | `Mismatch(observed="Tap me")` | `Tapped` |
| 4 | `Mismatch(observed="Tap me")` | `Tapped` |
| 5 | `Mismatch(observed="Tap me")` | `Tapped` |

**5/5.** The real, live UI state was already `"Tapped"` in every case
(proven by the direct query succeeding at the identical instant) — the
production verification path's own **first** query, specifically,
reads a stale, pre-tap snapshot every single time on this emulator.

A follow-up test — calling `observeAndVerify()` itself a **second** time,
immediately, with zero added delay, then a **third** time after +300ms —
across 3 repetitions:

| Repetition | Call #1 (immediate) | Call #2 (immediate, no delay) | Call #3 (+300ms) |
|---|---|---|---|
| 1 | Mismatch | **Verified** | Verified |
| 2 | Mismatch | Mismatch | **Verified** |
| 3 | Mismatch | **Verified** | Verified |

The **first** call after a tap was stale in all 3 repetitions; by
+300ms, every repetition had self-corrected, with no additional action
of any kind — this is a genuine, reproducible caching/propagation
window in the accessibility node-query path, not a persistent or
worsening failure, and not something a retry would need to run more
than once or twice to clear.

**This is offered as a mechanistic explanation candidate, not a fix.**
No change was made to `observeAndVerify()`, `ElementMatcher`, or any
other production file to address this — per this investigation's own
absolute rule against adding retry logic or altering the verification
path merely to make results look better.

## 10. Control View Results

DAY-22's own physical-device control-view evidence (a second, plain
`Button` in the same test app, behaving identically to the real target
button) was judged sufficient and was **not** re-run here — per this
investigation's own explicit instruction not to modify the test
application again unless genuinely necessary. Nothing in this session's
own findings implicates the target view's own implementation (§9's
finding is about the *query mechanism*, not the view being queried) —
a control view would be expected to, and by the underlying mechanism's
own nature would, exhibit the identical `getChild()`-staleness
characteristic, since it is a property of the query path, not of any
one specific node.

## 11. Physical Device Comparison

| Property | Physical Device (DAY-21/22/23) | Emulator (this session) |
|---|---|---|
| Android Version | 12 | 12 |
| API Level | 31 | 31 |
| Device/AVD | VGOTEL NEW 15 (MediaTek Helio P22-class) | `M9_Validation_API31` (Pixel 5 profile, x86_64) |
| Accessibility Binding | Required a specific enable/disable toggle sequence to reliably rebind | Bound on the first, direct enable sequence, every time tried |
| Target Discovery | `findElement` → `Found`, consistently, across all sessions | `findElement` → `Found`, consistently |
| `ACTION_CLICK` Available | Yes, every trial across all sessions | Yes, every trial |
| `ACTION_CLICK` Return | `true`, every trial across all sessions | `true`, every trial |
| Actual UI Change (direct engine test) | DAY-22: 15/15 changed; DAY-23: 7/8 did not change | 5/5 changed (with pre-launch removed) |
| Actual UI Change (production E2E, verification's own read) | DAY-23: 7/8 `Mismatch` | 5 runs: 3/5 `Mismatch`, 2/5 `Verified` — **but the real UI state itself was correct in 5/5**, confirmed independently |
| Successful Runs (direct engine) | DAY-22: 15/15 | 5/5 |
| Failed Runs (direct engine) | DAY-23: 7/8 | 0/5 (once the pre-launch artifact was removed) |

## 12. Evidence Table

| Hypothesis | Physical Evidence | Emulator Evidence | Combined Assessment |
|---|---|---|---|
| Code defect | DAY-22: architecture validated; verification never confused with execution | §9: production `observeAndVerify()`'s **first** call reads stale state, deterministically, 5/5, on this environment — a real, reproducible characteristic of the current implementation's single-immediate-query design | **Newly, directly supported** — not a defect in the verification *logic* (it always compares correctly against whatever it reads), but in the *timing assumption* that a single, immediate re-query is sufficient |
| Accessibility configuration | DAY-22: audited, no plausible link found | Not re-audited (no evidence pointed here); the same config was used throughout | Not supported |
| Target view | DAY-22: control button behaved identically; raw touch always worked | §6: raw button read/click behavior identical to physical device; §10: control view judged unnecessary given §9's mechanism-level (not view-level) finding | Not supported |
| Node targeting | DAY-22: correct node found in every trial | §6/§7: correct node found in every trial, including via two independent query APIs | Not supported |
| Accessibility focus | DAY-22: `ACTION_ACCESSIBILITY_FOCUS` fails but shown unrelated to `ACTION_CLICK` | Not re-tested (DAY-22's own correction already stands, unchallenged) | Not supported (already resolved in DAY-22) |
| Timing / asynchronous rendering | DAY-21/23: intermittent failures with no code change, hours apart | §8.1: a genuine, distinct timing artifact found (redundant-relaunch race) explained a novel symptom, then ruled out for production usage; §9: a *separate*, real, mechanistic timing/caching race directly explains the production verification path's own intermittent `Mismatch` reports, 5/5 reproducible | **Strongly, mechanistically supported** — this session found and precisely characterized a real timing race in the verification path itself |
| Device/OS behavior (DAY-22's own classification) | DAY-22: transient, MEDIUM confidence, trigger uncharacterized; DAY-23: reconfirmed present | The *same symptom* (verification reports failure despite a genuinely successful tap) reproduces on a completely different, generic, virtualized x86_64 environment — via a *specific, understood mechanism*, not an unexplained device quirk | **Weakened, not eliminated** — a device-specific OS bug is no longer the only, or even the best-supported, explanation for this symptom; a general Android accessibility-caching characteristic explains it without needing to invoke anything specific to the physical device's own chipset or OEM fork |
| Test methodology | — | §8.1 found and disclosed a genuine methodology flaw in this session's own first attempt (manual pre-launch → duplicate stacked Activity instances) | **Confirmed as a real, but narrow, contributing factor** — for this session's own diagnostic only; ruled out for production usage specifically, since `openApp()` is always the sole launcher in real DPS usage |

## 13. Root-Cause Assessment

The evidence gathered this session **does not overturn** DAY-22's own
finding that the physical device showed genuine, unexplained
intermittency with zero code changes — that evidence stands on its own
terms. What this session adds is a **second, independently-verified,
mechanistically-understood contributing factor**, found via the real
production code path on a different environment: `observeAndVerify()`'s
own first post-action query is subject to a real, short-lived (self-
resolving within ~300ms in every observed instance), well-known class of
Android accessibility node-caching lag. This factor alone is sufficient
to explain the exact symptom (`Performed`/`true` but `Mismatch`) this
session observed in 3 of 5 clean E2E runs, with the real UI state
independently confirmed correct in all 5.

Whether this same mechanism explains *all*, *some*, or *none* of the
physical device's own historical failures (DAY-21's original finding,
DAY-23's 7/8) cannot be determined from this session's own evidence
alone — the physical-device sessions did not isolate the two query
mechanisms the way this session did. It is a plausible, evidence-
consistent, but not proven, unifying explanation.

## 14. Architecture Impact

**NO ARCHITECTURE CHANGE WAS MADE, AND NONE IS PROPOSED BY THIS REPORT.**

No locked decision (DAY-19) is contradicted. `RiskPolicy`,
`PendingConfirmation`, M7 verification's own vocabulary,
`ACTION_CLICK` as the sole action mechanism, and the "action success ≠
verification success" design are all **directly reconfirmed correct**
by this session's own evidence (§8.2's honest `Mismatch` reports are the
architecture doing exactly what Decision 10 requires — see also §13 of
DAY-23, restated, not repeated). If a future, separately-authorized
decision chooses to address the timing race this session characterized
in §9 (for example, allowing the verification step a brief settle
window before its first read), that is an implementation-detail
decision within the already-locked architecture, not a reopening of
any decision — this report does not make that decision, and no such
change was made here.

## 15. M9 Acceptance Impact

Per this investigation's own explicit rule, M9 remains **NOT ACCEPTED**
— a diagnosis, however well-evidenced, is not a fix, and DAY-23's own
acceptance matrix (`M9-AUTO-06` marked FAIL/BLOCKED) is not resolved by
this report alone. Mapped to the five possible impacts this
investigation was asked to classify against:

- **(A) Removes the environmental concern**: No — new evidence adds
  nuance; it does not make the underlying symptom disappear or prove it
  will not recur.
- **(B) Strengthens the device-specific hypothesis**: No — if anything,
  this session's evidence **weakens** it (§12), by finding a
  cross-environment-reproducible, non-device-specific mechanism that
  explains the same symptom.
- **(C) Reveals a code defect**: **Partially, yes** — §9's finding is a
  real, reproducible, precisely-characterized property of the current
  `observeAndVerify()` call pattern (a single, immediate query with no
  allowance for a known platform caching lag). This is offered as a
  finding for a future decision, not acted on here.
- **(D) Reveals a target-app issue**: No — the target app's own behavior
  was confirmed correct and unmodified in every test.
- **(E) Reveals an unresolved cross-environment problem**: Yes, in the
  sense that the *underlying* platform characteristic (§9) appears to
  affect both environments, to different degrees, for reasons not yet
  fully quantified (why the emulator showed it 3/5 times in a clean run
  while the physical device's own historical rate differs) — this
  remains open.

## 16. Security/Safety Audit

Confirmed no emulator-specific workaround was introduced at any point:

- No `adb shell input tap` was used as a production or pipeline action
  — the one manual raw-touch-equivalent activity in this session was
  the diagnostic-only direct `performAction`/query comparisons in §7/§9,
  which are read/inspection-only Kotlin calls against the real
  `AccessibilityNodeInfo` API, not shell input injection, and were never
  wired into any production code path.
- No `dispatchGesture`/`ACTION_LONG_CLICK`/coordinate-based tapping
  exists anywhere in the production tree (confirmed via a full grep of
  `data/android/automation/` and `domain/automation/`, both before and
  after this session).
- No unrestricted automation, screenshot capture, credential/OTP/banking
  handling, or social-media automation was added or exercised.
- No autonomous retry or replanning was added — the §9 finding was
  investigated by making the *same* call twice manually, once, as a
  diagnostic; no retry loop was added to any production file.
- `RiskPolicy`, `PendingConfirmation`, and M7 verification's own types
  were not modified (confirmed via `git diff`, empty for all three
  areas).

## 17. Cleanup Verification

- `app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt`: restored
  to its exact DAY-23 baseline — `git diff` shows only the pre-existing
  DAY-21 revert, byte-for-byte identical to this session's own starting
  point.
- No other production or test file was touched during this session's
  device-comparison work (the test app, `AndroidAutomationEngine.kt`,
  `AutomationVerifier.kt`, and every other automation production file
  show **zero** diff against `HEAD`).
- Final `git status`:

```
 M app/src/androidTest/.../AutomationPipelineInstrumentedTest.kt   (DAY-21, untouched this session)
 M app/src/main/java/com/softwaremine/dps/ui/MainActivity.kt        (DAY-21, untouched this session)
 M app/src/main/res/xml/automation_accessibility_config.xml         (DAY-21, untouched this session)
 M automationtarget/src/main/res/layout/activity_main.xml           (DAY-21, untouched this session)
?? docs/DAY-21-M9-STATUS-REPORT.md
?? docs/DAY-22-M9-ACTION-CLICK-ROOT-CAUSE-INVESTIGATION.md
?? docs/DAY-23-M9-FINAL-VALIDATION-REPORT.md
?? docs/DAY-24-M9-EMULATOR-VALIDATION-REPORT.md
```

- Final build (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`,
  `:automationtarget:compileDebugKotlin`) completed with zero errors
  after cleanup.
- No commit was made at any point in this session.
- The emulator AVD (`M9_Validation_API31`) and its downloaded SDK
  packages were left installed on this machine (removing them was not
  requested and they are local development-environment resources, not
  repository state); no emulator-related file exists inside the DPS
  repository itself.

## 18. Final Recommendation

The physical device's own real-device evidence (DAY-21/22/23) is not
overturned, but this session's independent, second-environment evidence
meaningfully **broadens** the explanation: at least part of what
produces the "tap succeeded, verification says otherwise" symptom is a
generic, mechanistically-understood, cross-environment Android
accessibility node-caching timing race in the verification path's own
first post-action query — not something that requires invoking an
unexplained, physical-device-specific OS quirk. This is a genuinely
useful narrowing of the problem, obtained without touching the locked
architecture or any production behavior.

M9 remains **NOT ACCEPTED**. The path forward this report can honestly
recommend, without deciding it: a future, separately-authorized decision
should weigh whether to address the specific, now-characterized timing
race in `observeAndVerify()`'s call pattern (e.g., an explicit,
bounded settle allowance before the first verification read — an
implementation detail, not an architecture change, and one this report
deliberately does not implement). Until such a decision is made and
validated, the honest acceptance status for `M9-AUTO-06`
(the bounded interaction reliably executing *and being correctly
recognized as having executed*) remains BLOCKED.

---

## Final Evidence Table

| Hypothesis | Physical Evidence | Emulator Evidence | Combined Assessment |
|---|---|---|---|
| Code defect | Ruled out at the architecture level (DAY-22) | §9: a specific, reproducible (5/5) staleness in `observeAndVerify()`'s first call, self-resolving within ~300ms | Best-supported concrete, actionable finding of this session |
| Accessibility configuration | Audited, ruled out (DAY-22) | Unchanged config, same result pattern independent of config | Not supported |
| Target view | Ruled out (DAY-22 control button; this session's raw-touch/direct-click confirmation) | Ruled out — behavior is query-mechanism-specific, not view-specific | Not supported |
| Node targeting | Correct node found every time (all sessions) | Correct node found every time, via two independent APIs | Not supported |
| Accessibility focus | Found unrelated (DAY-22's own correction) | Not re-tested; stands as resolved | Not supported |
| Timing | Suspected, not proven (DAY-21) | Two distinct, proven timing effects: a test-methodology relaunch race (§8.1, ruled out for production) and a genuine verification-query caching race (§9, confirmed for production) | Strongly supported, now mechanistically characterized |
| Device/OS behavior | DAY-22: transient, MEDIUM confidence | Same symptom reproduces on unrelated, generic virtualized hardware via an understood mechanism | Weakened as the *primary* explanation, not eliminated as *a* contributing factor |
| Test methodology | Not applicable to prior sessions | §8.1: a genuine methodology flaw was found, disclosed, and corrected within this same session | Confirmed as a real but narrow, non-production-relevant factor |

---

## FINAL VERDICT

**CODE DEFECT IDENTIFIED**

Specifically and narrowly: `AndroidAutomationEngine.observeAndVerify()`'s
single, immediate post-action query is demonstrably (5/5, this session)
vulnerable to a real, short-lived, self-resolving Android accessibility
node-caching lag, independent of the physical device's own chipset or
OEM fork — reproduced on a generic, unrelated x86_64 emulator. This
finding **narrows and refines**, rather than replaces, DAY-22's own
"device/OS, transient, MEDIUM confidence" classification: at least one
concrete, characterized, cross-environment mechanism now exists that
produces the observed symptom, alongside — not necessarily instead of —
whatever the physical device's own remaining, still-uncharacterized
intermittency may separately involve.

This verdict does **not** mean `ACTION_CLICK` itself is defective, does
**not** mean the locked architecture is wrong, and does **not** mean M9
is accepted. No fix was implemented. No architecture was changed. No
gesture, coordinate tap, retry, or `adb input` workaround was introduced
anywhere in production code. **M9 remains NOT ACCEPTED** pending a
separately-authorized decision on whether and how to address the
now-characterized timing race, followed by genuine re-validation.
