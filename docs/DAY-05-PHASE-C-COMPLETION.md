# Day 05 Phase C — Communication Layer

**Project Falcon 🦅 · DPS Android Client**
**Date:** 2026-08-06
**Branch:** `day-05-android-tool-foundation`
**Scope:** Contacts, WhatsApp confirmation flow, Gmail confirmation flow

---

## The central design decision

**No code path in DPS sends a message or an email.**

That is not a limitation of the approach taken — it is the approach. Both
communication tools *prepare* and hand the final action to the user:

```
AI: "Send a reminder to Abdul?"
  ↓  resolve the contact
  ↓  build the Intent
  ↓  WhatsApp / the email app opens, pre-filled
  ↓  THE USER presses send
```

`development_rule.md` Rule 8 and `PRD.md`'s Human Confirmation principle both
require confirmation before contacting anyone. The reasoning is simple: a
message sent on someone's behalf cannot be recalled. An assistant that is 99%
accurate about recipients is still one that will eventually send a private
message to the wrong person.

Handing over the final tap makes that **structurally impossible** — not by
policy, not by a check that a later refactor could remove, but because the
capability to send does not exist in this codebase.

Both tools accept `send_message` / `send_email` as operation aliases, because a
model asked to message someone will naturally emit those words and rejecting
them would make the assistant look broken. They map to the same preparation,
and the returned summary states plainly that the user must press send. The
honesty lives in what the tool *does* and *reports*, not in refusing a word.

---

## Architecture

```
User → LLM → Intent Parser → ToolRegistry → ToolExecutor
                                                 │
                    ┌────────────────────────────┼────────────────────────────┐
                    ▼                            ▼                            ▼
           AndroidContactsTool      PrepareWhatsAppMessageTool        PrepareEmailTool
                    │                            │                            │
                    └──────────┬─────────────────┴──────────────┬─────────────┘
                               ▼                                ▼
                    ContactRepository +               WhatsAppIntentBuilder /
                     ContactResolver                   EmailIntentBuilder
                               │                                │
                    AndroidContactRepository            IntentLauncher
                               │                                │
                        ContactsContract              PackageManager / startActivity
```

`ContactResolver` and `IntentLauncher` are **shared by all three tools**. One
resolver means the contacts tool, WhatsApp and email all agree on who "Abdul"
is — two tools disagreeing about that would mean a private message reaching
different people depending on which path the assistant took.

### Layer placement

| Component | Layer | Android? |
|---|---|---|
| `Contact`, `ContactMatch`, `MatchStrategy` | `domain/contact` | ❌ pure Kotlin |
| `ContactResolver` | `domain/contact` | ❌ **all matching logic is JVM-testable** |
| `ContactRepository` (contract) | `domain/contact` | ❌ |
| `AndroidContactRepository` | `data/android/contacts` | ✅ |
| `WhatsAppIntentBuilder`, `EmailIntentBuilder` | `data/android/intent` | ✅ construction only |
| `IntentLauncher` | `data/android/intent` | ✅ |
| Three tools | `data/android/tool` | ❌ no direct Android imports |

---

## Files Created (8)

| File | Purpose |
|---|---|
| `domain/contact/Contact.kt` | `Contact`, `ContactMatch`, `MatchStrategy` |
| `domain/contact/ContactResolver.kt` | Shared matching — exact / case-insensitive / startsWith / contains / phone |
| `domain/contact/ContactRepository.kt` | Read contract |
| `data/android/contacts/AndroidContactRepository.kt` | `ContactsContract` access |
| `data/android/intent/IntentBuilders.kt` | `WhatsAppIntentBuilder`, `EmailIntentBuilder` |
| `data/android/intent/IntentLauncher.kt` | Shared resolution and launching |
| `data/android/tool/AndroidContactsTool.kt` | Contacts tool |
| `data/android/tool/PrepareWhatsAppMessageTool.kt` | WhatsApp confirmation flow |
| `data/android/tool/PrepareEmailTool.kt` | Gmail confirmation flow |

**Tests:** `ContactResolverTest.kt`, `IntentBuilderLogicTest.kt` (JVM);
`CommunicationToolsInstrumentedTest.kt` (device).

## Files Modified (2)

- `AndroidManifest.xml` — `READ_CONTACTS`, `<queries>` element
- `di/AiContainer.kt` — wiring only

---

## Verification of Android APIs

Every API was read from official documentation before use.

| API | Source | Consequence |
|---|---|---|
| `ContactsContract.Contacts.CONTENT_FILTER_URI` + `Uri.withAppendedPath(base, Uri.encode(q))` | contacts-provider | Names contain spaces and `#`; raw concatenation would malform the URI |
| `CommonDataKinds.Phone.CONTENT_URI` / `.NUMBER` / `.CONTACT_ID` | contacts-provider | Phone numbers live in a separate table from identity |
| `PhoneLookup.CONTENT_FILTER_URI` | contacts-provider | Provider handles formatting differences a `LIKE` query would not |
| `Intent.ACTION_SENDTO` + `mailto:` | intents-common | **Only email apps respond.** `ACTION_SEND` also matches messaging and social apps |
| `EXTRA_EMAIL` / `EXTRA_SUBJECT` / `EXTRA_TEXT` | intents-common | Recipients as `String[]` |
| `resolveActivity` / `queryIntentActivities` before `startActivity` | intents-common | Avoids `ActivityNotFoundException` |
| `FLAG_ACTIVITY_NEW_TASK` | intents-common | Required from a non-Activity context |
| **`<queries>` element, API 30+** | package-visibility | **Most consequential finding — see below** |

### The finding that would have broken both tools

From **API 30**, package visibility filtering means `resolveActivity` and
`queryIntentActivities` return nothing for undeclared apps. With
`targetSdk = 35`, a perfectly installed WhatsApp would have resolved to null and
DPS would have reported it as *"not installed"* on every modern device — with no
error, no crash, and nothing in a log to suggest the manifest was the cause.

The manifest now declares both the specific packages and the two intent
signatures actually used. `QUERY_ALL_PACKAGES` is deliberately **not** requested:
it needs Google Play justification and would let DPS enumerate the user's entire
app list, which a privacy-first product has no business doing in order to open a
chat.

### Where documentation is vendor-supplied, not Android

The `https://wa.me/<number>?text=` format is published by **WhatsApp**, not by
Android. That is stated in the code rather than glossed over. The Android half
*is* documented — an `ACTION_VIEW` Intent with an `https` URI, availability
determined by `resolveActivity` rather than assumed. If WhatsApp ever stops
claiming these links, resolution returns empty and the tool reports the app as
unavailable: it degrades to an honest "can't do that" rather than to a crash.

---

## A real bug caught by testing

The first implementation compared phone numbers with a suffix test — if one
number ends with the other, treat them as equal. It failed immediately:

```
stored:  +923001234567   → 923001234567
queried: 0300-1234567    → 03001234567
```

Neither ends with the other, yet they are the same person. The trunk prefix `0`
**replaces** the country code `92` rather than being appended to it.

Fixed by comparing the trailing seven significant digits — country codes and
trunk prefixes live at the front, the subscriber number at the back. This is the
same approach the platform's own `PhoneNumberUtils.compare` takes; it is
reimplemented rather than called because `ContactResolver` is pure Kotlin and
JVM-testable by design.

The digit floor matters independently: without it `4567` would match any number
ending in those digits, and a private message would reach a stranger.

---

## Ambiguity is never resolved silently

If the user says "message Ali" and three contacts are named Ali, DPS **asks**.
It does not pick the first.

This is the single most important behaviour in Phase C. Choosing wrongly here is
not a degraded result — it sends someone's private words to a person they did
not choose, and it cannot be undone. `ContactMatch.Ambiguous` is therefore a
first-class outcome that forces the caller to disambiguate, and both messaging
tools return a failure listing the candidates rather than guessing.

Ranking prevents needless questions in the other direction: an exact match wins
outright over weaker ones, so "Abdul" resolves cleanly even when "Abdul Rahman"
also exists.

---

## Build Result

**BUILD SUCCESSFUL · 0 errors · 0 warnings**

## Test Results

| Suite | Result |
|---|---|
| `ContactResolverTest` (JVM) | ✅ 17/17 |
| `IntentBuilderLogicTest` (JVM) | ✅ 6/6 |
| `ToolArgumentsTest` (JVM) | ✅ 16/16 |
| `ToolFoundationTest` (JVM) | ✅ 22/22 |
| `ChatTemplateTest` (JVM) | ✅ 8/8 |
| **JVM total** | **69** |
| `CommunicationToolsInstrumentedTest` (Phase C) | ✅ **21/21** |
| `AndroidToolsInstrumentedTest` (Phase B) | ✅ **18/18** |
| `PermissionFoundationInstrumentedTest` (Phase A) | ✅ **14/14** |
| `LlamaCppBridgeInstrumentedTest` (Day 03) | ✅ **9/9** |
| `GgufInferenceInstrumentedTest` (Day 04) | ✅ **10/10** |
| **Device total** | **72** |
| **Grand total** | **141** |

No crashes, no `SIGSEGV`/`SIGABRT`/`SIGBUS`, no ANR, no tombstones.

### One Phase B test was updated, and why it is not a regression

`outOfScopeToolsRemainUnimplemented` asserted that CONTACTS, WHATSAPP and GMAIL
were unimplemented — true of Phase B's scope, and made false by Phase C
implementing all three. The assertion described a **scope boundary that
legitimately moved**, not behaviour that regressed.

It now asserts PHONE alone, which is genuinely still out of scope, plus the
invariant that every id is registered exactly once. A companion test,
`phaseBToolsRemainImplementedAfterLaterPhases`, was added to cover the risk that
actually matters: a later phase shadowing or replacing an earlier tool.

### On-device evidence

```
DPS/PhaseC:       whatsapp handlers=[com.whatsapp.w4b, com.android.chrome]
                  email handlers=[com.google.android.gm]
                  whatsappInstalled=false
DPS/ToolExecutor: Permissions missing for CONTACTS: [READ_CONTACTS]
DPS/ToolExecutor: Unsupported operation GMAIL.delete_everything
dumpsys package:  queriesIntents=[VIEW https, SENDTO mailto]
```

Three things this confirms:

1. **Package visibility is working.** Both intent signatures resolve to real
   handlers. Without the `<queries>` declaration both lists would be empty and
   DPS would report the apps as missing.

2. **A design decision validated by the device.** `whatsappInstalled=false`
   while `com.whatsapp.w4b` *is* a handler — this device has WhatsApp
   **Business**, not standard WhatsApp. Because the tool gates on
   `canHandle(intent)` rather than on a package-name check, it works correctly
   here. Had availability been decided by `isPackageInstalled("com.whatsapp")`,
   DPS would have wrongly told this user WhatsApp was not installed.

3. **The permission gate fires before dispatch**, and the executor declines
   unknown operations cleanly rather than reaching the tool.

### Why Intent construction is tested on device, not the JVM

`android.net.Uri` and `android.content.Intent` are **stubs** in the unit-test
classpath and throw `RuntimeException("Stub!")`. A JVM test of Intent
construction would verify nothing, or would require adding Robolectric — a
substantial dependency to simulate a framework a connected device already
provides. The framework-free helpers (number normalisation, address
plausibility) *are* JVM-tested; construction is instrumented.

### What the device tests deliberately do not do

**No test sends a message, sends an email, or launches WhatsApp.** Intent
construction and resolution are verified; launching is not, because a passing
suite that leaves apps open and half-composed messages on the developer's phone
is not an acceptable trade. The launch path itself is thin — one flag and
`startActivity` — and its decision-making half, resolution, *is* covered.

---

## Architecture Verification

| Rule | Result |
|---|---|
| `domain/` free of `android.*` | ✅ PASS |
| `domain/` no outward dependencies | ✅ PASS |
| `ai/` free of `android.*`, `data/`, `ui/` | ✅ PASS |
| `ui/` performs no Android capability calls | ✅ PASS |
| No duplicate tool ids | ✅ all 10 registered exactly once |
| No duplicated platform logic | ✅ one owner per API |

| Platform API | Sole owner |
|---|---|
| `ContactsContract` | `AndroidContactRepository` |
| `PackageManager` / `startActivity` | `IntentLauncher` |
| `AlarmManager` | `ReminderScheduler` |
| `CalendarContract` | `CalendarWriter` |
| Notifications | `NotificationPresenter` |
| Permission checks | `AndroidPermissionManager` |

---

## Performance

Contact reads are two-stage — identity from the filter URI, then phone and email
per contact — so a search costs `1 + 2n` provider queries for `n` results.
Results are capped at 10 by default, so the worst case is 21 queries, each
disk-backed and measured in single-digit milliseconds. All run on
`dispatchers.io`, never the single-threaded inference dispatcher, so a contact
lookup cannot queue behind model generation.

Only id, display name, phone numbers and email addresses are read. Postal
addresses, photos, notes and organisations are never queried — the cheapest way
to keep personal data safe is not to read it.

---

## Known Risks

| Risk | Severity | Notes |
|---|---|---|
| **WhatsApp URL format is vendor-published** | Medium | `wa.me` is WhatsApp's, not Android's. Degrades to "not installed" if withdrawn, never to a crash |
| **Background activity starts are restricted on Android 10+** | Medium | DPS launches while the user is conversing, so the app is foreground — but the restriction is silent when it applies. `LaunchOutcome.Failed` is returned rather than assuming success |
| Launch path not exercised on device | Medium | Deliberate; see above |
| Contacts read costs `1+2n` queries | Low | Capped at 10 results |
| Phone matching uses 7 trailing digits | Low | Platform convention; a coincidental match is ~1 in 10⁷ |
| Email validation is permissive | Low | Strict RFC 5322 rejects legal addresses; the email app validates anyway |
| **Reminders still do not survive reboot** | High | Carried from Phase B — unchanged, still open |

---

## Not Implemented

Phone calls, SMS, attachments, and any form of automatic sending. Phone remains
a declared-but-unimplemented tool.
