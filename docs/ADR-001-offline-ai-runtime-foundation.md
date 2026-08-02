# ADR — Offline AI Runtime Foundation

**Project:** Falcon 🦅 — DPS Android Client
**Day:** 02
**Status:** ACCEPTED — Product Owner approved 2026-08-02
**Date:** 2026-08-02
**Author:** Lead AI Full Stack Engineer

---

## Context

The DPS web platform (React + Express + MongoDB + Groq native function calling) is
production-stable and **read-only** for this workstream. The Android client is a *new
client* of the DPS ecosystem, not a replacement (Development Rule 1, 12).

Day 02 builds only the **offline AI runtime foundation** — the substrate every later
feature (tool calling, voice, reminders, secretary logic) will sit on. No secretary
logic, no tools, no Android integrations.

The governing constraint is not the model. It is this, from `offliceLLM_guide.md`:

> The AI Model is not the product. The Executive Workflow built around the AI is the product.

So the foundation's job is to make the model **replaceable** and the workflow layer
**stable**. Every decision below is judged against: *will this still be maintainable in
five years, after the model, the runtime, and the Android APIs have all changed?*

### Measured environment (2026-08-02)

| Resource | Value | Impact |
|---|---|---|
| Dev machine RAM | 7.8 GB | Cannot run Studio + emulator + Ollama concurrently |
| Dev CPU | i5-1035G1, 4C/8T @ 1.0 GHz | Slow Gradle builds; no GPU offload |
| Toolchain | none installed | Nothing verified/compiled yet |

This is recorded because it directly drives ADR-002 and ADR-009.

---

## ADR-001 — Offline AI Runtime

**Decision: `llama.cpp` via JNI for production; Ollama over HTTP for development. Both
behind one `RuntimeProvider` interface.**

### Options considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **llama.cpp (JNI)** | Fastest mature on-device GGUF runtime; ARM NEON/i8mm optimised; full control over context, KV cache, threads; no extra process; largest community | Requires NDK + CMake; JNI boundary is C++ we must maintain; ABI/size cost (~2–4 MB per ABI) | ✅ **Production** |
| **Ollama (HTTP)** | Zero native build; instant iteration; identical GGUF weights; trivially swappable models | It is a *desktop server* — not shippable inside an APK; needs network; wrong privacy posture | ✅ **Development only** |
| MLC LLM | Excellent GPU (Vulkan) throughput | Model must be pre-compiled per architecture; brittle toolchain; small community; weak GGUF story | ❌ |
| MediaPipe LLM Inference | Google-supported, simple API | Narrow model support; `.task` bundles not GGUF; little control over sampling/context | ❌ Re-evaluate at Phase 3 |
| Cloud API (Groq, as web DPS does) | Zero device cost, strongest model | Violates Offline First and Privacy First — the entire product thesis | ❌ Non-negotiable |

### Why

`offliceLLM_guide.md` already locks this ("Development: Ollama. Production: llama.cpp.
Reason: most stable, fast, GGUF support, Android support, large community"). This ADR
confirms the choice and adds the *structural* consequence the doc left implicit:

**Because we ship two runtimes, the abstraction is not optional — it is forced by Day 02.**
That is a gift. A codebase with one runtime grows accidental coupling to it. Having two
from the first commit means the seam is real and continuously exercised, not theoretical.

### Tradeoffs accepted

- Dev and prod runtimes differ, so behaviour can diverge (sampling defaults, tokenizer
  edge cases, stop-sequence handling). **Mitigation:** the seam is narrow — `load`,
  `unload`, `generate`, `tokenCount`, `health`. Every prompt is built *above* the seam by
  `PromptManager`, so both runtimes receive byte-identical prompts. Divergence is
  detectable by diffing outputs for a fixed prompt set.
- JNI is our maintenance burden. Accepted: it is ~200 lines of C++ that changes rarely.

---

## ADR-002 — Memory Management

**Decision: exactly one model resident at a time; explicit, reference-counted lifecycle;
unload on sustained background.**

The device budget is < 3 GB RAM (`offliceLLM_guide.md`). Qwen2.5-1.5B at Q4_K_M is ~1.1 GB
of weights plus KV cache scaling with context. At 8 K context this lands near 2 GB.

Rules encoded in the foundation:

1. **Single-slot residency.** `AiEngine` holds at most one loaded model. Loading a second
   unloads the first. No LRU pool — with a 2 GB working set, a pool is a crash generator.
2. **Explicit lifecycle, never GC-driven.** `load()` / `unload()` are suspending and
   deterministic. Native memory is invisible to the JVM heap, so the garbage collector
   will happily let the app get OOM-killed while reporting a healthy heap. Anything
   holding native memory implements a `close()` contract.
3. **Lazy load.** The model is *not* loaded at process start. It loads on first AI use.
   This protects cold-start KPI (< 7 s) and battery.
4. **Unload on background pressure.** On `onTrimMemory(TRIM_MEMORY_BACKGROUND)` or higher,
   the engine unloads. Day 04's foreground service will later override this while a
   session is genuinely active — the hook exists now, the policy is pluggable.
5. **Context is bounded, not unbounded.** Conversation history is trimmed to fit the
   context window *before* it reaches the runtime. Never send the whole database
   (`offliceLLM_guide.md` memory strategy).

**Tradeoff:** lazy loading means the *first* message of a session pays a 5–8 s model-load
cost. Accepted — it is a one-time cost per session, and it is the honest place to show a
"waking up" state rather than delaying app launch for every user who only wanted to read
their dashboard.

---

## ADR-003 — Model Loading & Distribution Strategy

**Decision: download-on-first-launch to app-private storage. The GGUF is never in the APK
and never in Git.**

| Option | Verdict |
|---|---|
| Bundle GGUF in APK | ❌ 1.5 GB APK. Play Store hard limit is far below this. Every model update forces a full app update. |
| Play Asset Delivery | ⚠️ Viable later. Caps out around 1.5 GB for install-time delivery and couples model versioning to app releases. Revisit at launch. |
| **Runtime download + checksum** | ✅ APK stays ~150 MB. Model versions independently. Matches `offliceLLM_guide.md` exactly. |
| Git LFS | ❌ Never. Weights do not belong in version control at any size. |

Flow, per the locked guide: *open app → internet available → download → verify checksum →
store securely → offline forever.* If already present, skip.

Storage target is `Context.filesDir` (app-private, auto-encrypted at rest on modern
Android, removed on uninstall, requires no permission). **Not** external storage — that is
world-readable and violates Privacy First.

Integrity is non-negotiable: a truncated or bit-rotted GGUF causes a **native crash**, not
a Kotlin exception. So SHA-256 verification is a gate before every load, not just after
download. Downloads land in a `.part` file and are atomically renamed only after the
checksum passes, which makes a half-download impossible to mistake for a valid model.

Resume uses HTTP `Range` requests — mandatory, because 1.5 GB over a Pakistani mobile
connection will be interrupted.

---

## ADR-004 — AI Runtime Abstraction (model swappability)

**Decision: swapping a model must change *data*, not *code*. Two seams achieve this.**

The brief requires that Qwen → Llama → Phi → Gemma → Mistral require only a provider
change. A naive `RuntimeProvider` interface does **not** deliver that, because models
differ in a second, sneakier way: **chat template format**.

```
Qwen2.5   <|im_start|>system\n...<|im_end|>
Llama 3   <|start_header_id|>system<|end_header_id|>\n...<|eot_id|>
Phi-3     <|system|>\n...<|end|>
Gemma     <start_of_turn>user\n...<end_of_turn>
Mistral   [INST] ... [/INST]
```

Get this wrong and the model still answers — just measurably worse. That is the most
expensive class of bug, because it looks like "the model is bad" rather than "we formatted
the prompt wrong." So the foundation encodes it explicitly:

**Seam 1 — `RuntimeProvider`**: *how* tokens are produced (Ollama HTTP vs llama.cpp JNI).
**Seam 2 — `PromptFormat`**: *how* a conversation becomes a prompt string.

`ModelDescriptor` carries its own `PromptFormat` and stop sequences as data. Adding
Mistral is then: one new `ModelDescriptor` entry in the catalog + one `PromptFormat` case.
Zero changes to `AiEngine`, `AiSessionManager`, `ConversationManager`, or any ViewModel.

**Streaming is the primitive, not an add-on.** `generate()` returns `Flow<CompletionChunk>`.
A non-streaming call is `flow.toList()`; the reverse is impossible to retrofit cleanly.
Phase F asks for "future streaming support" — building it non-streaming first would
guarantee a rewrite, so the foundation is streaming from commit one.

---

## ADR-005 — Android Folder Structure

**Decision: one Gradle module (`:app`) whose package boundaries are shaped exactly like
the future module split.**

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Single module, flat packages | Fastest to start | Rots into a ball of mud | ❌ |
| **Single module, layer-shaped packages** | Fast builds now; split later is mechanical (move folder, add `build.gradle.kts`) | Boundaries enforced by review, not the compiler | ✅ |
| Multi-module now (`:core`, `:domain`, `:data`, `:ai`, `:app`, `:feature-chat`) | Compiler-enforced boundaries; parallel builds | 6 build scripts to maintain; **on a 7.8 GB / 4-core machine, configuration overhead exceeds the parallelism win**; slows a 7-day MVP | ⚠️ Deferred |

**Product Owner decision (2026-08-02):** single module confirmed. An earlier
written instruction had specified the six-module split; when the contradiction
was raised the Product Owner selected single-module. The package layout below is
shaped so that split remains mechanical — move a folder, add a build script — if
build times or team size later justify it.

The dependency rule is strict and one-directional regardless of module count:

```
ui  →  ai  →  domain  ←  data
                 ↑          │
                 └──────────┘
              core (no dependencies on anything)
```

- `domain` is **pure Kotlin** — zero `android.*` imports. This is the layer that must
  survive five years, so it is deliberately unable to depend on a platform that changes
  annually. It is also what makes the AI layer unit-testable on the JVM with no emulator,
  which matters enormously given the hardware measured above.
- `data` depends on `domain` (implements its interfaces) and inverts the arrow.
- `ai` orchestrates but knows nothing about *which* runtime is behind the interface.
- `ui` depends only on `ai` + `domain` models.

---

## ADR-006 — Dependency Injection

**Decision: constructor injection everywhere; a single hand-written composition root
(`AiContainer`). No DI framework yet.**

The brief says "Dependency Injection **Ready**" — not "DI framework installed". That
wording is correct and worth honouring literally.

| Option | Verdict |
|---|---|
| Hilt | ⚠️ Later. Adds KSP annotation processing to every build — a real cost on a 4-core 1 GHz CPU — for a graph currently ~12 objects deep. |
| Koin | ❌ Runtime resolution; failures surface at runtime, not compile time. |
| **Manual composition root** | ✅ Zero build cost, zero dependency, fully explicit, trivially testable. |

Because every class takes its collaborators as constructor parameters, adopting Hilt later
is purely additive: annotate the container's factory methods, delete the manual wiring.
No class under `ai/`, `domain/` or `data/` changes. Choosing manual DI now costs nothing
and forecloses nothing.

---

## ADR-007 — Conversation Pipeline

**Decision: unidirectional, streaming, single-writer state.**

```
User input
   ↓
ConversationManager.appendUser()      ← single writer of ConversationState
   ↓
PromptManager.build(state, descriptor) ← applies PromptFormat + context trimming
   ↓
AiEngine.generate() → RuntimeProvider  ← Flow<CompletionChunk>
   ↓
ResponseParser                         ← cleans tokens; Day 03 adds tool-call extraction
   ↓
ConversationManager.appendAssistant()  ← streams into the message in place
   ↓
StateFlow<ConversationState> → ViewModel → Compose
```

`ConversationManager` owns `ConversationState` and is the **only** writer. Everything else
reads a `StateFlow`. This is what makes the pipeline debuggable — every state change has
exactly one origin.

`ResponseParser` exists on Day 02 despite having almost nothing to do (it trims and strips
stop tokens). This is deliberate: it is the designated seam where Day 03's tool-call
extraction lands. Placing it now costs ~30 lines and prevents Day 03 from having to
retrofit parsing into the middle of a working stream.

Per the brief: **no memory layer, no secretary logic.** `PromptManager` today applies a
persona and a formatting contract only.

---

## ADR-008 — Error Model

**Decision: `DpsResult<T>` + a sealed `DpsError` hierarchy. Exceptions are for bugs.**

On-device AI fails in ways cloud AI does not, and every one of them is a *normal*
condition the UI must handle gracefully rather than crash on:

- model not installed / corrupted / checksum mismatch
- insufficient storage or RAM
- native library missing for this ABI
- context window overflow
- runtime not loaded

Modelling these as a sealed hierarchy makes the compiler enforce that the UI handles each.
Reserving exceptions for genuine programming errors keeps the two categories from blurring.

---

## ADR-009 — Development Environment (hardware-driven)

**Decision: physical device over USB as the primary test target. Emulator is a fallback.**

Measured: 7.8 GB RAM. Android Studio (~3 GB) + Gradle daemon (~2 GB) + emulator (~2.5 GB)
+ Ollama serving a 1.5 B model (~1.5 GB) exceeds physical memory and will swap.

Beyond memory, an x86 emulator **cannot honestly measure any of the MVP KPIs**: cold start
< 7 s, response < 2 s, RAM < 3 GB, and battery impact are all properties of ARM hardware
with real thermal limits. Testing LLM inference on a desktop-backed emulator produces
numbers that are not merely imprecise but actively misleading.

Consequences: Gradle heap capped at 2 GB; `org.gradle.parallel` retained but workers
limited; ADB over USB is the primary loop.

---

## Consequences

**Positive**
- Model, runtime, and prompt format are all swappable as data.
- `domain` + `ai` are JVM-unit-testable with no emulator — decisive on this hardware.
- Streaming, error handling, and the tool-call seam exist before anything depends on them.
- Migration to multi-module or Hilt is mechanical and additive.

**Negative / accepted**
- Two runtimes means two code paths to keep behaviourally aligned.
- Manual DI wiring grows by hand until Hilt is adopted.
- Package-level boundaries rely on review discipline, not the compiler.

**Deferred to a later day (deliberately)**
- Memory layer (short/long-term) — Day 04+
- Tool calling — Day 03
- Voice, reminders, calendar, notifications — Day 04+
- Model quantisation experiments beyond Q4_K_M
- Play Asset Delivery evaluation

---

## Resolved by the Product Owner — 2026-08-02

| Question | Decision |
|---|---|
| `applicationId` | **`com.softwaremine.dps`** — company namespace, permanent. "Falcon" stays an internal codename and does not appear in the package. |
| Module strategy | **Single module** with layer-shaped packages (see ADR-005). |
| Test target | **Physical device over USB.** No emulator, no AVD, no system images. |
| Toolchain | JDK 17, Android Studio, SDK, platform-tools, build-tools, API 35, Ollama. **NDK and CMake explicitly deferred to Day 03.** |
| SDK location | `D:\AndroidSDK` |

## Open questions remaining

1. **Model hosting and integrity metadata — blocking model installation.**
   `ModelCatalog` carries an empty `sha256` and an empty `downloadUrl`, and
   `canInstall` refuses any artifact whose integrity cannot be proven. Nothing
   can be installed until both are provisioned. Two decisions are needed:
   *where* the artifact is hosted (a controlled mirror is safer than pulling
   directly from an upstream repository, which can retag or remove a file and
   strand every user at once), and the *published SHA-256 and exact byte size*
   of the artifact that will ship.

2. **`minSdk` 26** — proposed and implemented (Android 8.0, ~98% device reach).
   llama.cpp requires 64-bit ABIs, which are universal at this level. Raising to
   28 would simplify a few storage APIs. Confirm 26 stands.

3. **Native ABI set** — currently `arm64-v8a` and `x86_64`. `armeabi-v7a` is
   deliberately excluded: 32-bit devices cannot address the working set a 1.5B
   model needs, so shipping it would produce installs guaranteed to OOM.
   Confirm this exclusion is acceptable for the target market.

---

*Supersedes nothing. Superseded by nothing. Amend rather than rewrite; ADRs are a
historical record of why, not a description of what currently is.*
