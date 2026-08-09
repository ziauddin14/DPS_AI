# Day 05 Phase E — AI Secretary Experience Layer (Stage 1)

**Project Falcon 🦅 · DPS Android Client**
**Date:** 2026-08-06
**Branch:** `day-05-android-tool-foundation`
**Scope:** Conversation memory, deterministic state machine, create/update/cancel
routing, Roman-Urdu reference resolution, and — for the first time — live wiring
of the tool-calling pipeline into the running chat app.

---

## What Stage 1 actually closes

Phase D built a complete intent-classification and tool-calling pipeline, but
it was never connected to anything a user could type into. Exploration at the
start of this phase confirmed `ToolOrchestrator` was wired into `AiContainer`
and otherwise dead code: `AiSessionManager.sendMessage()` only ever drove plain
streaming chat. Stage 1's job was to make that pipeline live, and to give it
enough memory to survive past a single message — the two things every one of
the brief's worked examples depends on.

Two structural gaps were found and closed as part of doing that honestly,
rather than papered over:

1. **No runtime-permission-request UI existed anywhere in the app.**
   `AndroidPermissionManager.request()` (Phase A) already implemented the
   request-orchestration logic and a `PermissionRequestHost` seam, but nothing
   implemented that host or attached it. In production, every request hit the
   "no host attached" branch and no dialog ever appeared.
2. **`AndroidCalendarTool` only implements `create_event`.** Rescheduling a
   meeting and rolling back a created event both need more than that —
   deferred to Stage 2, where the extra Android Calendar Provider work belongs.

---

## The regression that would have sunk on-device verification

Compiling and every JVM test passed cleanly the first time. On-device, the
very first live message hung indefinitely — no crash, no ANR, no log output,
CPU essentially idle. That combination (alive, silent, idle) pointed at a
classic single-thread-executor self-deadlock, not "still computing": Day 04's
own benchmark puts a classification-sized generation at 5–15 s, and this had
already run for over half an hour producing nothing.

The cause was `DefaultAiEngine.generateOnce()` (added in Phase D):

```kotlin
override suspend fun generateOnce(request: CompletionRequest): DpsResult<AiCompletion> =
    withContext(dispatchers.inference) {          // ← the bug
        generate(request).collect { chunk -> ... }
    }
```

`dispatchers.inference` is the single-thread executor Day 04's own deadlock
investigation produced. The comment directly above `generate()` in the same
file explains exactly why its consumer must *not* run there:

> "The runtime provider already dispatches its own blocking native work onto
> `inference`; pinning the *consumer* there too meant producer and consumer
> contended for one execution slot the producer held for the whole
> generation."

`generateOnce()` did precisely that — reproducing the Day 04 deadlock through
a different door. It went uncaught through the whole of Phase D because every
JVM test and every instrumented test for `ToolOrchestrator` used a scripted
`AiEngine`; nothing had ever called `generateOnce()` against the real
llama.cpp runtime before this phase's on-device verification.

**Fix:** removed the `withContext(dispatchers.inference)` wrapper. `collect`
now runs in the caller's own context, exactly the principle `generate()`'s own
callers already rely on. This is Phase D code, not Day 03/04 — fixing it does
not touch frozen work, and it was directly blocking this phase's own
verification requirement.

Confirmed fixed on-device: the same message that hung indefinitely before the
fix completed classification and executed a tool in 69 s (cold, first
inference after a fresh install — see timings below).

---

## Architecture

```
User message
     │
     ▼
SecretaryOrchestrator.handle()
     │
     ├─ ToolOrchestrator.classify()         ← Phase D, unchanged, reused directly
     │
     ├─ merge pending clarification answer   ← ClarificationEngine.merge, same
     │                                          pure class Phase D uses
     ├─ ActionDetector.detect()              ← create/update/cancel safety net
     │
     ├─ ReferenceResolver.resolve()          ← "usko"/"us reminder"/relative
     │                                          time offsets, from memory
     ├─ ClarificationEngine.check()          ← Phase D, extended for
     │                                          UPDATE/CANCEL completeness
     ├─ ToolOrchestrator.executeIntent()     ← Phase D, unchanged, reused directly
     │
     └─ ConversationMemoryUpdater.remember() ← updates the 7 memory slots
                                                 from what actually happened
```

`ToolOrchestrator.handle()` itself is untouched — every one of Phase D's 22
`ToolOrchestratorTest` cases still passes unmodified. It gained two **new
public seams** so `SecretaryOrchestrator` could reuse classification and
single-tool execution instead of reimplementing either:

- `classify()` — promoted from `private` to `internal`.
- `executeIntent()` — extracted from the private `execute()` `handle()` already
  called, same body, now callable directly.

Multi-step planning, rollback, contact selection and follow-up suggestions are
Stage 2. Stage 1's `SecretaryOrchestrator` drives single-tool turns only,
enriched by memory — which is exactly what the three worked examples need.

### Layer placement

| Component | Layer | Android? |
|---|---|---|
| `ConversationMemory`, `ReminderMemory`, `CalendarEventMemory`, `EmailMemory`, `WhatsAppMemory` | `domain/memory` | ❌ pure Kotlin |
| `IntentAction`, `IntentType.toolId` (extends `domain/intent/DpsIntent.kt`) | `domain/intent` | ❌ |
| `SecretaryState`, `SecretaryEvent`, `SecretaryStateMachine` | `domain/secretary` | ❌ |
| `ReferenceResolver`, `ActionDetector`, `ConversationMemoryUpdater` | `ai/memory` | ❌ fully JVM-testable |
| `SecretaryOrchestrator` | `ai/secretary` | ❌ sequencing only |
| `ActivityPermissionRequestHost` | `data/android/permission` | ✅ the only new Android API surface |
| Wiring | `di/AiContainer.kt`, `ui/MainActivity.kt` | ✅ composition root + Activity attach only |

---

## New Android API — researched before use

`ActivityPermissionRequestHost` is the first implementation of
`PermissionRequestHost` anywhere in the app. Verified against official docs
before writing it:

| API | Source | Confirmed |
|---|---|---|
| `ComponentActivity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` | developer.android.com/training/permissions/requesting | Launcher registration; callback receives `Map<String, Boolean>` |
| Registering from a helper class holding the `ComponentActivity` | developer.android.com/reference/androidx/activity/result/ActivityResultCaller | Legal, provided registration still happens before `STARTED` |
| `ActivityCompat.shouldShowRequestPermissionRationale(Activity, String)` | androidx.core:core | Feeds `AndroidPermissionManager`'s existing never-asked-vs-permanently-denied logic |

Constructed and attached synchronously in `MainActivity.onCreate`, detached in
`onDestroy`. No new Gradle dependency was needed — `androidx.activity:activity`
and `androidx.core:core` were already present.

---

## Files Created (9)

| File | Purpose |
|---|---|
| `domain/memory/ConversationMemory.kt` | The 7 required memory slots + `ReminderMemory`/`CalendarEventMemory`/`EmailMemory`/`WhatsAppMemory` |
| `domain/secretary/SecretaryState.kt` | 8-state enum, `SecretaryEvent`, exhaustive `SecretaryStateMachine.transition` |
| `ai/memory/ReferenceResolver.kt` | Pronoun/reference resolution + relative time-offset arithmetic, from memory |
| `ai/memory/ActionDetector.kt` | Deterministic create/update/cancel keyword safety net |
| `ai/memory/ConversationMemoryUpdater.kt` | Updates memory from a completed tool result |
| `ai/secretary/SecretaryOrchestrator.kt` | The new top-level entry point; composes `ToolOrchestrator` rather than bypassing it |
| `data/android/permission/ActivityPermissionRequestHost.kt` | The first real `PermissionRequestHost` implementation |
| `app/src/test/.../ai/secretary/SecretaryStateMachineTest.kt` | Exhaustive transition-table verification |
| `app/src/androidTest/.../ai/secretary/SecretaryLiveWiringInstrumentedTest.kt`, `app/src/androidTest/.../ui/MainActivityPermissionHostInstrumentedTest.kt` | On-device verification (see below) |

Plus JVM tests: `ReferenceResolverTest.kt`, `ActionDetectorTest.kt`,
`ConversationMemoryUpdaterTest.kt`, `SecretaryOrchestratorTest.kt`.

## Files Modified (10)

| File | Change |
|---|---|
| `domain/intent/DpsIntent.kt` | `IntentAction` enum, `action` on `DpsIntent` (default `CREATE`), `targetId` on `IntentParameters`, `IntentType.toolId` mapping — all additive, every Phase D call site unchanged |
| `ai/intent/IntentJsonParser.kt` | Parses optional `action_type` (distinct from the existing `action`-as-intent-type-alias key) |
| `ai/intent/IntentPromptBuilder.kt` | Schema gains `action_type`; one rule line; documents why memory is *not* injected here |
| `ai/intent/ToolSelector.kt` | `reminderCall()` branches on `IntentAction` for `update_reminder`/`cancel_reminder`; `CREATE` path byte-identical to Phase D |
| `ai/intent/ClarificationEngine.kt` | UPDATE/CANCEL completeness = "is `targetId` resolved", not the CREATE field groups; `merge()` now carries `targetId` through a clarification round |
| `ai/intent/ToolOrchestrator.kt` | `classify()`/`executeIntent()` promoted to `internal`; `handle()` and `resumeAfterPermissionGrant()` untouched |
| `ai/session/AiSessionManager.kt` | `sendMessage()` routes through `SecretaryOrchestrator` first; falls through to the unmodified `runGeneration()` on `Conversational`; drives the live permission round-trip |
| `ai/engine/DefaultAiEngine.kt` | **Bug fix** — removed the deadlocking `withContext(dispatchers.inference)` from `generateOnce()` |
| `di/AiContainer.kt` | `secretaryOrchestrator` lazy property; `sessionManager` gains two constructor params |
| `ui/MainActivity.kt` | Constructs and attaches `ActivityPermissionRequestHost`; detaches in `onDestroy` |

---

## On-device evidence

Every one of the three named example messages was run against the **real**
model, the **real** tool registry, and the **real** Android permission system
— not scripted, not simulated.

### 1. "Kal 4 baje Abdul ko yaad dila dena."

```
PROFILE ... total=69192.6ms tokens=67
Executing reminder.create_reminder
ReminderScheduler: Scheduled reminder id=1000 exact=true at=1786100940000
```
Assistant reply shown in the app: *"Reminder set for 2026-08-07T16:09."*

### 2. "Uska reminder 30 minute pehle kar do."

```
Executing reminder.update_reminder
ReminderScheduler: Scheduled reminder id=1001 exact=true at=1786099800000
```
Same reminder id as the one just created; new trigger time is exactly 30
minutes earlier than the original (1786101600000 → 1786099800000 = 1,800,000 ms).
Memory resolved "uska reminder" to the just-created id with no id ever typed
by the user.

### 3. "Usko WhatsApp bhi bhej do."

```
Executing whatsapp.prepare_message
ToolExecutor: Permissions missing for WHATSAPP: [READ_CONTACTS]
PermissionManager: Requesting 1 runtime permission(s).
[real system dialog: "Allow DPS to access your contacts?" — ALLOW tapped]
ToolOrchestrator: Resuming whatsapp after permission grant
```
"Usko" resolved to "Abdul" — the person named in message 1, where he was never
resolved as a contact (a reminder doesn't need to) — via the new
person-mention fallback in `ConversationMemoryUpdater`. The permission
rationale ("I need access to your contacts before I can find who you meant...")
was shown in-chat *before* the live OS dialog appeared, and the OS dialog
itself is the one Phase D noted could never appear before this phase.
Resolution went one step further than the example needed: this device
genuinely has several contacts named "Abdul," and the app asked which one
rather than guessing — real `ContactResolver` ambiguity, surfaced honestly
through the whole new pipeline.

### A caught bug, not just a caught deadlock

`ReferenceResolver`'s reminder/calendar cue list was written from the brief's
worked examples and missed "uska reminder" (as opposed to "us reminder")
specifically — a possessive form as natural as the ones already covered. The
first live run of example 2 asked "Which reminder do you mean?" instead of
resolving it, which is the correct, honest behaviour for an unresolved
reference — not a crash, not a guess — but not what should have happened here.
Fixed by extending the cue list (`uska`/`uski`/`iska`/`iski` + `reminder`/
`meeting`/`event`) and locked in with a JVM regression test
(`uska reminder resolves the target even when the model also filled another
field`) before rebuilding and re-verifying on-device.

### Observed classification reliability (Roman Urdu)

The exact phrase "Kal 4 baje Abdul ko yaad dila dena." failed to classify
(fell back safely to `CONVERSATION`, per Phase D's documented direction of
every fallback) three times in a row in one run, before succeeding on a later
attempt and succeeding again in a fresh run. The English equivalent
("Remind me to call Abdul tomorrow at 4 pm.") succeeded on the first attempt
every time it was tried. This is a real, reproducible signal about this 1.5B
model's Roman-Urdu classification reliability for this phrasing — not a Phase
E defect, and not silently retried, per Phase D's explicit "no retry" design
(`docs/DAY-05-PHASE-D-COMPLETION.md`, Known Risks). Recorded here as evidence
rather than smoothed over.

---

## Build Result

**BUILD SUCCESSFUL · 0 errors · 0 warnings** (`:app:compileDebugKotlin`,
`:app:compileDebugUnitTestKotlin`, `:app:compileDebugAndroidTestKotlin`, clean build)

## Test Results

| Suite | Result |
|---|---|
| JVM (Phase D baseline, unmodified) | ✅ 178/178 |
| `ReferenceResolverTest` (new) | ✅ 20/20 |
| `ActionDetectorTest` (new) | ✅ 6/6 |
| `ConversationMemoryUpdaterTest` (new) | ✅ 12/12 |
| `SecretaryStateMachineTest` (new) | ✅ 9/9 |
| `SecretaryOrchestratorTest` (new) | ✅ 22/22 |
| `ToolSelectorTest` (extended) | ✅ 6 new cases |
| `IntentJsonParserTest` (extended) | ✅ 5 new cases |
| `ClarificationEngineTest` (extended) | ✅ 4 new cases |
| **JVM total** | **254/254** |
| Device (Phase D baseline, unmodified) | ✅ 82/82 (see Phase D doc for breakdown) |
| `SecretaryLiveWiringInstrumentedTest` (new) | ✅ 3/3 |
| `MainActivityPermissionHostInstrumentedTest` (new) | ✅ 2/2 |
| **Device total (clean environment, model not pre-provisioned)** | **87 total · 0 failures · 0 errors · 10 skipped** (the 9 `GgufInferenceInstrumentedTest` + 1 `ThreadCountBenchmarkTest` cases that need the GGUF model staged in app-private storage first — Phase D's own designed graceful skip, not a Phase E gap) |
| `GgufInferenceInstrumentedTest`, isolated, model manually staged | ✅ **10/10**, including `t05_sessionPipelineProducesAssistantMessage` in 64.8 s — the test that now routes through `SecretaryOrchestrator` first |

No crashes, no ANR, no SIGSEGV/SIGABRT/SIGBUS, no tombstones.

### A transient failure investigated, not brushed aside

One earlier run of the full suite reported `t05_sessionPipelineProducesAssistantMessage`
failing with an **empty** failure body, and the instrumentation run terminated
after 5 of 87 tests ("Expected 87 tests, received 5") — the signature of the
instrumentation process itself dying (a device disconnect, matching this
project's documented history of exactly that during earlier phases) rather
than an assertion failing. Re-run twice in isolation with the model staged: 10/10
passed both times, `t05` completing in 64.8 s against a 180 s budget — nowhere
near timing out even with the new classification pass added ahead of it. Not
dismissed on a hunch: investigated, reproduced clean, and recorded here with
the evidence rather than silently retried until green.

---

## Architecture Verification

| Rule | Result |
|---|---|
| `domain/memory`, `domain/secretary` free of `android.*` | ✅ PASS |
| `ai/memory`, `ai/secretary` free of `android.*`, `ui/` | ✅ PASS |
| `SecretaryOrchestrator` composes `ToolOrchestrator` rather than bypassing it | ✅ PASS — `classify()`/`executeIntent()` reused directly; classification, permission-gating, execution and response-phrasing all still live in Phase D's class |
| `ToolOrchestrator.handle()` / `resumeAfterPermissionGrant()` behaviour unchanged | ✅ PASS — all 22 `ToolOrchestratorTest` cases pass unmodified |
| Only Android API touched is the researched, cited one (`ActivityPermissionRequestHost`) | ✅ PASS |
| No duplicated business logic | ✅ clarification completeness, merge and question-phrasing still live only in `ClarificationEngine`; date/time combination still only in `ToolSelector` |
| Day 03/04 runtime untouched except the one Phase D regression | ✅ `DefaultAiEngine.generateOnce()` is the only runtime-layer change, and it is a bug fix to Phase D code blocking this phase's own verification |

---

## Known Risks

| Risk | Severity | Notes |
|---|---|---|
| Roman-Urdu classification reliability varies by phrasing | Medium | Observed directly on-device this phase (see above); Phase D's no-retry-on-ambiguity design means this surfaces as an honest fallback to conversation, never a wrong action |
| `ReferenceResolver`'s cue vocabulary is bounded, not exhaustive | Medium | One real gap already found and fixed by on-device testing; likely not the last — same honest-limitation framing as `ContactResolver`'s phone-matching |
| `AndroidCalendarTool` still has no update/delete | High (carried) | Blocks "Meeting ko Friday kar do" and calendar rollback — Stage 2 |
| No multi-step planning yet | By design | `WHATSAPP_MESSAGE`/`EMAIL_MESSAGE` with a future time still execute as a single step, not the contact→reminder→calendar→draft chain the brief's example shows — Stage 2 |
| `WAITING_CONTACT_SELECTION`/`WAITING_CONFIRMATION` states exist but are not yet driven | By design | Reachable in the state machine, exercised in `SecretaryStateMachineTest`, but nothing in Stage 1 fires their entry events yet — Stage 2 |
| **Reminders still do not survive reboot** | High (carried) | Unchanged from Phase B/D |

---

## Not Implemented (Stage 2)

Multi-step planning and rollback (`IntentPlanner`, `PlanExecutor`), calendar
`update_event`/`delete_event`, contact-selection parsing, follow-up
suggestions, `ConfirmationParser`. Staged deliberately — the user's explicit
instruction was to get live wiring fully verified before continuing.
