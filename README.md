# DPS Android — Offline AI Executive Secretary

Project Falcon 🦅 · Day 02 — Offline AI Runtime Foundation

The Android client of the DPS ecosystem. A **new client**, not a replacement:
the production web platform at `dps-ai.vercel.app` and
`dps-backend-pvr8.onrender.com` is untouched and stays fully operational
(Development Rules 1 and 12).

---

## Build status — verified 2026-08-02

| Check | Result |
|---|---|
| `:app:assembleDebug` | ✅ SUCCESS — `app-debug.apk`, 9.92 MB |
| `:app:assembleRelease` (R8 + ProGuard) | ✅ SUCCESS |
| `:app:testDebugUnitTest` | ✅ 8/8 passed, 0 failures |
| Kotlin compiler errors | 0 |
| Kotlin compiler warnings | 0 |
| Layer boundaries | ✅ verified — no `android.*` in `domain/` |

Verified toolchain: JDK 17.0.20, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0,
compileSdk 35, build-tools 35.0.0 (pinned), platform-tools 37.0.1.

---

## What exists

The substrate every later feature builds on:

| Area | Delivered |
|---|---|
| AI runtime abstraction | `RuntimeProvider` with two implementations |
| Production runtime | llama.cpp via JNI (Kotlin side complete; native lib is Day 03) |
| Development runtime | Ollama over HTTP, debug builds only |
| Model lifecycle | Catalog, preflight, resumable download, SHA-256 verification, storage stats |
| Prompt system | Five chat-template families, swappable as data |
| Conversation pipeline | Streaming, single-writer state, cancellable |
| Session management | Start, end, reset, memory release, health |
| Error model | Sealed `DpsError` hierarchy, no exceptions for expected failure |

Deliberately **not** built (later days): secretary logic, tool calling, memory,
calendar, notifications, reminders, voice, contacts, dashboard, sync.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Microsoft OpenJDK 17.0.20 |
| Android Studio | 2026.1.3.7 | Bundles Gradle; generates the wrapper |
| Android SDK | API 35 | Platform, platform-tools, build-tools |
| Ollama | 0.32.5 | Development runtime only |
| Physical Android device | API 26+, 64-bit, ≥ 3 GB RAM | See "Why not the emulator" |

**Not installed, and not needed until Day 03:** Android NDK, CMake. They are
required only for the llama.cpp native build.

---

## First-time setup

### 1. Generate the Gradle wrapper

`gradle-wrapper.jar` and the `gradlew` scripts are binaries that cannot be
authored by hand. Open `android/` in Android Studio — it regenerates them on
first sync — or run:

```bash
gradle wrapper --gradle-version 8.11.1
```

Commit the generated files afterwards.

### 2. Point Gradle at the SDK

Create `android/local.properties` (git-ignored):

```properties
sdk.dir=D\:\\AndroidSDK
```

### 3. Build

```bash
./gradlew :app:assembleDebug
```

### 4. Run the JVM unit tests

These need no emulator and no device — the point of keeping `domain` pure:

```bash
./gradlew :app:testDebugUnitTest
```

---

## Development runtime (Ollama)

The native llama.cpp library does not exist yet, so the app falls back to Ollama
in debug builds. On the development machine:

```bash
ollama pull qwen2.5:1.5b-instruct-q4_K_M
```

Then bridge the device to the host over USB:

```bash
adb reverse tcp:11434 tcp:11434
```

The phone's `localhost:11434` now reaches the laptop's Ollama over the USB
cable — no shared Wi-Fi, no LAN exposure.

Release builds compile `OLLAMA_BASE_URL` to an empty string, so this runtime can
never be selected in a shipping build.

---

## Why not the emulator

The development machine has **7.8 GB RAM**. Android Studio (~3 GB) plus a Gradle
daemon plus an emulator (~2.5 GB) plus Ollama serving a 1.5B model (~1.5 GB)
exceeds physical memory and swaps.

The stronger reason is measurement. Every MVP KPI — cold start < 7 s, response
< 2 s, RAM < 3 GB, battery impact — is a property of ARM hardware under real
thermal limits. An x86 emulator backed by a desktop CPU does not merely measure
these imprecisely; it measures them *wrongly*, in the optimistic direction.

See `docs/ADR-001-offline-ai-runtime-foundation.md`, ADR-009.

---

## ⚠ Model provisioning required

**No model can be installed yet.** `ModelCatalog` carries an empty `sha256`, and
`ModelManager.canInstall` refuses any artifact whose integrity cannot be proven.

This is intentional. Loading a corrupted GGUF crashes the *native* runtime — a
process kill that no Kotlin `try/catch` can intercept — so an unverifiable model
must be unusable rather than merely unverified. A plausible-looking placeholder
hash would be worse than none: it would either fail with a confusing mismatch or
tempt someone to "fix" the failure by weakening the check.

To provision, download the artifact once and record its real values:

```bash
sha256sum qwen2.5-1.5b-instruct-q4_k_m.gguf
stat -c %s qwen2.5-1.5b-instruct-q4_k_m.gguf
```

Set `sha256`, `sizeBytes` and `downloadUrl` in `ModelCatalog` together — they
describe one artifact and must never be updated independently.

Hosting is an open decision. A controlled mirror is safer than pulling directly
from an upstream repository, which can retag or remove a file and strand every
user at once.

---

## Structure

```
android/
├── docs/                  ADR
└── app/src/main/java/com/softwaremine/dps/
    ├── core/              Result, errors, dispatchers, logging
    ├── domain/            Pure Kotlin contracts — no android.* imports
    ├── ai/                Engine, session, conversation, prompt, parser
    ├── data/              Runtime providers, model storage/download/verify
    ├── di/                AiContainer — the composition root
    └── ui/                Compose surface
```

Dependency rule, strictly one-directional:

```
ui  →  ai  →  domain  ←  data
                ↑          │
                └──────────┘
```

`domain` has zero Android imports, which is what makes the AI layer testable on
the JVM. Keep it that way — it is the layer meant to outlive the platform.

---

## Non-negotiables

Carried from `CLAUDE_CONTEXT.md` and `development_rule.md`:

1. **Never modify** `backend/`, `frontend/`, schemas, tool registry, or
   production APIs. They are read-only from here.
2. **Offline first.** Core AI works with no network. Release builds contain no
   network runtime.
3. **Privacy first.** No cloud backup, no device transfer, app-private storage,
   never log conversation content.
4. **Never load an unverified model.** SHA-256 before every load.
5. **Streaming is the primitive.** Blocking calls are derived from streams, not
   the reverse.
6. **Swapping a model edits data, not code.** If you need
   `if (model.id == …)`, a field is missing from `ModelDescriptor`.

---

## Next: Day 03

Tool calling. The seams are already in place — `ResponseParser` is where
tool-call extraction lands, and `RuntimeCapabilities.supportsGrammarConstraints`
is where GBNF-constrained sampling gets switched on.
