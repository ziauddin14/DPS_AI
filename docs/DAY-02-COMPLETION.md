# Day 02 — Offline AI Runtime Foundation · COMPLETE

**Project Falcon 🦅 · DPS Android Client**
**Completed:** 2026-08-02
**Status:** ✅ Verified by compilation and test execution

---

## Scope delivered

The AI substrate every later feature builds on. Design and code, verified on real
toolchain — not asserted.

| Phase | Deliverable | Status |
|---|---|---|
| A | ADR — 9 decisions, options compared, tradeoffs stated | ✅ |
| B | Production Android project, Clean Architecture, single module | ✅ |
| C | `AiEngine`, `ModelManager`, `AiSessionManager`, `ConversationManager`, `PromptManager`, `RuntimeProvider`, `ModelConfig`, `AiState`, `ConversationState`, `RuntimeStatus` | ✅ |
| D | Dual runtime: llama.cpp (JNI, production) + Ollama (HTTP, development) | ✅ |
| E | Model lifecycle: catalog, preflight, resumable download, SHA-256 gate, storage stats | ✅ |
| F | Session lifecycle: start, end, reset, memory release, runtime health, streaming | ✅ |
| G | Conversation pipeline, single-writer state, cancellable | ✅ |
| H | KDoc on every significant class; ADR + ARCHITECTURE reference | ✅ |

**Deliberately excluded** (later days): secretary logic, tool calling, memory,
calendar, notifications, reminders, voice, contacts, dashboard, sync.

---

## Verification

| Check | Result |
|---|---|
| `:app:assembleDebug` | ✅ `app-debug.apk` — 9.92 MB |
| `:app:assembleRelease` (R8 + ProGuard) | ✅ `app-release-unsigned.apk` — 1.1 MB |
| `:app:testDebugUnitTest` | ✅ 8/8 passed, 0 failures, 0.163 s |
| Kotlin errors / warnings | 0 / 0 |
| `domain/` free of `android.*` | ✅ verified |
| Layer dependency direction | ✅ verified |
| JNI bridge survives R8 obfuscation | ✅ confirmed in `mapping.txt` |
| Offline-First enforced by build | ✅ release `OLLAMA_BASE_URL = ""` |
| `backend/` + `frontend/` untouched | ✅ verified by mtime |

---

## Toolchain (verified)

| Tool | Version |
|---|---|
| Android Studio | AI-261.26222.65.2613.15948027 |
| JDK | Microsoft OpenJDK 17.0.20+8-LTS |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 |
| Build-Tools | 35.0.0 (pinned) |
| Platform-Tools | 37.0.1 |
| Command-line Tools | 22.0 |
| SDK location | `D:\AndroidSDK` |

Not installed by design: emulator, system images, NDK, CMake, Ollama.

---

## Defects found and fixed during verification

1. **`app/build.gradle.kts`** — `kotlin { compilerOptions }` nested inside
   `android { }`, where that extension does not exist. Moved to module level.
2. **`OllamaRuntimeProvider.kt`** — `json.encodeToString(payload)` resolved to
   the member overload rather than the reified extension. Now passes the
   serializer explicitly, which survives import reorganisation.
3. **`Theme.kt`** — deprecated `window.statusBarColor`, a no-op under
   `enableEdgeToEdge()`. Removed.
4. **Build-tools drift** — AGP silently downloaded 34.0.0 when
   `buildToolsVersion` was unset. Now pinned to 35.0.0 for reproducibility.

---

## Open blockers carried into Day 03

1. **Model catalog unprovisioned — blocks all AI execution.**
   `ModelCatalog.sha256` and `downloadUrl` are empty and `canInstall` refuses.
   A fabricated hash was deliberately not used: it would either fail with a
   confusing mismatch or invite someone to weaken verification. Needs a hosting
   decision plus the artifact's published SHA-256 and exact byte size.
2. **Ollama not installed** — no runtime can generate text yet.
3. **llama.cpp native library absent** — Day 03 scope; requires NDK + CMake,
   which need Product Owner approval before installation.
4. **No physical device connected** — `adb devices` untested.

---

## Day 03 readiness

Ready. The seams Day 03 needs exist and are proven:

- `ResponseParser` — where tool-call extraction lands
- `RuntimeCapabilities.supportsGrammarConstraints` — where GBNF switches on
- `LlamaCppBridge` — Kotlin contract fixed; R8 keep rule confirmed working

Day 03 requires explicit approval to install NDK and CMake.
