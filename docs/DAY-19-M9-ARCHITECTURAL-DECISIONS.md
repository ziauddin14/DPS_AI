# Day 19 — M9 Architectural Decisions: Controlled Android Device & App Automation

**Status: ARCHITECTURAL DECISIONS ONLY. No source, test, manifest, or
Gradle file was modified to produce this document.** Every decision below
was checked against live source re-verified at the time of writing (§2),
not assumed from the investigation report alone.

---

## 1. Decision Summary

23 decisions, all locked, none deferred to the implementation-planning
phase as "TBD." The architecture is small on purpose: one new `AndroidTool`
(`AUTOMATION`) behind one new `AutomationEngine` seam, one new
`AccessibilityService` that owns nothing but event/action mechanics, zero
new risk or confirmation systems (M8 reused verbatim), zero new
multi-step machinery (M2 reused verbatim), and exactly one new persisted
record — an `OperationCheckpoint`-shaped pre-action marker — closing the
one genuine gap the investigation found. Everything else reuses existing
M1–M8 vocabulary under its existing names.

## 2. Current Verified Architecture

Re-confirmed directly, not carried over from the investigation report
unchecked:

```
git log --oneline -3
3db0a02 some random commit                (M8, HEAD)
9dff80d M7 Investigation and some Impleemntation are completed
8e80755 Added Long Term memory M6 Complete
```
Working tree clean except the new, untracked `docs/DAY-18-M9-INVESTIGATION-REPORT.md`.
No automation code, no `<service>` element, no accessibility dependency
exists anywhere in the tree — reconfirmed by the investigation's own grep,
unchanged since.

Load-bearing shapes re-verified for this document specifically:
- `OperationCheckpoint` (`domain/secretary/ExecutionRecoveryState.kt:198-218`):
  `{operationType: OperationType, operationId: Int, title: String,
  requestedAtMillis: Long, schemaVersion: Int}`. `OperationType`
  (`:220-226`) is a plain 3-case enum (`CREATE_TASK, CREATE_REMINDER,
  CREATE_EVENT`) with an existing `UNUSED_OPERATION_ID = -1` sentinel
  precedent for "this operation type has no natural id" (`CREATE_EVENT`'s
  own case) — directly reusable for Decision 12.
- `AndroidPermissionManager.specialAccessState()`: a literal
  `when (permission) { SCHEDULE_EXACT_ALARM -> ...; else -> UNKNOWN }` —
  confirmed, single-case, the exact extension point for Decision 3.
- `RiskPolicy.classify(intent: DpsIntent): RiskLevel` — pure, two-case,
  no model call — confirmed unchanged, the exact reuse target for
  Decision 9.
- `VerificationOutcome` — sealed interface, exactly four cases
  (`Verified`/`Mismatch`/`NotFound`/`ObservationFailed`), consumed only by
  `ExecutionVerifier` and `ToolResponseGenerator.describeVerification` —
  confirmed unchanged, the exact reuse target for Decision 10.
- `ToolResult` — sealed interface, exactly seven cases
  (`Success/Failure/PermissionRequired/Cancelled/Unsupported/Timeout/Error`),
  no case shaped for "element not found"/"ambiguous match" — confirmed,
  the gap Decision 15 must close without touching this frozen type.

---

## DECISION 1
**Automation Architecture**

**CURRENT EVIDENCE:**
`ToolRegistry`/`AndroidTool`/`DefaultToolExecutor` are generic over any
`ToolId` + `operation` string pair; `ToolOrchestrator` knows nothing about
what a tool does internally (`AndroidCalendarTool.createEvent()` already
internally sequences resolve→build→insert→read-back as one opaque call,
never exposing sub-steps to the orchestrator). `SecretaryOrchestrator`'s
`proceedToExecution` is the one seam risk/permission/confirmation already
runs through for every tool, tool-agnostically.

**OPTIONS:**
A. Extend `AndroidTool` directly (add automation operations to an existing
   tool, e.g. a new `ToolId`-less capability bolted onto an unrelated tool).
B. Create a dedicated `AutomationTool : AndroidTool` — one new `ToolId`,
   thin, delegating all real work to a separate object.
C. Create an `AutomationEngine` (owns AccessibilityService binding, node
   search, action dispatch, observation) behind one dedicated
   `AutomationTool : AndroidTool`.
D. A parallel orchestration path outside `ToolOrchestrator`/`ToolRegistry`
   entirely.

**RECOMMENDED:** C.

**REASON:** A is rejected outright — no existing tool's domain (calendar,
task, contacts, memory) has anything to do with UI automation, and bolting
it onto one would violate every existing tool's own single-responsibility
doc. B alone (a fat `AutomationTool` containing AccessibilityService logic
directly) fails the "smallest architecture that can safely grow" test:
AccessibilityService binding/lifecycle is a genuinely different concern
from "what a tool does when called" and deserves its own boundary,
testable independently on the JVM behind an interface, mirroring exactly
how `AndroidCalendarTool` is a thin adapter over `CalendarWriter` and
`AndroidTaskTool` over `AndroidTaskStore` — every existing tool already
follows the "thin `AndroidTool`, real logic in a dedicated class" shape. D
is rejected: it would let automation bypass `DefaultToolExecutor`'s
permission gate and `SecretaryOrchestrator.proceedToExecution`'s risk/
confirmation gate, which is precisely the "no second decision path" this
codebase's entire M1–M8 history has never done for any capability.

**LOCKED DECISION:** One new `ToolId.AUTOMATION`, one new
`AndroidAutomationTool : AndroidTool` (thin, mirrors `AndroidCalendarTool`'s
shape), delegating all real mechanics to a new `AutomationEngine`
interface (mirrors `TaskRepository`/`CalendarEventReader`'s own
shape — a plain Kotlin interface the tool depends on, implemented by
something that does own the AccessibilityService binding). No change to
`ToolRegistry`/`ToolOrchestrator`/`SecretaryOrchestrator`'s own structure —
only a new registration entry, exactly like every prior milestone's own
tool addition.

**IMPLEMENTATION CONSEQUENCE:** New files only:
`domain/tool/AndroidTool.kt`'s `ToolId` enum gains one entry (a one-line,
compiler-enforced-exhaustive addition, same shape M6 used for `MEMORY`);
new `data/android/automation/AndroidAutomationTool.kt`; new
`domain/automation/AutomationEngine.kt` interface; new
`data/android/automation/AndroidAutomationEngine.kt` (the real,
AccessibilityService-backed implementation, wired only in `AiContainer`).

**TEST CONSEQUENCE:** `AndroidAutomationTool` is JVM-testable against a
fake `AutomationEngine`, exactly like `AndroidTaskTool` is already tested
against a fake `TaskRepository` — no Android dependency for the tool's own
argument-parsing/dispatch logic.

**NON-GOAL / BOUNDARY:** No generic "automation framework" with pluggable
action registries, action DSLs, or scripting — `AndroidAutomationTool`'s
`operations: Set<String>` is exactly as bounded as Decision 4's locked
vocabulary, nothing more.

---

## DECISION 2
**AccessibilityService Role**

**CURRENT EVIDENCE:** No `<service>` exists (§2). `DefaultToolExecutor`'s
own doc states its philosophy explicitly: "Sequencing only... each of
those lives in a pure class that can be tested without a model" — the
established codebase convention is thin mechanical layers, thick pure
decision layers.

**OPTIONS:**
A. The service is a thin mechanical layer only (events in, actions out) —
   `AutomationEngine`'s implementation lives largely *inside* the service.
B. The service also owns targeting/matching/risk decisions.
C. The service owns nothing; a separate bound component drives it via
   binder calls for every micro-step.

**RECOMMENDED:** A, with `AutomationEngine`'s *interface* in `domain/`
(pure Kotlin) and its Android-backed implementation using the service only
for what only a `Service` can do (receive `AccessibilityEvent`, hold live
`AccessibilityNodeInfo` references, call `performAction`/`dispatchGesture`).

**REASON:** Mirrors `ReminderScheduler`/`NotificationPresenter`'s own
established pattern exactly: the thinnest possible Android-owning class,
called into by orchestration, never calling back into orchestration
itself. C is rejected as needless ceremony — the service *is* the
"separate bound component"; adding another layer between it and
`AutomationEngine` duplicates B's own AndroidTool→domain-interface
boundary for no benefit.

**LOCKED DECISION:** The `AccessibilityService` subclass's only
responsibilities: (1) receive `onAccessibilityEvent`/`onServiceConnected`/
`onInterrupt`/`onDestroy`, (2) expose the current root node / a bounded
search result on request, (3) perform one bounded action
(`performAction`/`dispatchGesture`) on a freshly-supplied node reference,
(4) report the raw platform-level result (action succeeded/failed
per the API's own boolean/exception, node found/not-found) back through
`AutomationEngine`'s interface. It owns **zero** natural-language
understanding, **zero** `RiskPolicy` knowledge, **zero** confirmation
state, **zero** planning — it does not know what "sending a message" means,
only "click this node" / "set this node's text to this string."

**IMPLEMENTATION CONSEQUENCE:** `AutomationEngine` (domain interface) is
the only thing `AndroidAutomationTool` depends on; the service class itself
is never referenced outside `data/android/automation/` and `AiContainer`'s
wiring, exactly like every other Android-owning class in this codebase.

**TEST CONSEQUENCE:** Every decision about *what* to click/type (target
resolution, risk, confirmation) is JVM-testable against a fake
`AutomationEngine`; only the service's own mechanical correctness
(does `performAction` actually work) needs a real device.

**NON-GOAL / BOUNDARY:** The service must never call
`SecretaryOrchestrator`, `ToolOrchestrator`, or any AI-layer class directly
— exactly mirroring `ProactiveCheckWorker`'s own documented, verified "no
dependency, direct or transitive, on the AI subsystem" property (§3 of the
investigation), now extended to the automation service as an explicit,
locked requirement, not merely a nice-to-have.

---

## DECISION 3
**Accessibility Permission Model**

**CURRENT EVIDENCE:** `PermissionKind` has exactly two values,
`RUNTIME`/`SPECIAL_ACCESS`. `AndroidPermissionManager.specialAccessState()`
is a `when (permission)` with one real case
(`SCHEDULE_EXACT_ALARM: query AlarmManager.canScheduleExactAlarms(),
grant only via settings`). `DpsPermission.kt`'s own doc already states the
general principle this decision extends: "Android does not treat all
permissions alike... A permission model with a single 'request' path would
therefore be silently broken."

**OPTIONS:**
A. A third `PermissionKind` (e.g. `ACCESSIBILITY_SERVICE`).
B. Reuse `PermissionKind.SPECIAL_ACCESS`, add
   `DpsPermission.AUTOMATION_ACCESSIBILITY` as a new enum entry, add one
   new `when` branch to `specialAccessState()`.
C. Model it entirely outside `DpsPermission`/`PermissionManager`, as a
   bespoke check only `AndroidAutomationTool` performs.

**RECOMMENDED:** B.

**REASON:** `SPECIAL_ACCESS`'s existing contract — "granted only through a
dedicated system settings screen... each has its own query API... cannot
share the runtime path" — describes accessibility-service enablement
exactly: `AccessibilityManager.getEnabledAccessibilityServiceList()` (or
`isEnabled` + a component-name check) is the query API,
`Settings.ACTION_ACCESSIBILITY_SETTINGS` is the settings-only grant path,
and no runtime dialog exists — this is not a stretch of the existing
abstraction, it is a textbook instance of it. A is rejected: it would
duplicate `SPECIAL_ACCESS`'s entire contract under a new name for zero
behavioral difference — the exact "second permission system" the M9
master prompt explicitly forbids. C is rejected: it would mean
`AndroidAutomationTool`'s permission handling can't flow through
`DefaultToolExecutor`'s existing, uniform
`permissionManager.missing(tool.requiredPermissions)` check, forcing a
bespoke pre-check special-cased only for this one tool — inconsistent with
every other tool in the registry.

**LOCKED DECISION:** Add `DpsPermission.AUTOMATION_ACCESSIBILITY(kind =
PermissionKind.SPECIAL_ACCESS)`. `AndroidPermissionManager.specialAccessState()`
gains one branch: query the real accessibility-enabled-services list for
this app's own service component; `GRANTED` if present, `REQUIRES_SETTINGS`
otherwise (mirroring `SCHEDULE_EXACT_ALARM`'s own `GRANTED`/
`REQUIRES_SETTINGS` shape exactly — no `DENIED`/`PERMANENTLY_DENIED`
distinction applies here, same as the exact-alarm case, since there is no
runtime dialog to have "denied"). `AndroidAutomationTool.requiredPermissions
= setOf(DpsPermission.AUTOMATION_ACCESSIBILITY)` — flows through the
*existing* `DefaultToolExecutor` check and the *existing* M8 permission
precheck (`SecretaryOrchestrator.missingPermissionsFor`) with **zero**
new code in either of those two classes. Process-death behavior: identical
to `SCHEDULE_EXACT_ALARM` today — it is a live platform query, not a
persisted app-level flag, so it is always correct on every fresh read,
process death or not. Service-disabled-later behavior: the next
`missingPermissionsFor` check catches it naturally — no new detection
mechanism needed.

**IMPLEMENTATION CONSEQUENCE:** One new `DpsPermission` enum entry, one new
`when` branch in `specialAccessState()`, one new entry in
`AndroidPermissionMapping` if that class maps to a settings `Intent` per
permission (verify at planning time) — no other file in the permission
stack changes.

**TEST CONSEQUENCE:** JVM-testable exactly like every other
`PermissionManager` fake already used throughout the test suite (a fake
returning `GRANTED`/`REQUIRES_SETTINGS` for the new enum value); real-device
proof needed only for the actual `AccessibilityManager` query (Decision 20,
item 2).

**NON-GOAL / BOUNDARY:** DPS never enables the service itself, never
suppresses or dismisses the OS's own accessibility-enablement warning
screen, and never claims a false "enabled" state — the query always reads
real platform state, never an app-side assumption.

---

## DECISION 4
**Automation Action Vocabulary (Phase 1)**

**CURRENT EVIDENCE:** Investigation §8's per-action feasibility table.

**OPTIONS:** Per-action inclusion, evaluated individually — no bundled
option set makes sense here.

**RECOMMENDED / LOCKED DECISION** (per action):

| Action | Status |
|---|---|
| `OPEN_APP` | **Phase 1** |
| `OBSERVE_UI` / `READ_VISIBLE_TEXT` | **Phase 1** (the OBSERVE half of every step) |
| `FIND_ELEMENT` | **Phase 1** (the targeting primitive every action needs) |
| `TAP` | **Phase 1** (the one interaction Decision 5's slice needs) |
| `TYPE_TEXT` | **Later phase** — not needed for Decision 5's read/tap slice; deferred until a slice actually requires text entry, at which point §17/Decision 16's sensitive-field denylist must exist first |
| `WAIT_FOR_ELEMENT` | **Phase 1** (required for §11's bounded-wait discipline, not optional) |
| `CLEAR_TEXT` | **Later phase**, bundled with `TYPE_TEXT` |
| `PRESS_BACK` / `PRESS_HOME` | **Later phase** — useful for multi-step navigation, not needed for one bounded interaction |
| `SELECT` | **Later phase** — same primitive as `TAP`, deferred only because Phase 1 has no selection-list scenario |
| `SCROLL` | **Explicitly deferred** — investigation's own §8 finding: only justified "as a means to find an element," not needed if Phase 1's one target element is on-screen without scrolling (Decision 5 requires choosing such a target) |
| `LONG_PRESS` | **Explicitly deferred** — investigation flagged this as higher-risk (context menus, destructive options) |
| `SWIPE` | **Rejected for Phase 1**, investigation's own explicit finding: "not deterministic enough for a first slice" |
| `OPEN_DEEP_LINK` | **Later phase** — `OPEN_APP` alone suffices for Decision 5 |

**REASON:** Every "Phase 1" entry is required by Decision 5's slice with
no substitute; every "later phase"/"deferred"/"rejected" entry either
requires infrastructure Phase 1 doesn't build (sensitive-field denylist for
`TYPE_TEXT`) or was explicitly flagged by the investigation as
insufficiently deterministic (`SWIPE`) or higher-risk without Phase-1-level
justification (`LONG_PRESS`).

**IMPLEMENTATION CONSEQUENCE:** `AndroidAutomationTool.operations` =
exactly `{open_app, observe_ui, find_element, tap, wait_for_element}` for
Phase 1 — five operations, not fifteen.

**TEST CONSEQUENCE:** Each operation gets its own JVM argument-validation
test and its own real-device proof (Decision 20) — five, not fifteen,
bounding both test-writing and real-device-verification effort to what
Phase 1 actually ships.

**NON-GOAL / BOUNDARY:** No action outside this five-operation set exists
in Phase 1's `operations` set at all — `DefaultToolExecutor`'s existing
`tool.supports(operation)` check already rejects anything else as
`Unsupported`, with zero new code.

---

## DECISION 5
**M9 Phase 1 Slice**

**CURRENT EVIDENCE:** Investigation §22/§24: a real third-party app's UI is
outside this project's control and changes on its own release schedule —
directly the same "dynamic UI/localization" risk category. Investigation
§24 explicitly recommends against a WhatsApp-shaped first slice.

**OPTIONS:**
A. Automate a real, popular third-party app (e.g. WhatsApp) for an
   immediately-relatable demo.
B. Automate a small, first-party test app built and controlled entirely by
   this project.
C. Automate a stock Android system app (e.g. Settings, Clock) as a
   "neutral" third party.

**RECOMMENDED:** B.

**REASON:** A fails determinism (§10's own risk list — text changes with
locale/app updates, outside this project's control) and conflates
"prove the pipeline works" with "prove it works against a specific popular
app's specific UI," which are different claims; a regression suite built
against WhatsApp would break on WhatsApp's own release schedule, not this
project's. C is rejected: system apps vary by OEM/Android version far more
than a typical third-party app (this project already treats OEM
variance as a real risk — `ReminderTriggerInstrumentedTest`'s own
real-device timing flakiness this session is direct evidence of it), and
their UI is equally outside this project's control.

**LOCKED DECISION:**
- **Target app**: a new, minimal first-party test app (Decision 19),
  package `com.softwaremine.dps.automationtarget` (a separate,
  independently-installed APK — never merged into the main app, so it can
  be excluded from release builds and from the Play listing entirely).
- **Target element**: one `Button` with a fixed, never-localized
  `android:id` (e.g. `automation_target_button`) and fixed English text,
  on the test app's single screen.
- **Supported interaction**: `TAP` on that one button.
- **Expected observation**: the button's own text changes to a fixed,
  predictable string (e.g. "Tapped") after a real tap — a first-party,
  fully-controlled, unambiguous signal, mirroring exactly how M7's own
  verification reads real backing state rather than trusting a tool's own
  claim.
- **Verification requirement**: `OBSERVE_UI`/`FIND_ELEMENT` re-reads the
  same node's text after the tap and compares it against the expected
  post-tap string — `Verified` if it matches, `Mismatch` if the node exists
  with different text, `NotFound` if the node is gone, `ObservationFailed`
  if the tree couldn't be read at all (Decision 10).

**IMPLEMENTATION CONSEQUENCE:** Exactly the five Decision-4 operations,
exercised once each, against exactly one app, one screen, one element —
no app-targeting ambiguity logic is exercised beyond the trivial
single-entry case (Decision 6 still needs its general rule *designed*,
but Phase 1 only needs to prove it against a one-entry allowlist).

**TEST CONSEQUENCE:** This *is* Decision 19/20's test-target and
real-device-validation subject — no separate target needed for testing
versus for the "real" Phase 1 capability; they are the same artifact by
design, since Phase 1's only released capability is proving the pipeline,
not automating a real end-user app yet.

**NON-GOAL / BOUNDARY:** Phase 1 ships no capability an end user would
describe as "DPS can use WhatsApp for me" — it ships a proven, verified
pipeline against a controlled target, explicitly not yet pointed at any
real third-party app. Pointing it at a real app is a separate, later,
explicitly-scoped decision (Decision 23).

---

## DECISION 6
**App Targeting**

**CURRENT EVIDENCE:** `<queries>` already requires per-app manifest
declaration (investigation §3/§9); `ToolId.fromName()` already establishes
the "fixed lookup table, not free-form string trust" pattern for a
different kind of name resolution.

**OPTIONS:**
A. Resolve a spoken app name via a fixed, developer-maintained
   `{spoken name → package name}` table.
B. Trust a model-generated package-name-shaped string directly.
C. Enumerate installed apps at runtime and fuzzy-match against labels.

**RECOMMENDED:** A.

**REASON:** B risks launching an unintended, possibly sensitive app on a
malformed or hallucinated package string — the investigation's own §9
locked conclusion. C requires `QUERY_ALL_PACKAGES` or broad `<queries>`
enumeration, which this project has already explicitly and permanently
rejected on privacy grounds (manifest's own comment, §3) — reopening that
would contradict a standing, deliberate project decision, not merely add
risk.

**LOCKED DECISION:** Canonical internal representation is **package name
(`String`), resolved only through a fixed allowlist** (for Phase 1: one
entry, Decision 5's test app). The LLM may only ever supply a spoken/typed
app name in `IntentParameters`; a new deterministic lookup (mirroring
`ToolId.fromName()`'s own shape) maps it to a package name or returns
`null`. `null` → `ToolResult.Failure` ("I don't know how to open that"),
never a guess. **Ambiguous** (a spoken name matching multiple allowlist
entries — not reachable in Phase 1's one-entry table, but the rule is
locked now for when it becomes reachable): `Failure`, listing candidates,
mirroring `ContactMatch.Ambiguous`'s own established shape. **Unknown
app**: same `Failure` path as `null`. **Uninstalled**:
`PackageManager.getLaunchIntentForPackage()` returning `null` →
`ToolResult.Unsupported`, mirroring `IntentLauncher.canHandle()`'s
existing check for WhatsApp/email/dialer. **Package mismatch** (allowlist
says a package that isn't actually installed): same uninstalled path,
checked live, never cached.

**IMPLEMENTATION CONSEQUENCE:** One new, small, pure lookup function/object
in `domain/automation/` (or alongside `AndroidAutomationTool`), plus the
existing `<queries>` entry for the Phase 1 test app's own package.

**TEST CONSEQUENCE:** JVM-testable exhaustively (found/ambiguous/unknown/
null cases) with zero Android dependency, exactly like `ToolId.fromName()`'s
own tests.

**NON-GOAL / BOUNDARY:** No app-name inference beyond exact,
case-normalized lookup-table matching — no "did you mean" fuzzy suggestion
logic in Phase 1.

---

## DECISION 7
**UI Element Targeting**

**CURRENT EVIDENCE:** Investigation §10's risk analysis (duplicate text,
localization, staleness, lazy-loading) and its own locked conclusion: "No
fuzzy/AI-based UI targeting is justified by any evidence gathered."
`ContactMatch.Ambiguous`'s existing "never auto-pick, always ask" precedent.

**OPTIONS:**
A. Priority-ordered exact matching: resource-id → content-description →
   exact visible text, each requiring a unique match.
B. Coordinate-based targeting (tap at x,y).
C. Fuzzy/similarity-based text matching.
D. Let the LLM directly supply node identifiers or coordinates.

**RECOMMENDED:** A.

**REASON:** B is rejected: coordinates are meaningless across screen
sizes/densities/orientations and carry zero semantic guarantee that the
right element is even present — the investigation's own instruction to
avoid coordinate-based automation "unless evidence proves it is necessary
and sufficiently reliable" is not met by any evidence gathered. C is
rejected per the investigation's own explicit, evidence-based conclusion.
D is rejected: it is exactly the "LLM-controlled arbitrary UI action" the
master prompt's non-goals forbid — the model never sees node identifiers or
coordinates at all under Decision 8's boundary.

**LOCKED DECISION:** Matching priority, most specific first:
1. `viewIdResourceName` exact match (most stable, present on well-built
   apps — Decision 5's own test app guarantees this for Phase 1).
2. `contentDescription` exact match.
3. Exact visible text (`getText()`), as a last resort.

Every match must be **unique** within the searched scope (the current
window, or an explicitly-narrowed container) — zero or multiple matches is
never resolved by picking one; it is a bounded failure outcome (Decision
15: `ELEMENT_NOT_FOUND`/`ELEMENT_AMBIGUOUS`). No signal is combined with
another via scoring/weighting — this is exact, deterministic,
single-signal-at-a-time matching, not a ranked heuristic.

**IMPLEMENTATION CONSEQUENCE:** One pure `ElementMatcher`-shaped function
in the automation domain package, taking a root node + a target
descriptor (id/description/text) + returning a bounded outcome — no
dependency on the live service beyond the node tree it's handed.

**TEST CONSEQUENCE:** JVM-testable against a hand-built fake node tree
(no real `AccessibilityNodeInfo` needed for the matching *logic* itself,
though the real class is `final` on-device and typically requires
Robolectric or a thin wrapper interface to fake in pure JVM tests — an
implementation-phase detail, not an architectural one).

**NON-GOAL / BOUNDARY:** No `bounds`/hierarchy-position/class-name-only
matching in Phase 1 (available as platform data, deliberately unused here —
listed in the investigation as a candidate signal, not locked in); no
scroll-to-find in Phase 1 (Decision 4 defers `SCROLL`).

---

## DECISION 8
**LLM Boundary**

**CURRENT EVIDENCE:** `ToolOrchestrator`'s own "one inference pass, not
two" doctrine (unchanged since Phase D); `RiskPolicy.classify` takes an
already-classified `DpsIntent`, never a model output directly (M8,
unchanged).

**OPTIONS:**
A. The model emits one `IntentType.AUTOMATION`-shaped intent, in the exact
   same `DpsIntent`/`IntentParameters` structure every other intent uses,
   naming only an app + a described target element + a described action —
   all subsequent steps are deterministic code.
B. The model emits a full structured multi-step automation plan.
C. The model is given the live node tree and asked to choose what to tap.

**RECOMMENDED:** A.

**REASON:** B duplicates `DpsIntent`'s own role (investigation §11's own
conclusion) and gives the model two chances to be wrong instead of one. C
is exactly the forbidden "LLM-controlled arbitrary UI action" — rejected
without further analysis, per the master prompt's own explicit prohibition
and the investigation's own §11 locked conclusion.

**LOCKED DECISION:** `IntentType.AUTOMATION` (new), carrying only existing
`IntentParameters` fields reused for a new purpose — e.g. `title` for the
target app name, `message` or a new narrow field for the described element/
action (exact field mapping is an implementation-phase micro-decision, not
an architectural one, since `IntentParameters` is already a flat,
extensible value bag). The model **never** receives node text, node ids,
bounds, or coordinates in its prompt, and its output is validated the
same way every other intent already is
(`ClarificationEngine.check`/`ToolSelector.select`) before touching
`AndroidAutomationTool` at all. Every one of the six items the master
prompt lists ("cannot invent arbitrary accessibility actions... bypass
RiskPolicy... confirmation... permission... verification... execute code")
is enforced structurally: the model's output is a `DpsIntent`, which
cannot itself perform an action — only `AndroidAutomationTool.execute()`
can, and it is reached exclusively through `DefaultToolExecutor` (permission
gate, unchanged) and `SecretaryOrchestrator.proceedToExecution` (risk/
confirmation gate, unchanged, Decision 9).

**IMPLEMENTATION CONSEQUENCE:** One new `IntentType` enum entry
(compiler-enforced-exhaustive addition across `ToolSelector`/
`ClarificationEngine`/`ToolResponseGenerator`, same mechanical shape every
prior `IntentType` addition already required); one new prompt-builder rule
bullet in `IntentPromptBuilder` describing the new intent shape to the
model — no new prompt *architecture*.

**TEST CONSEQUENCE:** Classification tests mirror every existing
`IntentType`'s own JVM test shape (`IntentJsonParserTest`,
`ToolSelectorTest`, `ClarificationEngineTest` all gain a bounded number of
new cases, not new test infrastructure).

**NON-GOAL / BOUNDARY:** No second inference pass for automation
specifically, no automation-specific prompt template separate from the
existing single classification prompt, no model-visible node/coordinate
data ever, at any point.

---

## DECISION 9
**Risk / Confirmation**

**CURRENT EVIDENCE:** `RiskPolicy.classify(intent: DpsIntent): RiskLevel` —
pure, two-case, reused verbatim by every M8 confirm-required type. M8's own
locked contract: "No second risk engine... No separate confirmation
system."

**OPTIONS:**
A. Extend `RiskPolicy.classify`'s existing `when` with new branches for
   `IntentType.AUTOMATION`.
B. A parallel `AutomationRiskPolicy`.
C. Risk determined inside `AndroidAutomationTool` itself, not via
   `RiskPolicy` at all.

**RECOMMENDED:** A.

**REASON:** B is exactly the forbidden "second risk engine." C would
bypass `SecretaryOrchestrator.proceedToExecution`'s existing gate entirely
(the same seam every other confirm-required type is asked through) —
rejected for the identical reason Decision 1's Option D was rejected.

**LOCKED DECISION:** `RiskPolicy.classify` gains exactly one new branch:
`intent.type == IntentType.AUTOMATION && <action looks like TAP on a
non-trivial target> -> CONFIRM_REQUIRED`; `OBSERVE_UI`/`FIND_ELEMENT`/
`WAIT_FOR_ELEMENT` (read-only, Decision 4) → `SAFE_AUTO`. For **Phase 1
specifically**, keeping the investigation's own §12 instruction to "keep
risk rules deterministic and minimal": since Phase 1's only interactive
action is `TAP` on one, single, pre-known, non-destructive test-app button
(Decision 5), the locked Phase-1-specific rule is simply **every
`AUTOMATION` intent whose action is `TAP` is `CONFIRM_REQUIRED`**,
unconditionally — no target-app/sensitive-screen/destructive-operation
heuristic is needed or built yet, since Phase 1 has exactly one possible
`TAP` target and confirming it every time is trivially cheap and maximally
safe. The richer "does this target look like send/delete/pay"
classification the investigation sketched (§12) is explicitly deferred to
the phase that introduces `TYPE_TEXT`/a real target app (Decision 23) —
building it now, against a single harmless button, would be exactly the
"speculative abstraction" this whole decision-making discipline exists to
avoid.

**IMPLEMENTATION CONSEQUENCE:** One new `when` branch in the existing
`RiskPolicy.classify`, ~2 lines. `askConfirmationFor`
(`SecretaryOrchestrator`, M8) gains one new dispatch case producing an
automation-specific confirmation question (e.g. "Tap "Tapped" in the test
app?") — reusing `PendingConfirmation`/`ConfirmationParser`/
`WAITING_CONFIRMATION` with **zero** changes to any of the three.

**TEST CONSEQUENCE:** `RiskPolicyTest.kt` gains automation cases mirroring
its existing per-type test shape exactly; `SecretaryOrchestratorTest.kt`
gains an automation confirm/decline pair mirroring the existing
`forget_fact`/delete-confirmation test shape exactly.

**NON-GOAL / BOUNDARY:** No target-app-specific or sensitive-screen-aware
risk logic in Phase 1 — that is explicitly deferred (Decision 23), not
silently dropped.

---

## DECISION 10
**Observation / Verification**

**CURRENT EVIDENCE:** `VerificationOutcome` — four cases, consumed only by
`ExecutionVerifier` (which re-reads a `TaskRepository`/`CalendarEventReader`
by stable id) and `ToolResponseGenerator.describeVerification`. No
automation-shaped read path exists.

**OPTIONS:**
A. Reuse `VerificationOutcome`'s four cases directly, feeding them from a
   *new* automation-specific observation read (not `ExecutionVerifier`'s
   existing repository-reading code).
B. Extend `ExecutionVerifier` itself to also handle automation.
C. Invent new automation-specific outcome names.

**RECOMMENDED:** A.

**REASON:** B risks entangling M7's frozen, task/calendar-specific,
id-based re-read logic with a structurally different UI-tree read — the
master prompt's own explicit "Do NOT redesign M7" instruction, and the
investigation's own §13 conclusion, both point away from touching
`ExecutionVerifier.kt` itself. C would violate the master prompt's own
"Do not automatically reuse or reinterpret M7 states without evidence" by
inventing synonyms for the same four concepts — the investigation found no
evidence a fifth concept is needed, only that the *read mechanism* differs.

**LOCKED DECISION:** `VerificationOutcome`'s existing four cases
(`Verified`/`Mismatch`/`NotFound`/`ObservationFailed`) are reused
**by name and by meaning**, produced by a **new**, separate function —
`AutomationEngine`'s own `observeAndVerify`-shaped method (or a small new
`AutomationVerifier` class, parallel to but not inheriting from
`ExecutionVerifier`) — that re-queries the live node tree (via
`FIND_ELEMENT`) and compares against the expected post-action state
(Decision 5: the button's expected text) using the same four-way logic
`ExecutionVerifier.resolveTask`/`resolveCalendarEvent` already establish
as the pattern (found-and-matches → `Verified`; found-but-differs →
`Mismatch`; not found → `NotFound`; couldn't read → `ObservationFailed`).
DPS must never report "Done" from `AndroidAutomationTool.execute()`'s own
return alone for a `TAP` action — `ToolResult.Success` from the tap itself
means only "the platform accepted the click," exactly as thin as `AndroidCallTool`'s
own "dialer opened" success already is; the real answer comes from this
new verification step, run by `SecretaryOrchestrator.recordOutcome`
exactly where M7 verification already runs for `create_task`/`create_event`
today (one new `if (intent.type == IntentType.AUTOMATION)` branch
alongside the existing check, not a new call site).

**IMPLEMENTATION CONSEQUENCE:** New `data/android/automation/AutomationVerifier.kt`
(or equivalent), consumed from the same `recordOutcome`
site M7 already uses; zero changes to `VerificationOutcome.kt` or
`ExecutionVerifier.kt`.

**TEST CONSEQUENCE:** Mirrors `ExecutionVerifierTest.kt`'s own exhaustive
four-outcome JVM test shape exactly, against a fake `AutomationEngine`
rather than a fake `TaskRepository`.

**NON-GOAL / BOUNDARY:** `PendingVerification`'s own sealed interface
(`Task`/`CalendarEvent`) is not touched — if a persisted "verification
pending" record is needed for automation (it is, see Decision 12), it is a
**new sibling case or a new sibling type**, decided in Decision 12, not a
silent third case grafted onto M7's own frozen type without justification.

---

## DECISION 11
**Asynchronous UI**

**CURRENT EVIDENCE:** Investigation §20 (event-driven observation) and
§19-of-the-report ("re-observe vs re-execute" distinction). `ToolExecutor
.DEFAULT_TIMEOUT_MILLIS = 10_000L` — the existing, uniform per-call budget
every tool already operates under.

**OPTIONS:**
A. Event-driven wait (subscribe to `TYPE_WINDOW_CONTENT_CHANGED`/
   `TYPE_WINDOW_STATE_CHANGED`, bounded by a timeout) for
   `WAIT_FOR_ELEMENT`.
B. Fixed-delay polling.
C. No waiting at all — a single immediate read, fail if not found.

**RECOMMENDED:** A, falling back to a small number of bounded polls only
if the event stream itself proves insufficient during implementation
(an empirical question, not an architectural one — flagged, not locked
into polling now).

**REASON:** B wastes CPU/battery for no benefit the event stream doesn't
already provide (investigation §20's own conclusion). C is too brittle
given the investigation's own evidence that lazy-loading/animation/network
delay are real, common causes of "not found yet" that are not the same as
"will never exist."

**LOCKED DECISION:** `WAIT_FOR_ELEMENT` is event-driven, bounded by a
timeout **no larger than** `ToolExecutor.DEFAULT_TIMEOUT_MILLIS` (10s) —
no new, separate, larger budget is justified by any evidence gathered.
**RE-OBSERVE is the only thing a timeout or a "not yet visible" result ever
triggers** — re-reading the tree, re-running `FIND_ELEMENT`. **RE-EXECUTE
(re-tapping, re-typing) never happens automatically, under any
circumstance, for any reason, including a slow UI** — this is a hard,
non-negotiable boundary directly inherited from M7/M8's own "no autonomous
retry" contract, applied here for the first time to an *action* rather
than a *tool call*, but under the identical rule.

**IMPLEMENTATION CONSEQUENCE:** `WAIT_FOR_ELEMENT`'s implementation lives
entirely inside `AutomationEngine`'s Android-backed implementation
(subscribing to accessibility events internally) — `AndroidAutomationTool`
and everything above it sees only "found within budget" or "timed out,"
identical in shape to `ToolResult.Timeout`'s existing case.

**TEST CONSEQUENCE:** JVM-testable at the boundary (does the tool
correctly map an engine-reported timeout to `ToolResult.Timeout`); the
actual event-driven waiting mechanism needs real-device proof only
(Decision 20).

**NON-GOAL / BOUNDARY:** No automatic re-tap/re-type under any
circumstance in M9 at all, Phase 1 or later, without a separate, explicit,
future architectural decision reopening this one.

---

## DECISION 12
**Action/Observation Process Death**

**CURRENT EVIDENCE:** `OperationCheckpoint`/`OperationType` (§2 above) —
write-before-irreversible-action, clear-on-any-confirmed-outcome, never
auto-retried on a leftover. `PendingVerification` — write-after-success,
before-verification, cleared-on-resolve, deliberately no freshness/expiry.

**OPTIONS:**
A. Add `OperationType.AUTOMATION_ACTION` as a fourth case on the existing
   `OperationCheckpoint`, reusing `operationId` via
   `OperationCheckpoint.UNUSED_OPERATION_ID` (mirroring `CREATE_EVENT`'s own
   precedent — automation actions have no natural id either).
B. A new, separate, sibling type (e.g. `AutomationCheckpoint`), stored
   under its own key in `PersistentRecoveryStore`, mirroring how
   `PendingVerification` itself is a sibling of `OperationCheckpoint`
   rather than a case grafted onto it.
C. No persisted state at all — accept that a mid-flight automation action's
   outcome is always `ACTION_UNKNOWN` on restart, decided from context
   rather than a durable record.

**RECOMMENDED:** B.

**REASON:** A would force `OperationCheckpoint`'s `title: String` field
(currently "enough to name the operation in a user-facing notice") to
awkwardly describe an automation action ("tapped a button in an app") in a
type whose every other case names a task/reminder/event's own title — a
semantic stretch, not a clean fit. `PendingVerification` already
established the exact right precedent for "a new persisted concern gets
its own sibling type in the same store," not a shoehorned case (M7's own
committed reasoning, quoted in `ExecutionRecoveryState.kt`: "This type
answers the next question, which only exists once that one is already
settled"). C is rejected: it would mean a real, dispatched tap's outcome
is *always* unknowable after a restart, even when a durable marker could
have made "verified" the actual, common-case answer — strictly worse
user experience for no complexity savings, since B's marker is small.

**LOCKED DECISION:** A new sibling type, `PendingAutomationAction`
(domain/secretary, alongside `OperationCheckpoint`/`PendingVerification`),
written to `PersistentRecoveryStore` under a **fourth** key (`automation`,
alongside the existing `state`/`checkpoint`/`pending_verification`) —
same file, same `commit()`-based durability discipline, no new store.
Shape: `{targetApp: String, targetElementDescriptor: <id/description/text>,
actionType: String, expectedObservation: <the Decision-5-style expected
value>, requestedAtMillis: Long}`. Written **before** `performAction` is
called (mirroring `OperationCheckpoint`'s own "before the destructive
write" discipline exactly), cleared only once verification resolves to any
of the four `VerificationOutcome` cases (mirroring `PendingVerification`'s
own clear-on-any-resolution discipline). On restart, a leftover record
means the action's outcome is `ACTION_UNKNOWN` — **the system does not
re-tap.** Instead, it does the one thing it safely can: **re-observe**
(Decision 11's own rule, applied here to the recovery path too) using the
persisted `targetElementDescriptor`/`expectedObservation` to attempt a
fresh verification read; if that resolves cleanly (`Verified`/`Mismatch`/
`NotFound`), the record clears and the user is told the real outcome,
mirroring exactly how `resolveOutstandingVerificationNotice` already
silently resolves a leftover M7 verification on the very first message of
a fresh process. If re-observation itself fails
(`ObservationFailed` — e.g. the target app isn't even in the foreground
anymore), the leftover is surfaced once, honestly, as "I may have tapped
something in \[app\] but couldn't confirm it — please check," mirroring
`outstandingCheckpointNotice`'s own established wording pattern verbatim.

**IMPLEMENTATION CONSEQUENCE:** New `PendingAutomationAction` type; three
new methods on `PersistentRecoveryStore` (`saveAutomation`/
`loadAutomation`/`clearAutomation`, mirroring the existing
`save/loadVerification`/`clearVerification` triad exactly); one new
restored-state field + one new `handle()`-entry branch in
`SecretaryOrchestrator`, mirroring `pendingVerification`'s own exact shape.

**TEST CONSEQUENCE:** Mirrors `PersistentRecoveryStoreTest.kt`'s existing
per-key round-trip/independence test shape; a genuine, real-device,
two-phase process-death test (new file, mirroring
`ConfirmationProcessDeathInstrumentedTest.kt`'s own established M8 pattern)
is **mandatory**, not optional (Decision 20).

**NON-GOAL / BOUNDARY:** No freshness/expiry on `PendingAutomationAction` —
mirroring `PendingVerification`'s own explicit, locked "no `isFresh()`"
reasoning verbatim (resolving it is read-only and implies no stale
consent, exactly the same argument). No automatic re-tap under any
circumstance, ever (Decision 11).

---

## DECISION 13
**Service Death**

**CURRENT EVIDENCE:** Investigation §15 point 5: no M1–M8 precedent exists
for a second, independent process. `PendingIntentAwaitingContactPermission`/
M8's own `pendingIntentAwaitingActionPermission`: both explicitly,
deliberately **not persisted** (`ExecutionRecoveryState.kt`'s own stated
scope boundary, reaffirmed for M8's field by the same reasoning) — the
established precedent for "a transient, resumable-only-within-the-same-
live-session block" that is intentionally *not* elevated to durable,
cross-process-death state.

**OPTIONS:**
A. Treat service death as equivalent to a permission being (temporarily)
   unusable — no persisted state, the very next permission check
   (Decision 3) naturally reports it, no special recovery path.
B. Build a dedicated, persisted "service was disconnected mid-action"
   recovery record, independent from Decision 12's own checkpoint.
C. Have DPS actively monitor and attempt to restart the service.

**RECOMMENDED:** A, for the "service unavailable before/independent of an
in-flight action" case; Decision 12's own `PendingAutomationAction` already
covers "service died mid-action" (its re-observation-on-restart path
naturally reports `ObservationFailed` if the service is still down when
DPS next checks — no separate mechanism needed).

**REASON:** B would be genuinely new background-recovery infrastructure
for a scenario Decision 12 already covers by construction (a dead service
simply makes the *observation* step fail, which is already one of the four
bounded outcomes Decision 12's recovery path already handles) — exactly
the "do not create new recovery infrastructure unless necessary" the
investigation itself warned against. C is explicitly rejected: an app
actively working to restart or re-bind a system-managed accessibility
service is both unreliable (Android does not guarantee this is even
possible from application code in all cases) and edges toward the kind of
autonomous, self-directed background behavior every M9 non-goal list
explicitly forbids.

**LOCKED DECISION:**
- **Service disconnected / killed while DPS process is alive, no action
  in flight**: the next `AndroidAutomationTool` call's permission check
  (Decision 3) reports `REQUIRES_SETTINGS`-shaped unavailability; handled
  by the *existing*, unmodified M8 permission-precheck path — zero new
  code.
- **Service killed with an action in flight**: covered by Decision 12's
  `PendingAutomationAction` recovery path (re-observe → `ObservationFailed`
  if the service is still unavailable).
- **Service restarted on its own** (Android's own lifecycle decision): no
  DPS-side action needed — the next call simply succeeds again, exactly
  like any other special-access permission being re-granted.
- **DPS process alive, service unavailable**: as above.
- **DPS process dead, service alive** (possible — independent lifecycles):
  no automation call can be in flight without the DPS process (the tool
  call chain requires it), so this state has no automation-relevant
  consequence; the service simply sits idle until the next real call.
- **Both dead**: identical to any other full process-death scenario —
  Decision 12's checkpoint-based recovery applies unchanged on restart.

**IMPLEMENTATION CONSEQUENCE:** None beyond Decision 3 and Decision 12 —
this decision's entire content is "no new mechanism is needed," stated
explicitly rather than left implicit.

**TEST CONSEQUENCE:** A real-device test disabling the accessibility
service (via `adb shell settings put secure enabled_accessibility_services`
or the equivalent) mid-sequence, confirming the existing permission-gate
and Decision-12 recovery paths both correctly report unavailability rather
than hanging or crashing (Decision 20).

**NON-GOAL / BOUNDARY:** DPS never attempts to programmatically re-enable
or restart its own accessibility service — any such capability would
itself be a significant, separate security-relevant decision entirely
outside M9's scope.

---

## DECISION 14
**Multi-Step Automation**

**CURRENT EVIDENCE:** `continuePlan`/`parkRemainder`/
`continueAfterResumedStep` already treat "blocked on X" uniformly
regardless of *why* (M2-C); a non-`isSuccess` or non-`Verified` step
already stops the plan and drops the remainder (M7/M8, unchanged).

**OPTIONS:**
A. Every UI micro-operation (open, find, tap, observe) becomes its own
   `PendingPlan` step.
B. One user-facing automation request is one plan step; internal
   sequencing (open→find→act→observe→verify) happens inside
   `AndroidAutomationTool`/`AutomationEngine`, invisible to
   `SecretaryOrchestrator`.
C. A dedicated automation-only plan sub-model, separate from `PendingPlan`.

**RECOMMENDED:** B.

**REASON:** A would surface a confirmation/clarification prompt mid-way
through what the user experiences as one request — directly contradicted
by investigation §14's own worked-example analysis. C duplicates M2-C's
existing, already-generic machinery for no new capability — `PendingPlan`
already doesn't care what kind of step it's holding, so a
`DpsIntent`-shaped `AUTOMATION` step needs nothing new from it.

**LOCKED DECISION:** One `AUTOMATION` intent = one plan step, exactly like
`create_event`/`cancel_task`/`forget_fact` are each one step today despite
each internally doing multiple sub-operations (resolve calendar, build,
insert, read back id). If step 3 of a larger plan is the automation step
and it fails/comes back `Mismatch`/`NotFound`/`ObservationFailed`,
`continuePlan`'s existing check (already firing for M7's own
`VerificationOutcome`, unchanged) stops the plan and drops the remainder —
**zero new code in `continuePlan` itself.** Confirmation
(Decision 9) pauses the whole plan at that one step exactly like any other
`CONFIRM_REQUIRED` step already does, parking the remainder via the
existing, unmodified `parkRemainder`.

**IMPLEMENTATION CONSEQUENCE:** None to `SecretaryOrchestrator`'s
multi-step machinery itself — `AndroidAutomationTool`'s own internal
open→find→act→observe→verify sequencing is entirely inside its own
`execute()` call, opaque to everything above it, exactly like every
existing tool's own internal sequencing already is.

**TEST CONSEQUENCE:** New multi-step tests mirror the exact shape of
M8's own "two confirm-required steps in one plan" test, substituting one
step for an `AUTOMATION` intent — no new test *pattern*, just a new
scenario within the existing pattern.

**NON-GOAL / BOUNDARY:** No plan-level visibility into automation
sub-steps — a failure mid-way through `AndroidAutomationTool`'s own
internal sequence (e.g., app opened but element never found) is reported
as that one step's own bounded failure outcome (Decision 15), never as a
partially-completed multi-step plan needing special handling.

---

## DECISION 15
**Failure Outcomes**

**CURRENT EVIDENCE:** `ToolResult`'s seven cases have no shape for
"element not found"/"ambiguous"; `VerificationOutcome`'s four cases already
cover the post-action observation side.

**OPTIONS:**
A. A bounded, new sealed type for *execution-time* failures
   (`AutomationExecutionOutcome` or similar), kept strictly separate from
   `VerificationOutcome`, surfaced to the rest of the system only as
   existing `ToolResult` cases (`Failure`/`Unsupported`/`Timeout`) with a
   distinguishing `reason`/`data` payload — no new `ToolResult` case at
   all.
B. Add new cases directly to `ToolResult` (the frozen, M1-era sealed
   interface).
C. One large, flat enum covering both execution and verification failure
   reasons together.

**RECOMMENDED:** A.

**REASON:** B touches a file every prior milestone has treated as frozen,
and the master prompt's own M9 boundary explicitly does not authorize
touching M1-era architecture — rejected without further analysis. C
violates the master prompt's own explicit instruction: "Keep ToolResult
and VerificationOutcome semantically separate."

**LOCKED DECISION:** The master prompt's own candidate list, sorted into
exactly three small groups, is adopted as the bounded vocabulary — no
dozens-of-states expansion:

- **Execution-time** (produced by `AndroidAutomationTool`/`AutomationEngine`,
  surfaced as `ToolResult.Failure`/`Unsupported`/`Timeout` with a
  machine-readable `reason` tag in `data`, *not* a new `ToolResult` case):
  `APP_NOT_INSTALLED` (→ `Unsupported`), `APP_OPEN_FAILED` (→ `Failure`),
  `ACCESSIBILITY_UNAVAILABLE` (→ handled upstream as `PermissionRequired`,
  Decision 3 — not a `Failure` reason at all), `ELEMENT_NOT_FOUND` (→
  `Failure`), `ELEMENT_AMBIGUOUS` (→ `Failure`), `ACTION_REJECTED` (→
  `Failure`), `TIMEOUT` (→ the existing `ToolResult.Timeout` case,
  unchanged).
- **Observation/verification-time** (the new `AutomationVerifier`,
  Decision 10): `UI_CHANGED`/`STALE_NODE` both collapse into
  `VerificationOutcome.ObservationFailed` (a stale node during a
  verification read *is* an observation failure — no new sub-case
  justified), `OBSERVATION_FAILED` → `VerificationOutcome.ObservationFailed`
  directly, `VERIFICATION_FAILED` is not a distinct state — it *is*
  `VerificationOutcome.Mismatch`/`NotFound` depending on which applies.
- **Recovery-time** (Decision 12): `ACTION_OUTCOME_UNKNOWN` is the exact,
  correct name for what a leftover `PendingAutomationAction` means before
  its re-observation attempt resolves — reused as internal terminology in
  this document and in `PendingAutomationAction`'s own KDoc, not
  necessarily a literal new enum case (it is a description of *why* the
  record exists, not a fifth outcome the record's own type needs to
  encode).

**IMPLEMENTATION CONSEQUENCE:** No new sealed type is strictly required —
execution failures ride inside `ToolResult.Failure.reason`/`data` exactly
like every existing tool's own failure messages already do (e.g.
`PrepareWhatsAppMessageTool`'s "\"$phoneNumber\" is not a usable phone
number" pattern); only a documented, internal `data` key convention
(e.g. `"failure_reason" to "ELEMENT_NOT_FOUND"`) needs to exist, for
future tests/tooling to key off deterministically rather than
string-matching human-readable text.

**TEST CONSEQUENCE:** Each of the seven execution-time reasons gets one
JVM test asserting the correct `ToolResult` case + reason tag; each
verification outcome reuses `ExecutionVerifierTest.kt`'s own existing
four-case test shape.

**NON-GOAL / BOUNDARY:** No new top-level sealed type crosses the
`ToolResult`/`VerificationOutcome` boundary — both frozen types stay
exactly as wide as they are today.

---

## DECISION 16
**Security Boundaries**

**CURRENT EVIDENCE:** `MemoryPrivacyGuard`'s (M6) existing denylist
pattern — enforced at the persistence boundary, not merely trusted to
classification — is the established precedent for "a hard, code-level
denylist independent of what the model/user asks for."

**OPTIONS:** Not option-shaped — this is an enumeration of prohibitions,
not a choice between architectures.

**LOCKED DECISION:** Direct, non-negotiable prohibitions, enforced in
`AutomationEngine`'s implementation (not merely documented — checked in
code before any `TYPE_TEXT`/`TAP` reaches the platform):
- **Never read or type into a node** whose `viewIdResourceName`, hint text,
  or input-type flag indicates a password/PIN/OTP/CVV/account-number field
  — mirroring `MemoryPrivacyGuard`'s own keyword-plus-pattern denylist
  shape exactly, applied to accessibility nodes instead of remembered
  facts.
- **Never target** banking apps, password managers, or any app whose
  package is not on the explicit Decision-6 allowlist — the allowlist
  itself *is* the security boundary; there is no "known bad app" list to
  maintain, only a "known good, explicitly reviewed" one.
- **Never interact with an OS permission-grant dialog** (a system-owned
  window, not a target app's own UI) — accessibility services *can*
  technically see and click these on some Android versions, and Android
  itself has hardened against exactly this abuse pattern in recent
  versions; DPS must not attempt it regardless of whether a given OS
  version currently permits it.
- **Never perform a purchase, financial transaction, or account-security
  change** (password change, recovery-option change, 2FA/OTP-adjacent
  screen) — enforced the same way as the password/OTP field denylist
  above: a node/screen-level pattern check, not merely a risk-tier
  (`CONFIRM_REQUIRED` is not sufficient for these — they are refused
  outright, full stop, mirroring `MemoryPrivacyGuard`'s own "denied, not
  merely flagged" posture for card numbers).
- **Never attempt root, exploit, or any technique outside the documented,
  public `AccessibilityService`/`Intent` APIs.**
- **Never post publicly or extract private content from a screen** as an
  independent action — Phase 1 has no such capability at all (Decision 4).

**IMPLEMENTATION CONSEQUENCE:** One new, small, pure denylist-matching
function analogous to `MemoryPrivacyGuard`, consulted by
`AutomationEngine`'s implementation before any `TAP`/`TYPE_TEXT` action —
not optional, not a later hardening pass.

**TEST CONSEQUENCE:** Mirrors `MemoryPrivacyGuard`'s own existing test
shape (a table of "must refuse" node signatures).

**NON-GOAL / BOUNDARY:** This entire decision *is* a non-goal boundary —
every item above is a hard refusal, never a `CONFIRM_REQUIRED` softening.

---

## DECISION 17
**Privacy**

**CURRENT EVIDENCE:** `core/logging/redact` already exists and is already
used for model-classification text (`ToolOrchestrator.generateClassification`);
`allowBackup="false"`/`dataExtractionRules` already establish local-first
posture at the manifest level; M6's own episodic memory logs only a tool's
own summary, never raw internals.

**OPTIONS:** Not option-shaped — direct application of already-established
project-wide privacy doctrine to a new data source (the accessibility
tree).

**LOCKED DECISION:**
- **UI text persistence**: **never**, beyond the single node's text needed
  for the current action/verification, held only in memory for the
  duration of that one call — never written to `ConversationMemory`,
  semantic memory, or episodic memory (M6) as raw screen content.
- **Accessibility node logging**: any `DpsLogger` call touching node
  text/description **must** pass through the existing `redact()` utility —
  reused, not reimplemented.
- **Screenshots**: **not captured at all** in M9 — no pixel data, ever;
  the node tree is the only observation surface.
- **Automation observations entering memory**: only the minimal,
  user-relevant *fact* of what happened (e.g., episodic memory logging
  "tapped a button in \[app\]," mirroring `EpisodicMemoryRecorder`'s own
  existing "the tool's own summary, never internals" discipline) — never
  the raw node tree or surrounding screen text.
- **Sensitive-field redaction**: Decision 16's denylist is the enforcement
  point — a field DPS refuses to read never reaches any log or memory
  write in the first place, which is a stronger guarantee than
  redacting after the fact.
- **Debug logging**: node text is treated with the same sensitivity this
  project already treats model classification output — `redact()`-wrapped
  in every log call, no exceptions.

**IMPLEMENTATION CONSEQUENCE:** Reuse of `redact()` at every new log call
site; no new privacy-infrastructure class needed.

**TEST CONSEQUENCE:** A test asserting no raw node text ever reaches
`DpsLogger` un-redacted (mirroring how `AI Rules 1 and 2` — never exposing
internals — are already enforced/tested via `ToolResponseGenerator`'s own
existing conventions).

**NON-GOAL / BOUNDARY:** No telemetry, no analytics, no cloud sync of any
automation-observed content, ever — consistent with, not a new instance
of, this project's standing local-first commitment.

---

## DECISION 18
**Automation Data Lifetime**

**CURRENT EVIDENCE:** `PendingVerification`'s own explicit "no
freshness/expiry" reasoning (§ above); `AccessibilityNodeInfo`'s own
platform-documented staleness (investigation §5).

**LOCKED DECISION** (per data category):

| Data | Lifetime |
|---|---|
| Live `AccessibilityNodeInfo` object references | **Never persisted, ever** — held only within one synchronous `AutomationEngine` call, discarded immediately after; re-queried fresh for every action (Decision 7/§5) |
| Visible text read during an action | **Transient** — used for matching/verification within the call, never written to any store |
| Target app package name | **Persisted only as part of `PendingAutomationAction`** (Decision 12) while an action is genuinely in flight; otherwise transient, re-resolved from Decision 6's allowlist every time |
| Action arguments (the described element/action) | Transient, except inside `PendingAutomationAction` while pending |
| Expected observation | Same as action arguments |
| The pre-action checkpoint (`PendingAutomationAction` itself) | **Persisted** (Decision 12), cleared on any resolved outcome, no expiry |
| Verification result | **Not persisted as a standing record** — reported once via the existing `ToolResponseGenerator.describeVerification`-shaped path, exactly like M7's own `VerificationOutcome` today, never stored for later reference |

**IMPLEMENTATION CONSEQUENCE:** Confirms Decision 12's `PendingAutomationAction`
is the *only* new persisted automation state — everything else is
already correctly transient by construction if the above table is
followed.

**TEST CONSEQUENCE:** A test asserting `PendingAutomationAction` never
retains a stale node reference (only serializable descriptors — package
name, resource-id string, expected text — never a live node object, which
could not survive process death or even serialization in the first place).

**NON-GOAL / BOUNDARY:** No "automation history" store, no cache of
previously-seen UI trees, no persisted node-id-to-screen mapping across
app updates.

---

## DECISION 19
**Test Target**

**CURRENT EVIDENCE:** Investigation §22's own recommendation, now locked
by Decision 5's own Phase 1 scope choice.

**LOCKED DECISION:** **Yes**, a dedicated local deterministic test app is
necessary and is *the* Phase 1 target (Decision 5), not merely a testing
convenience layered on top of a "real" Phase 1 target. Defined here as a
testing/proof mechanism, not production functionality:
- **Why necessary**: a real third-party app's UI is outside this project's
  control (investigation §10/§22) — no genuinely deterministic regression
  suite is possible against one.
- **Screens**: exactly one.
- **Actions exposed**: exactly one button, whose text changes to a fixed,
  predictable string on tap (Decision 5).
- **Determinism**: total — fixed resource id, fixed English text, no
  animation, no network call, no loading state; the one deliberately
  "boring" surface this milestone needs.
- **Process-death testing**: the test app itself needs no special
  process-death behavior of its own — Decision 12/20's process-death
  proof is about *DPS's* process (and the accessibility service), not the
  target app's.

**IMPLEMENTATION CONSEQUENCE:** A second, separate Gradle module/APK
(explicitly **not** merged into the main `app` module or its release
build/Play listing) — an implementation-phase decision to make once
building actually starts; not built now, per this phase's own explicit
"do not implement" rule.

**TEST CONSEQUENCE:** This *is* the test consequence — see Decision 5/20.

**NON-GOAL / BOUNDARY:** Never shipped to end users as part of the DPS
app itself; exists solely for this project's own instrumented-test
infrastructure.

---

## DECISION 20
**Real-Device Validation**

**CURRENT EVIDENCE:** The established, nine-times-proven two-phase
`adb shell am force-stop` + `pidof`-confirmed-dead methodology (M3-D
through M8, most recently `ConfirmationProcessDeathInstrumentedTest.kt`
this same session).

**LOCKED DECISION:** JVM tests alone are insufficient for M9 acceptance —
reaffirmed, not merely restated. The mandatory real-device methodology,
mapped directly onto the master prompt's own ten numbered items:

1–2. **Service enabled / DPS detects state**: real
   `AccessibilityManager` query against a genuinely, manually-enabled
   service on the test device — not assumed from the manifest declaration.
3. **Known target app launches**: Decision 19's own test app, launched via
   `OPEN_APP`, confirmed via a real `TYPE_WINDOW_STATE_CHANGED` event or
   equivalent real observation.
4. **Accessibility tree observed**: `OBSERVE_UI` against the real, running
   test app.
5. **Deterministic element identified**: `FIND_ELEMENT` against the real
   button (Decision 5/7).
6. **Bounded action occurs**: a real `TAP`, confirmed by the test app's own
   real, observable text change — not inferred from `AndroidAutomationTool`'s
   own return value alone (mirroring M7's own "observe real state, don't
   trust internal bookkeeping" discipline exactly).
7. **Resulting UI state observed**: a second, real `OBSERVE_UI`/
   `FIND_ELEMENT` read.
8. **Result verified**: the new `AutomationVerifier` (Decision 10)
   produces `Verified` against real device state.
9. **Service/process death handled safely**: **two separate real-device
   proofs are required, not one** — (a) the existing DPS-process-only
   two-phase methodology, extended to cover Decision 12's
   `PendingAutomationAction` recovery, and (b) a genuinely new proof this
   codebase has never needed before: disabling/killing the
   **accessibility service independently** of the DPS process (via
   `adb shell settings put secure enabled_accessibility_services <list
   without this app's service>` or an equivalent real toggle) mid-sequence,
   confirming Decision 13's "reports unavailable, does nothing autonomous"
   behavior.
10. **No duplicate action occurs after recovery**: read the test app's own
   real, observable state exactly once, post-recovery, and assert it
   reflects exactly one tap having occurred — the same "observe real state
   once, don't count internal calls" discipline M7's own process-death
   tests already established.

**IMPLEMENTATION CONSEQUENCE:** At minimum three new real-device
instrumented test files at implementation time: one for the base
open→find→tap→observe→verify pipeline, one two-phase
`AutomationProcessDeathInstrumentedTest`-shaped pair (mirroring
`ConfirmationProcessDeathInstrumentedTest.kt`), and one dedicated
accessibility-service-disable test.

**TEST CONSEQUENCE:** Establishes the acceptance bar for the eventual M9
implementation plan/final report — no automation capability may be
declared accepted without all ten items genuinely, individually proven on
a real device.

**NON-GOAL / BOUNDARY:** No simulated lifecycle, no Activity recreation, no
in-memory reset ever substitutes for a genuine `adb`-driven death in any
of these ten items — the same standing rule every prior milestone's
real-device evidence has already had to satisfy.

---

## DECISION 21
**Android Version Support**

**CURRENT EVIDENCE:** `build.gradle.kts`, re-verified: `minSdk = 26`,
`targetSdk = 35`, `compileSdk = 35`.

**LOCKED DECISION:** M9 targets exactly this project's existing range —
**no broadening or narrowing**. Concretely: `ACTION_SET_TEXT`/
`dispatchGesture` (API 24+) need no version gate (`minSdk` 26 already
clears them). No foreground-service typed-permission gate is needed for
Phase 1, since Decision 2 locks the service to plain
`BIND_ACCESSIBILITY_SERVICE` mechanics with no foreground-service
component in Phase 1's scope (Decision 23 defers any design that would
need one). Package-visibility handling (`<queries>`, API 30+) for the
Phase 1 test app follows the exact existing convention (§3) — no new
mechanism.

**IMPLEMENTATION CONSEQUENCE:** No `Build.VERSION.SDK_INT` branching is
required anywhere in Phase 1's own new code, beyond the one new
`specialAccessState()` branch (Decision 3), which needs no version check
of its own (the enabled-services query works identically across the
entire `minSdk 26`–`targetSdk 35` range).

**TEST CONSEQUENCE:** No matrix of API-level-specific test variants is
needed for Phase 1.

**NON-GOAL / BOUNDARY:** No support commitment beyond this project's
existing `minSdk`/`targetSdk` — M9 does not extend platform support in
either direction.

---

## DECISION 22
**Accessibility Enablement UX**

**CURRENT EVIDENCE:** `AndroidPermissionManager`'s own established pattern
for `SCHEDULE_EXACT_ALARM`: query state, and when settings-only, report
`REQUIRES_SETTINGS` for the caller to route to a settings `Intent` **with
the user's consent** — `PermissionManager`'s own doc: "It also never
navigates to system settings on its own... the manager reports
[REQUIRES_SETTINGS] and the caller asks."

**LOCKED DECISION:** Identical pattern, applied to accessibility: DPS
never opens Accessibility Settings unprompted. When
`AndroidAutomationTool` is needed and the permission check (Decision 3)
reports `REQUIRES_SETTINGS`, `ToolResponseGenerator.phrasePermission`
(extended with one new `when` branch, mirroring its existing per-permission
"need" phrasing) explains **in plain language what accessibility access
means and why it's being asked for** — not merely "I need a permission" —
given how much heavier-weight this consent is than any existing one
(investigation §5's own finding). Only after the user explicitly agrees
does DPS launch `Settings.ACTION_ACCESSIBILITY_SETTINGS` — the user still
does the actual toggle themselves, inside the OS's own settings UI, seeing
the OS's own warning text; DPS never attempts to pre-fill, auto-scroll to,
or otherwise streamline past that OS-owned warning screen.

**IMPLEMENTATION CONSEQUENCE:** One new `phrasePermission` branch; reuse
of the existing `PermissionRequestHost`-adjacent "ask, then launch
settings" flow already established for `SCHEDULE_EXACT_ALARM`-shaped
special-access permissions — no new UX pattern invented.

**TEST CONSEQUENCE:** A test asserting the new permission-explanation
wording names the real capability (not the Android permission string —
AI Rules 1/2, unchanged) and never claims DPS itself can enable the
service.

**NON-GOAL / BOUNDARY:** No hidden enablement, no bypass, no silent
privilege escalation — restated as a hard requirement, not an aspiration.

---

## DECISION 23
**Future Expansion Boundary**

**LOCKED DECISION:** Every item explicitly deferred beyond Phase 1, with
the specific, already-built prerequisite each would need before being
reconsidered:

- **Multiple apps in one automation** — needs Decision 6's allowlist to
  grow past one entry, plus a richer Decision 9 risk heuristic (target-app-
  aware, not just action-type-aware).
- **Complex navigation** (multi-screen chains) — needs Decision 4's
  `PRESS_BACK`/`SCROLL`/`OPEN_DEEP_LINK` entries promoted from
  "later phase," plus real-device evidence on staleness/timing across
  screen transitions.
- **Messaging automation** (an actual send) — needs `TYPE_TEXT` (Decision
  4), Decision 16's sensitive-field denylist proven in production, and a
  target-app-and-destination-aware risk rule (Decision 9's own deferred
  richer heuristic).
- **Social media automation, purchases, banking, account settings,
  credentials, OTP** — **not deferred, prohibited** (Decision 16) — these
  do not become "later phase," they remain refused regardless of how much
  future infrastructure exists, absent an entirely separate, explicit,
  future architectural decision reopening Decision 16 itself.
- **Arbitrary gestures** (beyond bounded scroll) — needs real-device
  evidence `dispatchGesture` is reliable enough for the specific gesture,
  case by case.
- **Screenshots, advanced screen understanding** — **not deferred,
  rejected** (Decision 17) — no evidence in this investigation or these
  decisions motivates ever capturing pixel data when the node tree
  already serves every identified need.
- **Autonomous browsing, background automation** — **not deferred,
  prohibited** — these are categorically outside "controlled,
  user-directed" automation, M9's own stated mission boundary, not a
  matter of missing infrastructure.

**NON-GOAL / BOUNDARY:** This section is the authoritative deferred-vs-
prohibited distinction for the implementation-planning phase — planning
may design toward the "deferred" items' stated prerequisites; it may not
design toward any "prohibited" item under any framing.

---

## M9 LOCKED ARCHITECTURAL CONTRACT

1. **Automation architecture**: one new `ToolId.AUTOMATION` +
   `AndroidAutomationTool : AndroidTool` (thin) + `AutomationEngine`
   domain interface + Android-backed implementation. No parallel
   orchestration path; no generic pluggable framework.
2. **AccessibilityService role**: mechanical only — events in, bounded
   actions out, raw results reported. Zero NLU, risk, confirmation, or
   planning inside it.
3. **Permission model**: `DpsPermission.AUTOMATION_ACCESSIBILITY`,
   `PermissionKind.SPECIAL_ACCESS` (reused, not a third kind), one new
   `specialAccessState()` branch, flows through the existing
   `DefaultToolExecutor`/M8-precheck gates unchanged.
4. **Action vocabulary (Phase 1)**: `open_app`, `observe_ui`,
   `find_element`, `tap`, `wait_for_element` — five operations.
5. **Phase 1 scope**: one first-party test app, one button, one tap, one
   verified text-change observation.
6. **App targeting**: package name, resolved only via a fixed,
   developer-maintained allowlist; never LLM-guessed; unresolved/
   ambiguous/uninstalled all fail closed via existing `ToolResult` cases.
7. **UI targeting**: priority-ordered exact matching
   (resource-id → content-description → exact text), unique match
   required, no fuzzy matching, no coordinates, no LLM-chosen nodes.
8. **LLM boundary**: one new `IntentType.AUTOMATION`, classified once, in
   the existing `DpsIntent` shape; the model never sees node data or
   coordinates; every downstream step is deterministic code.
9. **Risk integration**: `RiskPolicy.classify` gains automation branches;
   Phase 1 rule: every automation `TAP` is `CONFIRM_REQUIRED`,
   unconditionally. No second risk engine.
10. **Confirmation integration**: existing `PendingConfirmation`/
    `ConfirmationParser`/`WAITING_CONFIRMATION` reused verbatim via one new
    `askConfirmationFor` dispatch branch.
11. **Observation**: a new automation-specific read (node-tree-backed),
    producing `VerificationOutcome`'s existing four cases by name and
    meaning — no new outcome vocabulary.
12. **Verification**: a new `AutomationVerifier`, parallel to but not
    modifying `ExecutionVerifier`; consumed from the same
    `recordOutcome` site M7 already uses.
13. **Async waiting**: event-driven `WAIT_FOR_ELEMENT`, bounded by the
    existing 10s tool-call budget; re-observe only, never re-execute,
    under any circumstance.
14. **Process-death recovery**: new sibling type `PendingAutomationAction`,
    a fourth `PersistentRecoveryStore` key, written before action,
    cleared on any resolved verification outcome, no freshness/expiry;
    on restart, re-observes, never re-acts.
15. **Service-death behavior**: no new mechanism — covered by the existing
    permission-precheck path (pre-action) and Decision 12's recovery path
    (mid-action). DPS never attempts to restart its own service.
16. **Multi-step behavior**: one automation intent = one plan step; all
    internal sequencing opaque to `SecretaryOrchestrator`; existing
    `continuePlan`/`parkRemainder` handle blocking/failure with zero new
    code.
17. **Failure states**: bounded, three-group vocabulary
    (execution/observation/recovery), riding inside existing `ToolResult`/
    `VerificationOutcome` cases via a `data`-map reason tag — no new
    top-level sealed type crossing either frozen boundary.
18. **Security boundaries**: hard, code-enforced denylist (password/OTP/
    PIN/financial/account-security fields and screens, permission
    dialogs, non-allowlisted apps) — refused outright, never merely
    `CONFIRM_REQUIRED`.
19. **Privacy boundaries**: no screenshots ever; no raw screen-content
    persistence; `redact()` reused for all node-text logging; only the
    minimal user-relevant fact enters episodic memory.
20. **Test target**: a dedicated, never-shipped, single-screen first-party
    test app — the same artifact as Phase 1's own production target
    (Decision 5/19).
21. **Real-device validation**: mandatory, ten-item, genuine
    `adb`-driven proof, including a new dual-process (DPS + accessibility
    service) death methodology this codebase has never needed before.
22. **Explicitly deferred capabilities**: multi-app, complex navigation,
    messaging/send automation, arbitrary gestures — deferred with named
    prerequisites. **Explicitly prohibited, not deferred**: social media,
    purchases, banking, account settings, credentials, OTP, screenshots,
    autonomous browsing, background automation.

## M9 Phase 1 Scope

See Decision 5 (canonical) — restated: launch one first-party test app,
tap its one button, observe and verify the resulting text change, with
full risk/permission/confirmation/verification/process-death coverage
proven on a real device.

## Security Boundaries

See Decision 16 (canonical).

## Privacy Boundaries

See Decision 17 (canonical).

## Recovery Model

See Decisions 12/13 (canonical).

## Testing Contract

JVM: every pure decision (app-targeting lookup, element-matching logic,
`RiskPolicy` branches, `ToolResult`-reason mapping) — zero Android
dependency, mirroring every prior milestone's own JVM-first discipline.
Instrumented: service lifecycle, real launch/discovery/action/observation
against Decision 19's own test app. Real device: Decision 20's ten-item
mandatory proof, in full, before any M9 acceptance claim.

## Real-Device Validation Contract

See Decision 20 (canonical) — ten items, two of them (service-independent
death, and the dual-process recovery proof) genuinely new to this
codebase's own established methodology.

## Deferred Capabilities

See Decision 23 (canonical) — deferred-with-prerequisite vs.
prohibited-outright, explicitly distinguished.

## Risks

| Risk | Likelihood | Impact | Mitigation | Blocks implementation planning? |
|---|---|---|---|---|
| Accessibility-service enablement UX proves too high-friction for real users to complete | Medium | Medium (product risk, not technical) | Decision 22's explicit, honest explanation requirement; not a technical blocker | No |
| `PendingAutomationAction`'s re-observation-on-restart path finds the target app no longer in the expected state (user closed it, OS killed it) between death and restart | Medium | Low | Already bounded by Decision 12's own `ObservationFailed`-shaped fallback — a known, handled case, not a gap | No |
| Play Store policy review friction for declaring accessibility-service usage | Medium | High (distribution) | Decision 2's narrow-scope service config + Decision 16's hard denylists give a genuinely narrow, defensible declared use case | No — a business/submission-process risk, not an architectural blocker |
| The dual-process (service + app) real-device death methodology (Decision 20 item 9b) proves harder to script reliably via `adb` than the existing single-process methodology | Medium | Medium | Flagged explicitly as new; budget real investigation time for it in the implementation plan rather than assuming it's a copy-paste of the existing script | No — a planning-effort risk, not an unknown |

## Remaining Open Questions

1. **Exact `IntentParameters` field mapping for `IntentType.AUTOMATION`**
   (which existing field carries the target-element description).
   *Why it matters*: a naming/schema micro-decision. *Evidence needed*:
   none — resolvable by whoever writes the implementation plan, using
   `IntentParameters`' existing flat shape. *Blocks the Implementation
   Plan?* No.
2. **Exact Gradle module structure for the Decision 19 test app** (separate
   module vs. separate standalone project entirely). *Why it matters*:
   build-configuration detail. *Evidence needed*: none architectural —
   an implementation-phase choice. *Blocks?* No.
3. **Whether `AccessibilityNodeInfo` needs a thin wrapper interface for
   JVM-testability of matching logic** (Decision 7), or whether Robolectric
   is acceptable for this project (which has otherwise avoided Robolectric
   in favor of real-device-only Android-layer testing, per its own ADR-009
   physical-device policy). *Why it matters*: could mean a new test
   dependency, which this decision phase is not authorized to add.
   *Evidence needed*: whether pure-Kotlin wrapping is sufficient without
   sacrificing real fidelity — resolvable during implementation, does not
   change any of the 23 decisions above. *Blocks?* No.

**No remaining question blocks proceeding to the Implementation Plan.**

## Final Verdict

**ARCHITECTURE LOCKED — READY FOR IMPLEMENTATION PLAN**
