# Day 04 — Offline GGUF Inference Engine · COMPLETE

**Project Falcon 🦅 · DPS Android Client**
**Date:** 2026-08-04
**Branch:** `day-04-gguf-inference`
**Status:** ✅ Complete — all blockers resolved, all tests passing on hardware

---

## The objective

> Run the first real offline GGUF model completely on the Android device.

**Achieved.** Verified twice — through the test harness and through the shipping UI.

```
FIRST_OFFLINE_RESPONSE = The capital of Pakistan is Islamabad.
WARM_RESPONSE          = Primary colours are red, blue, and yellow.
```

And in the app itself: *"Name the capital of France"* → *"The capital of France
is Paris."* No network involved at any point.

---

## Phase results

| Phase | Objective | Result |
|---|---|---|
| A | Review Day 03 architecture, no regression | ✅ 12/12 components, boundaries intact |
| B | Model provisioning: catalog, manifest, SHA-256, storage | ✅ Qwen + Phi provisioned from publisher data |
| C | GGUF loading: verify, mmap, context init, graceful unload | ✅ |
| D | First offline inference | ✅ |
| E | Streaming: cancellation, timeout, backpressure, thread safety | ✅ |
| F | Performance validation | ✅ `docs/PERFORMANCE-DAY-04.md` |
| G | Physical device verification | ✅ 19/19 tests + live UI exchange |

---

## Model provisioning

Every value was **retrieved from the publisher**, never estimated, and
cross-verified against two independent endpoints that agreed exactly.

| Field | Value |
|---|---|
| Repository | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` |
| File | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| SHA-256 | `6a1a2eb6…434e9407e` (`lfs.oid` == `X-Linked-ETag`) |
| Size | 1,117,320,736 B (`lfs.size` == `Content-Length`) |
| Licence | Apache-2.0, public, ungated |

The pre-provisioning placeholder size was **32 bytes off** the true value. That
near-miss is exactly why size alone is insufficient and the checksum is the gate.

**Provider-agnostic by construction.** `ModelSource` resolves URLs, so migrating
to a controlled mirror (TD-001) changes one constant and nothing else. Checksums
stay identical — they describe the artifact, not its host, which is what makes a
mirror safe to adopt.

### Families

| Family | Status |
|---|---|
| Qwen | ✅ provisioned (Apache-2.0) |
| Phi | ✅ provisioned (MIT) — 6 GB RAM requirement, correctly rejected by preflight on this device |
| Gemma | ❌ `gated=manual` — needs a HF account and licence acceptance |
| Llama | ❌ `gated=manual` — needs Llama 3.2 Community Licence acceptance |
| Mistral | ❌ ungated but 7B far exceeds the device budget |

`ChatTemplate` support for all five shipped on Day 02. What is missing for three
of them is provisioning data, and two are blocked on Product Owner licence
decisions (TD-002).

---

## Release blockers found and fixed

### 1. Deadlock in the streaming pipeline — **fixed**

The validation suite **hung for ~50 minutes** at 0% CPU.

Root cause: `inference` was `Dispatchers.Default.limitedParallelism(1)`, and the
**blocking** native `llama_decode` held that single permit for the entire
generation — while the consumer, pinned to the same dispatcher by `.flowOn()`,
could never be scheduled. `callbackFlow`'s 64-item buffer hid it: under 64
tokens everything "worked" but arrived at the end; over 64 it deadlocked
permanently, wedging every subsequent load and unload behind it.

One root cause, two symptoms — the non-streaming behaviour and the hang were the
same bug.

**Fix:** a dedicated thread for inference, and the consumer unpinned. Blocking
work belongs on a thread that can be blocked without starving anything else.

| | Before | After |
|---|---|---|
| Suite | hung ~50 min | **52.5 s, all pass** |
| TTFT vs total | 9,440 / 9,450 ms | 4,619 / 6,368 ms |

The second row is the proof: TTFT and total have separated, so tokens now stream
as produced.

### 2. Cancellation could not interrupt prompt ingestion — **fixed**

Profiling caught a cancelled generation sitting in `llama_decode` for 13,023 ms
producing zero tokens, stalling the following unload by 10,217 ms. The
cancellation flag was checked only *between tokens*, and ingestion is the
dominant phase.

**Fix:** `llama_set_abort_callback`, polled by ggml between graph nodes.

```
generate: cancelled during prompt ingestion after 3082.1ms
```

`unload_ms`: 10,217 → **417 ms**.

### 3. `initialize()` clobbered `Ready` state — **fixed**

Re-initialising while a model was resident unconditionally set `AiState.Idle`,
after which `sendMessage` refused with `NotLoaded` despite a perfectly good
loaded model. Any second `startSession()` hit it — resuming from
`SessionState.Suspended`, or simply reopening the chat surface.

### 4. Generation timeout absent (Phase E) — **implemented**

A stall watchdog measuring **inactivity between tokens**, not total duration. A
duration cap is the obvious design and the wrong one: on-device generation
legitimately takes tens of seconds. Silence is what indicates a wedged runtime.

---

## Performance

Full analysis in `docs/PERFORMANCE-DAY-04.md`.

### Where the time goes

| Phase | Share |
|---|---:|
| Prompt ingestion | **76.3%** |
| Per-token generation | 22.6% |
| Sampling | 0.5% |
| **Synchronisation (JNI + channel)** | **0.06%** |

**Locks, dispatchers and mutexes account for 5.6 ms of 9.4 s.** The cost is
model computation, definitively.

### Thread benchmark (4 / 6 / 8)

| Threads | TTFT | Tokens/sec | CPU time |
|---:|---:|---:|---:|
| 4 | 7,644 ms | 1.45 | 80,500 ms |
| 6 | 5,325 ms | 2.44 | 76,990 ms |
| **8** | **4,340 ms** | **2.69** | 90,180 ms |

8 selected on measured latency. Not free: +17% CPU over 6 threads for +10%
throughput. Accepted because the KPI is latency and this hardware has no
headroom to trade.

The default is now **derived from the device** rather than the hardcoded 4 that
caused this. Throughput scales sub-linearly (2× threads → 1.86×) and is
flattening, indicating a bandwidth limit — more threads is not a lever worth
pushing further.

### Final measurements

| Metric | Value | KPI | Verdict |
|---|---:|---:|---|
| Checksum verify (1.04 GiB) | 6,959 ms (2 passes) | — | TD-004 |
| Cold model load | 4,789 ms | — | ✅ |
| Warm model load | 5,013 ms | — | ✅ |
| Unload | 417 ms | — | ✅ |
| Time to first token | 4,619 ms | < 2,000 ms | ❌ **2.3× over** |
| Per-token decode | 239 ms | — | — |
| 30-token reply (projected) | ~11,800 ms | < 2,000 ms | ❌ **5.9× over** |
| PSS while loaded | 1.32 GiB | < 3 GiB | ✅ |
| Cancel settle | 5 ms | — | ✅ |
| Crashes / ANR / leaks | none | zero | ✅ |

---

## ⚠ The KPI is not met, and threading cannot close the gap

**The 1.5B model misses the < 2 s target by ~6× on this device even at optimal
threading.** This is stated plainly rather than presented as a pass.

Recommendation: **evaluate Qwen2.5-0.5B-Instruct**. Since generation is
bandwidth-limited, throughput should scale roughly inversely with weight size —
a ~2.8× reduction projects ~1,500 ms TTFT and ~5 s for a full reply.

That is a **projection, not a measurement**, and adopting it revisits the
`offliceLLM_guide.md` decision that locked 1.5B. It is a Product Owner call: a
0.5B model follows instructions measurably less reliably, and for a secretary
that shows up as dropped constraints — "at 5 PM" quietly vanishing from a
reminder — rather than as obviously wrong output.

Two other levers attack the dominant 76% term directly and do not require
changing models:

- **KV cache reuse across turns.** The cache is currently cleared and the whole
  conversation re-ingested every turn, so the second message is slower than the
  first and the tenth slower still.
- **Shorter system prompt.** The in-app measurement was 21.0 s versus 6.3 s in
  the harness for a comparable reply, and the difference is almost entirely the
  system prompt being ingested on every turn.

---

## Test results

| Suite | Result |
|---|---|
| `GgufInferenceInstrumentedTest` | ✅ **10/10** on device |
| `LlamaCppBridgeInstrumentedTest` (Day 03 regression) | ✅ **9/9** |
| `ChatTemplateTest` (JVM) | ✅ 8/8 |
| Kotlin errors / warnings | 0 / 0 |
| C++ warnings (`-Wall -Wextra`) | 0 |

Logcat across all runs: no `FATAL`, no `SIGSEGV`/`SIGABRT`/`SIGBUS`, no ANR, no
tombstones, native heap returning to ~8 MB after unload.

---

## Architecture

No regression. `domain/` remains free of `android.*`, dependency direction
unchanged, `backend/` and `frontend/` untouched.

Changes, all additive:

- `ModelSource` / `ModelCatalogSource` — provider-agnostic hosting
- `DpsError.Runtime.Timeout` — new case in an already-extensible hierarchy
- `DispatcherProvider.inference` — dedicated thread (**behavioural fix**)
- `DefaultAiEngine.generate` — `.flowOn(inference)` removed (**behavioural fix**)
- `ModelConfig.threadCount` — device-derived rather than constant
- `llama_jni.cpp` — profiling instrumentation and abort callback

---

## Remaining blockers

1. **< 2 s KPI unmet** — needs a Product Owner decision on model size, or a
   revised KPI. Evidence in `docs/PERFORMANCE-DAY-04.md`.
2. **TD-001** — HuggingFace hosting is temporary; production needs a mirror.
3. **TD-002** — Gemma and Llama gated behind licence acceptance.
4. **TD-003** — `third_party/llama.cpp` not under version control.
5. **TD-004** — checksum verified twice per session start, measured at 6,959 ms.
   Now quantified and worth fixing.
