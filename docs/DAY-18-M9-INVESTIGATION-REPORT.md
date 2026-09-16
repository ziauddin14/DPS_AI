# Day 18 — M9 Investigation Report: Controlled Android Device & App Automation

**Status: INVESTIGATION ONLY. No source, test, configuration, manifest, or
Gradle file was modified to produce this document.** Every architectural
claim is tagged CURRENT (what the repo does today), ANDROID PLATFORM FACT
(what the OS permits/restricts, independent of this repo), PROPOSED (what
M9 could do), LOCKED (what M1–M8 already require), or NON-GOAL (excluded).

---

## 1. Executive Summary

DPS has no automation capability today and no scaffolding toward one: zero
references to `Accessibility`/`UiAutomator`/`dispatchGesture` exist anywhere
in `app/src` (verified by grep), the manifest declares no `<service>` of any
kind, and the only two components beyond `MainActivity` are two narrowly
`exported="false"`/boot-only broadcast receivers (§3). This is a green
field, not a gap to close in existing code.

The good news is that M9 does not need a green-field *decision process* —
M1–M8 already built every non-UI-specific piece an automation layer needs:
a deterministic risk policy (M8), permission modeling that already has a
`SPECIAL_ACCESS` shape built for exactly this kind of settings-only,
non-dialog grant (§11), a real EXECUTE→OBSERVE→VERIFY→DECIDE pipeline (M7),
genuine process-death recovery proven three times over on a real device
(M3-D/M5-C/M7/M8), and a multi-step plan model that already treats "blocked
on something" uniformly regardless of *why* (M2-C). M9's job is to extend
these to a new tool, not invent new versions of them.

The hard, genuinely new part is narrower than "device automation" sounds:
**AccessibilityService is the only mechanism that can read arbitrary
third-party app UI and act on it (§5)**, it is a real Android `Service`
component this codebase has never had one of, it requires a manual,
high-trust user enablement step outside any dialog this app controls, and
its node tree is asynchronous, sometimes stale, and not currently
observable by anything M7's `ExecutionVerifier` was built for (`create_task`/
`create_event` field comparison — §13). The minimum honest M9 slice is
therefore small: one known app, one deterministic element, one bounded
interaction, one verified observation (§24) — not general-purpose device
control, which this report does not recommend attempting in a first slice.

## 2. Verified M1–M8 Baseline

Re-verified directly against the live repository, not assumed from prior
reports:

```
git log --oneline -3   (android/, independent repo)
3db0a02 some random commit         (contains the M8 implementation, verified below)
9dff80d M7 Investigation and some Impleemntation are completed
8e80755 Added Long Term memory M6 Complete
```
`git status --porcelain=v1 -uall` is empty — clean working tree, nothing
uncommitted.

`3db0a02`'s diff (inspected via `git show --stat`) contains exactly the M8
deliverable: `domain/secretary/RiskPolicy.kt` (new), the risk/permission
precheck restructuring of `SecretaryOrchestrator.proceedToExecution`, the
`forget_fact` confirmation addition, the new `ConfirmationProcessDeathInstrumentedTest.kt`,
and the M7 files carried over unmodified from the prior milestone. This
confirms M8 is genuinely the current HEAD state — the M8 final report's
"ACCEPTED" verdict describes what is actually in the tree.

## 3. Current Android Architecture

**Manifest** (`app/src/main/AndroidManifest.xml`, read in full):
- Components: `MainActivity` (exported, launcher) and exactly two
  `BroadcastReceiver`s — `ReminderReceiver` (`exported="false"`, targeted
  only by this app's own `PendingIntent`) and `ReminderBootReceiver`
  (`exported="true"`, `BOOT_COMPLETED` only). **Zero `<service>` elements.
  Zero accessibility scaffolding.**
- Permissions declared: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `POST_NOTIFICATIONS`, `READ_CALENDAR`, `WRITE_CALENDAR`,
  `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `READ_CONTACTS`,
  `RECORD_AUDIO`. `USE_EXACT_ALARM` explicitly *not* declared, with a
  documented reason (Play policy scoping) — direct evidence this project
  already reasons carefully about permission scope-creep and Play policy,
  which bears directly on M9 (§18).
- `<queries>` (API 30+ package visibility): exactly `com.whatsapp`,
  `com.whatsapp.w4b`, plus three narrow intent-signature queries (`https`
  VIEW, `mailto` SENDTO, `tel` DIAL). **`QUERY_ALL_PACKAGES` is explicitly
  and permanently rejected**, with a stated privacy rationale ("would let
  DPS enumerate the user's entire app list, which a privacy-first product
  has no business doing"). This is a LOCKED precedent, not merely a current
  fact: any M9 target app needs its own explicit `<queries>` entry, decided
  per-app, never a blanket grant.
- `allowBackup="false"`, `dataExtractionRules` present — durable,
  already-stated local-first/privacy-first posture.

**Build** (`app/build.gradle.kts`, read in full): `minSdk = 26` (Android
8.0), `targetSdk = 35`, `compileSdk = 35`. No accessibility, UI Automator,
or automation-adjacent dependency exists. `androidx.work.runtime.ktx` is
already a dependency — used today by `ProactiveCheckWorker` (§ below), not
by anything automation-related.

**A pre-existing, unrelated "proactive" package exists** —
`data/android/proactive/{ProactiveCheckWorker, ProactiveRuleEvaluator,
ProactiveStateStore}` — a deterministic, read-only, notification-only
`WorkManager` periodic worker (M4-A/B/C) for overdue-task and
upcoming-event nudges, gated behind a user preference, with **no
dependency, direct or transitive, on the AI subsystem** (its own doc,
verified verbatim). This predates M9, is not M10's future "Proactive
Intelligence" milestone, and is out of scope for M9 to touch or extend —
noted here only because it is the one piece of existing background-work
evidence relevant to §20's background-execution-restriction analysis.

**Core M1–M8 pipeline** (already implemented, unchanged by this
investigation, confirmed via the M8 session's own direct, exhaustive
reading — not re-derived here):
- `AndroidTool` interface (`domain/tool/AndroidTool.kt`): `id`,
  `operations: Set<String>`, `requiredPermissions: Set<DpsPermission>`,
  `minApiLevel: Int?`, `suspend fun execute(call: ToolCall): ToolResult`.
  Never throws; every outcome is a `ToolResult` value.
- `ToolResult` (`domain/tool/ToolResult.kt`): `Success`/`Failure`/
  `PermissionRequired`/`Cancelled`/`Unsupported`/`Timeout`/`Error` — closed,
  serializable, no case for "ambiguous UI match" or "element not found" yet
  (§8, §17).
- `ToolRegistry`/`DefaultToolRegistry`: pure lookup by `ToolId`, 16 entries
  registered today (`CALENDAR, NOTIFICATION, REMINDER, CONTACTS, PHONE,
  WHATSAPP, GMAIL, ALARM, WORK_MANAGER, PERMISSION, TASK, WORK_LOG,
  MEETING, ACTION_ITEM, REPORT, MEMORY`). `ALARM`/`WORK_MANAGER`/
  `PERMISSION` remain declared-but-`Unsupported` since Phase 1 — genuinely
  unimplemented, not automation-adjacent.
- `DefaultToolExecutor`: the one gate — resolve tool → API-level check →
  operation-supported check → permission check → timeout-guarded execute →
  catch-all `Error`. Reads `Build.VERSION.SDK_INT` only at the composition
  root (`AiContainer`), keeping `ai/`/`domain/` platform-free.
- `DpsPermission`/`PermissionKind` (`domain/permission/`): exactly two
  kinds, `RUNTIME` (dialog-grantable) and `SPECIAL_ACCESS` (settings-only,
  queried via a dedicated platform API, currently only
  `SCHEDULE_EXACT_ALARM`). `AndroidPermissionManager.specialAccessState()`
  is a literal `when (permission)` with one case — the exact, precedented
  extension point for a new special-access permission (§11).
- `RiskPolicy`/`RiskLevel` (M8, `domain/secretary/RiskPolicy.kt`): pure
  `SAFE_AUTO`/`CONFIRM_REQUIRED` classification of an already-classified
  `DpsIntent`, no model call.
- `PendingConfirmation`/`ConfirmationParser`/`SecretaryState
  .WAITING_CONFIRMATION`: single-slot, 5-minute-fresh, persisted via
  `PersistentRecoveryStore`, survives genuine process death (proven twice
  now, M5-B and M8).
- `PendingPermissionAction` (`ToolOrchestrator`-internal) and the M8
  `pendingIntentAwaitingActionPermission` (`SecretaryOrchestrator`):
  **neither persists across process death** — a documented, intentional
  M5-B/M8 scope boundary, not an oversight (§15).
- `OperationCheckpoint`/`PendingVerification`/`ExecutionVerifier`/
  `VerificationOutcome` (M5-C/M5-E/M7): pre-write checkpoint for
  `create_task`/`create_reminder`/`create_event`; post-write, four-outcome
  (`Verified`/`Mismatch`/`NotFound`/`ObservationFailed`) verification for
  `create_task`/`create_event` only, via a direct repository/provider
  re-read, never a second tool call.
- Multi-step (`SecretaryOrchestrator.continuePlan`, M2-C):
  `parkRemainder`/`continueAfterResumedStep` treat any block (clarification,
  contact ambiguity, confirmation, permission) as one shape; a step's
  outcome not `isSuccess` (or not `Verified`, post-M7) stops the plan and
  drops the remainder — no autonomous continuation.
- Real-device test methodology (established M3-D, reused unmodified
  through M8): two `@Test` methods per scenario (`phase1.../phase2...`),
  run as two separate `am instrument` invocations with a genuine
  `adb shell am force-stop` + `pidof`-confirmed kill between them. No
  simulated lifecycle, no Activity recreation, no in-memory reset ever
  substituted for this.

## 4. Android Automation Capability Survey

ANDROID PLATFORM FACT, evaluated against this project's actual `minSdk 26`/
`targetSdk 35`:

| Mechanism | Can do | Cannot do | Access needed | User approval | Cross-app | Reads UI | Acts on UI | Observes success | M9 fit |
|---|---|---|---|---|---|---|---|---|---|
| **AccessibilityService** | Read the full node tree of the foreground app; perform click/scroll/text-set/global actions (back/home/recents) on nodes; dispatch synthetic gestures (API 24+) | Read *other* apps' UI while they are not foreground (only the currently-focused window, or windows it's configured to watch); guarantee node stability across an async UI update | A dedicated manifest `<service>` + a `res/xml` config + **manual enablement in system Accessibility Settings** (no runtime dialog exists) | Yes — explicit, deliberate, high-trust, done once outside this app entirely | Yes — the only mechanism here that is | Yes | Yes | Only by re-reading the tree afterward — no native "did my click succeed" signal | **Core candidate — §5** |
| Explicit Intents (`ACTION_VIEW`/`ACTION_SENDTO`/`ACTION_DIAL`, already used) | Launch a specific activity/app to a known state; hand off structured data | Cannot navigate *inside* an app past its entry point, cannot read anything back | None beyond package visibility (`<queries>`) already in place for 3 of these | No dialog; implicit "user asked for this" | Yes, narrowly | No | No (fire-and-forget) | No — the existing `PrepareWhatsAppMessageTool`/`PrepareEmailTool`/`AndroidCallTool` pattern already accepts this and hands the *final* step to the user for exactly this reason | **Already core M1–M8 architecture — extend, don't replace (§6)** |
| Deep links (`https`/custom scheme) | Same as explicit intents, plus can target a specific in-app screen if the target app registers one | Same as above; depends entirely on the target app's own deep-link surface, which DPS cannot discover or guarantee | Package visibility if scheme-specific | No dialog | Yes, per-scheme | No | No | No | **Same bucket as explicit intents — already used for WhatsApp** |
| App-specific APIs / SDKs | Whatever that SDK exposes | Only works per-app, requires that app's own SDK integration | Varies | Varies | No — single-app only | Varies | Varies | Varies | **Non-goal for a general M9 — would mean bespoke integration per target app, contradicting "controlled, general automation"** |
| Content providers | Read/write structured data the *provider owner* explicitly exposes (contacts, calendar — already used) | Cannot touch anything not deliberately exposed as a provider; almost no consumer app exposes a general-purpose provider for arbitrary UI actions | `ContentResolver` + the specific permission the provider declares | Same as any runtime permission | Only for apps that expose one | N/A | N/A | Query-based, yes | **Already core (Calendar/Contacts) — not a path to general app automation** |
| Broadcasts | Send/receive structured signals between components | Cannot inspect or drive another app's UI; most system broadcasts are protected (senders restricted) from API 26+ | Varies | No | Limited | No | No | No | **Not relevant to UI automation** |
| Notification access (`NotificationListenerService`) | Read posted notifications system-wide, dismiss them, invoke their actions | Cannot read arbitrary app UI, only what an app chooses to notify about | Dedicated `<service>` + manual Settings enablement (same shape as Accessibility) | Yes, same high-trust flow | Yes | Only notification content | Only notification actions | Only via the notification's own state changing | **Out of scope for M9 — a different capability (notification triage), not app automation; worth flagging as a possible, separate future capability, not this milestone** |
| Media/session APIs (`MediaSessionManager`) | Control apps that expose a media session (play/pause/skip) | Only media-playback apps that opt in | `MEDIA_CONTENT_CONTROL` (signature-level on many OEMs) or notification-listener-adjacent access | Yes | Only media apps | No | Playback commands only | Via session state callback | **Not relevant to general app automation; out of scope** |
| Foreground services | Keep a process alive and visible while doing user-visible work | Cannot, by itself, read or act on other apps' UI — it's a lifecycle mechanism, not an automation mechanism | `FOREGROUND_SERVICE` (+ a typed sub-permission from API 34, e.g. `FOREGROUND_SERVICE_SPECIAL_USE` — see §21) | No dialog for the permission itself, but a persistent, visible notification is mandatory | N/A | N/A | N/A | N/A | **Possibly needed *alongside* AccessibilityService to keep an automation session alive while DPS's own UI is backgrounded — not a replacement for it (§21)** |
| Background execution restrictions | — | From API 26+, background-started services and implicit broadcasts are restricted; from Android 12 exact alarms need special access (already handled); Doze/App-Standby throttle timers and network for backgrounded apps | — | — | — | — | — | — | **Directly explains this session's own `ReminderTriggerInstrumentedTest` real-device flakiness (a live example, not theoretical) — AccessibilityService itself is largely exempt from Doze/standby *while its window events are firing*, but a long automation sequence spanning idle time is not automatically protected** |

**No single mechanism controls everything.** Only AccessibilityService
reads and acts on arbitrary third-party UI; every other mechanism here is
either already in use for its own narrow purpose or is not a path to
general app automation at all.

## 5. AccessibilityService Investigation

ANDROID PLATFORM FACT, with CURRENT-repo implications noted inline.

- **Lifecycle**: a bound `Service`, started by the system once the user
  enables it in Settings, receiving `onAccessibilityEvent`/`onInterrupt`
  callbacks; `onServiceConnected` is where `AccessibilityServiceInfo` is
  finalized. The system can and does kill/restart it independent of the
  host app's own process (its own binding, its own lifecycle) — this is a
  **second, independent process-death surface** beyond anything M1–M8's
  recovery model has ever had to reason about (M5/M7 all assume a single
  `DpsApplication` process; §15 addresses this directly).
- **Manifest declaration**: a `<service>` with
  `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
  (mandatory — prevents any app but the system from binding it) and an
  intent-filter for `android.accessibilityservice.AccessibilityService`,
  plus a `<meta-data>` pointing at an XML config resource. **None of this
  exists in the current manifest** (§3) — this is 100% new surface for M9,
  not an extension of an existing declaration.
- **XML configuration** (`res/xml/accessibility_service_config.xml`
  equivalent): declares `accessibilityEventTypes` (e.g.
  `typeWindowStateChanged|typeWindowContentChanged`),
  `packageNames` (a filter — **critically, this is where the "which apps
  can DPS automate" boundary can be enforced at the OS level**, not just in
  application code), `accessibilityFlags` (e.g.
  `flagRetrieveInteractiveWindows` to get node bounds/actions),
  `canRetrieveWindowContent="true"` (required to read the tree at all),
  and `canPerformGestures` (API 24+, required for `dispatchGesture`).
- **Required user enablement**: Settings → Accessibility → find the app →
  toggle on, with an OS-presented warning describing exactly what
  accessibility access can see and do (screen content, actions on your
  behalf). **No API grants this programmatically; the only thing an app can
  do is launch `Settings.ACTION_ACCESSIBILITY_SETTINGS` and ask the user to
  find and enable it themselves** — a materially heavier-weight, more
  visible consent step than any runtime permission dialog this app has ever
  shown. This is directly analogous in *shape* to `SCHEDULE_EXACT_ALARM`'s
  existing `SPECIAL_ACCESS` handling (query state, launch settings, no
  dialog) but is a **materially higher-trust ask** in substance — the
  report treats the shape as reusable (§11) and the trust weight as a
  distinct, explicit product decision M9 must not smuggle in as "just
  another special permission."
- **Event types / window changes**: `TYPE_WINDOW_STATE_CHANGED` (a new
  screen/activity became foreground) and `TYPE_WINDOW_CONTENT_CHANGED` (the
  current screen's content mutated) are the two an automation flow needs to
  know *when* to re-read the tree — event-driven observation, not polling
  (§20).
- **Node tree access**: `AccessibilityNodeInfo` exposes `getText()`,
  `getContentDescription()`, `getViewIdResourceName()`, `getClassName()`,
  `getBoundsInScreen()`, `isClickable()`/`isEditable()`/`isScrollable()`,
  and child/parent traversal. `findAccessibilityNodeInfosByText`/
  `...ByViewId` provide bounded search.
- **Actions**: `performAction(ACTION_CLICK)`, `ACTION_SET_TEXT` (API 21+,
  the correct way to type into an editable node — no need to simulate
  keystrokes), `ACTION_SCROLL_FORWARD`/`BACKWARD`, and global actions
  (`GLOBAL_ACTION_BACK`/`HOME`/`RECENTS`) via
  `performGlobalAction`. `dispatchGesture` (API 24+) covers swipe/long-press
  where no direct node action exists.
- **Node stability / staleness**: an `AccessibilityNodeInfo` reference can
  become stale the instant the underlying view is recycled, scrolled out,
  or the screen changes — calling an action on a stale node either no-ops
  or throws (platform-documented, not this project's assumption). **Any M9
  design must re-query the tree immediately before acting, not cache a node
  across a suspend boundary or a user-visible delay.**
- **Security/privacy**: this is the single most privacy-sensitive capability
  Android exposes to a third-party app short of root — the OS's own consent
  screen says so explicitly. Google Play has historically enforced strict,
  narrow-purpose review for accessibility-service apps (declared use case
  required, misuse is a policy/removal risk) — a real constraint on this
  project's eventual distribution, not merely an implementation detail.

**Verdict on AccessibilityService's role**: **(A) core M9 architecture** —
it is the only mechanism satisfying "read arbitrary app UI, act on it,"
which is M9's stated mission. Not (B) optional: without it there is no M9.
Not (C) deferred: the mission requires it now. Not (D) rejected: no
platform or repo evidence disqualifies it, only evidence that it must be
scoped narrowly (§9, §18, §24).

## 6. Intent/Deep-Link Investigation

CURRENT: already the backbone of `PrepareWhatsAppMessageTool`/
`PrepareEmailTool`/`AndroidCallTool` — each builds an explicit `Intent`,
checks `IntentLauncher.canHandle()` before launching (avoiding
`ActivityNotFoundException`), and hands the *final* consequential action to
the user, never completing it itself (`WhatsAppIntentBuilder`,
`EmailIntentBuilder`, `DialIntentBuilder`, all frozen, all unmodified by
M8). This is not a new mechanism for M9 to introduce — it is a proven
pattern M9's `OPEN_APP`/`OPEN_DEEP_LINK` action vocabulary entries (§8)
should reuse verbatim: launch, then hand off to AccessibilityService (or to
the user directly) for whatever comes next, rather than building a second,
parallel intent-launching path.

## 7. Candidate Automation Architectures

PROPOSED, evaluated against evidence, not preference:

**Do dedicated `AutomationAction`/`AutomationStep`/`AutomationPlan`/
`AutomationObservation`/`AutomationVerification` types need to exist?**
Partially. `DpsIntent`/`IntentParameters` already model "one user request,"
and `PendingPlan` already models "the rest of a multi-step plan" — neither
needs replacing. But **`ToolResult`'s seven closed cases have no shape for
"element not found," "duplicate match," or "node became stale"** — these
are not failures in the `ToolResult.Failure` sense (the tool didn't
malfunction) nor `Error` (not a defect) nor `Unsupported` (the operation is
supported, the *target* wasn't found). This is the one place new domain
vocabulary is actually justified by a real gap, not speculative
abstraction: a bounded `AutomationObservation` outcome (found/not-found/
ambiguous/stale), analogous in spirit to `CalendarEventReader
.EventSnapshotOutcome` (M7's own precedent for exactly this shape:
Found/NotFound/Failed), not a generic new framework.

**Full `AutomationPlan` as a parallel concept to `PendingPlan`**: not
justified. `PendingPlan`'s existing shape (steps, offsets, completed
replies) is already generic over *what kind* of step is blocked — an
automation step blocking on "element not found" is structurally identical
to today's step blocking on "clarification needed." Building a second plan
concept would duplicate M2-C's own machinery for no benefit — reuse it.

**Recommendation**: extend `ToolResult` (or, to avoid touching the frozen
M1-era sealed interface, wrap automation-specific outcomes *inside* a
`ToolResult.Success`/`Failure`'s `data` map the way M7 already does for
verification echo-back — this needs an explicit locked decision, not an
assumption) rather than building a parallel five-type object model.

## 8. Automation Action Vocabulary Analysis

Evaluated for feasibility/determinism/observability/safety, not assumed
wholesale:

| Action | Feasible | Deterministic | Observable | Safe for M9 | Verdict |
|---|---|---|---|---|---|
| `OPEN_APP` | Yes — explicit intent to a known package's launch intent | Yes | Yes (window-state-changed event) | Yes | **In scope** |
| `OPEN_DEEP_LINK` | Yes, for links the target app registers | Yes if the link is known-valid | Yes | Yes | **In scope, narrow** |
| `TAP` (on a resolved node) | Yes, via `ACTION_CLICK` | Yes, if the node was just freshly queried | Only by re-observation | Yes, with the risk gate (§12) | **In scope** |
| `TYPE_TEXT` | Yes, via `ACTION_SET_TEXT` | Yes | Yes | Conditionally — see §17 (sensitive fields) | **In scope with guardrails** |
| `CLEAR_TEXT` | Yes (`ACTION_SET_TEXT` with empty string) | Yes | Yes | Yes | **In scope** |
| `SCROLL` | Yes, `ACTION_SCROLL_FORWARD/BACKWARD` | Approximately — scroll distance is not exact | Yes | Yes | **In scope, as a means to find an element, not an end** |
| `PRESS_BACK`/`PRESS_HOME` | Yes, global actions | Yes | Yes (window change) | Yes | **In scope** |
| `SELECT` | Yes, if it maps to `ACTION_CLICK` on a selectable node | Yes | Yes | Yes | **In scope, same primitive as TAP** |
| `LONG_PRESS` | Yes, via `dispatchGesture` or `ACTION_LONG_CLICK` | Yes | Yes | Yes, but higher-risk (often reveals a context menu with destructive options) | **In scope, but candidate for CONFIRM_REQUIRED by default (§12)** |
| `SWIPE` | Yes, via `dispatchGesture` | Approximate | Yes | Only if bounded to scrolling a known container | **Defer** — real general-purpose swipe (e.g. dismissing, reordering) is not deterministic enough for a first slice |
| `READ_VISIBLE_TEXT` | Yes | Yes | N/A (it *is* the observation) | Yes | **In scope — this is the "OBSERVE" half of the pipeline, not a side action** |
| `FIND_ELEMENT` | Yes, bounded search (§10) | Only as deterministic as the matching rule | N/A | Yes | **In scope — the targeting primitive every other action depends on** |
| `WAIT_FOR_ELEMENT` | Yes, event-driven with a bound (§19) | Yes, if bounded | N/A | Yes | **In scope, strictly re-observation, never re-action (§19)** |
| `OBSERVE_UI` | Yes | Yes | N/A (it *is* the observation) | Yes | **In scope — same primitive as READ_VISIBLE_TEXT at the whole-screen level** |

**Explicitly out of scope for M9**: any action implying multi-touch
gestures beyond simple scroll (pinch/zoom/drag-and-drop), any action that
reads content off-screen without scrolling there first (defeats
observability), any action targeting system UI/other apps' settings
screens, and — per the master prompt's own non-goals — anything resembling
autonomous exploration ("try tapping around until something looks right").

## 9. App Targeting Model

CURRENT precedent: `<queries>` already requires each target package to be
explicitly declared (§3) — this is not new for M9, it is the existing rule
applied to a new capability.

PROPOSED model: a target app is identified by **package name only**,
resolved from a small, explicit, developer-maintained allowlist (mirroring
`<queries>`'s own explicit-package convention) — never by app label alone
(labels are user-language, ambiguous, and localized) and never by letting a
model-generated string map directly to a package. A user saying "open
WhatsApp" should resolve through a fixed `{"WhatsApp" -> "com.whatsapp"}`
table the same shape `AndroidTool`'s own `ToolId.fromName()` already uses
for tool names — not a fuzzy or LLM-driven lookup.

- **Ambiguity handling**: if a spoken app name matches no entry in the
  allowlist, the honest answer is `Unsupported`/`Failure` — never a guess,
  matching this codebase's own established doctrine for ambiguous contact
  matches (`ContactMatch.Ambiguous`, never silently resolved).
- **Uninstalled app handling**: `PackageManager.getLaunchIntentForPackage()`
  returning `null` (or `queryIntentActivities` returning empty, given
  package visibility) is the deterministic, already-idiomatic check this
  codebase's `IntentLauncher.canHandle()` already performs for WhatsApp/
  email/dialer — reuse it, don't invent a second existence check.
- **Package visibility**: every new target app needs its own `<queries>`
  entry — a real, per-app, reviewed manifest change, not a runtime
  decision. This bounds M9's practical app surface to a small, explicitly
  curated list by construction, not merely by policy.
- **Stale package information**: not meaningfully different from the
  existing "WhatsApp not installed" case `PrepareWhatsAppMessageTool`
  already handles — no new mechanism needed.

**No evidence supports model-generated app-name-to-package mapping being
safe.** This is a LOCKED conclusion of this investigation, not merely a
recommendation: guessing a package name wrong could open an entirely
unintended, possibly sensitive app.

## 10. UI Element Targeting Model

Investigated risk-first, per the master prompt's own emphasis:

- **Duplicate text**: two buttons labeled "Send" on one screen is common
  (message compose + a nearby "Send feedback" banner, say) — text-only
  matching is provably insufficient alone.
- **Dynamic UI / localization**: text changes with locale and with app
  updates; a hardcoded English string is fragile across both axes.
- **Stale node references**: covered in §5 — a node queried even one frame
  ago may already be invalid.
- **Scrolling / lazy-loaded elements**: an element not currently in the
  tree may simply not be rendered yet, which looks identical to "does not
  exist" without a bounded scroll-and-recheck step.

**Recommendation, evidence-based**: layered, deterministic matching, most
specific first — `viewIdResourceName` (stable across locale, present on
well-built apps, absent on poorly-built ones) → `className` +
`contentDescription` (accessibility-authored, more stable than visible
text) → exact visible text as a last resort, always scoped to a specific,
already-known parent/container rather than the whole tree, and **always
require the match to be unique** — an ambiguous match is `Failure`
(mirroring `ContactMatch.Ambiguous`), never "pick the first one." **No
fuzzy/AI-based UI targeting is justified by any evidence gathered here** —
the master prompt's own instruction not to introduce one without
justification is affirmed, not merely deferred.

## 11. LLM Boundary

LOCKED (M8, unmodified, reaffirmed here): the model classifies natural
language into a structured intent, once, and is never consulted again for
the same request (`ToolOrchestrator`'s own "one inference pass, not two"
doctrine, verified unchanged in §2/§3).

For M9 specifically: the safer of the two architectures the master prompt
poses is the first —

```
Natural Language → DpsIntent (existing, unchanged) → a new, narrow
AUTOMATION-shaped IntentType/parameters → deterministic target/action
resolution (§9, §10) → RiskPolicy (§12) → permission/confirmation (LOCKED,
M8) → execution
```

not "structured automation plan" as a second, separate LLM output shape —
introducing a second schema the model must learn to emit would duplicate
`DpsIntent`'s own role and give the model two chances to be wrong instead
of one. The model should emit **one target app name + one described
action + one described element**, in the same `IntentParameters` shape
every other intent already uses, and every subsequent step
(app-name-to-package, element-matching rule selection, risk
classification) is 100% deterministic code, with zero model involvement,
exactly as M8 already established for risk and this investigation confirms
should extend unchanged to automation. **The model is never handed raw
node text and asked to "decide what to click" — that would be exactly the
"LLM-controlled arbitrary UI action" the master prompt's non-goals
forbid.**

## 12. M8 Risk Integration

LOCKED: only `SAFE_AUTO`/`CONFIRM_REQUIRED` exist; M9 must not invent a
third state or a parallel risk model.

Evidence-based classification for automation:

- **Risk should depend on the *destination app and the described action's
  nature*, not on automation-vs-tool as a category.** A `TAP`/`TYPE_TEXT`
  sequence that ends in something equivalent to "send" (a message, a post,
  a payment, a delete) is exactly the class of action `RiskPolicy` already
  treats as `CONFIRM_REQUIRED` for the tool-based case (`cancel_task`,
  `delete_event`, `call_contact`, `forget_fact`) — the same policy object
  should classify automation actions by the same lens: is the action
  destructive/irreversible/externally-communicative, not by which
  mechanism performs it.
- **A reasonable, evidence-consistent default**: `OPEN_APP`/`FIND_ELEMENT`/
  `READ_VISIBLE_TEXT`/`OBSERVE_UI`/`WAIT_FOR_ELEMENT` are `SAFE_AUTO` (they
  observe, they don't change state). `TAP`/`TYPE_TEXT`/`LONG_PRESS`/any
  action whose target node's text/description matches known
  send/submit/delete/confirm/pay vocabulary should default
  `CONFIRM_REQUIRED` — mirroring exactly how `PrepareWhatsAppMessageTool`'s
  own philosophy ("the confirmation flow is the feature") already treats
  message-sending as needing a human's final tap even *without* any
  accessibility involvement. Automation should not lower that bar just
  because DPS can now technically press the button itself.
- This is a PROPOSED default, not a locked decision — the exact
  vocabulary/heuristic for "looks like a send/delete/pay action" is a real,
  open design question (§27), but the *shape* (extend `RiskPolicy.classify`,
  don't replace it) is locked by M8's own contract.

## 13. M7 Observation/Verification Integration

`ExecutionVerifier`'s four-outcome model (`Verified`/`Mismatch`/`NotFound`/
`ObservationFailed`) is conceptually reusable — **the same four questions
apply**: did the expected state now exist (Verified), does it exist but
differ (Mismatch), does it not exist at all (NotFound), or could DPS not
check (ObservationFailed, e.g., the AccessibilityService disconnected mid-
check). But the *mechanism* is not reusable as-is: `ExecutionVerifier`
re-reads a real repository/content-provider record by a stable id
(`taskId`, `eventId`) — an automation action has no such id, only "the
screen now shows/doesn't show X," which is a UI-tree read, not a repository
read.

**Recommendation**: reuse `VerificationOutcome`'s four-case *shape and
naming* (do not invent new terms for the same four concepts — that would
be the "redesigning M7 without evidence" the master prompt forbids), but
the actual read for automation is a fresh `OBSERVE_UI`/`FIND_ELEMENT` call
against the live tree, not `ExecutionVerifier`'s existing
repository-reading code path. Whether this becomes a new, sibling verifier
class (analogous to `ExecutionVerifier` but UI-tree-backed) or an extension
of the existing one is an open question for the planning phase (§27) — the
*outcome vocabulary* is locked to M7's existing four names.

## 14. M2 Multi-Step Integration

Evaluated against the exact worked example the master prompt poses ("Open
WhatsApp, open a chat, type a message, then..."):

- **Does each UI action become a plan step?** No — evidence from M2's own
  design argues against it. `PendingPlan` steps are `DpsIntent`s, one per
  *user-meaningful* request; "open the app," "find the chat," "tap it,"
  "type the message" are all sub-steps of *one* user request ("message
  Abdul on WhatsApp"), not four separate user asks. Splitting them into
  separate plan steps would surface a confirmation/clarification prompt
  after step 2 for a request the user experiences as one action.
- **Recommendation**: one user-facing automation request is one
  `DpsIntent`/one plan step, internally sequenced through
  open→find→act→observe→verify as a single tool's own internal state
  machine (mirroring how `AndroidCalendarTool.createEvent()` already
  internally sequences "resolve calendar → build event → insert → read
  back id" as one opaque tool call, not four plan steps). Verification
  happens once, at the end of that one step, exactly where M7 already
  verifies `create_task`/`create_event` today.
- **Step 3 (of a *separate*, later plan) failing**: unaffected — existing
  `continuePlan` behavior (a failed/unverified step stops the plan, drops
  the remainder) already covers this with zero new code.
- **Confirmation's effect on the plan**: identical to today — a
  `CONFIRM_REQUIRED` automation step pauses the *whole* plan exactly like a
  `CONFIRM_REQUIRED` tool step does today (M8, §12 of the M8 report), parks
  the remainder, resumes on yes, drops it on no/unclear.
- **What survives process death**: the same boundary M5-B already draws —
  a *blocked* automation step's `PendingConfirmation` survives (reusing
  M8's mechanism verbatim); a step *mid-execution* (between TAP and
  OBSERVE) does not have an equivalent to `OperationCheckpoint` yet — this
  is a genuine, new gap addressed in §15/§16, not an extension of an
  existing solved problem.

## 15. Process-Death/Recovery Analysis

Walking the master prompt's own five points, using the exact
already-established M5/M7 vocabulary:

1. **Process dies before the action** (mid `OPEN APP`/`FIND ELEMENT`,
   nothing yet performed): equivalent to today's "process died before any
   real side effect" case — nothing to recover, the next message is just a
   fresh request. No new mechanism needed.
2. **Process dies after the action but before observation** (DPS tapped
   "Send," then died before ever checking the screen): this is the
   genuinely new, `OperationCheckpoint`-shaped gap. M5-C's own checkpoint
   answers exactly this question for creates ("did the write happen, I
   don't know, don't retry, tell the user honestly") — the same pattern
   (write a durable marker *before* the irreversible action, clear it only
   once the action's outcome is confirmed one way or another) generalizes
   directly: write a checkpoint before `TAP`, clear it after `OBSERVE`
   resolves either way. This is the one place M9 should extend
   `OperationCheckpoint`'s *pattern*, not necessarily its own `OperationType`
   enum (a new `OperationType.AUTOMATION_ACTION` case, or a sibling type —
   an open, small decision, §27).
3. **Process dies after observation but before verification**: narrower
   than it sounds — if observation (reading the tree) already completed
   and is held in memory only, this is the same "verification itself never
   ran" window M7's own `PendingVerification` already exists for. Reusable
   as-is in spirit, once the automation-specific observation exists (§13).
4. **Process dies after verification**: verification is terminal and
   already clears its own pending record (`ExecutionVerifier.verify`'s own
   save→observe→compare→clear sequence) — nothing new needed.
5. **The AccessibilityService dies independently of the DPS process**: this
   is the one scenario with **no M1–M8 precedent at all** — every existing
   recovery mechanism assumes a single process. A service unbound
   mid-action is architecturally closer to "permission revoked mid-flight"
   than to "process died" — the automation action should fail closed
   (`ObservationFailed`-shaped, per §13) and the *user's* DPS session
   continues normally; it should not be treated as a `SecretaryOrchestrator`-
   level process-death event, since the two processes' lifecycles are
   independent by platform design.

**Conclusion**: M5/M7's infrastructure supports points 1, 3, and 4 as-is or
with a small, precedented extension. Point 2 needs a genuinely new
checkpoint-shaped write for the specific "before the button is pressed"
window. Point 5 needs a genuinely new failure category, not a recovery
mechanism — reused vocabulary (`ObservationFailed`), not reused code path.

## 16. Idempotency Analysis

Directly answering the master prompt's own worked example ("DPS taps
Send. Process dies. After restart, must not blindly tap Send again"):

The distinguishing states the master prompt names map cleanly onto
existing or lightly-extended vocabulary:
- `ACTION_PENDING` → the new pre-action checkpoint from §15 point 2,
  unresolved.
- `ACTION_UNKNOWN` → what a leftover, uncleared checkpoint *means* on
  restart — exactly `OperationCheckpoint`'s own existing doctrine ("the
  operation's outcome is genuinely unknown... never treated as permission
  to retry" — verbatim from that class's own doc, unmodified since M5-C).
- `OBSERVATION_PENDING` → the M7-shaped gap in §15 point 3.
- `VERIFIED`/`FAILED` → `VerificationOutcome`'s own existing terminal
  states.

**The safe, evidence-consistent rule, directly inherited from M5-C's own
established doctrine, not invented here**: a leftover automation
checkpoint on restart is surfaced once, honestly, as "I may have done X but
couldn't confirm it — please check" (the exact wording pattern
`outstandingCheckpointNotice` already uses for `create_task`/`create_event`)
and is **never** auto-retried, exactly as M5-C already refuses to
auto-retry a leftover create. No new idempotency *concept* is needed; the
existing one already generalizes.

## 17. Failure Model

Bounded outcomes for every scenario the master prompt lists, using only
vocabulary already established or explicitly proposed above — no
autonomous alternative action or replanning in any branch:

| Scenario | Outcome |
|---|---|
| Target app not installed | `Unsupported` (existing `ToolResult` case, existing `IntentLauncher.canHandle()` pattern) |
| App cannot open | `Failure`, retryable=false unless evidence suggests otherwise |
| UI element not found | New bounded observation outcome (§7), surfaced as `NotFound`-shaped |
| Duplicate element found | Same new outcome, `Ambiguous`-shaped (mirrors `ContactMatch.Ambiguous`) — never auto-picks one |
| UI changed mid-action | Re-observe once (§19), then `ObservationFailed`-shaped if still inconsistent — never re-tap blindly |
| Node becomes stale | Re-query fresh before acting, per §5 — if still stale, `ObservationFailed`-shaped |
| Action rejected by the platform | `Failure` |
| Accessibility service unavailable/disconnected | `ObservationFailed`-shaped (§15 point 5) |
| Permission missing | Existing M8 permission-precheck path, unchanged |
| Target app crashes | `ObservationFailed`-shaped — DPS cannot distinguish "crashed" from "slow" without a bounded wait (§19) |
| Network is loading (inside the target app) | Bounded wait (§19), then `ObservationFailed`-shaped if still unresolved |
| Timeout | Existing `ToolResult.Timeout` case, reused as-is |
| Process dies | §15/§16 |
| Android kills the accessibility service | Same as "unavailable" above |
| Target app changes state unexpectedly | `Mismatch`-shaped, or `ObservationFailed`-shaped if the new state is unrecognizable |

Every row terminates in an existing or newly-proposed *bounded* outcome —
none implies retrying the action itself, choosing a different action, or
continuing a multi-step plan unattended, matching the locked M7/M8
"no autonomous retry, no autonomous replanning" contract exactly.

## 18. Security Model

Minimum viable M9 security architecture, evidence-based:

- **App/package allowlisting**: enforced twice, redundantly and
  deliberately — once in application code (§9's explicit allowlist) and
  once at the OS level via the AccessibilityService's own `packageNames`
  config filter (§5) restricting which apps even *deliver* events to this
  service at all. Belt-and-suspenders is justified here specifically
  because accessibility access, once granted, is otherwise unbounded by the
  OS.
- **Accessibility service trust**: the service must do nothing on its own
  initiative — every action it performs must originate from a
  `SecretaryOrchestrator`-issued, already-risk-classified,
  already-permission-checked, already-confirmed-if-required instruction.
  It is a mechanism the orchestration layer drives, never an independent
  decision-maker — directly extending M8's own "deterministic code only
  past classification" doctrine.
- **Sensitive UI detection**: a bounded, explicit denylist of node
  signals (password/PIN input types, fields whose resource id or hint
  matches password/OTP/CVV/account-number vocabulary — the same *shape* of
  denylist `MemoryPrivacyGuard` (M6) already uses for semantic-memory
  writes) that DPS must refuse to read or type into, full stop, not merely
  flag for confirmation. This is a NON-GOAL boundary, not a risk tier.
- **UI spoofing / accidental clicks**: mitigated by §10's requirement that
  every match be unique and freshly-queried immediately before acting —
  the same discipline that prevents a stale node from being clicked
  prevents a maliciously-relabeled node from being mistaken for the
  intended one, though this is a partial mitigation, not a guarantee (open
  question, §27).
- **Unintended app switching**: `OPEN_APP` should always be a `SAFE_AUTO`
  observation step (§12) but every action *after* that point operates only
  within the `packageNames`-filtered service scope — an accidental switch
  to an unlisted app produces no further automation events at all, by OS
  enforcement, not merely by application-level discipline.
- **Data leakage through the accessibility tree**: addressed as privacy,
  §19.

## 19. Privacy Model

- **What DPS actually needs**: the specific node(s) relevant to the current
  action/observation — never a full-screen tree dump persisted anywhere.
- **UI text persistence**: **NON-GOAL** — no automation-observed text
  should be written to `ConversationMemory`, semantic memory, or episodic
  memory (M6) unless it is the literal, minimal fact the user asked DPS to
  remember, mirroring exactly how M6's `EpisodicMemoryRecorder` already
  logs a tool's own summary, never raw internals.
- **Screenshots**: **NON-GOAL** for M9 — no evidence in this investigation
  motivates capturing pixel data when the node tree already provides
  structured, purpose-built access; screenshots would be strictly more
  invasive for no demonstrated benefit.
- **Logging**: `DpsLogger` calls touching node content must redact, exactly
  as `core/logging/redact` already exists and is already used for model
  classification output (`ToolOrchestrator.generateClassification`'s own
  `logger.d(TAG, "Classification produced ${redact(text)}")`) — reuse the
  existing utility, don't build a second one.
- **Local-first**: unaffected — nothing about AccessibilityService requires
  network access; the existing `allowBackup="false"` posture already
  ensures none of this data leaves the device via backup either.

## 20. Performance Considerations

- **Event-driven, not polling**: `TYPE_WINDOW_CONTENT_CHANGED`/
  `TYPE_WINDOW_STATE_CHANGED` callbacks are the correct trigger for
  re-reading the tree — a polling loop would cost battery/CPU for no
  benefit the event stream doesn't already provide, and this project's own
  `ReminderTriggerInstrumentedTest` (this session's own regression evidence)
  already demonstrates real-device timing/background-delivery variance is
  a genuine, observed risk category here, not a theoretical concern.
- **Tree traversal cost**: bounded, targeted searches
  (`findAccessibilityNodeInfosByViewId`/`...ByText` scoped to a known
  container) rather than full-tree walks for every action.
- **Battery/CPU**: an enabled AccessibilityService with a narrow
  `packageNames` filter and narrow `accessibilityEventTypes` receives
  events only for in-scope apps and in-scope event types — the filter
  itself is the primary performance lever, decided once at declaration
  time, not something to "optimize" further without evidence of an actual
  problem.
- **Latency**: bounded waits (§19-of-the-master-prompt/here labeled §17's
  "bounded wait") should be short and capped, consistent with
  `ToolExecutor.DEFAULT_TIMEOUT_MILLIS` (10s) already governing every other
  tool call — no evidence justifies a different budget for automation
  specifically, pending real-device measurement in the planning phase.

## 21. Android Version Compatibility

`minSdk 26`/`targetSdk 35`, confirmed directly from `build.gradle.kts`
(§3) — not assumed.

- `AccessibilityNodeInfo.ACTION_SET_TEXT` and `dispatchGesture`: both API
  24+ — safely below `minSdk 26`, no version gate needed for these two.
- `AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON` and other
  newer flags: version-dependent, but not required for the minimum M9
  slice (§24).
- **Foreground service typed sub-permissions** (API 34+,
  `FOREGROUND_SERVICE_SPECIAL_USE` or a more specific type) would gate any
  design that keeps a *foreground service* alive during automation — this
  needs an explicit `Build.VERSION.SDK_INT` check exactly like
  `AndroidReminderTool`'s existing `SCHEDULE_EXACT_ALARM`
  API-31-vs-below branching, if a foreground service is used at all
  (open question, §27 — AccessibilityService itself does not require one).
- **Background execution restrictions**: from API 26, implicit broadcasts
  to manifest receivers are restricted (already handled correctly for
  `ReminderBootReceiver` per its own manifest comment, §3) — a data point
  confirming this project already reasons about this axis correctly, not a
  new risk for M9 specifically, since AccessibilityService callbacks are
  not implicit broadcasts and are not subject to this restriction.
- **Package visibility** (API 30+): already correctly handled (§3);
  every new M9 target app needs the identical treatment.

No version gap was found that blocks M9 architecturally on this project's
actual `minSdk`.

## 22. Testing Architecture

- **JVM**: action-vocabulary validation, target-app-allowlist resolution,
  element-matching-rule selection, `RiskPolicy` classification for
  automation intents — all pure functions of already-classified data,
  exactly like M8's own `RiskPolicyTest.kt`, fully JVM-testable with no
  Android dependency, given the seams described in §9/§10/§12 are built as
  pure functions (as every prior milestone's own equivalent seam already
  was).
- **Instrumented**: service lifecycle (enable/disable, connect/disconnect),
  real app launch, real UI discovery against a *known, controlled* target,
  real action + real observation.
- **Real device**: everything instrumented tests cannot fake — genuine
  accessibility enablement state, genuine target-app behavior, genuine
  process death of *both* the DPS process and (separately) the
  accessibility service, genuine recovery.
- **A dedicated local test target app is justified by evidence, not
  convenience**: automating a real third-party app (WhatsApp, say) for
  deterministic regression tests is fragile by construction — that app's
  UI, text, and layout are entirely outside this project's control and
  change on its own release schedule, exactly the "dynamic UI/localization"
  risk §10 already identifies as a real problem. A minimal, first-party
  test app under this project's own control (fixed, versioned, never
  changes without this project's own decision) is the only way to get
  genuinely deterministic UI-automation regression coverage — **this
  investigation recommends building one, but explicitly does not create it
  now** (out of scope for an investigation phase), matching the master
  prompt's own instruction.

## 23. Real-Device Validation Plan

Directly reusing the established, already-proven-nine-times-over
methodology (M3-D through M8), applied to the nine numbered items the
master prompt itself lists:

1–2. **Service genuinely enabled / target app genuinely launches**: read
   `AccessibilityManager.getEnabledAccessibilityServiceList()` (or the
   equivalent settings query) and `ActivityManager`'s running-tasks/
   window-state, on the real device, not assumed from a manifest
   declaration.
3–4. **Real UI discovered / real action occurs**: instrumented tests
   against the recommended local test target app (§22), asserting on the
   test app's own, first-party-controlled state (a counter incremented, a
   text field's real content) — not on inference from DPS's own reported
   outcome, mirroring M7's own "observe real state, don't trust internal
   bookkeeping" discipline exactly.
5–6. **Resulting UI observed / verified**: same test app, same real-state
   assertion.
7–9. **Process death / recovery / no duplicate action**: the exact
   two-phase `phase1.../phase2...` + genuine `adb shell am force-stop` +
   `pidof`-confirmed-dead methodology this session's own
   `ConfirmationProcessDeathInstrumentedTest` just used for M8, extended to
   also force-stop/restart the **accessibility service** independently
   (a genuinely new methodology step, not previously needed since M1–M8
   never had a second process to kill) — needs its own real-device proof,
   not an assumption that the existing DPS-process-only methodology
   automatically covers it.

## 24. Minimum M9 Scope Recommendation

The master prompt's own candidate slice is **sufficient and correctly
scoped**, evidence-based:

1. Launch one known, allowlisted app.
2. Inspect its visible UI (read-only — `OBSERVE_UI`/`READ_VISIBLE_TEXT`).
3. Identify one deterministic element (§10's layered matching, unique-match
   required).
4. Perform one bounded interaction (`TAP` or `TYPE_TEXT`, `SAFE_AUTO`
   unless the target matches the sensitive/destructive vocabulary, §12).
5. Observe the resulting state.
6. Verify against an expected outcome (§13's four-outcome vocabulary).

This is enough for M9 Phase 1 because it exercises every genuinely new
piece of architecture (AccessibilityService lifecycle, node targeting,
UI-backed observation/verification, the new pre-action checkpoint) exactly
once each, against a single, ideally first-party-controlled target app —
proving the pipeline end-to-end without also having to solve multi-app
generality, complex multi-step chains, or the full sensitive-action
denylist in the same slice. **A full "open WhatsApp, find a chat, type and
send a message" slice is not recommended as Phase 1** — it compounds
targeting risk (a real third-party app's UI), risk-classification
uncertainty (send is the most consequential of the candidate actions), and
new-architecture risk (everything else in this report) into one slice with
no isolated failure signal.

## 25. Explicit Non-Goals

Restated and confirmed unaffected by every finding above: unrestricted
device control, autonomous browsing, autonomous social-media activity,
autonomous purchasing/banking/financial actions, password/OTP/PIN handling
or interception, security or permission bypass, hidden UI interaction, root
or exploit-based automation, background autonomous agent behavior,
proactive intelligence (M10), advanced voice (M11), productization (M12),
autonomous replanning, autonomous retry, and LLM-controlled arbitrary UI
actions (§11 explicitly rules this out architecturally, not just by
policy).

## 26. Risks

| Risk | Likelihood | Impact | Mitigation | Blocks M9? |
|---|---|---|---|---|
| AccessibilityService is a fundamentally new process/lifecycle this codebase has never modeled | High (certain) | High | §15's explicit gap analysis; new real-device methodology (§23) required, not assumed | No — identified, not unknown |
| Node staleness causes an action to silently no-op or hit the wrong element | Medium | High | §5/§10's "always re-query immediately before acting, require unique match" discipline | No |
| Real third-party app UI changes break deterministic tests | High over time | Medium (test maintenance, not runtime safety) | §22's first-party test-app recommendation | No |
| Play Store policy risk for declaring accessibility-service usage | Medium | High (distribution risk) | Narrow, justified, disclosed use case; §18's minimal-scope service config | No — a product/business decision to make explicitly, not a technical blocker |
| Automation risk classification (§12) under- or over-triggers CONFIRM_REQUIRED | Medium | Medium | Explicitly flagged as an open, non-blocking design question (§27) | No |
| A second, independent process (the service) complicates the "genuine process death" test methodology | Medium | Medium | §23's explicit new methodology step | No |

No risk found rises to blocking.

## 27. Open Architectural Questions

1. **Exact `ToolResult`/verification extension mechanism** (§7, §13): new
   sealed cases vs. `data`-map echo-back vs. a sibling verifier class.
   *Why it matters*: shapes every subsequent file in the implementation
   plan. *Evidence needed*: none new — a locked decision for the planning
   phase. *Blocks proceeding to planning?* No.
2. **Exact automation-risk vocabulary** (§12): what node
   text/description/resource-id patterns constitute "looks like send/
   delete/pay." *Why it matters*: determines the default
   `SAFE_AUTO`/`CONFIRM_REQUIRED` split for the one real target app chosen.
   *Evidence needed*: a locked decision once the first target app is
   chosen. *Blocks?* No.
3. **New `OperationCheckpoint` shape vs. new sibling type** (§15 point 2).
   *Blocks?* No — either is buildable from evidence already gathered.
4. **Whether a foreground service is needed alongside AccessibilityService**
   to keep an automation session alive while DPS's own UI is backgrounded
   (§21). *Evidence needed*: real-device measurement of whether
   AccessibilityService's own callbacks are throttled while DPS is
   backgrounded on this project's `minSdk`/target OEM matrix. *Blocks?* No
   — resolvable empirically during planning/implementation, not a
   precondition for locking architecture.
5. **Whether to build the recommended first-party test target app now or
   defer it to the start of implementation** (§22). *Blocks?* No —
   explicitly out of scope for this investigation phase either way.

**No open question here blocks proceeding to architectural decisions.**

## 28. Proposed M9 Milestone Breakdown

Not a phased implementation plan (out of scope for this document) — a
suggested shape for the planning phase to structure around:

- **M9-A**: AccessibilityService scaffolding (manifest, config, lifecycle,
  enablement detection) — no automation logic yet, proving the new
  process/lifecycle surface in isolation.
- **M9-B**: App targeting + element targeting (§9, §10), pure/JVM-testable.
- **M9-C**: The minimum action vocabulary (§8's "in scope" rows only) +
  RiskPolicy extension (§12).
- **M9-D**: Observation/verification extension (§13).
- **M9-E**: Pre-action checkpoint + process-death recovery, including the
  new dual-process methodology (§15, §23).
- **M9-F**: The one, first-party test target app (§22), if built.
- **M9-G**: Full regression + genuine real-device validation against every
  item in §23.

## 29. Final Readiness Verdict

**READY FOR ARCHITECTURAL DECISIONS**

Current architecture is fully understood and re-verified against live
source (§2, §3). Android automation mechanisms are surveyed with concrete
platform facts, not assumptions (§4–§6). AccessibilityService feasibility
is established with an explicit A/B/C/D verdict and evidence (§5). M7/M8
integration points are mapped precisely, distinguishing what reuses
existing vocabulary from what needs new mechanism (§12–§14). Process-death
implications are analyzed point-by-point against the master prompt's own
five scenarios, with exactly one genuinely new gap identified and scoped,
not hand-waved (§15). Security and privacy boundaries are defined with
concrete, bounded mechanisms, not aspirations (§18–§19). A minimum M9 scope
is defined and justified against alternatives (§24). Every open question
(§27) is explicitly non-blocking, with a stated reason.
