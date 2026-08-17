# Day 09 — Milestone 2: Agentic Multi-Step Execution

**Project Falcon 🦅 · DPS Android Client**
**Branch:** `day-05-android-tool-foundation`
**Scope:** M2-A (generalized cross-step reminder offset), M2-B (Day 09 Option 1
reachable from multi-step plans), M2-C (`PendingPlan` — multi-step plan
parking and resumption), M2-D (real-device end-to-end validation).

---

## 1. Executive Summary

M2's stated goal was to "transform DPS from a single-step AI secretary into a
reliable agent capable of planning and executing multi-step user requests."
What M2 actually delivered is narrower and more precise than that framing
suggests, because the investigation phase (M2's own first task) found that
most of that capability already existed, shipped and tested, from Day 05
Phase E Stage 2 — over a year of commits before M2 began.

M2 closed three specific, concrete gaps in that existing machinery:

1. **A hardcoded 30-minute cross-step offset** that ignored anything the user
   actually said ("remind me 15 minutes before") — closed by M2-A.
2. **Day 09 Option 1's structural type-disambiguation redirect** (committed
   as M1's final piece) was unreachable from inside a multi-step plan —
   closed by M2-B.
3. **A block mid-plan silently dropped everything after it** — the single
   largest gap, and the one the "M2-C" domain type is named for — closed by
   M2-C.

M2-D then validated all three against real device state — real
`CalendarProvider`, real `ReminderStore`/`AlarmManager`, real
`SharedPreferences`-backed `TaskStore` — rather than trusting the JVM suite's
scripted-model coverage alone.

## 2. Starting Point — What Already Existed Before M2

Confirmed by direct inspection, not assumed, at the start of M2's
investigation phase:

| Component | What it already did |
|---|---|
| `IntentJsonParser.parsePlan()` | Parses `{"steps":[{...},{...}]}` from one classification pass, falling back to a 1-step plan for a bare object. |
| `ToolOrchestrator.classifyPlan()` | One inference call either way — single- or multi-step — token budget doubled to accommodate a plan. |
| `SecretaryOrchestrator.handlePlan()` | Ran steps sequentially, contact grounding, execution and follow-up suggestions all reused from the single-step path. |
| `TemporalStepAttributor` | Per-occurrence temporal-phrase attribution, so one step's hallucinated `raw_when` could never borrow a sibling step's genuine phrase. |
| `anchorToPriorEvent` | The one existing cross-step rule: a bodiless reminder step immediately after a calendar-event step defaulted to a fixed 30 minutes before it. |

M2 therefore closed specific, already-identified architectural gaps in
already-shipped machinery — it did not build multi-step execution from
nothing, and no new plan/agent/workflow abstraction was introduced anywhere
in M2.

## 3. M2-A — Generalized Cross-Step Reminder Offset

**Problem:** "Create a meeting with Ali tomorrow at 3pm and remind me 15
minutes before" silently produced a reminder 30 minutes before instead —
`anchorToPriorEvent`'s lead time was a fixed constant, not read from
anything the user said.

**Fix:** the reminder step's own `raw_when` is checked for a stated offset
*before* `TemporalStepAttributor` runs. This ordering is load-bearing: that
attributor only keeps a `raw_when` when it matches a genuine occurrence
`TemporalPhraseSpanFinder` found, and that finder only recognises phrases
`TemporalPhraseResolver` can resolve — which has no concept of a relative
delta like "15 minutes before" at all. Reading it earlier, from the step's
own pre-attribution text, sidesteps that entirely without touching the
attributor.

**Reuse, not duplication:** the actual extraction reuses
`ReferenceResolver.findRelativeOffsetMillis` — the exact regex and
vocabulary already shipped and tested for single-turn reminder rescheduling
("30 minute pehle kar do") — widened from `private` to `internal` for this
one new call site. Zero behavioural change to `ReferenceResolver` itself.

**Fallback:** unchanged. No stated offset, or one the parser doesn't
recognise, still means exactly the original 30-minutes-before default.

No prompt change. No new inference call.

## 4. M2-B — Option 1 Reachable from Multi-Step Plans

**Problem:** Day 09 Option 1 (M1's final commit, `d6de0c1`) already
redirected a structurally suspicious classification — a type with no
grounding, or a targetless "which one?" — toward a real remembered
candidate. But `disambiguationCandidates`/`askTypeDisambiguation` were only
ever called from `handleSingleStep`. A plan step hitting the exact same
condition still got the old generic clarification question.

**Fix:** a second call site for the *identical, unmodified* mechanism,
placed at the same point in `handlePlan`'s loop that `handleSingleStep`
already uses it — after `clarification.check()`, before its verdict is
acted on.

**Bug discovered and fixed:** building the required test coverage exposed
a real, exploitable defect: if an *earlier* step in the same plan had
already triggered its own follow-up suggestion (e.g. a calendar-event
create asks "would you like a reminder 30 minutes before?"), that left a
stale `pendingConfirmation` behind. `handle()`'s dispatch checks
`pendingConfirmation` before checking whether an Option 1 redirect is even
pending, so the user's answer to the redirect question would have been
silently misread as answering the abandoned suggestion instead. Fixed by
clearing `pendingConfirmation` at the new call site before setting
`pendingTypeDisambiguation`.

Tests: 4 new JVM, 1 new instrumented, both fully passing.

## 5. M2-C — `PendingPlan`: Parking and Resumption

**Problem:** the single largest gap. A block mid-plan (clarification,
confirmation, contact selection, or an Option 1 redirect) resolved only the
*one* blocked step — everything scheduled after it was silently dropped,
by design, since Stage 2. This was explicitly named and accepted as a
deferred limitation in the original Stage 2 completion report.

**`PendingPlan`** (`domain/secretary/PendingPlan.kt`), mirroring the shape
and freshness convention of the three existing `Pending*` types:

```kotlin
data class PendingPlan(
    val remainingSteps: List<DpsIntent>,
    val remainingOffsets: List<Long?>,
    val completedReplies: List<String>,
    val lastEventStartMillis: Long?,
    val requestedAtMillis: Long,
)
```

`remainingOffsets` is the one field beyond the originally suggested shape —
a genuine correctness requirement discovered while implementing, not scope
creep: without it, M2-A's own offset feature would silently regress across
any park/resume boundary, for the identical pre-attribution-stripping
reason M2-A itself had to work around.

**Architecture:** `handlePlan`'s loop was extracted into a shared
`continuePlan` function, driven either fresh (from `handlePlan`) or resumed
(from `continueAfterResumedStep`, called by all four existing resume
paths). A block calls `parkRemainder`, storing everything *after* the
current step — never the blocked step itself, which already lives inside
whichever of the four existing `pending*` fields is set alongside it.
Full natural completion clears `pendingPlan`.

**Re-blocking:** if the resumed remainder blocks again, `parkRemainder`
simply overwrites `pendingPlan` with a fresh value describing the new,
smaller remainder — never appending to or merging with the old one.

**Abandonment:** a message that turns out *not* to answer the pending
question — a genuinely different intent type (Track A's own existing
"fresh request" rule), an unclear confirmation, a declined or unparseable
type-disambiguation answer, or any of the four resume paths finding its own
pending state stale — explicitly clears `pendingPlan` too, so a stale
remainder can never execute against an unrelated later message.

**A second stale-`pendingConfirmation` bug, same class as M2-B's:** the
same contamination pattern existed for the `Missing`-clarification branch
too (a dangling suggestion from an earlier step could misroute the answer
to a *later* step's own missing-field question). Found and fixed the same
way, plus a defensive clear added to the contact-ambiguous branch (not
currently reachable as a live bug, given dispatch order, but closed for
robustness).

**Explicitly out of scope, documented rather than silently expanded:**
a step blocked on an Android/tool *permission* is not parked. Resuming it
goes through `onPermissionResult()` — a system callback carrying no user
message at all, with its own pending state living inside `ToolOrchestrator`,
not `SecretaryOrchestrator`. Reaching that case would mean extending a
second class's private state; left for a future, separately-scoped pass.

**Declining a destructive step mid-plan stops the remainder**, deliberately
— treated the same as a genuine failure (no retry, no rollback), not as
consent to keep running unattended.

Tests: 7 new JVM (clarification, confirmation, declining, contact
selection, type disambiguation with its own re-block, double re-blocking,
and full-completion state clearing), 2 new instrumented, all passing.

## 6. Real Device Validation (M2-D)

| Scenario | Result | Verification |
|---|---|---|
| Event + 30-min default reminder | **PASS** | `CalendarProvider` + `ReminderStore`, exact `eventStart − 30min` timestamp |
| Event + 15-min stated-offset reminder | **PASS** (pre-existing M2-A test, re-run) | exact `eventStart − 15min` timestamp, real device |
| 3-step mid-plan clarification + resumption | **PASS** (pre-existing M2-C test, re-run) | real `TaskStore`, step 3 confirmed present only after step 2 resolves |
| Re-blocking (two different reasons, one plan) | **PASS** | real `TaskStore`, no duplicate or skipped step across two resumes |
| Multi-step Option 1 redirect with remainder preserved | **PASS** | real `CalendarProvider` event + real `TaskStore` step 3, redirect → re-block into delete confirmation → step 3 |

Objectives B and C from the M2-D brief were already proven precisely by
existing M2-A/M2-C instrumented tests; per the brief's own "use existing
infrastructure first" instruction, these were re-run as part of the full
suite rather than duplicated. Three new instrumented tests closed the
genuine gaps: the 30-minute default had never been checked against real
device state (only the explicit-offset case had), re-blocking had never run
on-device, and the Option 1 redirect had never been exercised with a plan
remainder still pending on-device.

All new real-device tests deliberately use `IntentType.TASK` where the
scenario allows it — the one type in this suite whose real tool
(`SharedPreferences`-backed) needs no runtime permission — so they are not
subject to calendar/reminder permission flakiness on a fresh run.

**One incidental finding during validation, unrelated to M2:** an
`am instrument` command-syntax mistake caused an accidental full-suite run
(151 tests, including the ~25-minute throwaway `CalendarClassificationInvestigationTest`
live-model harness). This created no M2 defect — it is a pre-existing,
already-documented-as-throwaway harness that was simply never designed to
clean up after itself — but it left calendar and reminder test artifacts on
the device, cleaned up directly (see Section 10). A second, genuinely
pre-existing, unrelated M1-era test (`rescheduleFollowUpResolvesAgainstTheRealReminderJustCreated`)
also has no cleanup by original design; its one leftover reminder was
likewise cleaned up manually.

## 7. Test Results

```
JVM:
  baseline (post-M1):        522
  M2-A:                      +5   → 527/527
  M2-B:                      +4   → 531/531
  M2-C:                      +7   → 538/538
  M2-D:                      +0   → 538/538  (real-device-only stage)
  final:                     538/538, 0 failures

Instrumented (SecretaryLiveWiringInstrumentedTest):
  baseline (post-M1):        9
  M2-A:                      +1   → 10/10
  M2-B:                      +1   → 11/11
  M2-C:                      +2   → 13/13
  M2-D:                      +3   → 16/16
  final:                     16/16, 0 failures, real device VNEW1535091002114
```

## 8. Files Changed (Complete, All of M2)

- `android/app/src/main/java/com/softwaremine/dps/ai/secretary/SecretaryOrchestrator.kt` — M2-A offset threading, M2-B redirect call site, M2-C `PendingPlan`/`continuePlan`/`parkRemainder`/`continueAfterResumedStep` and wiring into all four resume paths, two stale-`pendingConfirmation` fixes.
- `android/app/src/main/java/com/softwaremine/dps/ai/memory/ReferenceResolver.kt` — one method, `private` → `internal` (M2-A reuse). No behavioural change.
- `android/app/src/main/java/com/softwaremine/dps/domain/secretary/PendingPlan.kt` — new (M2-C).
- `android/app/src/test/java/com/softwaremine/dps/ai/secretary/SecretaryOrchestratorTest.kt` — 16 new JVM tests across M2-A/B/C.
- `android/app/src/androidTest/java/com/softwaremine/dps/ai/secretary/SecretaryLiveWiringInstrumentedTest.kt` — 7 new instrumented tests across M2-A/B/C/D.

Not touched by any M2 stage: `IntentPromptBuilder.kt`, `ToolSelector.kt`,
`TemporalPhraseResolver.kt`, `ActionDetector.kt`, `ClarificationEngine.kt`,
`SecretaryState.kt`, Track A's merge-gating logic, Day 09 Option 1's own
`disambiguationCandidates`/`askTypeDisambiguation`/`resolveTypeDisambiguation`
logic (only a second call site was added to the first, in M2-B).

## 9. Known Limitations / Deferred Work

- **Permission-blocked plan continuation is not implemented.** A plan step
  blocked on an Android/tool permission still silently drops its remainder —
  unchanged from before M2, not newly broken by it. `onPermissionResult()`'s
  pending state lives inside `ToolOrchestrator`, a separate class M2
  deliberately did not extend.
- **`pendingClarification` has no independent freshness check**, unlike its
  three siblings (`PendingConfirmation`, `PendingContactSelection`,
  `PendingTypeDisambiguation`, and now `PendingPlan`, all of which check a
  5-minute window). Discovered during M2-C; not invented a policy for
  during M2, since `PendingPlan` is always set and consumed in lockstep with
  whichever field's own resume path already decides freshness — inventing
  an independent check for `PendingPlan` alone would create a new
  inconsistency (the step resumes, the remainder mysteriously does not),
  not a safer one.
- **Model classification reliability** (whether `classifyPlan` reliably
  splits an arbitrary compound message into the right number of steps) was
  not re-investigated in M2 — that is M1 territory, already closed, and
  M2-D's brief explicitly excluded launching a new live-model experiment.
- **`TemporalPhraseResolver`'s vague-time-of-day vocabulary** (e.g. "this
  evening" with no explicit hour) is unchanged and was not expanded.
- **CAT5's "move that meeting to tomorrow evening" bare-action-verb gap**
  is a Milestone 1 finding (`ActionDetector`), already closed separately in
  commit `d0c569c` — unrelated to and untouched by M2.
- **No rollback, no retry** — a deliberate, unchanged policy throughout M2.
  Partial success is reported honestly; a failed or declined step never
  silently undoes an earlier successful one.

## 10. Definition of Done

| Requirement | Status |
|---|---|
| M2-A: generalized cross-step offset | **COMPLETE** |
| M2-B: Option 1 reachable from plans | **COMPLETE** |
| M2-C: `PendingPlan` parking/resumption | **COMPLETE** |
| M2-D: real-device E2E validation | **COMPLETE** |
| Permission-blocked continuation | **DEFERRED** (documented, unchanged from pre-M2) |
| Combined (single-question) plan confirmation | **DEFERRED** (design report's own recommendation: per-step confirmation is safer, not merely simpler) |
| `pendingClarification` freshness policy | **DEFERRED** (documented finding, no policy invented) |

**M2 overall: COMPLETE WITH DOCUMENTED LIMITATIONS.**
