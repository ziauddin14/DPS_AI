# Day 05 Phase D — AI Tool Calling & Intent Orchestration

**Project Falcon 🦅 · DPS Android Client**
**Date:** 2026-08-06
**Branch:** `day-05-android-tool-foundation`
**Scope:** Intent classification, parameter extraction, tool selection, clarification,
permission recovery, failure recovery, natural response generation

---

## The central design decision

**One inference pass per user message. The reply is templated, never generated.**

```
user text
  → IntentPromptBuilder        build a compact classification prompt
  → AiEngine                   one inference pass
  → IntentJsonParser           tolerant structure recovery
  → ClarificationEngine        complete? or what should we ask?
  → ToolSelector                intent → ToolCall
  → ToolExecutor                permission gate, timeout, dispatch  (Phase A)
  → ToolResponseGenerator       ToolResult → a sentence, templated
```

Two facts from Day 04's profiling drove this shape, and both still hold on this
hardware:

- **Prompt ingestion is 76% of inference cost**, and it scales with length —
  so the classification prompt names fields, not worked examples, and stays
  under 800 characters (`IntentPromptBuilderTest`).
- **A generation costs 5–15 s.** A second pass to phrase the reply would
  roughly double the latency of every action, for a benefit — nicer prose —
  the user does not need.

The second reason is the one that actually decided it. A model asked to
narrate a `ToolResult` will eventually narrate it wrongly, and the specific
failure that matters is a model writing *"I've sent your message to Abdul"*
when Phase C guarantees nothing was sent. Templating removes that possibility
structurally, the same way Phase C removed the sending capability itself.

---

## Architecture

```
User message
     │
     ▼
IntentPromptBuilder ── injects Now, offers every IntentType.wireName,
     │                  names all 9 IntentField values, asks for JSON only
     ▼
AiEngine.generateOnce() ── ModelConfig.DETERMINISTIC, capped at 160 tokens
     │
     ▼
IntentJsonParser ── brace-counts past prose/fences, coerces unquoted values,
     │               field aliases (person/contact/name, message/body/text,
     │               title/subject/summary), unparseable → CONVERSATION
     ▼
ClarificationEngine.check() ── IntentType.requiredFields, any-group-complete
     │
     ├─ Missing ──► question ──► held as pendingClarification, ask the user
     │
     └─ Complete
          │
          ▼
     ToolSelector.select() ── intent → ToolCall (tool id, operation, arguments)
          │                    date+time → epoch millis; time-only → next
          │                    occurrence; date-only → 09:00; relative
          │                    expressions are the model's job, never guessed
          ▼
     ToolExecutor.execute() ── Phase A/B/C tool layer, unchanged
          │
          ├─ PermissionRequired ──► held as pendingPermission, ask to allow
          │
          └─ any other ToolResult
                  │
                  ▼
             ToolResponseGenerator.describe() ── templated sentence,
                                                   never surfaces
                                                   ToolResult.Error.cause
```

`ToolOrchestrator` is sequencing only. It parses nothing, matches nothing, and
phrases nothing — each lives in a pure class, testable without a model.

### Layer placement

| Component | Layer | Android? |
|---|---|---|
| `IntentType`, `IntentParameters`, `IntentField`, `DpsIntent`, `requiredFields` | `domain/intent` | ❌ pure Kotlin |
| `IntentResolution`, `PendingPermissionAction` | `domain/intent` | ❌ |
| `IntentJsonParser`, `ToolSelector`, `ClarificationEngine`, `ToolResponseGenerator`, `IntentPromptBuilder` | `ai/intent` | ❌ **fully JVM-testable, no model needed** |
| `ToolOrchestrator` | `ai/intent` | ❌ sequencing only, no `android.*` |
| Wiring | `di/AiContainer.kt` | ✅ composition root only |

No new Android APIs were used. Phase D is orchestration over the tool layer
Phases A–C already built; every platform capability it exercises was verified
then.

---

## Files Created (7)

| File | Purpose |
|---|---|
| `domain/intent/DpsIntent.kt` | `IntentType`, `IntentParameters`, `IntentField`, `DpsIntent`, `IntentType.requiredFields` |
| `domain/intent/IntentResolution.kt` | `IntentResolution` sealed outcome, `PendingPermissionAction` with a 5-minute freshness window |
| `ai/intent/IntentJsonParser.kt` | Tolerant structure recovery from raw model output |
| `ai/intent/ToolSelector.kt` | Intent → `ToolCall`, date/time combination rules |
| `ai/intent/ClarificationEngine.kt` | Completeness check, question phrasing, answer merging |
| `ai/intent/ToolResponseGenerator.kt` | `ToolResult` → templated sentence |
| `ai/intent/IntentPromptBuilder.kt` | Classification prompt, grounded in the current date/time |
| `ai/intent/ToolOrchestrator.kt` | Sequences the pipeline; owns `pendingClarification` and `pendingPermission` |

**Tests:** `IntentJsonParserTest.kt`, `ToolSelectorTest.kt`, `ClarificationEngineTest.kt`,
`ToolResponseGeneratorTest.kt`, `IntentPromptBuilderTest.kt`, `ToolOrchestratorTest.kt` (JVM);
`IntentOrchestrationInstrumentedTest.kt` (device).

## Files Modified (1)

- `di/AiContainer.kt` — added the `toolOrchestrator` lazy property and its six imports; wiring only

---

## Why no new Android documentation review was needed

The brief asked that official documentation be read before any new platform
API is used. None was: Phase D calls `AiEngine.generateOnce()` (Day 03/04) and
`ToolExecutor.execute()` (Phase A) exactly as already verified, and everything
new in this phase — JSON recovery, date arithmetic, prompt text — is pure
Kotlin and `java.time`, already available at `minSdk 26`.

---

## Two design decisions worth recording

### Clarification is a first-class outcome, derived from data

`IntentType.requiredFields` expresses completeness as `List<Set<IntentField>>`
— alternative groups, any one of which satisfies the intent — rather than as a
hand-written `when`. "Message Abdul" and "message +923001234567" are both
complete, by different routes, and `ClarificationEngine` picks the group
**closest to complete** to ask about: a reminder with only a title is asked
"When?", not "What, and when?"

Deriving the question from the same data that decides completeness means a new
intent gets sensible clarification for free, and the two can never drift
apart. `ClarificationEngineTest` sweeps every `(IntentType, IntentField)`
combination and asserts the resulting question is non-empty, ends in `?`, and
never contains an internal term — `field`, `parameter`, `null`, `intent`,
`tool`, `json` — rather than checking the handful of phrasings anyone thought
to write by hand.

### Permission recovery resumes the call, not the sentence

`PendingPermissionAction` stores the resolved `ToolCall`, not the user's
original text. Re-running the sentence through the model after a permission
grant would cost a second inference pass and risk the model interpreting the
same words differently the second time. Storing the call makes resumption
immediate — `ToolOrchestratorTest` confirms `resumeAfterPermissionGrant()`
spends **zero** additional inference passes — and guarantees the action taken
is the one the user was actually told about.

The 5-minute freshness window (`PendingPermissionAction.isFresh`) exists
because a permission granted long after the request no longer implies the user
still wants that specific action; they may have opened Settings for an
unrelated reason. `ToolOrchestratorTest` verifies a stale held action is
discarded rather than performed.

---

## Build Result

**BUILD SUCCESSFUL · 0 errors · 0 warnings**

## Test Results

| Suite | Result |
|---|---|
| `IntentJsonParserTest` (JVM) | ✅ 24/24 |
| `ToolSelectorTest` (JVM) | ✅ 22/22 |
| `ClarificationEngineTest` (JVM) | ✅ 17/17 |
| `ToolResponseGeneratorTest` (JVM) | ✅ 13/13 |
| `IntentPromptBuilderTest` (JVM) | ✅ 11/11 |
| `ToolOrchestratorTest` (JVM) | ✅ 22/22 |
| `ContactResolverTest` (JVM) | ✅ 17/17 |
| `IntentBuilderLogicTest` (JVM) | ✅ 6/6 |
| `ToolArgumentsTest` (JVM) | ✅ 16/16 |
| `ToolFoundationTest` (JVM) | ✅ 22/22 |
| `ChatTemplateTest` (JVM) | ✅ 8/8 |
| **JVM total** | **178** |
| `IntentOrchestrationInstrumentedTest` (Phase D) | ✅ **9/9** |
| `CommunicationToolsInstrumentedTest` (Phase C) | ✅ **21/21** |
| `AndroidToolsInstrumentedTest` (Phase B) | ✅ **18/18** |
| `PermissionFoundationInstrumentedTest` (Phase A) | ✅ **14/14** |
| `LlamaCppBridgeInstrumentedTest` (Day 03) | ✅ **9/9** |
| `GgufInferenceInstrumentedTest` (Day 04) | ✅ **10/10** |
| `ThreadCountBenchmarkTest` (Day 04) | ✅ **1/1** |
| **Device total** | **82** |
| **Grand total** | **260** |

No crashes, no `SIGSEGV`/`SIGABRT`/`SIGBUS`, no ANR, no tombstones.

### Why the model is scripted in every JVM and instrumented test

`ToolOrchestrator`'s job is sequencing; every branch it takes is a consequence
of what the model said. A scripted `AiEngine` reproduces exact model output —
a truncated reply, a refusal, an intent with a field missing — deliberately
and repeatably. A real 1.5B model produces those cases eventually and never on
demand, and would make the suite slow (5–15 s per pass) and flaky while
testing the model rather than the wiring. Real inference is exercised
separately by `GgufInferenceInstrumentedTest`.

### What only the device test could catch

`IntentOrchestrationInstrumentedTest` runs the same scripted-model approach
but against the **real** `AiContainer` graph — the actual `DefaultToolRegistry`
and `DefaultToolExecutor`, not stubs. Its purpose is a specific silent-failure
mode the JVM suite cannot see: `ToolSelector` naming a tool id or operation
that the real registry does not accept. A renamed operation in, say,
`AndroidCalendarTool` would leave every JVM test green while every calendar
request on the handset resolved to `Unsupported`.

`everyRoutableIntentReachesARegisteredToolAndOperation` closes that gap by
asserting, for every routable `IntentType`, that `ToolSelector.select()`
produces a call the live registry recognises and the target tool actually
declares. `noRoutedOperationSends` re-checks Phase C's guarantee — no selected
operation contains `"send"` — against the shipping registry rather than a stub
one.

### On-device evidence

The permission-recovery test exercised the real gate, not a simulated one —
this device genuinely denies `READ_CONTACTS`:

```
DPS/ToolExecutor: Permissions missing for CONTACTS: [READ_CONTACTS]
```

`aContactRequestReachesTheRealPermissionGate` and
`aPermissionBlockedActionIsHeldAndResumable` both observed
`ToolOrchestrator.Outcome.NeedsPermission` from this real denial, confirming
the orchestrator's permission path runs against actual platform state, not
just against `FakePermissions` on the JVM.

---

## Architecture Verification

| Rule | Result |
|---|---|
| `domain/intent` free of `android.*` | ✅ PASS |
| `ai/intent` free of `android.*`, `ui/` | ✅ PASS |
| `ToolOrchestrator` touches only `AiEngine`, `ToolExecutor`, `ToolRegistry` and pure intent classes | ✅ PASS |
| No duplicated business logic | ✅ date/time combination lives only in `ToolSelector`; permission phrasing only in `ToolResponseGenerator` |
| No modification to Day 03/04 runtime or Phase A/B/C tools | ✅ `ToolExecutor`, `ToolRegistry` and every tool are unchanged; only `AiContainer` wiring was touched |

---

## Known Risks

| Risk | Severity | Notes |
|---|---|---|
| Classification is a single 1.5B pass with no retry | Medium | An ambiguous or malformed reply falls back to `CONVERSATION` rather than to a guessed action — the safe direction, but the user must re-ask |
| `confidence` is parsed but unused | Low | Deliberate (Day 03/04 KPI gap: a 1.5B model's self-reported confidence is not reliable enough to gate an irreversible action on); logged for future evidence, not enforced |
| Relative date/time expressions depend entirely on the model | Medium | `ToolSelector` intentionally does not parse "tomorrow" itself; a misclassified relative date surfaces as a wrong reminder time rather than a rejected request |
| **Reminders still do not survive reboot** | High | Carried from Phase B — unchanged, still open |
| `PendingClarification`/`PendingPermissionAction` are single-slot, process-scoped | Low | A second request while one is pending replaces it; deliberate — a queue would let DPS act on something the user has since forgotten |

---

## Not Implemented

Multi-turn clarification beyond one follow-up round; the KPI-driven model swap
recommended in Day 04 (Qwen2.5-0.5B) remains a pending Product Owner decision
and Phase D's one-inference-pass design carries the same 5–15 s-per-action cost
either way.
