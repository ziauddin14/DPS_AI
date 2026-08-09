# Technical Debt Register

**Project Falcon 🦅 · DPS Android Client**

Items accepted deliberately, with the conditions under which they must be paid
down. An entry here is a decision that was made knowingly — not a bug, and not
something to be discovered later by whoever trips over it.

---

## TD-001 — Model artifacts hosted on HuggingFace

| | |
|---|---|
| **Raised** | 2026-08-04 (Day 04) |
| **Severity** | **High** — blocks public release |
| **Status** | Open — accepted for development |
| **Approved by** | Product Owner, 2026-08-04 |
| **Owner** | Release engineering |

### What was accepted

`ModelCatalog` resolves download URLs against `huggingface.co`
(`ModelCatalogSource.ACTIVE`). Artifacts are fetched directly from a public
third-party repository.

### Why it is debt

The repository operator, not us, controls those files. They can be retagged,
moved, rate-limited or withdrawn at any time and without notice.

The failure mode is unusually bad: because verification is by SHA-256, a changed
upstream file does not silently corrupt anything — it fails the checksum. That
is the correct behaviour, but it means **every user who has not yet downloaded
the model is blocked simultaneously**, with no fix available short of shipping an
app update carrying a new hash. A product whose core promise is working offline
would be broken by an event on someone else's infrastructure.

Secondary concerns: no control over availability in the target market, no
bandwidth guarantees, and an external dependency in a product marketed on
data sovereignty.

### What "done" looks like

1. Host the artifacts on infrastructure under company control.
2. Change one line — `ModelCatalogSource.ACTIVE` — to
   `ModelSource.controlledMirror("https://…")`.
3. Leave every checksum untouched. They describe the artifact, not its host, and
   are precisely what proves the mirror serves identical bytes.
4. Confirm the mirror serves HTTP `Range` requests; without resume, a ~1 GB
   transfer over a mobile connection effectively never completes.
5. Verify licence compliance for redistribution — Qwen2.5 is Apache-2.0 and
   Phi-3 is MIT, both of which permit it subject to attribution.

### Why the fix is cheap

`ModelSource` was introduced on Day 04 specifically so this migration is a
constant change rather than a refactor. No runtime code — downloader, manager,
engine, UI — knows the source abstraction exists; they all receive a resolved
absolute URL exactly as they did on Day 02.

**Do not let this rot into a refactor by adding host-specific logic downstream.**

---

## TD-002 — Gated model families cannot be provisioned

| | |
|---|---|
| **Raised** | 2026-08-04 (Day 04) |
| **Severity** | Medium |
| **Status** | Open — blocked on Product Owner |

The Day 04 brief named five supported families. Runtime support for all five
shipped on Day 02 as `ChatTemplate` implementations. Provisioning is another
matter:

| Family | Repository | Blocker |
|---|---|---|
| Qwen | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | ✅ provisioned (Apache-2.0) |
| Phi | `microsoft/Phi-3-mini-4k-instruct-gguf` | ✅ provisioned (MIT) |
| Gemma | `google/gemma-2-2b-it-GGUF` | **`gated=manual`** — needs a HuggingFace account and manual acceptance of the Gemma licence |
| Llama | `meta-llama/Llama-3.2-1B-Instruct` | **`gated=manual`** — needs acceptance of the Llama 3.2 Community Licence |
| Mistral | `mistralai/Mistral-7B-Instruct-v0.3` | Ungated, but 7B far exceeds the device budget; no official small GGUF |

Gemma and Llama additionally carry **custom licences with redistribution
conditions**, which interacts directly with TD-001: mirroring them is not simply
a hosting change but a licensing question.

**Needed from the Product Owner:** whether these families are wanted at all, and
if so, a decision on accepting their licence terms.

---

## TD-003 — `third_party/llama.cpp` is not under version control

| | |
|---|---|
| **Raised** | 2026-08-04 (Day 03) |
| **Severity** | Medium |
| **Status** | Open — blocked on Product Owner |

llama.cpp is consumed as a git-ignored source checkout pinned to release
`b10246`. A git submodule is the right answer, but `.gitmodules` lives at the
repository root and Day 03 was scoped to `android/` only.

Consequence: a fresh clone does not build until the documented manual clone step
is run, and CI would need that step scripted.

---

## TD-004 — Model checksum is verified twice per session start

| | |
|---|---|
| **Raised** | 2026-08-04 (Day 04) |
| **Severity** | Low — pending measurement |
| **Status** | Open |

`DefaultAiEngine.initialize()` calls `ModelManager.resolveInstalled()`, and
`loadModel()` calls it again. Each call hashes the entire ~1 GB artifact, so
startup pays for two full SHA-256 passes where one would do.

The duplication is not accidental — verifying immediately before handing a file
to the native loader is the invariant that keeps a corrupt GGUF from reaching
`mmap` — but the first pass could cache its result against the file's size and
mtime.

Deliberately not optimised before measuring. Cache invalidation here is exactly
the kind of subtlety that quietly reintroduces the bug the check exists to
prevent, and on ARM with crypto extensions the cost may be small enough not to
matter. See the Day 04 performance report for the measured figure.
