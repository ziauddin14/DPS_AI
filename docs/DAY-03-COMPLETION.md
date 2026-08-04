# Day 03 — Native AI Runtime Integration

**Project Falcon 🦅 · DPS Android Client**
**Date:** 2026-08-04
**Branch:** `day-03-native-runtime`
**Status:** ✅ **All phases complete** — verified on physical hardware

---

## Scope

Bring llama.cpp into the build and implement the native half of the JNI bridge
declared on Day 02. Explicitly **not** in scope: tool calling, grammar
constraints, model download, GGUF execution, Ollama, Day 04 work.

---

## Phase results

| Phase | Objective | Result |
|---|---|---|
| 1 | Install NDK + CMake, configure Gradle, verify toolchain | ✅ |
| 2 | Integrate llama.cpp, build arm64-v8a only | ✅ |
| 3 | Implement native `LlamaCppBridge` | ✅ |
| 4 | Verify JNI round-trip, memory, no crashes | ✅ verified on device |
| 5 | Release compatibility, R8, packaging | ✅ |

---

## Toolchain (verified)

| Component | Version |
|---|---|
| Android NDK | **27.3.13750724** (r27d) |
| Clang | 18.0.4 (Android 13691557, r522817d) |
| CMake | **3.31.5** |
| Ninja | bundled with CMake 3.31.5 |
| llama.cpp | **b10246** — `39eab74a05d3e68ac822b6dc6cd78c90cb985c19` |

NDK r27d chosen over r29/r30 because it is the revision AGP 8.7.x aligns with
and sits inside llama.cpp's tested matrix. Newer NDKs are not yet validated
upstream, and a mismatch there surfaces as miscompiled inference rather than a
build error.

---

## Native build configuration

llama.cpp is consumed as a **pinned source checkout** at
`android/third_party/llama.cpp`, git-ignored rather than vendored.

Options disabled and why — every one of these defaults ON in b10246 and is
actively harmful in an Android APK:

| Option | Reason |
|---|---|
| `LLAMA_USE_PREBUILT_UI` | Downloads a prebuilt web UI from a HuggingFace bucket **at configure time**. A build-time fetch from a third-party host is both a network dependency an offline-first product must not have and an unaudited input to the shipped binary. |
| `LLAMA_BUILD_UI`, `LLAMA_BUILD_HTML` | Web assets with no meaning inside an APK. |
| `LLAMA_OPENSSL` | Would require OpenSSL cross-compiled for Android to serve HTTPS we never use. |
| `GGML_OPENMP` | The NDK ships no OpenMP runtime; this fails at `dlopen` on device rather than at build time. |
| `GGML_NATIVE` | Probes the *host* CPU while cross-compiling for arm64. |
| `GGML_VULKAN`, `GGML_OPENCL` | GPU offload is device-fragmented on Android; a wrong answer is a crash, not a slowdown (ADR-002). |

Note `LLAMA_CURL`, referenced by most llama.cpp Android guides, **no longer
exists** in b10246. Option names were read from the checked-out source rather
than carried over from documentation.

---

## JNI contract

Signatures were taken from `javap -s` on the compiled Day 02 class, not inferred.

```
loadModel  (Ljava/lang/String;III)J
freeModel  (J)V
generate   (JLjava/lang/String;IFFIFI[Ljava/lang/String;L…$TokenCallback;)I
tokenCount (JLjava/lang/String;)I
cancel     (J)V
```

`LlamaCppBridge` is a Kotlin `object`, so its `external fun` members compile to
**instance** methods. JNI therefore passes `jobject thiz`, not `jclass`.
Declaring `jclass` would still link but misrepresents the ABI.

`FinishCode` constants were read from `javap -constants` and mirrored exactly:
`END_OF_TURN=0, STOP_SEQUENCE=1, MAX_TOKENS=2, CANCELLED=3, ERROR=-1`.

**No Kotlin source was modified.** The Day 02 interface is unchanged.

---

## Correctness details worth recording

**UTF-8 handling.** Two distinct hazards, both handled:

1. llama.cpp emits *token pieces*, which are byte fragments. A multi-byte
   character can straddle two tokens, so a piece is not necessarily valid UTF-8
   on its own. Incomplete trailing sequences are withheld and prepended to the
   next token.
2. `NewStringUTF` expects Java's *modified* UTF-8, which differs from real UTF-8
   above the BMP. Passing real 4-byte sequences to it is undefined behaviour.
   Conversion goes UTF-8 → UTF-16 explicitly and uses `NewString`.

**Sampling.** `llama_sampler_sample` performs apply + select + **accept**.
Calling `llama_sampler_accept` separately would double-accept and corrupt
penalty state — a silent quality regression, not a crash.

**Model loading.** b10246 replaced the `use_mmap` / `use_mlock` booleans with a
`load_mode` enum. `LLAMA_LOAD_MODE_MMAP` is used — mmap **without** mlock.
Pinning ~1.1 GB of weights so the kernel can never reclaim them is precisely what
gets an Android app killed under memory pressure, and it defeats the
trim-and-reload strategy `DpsApplication` depends on.

**Cancellation.** `cancel` runs on a different thread from `generate`. It touches
only an atomic flag and makes no JNI calls.

**Context overflow** is reported rather than truncated. Silently dropping the
head of a prompt removes the system persona and produces confidently wrong
answers — worse for a secretary than an honest failure.

---

## Symbol export restriction

A linker version script (`dps_llama.map`) limits the dynamic symbol table to the
JNI entry points.

Without it, `-fvisibility=hidden` governs only our own translation units;
llama.cpp and ggml are statically linked and compiled with default visibility, so
all of their symbols were re-exported.

| Metric | Before | After |
|---|---|---|
| Exported dynamic symbols | 7,751 | **5** |
| `libdps_llama.so` | 5.27 MB | **3.79 MB** |

This matters beyond size: Android loads all native libraries into one process
namespace, so a future dependency exporting `llama_*` would bind against ours,
producing failures that look like memory corruption rather than a link problem.

The version node is **anonymous**. A named node tags symbols `@@NAME`; those
still resolve via `dlsym`, but making JNI lookup depend on default-version
handling buys nothing when shipping a single unversioned library.

---

## Build status

| Check | Result |
|---|---|
| `:app:externalNativeBuildDebug` | ✅ arm64-v8a, 202 objects |
| `:app:assembleDebug` | ✅ `app-debug.apk` — 13.7 MB |
| `:app:assembleRelease` | ✅ `app-release-unsigned.apk` — 4.88 MB |
| `:app:testDebugUnitTest` | ✅ 8/8 passed |
| Kotlin errors / warnings | 0 / 0 |
| C++ warnings (`-Wall -Wextra`) | 0 |

**Packaging** — both APKs contain `lib/arm64-v8a/libdps_llama.so` (3.79 MB),
stored **uncompressed** (`useLegacyPackaging = false`), which mmap-based loading
requires. Only `arm64-v8a` is present.

**Release/R8** — `LlamaCppBridge` and `LlamaCppBridge$TokenCallback` map to
themselves in `mapping.txt`. The strings `LlamaCppBridge`, `loadModel`,
`freeModel`, `tokenCount`, `onToken` and `dps_llama` were confirmed present in
the release DEX, and the `.so` exports exactly the five matching symbols. Both
sides of the JNI contract verified in release configuration.

---

## Phase 4 — runtime verification on physical hardware

### Test device

| Property | Value |
|---|---|
| Model | NEW 15 (SSH Telecom SMC Pvt. Ltd) |
| Serial | `VNEW1535091002114` |
| Android | 12 (API 31) |
| ABI | **arm64-v8a** — matches the only ABI we ship |
| RAM | 3,909,076 kB (3.73 GiB) — clears the 3 GB `minDeviceRamBytes` gate |
| Cores | 8 |

### Static verification (retained)

- ELF is AArch64, `DYN`, ELF64
- All five JNI symbols exported, unversioned, names matching Kotlin exactly
- `NEEDED` lists only `libandroid`, `liblog`, `libm`, `libdl`, `libc` — no
  `libc++_shared.so`, confirming `c++_static`
- All undefined symbols are standard libc/liblog

### Runtime verification

**App install and launch**

`primaryCpuAbi=arm64-v8a`. Cold launch measured at **3,908 ms**, inside the
< 7 s cold-start KPI — though note the model is not loaded on this path, so this
is a floor, not the final figure.

The device's `lib/arm64/` directory is **empty**, which is correct rather than a
fault: `useLegacyPackaging = false` means the `.so` is mmap'd straight out of the
APK instead of being extracted. Logcat confirms it:

```
D/nativeloader: Load .../base.apk!/lib/arm64-v8a/libdps_llama.so
                using class loader ns clns-4 ...: ok
```

**Library and backend initialisation**

```
I/DPS/llama_jni: llama.cpp backend initialised
I/DPS/AiEngine : Selected runtime: llama.cpp
```

The second line is the decisive one. The engine only reports a selected runtime
after `LlamaCppRuntimeProvider.isAvailable()` returns true, which requires
`System.loadLibrary("dps_llama")` to have succeeded. Had the library failed to
load, the engine would have reported `AiState.Unavailable` instead.

Graceful degradation on the absent model was also confirmed end to end:

```
I/DPS/AiEngine       : Model requires installation: Model 'qwen2.5-1.5b-instruct-q4_k_m' is not installed.
I/DPS/AiSessionManager: Session cannot start: model 'qwen2.5-1.5b-instruct-q4_k_m' required.
```

**Instrumented tests** — `LlamaCppBridgeInstrumentedTest`, **9/9 passed**,
0 failures, 0 errors, 4.579 s on device.

| Test | Verifies |
|---|---|
| `t01_nativeLibraryLoads` | No `UnsatisfiedLinkError`; `isAvailable` true |
| `t02_freeModelWithNullHandleIsSafe` | Symbol resolves; null handle tolerated |
| `t03_cancelWithNullHandleIsSafe` | Cross-thread cancel path safe |
| `t04_tokenCountWithInvalidHandleReturnsNegative` | No dereference of a bad handle |
| `t05_loadModelWithMissingFileReturnsNullHandle` | Missing GGUF returns 0, no signal kill |
| `t06_loadModelWithEmptyPathReturnsNullHandle` | Empty-path guard fires before native loader |
| `t07_runtimeProviderReportsAvailableAndFailsCleanlyOnMissingModel` | Full Kotlin → JNI → native → Kotlin round trip, `DpsResult` mapping, idempotent `unload()` |
| `t08_generateWithoutLoadedModelFailsCleanly` | Emits one terminal `Failed(NotLoaded)` chunk |
| `t09_repeatedFailedLoadsDoNotLeakNativeMemory` | 100 load/free cycles |

**Memory cleanup**

```
I/DPS/LeakProbe: native heap before=6157808 after=6157808 delta=0 over 100 iterations
```

**Zero bytes** of native heap growth across 100 allocate-and-destroy cycles. The
`unique_ptr` cleanup path in `loadModel`'s failure branch releases everything it
allocates. This matters disproportionately because native allocations are
invisible to the JVM garbage collector: a leak here would never show as heap
pressure, only as the process being killed after prolonged use.

**Crash and error handling**

- 104 failed model loads and 1 empty-path rejection handled — **no crash on any**
- `FATAL EXCEPTION` / `SIGSEGV` / `SIGABRT` / `SIGBUS`: **none**
- `/data/tombstones/`: **empty**
- `UnsatisfiedLinkError`: **none**

llama.cpp's own diagnostics arrive correctly through the `llama_log_set` bridge,
confirming that callback wiring works:

```
E/DPS/llama: gguf_init_from_file: failed to open GGUF file '...' (No such file or directory)
E/DPS/llama: llama_model_load_from_file_impl: failed to load model
E/DPS/llama_jni: loadModel: llama_model_load_from_file failed
```

### What remains unverified

Actual GGUF inference — `generate()` producing tokens from a real model. That is
out of Day 03 scope by instruction and blocked regardless by the unprovisioned
model catalog. Every JNI entry point has been exercised on device; the inference
path itself has not.

---

## Risks

| Risk | Severity | Notes |
|---|---|---|
| ~~No runtime verification~~ | ~~High~~ | **RESOLVED** — 9/9 on-device tests pass, zero leak, no crashes. |
| GGUF execution never exercised | High | Out of Day 03 scope; also blocked by the unprovisioned model catalog. Every entry point is verified; inference itself is not. |
| `third_party/` not in version control | Medium | A submodule is the right answer, but `.gitmodules` lives at repo root and Day 03 is scoped to `android/`. Fresh clones need a documented manual step. |
| llama.cpp pinned to a rolling build tag | Medium | b10246 is a per-commit release, not an LTS. Upgrades will need API review — this Day alone hit two breaking changes. |
| Native build time | Low | ~4 min cold for 202 objects on 4 cores. |

---

## Remaining blockers

1. **Model catalog unprovisioned** — `sha256` and `downloadUrl` still empty;
   blocks any GGUF execution regardless of the native layer. This is now the
   single blocker standing between the runtime and real inference.
2. **`third_party/llama.cpp` acquisition** — needs a Product Owner decision on
   submodule vs. documented clone step.

*(The "no physical device" blocker is resolved — device `VNEW1535091002114`
verified on 2026-08-04.)*

---

## Architecture verification

- ✅ No Kotlin source modified — the Day 02 JNI interface is untouched
- ✅ `domain/` still free of `android.*` imports
- ✅ `domain/` still has no outward dependencies
- ✅ `ai/` still independent of `data/` and `ui/`
- ✅ `backend/` and `frontend/` untouched
- ✅ Offline-first preserved — the native runtime is fully on-device and the
  build performs no third-party downloads after the pinned checkout

One Day 02 build-config line changed, as instructed by the Day 03 brief:
`abiFilters` narrowed from `["arm64-v8a", "x86_64"]` to `["arm64-v8a"]`. The
consequence is that the app no longer runs on x86 emulators, which is consistent
with the physical-device-only test policy (ADR-009).
