# Day 10 — Milestone 3-D: Real-Device Process-Death Validation

**Project Falcon 🦅 · DPS Android Client**
**Branch:** `day-05-android-tool-foundation`
**Scope:** M3-D — validation, hardening and documentation only. No new product
capability. Proves that M3-A (`PersistentMemoryStore`), M3-B (its wiring into
`SecretaryOrchestrator`/`AiContainer`) and M3-C (`PersistentPreferenceStore`
and the `anchorToPriorEvent` three-way precedence) survive a genuine Android
process death, not merely a same-process object reconstruction.

---

## 1. Objective

M3-B and M3-C's own real-device tests each proved something narrower than
process death and said so explicitly in their own KDoc: a **second,
independently constructed** `SecretaryOrchestrator`/`AiContainer` inside the
**same still-running instrumentation process**, reading the same real
`SharedPreferences` file. That is legitimate evidence that the disk
round-trip itself works, but it is not evidence that the state survives the
one event that actually matters in production — Android reclaiming the
process and the user reopening the app later. M3-D's one job is to close
that specific, previously-acknowledged gap, honestly, and to lock down two
already-decided but previously untested-on-device behaviors: the
failure/refusal memory-pollution guard, and `reset()`'s persisted-memory
semantics.

## 2. Audit Findings

Read in full before any edit, confirming the exact current shape of every
M3-A/B/C component:

| Component | Confirmed current state |
|---|---|
| `PersistentMemoryStore` | `SharedPreferences` + one JSON blob; `load()`/`save()`/`clear()`; `load()` falls back to `ConversationMemory.EMPTY` on missing/corrupt storage. Unchanged since M3-B. |
| `ConversationMemory` | `@Serializable`, nine "last X" slots, unchanged since M3-A. |
| `SecretaryOrchestrator` | `_memory` seeded via `persistentMemoryStore.load()` at construction; one write point, `updateMemory()`, used by all three memory-write call sites; `reset()` clears both `_memory.value` (`EMPTY`) **and** calls `persistentMemoryStore.clear()`; `anchorToPriorEvent`'s offset resolves `statedOffsetMillis ?: preferredLeadMillis() ?: -DEFAULT_REMINDER_LEAD_MILLIS`, where `preferredLeadMillis()` reads (never writes) `persistentPreferenceStore.load().defaultReminderLeadMinutes`. |
| `AiContainer` | `persistentMemoryStore` (private) and `persistentPreferenceStore` (public) both `by lazy { *.create(applicationContext, logger) }`; both threaded into `secretaryOrchestrator`'s construction. |
| `PersistentPreferenceStore` | Same pattern as `PersistentMemoryStore`; `save()` rejects a non-null, non-positive `defaultReminderLeadMinutes` via `IllegalArgumentException` before writing anything. |
| `UserPreferences` | `@Serializable data class UserPreferences(val defaultReminderLeadMinutes: Int? = null)`. |
| `SecretaryOrchestratorTest` / `SecretaryLiveWiringInstrumentedTest` | Confirmed every `SecretaryOrchestrator(...)` construction site already threads both stores (9 sites total across the codebase, all fixed during M3-B/M3-C). |

**Construction-site search** (`grep -rn "SecretaryOrchestrator(" android/`,
`grep -rn "PersistentMemoryStore(" android/`,
`grep -rn "PersistentPreferenceStore(" android/`) confirmed no construction
site was missed and none needed a new fix for M3-D — the 9 sites fixed
during M3-B/M3-C already cover the whole codebase.

`git status`/`git diff --stat` at the start of M3-D matched exactly the
state left at the end of the M3-C finalization report — nothing drifted.

No prior M3-A/B/C completion document existed (`DAY-09-M2-COMPLETION.md` is
the most recent), so this is the first M3 completion document; it also
implicitly documents M3-A/B/C's final shape via the audit table above.

## 3. Exact Files Changed

**Zero production files touched.** M3-D is validation and documentation only:

- **New:** `android/app/src/androidTest/java/com/softwaremine/dps/ai/secretary/ProcessDeathPersistenceInstrumentedTest.kt`
- **New:** `android/docs/DAY-10-M3-D-COMPLETION.md` (this file)

No file under `app/src/main` changed. No JVM test file changed.

## 4. Process-Death Validation Method

Two `@Test` methods in one new file, run as **two separate
`am instrument` invocations** with a real `adb shell am force-stop` between
them — not a same-process trick:

```bash
adb shell am instrument -w -r \
  -e class com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest#phase1WriteRealStateBeforeProcessDeath \
  com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner

adb shell am force-stop com.softwaremine.dps

adb shell am instrument -w -r \
  -e class com.softwaremine.dps.ai.secretary.ProcessDeathPersistenceInstrumentedTest#phase2VerifyRestoredStateAfterProcessDeath \
  com.softwaremine.dps.test/androidx.test.runner.AndroidJUnitRunner
```

`am force-stop` genuinely terminates the OS process while leaving on-disk
`SharedPreferences` files untouched (`pm clear` would wipe them too — never
run). This was the strongest mechanism available in this repository: no
UiAutomator-based kill/relaunch harness exists, and no in-process trick
(`reset()`, Activity recreation, a second `AiContainer` in the same process)
would have proven anything beyond what M3-B/M3-C already proved.

**Empirical confirmation the kill was real:** `adb shell pidof
com.softwaremine.dps` returned no PID immediately after `force-stop`, and
Phase 2 constructed a brand-new `AiContainer` from scratch — `_memory` was
seeded via `persistentMemoryStore.load()` at that construction, exactly the
same code path production hits on every cold start.

**A real bug this validation caught:** the first Phase 1 run passed all its
own in-process assertions but, when the raw `SharedPreferences` XML was
inspected directly afterward, showed an empty file — the exact async
`SharedPreferences.Editor.apply()` flush race M2-D previously found and
documented. The instrumentation process exits shortly after the test method
returns, and the queued disk write had not yet landed. Fixed by adding a
`delay(1500)` at the end of Phase 1 (mirroring M2-D's own fix), after which
direct inspection of `dps_conversation_memory.xml`, `dps_user_preferences.xml`
and `dps_tasks.xml` confirmed all three had genuinely flushed before the kill.

## 5. ConversationMemory Persistence Result

**PASS.** After the real kill, Phase 2 read
`container.secretaryOrchestrator.memory.value.lastTask` — the production
`AiContainer`'s own lazily-constructed `SecretaryOrchestrator`, no test
scaffolding involved — and it correctly held the task Phase 1 created
("M3-D process-death check"), never accessed via `.handle()`, purely via the
constructor-time `persistentMemoryStore.load()` seed.

## 6. ReferenceResolver-After-Restart Result

**PASS.** A second, scripted-engine `SecretaryOrchestrator` — built from the
*same* real, on-disk `PersistentMemoryStore`/`PersistentPreferenceStore`
`container.secretaryOrchestrator` itself reads from, with only the AI engine
swapped for the deterministic stand-in this whole test suite already uses —
was sent `"complete that task"` with no title. `ReferenceResolver` resolved
it purely from the memory restored across the real process boundary, and the
real `AndroidTaskTool` genuinely completed task id 65. `ReferenceResolver`
itself was not modified or specially exercised — this is its existing,
unmodified single-reference resolution path.

## 7. Preference Persistence Result

**PASS.** `container.persistentPreferenceStore.load().defaultReminderLeadMinutes`
read `15` after the real kill — the exact value Phase 1 saved, read via the
production `AiContainer`'s own lazily-constructed store.

## 8. Three-Way Precedence Result

**PASS**, all three cases, run against the *restored* real preference store
in the post-kill process:

| Case | Explicit request offset | Stored preference (restored) | Expected | Result |
|---|---|---|---|---|
| B | none | 15 min | 15 min before | ✅ |
| A/D | 5 min | 15 min | 5 min before | ✅ |
| C | none | cleared mid-test | 30 min (original default) | ✅ |

## 9. Failure/Refusal Pollution Test

**PASS.** Before any process boundary was even involved (Phase 1, in-process):
a real task was created ("M3-D process-death check"), then a `complete_task`
was attempted against a title that matches nothing
(`"M3-D nonexistent task xyz"`). `AndroidTaskTool.notFound()` returned
`ToolResult.Failure` — confirmed by direct code read — and
`ConversationMemoryUpdater`'s existing, unmodified gating (`if (result !is
ToolResult.Success) return memory`) left `lastTask` exactly as it was, both
in `secretary.memory.value` and in `memoryStore.load()` (the real disk
copy). `ConversationMemoryUpdater.kt` was not opened for editing.

## 10. Reset Semantics

**Confirmed unchanged, not re-decided.** M3-B's `reset()` already clears
**both** the in-memory `_memory.value` (`EMPTY`) **and** calls
`persistentMemoryStore.clear()` — Option B in the M3-D brief's own framing.
This was M3-B's deliberate, already-approved design (its own KDoc: *"an
explicit 'forget the conversation' action, not an ordinary write"*), not an
ambiguity M3-D needed to resolve. Locked down with one new real-device
assertion in Phase 2: after `container.secretaryOrchestrator.reset()`, the
real on-disk `PersistentMemoryStore.load()` returns `ConversationMemory.EMPTY`.
`SecretaryOrchestrator.kt` itself was not modified.

## 11. Device Cleanup Result

**PASS**, verified by directly reading the raw `SharedPreferences`/
`CalendarProvider` state after the full two-phase run — not by trusting
`finally` alone:

| Store | State after cleanup |
|---|---|
| `dps_conversation_memory.xml` | `<map />` — empty (reset() + explicit clear both ran) |
| `dps_user_preferences.xml` | `<map />` — empty |
| `dps_tasks.xml` | `tasks: {}` — the M3-D task was cancelled |
| `dps_reminders.xml` | Contains **only** the two pre-existing, unrelated `"call the bank"` entries (ids `1060`, `1065`) left by `rescheduleFollowUpResolvesAgainstTheRealReminderJustCreated` in earlier sessions — **explicitly not** an M3-D test, **not modified**, and **no M3-D-titled reminder present**. |
| `CalendarProvider` | No `"M3-D"`-titled event remains. |

## 12. JVM Test Count

**564/564, 0 failures** — unchanged from the M3-C finalization baseline.
M3-D added zero JVM tests (its entire value is process-boundary proof, which
a JVM test cannot provide). Confirmed by actually running
`./gradlew :app:testDebugUnitTest` and aggregating every
`test-results/testDebugUnitTest/*.xml` file's `tests=`/`failures=`/`errors=`
attributes.

## 13. Instrumented Test Count

**Relevant suite: 18 + 2 = 20/20, 0 failures**, actually run on-device:

- `SecretaryLiveWiringInstrumentedTest` — re-run in full after adding the new
  file: **18/18** (unchanged from the M3-C baseline).
- `ProcessDeathPersistenceInstrumentedTest` — run as the genuine two-phase,
  real-process-kill sequence described in §4: **2/2**
  (`phase1WriteRealStateBeforeProcessDeath`,
  `phase2VerifyRestoredStateAfterProcessDeath`).

(Running both `ProcessDeathPersistenceInstrumentedTest` methods in one
combined `am instrument` invocation — e.g. via Android Studio's "Run Tests" —
would still compile and likely pass, but would **not** exercise a real
process death between them; that mode is documented in the file's own KDoc
as a compile/logic smoke check only, and was not what was run to produce the
results in this document.)

## 14. Production Compilation Result

`./gradlew :app:compileDebugKotlin` — **BUILD SUCCESSFUL**. (Unaffected by
M3-D, since no production file changed; run anyway per the brief's explicit
"do not report all green without running.")

## 15. Instrumented Compilation Result

`./gradlew :app:compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL** (one
pre-existing, unrelated warning in `MainActivityPermissionHostInstrumentedTest.kt`,
present before M3-D and not touched).

## 16. Git Diff Audit

```
git status --short   → 1 new untracked file (the test) + 1 new doc; every
                        other entry byte-identical to the M3-C finalization
                        end state
git diff --stat       → zero production files changed
git diff --check      → only the pre-existing, unrelated
                        frontend/src/components/TaskTable.jsx trailing-
                        whitespace issue (not touched, not introduced here)
git diff --cached     → empty; nothing staged
```

## 17. Forbidden-File Audit

None of `ConversationMemoryUpdater.kt`, `ReferenceResolver.kt`,
`IntentPromptBuilder.kt`, `PromptManager.kt`, `ToolSelector.kt`,
`TemporalPhraseResolver.kt`, `TemporalStepAttributor.kt`, `ActionDetector.kt`,
`ClarificationEngine.kt`, `PendingPlan.kt`, `SecretaryState.kt`,
`ConversationManager.kt`, `ConversationState.kt` appear in `git status` —
confirmed untouched.

## 18. Existing Unrelated Test-Device Leak

`rescheduleFollowUpResolvesAgainstTheRealReminderJustCreated` (in
`SecretaryLiveWiringInstrumentedTest.kt`, unmodified) has no cleanup of its
own and has now left **2** stray `"call the bank"` reminders (ids `1060`,
`1065`) on the test device across prior sessions. Per explicit instruction,
this test was not modified and this leak is not treated as an M3-D
regression — confirmed distinguishable from M3-D's own (fully cleaned up)
state in §11.

## 19. Known Limitations

- The two-phase, ADB-orchestrated `am force-stop` sequence is the strongest
  process-death proof available in this repository, but it requires a human
  or script to run the two `am instrument` invocations with the kill between
  them in the correct order — there is no single command that performs the
  whole validation unattended, and no CI wiring for it in this change.
- `ProcessDeathPersistenceInstrumentedTest`'s two `@Test` methods, if someone
  runs the whole class in one invocation (bypassing the documented two-step
  procedure), will still pass but will silently stop proving process death —
  this is documented in the file's own KDoc, not hidden.
- The pre-existing reminder leak (§18) is untouched and will keep growing by
  one entry every time `SecretaryLiveWiringInstrumentedTest` is run in full;
  out of scope for M3-D.

## 20. M3 Final Architectural Status

M3 is complete across all four sub-phases:

- **M3-A** — `ConversationMemory` is serializable and round-trips through
  `PersistentMemoryStore` (`SharedPreferences` + one JSON blob).
- **M3-B** — that store is wired into `SecretaryOrchestrator`/`AiContainer`;
  every successful memory update persists through one write point; `reset()`
  clears both copies.
- **M3-C** — `UserPreferences.defaultReminderLeadMinutes` persists through
  the parallel `PersistentPreferenceStore`, consumed by `anchorToPriorEvent`'s
  three-way precedence (explicit request offset → stored preference → fixed
  30-minute default), with the store itself validating input and never
  persisting the fallback.
- **M3-D** — all of the above verified to survive a genuine Android process
  death, not merely a same-process reconstruction, using the strongest
  mechanism this repository's test infrastructure supports (`adb shell am
  force-stop` between two separate `am instrument` invocations); the
  failure/refusal memory-pollution guard and `reset()`'s persisted-memory
  semantics were both locked down against real device state in the same run.

DPS's short-term conversational memory and the one user preference it
currently exposes are both genuinely durable across everything short of the
device being wiped or the app's data being explicitly cleared. No new
product capability, no new persistence mechanism, no architectural change was
introduced in M3-D — exactly as scoped.
