# Day 20 — M9 Implementation Plan: Controlled Android Device & App Automation

**Status: PLAN ONLY. No source, test, manifest, or Gradle file was
modified to produce this document.** Every file reference cites the exact
current line number, re-read at the time of writing — none is carried over
unchecked from the investigation or decisions documents.

---

## 1. Executive Summary

This plan converts `docs/DAY-19-M9-ARCHITECTURAL-DECISIONS.md`'s 23 locked
decisions into an exact, file-level build order. One new tool
(`ToolId.AUTOMATION`), one new intent type, one new `AndroidTool`
implementation, one new domain interface (`AutomationEngine`), one new
Android `Service`, one new `PersistentRecoveryStore` key
(`PendingAutomationAction`), and small, mechanical, compiler-enforced
additions to seven already-exhaustive `when` expressions this codebase's
own convention already requires for every new `IntentType`/`DpsPermission`.
No locked decision is reopened. One small, genuine implementation-detail
conflict was found during re-audit (§4) — not blocking, resolved within
the locked contract, documented rather than silently patched over.

## 2. Locked Architecture Summary

Restated from `DAY-19-M9-ARCHITECTURAL-DECISIONS.md`, unmodified: thin
`AndroidAutomationTool` behind an `AutomationEngine` interface; a
mechanical-only `AccessibilityService`; `AndroidPermissionManager
.specialAccessState()` extended, not replaced; `RiskPolicy`/
`PendingConfirmation`/`continuePlan` all reused verbatim; a new
`PendingAutomationAction` modeled on `OperationCheckpoint`'s
write-before/clear-on-resolve pattern; Phase 1 = one first-party test app,
one button, one tap, one verified observation; five-entry `AutomationEngine`
action vocabulary (`open_app`/`observe_ui`/`find_element`/`tap`/
`wait_for_element`) at the engine-interface layer, one coarse `ToolCall`
operation at the tool-dispatch layer (§6, resolving Decision 4/14's own
layering — see below).

## 3. Verified Repository Baseline

Re-verified at the time of writing, not carried over:

```
git log --oneline -3
3db0a02 some random commit   (M8, HEAD, unchanged)
9dff80d M7 Investigation and some Impleemntation are completed
8e80755 Added Long Term memory M6 Complete
```
`git status --porcelain=v1 -uall` shows only the two new, untracked M9
documents (`DAY-18-...`, `DAY-19-...`) — no other change since M8's
acceptance.

Exact current line numbers established for this plan (all re-read fresh):

| Symbol | File:Line |
|---|---|
| `enum class ToolId` / `MEMORY("memory")` / `companion object` | `domain/tool/AndroidTool.kt:102` / `:122` / `:125` |
| `enum class IntentType` / `CONVERSATION` terminal case | `domain/intent/DpsIntent.kt:33` / `:129` |
| `IntentParameters` full field list | `domain/intent/DpsIntent.kt:162-266` |
| `IntentField` enum | `domain/intent/DpsIntent.kt:292-304` |
| `IntentType.requiredFields` `when` start | `domain/intent/DpsIntent.kt:395` |
| `ToolSelector.select()` `when (intent.type)` / `CONVERSATION -> null` | `ai/intent/ToolSelector.kt:62` / `:142` |
| `ClarificationEngine.check()` | `ai/intent/ClarificationEngine.kt:64-100+` |
| `ClarificationEngine`'s `questionFor`-shaped `when` / `CONVERSATION` case | `ai/intent/ClarificationEngine.kt:216` |
| `ToolResponseGenerator.phrasePermission`'s purpose `when` / `CONVERSATION` case | `ai/intent/ToolResponseGenerator.kt:160` |
| `ToolResponseGenerator.defaultSuccess`'s `when` / `CONVERSATION` case | `ai/intent/ToolResponseGenerator.kt:193` |
| `RiskPolicy.classify` | `domain/secretary/RiskPolicy.kt:52-61` |
| `DpsPermission` enum body | `domain/permission/DpsPermission.kt:44-88` |
| `AndroidPermissionMapping.androidName()` (exhaustive, non-nullable) | `data/android/permission/AndroidPermissionMapping.kt:42-51` |
| `AndroidPermissionManager.specialAccessState()` | `data/android/permission/AndroidPermissionManager.kt:198-213` (per M9 investigation's own citation, re-confirmed unchanged) |
| `PersistentRecoveryStore` — full class, 3 existing key triads | `data/android/secretary/PersistentRecoveryStore.kt:1-221` |
| `OperationCheckpoint`/`OperationType` | `domain/secretary/ExecutionRecoveryState.kt:198-226` |
| `SecretaryOrchestrator` constructor params | `ai/secretary/SecretaryOrchestrator.kt:116-121` |
| `pendingVerification` field | `ai/secretary/SecretaryOrchestrator.kt:290` |
| `handle()`'s checkpoint/verification precedence | `ai/secretary/SecretaryOrchestrator.kt:328`, `:339` |
| `onPermissionResult()` | `ai/secretary/SecretaryOrchestrator.kt:569` |
| `reset()` | `ai/secretary/SecretaryOrchestrator.kt:598` |
| `askConfirmationFor()` | `ai/secretary/SecretaryOrchestrator.kt:1248` |
| `recordOutcome()`'s verification read (3 occurrences: `continuePlan`, `attachSuggestionIfApplicable`, `recordOutcome` itself) | `ai/secretary/SecretaryOrchestrator.kt:854`, `:1363`, `:2133` |
| `currentPersistablePendingState()` | `ai/secretary/SecretaryOrchestrator.kt:2040` |
| `AiContainer.toolRegistry` / tool list / `secretaryOrchestrator` construction | `di/AiContainer.kt:386-413`, `:464-491` |

## 4. Implementation Boundaries

Per the master prompt's own authoritative rule, one genuine, small
implementation-detail conflict was found during re-audit and is documented
here rather than silently resolved elsewhere in this plan:

**Conflict**: `AndroidPermissionMapping.androidName(permission:
DpsPermission): String` (`data/android/permission/AndroidPermissionMapping.kt:42-51`)
is an exhaustive `when` with **no `else` branch and a non-nullable `String`
return type**, and its own doc states the invariant explicitly: "Total by
construction — `DpsPermission` is a closed enum, so adding a case without
a mapping fails to compile." Decision 3 locks
`DpsPermission.AUTOMATION_ACCESSIBILITY` as a new enum entry — but
accessibility-service enablement has **no corresponding
`android.permission.*` string at all** (it is queried via
`AccessibilityManager`/`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`,
an entirely different API surface than `checkSelfPermission`). Adding the
new enum entry without also touching this file would not compile;
touching it means deciding what a "there is no such string" case returns.

**Why it matters**: this is not a reason to reopen Decision 3 — the
decision's own text already anticipated `specialAccessState()` needing a
new branch and never claimed `androidName()` was unaffected; the re-audit
simply surfaced the one subordinate function Decision 3 didn't explicitly
name.

**Resolution, within the locked contract, not a new one**: change
`androidName()`'s return type to `String?`, add
`DpsPermission.AUTOMATION_ACCESSIBILITY -> null` with a one-line comment
("no Android permission string exists for accessibility-service
enablement — queried via `AccessibilityManager`, granted via
`Settings.ACTION_ACCESSIBILITY_SETTINGS`, see `specialAccessState()`"),
and confirm every existing caller tolerates `null`. Checked: `request()`'s
special-access branch (`AndroidPermissionManager.kt`, per the M9
investigation's own citation) never calls `androidName()` for
`SPECIAL_ACCESS`-kind permissions at all (only the `runtime` branch does) —
`SCHEDULE_EXACT_ALARM` already proves this path exists and already never
touches `androidName()` in practice. `fromAndroidName(androidName: String):
DpsPermission?`'s reverse lookup (`:54-55`) compares against a non-null
input parameter, so a `null`-returning entry simply never matches — no
behavior change for any existing permission. **This is a one-file,
two-line-plus-signature change, not a redesign**, and is included in §27's
file matrix as a small MODIFIED file, not silently omitted.

No other contradiction was found between the locked decisions and current
source during this re-audit.

## 5. Domain Model Plan

**New**: `domain/tool/AndroidTool.kt` — `ToolId` enum gains one entry,
`AUTOMATION("automation")`, inserted after `MEMORY("memory")` at line 122,
before the closing `;` at line 123. Mirrors the exact M6 precedent for
adding `MEMORY` — no other change to the enum's structure, its
`companion object`'s `BY_NAME`/`fromName()` (`:125-130`) is already
generic over `entries`.

**New**: `domain/intent/DpsIntent.kt` — `IntentType` gains one entry,
`AUTOMATION("automation")` (with `@SerialName("automation")`), inserted
after `FORGET_FACT` (line 118) and before `CONVERSATION` (line 128-129) —
placed last among "real" action types, immediately before the
conversation fallback, mirroring exactly where every prior milestone's
own new `IntentType` was inserted (M6's three memory types went in the
same relative position). `IntentType.requiredFields`'s `when` (starting
`:395`) gains:
```kotlin
IntentType.AUTOMATION -> listOf(setOf(IntentField.TITLE))
```
**No new `IntentField`, no new field on `IntentParameters`.** Resolves
Decision-document open micro-decision #1: `IntentParameters.title`
(`:176`, "Short label for a reminder, event or notification" — extended in
meaning, not in shape, to also mean "the target app's spoken name" for
`IntentType.AUTOMATION`, exactly the same flat-bag reuse `REMEMBER_FACT`/
`RECALL_FACT`/`FORGET_FACT` already established for `title` meaning "the
fact's subject") carries the target app name. No element/action
description field is needed for Phase 1 because Phase 1 has exactly one
possible interaction (Decision 5) — the *only* thing the model must
extract is which app the user means, which the deterministic
`AndroidAutomationTool`/`AutomationEngine` layer then drives through the
fixed open→find→tap→observe→verify sequence internally (§6). `IntentAction`
defaults to `CREATE` for `AUTOMATION` (unused meaningfully, same as it is
for `NOTIFICATION`/`REPORT` today) — no new `IntentAction` value.

**New**: `domain/automation/` package (new directory):
- `AutomationEngine.kt` — the domain interface (§7).
- `PendingAutomationAction.kt` — alongside `ExecutionRecoveryState.kt`'s
  own package (`domain/secretary/`, not `domain/automation/` — it is a
  *recovery* record, and every existing recovery type
  (`OperationCheckpoint`, `PendingVerification`, `PendingConfirmation`)
  already lives in `domain/secretary/`; placing it there keeps the
  established "all recovery-shaped types in one package" convention
  intact rather than splitting it across two packages for no reason)
  (§15).
- `AutomationOutcome.kt` (or a nested sealed type inside
  `AutomationEngine.kt` — a naming micro-decision, not architectural) —
  the bounded execution-time result shape `ToolResult` itself has no case
  for (§13, §20).

## 6. Tool Integration Plan

**Resolving the Decision-4/Decision-14 layering** (a genuine design detail
the architectural decisions document left implicit): Decision 4 locks a
five-entry *action vocabulary*; Decision 14 locks "one automation intent =
one plan step... internal sequencing opaque to `SecretaryOrchestrator`."
Precedent (`ai/intent/ToolSelector.kt:69-140`) shows every existing tool's
`ToolCall.operation` is **coarser** than its own internal steps
(`AndroidCalendarTool.createEvent()`'s single `create_event` operation
internally resolves a calendar, builds an event, inserts it, and reads
back an id — four internal steps, one `ToolCall`). This plan applies the
identical layering: **the five-entry vocabulary lives at the
`AutomationEngine` interface** (its own methods); **`AndroidAutomationTool
.operations` is exactly one entry for Phase 1**, `perform_interaction`,
whose single execution internally calls `AutomationEngine`'s
`openApp`→`findElement`→`tap`→`observeAndVerify` in sequence. This is not
a deviation from either locked decision — it is the precise, precedented
answer to a question neither decision's text fully spelled out.

**`ai/intent/ToolSelector.kt`** — `select()`'s `when` (`:62`) gains, after
the `IntentType.FORGET_FACT` branch (`:136-140`) and before
`IntentType.CONVERSATION -> null` (`:142`):
```kotlin
IntentType.AUTOMATION -> ToolCall(
    toolId = ToolId.AUTOMATION,
    operation = "perform_interaction",
    arguments = buildMap { put("app", p.value(IntentField.TITLE).orEmpty()) },
)
```

**`ai/intent/ClarificationEngine.kt`** — `check()` (`:64+`) needs **zero
new code**: `IntentType.AUTOMATION` is not in `TARGETABLE_TYPES` (`:231`)
or `TITLE_ADDRESSABLE_TYPES` (`:234`), so it falls through to the
existing, already-generic `requiredFields`-driven completeness check every
non-special-cased type already uses — confirmed by direct reading, not
assumed. The question-phrasing `when` (`:216` context) gains one new
branch before the `CONVERSATION` case:
```kotlin
IntentType.AUTOMATION -> "Which app would you like me to open?"
```
`questionForMissingTarget` (`:221-227`) needs no change — it already has
an `else -> "Which one do you mean?"` fallback and is never reached for
`AUTOMATION` (not in `TARGETABLE_TYPES`).

**`ai/intent/ToolResponseGenerator.kt`** — `phrasePermission`'s purpose
`when` (`:160` context) gains one new branch:
```kotlin
IntentType.AUTOMATION -> "open that app for you"
```
`defaultSuccess`'s `when` (`:193` context) gains:
```kotlin
IntentType.AUTOMATION -> "Done."
```
(unreachable in practice — `AndroidAutomationTool` always sets a non-blank
summary, exactly the same "unreachable but every case must say something"
precedent already documented for `REMEMBER_FACT`/`RECALL_FACT`/
`FORGET_FACT` at this same line).

**`ai/intent/IntentPromptBuilder.kt`** — one new rule bullet describing
`automation`/`AUTOMATION` to the model (mirrors the exact, established
M6 precedent: "one new rule bullet distinguishing the three memory
intents... the intent list and JSON schema needed no structural change").
**Per Decision 8, this bullet names only**: the wire name `automation`,
that `title` carries the app name, and nothing about UI elements, node
data, or coordinates — the model is never told those concepts exist.

## 7. AutomationEngine Plan

```kotlin
package com.softwaremine.dps.domain.automation

interface AutomationEngine {
    suspend fun openApp(packageName: String): AutomationOutcome.OpenApp
    suspend fun findElement(descriptor: ElementDescriptor): AutomationOutcome.Find
    suspend fun tap(descriptor: ElementDescriptor): AutomationOutcome.Action
    suspend fun observeAndVerify(
        descriptor: ElementDescriptor,
        expectedText: String,
    ): com.softwaremine.dps.domain.secretary.VerificationOutcome
}

data class ElementDescriptor(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
)
```
- **Synchronous vs. suspend**: every method `suspend` — `openApp`
  dispatches a real intent and waits (bounded) for the target to become
  foreground; `findElement` may internally wait (Decision 11); `tap`
  performs one platform call and returns immediately (its boolean result
  proves nothing about the *outcome*, only that the platform accepted the
  call — §12); `observeAndVerify` is the one method that produces
  `VerificationOutcome` directly, reusing Decision 10's locked vocabulary.
- **Android-lifecycle interaction**: the real implementation
  (`AndroidAutomationEngine`, `data/android/automation/`) holds a
  reference to the live `AccessibilityService` instance (or `null` if
  disconnected), obtained via a static/companion accessor the service
  itself sets in `onServiceConnected`/clears in `onDestroy` — the
  **only** place a live service reference crosses a class boundary; never
  passed through `AiContainer`'s constructor-injection graph, since the
  service's own lifecycle is independent of `AiContainer`'s (§9's own
  Decision-13 boundary).
- **Kept minimal**: four methods, not a generic action-dispatch framework —
  exactly Phase 1's five-vocabulary-item surface, with `wait_for_element`
  folded into `findElement`'s own bounded internal wait (Decision 11) and
  `observe_ui` folded into `observeAndVerify` (there is no
  observe-without-verify need in Phase 1 — a genuinely new method is added
  only when a later phase's scope actually requires observing without an
  expected value to check against).

## 8. AccessibilityService Plan

**New**: `data/android/automation/DpsAutomationService.kt`, extending
`android.accessibilityservice.AccessibilityService`.

- **Manifest declaration** (planned, not applied — §29):
```xml
<service
    android:name=".data.android.automation.DpsAutomationService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/automation_accessibility_config" />
</service>
```
`exported="true"` is mandatory for this specific intent-filter action
(the system, not another app, is what binds it — `BIND_ACCESSIBILITY_SERVICE`
is exactly the protection that makes this safe, the identical reasoning
`AndroidManifest.xml`'s own existing comment already gives for
`ReminderBootReceiver`'s `exported="true"` + a protected system action).
- **Accessibility config XML** (planned): `res/xml/automation_accessibility_config.xml` —
  `accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"`,
  `packageNames="com.softwaremine.dps.automationtarget"` (Phase 1's one
  allowlisted target, per Decision 5/9 — **not** left blank/all-packages),
  `canRetrieveWindowContent="true"`, `canPerformGestures="false"` (Phase 1
  needs no gesture dispatch — `tap` uses `ACTION_CLICK`, not
  `dispatchGesture` — kept off until a later phase's vocabulary actually
  needs it, per Decision 4's own "no unapproved actions" discipline).
- **Event types**: exactly the two named above — nothing else, matching
  Decision 20's performance guidance.
- **Package filtering**: enforced at the OS level via `packageNames`
  above, redundant with (not a substitute for) Decision 6's own
  application-level allowlist check — the two-layer enforcement Decision
  18/security explicitly locks.
- **Lifecycle methods**: `onServiceConnected()` sets a static/companion
  `instance: DpsAutomationService?` reference and logs connection;
  `onAccessibilityEvent(event)` is a no-op body for Phase 1 (observation
  is pull-based via `findElement`'s own fresh `rootInActiveWindow` read,
  not push-based event accumulation — the simplest correct Phase 1
  behavior; event-driven *waiting*, Decision 11, is implemented inside
  `findElement`'s bounded-wait loop by polling `rootInActiveWindow`
  between short, bounded pauses rather than subscribing to a callback
  stream, since Phase 1 has exactly one screen/one element and does not
  need general-purpose event routing — a smaller, still fully
  event-informed design than a full pub/sub layer would be, and
  consistent with "smallest architecture that can safely grow"); 
  `onInterrupt()` logs only; `onDestroy()` clears the static reference to
  `null`.
- **Action bridge**: `AndroidAutomationEngine.tap()`/`findElement()`
  read the static `DpsAutomationService.instance` at call time (never
  cached across calls, per Decision 18's "no stale service references")
  and return an `ObservationFailed`/`ACCESSIBILITY_UNAVAILABLE`-shaped
  outcome immediately if it is `null` — never throwing, never blocking on
  a service that may never reconnect.

## 9. Permission Integration Plan

**`domain/permission/DpsPermission.kt`** — one new entry after
`RECORD_AUDIO` (`:84`, before the closing `;` at `:85`):
```kotlin
/**
 * Accessibility-service enablement for controlled UI automation (M9).
 * **Special access, not a runtime permission** — see [AndroidPermissionManager]'s
 * `specialAccessState()` for the query, and [DpsAutomationService] for what
 * it gates. No [minApiLevel]: accessibility services are available at
 * every API level this app supports.
 */
AUTOMATION_ACCESSIBILITY(PermissionKind.SPECIAL_ACCESS),
```

**`data/android/permission/AndroidPermissionMapping.kt`** — the §4
resolution: `androidName()`'s signature becomes `fun androidName(permission:
DpsPermission): String?`, gains `DpsPermission.AUTOMATION_ACCESSIBILITY ->
null` (with the one-line comment from §4), and every existing caller is
verified tolerant (checked in §4 — none breaks).

**`data/android/permission/AndroidPermissionManager.kt`** —
`specialAccessState()`'s `when (permission)` (the investigation's own
cited `:198-213`) gains one branch:
```kotlin
DpsPermission.AUTOMATION_ACCESSIBILITY -> {
    val enabled = AccessibilityServiceUtils.isEnabled(context, DpsAutomationService::class.java)
    if (enabled) PermissionState.GRANTED else PermissionState.REQUIRES_SETTINGS
}
```
where `AccessibilityServiceUtils.isEnabled` is a small new private
helper (or inlined) reading
`Settings.Secure.getString(contentResolver, ENABLED_ACCESSIBILITY_SERVICES)`
and checking for this app's own service `ComponentName` — the standard,
documented platform technique, with **no dependency on
`AccessibilityManager.getEnabledAccessibilityServiceList()`'s own
`AccessibilityServiceInfo` objects** (a heavier API returning live service
descriptors, unnecessary for a simple presence check) — a deliberate,
minimal choice, not an oversight.
- **User guidance**: `ToolResponseGenerator.phrasePermission` (`:128-165`
  in its own file) gains one new `need`-clause branch (a new `when` arm
  alongside the existing `READ_CONTACTS`/`READ_CALENDAR`/etc. checks) —
  `result.permissions.contains(DpsPermission.AUTOMATION_ACCESSIBILITY) ->
  "accessibility access"` — paired with the new `AUTOMATION` purpose
  branch already planned in §6, per Decision 22's own "explain what
  accessibility access means" requirement (the exact wording is an
  implementation-time copy-writing detail, not an architectural one).
- **Result handling / process death**: unchanged — `AndroidPermissionManager
  .request()`'s existing special-access partition (`permissions.partition
  { it.kind == PermissionKind.SPECIAL_ACCESS }`) already routes this
  correctly with zero new code, exactly as Decision 3 locks.
- **Service disabled after being enabled**: the very next `state()` call
  re-queries the live settings string — always current, never cached, so
  this requires no new detection code (Decision 13, restated).

## 10. App Targeting Plan

**New**: a small, pure `AutomationAppRegistry` (or a top-level function),
placed in `domain/automation/`:
```kotlin
object AutomationAppRegistry {
    private val KNOWN_APPS = mapOf("test app" to "com.softwaremine.dps.automationtarget")
    fun resolve(spokenName: String): String? = KNOWN_APPS[spokenName.trim().lowercase()]
}
```
Phase 1's table has exactly one entry (Decision 5/6) — the lookup
mechanism is real and exercised end-to-end even though the table is
trivially small, so Decision 6's general rule is genuinely proven, not
bypassed. `null` → `AndroidAutomationTool` returns `ToolResult.Failure`
("I don't know how to open that"). **Installed/uninstalled check**: inside
`AndroidAutomationEngine.openApp(packageName)`, via
`packageManager.getLaunchIntentForPackage(packageName) == null` →
`ToolResult.Unsupported`, mirroring `IntentLauncher.canHandle()`'s
existing, unmodified pattern exactly (no new existence-check idiom
invented). **Ambiguous**/**package mismatch**: not reachable with a
one-entry table; the rule (Decision 6's `Failure`-with-candidates,
mirroring `ContactMatch.Ambiguous`) is written now so a future table
growth needs no new logic, only more table entries.

## 11. UI Element Targeting Plan

**New**: a pure `ElementMatcher` function/object in `domain/automation/`,
taking a root node abstraction + an `ElementDescriptor` (§7) and returning
a bounded outcome:
```kotlin
sealed interface ElementMatch {
    data class Found(val node: /* node handle */ Any) : ElementMatch
    data object NotFound : ElementMatch
    data class Ambiguous(val count: Int) : ElementMatch
}
```
**Priority, locked and unchanged from Decision 7**: `resourceId` exact
match first, then `contentDescription` exact match, then exact `text` —
each checked only if the previous signal was absent from the descriptor
(the descriptor names *which* signal(s) to use; the matcher does not
itself decide which signal is "best" for a given node — that choice is
made once, by whoever constructs the `ElementDescriptor` for Phase 1's one
button, always `resourceId`-first per Decision 5's own test-app design).
**Duplicate matches**: `Ambiguous`, never auto-picked. **Zero matches**:
`NotFound`. **Stale nodes**: the matcher always operates on a freshly
obtained `rootInActiveWindow` (never a cached node reference across calls —
Decision 18) — staleness during the *match* itself (the tree mutating
mid-traversal) surfaces as either a thrown platform exception (caught,
mapped to `ObservationFailed`) or a node that fails validity checks before
use, never propagated as a crash. **Dynamic UI/localization/scrolling**:
not applicable to Phase 1's own fixed, non-localized, single-screen test
app (Decision 19) — the matcher's own logic is generic and will apply
unchanged whenever a later phase introduces a real variable-UI target; no
Phase 1 code special-cases the test app's own text.

## 12. Action Execution Plan

For Phase 1's one interaction (`TAP` on the test button):
1. **Element found**: via `ElementMatcher` (§11), scoped to the current
   foreground window only.
2. **Node validity checked**: `AccessibilityNodeInfo.refresh()` (or a
   fresh re-query, whichever the implementation phase's own real-device
   testing proves more reliable — an implementation detail, not an
   architectural one) immediately before acting, per Decision 5/§5's own
   "always re-query immediately before acting" discipline.
3. **Action performed**: `node.performAction(AccessibilityNodeInfo.ACTION_CLICK)`.
4. **What the Android result means**: a `true` return means only "the
   platform accepted and dispatched the click event" — **it does not mean
   the target app's own click handler ran, changed state, or did anything
   observable.**
5. **What it does NOT prove**: whether the test app's button text actually
   changed — that is a separate, subsequent read.
6. **Observation that follows**: a fresh `findElement` re-query for the
   same `resourceId`, per §11.
7. **Verification that follows**: `observeAndVerify` (§7) compares the
   re-queried node's text against the expected post-tap string (Decision
   5), producing one of `VerificationOutcome`'s four existing cases (§13).

**Locked, restated as a hard rule**: `AndroidAutomationTool.execute()`'s
own `ToolResult.Success` (if `performAction` returned `true`) is
deliberately worded to claim nothing beyond "the tap was dispatched" — the
*real* answer the user receives comes from `SecretaryOrchestrator
.recordOutcome`'s own verification step (§6/§13), exactly mirroring how
`AndroidCallTool`'s own `ToolResult.Success` already says "The dialer is
open" rather than "Called" (`AndroidCallTool.kt`, unmodified, cited in the
M8 investigation) — the same "never claim more than what actually
happened" discipline, applied here to accessibility actions for the first
time.

## 13. Observation Plan

`findElement`'s bounded-wait behavior (§7/§8's polling design): re-query
`rootInActiveWindow` on a short interval (e.g. 200ms, an implementation-
tuning detail) up to `ToolExecutor.DEFAULT_TIMEOUT_MILLIS` (10s, Decision
11's own locked ceiling) — each iteration is a **fresh** read, never a
cached reference reused across iterations (staleness, §5/§11). On timeout,
returns `AutomationOutcome.Find` shaped as not-found-within-budget, mapped
by `AndroidAutomationTool` to `ToolResult.Timeout` (the existing case,
reused, per Decision 15's own "no new top-level type" rule). **Activity/
window transitions**: `openApp`'s own bounded wait (same mechanism,
watching for the target package to become the foreground window) uses the
identical poll-and-recheck shape — one mechanism, two call sites, not two
mechanisms. **Loading states**: not modeled explicitly for Phase 1 — the
test app (Decision 19) has no loading state by design; a later phase
targeting a real app with genuine loading UI would extend this same
bounded-wait primitive, not build a second one.

## 14. Verification Plan

`VerificationOutcome`'s four cases (`Verified`/`Mismatch`/`NotFound`/
`ObservationFailed`, unmodified) are produced by the new
`observeAndVerify` (§7), **not** by `ExecutionVerifier.kt` — confirmed,
per §4's re-audit, that no existing `ExecutionVerifier` method is touched
or extended. `SecretaryOrchestrator.recordOutcome()` (the exact site M7
verification already runs at, `:854`/`:1363`/`:2133` region) gains one new
conditional alongside the existing `executionVerifier.verify(...)` call:
```kotlin
val verification = when (outcome.intent.type) {
    IntentType.TASK, IntentType.CALENDAR_EVENT -> success?.let { executionVerifier.verify(outcome.intent, it) }
    IntentType.AUTOMATION -> success?.let { automationVerifier.verify(outcome.intent, it) }
    else -> null
}
```
(exact conditional structure is illustrative — the implementation phase
resolves the precise merge with the existing `let`-chain at `:1363`
without restructuring `ExecutionVerifier`'s own call shape). **ACTION
EXECUTED vs. ACTION VERIFIED are kept distinct exactly as M7 already keeps
them distinct for `create_task`/`create_event`**: `ToolResult.Success`
answers the former, `VerificationOutcome` answers the latter, and
`ToolResponseGenerator.describeVerification` (already generic over all
four cases, unmodified) phrases a non-`Verified` outcome honestly without
any automation-specific wording change needed.

## 15. PendingAutomationAction Recovery Plan

**New**: `domain/secretary/PendingAutomationAction.kt` (placed alongside
`OperationCheckpoint`, per §5's package-placement reasoning):
```kotlin
@Serializable
data class PendingAutomationAction(
    val targetApp: String,           // resolved package name (Decision 6)
    val resourceId: String?,          // the ElementDescriptor's own fields —
    val contentDescription: String?,  // whichever were used, for re-observation
    val text: String?,
    val expectedText: String,         // Decision 5's expected post-action value
    val requestedAtMillis: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}
```
Fields chosen from the master prompt's own candidate list: **included**
(automation action identity is implicit in "this is the one Phase 1
action"; target app; target element identity via the three descriptor
fields; expected outcome; timestamp); **explicitly excluded**: raw
"action arguments" beyond what's listed (nothing else exists in Phase 1),
"current phase" (unnecessary — a leftover record's mere existence already
means "action dispatched, not yet verified"; there is only one phase to
be in, since verification always immediately follows action with no
further sub-phase in between for Phase 1's synchronous flow), and — per
the master prompt's own explicit, repeated instruction — **no
`AccessibilityNodeInfo`, no raw screen content**, only the three
serializable descriptor strings.

**`data/android/secretary/PersistentRecoveryStore.kt`** — a **fourth**
key (`automation`, alongside `state`/`checkpoint`/`pending_verification`
at `:213-215`), and three new methods mirroring `saveVerification`/
`loadVerification`/`clearVerification` (`:170-208`) verbatim in shape —
`commit()`-based (not `apply()`), for the identical "window-D" reason
already documented at `:130-136`/`:180-189`: `saveAutomation` must be
durable *before* `performAction` is called, or the entire mechanism is
defeated.

**Write-before/clear-on-resolve** (Decision 12, mirroring
`OperationCheckpoint`'s own discipline exactly): `saveAutomation()` is
called immediately before step 3 of §12's sequence (`performAction`);
`clearAutomation()` is called once `observeAndVerify` produces **any** of
the four `VerificationOutcome` cases — mirroring `PendingVerification`'s
own "cleared on any resolution, not just success" rule at
`PersistentRecoveryStore.kt:196-204`.

## 16. Process-Death Plan

Mapped directly to the master prompt's own six scenarios:

| Scenario | Handling |
|---|---|
| A. Dies before action | No `PendingAutomationAction` written yet — nothing to recover, next message is a fresh request. |
| B. Dies during the action itself (mid-`performAction` call) | Indistinguishable from C in practice (the write happens before the call per §15) — treated identically. |
| C. Dies after action, before observation | The genuinely new case §15 exists for: on restart, `SecretaryOrchestrator` (new field mirroring `pendingVerification` at `:290`, new `handle()`-entry branch mirroring `:339`) loads the leftover record and **re-observes** (calls `observeAndVerify` fresh, using the persisted descriptor/expected-text) rather than re-tapping. |
| D. Dies during observation | Same as C — observation itself is stateless/re-runnable; a leftover record's next read simply re-attempts it. |
| E. Dies after observation, before verification | Not a distinct window in this design — `observeAndVerify` (§7) performs observation and verification as one atomic suspend call; there is no persisted intermediate state between the two, mirroring M7's own precedent that a *read* need not be checkpointed the way a *write* must be. |
| F. Dies after verification | `clearAutomation()` already ran (§15) — nothing left to recover, identical to the existing M7 `PendingVerification` post-resolution state. |

**The critical rule, restated as non-negotiable**: recovery **never**
calls `tap`/`performAction` again for a leftover record — only
`observeAndVerify`. If re-observation itself fails
(`ObservationFailed` — service unavailable, app no longer foreground),
the leftover is surfaced once, honestly, via a new
`resolveOutstandingAutomationNotice()`-shaped method (mirroring
`resolveOutstandingVerificationNotice`'s own exact shape, called from the
same `handle()` region as `:339`), worded like
`outstandingCheckpointNotice`'s own established pattern: "I may have
tapped something in \[app\] but couldn't confirm it — please check."

## 17. Service-Death Plan

Per Decision 13, restated with exact file-level consequence: **no new
mechanism** beyond §9 (permission precheck) and §16 (mid-action recovery).
`AndroidAutomationEngine`'s every method (§7) checks
`DpsAutomationService.instance` freshly at call time; `null` → immediate
`ACCESSIBILITY_UNAVAILABLE`-shaped failure (§20), never a hang, never a
crash, never an attempt to rebind. This requires zero new persisted state
and zero new `SecretaryOrchestrator` field beyond §16's own.

## 18. Multi-Step Integration

Per Decision 14: `continuePlan`/`parkRemainder`/`continueAfterResumedStep`
(`ai/secretary/SecretaryOrchestrator.kt`, region already cited for the
existing M7 verification check at `:854`) require **zero modification** —
`IntentType.AUTOMATION` is one `DpsIntent`, one plan step, and the existing
`if (verification != null && verification !is VerificationOutcome.Verified)`
check already generalizes to whatever verifier produced the outcome,
`ExecutionVerifier` or the new `AutomationVerifier`/`observeAndVerify`
alike — confirmed by re-reading the exact conditional, which branches only
on `VerificationOutcome`'s own type, never on which verifier produced it.

## 19. Risk/Confirmation Integration

**`domain/secretary/RiskPolicy.kt`** — `classify()`'s `when` (`:52-61`)
gains one new arm, inserted before the final `else`:
```kotlin
intent.type == IntentType.AUTOMATION -> RiskLevel.CONFIRM_REQUIRED
```
Per Decision 9's own Phase-1-specific lock: **every** `AUTOMATION` intent
is `CONFIRM_REQUIRED`, unconditionally — no target/action-aware heuristic
for Phase 1 (deferred, Decision 23).

**`ai/secretary/SecretaryOrchestrator.kt`** — `askConfirmationFor()`
(`:1248`) gains one new `when` branch:
```kotlin
IntentType.AUTOMATION -> askAutomationConfirmation(intent)
```
and a new sibling private function, mirroring `askForgetFactConfirmation`'s
own exact shape (constructing a question string, setting
`pendingConfirmation = PendingConfirmation(intent, now())`, transitioning
state, returning `Outcome.Clarify`) — e.g. `"Open the test app and tap the
button?"`. **Zero changes** to `PendingConfirmation.kt`, `ConfirmationParser.kt`,
or `SecretaryState`'s `WAITING_CONFIRMATION` — all three reused verbatim,
confirmed by direct reading unchanged.

## 20. Security Plan

Phase 1 already cannot reach any prohibited surface, by construction, not
merely by policy: the one allowlisted target (`com.softwaremine.dps
.automationtarget`, Decision 19) is a first-party test app with no
password/OTP/financial/account-settings screen of any kind — there is
nothing sensitive *to* touch. The Decision-16 denylist (password/OTP/PIN
field detection, prohibited-app refusal) is still planned as production
code in `AndroidAutomationEngine` (not deferred to "when a real app is
targeted") specifically so Phase 1's own implementation and tests exercise
the real guard, not a stub — a future phase adding a second allowlist
entry inherits an already-proven denylist rather than building one for the
first time under schedule pressure. The failure vocabulary's
`ACCESSIBILITY_UNAVAILABLE`/`ACTION_REJECTED` cases (§20-of-the-decisions,
mapped in §13 above) are the same bounded outcomes a denylist refusal
produces — no new `ToolResult`/`VerificationOutcome` case needed for a
security refusal specifically.

## 21. Privacy Plan

Every `DpsLogger` call `AndroidAutomationEngine`/`DpsAutomationService`
make that touches node text/description passes through the existing
`core/logging/redact` utility — no new redaction mechanism. No
screenshot API is called anywhere in this plan (confirmed: no
`android.view.PixelCopy`/`Bitmap`-capturing code appears in any file
listed in §27). `PendingAutomationAction` (§15) persists only the three
descriptor strings + one expected-text string — never a full node tree,
never surrounding screen content. Episodic memory (M6,
`EpisodicMemoryRecorder`, unmodified) logs only the tool's own summary
string for a successful automation action ("Tapped a button in the test
app"), exactly like every other tool today — no automation-specific
memory-writing code is added anywhere in this plan.

## 22. Test Application Plan

**New, separate module/project** (not part of the `app` module's release
build or Play listing): package `com.softwaremine.dps.automationtarget`.
- **Activity**: one, `MainActivity`, one `Button`
  (`android:id/automation_target_button`, fixed English text
  `"Tap me"`).
- **Deterministic state**: on click, the button's own displayed text
  changes to the fixed string `"Tapped"` — no network call, no animation
  beyond the platform's own default (negligible, non-blocking) ripple,
  no persisted state across app restarts (a fresh launch always shows
  `"Tap me"`).
- **Expected interaction**: `TAP` on the one button.
- **Expected post-action state**: the same node's text reads `"Tapped"`.
- **Accessibility properties**: the button is a real, focusable,
  clickable `Button` (Android's own accessibility framework exposes
  standard `Button` widgets fully by default — no custom
  `View.onInitializeAccessibilityNodeInfo` override needed).
- **Reset behavior**: relaunching the activity (`Intent.FLAG_ACTIVITY_CLEAR_TOP`
  or simply restarting the app between test runs) resets to `"Tap me"` —
  no explicit "reset" button needed since the app holds no persisted state
  at all.
- **Test isolation**: installed as its own APK; instrumented tests launch
  it via `openApp` exactly as Phase 1's real user-facing flow would,
  never via a test-only backdoor.

## 23. JVM Test Plan

- `RiskPolicyTest.kt` — one new case, `AUTOMATION` → `CONFIRM_REQUIRED`,
  mirroring its existing per-type table shape exactly.
- `IntentJsonParserTest.kt`/`ToolSelectorTest.kt`/`ClarificationEngineTest.kt` —
  one new case each, mirroring every prior `IntentType` addition's own
  test shape (parses correctly; selects the correct `ToolCall`; asks the
  correct question when `title` is missing).
- New `AutomationAppRegistryTest.kt` (or equivalent) — resolves the one
  known name; returns `null` for unknown names; case/whitespace
  normalization.
- New `ElementMatcherTest.kt` — resource-id match, content-description
  match, text match, priority ordering when multiple signals are present,
  zero-match, multi-match (`Ambiguous`), against a hand-built fake node
  abstraction (§27 flags the one open micro-decision about how a fake
  `AccessibilityNodeInfo`-shaped tree is constructed for pure-JVM testing).
- New `PendingAutomationActionTest.kt` (in
  `data/android/secretary/PersistentRecoveryStoreTest.kt`, extending it —
  mirrors the existing per-key round-trip/clear/independence test triad
  already established for `PendingVerification`) — round-trip, clear,
  independence from the other three keys.
- New `AndroidAutomationToolTest.kt` — argument validation (missing
  `app` argument → `Failure`), dispatch to a fake `AutomationEngine`,
  `ToolResult` mapping for each of the failure categories (§13/§20).
- `SecretaryOrchestratorTest.kt` — new automation confirm/decline pair
  (mirroring the existing `forget_fact`/delete-confirmation test shape
  exactly), one multi-step test (an automation step alongside an
  unrelated `SAFE_AUTO` step, mirroring M8's own "confirmed step followed
  by an unrelated create step" test), one test asserting `recordOutcome`
  never confuses `ToolResult.Success` (action dispatched) with
  `VerificationOutcome.Verified` (action confirmed) — the exact ACTION
  EXECUTED vs. ACTION VERIFIED distinction the master prompt itself
  demands be provable.
- **No autonomous retry/replanning tests are "new capability" tests** —
  they are **regression** tests asserting the *absence* of new behavior:
  a `Mismatch`/`NotFound`/`ObservationFailed`/`Timeout` outcome from an
  automation step never triggers a second `tap` call within the same
  `execute()` invocation, and never triggers a different action being
  chosen — asserted by counting calls to a fake `AutomationEngine`,
  mirroring M8's own "resuming a permission grant twice only ever runs the
  tool once" test discipline exactly.

## 24. Instrumented Test Plan

- Accessibility permission state: real `AccessibilityManager`/`Settings
  .Secure` query against a genuinely, manually-enabled service on the
  test device (§9), before any automation call is attempted.
- Service connection: `DpsAutomationService.instance` becomes non-null
  after real enablement; becomes `null` after real disablement
  (`adb shell settings put secure enabled_accessibility_services`).
- Target app launch: real `openApp` against Decision-19's test app,
  confirmed via real window-state observation.
- UI tree discovery / deterministic element matching / bounded action /
  post-action observation / verification: the full §12 sequence, against
  the real test app, asserting on the test app's own real, observable
  button text — never on `AndroidAutomationTool`'s own return value alone
  (M7's own "observe real state" discipline, applied here for the first
  time to accessibility).
- Service unavailable behavior: disable the service mid-test, confirm
  `ACCESSIBILITY_UNAVAILABLE`-shaped failure, never a hang/crash.
- UI-changed / element-not-found / timeout: force each condition
  deterministically against the test app (rename the expected resource id
  in one test build variant, or simply search for a resource id that
  never exists, for the "not found" case — an implementation-time test
  design detail).
- Process death: §16/§26.

## 25. Real-Device Test Plan

Directly implements Decision 20's ten-item mandatory list, verbatim — not
restated here in full (see `DAY-19-M9-ARCHITECTURAL-DECISIONS.md` §Decision
20) — with the two genuinely new methodology pieces this codebase has
never needed before called out explicitly:
1. **Dual-process death** (item 9b): `adb shell am force-stop
   com.softwaremine.dps` kills the DPS app process; **separately**,
   `adb shell settings put secure enabled_accessibility_services <list
   without this app's service>` + `adb shell settings put secure
   accessibility_enabled 0` (or toggling the specific service off) disables
   the accessibility service independently — these are two different
   `adb` operations, exercised both together and individually, since
   Decision 13 requires all four combinations (DPS alive/dead × service
   alive/dead) to behave safely.
2. **Exactly-once proof** (item 10): read the test app's own real,
   observable button text exactly once, post-recovery, asserting it reads
   `"Tapped"` — never `"Tap me"` (never happened) and never evidence of a
   second, redundant tap having somehow occurred (not independently
   detectable from text alone, which is why the JVM-level call-counting
   tests in §23 are the real proof of "never re-executes" — the
   real-device test proves the *user-visible* outcome is correct exactly
   once, not the internal call count, mirroring exactly how M8's own
   `ConfirmationProcessDeathInstrumentedTest` combined a real-device
   outcome check with a separate JVM-level call-count assertion).

## 26. Regression Plan

Full JVM suite (baseline: 743/743 per the M8 final report) must remain
100% green plus the new M9 additions (§23) — any change to an
already-exhaustive `when` (§6, §9, §19) is compiler-enforced to require
a new branch, so a missed case fails the *build*, not merely a test,
before any regression test even runs. Full instrumented re-run required
across every M1-M8 suite this session's own M8 work already re-verified
(`ExecutionVerifierInstrumentedTest`, `SecretaryLiveWiringInstrumentedTest`,
`SecretaryLiveWiringProductivityInstrumentedTest`, `AndroidToolsInstrumentedTest`,
`ProductivityInstrumentedTest`, `CalendarWriterInstrumentedTest`, every
process-death-shaped suite, `ConfirmationProcessDeathInstrumentedTest`) —
**zero of these files are touched by this plan** (§27's FROZEN list), so a
regression here would indicate an unexpected interaction, not an expected
one, and must be investigated as such per the same evidence-based
discipline M8's own final report applied (pre-existing vs. environmental
vs. genuine regression, never assumed).

## 27. File Change Matrix

**NEW PRODUCTION FILES**

| File | Purpose | Locked decision | Test coverage | Risk |
|---|---|---|---|---|
| `domain/automation/AutomationEngine.kt` | Domain interface, 4 methods | Decision 1/5 | Fake-backed JVM tests via `AndroidAutomationToolTest` | Low |
| `domain/automation/AutomationOutcome.kt` (or nested) | Bounded execution-time outcome shape | Decision 15 | Same as above | Low |
| `domain/automation/AutomationAppRegistry.kt` | App-name → package lookup | Decision 6 | `AutomationAppRegistryTest.kt` | Low |
| `domain/automation/ElementMatcher.kt` | Deterministic node matching | Decision 7 | `ElementMatcherTest.kt` | Medium (fake-node-tree fidelity, §33) |
| `domain/secretary/PendingAutomationAction.kt` | Pre-action recovery record | Decision 12 | Extends `PersistentRecoveryStoreTest.kt` | Medium (new persisted shape) |
| `data/android/automation/AndroidAutomationEngine.kt` | Real, service-backed implementation | Decision 1/2 | Instrumented only (§24) | High (new Android surface) |
| `data/android/automation/DpsAutomationService.kt` | The `AccessibilityService` itself | Decision 2 | Instrumented only (§24/§25) | High (new process/lifecycle) |
| `data/android/tool/AndroidAutomationTool.kt` | Thin `AndroidTool` adapter | Decision 1 | `AndroidAutomationToolTest.kt` | Low |

**MODIFIED PRODUCTION FILES**

| File | Current responsibility | Exact change | Locked decision | Risk |
|---|---|---|---|---|
| `domain/tool/AndroidTool.kt` | `ToolId` enum | +1 entry, `AUTOMATION` (line ~122) | Decision 1 | Low — compiler-enforced |
| `domain/intent/DpsIntent.kt` | `IntentType`/`requiredFields` | +1 `IntentType` entry (~line 118-128); +1 `requiredFields` branch (~line 395) | Decision 8 | Low — compiler-enforced |
| `ai/intent/ToolSelector.kt` | Intent → ToolCall | +1 `when` branch (~line 136-142) | Decision 8 | Low — compiler-enforced |
| `ai/intent/ClarificationEngine.kt` | Completeness + question phrasing | +1 question-phrasing branch (~line 216) | Decision 8 | Low |
| `ai/intent/ToolResponseGenerator.kt` | Result phrasing | +1 `phrasePermission` branch (~line 160), +1 `defaultSuccess` branch (~line 193) | Decision 22 | Low |
| `ai/intent/IntentPromptBuilder.kt` | Classification prompt | +1 rule bullet | Decision 8 | Medium (prompt-quality risk, real-device classification test needed) |
| `domain/permission/DpsPermission.kt` | Permission enum | +1 entry, `AUTOMATION_ACCESSIBILITY` (after line 84) | Decision 3 | Low — compiler-enforced |
| `data/android/permission/AndroidPermissionMapping.kt` | Permission → Android string | Signature `String` → `String?`; +1 `null` case | §4's discovered, resolved conflict | Low, but the one file outside the architectural decisions' own explicit list |
| `data/android/permission/AndroidPermissionManager.kt` | Permission state queries | +1 `specialAccessState()` branch | Decision 3 | Low |
| `domain/secretary/RiskPolicy.kt` | Risk classification | +1 `classify()` branch | Decision 9 | Low |
| `ai/secretary/SecretaryOrchestrator.kt` | Orchestration | +1 `askConfirmationFor` branch + 1 new private fn (§19); +1 `recordOutcome` conditional (§14); +1 new field + `handle()` branch mirroring `pendingVerification` (§15/§16); `reset()` gains 1 line | Decisions 9/10/12 | Medium — the single largest-surface file touched, same file M8 already touched safely |
| `data/android/secretary/PersistentRecoveryStore.kt` | Recovery persistence | +1 key, +3 methods (mirroring the verification triad) | Decision 12 | Low — pure additive, same pattern 3x already proven |
| `di/AiContainer.kt` | Composition root | +1 tool registration; +1 `AutomationEngine`/`AndroidAutomationEngine` wiring; `secretaryOrchestrator` gains 1 new dependency reference if a standalone `AutomationVerifier`/similar needs container-level wiring | Decision 1 | Low |

**NEW TEST FILES**: `RiskPolicyTest.kt` (extended, not new — existing
file), `AutomationAppRegistryTest.kt`, `ElementMatcherTest.kt`,
`AndroidAutomationToolTest.kt`, new instrumented
`AutomationPipelineInstrumentedTest.kt`, new instrumented
`AutomationProcessDeathInstrumentedTest.kt` (mirroring
`ConfirmationProcessDeathInstrumentedTest.kt`'s own two-phase shape), new
instrumented `AutomationServiceDeathInstrumentedTest.kt`.

**MODIFIED TEST FILES**: `IntentJsonParserTest.kt`, `ToolSelectorTest.kt`,
`ClarificationEngineTest.kt`, `SecretaryOrchestratorTest.kt`,
`PersistentRecoveryStoreTest.kt` — each gains new cases mirroring an
existing, already-proven pattern (§23); **no existing test's own
assertions change**, only additions.

**TEST APP FILES** (separate module/project, §22): `MainActivity.kt`, one
layout XML, its own minimal `AndroidManifest.xml` — never merged into the
main `app` module.

**MANIFEST FILES**: `app/src/main/AndroidManifest.xml` gains one
`<service>` declaration (§8) and one new `<uses-permission>`? — **no**,
confirmed: `BIND_ACCESSIBILITY_SERVICE` is declared *by the service*, not
requested *of* the OS by the app the way `READ_CALENDAR` is — no new
`<uses-permission>` line is needed for accessibility itself (this matches
how `SCHEDULE_EXACT_ALARM` **is** declared as a `<uses-permission>` — a
genuine, confirmed difference between the two special-access mechanisms
worth stating explicitly rather than assumed). One new `<queries>` entry
for the test-app package if it is ever launched via implicit intent
resolution (`openApp`'s `getLaunchIntentForPackage` does not require a
`<queries>` entry the way `queryIntentActivities` does — verify this
exact distinction during implementation; flagged, not resolved, since it
is a genuine platform-behavior detail worth confirming empirically before
locking whether a `<queries>` entry is actually required for the test app
specifically).

**RESOURCE/XML FILES**: `res/xml/automation_accessibility_config.xml`
(new, §8).

**DOCUMENTATION**: this plan itself; a future `DAY-21-M9-...-COMPLETION.md`
at implementation's end, per this project's own established convention —
not created now.

**FROZEN FILES** (confirmed untouched, with reason):
`ToolOrchestrator.kt`/`DefaultToolExecutor.kt`/`ToolResult.kt`/
`ToolRegistry.kt` — Decision 1 explicitly avoids Option D (no parallel
orchestration path); every new capability flows through these unmodified,
exactly like every prior milestone's own tool addition. `ExecutionVerifier.kt`/
`VerificationOutcome.kt` — Decision 10 explicitly reuses `VerificationOutcome`'s
vocabulary via a new, separate function, never touching M7's own frozen
class. `ConfirmationParser.kt`/`PendingConfirmation.kt`/`SecretaryState.kt` —
Decision 10-of-the-decisions/§19 reuse verbatim. `OperationCheckpoint`'s own
`OperationType` enum (`ExecutionRecoveryState.kt:220-226`) — Decision 12
locked a *sibling* type, not a fourth `OperationType` case, specifically
to avoid touching this frozen shape. `continuePlan`/`parkRemainder`
(within `SecretaryOrchestrator.kt`, but this specific *function*, not the
whole file) — confirmed needs zero edits (§18). All M6 Room files, all
`data/android/proactive/*` files (M4, unrelated), `ReminderReceiver`/
`ReminderBootReceiver` (unrelated components) — none reference or are
referenced by anything in this plan.

## 28. Dependency Audit

- **New Gradle dependency**: **none**. `AccessibilityService` is a
  platform SDK class (`android.accessibilityservice.*`), already available
  via the existing `compileSdk 35`/`minSdk 26` — no new artifact.
- **New Android permission**: **one addition to the domain model**
  (`DpsPermission.AUTOMATION_ACCESSIBILITY`), but **no new
  `<uses-permission>` manifest line** (§27's own finding — accessibility
  enablement is not requested via a manifest permission the way
  `READ_CALENDAR` is).
- **Accessibility service declaration**: yes, one new `<service>` element
  (§8/§27) — a manifest change, not a dependency.
- **XML resource**: yes, one new file (§8/§27).
- **Room migration**: **none** — `PendingAutomationAction` lives in
  `PersistentRecoveryStore`'s existing `SharedPreferences`-plus-JSON file,
  not Room; M6's own Room database (`DpsMemoryDatabase`) is untouched.
- **Serialization change**: `AndroidPermissionMapping.androidName()`'s
  return-type change (§4) is a Kotlin signature change, not a
  `kotlinx.serialization` schema change — no `@Serializable` type is
  affected.

**No new dependency is required or proposed.**

## 29. Implementation Sequence

The master prompt's own proposed A–U sequence is sound; adopted with one
explicit reordering and its reason:

- **Phase A** — Baseline re-verification (this plan's §3, re-run
  immediately before Phase B in case time has passed).
- **Phase B** — Automation domain models (§5): `AutomationEngine`
  interface, `AutomationOutcome`, `AutomationAppRegistry`,
  `ElementMatcher`, `PendingAutomationAction` — all pure Kotlin, all
  independently JVM-testable before any Android code exists.
- **Phase C** — `ToolId`/registry/intent integration (§6): the seven
  small, compiler-enforced `when`-branch additions, verified by a clean
  build (any missed exhaustive case fails immediately) — deliberately
  before Phase D/E/G, so the whole classification→dispatch chain compiles
  and is JVM-testable against a fake tool before any real
  `AndroidAutomationTool`/service exists.
- **Phase D** — `AndroidAutomationTool` (§6/§27) against a **fake**
  `AutomationEngine` — proving the tool-adapter layer in isolation, before
  the real, Android-heavy implementation.
- **Phase E** — `AccessibilityService` infrastructure (§8): the service
  class, manifest declaration, XML config — scaffolding only, no real
  action/observation logic yet, proving the new process/lifecycle surface
  exists and connects before anything depends on it (**deliberately
  reordered ahead of the master prompt's own suggestion of doing
  permission integration first, since the permission check in Decision 3
  needs a real `DpsAutomationService::class` reference to check against —
  the service class must exist, even if inert, before Phase F can be
  meaningfully implemented**).
- **Phase F** — Accessibility permission integration (§9): `DpsPermission`/
  `AndroidPermissionMapping`/`AndroidPermissionManager` changes, now
  checkable against Phase E's real (if still inert) service.
- **Phase G** — `AndroidAutomationEngine`'s real implementation (§7/§8):
  wiring the tool/engine interface to the now-real service.
- **Phase H** — App targeting (§10) — pure, already built in Phase B,
  wired into the real `openApp` here.
- **Phase I** — UI targeting (§11) — pure, already built in Phase B,
  wired into the real `findElement`/`tap` here.
- **Phase J** — Phase 1's bounded interaction end-to-end (§12) — the
  first point the full pipeline runs against a real device.
- **Phase K** — Observation (§13).
- **Phase L** — Verification (§14).
- **Phase M** — `PendingAutomationAction`/recovery (§15/§16/§17) — placed
  after the base pipeline works (Phase J-L), since recovery only makes
  sense once there is a real action/observation/verification sequence to
  recover.
- **Phase N** — JVM tests (§23) — many of these are actually written
  incrementally alongside Phases B-D/H-I per this project's own
  established practice (M7/M8 both wrote tests alongside, not strictly
  after, each phase) — this phase is the consolidation/completeness pass,
  not the first time any JVM test is written.
- **Phase O** — Instrumented tests (§24).
- **Phase P** — Genuine process-death tests (§25/§26 of the master
  prompt).
- **Phase Q** — Service-death validation (§17/§25).
- **Phase R** — Full M1–M8 regression (§26).
- **Phase S** — Cleanup (device-state hygiene, mirroring M7/M8's own
  established stray-artifact-check discipline).
- **Phase T** — Independent audit.
- **Phase U** — M9 completion decision.

## 30. Acceptance Matrix

The master prompt's own IDs, each mapped to this plan's exact evidence
source:

| ID | Requirement | Evidence source |
|---|---|---|
| M9-AUTO-01 | `ToolId.AUTOMATION` exists, registered | §5, §6, `AiContainer.kt` registration (§27) |
| M9-AUTO-02 | `AutomationEngine` behind thin `AndroidAutomationTool` | §6, §7, §27's file matrix |
| M9-AUTO-03 | `AccessibilityService` declared, lifecycle-managed | §8, §24 |
| M9-PERM-01 | Accessibility enablement uses existing special-access architecture | §9, §4's resolved conflict |
| M9-AUTO-04 | Known test app targeted deterministically | §10, §22 |
| M9-AUTO-05 | Known UI element found deterministically | §11, §22 |
| M9-AUTO-06 | One bounded interaction executes | §12, §25 |
| M9-AUTO-07 | Post-action UI state observed | §13, §24 |
| M9-AUTO-08 | Observed state verified | §14, §24 |
| M9-AUTO-09 | Action success ≠ verification success | §12's own explicit rule, §23's dedicated test |
| M9-REC-01 | `PendingAutomationAction` persists at the correct boundary | §15 |
| M9-REC-02 | Process death between action and observation recoverable | §16, §25's dual-process proof |
| M9-REC-03 | Recovery never blindly re-executes | §16's hard rule, §23's call-counting test |
| M9-REC-04 | Recovery state clears after resolution | §15's clear-on-any-resolution rule |
| M9-RISK-01 | `RiskPolicy` remains sole risk authority | §19, zero new risk types anywhere in §27 |
| M9-RISK-02 | No LLM risk decision | §6's locked LLM boundary — model never sees risk-relevant data |
| M9-CONF-01 | `PendingConfirmation` remains sole confirmation mechanism | §19, zero new confirmation types in §27 |
| M9-MULTI-01 | M2 multi-step architecture intact | §18, zero edits to `continuePlan`/`parkRemainder` |
| M9-SEC-01 | Prohibited sensitive automation cannot execute | §20, Decision 16's denylist, planned as production code from Phase G |
| M9-PRIV-01 | Arbitrary screen data not persisted | §21, §15's field-level restriction |
| M9-TEST-01 | JVM tests pass | §23, Phase N |
| M9-TEST-02 | Instrumented tests pass | §24, Phase O |
| M9-TEST-03 | Genuine process-death validation passes | §25/§26, Phase P |
| M9-TEST-04 | M1–M8 regression passes | §26, Phase R |
| M9-SCOPE-01 | No M10/M11/M12 scope leakage | §32 |

## 31. Risk Register

| Risk | Likelihood | Impact | Mitigation | Blocking |
|---|---|---|---|---|
| `AccessibilityService` lifecycle proves harder to test deterministically than any existing single-process component | High (certain, given zero prior precedent) | Medium | §25's explicit new dual-process methodology, budgeted as genuinely new effort, not assumed to be a copy-paste of existing scripts | No |
| Android-version-specific accessibility behavior differences across `minSdk 26`–`targetSdk 35` | Low (per M9 investigation's own §21 finding — no version gate identified) | Low | Re-verify empirically during Phase J/O on the actual test device(s) available | No |
| UI instability/staleness even against the fully-controlled test app (animation timing, first-frame-not-ready) | Low (Decision 19's own "intentionally boring" design minimizes this) | Low | §13's bounded re-observation | No |
| Asynchronous rendering causing a false `NotFound` immediately after `openApp` | Medium | Low | §13's bounded polling already anticipates this | No |
| Duplicate action across process death | Low (given §16's checkpoint discipline, directly modeled on 3x-proven `OperationCheckpoint`) | High if it occurred | §25's real-device exactly-once proof | No |
| Privacy exposure via an overlooked un-redacted log line | Low | Medium (policy/trust risk) | §21's blanket "every node-text log call uses `redact()`" rule, enforced by code review during implementation | No |
| Permission UX friction (accessibility's own OS warning screen deters users) | Medium | Low (product risk, not implementation risk) | Decision 22, already locked — out of this plan's own control to mitigate further | No |
| Test determinism against the first-party test app itself proves insufficient (unexpected OEM/keyboard/system overlay interference) | Low | Medium | §22's deliberately minimal, no-input-field design (a button, not a text field) sidesteps most such interference | No |
| Regression in M1–M8 from the seven small `when`-branch additions | Very low (compiler-enforced exhaustiveness makes a missed case a build failure, not a silent bug) | Low if it occurred | §26's full regression pass | No |

**No risk identified rises to blocking.**

## 32. Explicit Non-Goals

Restated, confirmed unaffected by every section above: unrestricted phone
control, autonomous browsing, autonomous social-media activity, banking,
financial transactions, purchasing, passwords, OTP, PIN, security bypass,
permission bypass, root access, exploit techniques, arbitrary screenshots
or screen recording (§21 — zero such API called anywhere in this plan),
autonomous retries (§23's own regression-test framing proves their
*absence*, not merely their omission), autonomous replanning,
alternative-action selection, proactive intelligence (M10), background
agent behavior, advanced voice (M11), productization (M12). Every file in
§27 lives in `domain/automation`, `data/android/automation`,
`domain/secretary`, `ai/intent`, `ai/secretary`, `di`, or a test/test-app
file — no M9/M10/M11/M12 boundary is crossed.

## 33. Remaining Micro-Decisions

The three items `DAY-19-M9-ARCHITECTURAL-DECISIONS.md` §27 flagged as
non-blocking, resolved here where doing so had genuine implementation
consequences, per the master prompt's own instruction:

1. **`IntentParameters` field mapping** — **resolved** (§5): `title`
   carries the target app name; no new field, no new `IntentField`. Chosen
   because it exactly mirrors the established `REMEMBER_FACT`/
   `RECALL_FACT`/`FORGET_FACT` precedent of reusing `title` for a
   type-specific "subject" meaning — no architectural decision reopened,
   since Decision 8 only locked the *shape* (existing `IntentParameters`,
   no new schema), not the specific field.
2. **Gradle module structure for the test app** — **not resolved here**,
   correctly deferred to the start of implementation (Phase B/E) — a pure
   build-tooling choice (separate Gradle module within the same repo vs.
   a fully separate project) with zero effect on any of the 23 locked
   decisions or this plan's own file matrix either way.
3. **`AccessibilityNodeInfo` JVM-testability wrapper** — **partially
   resolved**: `ElementMatcher` (§11/§27) is planned to operate against a
   small, pure node abstraction (an interface exposing only
   `resourceId`/`contentDescription`/`text`/children — not the real,
   `final`, Android-only `AccessibilityNodeInfo` class), fakeable
   directly in JVM tests with no Robolectric dependency, consistent with
   this project's own stated physical-device-only testing policy (ADR-009,
   cited in the M9 investigation's own §22). This is additive (one new
   small interface), not architectural, and does not reopen Decision 7.

**No remaining micro-decision blocks implementation.**

## 34. Final Readiness Verdict

**READY FOR IMPLEMENTATION**

Every locked decision in `DAY-19-M9-ARCHITECTURAL-DECISIONS.md` is mapped
to an exact file, an exact change, and an exact test (§5–§27). The one
genuine implementation-detail conflict discovered during re-audit (§4,
`AndroidPermissionMapping.androidName()`'s exhaustive non-nullable
signature) is resolved within the locked contract, not by reopening it.
The one genuine design-layering question the decisions document left
implicit (§6, how a five-entry action vocabulary composes with "one
intent = one plan step") is resolved by direct precedent
(`AndroidCalendarTool`'s own internal multi-step-behind-one-`ToolCall`
shape), not by invention. `AccessibilityService` integration, permission
integration, action/observation/verification boundaries, the recovery
boundary, the process-death test, service-death behavior, the test app,
and the regression strategy are each defined at the file level, not left
as prose aspiration. All three previously-open micro-decisions are
resolved or correctly deferred with a stated, non-blocking reason. No
unresolved blocking architecture remains.
