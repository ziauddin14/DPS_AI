# Day 17 — M8 Implementation Plan: Risk, Confirmation & Controlled Execution

**Status: PLAN ONLY. No source, test, configuration, dependency, or schema file
was modified to produce this document.** Every architectural claim below is
cited against the repository as it stands on commit `9dff80d` plus the
current uncommitted working tree (verified at the time of writing — see
§2).

---

## 1. Executive Summary

M8 generalizes a confirmation mechanism that **already exists and already
works** for two narrow cases (deleting a calendar event/task/reminder;
placing a phone call) into a deterministic, two-state (`SAFE_AUTO` /
`CONFIRM_REQUIRED`) risk policy covering the full action surface, and closes
one real gap (`forget_fact` currently runs with no confirmation at all,
despite being destructive and irreversible).

The investigation phase's central finding holds up under re-verification
(§2): `PendingConfirmation`, `ConfirmationParser`, `SecretaryState
.WAITING_CONFIRMATION`, and M5-B's persisted, process-death-safe recovery of
a pending confirmation are all real, tested, and unmodified. M8's actual new
work is smaller than a green-field reading of the roadmap would suggest:

1. A new pure `RiskPolicy` object (domain layer) replacing two hardcoded
   `Set<IntentType>` constants in `SecretaryOrchestrator.kt`.
2. A minimal, additive permission-precheck seam inserted at the one existing
   choke point (`proceedToExecution`) so risk → permission → confirmation
   runs in that order, per the locked contract's decision 6 — requiring two
   new constructor dependencies on `SecretaryOrchestrator` (`PermissionManager`,
   `ToolRegistry`), both already-existing interfaces with zero new
   production classes.
3. Extending `DELETE_CONFIRMATION_TYPES`-equivalent coverage to
   `FORGET_FACT`.
4. One genuinely new, small piece of state — `pendingIntentAwaitingActionPermission`
   — modeled exactly on the existing `pendingIntentAwaitingContactPermission`
   field, needed to correctly sequence "permission missing for a
   confirm-required action" without collapsing into "permission granted,
   auto-execute, skip confirmation" (a real correctness trap explained in
   §12).
5. A decision, not an implementation: whether to add checkpoint-style replay
   protection for delete/cancel operations (§8 concludes: not necessary,
   evidence-based).

No new store, no new state machine, no new Room migration, no new Gradle
dependency, no new Android permission, and no manifest change are required.

## 2. Verified Repository Baseline

Re-verified directly, not assumed from the prior investigation report:

```
git log --oneline -5   (android/, independent repo)
9dff80d M7 Investigation and some Impleemntation are completed
8e80755 Added Long Term memory M6 Complete
b7be002 DPS's brain foundation is complete
d854712 M1, M2, M3 M4 almost done
b3c7085 M3 Persstent memory stage compleet
```

`git status --porcelain=v1 -uall` is byte-identical to the state recorded at
the end of the M8 investigation phase: 16 modified files (all M7 artifacts)
plus 1 untracked file (`ExecutionVerifierProcessDeathInstrumentedTest.kt`).
**No code changed between the investigation and this plan.** Every
file:line citation below was re-read from the live file during this
planning pass, not carried over from memory.

Every load-bearing claim from the investigation report was independently
re-confirmed during this pass, with two refinements now added (not present
in the investigation report, discovered during this deeper pass):

- `AndroidReminderTool.requiredPermissions` is `{POST_NOTIFICATIONS}`
  (`AndroidReminderTool.kt:78-79`), not empty — the investigation report did
  not enumerate this. `AndroidTaskTool.requiredPermissions` is confirmed
  empty (`AndroidTaskTool.kt:66`).
- `IntentType.toolId` (`DpsIntent.kt:498-516`) is a pure, total,
  already-existing `IntentType → ToolId?` mapping, already imported into
  `SecretaryOrchestrator.kt:32` and used at `:1929`. This is the missing
  piece that makes a minimal permission-precheck possible without touching
  `ToolOrchestrator`/`ToolSelector` — not identified as a specific
  mechanism in the investigation report, which only established that no
  precheck existed at all.

## 3. Current Architecture

Unchanged from the investigation report; the load-bearing facts, re-cited:

- **Existing confirmation gate**: `proceedToExecution()`
  (`SecretaryOrchestrator.kt:1095-1128`) checks `DELETE_CONFIRMATION_TYPES`
  (`:2040`, `{CALENDAR_EVENT, TASK, REMINDER}`, gated on
  `IntentAction.CANCEL`) and `CALL_CONFIRMATION_TYPES` (`:2032`,
  `{CALL_CONTACT}`) before ever calling `toolOrchestrator.executeIntent()`.
  This is the **one and only** seam every risk/confirmation decision must
  run through — both `handleSingleStep` (`:493`) and `continuePlan`
  (`:784`) call `proceedToExecution` exclusively; there is no second path
  into tool execution.
- **`PendingConfirmation`** (`domain/secretary/PendingConfirmation.kt`) —
  `{intent, requestedAtMillis}`, 5-minute freshness, single in-memory slot
  (`SecretaryOrchestrator.kt:144`).
- **`ConfirmationParser`** (`ai/plan/ConfirmationParser.kt`) — pure,
  bounded-vocabulary yes/no/unclear, no model call.
- **Persistence**: `PersistedPendingState.Confirmation`
  (`ExecutionRecoveryState.kt:118-124`) mirrors `PendingConfirmation`
  field-for-field; `restorePendingState`/`syncRecoveryPersistence`
  (`SecretaryOrchestrator.kt:1800-1912`) already round-trip it through
  process death with zero auto-execution (`ExecutionRecoveryState.kt:58-62`).
- **Permission checking**: exclusively inside `DefaultToolExecutor.execute()`
  (`ai/tool/DefaultToolExecutor.kt:66-104`), which runs only when
  `toolOrchestrator.executeIntent()` is reached — i.e., strictly **after**
  today's confirmation gate. `PermissionManager.missing()`
  (`domain/permission/PermissionManager.kt:50`) is a pure, synchronous query
  over already-known state (never itself triggers a request).
- **Multi-step**: `continuePlan()` (`:702-849`) treats any `Outcome.Clarify`
  (confirmation or contact-ambiguity) uniformly via `parkRemainder`
  (`:858-877`); a decline does not resume the remainder
  (`continueAfterResumedStep`, `:886-891` doc); a **post-hoc** permission
  block mid-plan already drops the remainder today, undocumented as a
  limitation, documented as current behavior (`:830-841`).
- **M7**: `ExecutionVerifier`/`VerificationOutcome` unchanged, confirmed by
  this pass to still cover only `create_task`/`create_event` `Success`
  (`recordOutcome`, `:1950-1953`).

## 4. Locked M8 Contract

Restated verbatim from the master prompt, for traceability — not
reopened anywhere in this plan:

1. Risk vocabulary: `SAFE_AUTO` / `CONFIRM_REQUIRED` only, deterministic, no LLM.
2. `FORGET_FACT` is `CONFIRM_REQUIRED`.
3. WhatsApp composer flow is `SAFE_AUTO`; no redundant confirmation added.
4. Email composer flow is `SAFE_AUTO`; no redundant confirmation added.
5. Phone confirmation is preserved unchanged.
6. Order is RISK → PERMISSION PRECHECK → CONFIRMATION → EXECUTION.
7. Multi-step + missing permission: stop the plan safely, no auto-retry/replan.
8. Reuse `PendingConfirmation`/`WAITING_CONFIRMATION`/existing persistence;
   no second confirmation system; no permanent authorization.
9. Confirmation must survive genuine process death, proven on a real device.
10. Destructive replay: investigate first, build only the smallest
    mechanism evidence actually requires.
11. LLM boundary: classification only, never a second call for risk.
12. Memory never grants authorization.
13. M7 is frozen.

Every one of these is technically implementable against the current
architecture (see §§5–11 and §12's discussion of decision 6's real
complexity). **No conflict was found that requires reopening a locked
decision.**

## 5. Risk Policy Matrix

Every action in the current registry (`AndroidToolCatalog`/`AiContainer.kt:386-412`),
verified against source, not invented:

| ToolId.operation | Actual side effect | Modifies persistent/external state? | Confirmation today? | M8 risk | Why | Permission involved? | M7 verification? | Argument-level rule? |
|---|---|---|---|---|---|---|---|---|
| CALENDAR.create_event | Calendar Provider row inserted | Yes | No | SAFE_AUTO | Unchanged; M7 catches drift post-hoc | WRITE_CALENDAR | Yes (Verified/Mismatch/NotFound/ObservationFailed) | None |
| CALENDAR.update_event | Row mutated | Yes | No | SAFE_AUTO | No evidence motivates change; out of M7 scope already | WRITE_CALENDAR | No | None |
| CALENDAR.delete_event | Row deleted | Yes, irreversible | **Yes** (`DELETE_CONFIRMATION_TYPES`) | CONFIRM_REQUIRED | Unchanged — already correct | WRITE_CALENDAR | No | Gated on `IntentAction.CANCEL` only (unchanged) |
| CALENDAR.list_events | Read-only | No | N/A | SAFE_AUTO | — | WRITE_CALENDAR (declared tool-wide) | N/A | None |
| TASK.create_task | Store row written | Yes | No | SAFE_AUTO | Unchanged; M7 covers it | none | Yes | None |
| TASK.update_task | Row mutated | Yes | No | SAFE_AUTO | No evidence motivates change | none | No | None |
| TASK.complete_task | Status flag flipped | Yes, but non-destructive (data intact) | No | SAFE_AUTO | Reversible in spirit; no evidence of harm | none | No | None |
| TASK.cancel_task | Row deleted | Yes, irreversible | **Yes** (`DELETE_CONFIRMATION_TYPES`) | CONFIRM_REQUIRED | Unchanged — already correct | none | No | Gated on `IntentAction.CANCEL` |
| TASK.list_tasks | Read-only | No | N/A | SAFE_AUTO | — | none | N/A | None |
| REMINDER.create_reminder | Store row + `AlarmManager` schedule | Yes | No | SAFE_AUTO | Unchanged | POST_NOTIFICATIONS | No | None |
| REMINDER.update_reminder | Row + alarm mutated | Yes | No | SAFE_AUTO | No evidence motivates change | POST_NOTIFICATIONS | No | None |
| REMINDER.cancel_reminder | Row + alarm removed | Yes, irreversible | **Yes** (`DELETE_CONFIRMATION_TYPES`) | CONFIRM_REQUIRED | Unchanged — already correct | POST_NOTIFICATIONS | No | Gated on `IntentAction.CANCEL` |
| REMINDER.list_reminders | Read-only | No | N/A | SAFE_AUTO | — | POST_NOTIFICATIONS (declared tool-wide) | N/A | None |
| NOTIFICATION.notify | Notification posted | Yes, reversible (`cancel`) | No | SAFE_AUTO | No evidence motivates change | POST_NOTIFICATIONS | No | None |
| NOTIFICATION.cancel | Notification dismissed | Yes, benign | No | SAFE_AUTO | — | POST_NOTIFICATIONS | No | None |
| CONTACTS.find_contact / list_contacts | Read-only | No | N/A | SAFE_AUTO | — | READ_CONTACTS | N/A | None |
| PHONE.place_call | Dialer opened, **never dials** (`ACTION_DIAL` only, `AndroidCallTool.kt:15,29`) | No real call placed | **Yes** (`CALL_CONFIRMATION_TYPES`) | CONFIRM_REQUIRED | **Locked decision 5 — preserved as-is**, despite this investigation's own §4 finding that the tool itself is already a safety boundary; not reopened | READ_CONTACTS | No | None |
| WHATSAPP.prepare_message / send_message | WhatsApp composer opened, **never sends** (`PrepareWhatsAppMessageTool.kt:16,121-137`) | No message sent by DPS | No | SAFE_AUTO | **Locked decision 3** | READ_CONTACTS | No | None |
| GMAIL.compose_email / send_email | Email composer opened, **never sends** (`PrepareEmailTool.kt:15,111-123`) | No email sent by DPS | No | SAFE_AUTO | **Locked decision 4** | READ_CONTACTS | No | None |
| MEMORY.remember_fact | Room row inserted | Yes, reversible (`forget_fact`) | No | SAFE_AUTO | No evidence motivates change | none | No | None |
| MEMORY.recall_fact | Read-only | No | N/A | SAFE_AUTO | — | none | N/A | None |
| MEMORY.forget_fact | Room row permanently deleted, no soft-delete (`AndroidMemoryTool.kt:137`) | Yes, irreversible | **No — the gap** | **CONFIRM_REQUIRED** | **Locked decision 2** | none | No | None — always confirm-required, no action-verb split needed (there is only one `forget_fact` operation, unlike CANCEL vs CREATE for delete-types) |
| WORK_LOG / MEETING_NOTE / ACTION_ITEM.create / REPORT.daily,weekly | Internal record row, or read-only aggregation | Yes (create) / No (report) | No | SAFE_AUTO | No external visibility, no evidence of harm | none | No | None |
| ACTION_ITEM.complete | Status flag flipped | Yes, non-destructive | No | SAFE_AUTO | Same reasoning as `complete_task` | none | No | None |

**Net change to the risk surface**: exactly one addition — `FORGET_FACT`
moves from implicit SAFE_AUTO to explicit CONFIRM_REQUIRED. Every other row
matches current behavior; the `RiskPolicy` object formalizes what
`DELETE_CONFIRMATION_TYPES`/`CALL_CONFIRMATION_TYPES` already encode, it
does not change any of their outcomes.

## 6. Permission Architecture

Traced exactly, confirming and extending the investigation report:

- **Types**: `DpsPermission.kt:45-89`, 7 entries, two `PermissionKind`s
  (`RUNTIME`, `SPECIAL_ACCESS`). Only `SCHEDULE_EXACT_ALARM` is
  `SPECIAL_ACCESS` in the whole enum, and it is **not** required by any tool
  in the current registry's `requiredPermissions` sets — `AndroidReminderTool`
  deliberately excludes it (`AndroidReminderTool.kt:77`, "SCHEDULE_EXACT_ALARM
  is intentionally absent"). **This means every CONFIRM_REQUIRED action's
  relevant permission is `RUNTIME`, dialog-requestable — the "permission
  precheck is impossible for some kind" case the master prompt asks about
  does not currently arise.** If a future tool ever declared a
  `SPECIAL_ACCESS` permission, the precheck design below degrades safely:
  `PermissionState.needsSettings` (`PermissionState.kt:81-83`) is already
  `true` for `REQUIRES_SETTINGS`, and the same `NeedsPermission`-shaped
  outcome/phrasing already handles it uniformly (`ToolResponseGenerator
  .phrasePermission` does not branch on `PermissionKind` at all).
- **Where checked today**: exclusively `DefaultToolExecutor.execute()`
  (`:91-102`), via `permissionManager.missing(tool.requiredPermissions)` — a
  synchronous, pure read of already-known state. **Never asynchronous by
  itself**; only `PermissionManager.request()` (`PermissionManager.kt:69`)
  is suspending, and it is only ever called by UI code responding to a
  `NeedsPermission` outcome (outside the scope of files this investigation
  read — UI layer, correctly out of scope for M8).
- **Missing-permission handling**: `ToolOrchestrator.executeIntent()`
  (`ToolOrchestrator.kt:188-196`) parks a `PendingPermissionAction`
  (`IntentResolution.kt:86-104`) — **in-memory only, inside `ToolOrchestrator`,
  never persisted** (`ExecutionRecoveryState.kt:38-44`'s own explicit scope
  note, re-confirmed unchanged). 5-minute freshness (`:97-102`).
- **Interaction with confirmation today**: none exists — confirmation is
  asked before permission is ever checked (§3), which is exactly locked
  decision 6's target for correction.
- **Interaction with multi-step today**: a post-hoc permission block mid-plan
  drops the plan remainder entirely, already, today (`:830-841`) — not a
  new M8 behavior, the existing precedent locked decision 7 must match.

### Minimum change to achieve RISK → PERMISSION PRECHECK → CONFIRM → EXECUTE

`SecretaryOrchestrator` currently has **no visibility** into
`PermissionManager` or `ToolRegistry` at all — its only view of permission
state is reactive, via `Outcome.NeedsPermission` returned from an
`executeIntent()` call it already made. A true precheck — one that runs
*before* asking for confirmation — needs a way to answer "does this intent's
tool need a permission it doesn't have" without dispatching the tool.

The minimal seam, using only what already exists:

```kotlin
// SecretaryOrchestrator gains two new constructor parameters:
private val permissionManager: PermissionManager,
private val toolRegistry: ToolRegistry,

// New private pure function:
private fun missingPermissionsFor(intent: DpsIntent): Set<DpsPermission> {
    val toolId = intent.type.toolId ?: return emptySet()          // DpsIntent.kt:498 — already exists, already imported
    val required = toolRegistry.find(toolId)?.requiredPermissions ?: emptySet()
    return permissionManager.missing(required)                     // PermissionManager.kt:50 — already exists
}
```

This duplicates **zero** permission logic — `PermissionManager.missing()`
is called with the exact same inputs `DefaultToolExecutor` itself would use
via `tool.requiredPermissions`; it is a second **call site**, not a second
**system**, exactly as decision 6 requires ("Use the existing permission
architecture where possible. Do not create a second permission system.").
`ToolRegistry`/`PermissionManager` are both already public, already
constructor-injected into other classes (`AiContainer.kt:259-264,386-413`),
and both already have working fakes in the existing JVM test suite
(`SecretaryOrchestratorTest.kt:292` `FakePermissions`; `:317`
`DefaultToolRegistry` — the real, pure-Kotlin production class, not a fake,
already used directly against fake tools in tests).

## 7. Confirmation Lifecycle

Re-traced and confirmed unchanged from the investigation (§10 of that
report): `PendingConfirmation` carries `{intent, requestedAtMillis}` only;
5-minute freshness (`PendingConfirmation.kt:25-30`); persisted via
`PersistedPendingState.Confirmation`; cleared the instant it's answered
(`resolveConfirmation`, `:1580/:1592/:1604`); `Confirmation.UNCLEAR` treated
as implicit decline, message reprocessed fresh (`:1558-1568`); multi-step
parking/resumption already type-agnostic (§3 above).

**Locked decision 8 is satisfied by construction**: the plan below adds
zero new fields to `PendingConfirmation` itself (a risk-tier is never
needed at resolution time — `resolveConfirmation` doesn't need to know
*why* it was asked, only what intent to run on yes). This is a **smaller**
change than the investigation report's own §21 speculated (it proposed an
optional reason field for future response-phrasing generality) — this plan
finds that field is not actually needed: the confirmation *question* is
already generated fresh, per-type, by the function that decides to ask
(`askDeleteConfirmation`/`askCallConfirmation`/new `askForgetFactConfirmation`),
before `PendingConfirmation` is ever constructed. **Correction from the
investigation report, per this prompt's own instruction to verify rather
than trust it blindly.**

## 8. Destructive Operation Analysis

Per action, per the master prompt's exact 10-point checklist:

**delete_event / cancel_task / cancel_reminder** (existing, unchanged behavior):
1. Confirmation required? Yes, already.
2. After YES? `finishExecution(pending.intent)` → `toolOrchestrator.executeIntent()` → real delete/cancel dispatched exactly once.
3. Cleared? Immediately, in the same branch, before dispatch (`:1580`).
4. Checkpoint? **No** — `OperationType` enum (`ExecutionRecoveryState.kt:220-226`) covers only `CREATE_TASK/CREATE_REMINDER/CREATE_EVENT`.
5. Process dies during execution? No recovery record exists for this window today — confirmed gap, not new to M8.
6. Could it execute twice? Only if the user answers YES twice in immediate succession before the field is cleared — structurally impossible in this single-threaded, one-message-at-a-time `handle()` dispatch (there is no concurrent-message path in `SecretaryOrchestrator`; each `handle()` call runs to completion, including clearing `pendingConfirmation`, before another can begin).
7. Could it partially complete? Same platform-level uncertainty M5-C/M5-E already accept for creates (a `ContentResolver`/`SharedPreferences` write mid-flight) — no new risk introduced, same class of risk M7/M5-C already live with for creates.
8. Can the system safely determine final state after restart? **Yes, already** — for calendar, `CalendarWriter.readEventSnapshot()` (already exists, M7) can be queried; for task, `AndroidTaskStore.find()` (already exists, M7). Both already have the exact read primitives needed to answer "does this still exist."
9. Is M7 verification applicable? **No — by design.** M7 verifies CREATE only (confirms new state matches intent); a DELETE's success criterion is "the row is now gone," which is a different, much simpler check (existence, not field-matching) that M7's `ExecutionVerifier` was never built for and the locked contract (§13, "M7 is frozen") forbids extending.
10. Is additional M8 recovery machinery necessary? **No.** Evidence-based conclusion: a delete/cancel confirmed by the user and then interrupted mid-write is, on restart, indistinguishable from "did it happen or not" — but so is every create before M5-C/M7 existed, and the actual observed failure mode motivating M5-C/M7 (a stale local id, a leftover local record) does not apply symmetrically to a delete: the *worst* outcome of an interrupted delete is "the row still exists" (safe — nothing was lost, the user can just ask again) or "the row is gone" (the intended outcome). There is no third, corrupted state a delete can land in the way a partially-written create can. **No replay-protection mechanism is proposed.** This directly satisfies decision 10 ("Add it only if evidence demonstrates a real gap" — no such evidence was found).

**forget_fact** (new CONFIRM_REQUIRED):
1. Confirmation required? Not today; **added by this plan**.
2. After YES? `store.forgetFact(fact.id)` (`AndroidMemoryTool.kt:137`), synchronous Room delete.
3. Cleared? Same mechanism as above, immediately.
4. Checkpoint? No — and for the same reasoning as point 10 above, none is proposed: a `forget_fact` interrupted mid-delete leaves the fact either still present (safe, re-askable) or gone (intended); no corrupted intermediate state exists in a single-row Room delete.
5–9. Same analysis as delete/cancel above, substituting "Room row" for "SharedPreferences/Provider row." M7 does not apply (`MEMORY` is not in M7's covered `IntentType`s regardless of action).
10. No additional recovery machinery proposed, same evidence-based reasoning.

## 9. Multi-Step Behavior

Traced against the exact worked example the master prompt specifies:

```
Step 1: CREATE TASK
Step 2: DELETE TASK   (already CONFIRM_REQUIRED, unchanged)
Step 3: CREATE REMINDER
```

| Scenario | Behavior (existing mechanism, confirmed applicable unchanged) |
|---|---|
| A. Step 1 succeeds and verifies | `continuePlan` loop iteration 1: `proceedToExecution` → SAFE_AUTO → `finishExecution` → M7 `Verified` → loop continues to step 2 (`:790-817`, unaffected by M8) |
| B. Step 2 requires confirmation | New `RiskPolicy` check inside `proceedToExecution` returns CONFIRM_REQUIRED (unchanged outcome, now policy-driven not `Set`-driven) → `Outcome.Clarify` → `parkRemainder` parks step 3 (`:826-827`, unchanged) |
| C. User says YES | `resolveConfirmation` → `Confirmation.YES` → `finishExecution` → `continueAfterResumedStep` resumes parked step 3 (`:1582`, unchanged) |
| D. Permission is missing (new, for a permission-bearing CONFIRM_REQUIRED type, e.g. a hypothetical future one — `cancel_task`/`forget_fact` have none) | **New behavior, §12 below**: precheck fires *before* the confirmation question is ever shown; step 2 is treated as blocked on permission, remainder (step 3) is dropped per the existing post-hoc-permission-block precedent (`:830-841`) — satisfying locked decision 7 by reusing, not inventing, the drop-remainder behavior |
| E. User says NO | `Confirmation.NO` → step 2 recorded `Cancelled` → `continueAfterResumedStep` sees non-`Handled`... actually sees `Handled` with a `Cancelled` result, which is **not** `isSuccess` → per `continuePlan`'s own existing check (`:795-802`) the remainder (step 3) is **not** run. Already exactly locked decision 7's requirement, already implemented, unchanged. |
| F. Process dies while waiting for confirmation | M5-B recovery (§3, §11) — unchanged, already proven at the JVM level; real-device proof required (§14) |
| G. Verification fails (hypothetically, if step 2 were a CREATE — it isn't, delete has no M7 verification per §8) | Not reachable for a delete/cancel/forget_fact step; for a SAFE_AUTO create step elsewhere in a plan, existing `continuePlan` behavior (`:814-817`) already stops the plan — unaffected by M8 |
| H. Step 2 executes but process dies immediately afterward | Identical to today's existing "interrupted delete" gap analyzed in §8 point 10 — no new mechanism, evidence-based |

**Previously completed/verified steps remain valid in every scenario** —
`continuePlan`'s `replies` accumulator and already-written tool state are
never rolled back by any existing or proposed mechanism; this is a
structural property of the loop (`:710`, `:786-788`), not something M8
needs to add.

## 10. Process-Death Architecture

Confirmed unchanged from investigation §11: `PendingConfirmation` already
persists via `PersistedPendingState.Confirmation`, already never
auto-executes, already re-asks the exact original question on restart
(`:320-337`). This satisfies locked decision 9's **mechanism** requirement
completely, with zero new code — the gap is entirely in **proof**, not
architecture (§14).

**New for M8**: does `pendingIntentAwaitingActionPermission` (§12) also need
process-death persistence? **Yes, by the same reasoning `ExecutionRecoveryState`
already applies to `pendingIntentAwaitingContactPermission`'s sibling** —
except `ExecutionRecoveryState.kt:38-44` explicitly documents that
`pendingIntentAwaitingContactPermission`-shaped state is **not** covered by
M5-B ("a permission block... is deliberately not covered"). This plan
recommends the identical scope decision for the new field: **not persisted**,
for the same reason M5-B gave — it is transient, resumed via
`onPermissionResult()`'s system callback path, not via `handle()`'s
message-driven recovery path, and persisting it would require the same
scope expansion M5-B explicitly declined. This is documented, not silent,
per the master prompt's own change-control rules.

## 11. M7 Integration

Confirmed frozen and untouched. The only new *combination* (not new
mechanism) requiring test coverage: a CONFIRM_REQUIRED action that is also
a CREATE does not exist in the current or proposed risk matrix (§5) — every
CONFIRM_REQUIRED row is a CANCEL/delete/forget action, and M7 only verifies
CREATE. **There is no code path where a confirmed action also runs through
`ExecutionVerifier`, today or after this plan.** The master prompt's
requested test category "confirmation-required action → execution → M7
verification where applicable" therefore has an empty applicable set under
the locked risk matrix — documented here rather than silently assumed, per
the master prompt's own instruction not to invent test scenarios that don't
apply. (If §26 Decision 1 is ever revisited to make some CREATE
confirm-required, this combination would need building; not required by
the current locked contract.)

## 12. Domain Model Proposal

### New file: `domain/secretary/RiskPolicy.kt`

```kotlin
package com.softwaremine.dps.domain.secretary

import com.softwaremine.dps.domain.intent.DpsIntent
import com.softwaremine.dps.domain.intent.IntentAction
import com.softwaremine.dps.domain.intent.IntentType

/** Whether an intent may run without asking, or needs an explicit yes first. */
enum class RiskLevel { SAFE_AUTO, CONFIRM_REQUIRED }

/**
 * Deterministic risk classification. No model call, no I/O, no argument
 * beyond the already-classified [DpsIntent] — a pure function, total over
 * every (IntentType, IntentAction) pair reachable from real classification.
 */
object RiskPolicy {
    fun classify(intent: DpsIntent): RiskLevel = when {
        intent.type == IntentType.FORGET_FACT -> RiskLevel.CONFIRM_REQUIRED
        intent.type in DELETE_TYPES && intent.action == IntentAction.CANCEL -> RiskLevel.CONFIRM_REQUIRED
        intent.type == IntentType.CALL_CONTACT -> RiskLevel.CONFIRM_REQUIRED
        else -> RiskLevel.SAFE_AUTO
    }

    private val DELETE_TYPES = setOf(IntentType.CALENDAR_EVENT, IntentType.TASK, IntentType.REMINDER)
}
```

This is a direct, behavior-preserving rename/relocation of
`DELETE_CONFIRMATION_TYPES`/`CALL_CONFIRMATION_TYPES`
(`SecretaryOrchestrator.kt:2032,2040`) into a dedicated, testable domain
object, plus the one-line `FORGET_FACT` addition. **Placed in `domain/`,
not `ai/`**, mirroring where `VerificationOutcome` lives (M7 precedent) —
pure Kotlin, no Android, independently unit-testable without constructing a
`SecretaryOrchestrator` at all (a genuine testability improvement over
today's private `Set` constants, which can currently only be exercised
indirectly through the orchestrator).

### No new type for the permission precheck

As shown in §6, `missingPermissionsFor()` is a private function, not a new
domain type — there is nothing to model beyond "a set of missing
permissions," which `Set<DpsPermission>` already expresses.

### New field: `pendingIntentAwaitingActionPermission`

```kotlin
/**
 * The intent a risk/permission precheck found missing a permission for,
 * held only until that permission is resolved — mirrors
 * [pendingIntentAwaitingContactPermission] exactly, generalized from "a
 * find_contact pre-resolution step" to "any CONFIRM_REQUIRED intent whose
 * own tool needs a permission it doesn't have yet."
 */
private var pendingIntentAwaitingActionPermission: DpsIntent? = null
```

**Why this field must exist, and why it cannot be avoided by reusing
`ToolOrchestrator`'s existing `pendingPermission`** — this is the one
genuinely subtle design point in this whole plan, worked through in full:

If, on discovering a missing permission for a CONFIRM_REQUIRED intent, the
code simply called `toolOrchestrator.executeIntent(intent)` (letting
`DefaultToolExecutor` independently rediscover the same missing permission
and populate `ToolOrchestrator`'s own `pendingPermission`), then later,
once the user grants the permission, `onPermissionResult()` would call
`toolOrchestrator.resumeAfterPermissionGrant()` — which **directly
re-executes the original tool call** (`ToolOrchestrator.kt:222-223`),
**never asking for confirmation at all**. That would silently violate
locked decision 6/8 for exactly the case it exists to protect: a
CONFIRM_REQUIRED action would execute automatically the moment a
permission is granted, with no yes/no ever asked. This is a genuine
correctness trap that a shallower reading of "reuse the existing permission
seam" would fall into.

The correct minimal fix, mirroring the **already-proven** shape of
`pendingIntentAwaitingContactPermission`/`resolveContactThenExecute`
(`:1237-1255`) exactly: when the precheck finds a missing permission for a
CONFIRM_REQUIRED intent, **do not call `executeIntent` at all.** Instead:

```kotlin
private fun proceedToExecution(intent: DpsIntent): ToolOrchestrator.Outcome {
    val risk = RiskPolicy.classify(intent)
    val missing = missingPermissionsFor(intent)

    if (missing.isNotEmpty()) {
        pendingIntentAwaitingActionPermission = intent
        _state.value = transition(SecretaryEvent.PermissionNeeded)
        val rationale = ToolResult.PermissionRequired(missing.toList(), buildRationale(intent, missing))
        return ToolOrchestrator.Outcome.NeedsPermission(responses.describe(rationale, intent), rationale)
    }

    if (risk == RiskLevel.CONFIRM_REQUIRED) {
        // existing askDeleteConfirmation / askCallConfirmation / new askForgetFactConfirmation
    }
    // ...existing contact-grounding / proceedAfterContactResolved fallthrough, unchanged
}
```

and in `onPermissionResult()` (`:538-556`), check the new field **before**
delegating to `toolOrchestrator.resumeAfterPermissionGrant()` — mirroring
exactly how `pendingIntentAwaitingContactPermission` is already checked
there:

```kotlin
suspend fun onPermissionResult(): ToolOrchestrator.Outcome? {
    _state.value = transition(SecretaryEvent.PermissionGranted)
    pendingIntentAwaitingActionPermission?.let { intent ->
        pendingIntentAwaitingActionPermission = null
        val result = recordOutcome(proceedToExecution(intent))   // re-run the gate fresh; permission state may now allow confirmation to be asked
        syncRecoveryPersistence()
        return result
    }
    // ...existing body, unchanged
}
```

Re-running `proceedToExecution(intent)` on resume — rather than jumping
straight to confirmation or straight to execution — is deliberate: it
re-evaluates the precheck fresh (the permission could have been re-revoked,
though unlikely, between grant and this call) and reaches the *same*
`askXConfirmation` call the original request would have reached had the
permission already been present, producing an identical user experience to
"permission was never missing" once the gate is passed. This reuses
`buildRationale`-equivalent phrasing already established in
`DefaultToolExecutor.buildRationale`/`describe`
(`ai/tool/DefaultToolExecutor.kt:150-162`) — a small, private, near-identical
helper is needed in `SecretaryOrchestrator` since `DefaultToolExecutor`'s
own `buildRationale` is `private`; **this plan proposes duplicating the
~10-line phrase-building logic rather than exposing it from
`DefaultToolExecutor`**, since `DefaultToolExecutor`/`ToolExecutor` are
explicitly frozen files under this milestone's scope guard. The duplication
is small, isolated, and each copy can independently evolve if the two
call sites' wording needs ever diverge — an acceptable, disclosed
trade-off given the alternative (touching a frozen file) is not permitted.

**Reused verbatim, no duplication**: `ToolResponseGenerator.phrasePermission`
is already `private` too — but `ToolResponseGenerator.describe(result,
intent)` (public) already accepts any `ToolResult.PermissionRequired` and
phrases it correctly regardless of caller, so the *phrasing* is fully
reused; only the ~10-line *rationale-string* construction
(`DefaultToolExecutor.buildRationale`/`describe`, lines 150-162) needs a
small, disclosed duplicate.

## 13. File-Level Change Plan

### NEW FILES

| File | Responsibility | Test coverage |
|---|---|---|
| `domain/secretary/RiskPolicy.kt` | Deterministic `RiskLevel` classification | New JVM `RiskPolicyTest.kt` |

### MODIFIED FILES

| File | Current responsibility | Required M8 change | Reason | Risk | Test coverage |
|---|---|---|---|---|---|
| `ai/secretary/SecretaryOrchestrator.kt` | Orchestration, existing confirmation gate | Replace `DELETE_CONFIRMATION_TYPES`/`CALL_CONFIRMATION_TYPES` checks with `RiskPolicy.classify`; add permission precheck in `proceedToExecution`; add `pendingIntentAwaitingActionPermission` field + its `onPermissionResult` branch; add `askForgetFactConfirmation`-equivalent (or generalize `askDeleteConfirmation`'s question-building to cover `FORGET_FACT`'s distinct phrasing); add 2 new constructor params (`permissionManager`, `toolRegistry`); small private rationale-builder | Locked decisions 1,2,6,7,8 | Medium — the single file every scenario in §9 routes through | New tests in `SecretaryOrchestratorTest.kt` (§15) |
| `di/AiContainer.kt` | Composition root | Pass `permissionManager`, `toolRegistry` into `secretaryOrchestrator`'s construction (both already exist as `val`s at `:259-264`/`:386`) | Wiring only | Low | Existing instrumented suites exercise the real container unchanged |
| `data/android/tool/AndroidMemoryTool.kt` | `remember_fact`/`recall_fact`/`forget_fact` | **None** — `forget_fact`'s own execution is unchanged; only the *caller* (SecretaryOrchestrator, via RiskPolicy) now asks first | N/A | None | No new tests needed here; existing `forgetFact` unit tests remain valid |
| 5 JVM test files wiring `SecretaryOrchestrator`'s constructor (`SecretaryOrchestratorTest.kt`, `SecretaryOrchestratorProductivityTest.kt`, `AiSessionManagerInterruptionTest.kt`, `AiSessionManagerReplyShortcutTest.kt`, `VoiceModeControllerTest.kt`) | Build a `secretary(...)` test double | Add `permissionManager`/`toolRegistry` params to each helper, defaulting to the `permissions`/`registry` locals each helper **already constructs** (§6) | Compile requirement, mirrors the exact M7 precedent for `executionVerifier`/`responses` | Low — each helper already has both values in scope | Existing tests unaffected; compile-only change for 4 of the 5 |
| 9 instrumented test files wiring `container.secretaryOrchestrator`'s constructor indirectly via `container.` fields, OR directly constructing `SecretaryOrchestrator` (same 9 files M7 touched: `GgufInferenceInstrumentedTest.kt`, `CalendarCheckpointRecoveryInstrumentedTest.kt`, `CalendarClassificationInvestigationTest.kt`, `CheckpointRecoveryInstrumentedTest.kt`, `ProcessDeathPersistenceInstrumentedTest.kt`, `SecretaryExecutionRecoveryInstrumentedTest.kt`, `SecretaryLiveWiringInstrumentedTest.kt`, `SecretaryLiveWiringProductivityInstrumentedTest.kt`) | Construct `SecretaryOrchestrator` against real device state | Add `permissionManager = container.permissionManager, toolRegistry = container.toolRegistry,` | Compile requirement, identical M7 precedent | Low | Existing suites unaffected |

### FROZEN FILES (confirmed untouched by this plan)

`ToolOrchestrator.kt`, `ToolExecutor.kt`/`DefaultToolExecutor.kt`,
`ToolResult.kt`, `ExecutionVerifier.kt`, `VerificationOutcome.kt`,
`ExecutionRecoveryState.kt`'s `PendingVerification`/`OperationCheckpoint`
sections (its `PersistedPendingState`/`PendingPlan` sections are
**read**, not modified — no new field added to either), `ClarificationEngine.kt`,
`IntentPromptBuilder.kt`, `IntentJsonParser.kt`, `ToolSelector.kt`,
`PrepareWhatsAppMessageTool.kt`, `PrepareEmailTool.kt`,
`AndroidContactsTool.kt`, `AndroidCalendarTool.kt`, `AndroidTaskTool.kt`,
`AndroidReminderTool.kt`, `AndroidNotificationTool.kt`, `AndroidCallTool.kt`,
all M6 Room files, all proactive/background files (none exist yet in this
codebase — confirmed absent), all voice files, all settings files.

**No frozen area was found necessary.** No STOP condition triggered.

## 14. Test Seam Plan

| Component | Seam needed | Already exists? |
|---|---|---|
| `RiskPolicy` | None — pure `object`, directly testable | New file, trivially testable |
| Permission precheck | `PermissionManager`, `ToolRegistry` as constructor params on `SecretaryOrchestrator` | **Yes, both interfaces already exist and already have fakes/real-pure-impls in `SecretaryOrchestratorTest.kt`** (`FakePermissions` at `:292`, real `DefaultToolRegistry` at `:317`) |
| Confirmation (generalized) | None new — `ConfirmationParser`/`PendingConfirmation` unchanged | Existing |
| Process-death (confirmation) | None new — `PersistentRecoveryStore` unchanged | Existing |
| Process-death (new `pendingIntentAwaitingActionPermission`) | None — deliberately not persisted (§10) | N/A |

**No Android-heavy class needs new JVM-compatibility work.** Every seam
needed already exists; this is a direct consequence of §6's finding that
the precheck reuses existing pure interfaces rather than reaching into
Android-specific implementations.

## 15. JVM Test Plan

New file `domain/secretary/RiskPolicyTest.kt`:
1. `create_task` → SAFE_AUTO
2. `create_event` → SAFE_AUTO
3. `update_task`/`update_event`/`update_reminder` → SAFE_AUTO
4. `complete_task` → SAFE_AUTO
5. `cancel_task` (CANCEL) → CONFIRM_REQUIRED
6. `cancel_reminder` (CANCEL) → CONFIRM_REQUIRED
7. calendar CANCEL → CONFIRM_REQUIRED
8. `CALENDAR_EVENT` with a non-CANCEL action → SAFE_AUTO (proves the action-gate, not just the type-gate, is preserved)
9. `CALL_CONTACT` → CONFIRM_REQUIRED regardless of action
10. `FORGET_FACT` → CONFIRM_REQUIRED
11. `REMEMBER_FACT`/`RECALL_FACT` → SAFE_AUTO
12. `WHATSAPP_MESSAGE`/`EMAIL_MESSAGE` → SAFE_AUTO (locked decisions 3/4, explicit regression guard)
13. `NOTIFICATION`, `WORK_LOG`, `MEETING_NOTE`, `ACTION_ITEM`, `REPORT` → SAFE_AUTO
14. Pure-function determinism: same intent classified twice yields the same result (no hidden clock/random)

New tests in `SecretaryOrchestratorTest.kt` (extending the existing
"Confirmation (destructive actions...)" area, `:1818` onward):

CONFIRMATION (generalized):
15. `forget_fact` asks before doing anything (mirrors `:1818` exactly, new type)
16. Confirming a `forget_fact` executes it (mirrors `:1838`)
17. Declining a `forget_fact` leaves the memory intact (mirrors `:1857`)
18. Existing delete/call confirmation tests (`:1818-2160`) still pass unmodified — regression, not new

PERMISSION + CONFIRMATION ORDERING (new):
19. A CONFIRM_REQUIRED intent whose tool is missing a required permission produces `NeedsPermission`, **never** a confirmation question first
20. Granting that permission via `onPermissionResult()` re-enters the gate and **asks for confirmation** — proves the trap in §12 does not occur (execution must not happen automatically)
21. Declining/never granting that permission leaves `pendingIntentAwaitingActionPermission` set, expires per the same staleness handling `PendingPermissionAction` already uses conceptually (documented, not necessarily code-shared, since the field is a plain `DpsIntent?` mirroring `pendingIntentAwaitingContactPermission`'s own lack of independent freshness — consistent with that existing precedent, not a new gap)
22. A CONFIRM_REQUIRED intent whose tool has **no** required permissions (e.g. `cancel_task`, `forget_fact`) skips the precheck branch entirely and reaches the confirmation question directly — proves the common case is unaffected

MULTI-STEP (new):
23. Step 2 CONFIRM_REQUIRED, permission missing → plan remainder (step 3) dropped, matching existing post-hoc precedent (`:830-841`)
24. Step 2 CONFIRM_REQUIRED, permission present, user declines → remainder dropped (regression of existing `:795-802`/`:886-891` behavior, now reachable via a second confirm-required type)
25. Two confirm-required steps in one plan (e.g. delete-task then forget-fact) — second question correctly asked only after the first resolves, remainder tracking intact

M7 INTEGRATION:
26. Confirmed CONFIRM_REQUIRED action followed immediately by an unrelated SAFE_AUTO CREATE step in the same plan still gets M7-verified normally (proves the two mechanisms compose without interference)

## 16. Instrumented Test Plan

Extending the existing per-file convention:

- `SecretaryLiveWiringInstrumentedTest.kt` / `SecretaryLiveWiringProductivityInstrumentedTest.kt`: confirm `forget_fact` is registered and reachable via the real `container.toolRegistry` (mirrors existing `taskOperationsAreRegisteredOnTheRealTool`-style tests).
- New or extended real-device confirmation test: forget_fact confirm/decline against the real `LongTermMemoryStore`/Room database, mirroring `ExecutionVerifierInstrumentedTest.kt`'s existing real-repository pattern.
- Permission-precheck-then-confirm against a real `AndroidPermissionManager` reading actual granted/denied state (feasible — `PermissionManager.state()` is a real, synchronous, already-instrumented-testable query; no UI interaction needed to *read* permission state, only to *request* it, which this precheck never does).
- Existing confirmation regression: `CalendarCheckpointRecoveryInstrumentedTest.kt`, `SecretaryExecutionRecoveryInstrumentedTest.kt` continue to pass unmodified (these already exercise `PersistedPendingState.Confirmation`'s recovery path per M5-B).
- M7 regression: `ExecutionVerifierInstrumentedTest.kt`, `ExecutionVerifierProcessDeathInstrumentedTest.kt` unmodified, must stay green.

## 17. Real Device Process-Death Plan

Required, per locked decision 9, following the exact genuine two-phase
methodology already established and proven three times in this codebase
(M3-D checkpoint precedent, M5-C/M5-E `OperationCheckpoint`, M7's own
`ExecutionVerifierProcessDeathInstrumentedTest.kt`):

```
adb shell am instrument -w -r \
  -e class .../ConfirmationProcessDeathInstrumentedTest#phase1AskConfirmationBeforeProcessDeath \
  com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner

adb shell am force-stop com.softwaremine.dps

adb shell am instrument -w -r \
  -e class .../ConfirmationProcessDeathInstrumentedTest#phase2ResolveConfirmationAfterProcessDeath \
  com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
```

Phase 1: drive a real `SecretaryOrchestrator.handle()` call that reaches
`askDeleteConfirmation`/`askForgetFactConfirmation`, confirm
`PersistentRecoveryStore.load()` shows the persisted `Confirmation` record
on disk. Phase 2 (fresh process): confirm the record survived, confirm the
first `handle()` call re-asks the exact original question (proving no
auto-execution), then send an explicit "yes" and confirm the action
executes **exactly once** (checked by reading real backing state — the
task/event/fact — once, not by counting internal calls, matching M7's own
"observe real state, don't trust internal bookkeeping" discipline).

This closes the investigation report's §11 disclosed gap: M5-B's mechanism
existed and worked at the JVM level, but had never been proven this way.
This plan requires that proof as part of M8's own acceptance, even though
the mechanism itself is M5-B's, because M8 is the milestone that first
depends on it being genuinely true.

**No production-only test hooks are introduced** — exactly like every
existing process-death test in this codebase, the test drives the same
public `handle()`/`onPermissionResult()` surface and reads the same
`PersistentRecoveryStore.load()` API production code already calls.

## 18. Regression Plan

Baseline to preserve: **JVM 722/722** (per the accepted M7 verdict) plus
however many new tests §15 adds. Any pre-existing failure must be
attributed to a real cause, not waved away as environmental, per the
locked contract's own rule — and conversely a new failure must not be
mislabeled pre-existing. Full instrumented suite re-run required, watching
specifically for:
- The one already-known, already-documented pre-existing failure
  (`outOfScopeToolsRemainUnimplemented`) — expected, not a regression.
- Any drift in the 9 instrumented files' `SecretaryOrchestrator`
  construction (compile-level, caught immediately by a build).

## 19. Scope Guard

Explicitly checked against every listed non-goal:

- **Autonomous replanning/retry/alternate actions**: none — every new branch
  in §12 either asks a question or stops; nothing decides what to do next
  on the assistant's own initiative.
- **Proactive/background intelligence**: no proactive files exist in this
  codebase at all yet (confirmed by their absence from the file tree);
  none added.
- **Android UI automation**: none — `ACTION_DIAL`/composer-opening behavior
  unchanged (locked decisions 3/4/5).
- **Advanced voice**: `VoiceModeController.kt` is only touched for its test
  helper's constructor wiring (§13) — zero behavioral change.
- **Permanent authorization**: explicitly absent — `PendingConfirmation`
  gains no persisted-preference field; `UserPreferences`/
  `PersistentPreferenceStore` untouched (locked decisions 8/12).
- **LLM risk/verification decisions**: `RiskPolicy.classify` takes a
  `DpsIntent` (already fully classified) and returns synchronously; no
  `AiEngine` reference anywhere in its signature or implementation.
- **Embeddings/RAG**: not applicable to anything in this plan.
- **M9/M10/M11/M12 functionality**: none — every changed file is in
  `ai/secretary`, `domain/secretary`, or `di`, all M1-M8 territory.

## 20. Risks

| Risk | Likelihood | Impact | Mitigation | Blocks implementation? |
|---|---|---|---|---|
| Confirmation/permission ordering introduces a regression in the 8 existing delete/call confirmation tests (`:1818-2160`) | Low | Medium | Precheck only fires when `missing.isNotEmpty()`; for every *existing* confirm-required action exercised by those tests, the test fakes already report permissions as granted (confirmed: `deleting a calendar event asks before doing anything` etc. use `FakePermissions()` default-granted) — precheck is a no-op for all of them | No |
| Stale `pendingIntentAwaitingActionPermission` leaks across turns (the exact bug class M2-B/M2-C already found and fixed twice for `pendingConfirmation`, §12 of the investigation report) | Medium | Medium | **Verified**: `pendingIntentAwaitingContactPermission` is explicitly cleared inside `reset()` at `:565`. The new field must get an identical line in `reset()` — a direct, confirmed precedent-copy, not new design | No |
| Destructive replay decision (§8, "no mechanism needed") turns out wrong once real usage is observed | Low | Low (bounded: worst case is a re-askable safe state, per §8's own analysis) | Explicitly disclosed as a judgment call in this plan, not hidden; revisit only if evidence (a real observed duplicate/lost delete) ever appears | No |
| Multi-step + permission-precheck interaction (§9 row D) has no existing test to model against for a *permission-bearing* CONFIRM_REQUIRED type mid-plan (today's only permission-bearing confirm type, `CALL_CONTACT`, is a single-step-shaped action in every existing test) | Medium | Low | New test #23 (§15) explicitly built for this; implementation must not assume it is a copy-paste of the single-step case | No |
| SharedPreferences persistence untouched but exercised by more call paths (`syncRecoveryPersistence` still only reads the 4 existing `pending*` fields — the new field is deliberately excluded per §10) | Low | Low | §10's decision is explicit and matches an existing precedent exactly | No |
| Regression risk from touching `SecretaryOrchestrator.kt`, the largest and most central file in the codebase | Medium | Medium | Full JVM+instrumented regression required before acceptance (§18), same discipline M7 already applied successfully to the same file | No |

**No risk identified rises to blocking.**

## 21. Open Questions

**NO BLOCKING ARCHITECTURAL QUESTIONS.**

One non-blocking clarification worth surfacing before Phase B begins,
included per the master prompt's instruction to list only genuine
unresolved items:

- **Question**: Should `askForgetFactConfirmation`'s question text be a new
  small function, or should `askDeleteConfirmation` be generalized to cover
  `FORGET_FACT` too (its phrasing — "Forget that Bilal is my developer?"
  — is structurally similar to "Delete \"X\"? This can't be undone.")?
  **Why it matters**: pure code-organization, zero behavioral or
  architectural consequence either way. **Evidence required**: none —
  this is a naming/structure choice implementers can make either way
  without affecting any test in §15. **Can implementation proceed without
  resolving it?** Yes, immediately — left to whichever reads more clearly
  once `RiskPolicy` exists (this plan has no preference and does not block
  on it).

## 22. Acceptance Matrix

| ID | Requirement | Implementation location | Test type | Expected evidence | Pass criteria |
|---|---|---|---|---|---|
| M8-RISK-01 | Two-state deterministic risk model | `RiskPolicy.kt` | JVM | `RiskPolicyTest.kt` all cases | Every case in §15 items 1-14 passes |
| M8-RISK-02 | `forget_fact` requires confirmation | `RiskPolicy.classify` + `SecretaryOrchestrator` | JVM | Tests 10, 15-17 | Confirm/decline both correct |
| M8-RISK-03 | WhatsApp composer remains safe-auto | `RiskPolicy.classify` | JVM | Test 12 | No confirmation ever asked for `WHATSAPP_MESSAGE` |
| M8-RISK-04 | Email composer remains safe-auto | `RiskPolicy.classify` | JVM | Test 12 | No confirmation ever asked for `EMAIL_MESSAGE` |
| M8-RISK-05 | Phone confirmation preserved | `RiskPolicy.classify` | JVM + instrumented regression | Test 9 + existing `:2093-2160` tests | Byte-identical behavior to today |
| M8-PERM-01 | Permission evaluated before confirmation | `proceedToExecution` precheck | JVM | Tests 19-22 | Confirmation never asked when permission is known-missing |
| M8-PERM-02 | Missing permission safely blocks execution | `proceedToExecution` + `onPermissionResult` | JVM | Test 20 | No tool call occurs before an explicit later yes |
| M8-MULTI-01 | Permission failure stops current plan | `continuePlan`/`parkRemainder` (existing) + precheck | JVM | Test 23 | Remainder dropped, no auto-continue |
| M8-MULTI-02 | Confirmation decline stops current plan | `continueAfterResumedStep` (existing, regression) | JVM | Test 24 | Remainder dropped |
| M8-CONF-01 | Existing `PendingConfirmation` reused | `SecretaryOrchestrator` | JVM + instrumented | All §15/§16 tests | No second confirmation type/state exists in the diff |
| M8-CONF-02 | No permanent authorization | Code review + `UserPreferences` unchanged | Static | Diff review | Zero new persisted-preference field |
| M8-CONF-03 | No automatic execution after restart | M5-B (existing) | Real device | §17 phase 1/2 | Action executes only after explicit post-restart yes |
| M8-CONF-04 | Confirmation is consumed once | `resolveConfirmation` (existing) | JVM + real device | §17 | Exactly one real state change observed |
| M8-REC-01 | Genuine process-death confirmation recovery | M5-B (existing) + new real-device proof | Real device | §17 | `adb force-stop` between ask and resolve, proven |
| M8-REC-02 | No duplicate destructive execution | §8 analysis (no new mechanism) | Real device (observational) | §17 phase 2 | Exactly one real state change |
| M8-M7-01 | M7 verification remains intact | Unchanged files | JVM + instrumented | Existing M7 suites | 100% pass, zero modification to M7 files |
| M8-TEST-01 | JVM regression | Full suite | JVM | `./gradlew test` | 722 + new tests, 100% pass |
| M8-TEST-02 | Instrumented regression | Full suite | Instrumented | `am instrument` full run | 100% pass except the one pre-existing documented failure |
| M8-SCOPE-01 | No M9+ scope leakage | Diff review | Static | File list in §13 | Every changed file is in `ai/secretary`, `domain/secretary`, or `di`, or a test file |

## 23. Implementation Sequence

The master prompt's proposed A-N sequence is sound and is adopted with one
refinement: Phase C (permission/confirmation ordering) is split into two
sub-steps because §12 shows it has real internal sequencing risk (the
auto-execute trap) that deserves its own checkpoint before forget_fact is
layered on top.

- **Phase A** — Repository baseline re-verification (this document's §2; re-run immediately before Phase B starts, in case time has passed).
- **Phase B** — `RiskPolicy` domain design + `RiskPolicyTest.kt` (§12, §15 items 1-14). No `SecretaryOrchestrator` change yet.
- **Phase C1** — Wire `RiskPolicy` into `proceedToExecution` in place of the two `Set` constants, zero behavior change (regression-only checkpoint: existing confirmation tests must still pass byte-for-byte).
- **Phase C2** — Add the permission precheck + `pendingIntentAwaitingActionPermission` (§12), with tests 19-22 before moving on — this is the highest-risk single step in the plan and gets its own isolated verification.
- **Phase D** — `forget_fact` confirmation integration (tests 15-17) — deliberately after C2, so it is built and tested against the *final* gate shape, not an intermediate one.
- **Phase E** — Destructive replay/recovery decision — already made in §8 (no mechanism); this phase is a documentation/report step, not code.
- **Phase F** — Multi-step integration (tests 23-25).
- **Phase G** — M7 integration verification (test 26; confirm §11's "empty applicable set" finding holds).
- **Phase H** — Full JVM test run.
- **Phase I** — Instrumented tests (§16).
- **Phase J** — Genuine process-death tests (§17).
- **Phase K** — Full regression (§18).
- **Phase L** — Cleanup (stale test artifacts on-device, per the M7 precedent of checking for leftover rows).
- **Phase M** — Independent audit.
- **Phase N** — M8 completion decision.

## 24. Final Readiness Verdict

**READY FOR IMPLEMENTATION**

Repository architecture is fully understood and re-verified against live
source (§2). Every locked decision is technically implementable without
touching a frozen file (§4, §13, §19). The one genuine design subtlety
(permission-precheck-vs-auto-execute-trap, §12) has a concrete, minimal,
precedent-based resolution, not an open question. Test seams are fully
defined and already exist (§14). Process-death methodology is fully
specified, reusing exactly the proven M3-D/M5-C/M7 pattern (§17).
Acceptance criteria are measurable (§22). No blocking open question
remains (§21).

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
