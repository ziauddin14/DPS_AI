# Day 16 — M6: Real Long-Term Memory

## Scope

M6 gives DPS genuine, durable memory beyond one conversation: **semantic
facts** ("Bilal mera developer hai, yaad rakhna" → recalled correctly days
later, surviving a real process restart) and **episodic history** (every
completed action logged automatically, retained on a bounded schedule). Both
are new — the first Room database this codebase has ever needed — added
strictly alongside the ten existing `SharedPreferences`-plus-JSON stores,
none of which were touched, migrated, or replaced.

All 10 locked architectural decisions from the M6 Master Plan were
implemented exactly as approved. All 3 disclosed scope decisions (preference
write path limited to `defaultReminderLeadMinutes`; memory controls stay
conversational-only, no Settings UI; no at-rest encryption) hold. No M7+
capability was implemented or investigated.

---

## 1. What shipped

### New domain/data layer (Room, scoped entirely to M6)
- `domain/memory/SemanticFact.kt`, `domain/memory/EpisodicMemoryEntry.kt` —
  pure Kotlin, no Android.
- `data/android/memory/semantic/{SemanticFactEntity,SemanticFactDao}.kt`,
  `data/android/memory/episodic/{EpisodicMemoryEntity,EpisodicMemoryDao}.kt`,
  `data/android/memory/DpsMemoryDatabase.kt` — Room, `exportSchema = true`,
  schema history checked into `app/schemas/`, **`fallbackToDestructiveMigration()`
  never used**.
- `data/android/memory/LongTermMemoryStore.kt` — the one class touching the
  DAOs directly; owns retention pruning (episodic: 365-day age cap **and**
  5,000-row count cap, oldest-first, opportunistic after each insert),
  deterministic relevance ranking (`rankBySubjectRelevance`: exact
  case-insensitive subject match before substring match, each tier
  newest-first from the DAO's own ordering), and the privacy-guard call.
  Semantic facts are never auto-pruned; conflicting facts about the same
  subject coexist by design (no auto-merge).

### New AI-layer components
- `ai/memory/MemoryPrivacyGuard.kt` — deny-list (password/OTP/CVV/IBAN/
  account-number/card-number/CNIC/national-id keywords, plus a grouped
  13–19-digit card-number regex and a bare 9+-digit-run regex), enforced at
  the persistence boundary inside `LongTermMemoryStore.rememberFact`, not
  merely trusted to classification.
- `ai/memory/EpisodicMemoryRecorder.kt` — logs every tool's
  `ToolResult.Success` (never a failure/cancellation) to episodic memory,
  reusing the tool's own summary text rather than re-deriving one; explicitly
  excludes `ToolId.MEMORY` itself (remembering a fact is not loggable
  history).
- `ai/memory/PreferenceStatementRecognizer.kt` — the one, narrow
  `UserPreferences.defaultReminderLeadMinutes` write path: fires only on an
  explicit "hamesha/by default/always ... N minutes before" statement,
  reusing `ReferenceResolver.findRelativeOffsetMillis` rather than a second
  regex. An ordinary one-off "remind me N minutes before" is untouched.
- `data/android/tool/AndroidMemoryTool.kt` — `remember_fact`/`recall_fact`/
  `forget_fact`, mirroring `AndroidTaskTool`'s own ambiguous-match pattern
  for `forget_fact` (zero matches → honest failure; several matches → "which
  one?"; exactly one → real delete).

### Classification wiring
- `domain/intent/DpsIntent.kt` — three new `IntentType` values
  (`REMEMBER_FACT`/`RECALL_FACT`/`FORGET_FACT`), each with `requiredFields`
  (`{title, message}` / `{title}` / `{title}`) and a `toolId` mapping to
  `ToolId.MEMORY`. Subject carried in `title`, fact content in `message` —
  deliberately not `person`, to avoid misfiring
  `SecretaryOrchestrator.proceedToExecution()`'s unrelated contact-grounding
  branch.
- `domain/tool/AndroidTool.kt` — `ToolId.MEMORY` added.
- `ai/intent/IntentPromptBuilder.kt` — one new rule bullet distinguishing the
  three memory intents from `task`/`conversation`; the intent list and JSON
  schema needed no structural change (`ROUTABLE_INTENTS` is already generic
  over `IntentType.entries`, and the schema already carries `title`/`message`).
- `ai/intent/IntentJsonParser.kt` — **zero changes**; already generic over
  wire names and the `title`/`message` fields.
- `ai/intent/ToolSelector.kt` — three new branches mapping the memory intents
  to `ToolCall(ToolId.MEMORY, ...)`. This `when` is exhaustive over
  `IntentType`, so this edit was compiler-enforced, not optional.
- `ai/intent/ClarificationEngine.kt` — three new branches in `questionFor`
  (also compiler-enforced exhaustiveness).
- `ai/intent/ToolResponseGenerator.kt` — two `when` blocks
  (`phraseNeedsPermission`'s `purpose`, `defaultSuccess`) needed a branch each
  for exhaustiveness; both are practically unreachable (the memory tool
  declares no permissions and always returns a non-blank summary) but every
  `IntentType` must say something.

### Orchestration/wiring
- `ai/secretary/SecretaryOrchestrator.kt` — `recordOutcome` converted to
  `suspend fun` (every call site was already inside a suspend context) and
  now calls `episodicMemoryRecorder.record(toolId, outcome.result)`
  alongside the existing `ConversationMemoryUpdater.remember()` call, on the
  same `Handled` branch, so a failed/cancelled outcome logs nothing to either.
  `handleInternal` gained a `recordDefaultLeadMinutesIfStated()` call at its
  very top — a side-channel check on the raw message that never gates or
  alters classification, deliberately placed after (not inside) the
  `pendingCheckpoint`/`restoredRecovery` branches in `handle()`, which are
  documented as never inspecting `userMessage` at all.
- `di/AiContainer.kt` — `dpsMemoryDatabase`, `longTermMemoryStore`,
  `episodicMemoryRecorder` (public, like `toolExecutor`/`toolRegistry`, so
  instrumented tests constructing their own `SecretaryOrchestrator` can reuse
  the real instance), `AndroidMemoryTool` added to the `implemented` tool
  list. `AndroidToolCatalog.kt` needed **zero changes** — confirmed by
  reading its `declaredTools()` logic before editing anything.

---

## 2. JVM regression

**684/684, 0 failures.** 644 pre-M6 baseline + 40 new:
- `MemoryPrivacyGuardTest` (7), `PreferenceStatementRecognizerTest` (7),
  `LongTermMemoryStoreTest` (13: ranking, semantic CRUD, episodic retention).
- `IntentJsonParserTest` (+4), `ToolSelectorTest` (+3),
  `ClarificationEngineTest` (+3).
- `SecretaryOrchestratorTest` (+6): episodic-logged-only-on-success,
  remember→recall→forget end-to-end through the real `AndroidMemoryTool`,
  a disallowed fact failing rather than storing, an explicit default-lead
  statement persisting, an ordinary one-off statement leaving the default
  untouched.
- `IntentPromptBuilderTest`'s own prompt-size budget test was updated
  (1400 → 1650 chars) with a dated justification, following this file's own
  established convention (Day 06/08-B/08-E did the same) — not loosened
  casually; the new rule bullet was trimmed first, then the budget moved for
  the remainder.

## 3. Instrumented regression and genuine process-death results

All run this session on the connected physical device.

- **`LongTermMemoryStoreInstrumentedTest`** (new, in-memory Room): 5/5 —
  remember/recall through real SQLite, forget removes only that row, a
  disallowed fact is never written, episodic range/keyword lookup, and the
  5,000-row cap pruning oldest-first (5,003 real inserts).
- **`AndroidMemoryToolInstrumentedTest`** (new, in-memory Room): 5/5 —
  remember→recall, unknown-subject never fabricates an answer, forget then
  re-recall finds nothing, an ambiguous subject fails and asks which one, a
  disallowed fact fails rather than storing.
- **`LongTermMemoryProcessDeathInstrumentedTest`** (new, genuine two-phase,
  real `adb shell am force-stop` + confirmed-killed `pidof`): **2/2** — a
  semantic fact and an episodic entry both survive a real process kill and
  restart, read back through a completely fresh `DpsMemoryDatabase.create()`
  call in a new process.
- **`AndroidToolsInstrumentedTest#m6MemoryToolIsRegisteredAsARealImplementation`**
  (new): confirms `ToolId.MEMORY` resolves to `AndroidMemoryTool`, not a
  placeholder, in the actual production `AiContainer` DI graph.
- **`ProcessDeathPersistenceInstrumentedTest`** (M3-D): 2/2, genuine process
  death, unaffected.
- **`SecretaryExecutionRecoveryInstrumentedTest`** (M5-B): both pairs, 4/4,
  genuine process death, unaffected.
- **`CheckpointRecoveryInstrumentedTest`** (M5-C): all 3 pairs, 6/6, genuine
  process death, unaffected.
- **`CalendarCheckpointRecoveryInstrumentedTest`** (M5-E): both pairs, 4/4,
  genuine process death, unaffected.
- **`ProactiveCheckWorkerInstrumentedTest` + `CalendarWriterInstrumentedTest`**
  (M4): 20/20.
- **`AndroidToolsInstrumentedTest`**: 21/22 — see §4 for the 1 pre-existing
  failure (a 2nd, unrelated pre-existing failure surfaced only when this
  class's tests are combined with others in one process; see §4).

### A methodology note from this session
An initial regression pass batched five instrumented classes into one `am
instrument` invocation for speed. That produced 7 apparent failures. On
investigation, 5 of the 7 were an artifact of the batching itself: these
classes' own two-phase (`phase1...`/`phase2...`) tests have no
`@FixMethodOrder`, so JUnit's default (unordered) method execution let a
`phase2` run before its own `phase1` within the same process — the exact
"still executes both methods... but without a real process death between
them" caveat each of these classes' own doc already warns about. Re-run each
pair with the documented separate-invocation-plus-real-`force-stop`
methodology, every one passed. The remaining 2 are addressed in §4.

## 4. Failures observed, both investigated, neither caused by this milestone

Both are pre-existing and already documented in M5-C's/M5-D's/M5-E's own
completion docs, reproduced identically here:

- `AndroidToolsInstrumentedTest#outOfScopeToolsRemainUnimplemented` — the
  same stale `PHONE`-tool assertion. Re-confirmed this session:
  `AndroidCallTool` (`override val id: ToolId = ToolId.PHONE`) is already
  registered in `AiContainer`'s `implemented` list, predating this session's
  diff entirely — the test's own doc comment ("PHONE is genuinely still
  unimplemented") is simply out of date.
- `AndroidToolsInstrumentedTest#calendarListingFindsControlledEventsForASpecificDateThenCleansUp`
  — expected 2, found 7. The test's own `finally` block deletes exactly the
  2 events it creates every run (verified: it always did, including during
  this session's failing runs), so the other 5 are real, pre-existing
  personal calendar entries on this physical device landing on the test's
  fixed `today + 5 days` offset — unrelated to any code in this repository.
  Re-confirmed as the identical pattern M5-E's own completion doc already
  documented.

Neither touches memory, intent, or orchestration code, and both reproduce
identically with the M6 diff fully reverted in a mental check (Room/KSP
build-config changes cannot affect calendar provider queries or `ToolId.PHONE`
registration).

## 5. Device cleanup verification

- Real on-disk `dps_long_term_memory.db`: confirmed empty of semantic facts
  (the process-death test's own final assertion) and episodic entries (a
  temporary one-off `deleteOlderThan(Long.MAX_VALUE)` cleanup call, run once
  via `am instrument` and then deleted from the source file — never shipped
  as permanent test or production code).
- `calendarListingFindsControlledEventsForASpecificDateThenCleansUp`'s own
  `finally` block confirmed (by reading it) to delete both events it creates
  on every invocation, regardless of assertion outcome — this session's
  repeated runs left nothing new behind.
- `git status` after the full session: exactly the file set in §6 below,
  nothing else.

## 6. Files that remained frozen

Confirmed via `git status`/`git diff`: `ConversationMemory.kt`,
`PersistentMemoryStore.kt`, `ConversationMemoryUpdater.kt`,
`ReferenceResolver.kt` (not touched at all — `PreferenceStatementRecognizer`
only *calls* its existing `findRelativeOffsetMillis`), `PersistentPreferenceStore.kt`
itself (only its pre-existing `save()`/`load()` are called, never edited),
`ProactiveCheckWorker.kt`, `ProactiveRuleEvaluator.kt`,
`ProactiveStateStore.kt`, every M5-series recovery/checkpoint file, every M4
file — all show either zero diff or exactly their pre-existing, pre-M6 diff.

## 7. Safety and privacy invariants preserved

- Every Room DAO write is an awaited `suspend` call — no fire-and-forget,
  carrying forward the M5-C "window-D" lesson into the new persistence layer.
- `MemoryPrivacyGuard` runs at the persistence boundary
  (`LongTermMemoryStore.rememberFact`), not only trusted to classification —
  defense in depth, verified by both JVM and real-SQLite instrumented tests.
- Real deletes only (`SemanticFactDao.deleteById`), no soft-delete flag —
  mirrors `PersistentMemoryStore.clear()`'s own "erase outright" precedent.
- `SecretaryOrchestrator.reset()`'s scope is unchanged — it still clears only
  `ConversationMemory` and M5-B recovery state; long-term memory has its own,
  separate, explicit deletion path (`FORGET_FACT`), confirmed by a passing
  regression on every existing `reset()` test.
- No `fallbackToDestructiveMigration()` anywhere; schema exported
  (`app/schemas/com.softwaremine.dps.data.android.memory.DpsMemoryDatabase/1.json`)
  so a real future `Migration` can be written against actual history.
- No new AI infrastructure: semantic-fact extraction is one more `IntentType`
  through the exact same one-shot classification pass every other intent
  already uses; episodic logging is 100% deterministic, triggered by the
  same `ToolResult.Success` signal `ConversationMemoryUpdater.remember()`
  already keys on.

## 8. M7+ scope check

Not started, not investigated: observe/verify/replan (M7), risk-tier safety
(M8), controlled device/app automation (M9), proactive background wiring —
`ProactiveCheckWorker.kt` untouched — (M10), advanced voice (M11),
productization/Settings UI (M12). Memory controls remain conversational-only
(`REMEMBER_FACT`/`RECALL_FACT`/`FORGET_FACT`), no Settings screen, per the
disclosed and approved M6 scope.

---

M6 complete: both new tables are genuinely, on-device, process-death-proven
durable; the full JVM and instrumented regression suites are green aside from
two pre-existing, independently root-caused, unrelated environmental
failures already documented in three prior milestones' own completion docs.
M7 not started. Waiting for explicit approval.
