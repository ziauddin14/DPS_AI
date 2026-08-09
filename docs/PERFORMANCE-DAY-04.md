# Performance Report — Day 04

**Device:** NEW 15 (SSH Telecom SMC) · Android 12 (API 31) · arm64-v8a · 8 cores · 3.73 GiB RAM
**Model:** Qwen2.5-1.5B-Instruct Q4_K_M (1.04 GiB) · llama.cpp b10246 · CPU only, `gpu_layers=0`
**Measured:** 2026-08-04

All figures are read back from on-device logcat. Nothing here is estimated.

---

## 1. Where the time goes

Native instrumentation around each phase of `generate()`, first inference
(34 prompt tokens → 7 output tokens, 9,429.5 ms total):

| Phase | Time | Share |
|---|---:|---:|
| **Prompt ingestion** (`llama_decode` of prompt) | **7,197.2 ms** | **76.3%** |
| **Per-token generation** (forward passes) | **2,135.2 ms** | **22.6%** |
| Sampling | 47.8 ms | 0.5% |
| Tokenization | 2.5 ms | 0.03% |
| **Synchronisation** (JNI upcall + channel send) | **5.6 ms** | **0.06%** |
| Detokenization | 0.0 ms | ~0% |
| Unaccounted | 41.0 ms | 0.4% |

### Conclusion

**Locks, dispatchers, mutexes and UI dispatch account for 5.6 ms out of 9.4
seconds — 0.06%.** The slowness is model computation. Warm runs matched cold
runs (prompt ingestion 5,792 ms for 28 tokens, ~207 ms/token versus ~212 ms/token
cold), which also rules out mmap page-in as the explanation.

### Load phases

| Phase | Cold | Warm |
|---|---:|---:|
| Model mmap | 1,679.1 ms | 1,642.9 ms |
| KV cache / context init (`n_ctx=8192`) | 468.7 ms | 204.0 ms |
| Checksum verify (1.04 GiB, SHA-256) | 3,336 ms per pass | — |

Session start performs **two** checksum passes (TD-004), measured at 7,271 ms
combined.

---

## 2. Thread-count benchmark

Identical prompt and `maxOutputTokens=32` for every configuration. A discarded
warm-up generation precedes each measurement. CPU time sampled from
`/proc/self/stat`; **effective cores = CPU time ÷ wall time**.

| Threads | TTFT | Total | Tokens/sec | CPU time | Effective cores | Scaling vs 4 |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 7,644 ms | 22,010 ms | 1.45 | 80,500 ms | 3.66 | baseline |
| 6 | 5,325 ms | 13,120 ms | 2.44 | 76,990 ms | 5.87 | **1.68×** |
| **8** | **4,340 ms** | **11,937 ms** | **2.69** | 90,180 ms | 7.55 | **1.86×** |

### Reading the curve

- **4 → 6 threads:** +68% throughput, −30% TTFT. Large win.
- **6 → 8 threads:** +10% throughput, −19% TTFT, but **+17% CPU time**.

Throughput is scaling **sub-linearly** and flattening: 2× the threads buys 1.86×
the throughput, and the last third of that is nearly free of benefit. That shape
is characteristic of approaching a memory-bandwidth wall.

### Caveats stated honestly

1. **The `threads=4` row is pessimistic.** It ran first, and its `load_ms` of
   19,624 ms (versus ~5,000 ms for the later configurations) shows the page
   cache was cold. The warm-up generation did not touch every weight. The
   4 → 6 comparison is therefore inflated; the **6 → 8 comparison is clean**,
   both having run against a warm cache.

2. **Effective cores is not proof of compute-bound.** ggml's threadpool uses
   spin-wait barriers, so a thread stalled on memory still burns CPU and still
   counts toward utilisation. 7.55 of 8 cores "busy" does **not** establish that
   the cores are doing useful arithmetic. The flattening throughput curve is the
   more trustworthy signal, and it points at bandwidth.

3. Arithmetic sanity check: Q4_K_M 1.5B reads roughly 1.1 GB of weights per
   token. At 2.69 tokens/sec that implies ~3.0 GB/s of sustained read bandwidth,
   which is entirely plausible for a budget MediaTek part — and close enough to
   the practical ceiling to explain why more threads stop helping.

**Verdict: partially compute-bound up to ~6 threads, bandwidth-limited beyond.**

---

## 3. Selected configuration

**`threadCount = 8`** on this device — the best measured latency, chosen on
evidence rather than on the assumption that "all cores" is correct.

The choice is not free: 8 threads costs 17% more CPU than 6 for a 10% gain,
which is worse energy per token. It was taken because the product KPI is latency
and this device has no efficiency headroom to trade away.

Rather than hardcode 8, the default is now **derived from the device**
(`availableProcessors()`, clamped). Hardcoding 4 was the original defect; a
4-core phone should use 4 and a 12-core phone should not be capped at 8 by an
arbitrary constant baked in on this handset.

---

## 4. KPI assessment — the model does not fit this device

`offliceLLM_guide.md` targets **AI response < 2 s**.

| Metric | Target | Best measured (8 threads) | Verdict |
|---|---:|---:|---|
| Time to first token | < 2,000 ms | **4,340 ms** | ❌ 2.2× over |
| Throughput | — | **2.69 tok/s** | — |
| A 30-token reply | < 2,000 ms | **≈ 15,500 ms** | ❌ 7.7× over |

**Even at optimal threading the 1.5B model misses the KPI by a wide margin on
this hardware.** No amount of threading closes a 7.7× gap; the remaining levers
are model size, quantisation, or the KPI itself.

### Recommendation: evaluate Qwen2.5-0.5B-Instruct

| | 1.5B Q4_K_M | 0.5B Q4_K_M (projected) |
|---|---|---|
| Weights read per token | ~1.1 GB | ~0.4 GB |
| Expected throughput | 2.69 tok/s | **~7–8 tok/s** |
| Expected TTFT | 4,340 ms | **~1,500 ms** |
| 30-token reply | ~15.5 s | **~5 s** |

Since generation is bandwidth-limited, throughput should scale roughly inversely
with weight size — a ~2.8× reduction predicts ~2.8× more tokens per second.
That brings TTFT within the KPI and a full reply to roughly 5 s.

This is a **projection from the bandwidth model, not a measurement.** It needs
benchmarking before adoption, and adopting it means revisiting the
`offliceLLM_guide.md` decision that locked 1.5B — a Product Owner call, since a
0.5B model follows instructions measurably less reliably, and for a secretary
that manifests as dropped constraints ("at 5 PM" quietly disappearing from a
reminder) rather than obviously wrong output.

### Other levers, ranked

| Lever | Expected gain | Cost |
|---|---|---|
| Smaller model (0.5B) | **~2.8×** | Weaker instruction-following |
| Shorter system prompt | Proportional to prompt tokens | Ingestion is 76% of the cost, so this is worth more than it sounds |
| KV cache reuse across turns | Removes re-ingestion of history | Real work; currently the cache is cleared per call |
| Lower quantisation (Q4_0) | ~10–15% | Quality loss for little gain |
| GPU offload (Vulkan) | Unknown, possibly large | Device-fragmented; crashes rather than degrades (ADR-002) |

**KV cache reuse deserves attention.** Prompt ingestion dominates at 76%, and
the current implementation clears the cache and re-ingests the entire
conversation on every turn. In a multi-turn session that cost grows with history
length — so the second message in a conversation is slower than the first, and
the tenth is slower still.

---

## 4b. Post-optimisation results (threads=8, both fixes applied)

Full validation suite re-run after adopting `threadCount = 8` and the
cancellation fix. **19/19 tests pass** (10 inference + 9 JNI regression).

| Metric | Before | After | Change |
|---|---:|---:|---|
| **`unload_ms`** | 10,217 ms | **417 ms** | **24× faster** |
| `cancel_settle_ms` | 13 ms | **5 ms** | 2.6× faster |
| Time to first token | 7,348 ms | **4,619 ms** | −37% |
| Prompt decode (34 tokens) | 7,197 ms | **4,522 ms** | −37% |
| Per-token decode | 305.0 ms | **239.0 ms** | −22% |
| Validation suite wall time | 70.3 s | **52.5 s** | −25% |
| Cold model load | 5,053 ms | 4,789 ms | −5% |
| Model mmap | 1,679 ms | 1,642 ms | flat |
| KV context init | 469 ms | 329 ms | −30% |

Cancellation during ingestion, previously impossible:

```
generate: cancelled during prompt ingestion after 3082.1ms
```

Memory, unchanged and healthy:

| Metric | Value |
|---|---:|
| PSS while loaded | 1,381,591 kB (1.32 GiB) |
| Private dirty | 355,112 kB (347 MiB) |
| Native heap while loaded | 661,276,448 B |
| Native heap after unload | 8,224,544 B |
| Native heap after shutdown | 8,058,672 B |

Native heap returns to ~8 MB after unload, so the model's ~630 MB is fully
released. PSS of 1.32 GiB against private dirty of 347 MiB confirms mmap is
doing its job — most of the model is clean, file-backed pages the kernel may
reclaim under pressure.

### In-app measurement (Phase G)

A live exchange through the shipping UI — *"Name the capital of France"* →
*"The capital of France is Paris."*

```
PROFILE tokenize=2.2ms prompt_decode=14300.9ms decode=6554.7ms
        first_token=14416.0ms total=21017.3ms tokens=7
```

**21.0 s in the app versus 6.3 s in the test harness for a comparable reply.**
The difference is prompt length: the app prepends
`PromptManager.DEFAULT_SYSTEM_PROMPT`, and ingestion is the dominant cost, so
extra system-prompt tokens are expensive in a way that is easy to overlook.

This is the strongest available argument for prioritising **KV cache reuse** and
a **shorter system prompt** — both attack the 76% term directly, and the system
prompt is paid on every single turn.

---

## 5. Deadlock and cancellation fixes

### Deadlock — resolved

| | Before | After |
|---|---|---|
| Validation suite | **hung ~50 min** | **70.3 s, 10/10 pass** |
| TTFT vs total | 9,440 ms vs 9,450 ms | 7,348 ms vs 9,553 ms |

Root cause: `inference` was `Dispatchers.Default.limitedParallelism(1)`, and a
**blocking** native call held that single permit for the whole generation while
the consumer — pinned to the same dispatcher by `.flowOn()` — could never be
scheduled. `callbackFlow`'s 64-item buffer masked it below 64 tokens (tokens
simply arrived all at once at the end) and deadlocked above it.

Fixed by giving inference a **dedicated thread** and removing `.flowOn()` from
the consumer. The second row above is the evidence: TTFT and total have
separated, so tokens now stream as produced.

### Cancellation during prompt ingestion — resolved

Profiling caught a cancelled generation sitting in `llama_decode` for 13,023 ms
producing zero tokens, which stalled the following unload by 10,217 ms. The
cancellation flag was only checked *between* tokens, and ingestion is the
dominant phase.

Fixed with `llama_set_abort_callback`, which ggml polls between graph nodes, so
`llama_decode` is now interruptible mid-computation.
