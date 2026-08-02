# Architecture Reference

Project Falcon 🦅 · DPS Android Client · Day 02

Companion to `ADR-001-offline-ai-runtime-foundation.md`. The ADR records **why**
each decision was made; this document describes **what exists** and how the
pieces fit together.

---

## Layer model

```
┌──────────────────────────────────────────────────────────────┐
│  ui/            Compose · ChatViewModel · DpsTheme           │
│                 Knows: ai/ facade + domain models            │
├──────────────────────────────────────────────────────────────┤
│  ai/            AiSessionManager · DefaultAiEngine           │
│                 ConversationManager · PromptManager          │
│                 ResponseParser · ChatTemplate                │
│                 Knows: domain contracts only                 │
├──────────────────────────────────────────────────────────────┤
│  domain/        AiEngine · RuntimeProvider · ModelManager    │
│                 ModelDescriptor · ModelConfig · AiState      │
│                 ConversationState · RuntimeStatus            │
│                 PURE KOTLIN — zero android.* imports         │
├──────────────────────────────────────────────────────────────┤
│  data/          LlamaCppRuntimeProvider · OllamaRuntime…     │
│                 DefaultModelManager · ModelStorage           │
│                 ModelDownloader · ChecksumVerifier           │
│                 Implements domain interfaces                 │
├──────────────────────────────────────────────────────────────┤
│  core/          DpsResult · DpsError · DispatcherProvider    │
│                 DpsLogger — depends on no other layer        │
└──────────────────────────────────────────────────────────────┘

               di/AiContainer — composition root
        the only class that knows every concrete type
```

Dependency direction:

```
ui  →  ai  →  domain  ←  data
                ↑           │
                └───────────┘
```

`data` depends on `domain` and implements its interfaces — the arrow inverts, so
`domain` never points outward at an implementation.

---

## AI runtime flow

The complete path from a keystroke to a rendered token.

```
User types and presses Send
        │
        ▼
ChatViewModel.sendMessage(text)
        │
        ▼
AiSessionManager.sendMessage(text)
        │
        ├─→ guard: text non-empty
        ├─→ guard: no generation already in flight
        ├─→ guard: AiState is Ready
        │
        ▼
ConversationManager.appendUserMessage()          ← single writer
        │                                          StateFlow emits
        ▼
PromptManager.build(conversation, descriptor, config, tokenCounter)
        │
        ├─→ prepend system persona
        ├─→ count tokens via the loaded model's tokenizer
        ├─→ trim history newest-first to fit the budget
        │     · system prompt   — never dropped
        │     · newest user turn — never dropped
        │     · neither fits    → ContextOverflow, no truncation
        ├─→ ChatTemplate.render()  ← Qwen / Llama3 / Phi3 / Gemma / Mistral
        │
        ▼
CompletionRequest(prompt = fully rendered string)
        │
        ▼
ConversationManager.beginAssistantMessage()      ← empty bubble, Streaming
        │                                          so the UI is never frozen
        ▼
AiEngine.generate(request)
        │
        ▼  dispatchers.inference — parallelism limited to 1
RuntimeProvider.generate(request)
        │
        ├── LlamaCppRuntimeProvider ──→ JNI ──→ llama.cpp ──→ GGUF
        │      callbackFlow · awaitClose wired to native cancel
        │
        └── OllamaRuntimeProvider ────→ HTTP /api/generate (raw=true)
               NDJSON line stream                ↑
                                    raw=true bypasses Ollama templating,
                                    so both runtimes get identical prompts
        │
        ▼
Flow<CompletionChunk>
        │
        ├─ Token(text)     × N
        ├─ Completed(AiCompletion)   terminal
        └─ Failed(DpsError)          terminal — tokens already emitted survive
        │
        ▼
ResponseParser.parsePartial(rawSoFar, format)
        │
        ├─→ strip control tokens
        └─→ withhold trailing fragment that may be a partial control token
        │
        ▼
ConversationManager.appendToken(messageId, cleanedText)
        │
        ▼
StateFlow<ConversationState>
        │
        ▼
ChatViewModel.uiState  →  ChatScreen  →  Compose recomposition
```

---

## Class relationships

### The AI subsystem

```
                        ┌─────────────────┐
                        │  AiSessionManager│
                        │  (orchestrator)  │
                        └────────┬─────────┘
             ┌───────────────────┼───────────────────┬──────────────┐
             ▼                   ▼                   ▼              ▼
      ┌────────────┐   ┌──────────────────┐  ┌──────────────┐ ┌──────────┐
      │  AiEngine  │   │ConversationManager│  │PromptManager │ │Response  │
      │ «interface»│   │ (single writer)   │  │              │ │Parser    │
      └──────┬─────┘   └──────────────────┘  └──────┬───────┘ └──────────┘
             │                                       │
             │ implemented by                        │ uses
             ▼                                       ▼
      ┌────────────────┐                    ┌──────────────────────┐
      │DefaultAiEngine │                    │ ChatTemplateRegistry │
      └───┬────────┬───┘                    └──────────┬───────────┘
          │        │                                    │
          │        └───────────────┐        ┌───────────┴───────────┐
          ▼                        ▼        ▼                       ▼
  ┌──────────────┐        ┌─────────────────┐              ┌────────────────┐
  │ ModelManager │        │ RuntimeProvider │              │  ChatTemplate  │
  │ «interface»  │        │   «interface»   │              │  «interface»   │
  └──────┬───────┘        └────────┬────────┘              └───────┬────────┘
         │                          │                               │
         ▼                  ┌───────┴────────┐          Qwen · Llama3 · Phi3
 ┌───────────────────┐      ▼                ▼               Gemma · Mistral
 │DefaultModelManager│  ┌──────────┐  ┌─────────────┐            Plain
 └─┬───────┬───────┬─┘  │LlamaCpp  │  │   Ollama    │
   ▼       ▼       ▼    │Runtime   │  │  Runtime    │
Storage Downloader     └────┬─────┘  └─────────────┘
        Verifier            ▼
                    LlamaCppBridge (JNI)
```

### Key collaborations

| Relationship | Nature | Why it is shaped this way |
|---|---|---|
| `AiSessionManager` → everything | composition | The only class that sequences the pipeline; each stage stays ignorant of the others |
| `DefaultAiEngine` → `RuntimeProvider` | strategy | Runtime chosen at wiring time; no code above the seam knows which |
| `DefaultAiEngine` → `ModelManager` | delegation | Files and memory are separate concerns and meet only at `InstalledModel` |
| `PromptManager` → `ChatTemplate` | strategy | Model family selects the template; adding one changes no orchestration |
| `PromptManager` → `TokenCounter` | narrow interface | Breaks a cycle — the engine calls the prompt manager, so it cannot be its dependency |
| `ConversationManager` → nothing | leaf | A pure state container with one writer; no outward dependency to race with |
| `AiContainer` → all | composition root | The single place holding concrete types, and the only one branching on build type |

---

## State machines

### `AiState` — the AI subsystem

```
Idle
 └─ initialize() ──→ CheckingModel
                      ├─ verified model present ─→ Idle ─ loadModel() ─→ LoadingModel ─→ Ready
                      ├─ missing or corrupt ─────→ ModelRequired
                      │                              └─ user consents ─→ PreparingModel
                      │                                   ├─ Downloading(progress)
                      │                                   ├─ Verifying(progress)
                      │                                   └─ done ─→ LoadingModel ─→ Ready
                      └─ no runtime available ───→ Unavailable
```

There is deliberately **no `Generating` state**. Generation is a property of a
conversation, not of the subsystem — the engine stays `Ready` throughout, and
`ConversationState.isGenerating` carries it. Two sources of truth for one fact
could disagree.

### `SessionState` — the user's session

```
Inactive ─ startSession() ─→ Starting ─→ Active(modelId)
                                │            │
                                │            ├─ releaseMemory() ─→ Suspended
                                │            │                       └─ startSession() ─→ Active
                                │            └─ endSession() ──────→ Inactive
                                ├─ model absent ─→ AwaitingModel
                                └─ failure ──────→ Failed(error)
```

`Suspended` is distinct from `Inactive` on purpose: it means the system reclaimed
memory and the conversation survived, so the UI can offer one-tap resume rather
than presenting a cold start.

---

## Threading

| Dispatcher | Used for | Constraint |
|---|---|---|
| `main` | Compose state | UI thread |
| `default` | Parsing, formatting, pipeline sequencing | CPU-bound pool |
| `io` | Files, HTTP, downloads, checksums | Blocking IO pool |
| `inference` | Model load, unload, generation | **Parallelism limited to 1** |

`inference` is the non-obvious one. llama.cpp is already internally
multi-threaded, so the correct *external* concurrency is exactly one. Running
inference on `Dispatchers.IO` would admit several concurrent generations, each
spawning its own native worker threads — a thermal and memory failure on a
phone. Serialising it also enforces ADR-002's single-resident-model rule at the
scheduling level, where it cannot be bypassed.

---

## Extension points

Deliberately placed now, while they cost nothing:

| Point | Day | What lands there |
|---|---|---|
| `ResponseParser` | 03 | Tool-call extraction from the token stream |
| `RuntimeCapabilities.supportsGrammarConstraints` | 03 | GBNF-constrained sampling for valid JSON |
| `ChatMessage` | 03 | Optional tool-call payload |
| `PromptManager` system section | 03 | Tool definitions |
| Between system prompt and history | 04+ | Retrieved memory |
| `DispatcherProvider` | 05 | An `audio` dispatcher for speech |
| `ModelDescriptor` | any | New models — data only, no code change |

---

## Invariants

Break these and the failure mode is a native crash or a silent quality
regression — neither of which a test will catch after the fact.

1. **Never load an unverified model.** Only `ModelManager` produces
   `InstalledModel`, and only after SHA-256 passes.
2. **Never template a prompt below the runtime seam.** Both runtimes must
   receive byte-identical input.
3. **`ConversationManager` is the sole writer of `ConversationState`.**
4. **`domain` imports nothing from `android.*`.**
5. **Streaming is the primitive.** Derive blocking from it, never the reverse.
6. **Never branch on model identity.** A missing `ModelDescriptor` field is the
   real problem.
7. **Never log conversation content.** Logcat is off-device for practical
   purposes; use `redact()`.
