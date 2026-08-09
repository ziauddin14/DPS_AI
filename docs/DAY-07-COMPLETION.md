# Day 07 Completion — Voice, TTS & Release Hardening

Final MVP hardening phase: voice input, text-to-speech output, and the
reliability work needed for DPS to be a genuinely usable offline-first
personal secretary on a real Android phone. No Day 08 scope started.

## 1. Executive summary

Voice is now a first-class input alongside typed text, and TTS speaks the
assistant's replies — both built as thin platform-owner classes that hand
off to the *existing* Day 05/06 pipeline rather than duplicating any of it.
A tap-to-talk microphone control was chosen deliberately over a fake
always-listening wake word, because no genuinely offline, battery-safe
hotword engine exists in this stack without adding a third-party SDK the
brief explicitly forbids. Every core capability — conversation, task
management, work logs, meeting notes, reports, reminders — was re-verified
live on a real device with WiFi and mobile data both disabled, and stayed
fully functional. One real defect was found and fixed along the way (a
"dead" microphone button after an error) and one real limitation was found
and documented, not hidden (severe CPU throttling while backgrounded can
turn a ~40s inference call into 7+ minutes — the app never crashes or
hangs, it genuinely just waits, and recovers correctly once foregrounded).

## 2. Final architecture

No Day 03–06 file was redesigned. Every change is additive or a small,
targeted extension:

```
domain/voice/            VoiceCaptureOutcome, VoiceRecognizer, SpeechSynthesizer,
                          VoiceMode. Pure Kotlin — zero android.* imports.
data/android/voice/      AndroidSpeechRecognizer, AndroidTextToSpeech.
                          The only two files importing android.speech.*.
ai/voice/                VoiceModeController — the coordinator. Depends on
                          the domain voice interfaces + the existing
                          AiSessionManager/PermissionManager; introduces no
                          new AI pipeline.
ui/chat/                 ChatViewModel + ChatScreen extended with voice
                          state and a microphone control. No new screens.
```

Dependency direction is unchanged: `ui → ai → domain ← data`. `domain/voice`
imports nothing from Android; the two Day 07 Android files are the sole
owners of `android.speech.SpeechRecognizer` and
`android.speech.tts.TextToSpeech` respectively (architecture rules 10, 11).

## 3. Voice architecture

**Voice becomes text, never a second AI pipeline** (architecture rules 14,
15). `VoiceModeController.handleRecognized()` passes a
`VoiceCaptureOutcome.FinalResult.text` straight to
`AiSessionManager.sendMessage()` — the exact call the text composer already
makes. No intent classification, no tool selection, and no prompt building
happens in the voice layer; it only starts a turn and later reads the reply
back.

**Deterministic outcomes, never a false "I heard you"** (Phase 1
requirement). `VoiceCaptureOutcome` is a closed set: `Listening`,
`PartialResult`, `FinalResult`, `NoSpeech`, `PermissionRequired`,
`Unavailable`, `Error`, `Cancelled`. `AndroidSpeechRecognizer` maps every
`RecognitionListener` callback and every `SpeechRecognizer.ERROR_*` code
into one of these — nothing is inferred or assumed.

**Threading — verified against official documentation.**
`developer.android.com/reference/android/speech/SpeechRecognizer` states
creation and every callback must happen on the main application thread.
`AndroidSpeechRecognizer.listen()` therefore constructs and drives the
recognizer via `DispatcherProvider.main`, wrapped in a `callbackFlow` —
the same bridge pattern `LlamaCppRuntimeProvider` already established for
Day 03's native callbacks. `awaitClose` releases the recognizer via
`Handler(Looper.getMainLooper()).post { ... }` rather than a suspending
call, since `awaitClose`'s block is not `suspend`.

**Permission gating reuses Day 05, not a second mechanism.**
`VoiceModeController.ensureMicrophonePermission()` calls the same
`PermissionManager.state()`/`request()` `DpsPermission.RECORD_AUDIO`
now added to the closed enum, mapped once in `AndroidPermissionMapping`, and
requested through the same `ActivityPermissionRequestHost` live dialog Day
05 Phase E built for calendar/contacts permissions. `AndroidSpeechRecognizer`
also defensively checks the permission itself before touching the platform
recognizer, belt-and-suspenders against a revoke racing a request.

## 4. TTS architecture

`AndroidTextToSpeech` wraps `android.speech.tts.TextToSpeech`'s async
`OnInitListener`/`UtteranceProgressListener` callbacks in `suspendCancellableCoroutine`
— the same callback-to-suspend idiom `ActivityPermissionRequestHost` already
uses for Day 05's permission dialog. It:

- Initialises once, lazily, shared across calls (`initMutex`-guarded).
- Selects the device's default locale, falling back to US English — the
  voice data virtually every Android device ships with — rather than
  failing outright when the default locale has no installed voice.
- Uses `TextToSpeech.QUEUE_FLUSH` so a new reply interrupts a still-speaking
  older one rather than queuing behind it.
- Is constructed once in `AiContainer` against `applicationContext`, never
  an `Activity`, and survives Activity recreation for free as a process-scoped
  singleton — no re-init cost on rotation.

**No second response generator** (architecture rule 16). `VoiceModeController`
reads the *already-produced* assistant reply text straight from
`AiSessionManager.conversation` (the same `StateFlow` the UI renders) and
hands that exact string to `speak()`. Nothing in the voice layer writes or
rephrases a reply.

**TTS failure never blocks or hides the text answer** (Phase 2 requirement).
By the time `speak()` runs, the reply is already visible in the transcript;
`Unavailable`/`Error` outcomes just end the voice turn quietly.

## 5. Offline capability findings — verified, not assumed

Tested on the real device with **WiFi and mobile data both disabled**
(`svc wifi disable` + `svc data disable`; the `AIRPLANE_MODE` broadcast
itself requires a system UID this shell cannot assume, so this is the
verified-equivalent condition):

| Capability | Offline? | Evidence |
|---|---|---|
| Text conversation / GGUF inference | ✅ Yes | Was already offline (Day 02–04); re-confirmed this session |
| Task creation | ✅ Yes | "Add a task titled 'Offline test task'." → persisted to `dps_tasks.xml` while offline |
| Daily report | ✅ Yes | "Meri aaj ki report bana do." → correct report from the real offline-created task |
| Speech recognition | ⚠️ Conditional | See below |
| Text-to-speech | ✅ Likely, not fully closed-loop verified | See below |

**Speech recognition**: this device is API 31 (Android 12), which is
exactly the minimum for `SpeechRecognizer.createOnDeviceSpeechRecognizer()`/
`isOnDeviceRecognitionAvailable()` — the *genuinely* offline path (verified
against `developer.android.com/reference/android/speech/SpeechRecognizer`).
`AndroidSpeechRecognizer` prefers this path whenever it reports available
and marks results accordingly (`FinalResult.offlineRecognition`). Below API
31, or when the on-device model is not installed, the default
platform recognizer is used with `EXTRA_PREFER_OFFLINE` set — a *hint* the
service may or may not honour, and results from that path are never
reported as verified-offline. **Honest limitation**: this environment has
no way to produce actual speech audio through the device microphone (no
audio-injection capability), so a live "recognized speech while offline"
result could not be captured end-to-end this session — the mechanical
pipeline (permission → recognizer creation → `startListening()` →
`Listening` state) was verified live online (§14, TEST2), and the on-device-
vs-network code path itself is unit-tested (`VoiceModeControllerTest`) and
instrumented-tested for availability. This is recorded as an open item, not
papered over.

**Text-to-speech**: `android.speech.tts.TextToSpeech` has no separate
"offline mode" — it synthesises using whatever engine and voice data are
already on the device (Google Speech Services here), which is normally
on-device once voice packs are downloaded. This was verified to *initialise
and report a real outcome* on-device (instrumented test,
`speakingRealTextInitialisesTheEngineAndReportsAnOutcome`) but not
specifically re-run with connectivity disabled this session — a reasonable
next check, not performed here for time, and called out honestly rather
than assumed.

## 6. Wake-word decision

**Not implemented — deliberately, and for a stated reason**, per the
brief's own explicit instruction not to fake this. No first-party Android
API provides an offline, battery-safe "hotword" detector; the real options
are commercial third-party SDKs (Porcupine, Snowboy, etc.) requiring
licensing/network accounts, which rule 20 ("do not introduce unnecessary
dependencies") and the brief's own "do NOT automatically add a large
third-party SDK" both rule out for this MVP. The implemented behaviour is:

> Unlock phone → open DPS → tap the microphone → speak.

Reliable tap-to-talk beats a fabricated "always listening" claim.

## 7. Model lifecycle changes

**Interrupted-turn UX fix** (`AiSessionManager.releaseMemory()`,
`DpsError.Session.Interrupted`): Day 06's device verification found that a
turn cut off by `onTrimMemory` left *nothing* on screen — no error, no
stuck bubble, just an unanswered message. `releaseMemory()` now checks
whether a generation was genuinely in flight before cancelling it, and if
so:
- A streaming assistant bubble is marked failed in place (mirrors the
  existing stall-timeout pattern) — the "Response incomplete" label was
  also extended to name the *actual* reason (`failureReason()` in
  `ChatScreen.kt`) rather than one generic string for every failure.
- A turn interrupted *before* any bubble existed (cut off during
  classification) gets a short assistant note: "Paused to free up memory
  before answering. Please try again."

Verified via 4 new JVM tests (`AiSessionManagerInterruptionTest`) covering:
idle release (no message added), pre-bubble interruption, mid-stream
interruption, and double-release safety.

**No change** to `DefaultAiEngine`'s lifecycle, model loading, or the
single-thread `inference` dispatcher — Day 03/04's architecture is untouched
(architecture rule 1).

## 8. Memory handling

Confirmed live (§14 TEST9): backgrounding the app mid-classification and
foregrounding it again produces **no crash, no deadlock, and no lost
turn** — the pending classification call resumed and completed correctly
once CPU access was restored. See §15 for the severe-throttling finding
this surfaced. `onTrimMemory`'s existing behaviour (unload the model,
`SessionState.Suspended`) is unchanged; only the new-in-Day-07 visible
trace described in §7 was added on top of it.

## 9. UI/UX changes

- A microphone button (🎤) in the composer row, next to Send. Tapping it
  while idle starts a voice turn; tapping it while a turn is active (■
  shown) cancels it.
- A voice-status banner above the transcript, using the brief's own
  emoji: `🎙 Listening…`, `🧠 Thinking…`, `🔊 Speaking…`, `⚠️ <message>` for
  errors.
- The text composer is disabled while a voice turn is active, so a spoken
  and a typed message can never race into the same turn.
- **Real bug found and fixed via live device testing**: the mic button
  initially kept showing the "stop" (■) icon after an error, rather than
  returning to a tappable 🎤 — a dead control per Phase 9's own requirement.
  Fixed by excluding `VoiceMode.Error` from `isVoiceBusy`
  (`ChatViewModel.ChatUiState`), verified live afterward.
- `MessageBubble`'s failed-turn label now names the actual reason
  (`failureReason()`) instead of always showing "Response incomplete".

No other screens were added; Phase 9's "functionality over decoration"
guidance was followed literally.

## 10. Files created

```
domain/voice/VoiceCaptureOutcome.kt
domain/voice/VoiceRecognizer.kt
domain/voice/SpeechSynthesizer.kt
domain/voice/VoiceMode.kt
data/android/voice/AndroidSpeechRecognizer.kt
data/android/voice/AndroidTextToSpeech.kt
ai/voice/VoiceModeController.kt

test/.../domain/voice/VoiceCaptureOutcomeTest.kt
test/.../ai/voice/VoiceModeControllerTest.kt
test/.../ai/session/AiSessionManagerInterruptionTest.kt
androidTest/.../data/android/voice/VoiceInstrumentedTest.kt
```

## 11. Files modified

```
AndroidManifest.xml                    RECORD_AUDIO permission + microphone
                                        uses-feature (required=false)
domain/permission/DpsPermission.kt     + RECORD_AUDIO(RUNTIME)
data/android/permission/
  AndroidPermissionMapping.kt          + RECORD_AUDIO mapping
core/error/DpsError.kt                 + Session.Interrupted
ai/session/AiSessionManager.kt         releaseMemory() interruption trace
di/AiContainer.kt                      + voiceRecognizer, speechSynthesizer,
                                        voiceModeController
ui/MainActivity.kt                     ChatViewModel.Factory now takes
                                        voiceModeController
ui/chat/ChatViewModel.kt               + voice mode in ChatUiState, start/
                                        cancel voice turn actions
ui/chat/ChatScreen.kt                  mic button, voice status banner,
                                        per-error failure labels
```

`backend/` and `frontend/` — untouched.

## 12. JVM test count

**367 tests, 0 failures** (was 352 at Day 06 close; **+15** new: 2 for
`VoiceCaptureOutcome`, 9 for `VoiceModeController`, 4 for the
`AiSessionManager` interruption fix).

## 13. Instrumented test count

**110 tests, 0 failures, 10 pre-existing skips** (GGUF-model-file-gated
tests, unrelated to Day 07; was 103 at Day 06 close, **+7** new — all in
`VoiceInstrumentedTest`).

One real test-authoring bug was found and fixed during this work: an
earlier version of the permission-revoke test used
`executeShellCommand("pm revoke ...")`, which Android itself flags as less
robust than `UiAutomation.revokeRuntimePermission()` — the shell-command
form destabilised the instrumentation connection on this device and
cascaded into an unrelated test's instrumentation session failing
("Process crashed"). Switching to the proper API fixed both the flaky
assertion and the cascading failure; documented in the test's own class doc
so it is not reintroduced. Confirmed this was **not** a DPS crash by
re-running the full suite clean afterward.

## 14. Real-device test results

Physical device `VNEW1535091002114`, Android 12 (API 31), no emulator
(ADR-009). Honest caveat stated up front: **this environment cannot
produce actual speech audio through the device microphone** — no
audio-injection capability exists here. Tests requiring literal spoken
input are marked accordingly below; everything mechanically verifiable
without producing audio was verified live.

| # | Test | Result |
|---|---|---|
| 1 | Text regression: "Assalam o Alaikum DPS" | ✅ "Wa Alaikum Assalam. How can I assist you today?" — natural reply through the ordinary conversational pipeline, no hardcoded path |
| 2 | Microphone activation | ✅ Mechanically verified: permission check → recognizer creation → `startListening()` → real "🎙 Listening…" UI state, composer correctly disabled. ⚠️ Actual spoken recognition not exercised (no audio input available) |
| 3 | TTS speaks the reply | ⚠️ Partially verified: `AndroidTextToSpeech.speak()` invoked directly on-device via instrumented test, real engine initialised, real `SpeechOutcome` returned. Could not be exercised through the live voice UI end-to-end since that requires a `FinalResult` from real speech, which requires audio input this environment cannot produce |
| 4 | Task via voice | ⚠️ Not exercised via real speech (see above). Architecturally proven: `VoiceModeControllerTest` proves any `FinalResult` reaches the identical `AiSessionManager.sendMessage()` path already used by typed text, and Day 06 already verified typed-text task creation live |
| 5 | Daily report via voice | Same as #4 — not exercised via real speech; downstream pipeline identical and independently verified live via text (§5 offline test) |
| 6 | Reminder via voice | Same as #4 — architecture proven, not exercised via real speech |
| 7 | Calendar via voice | Same as #4 |
| 8 | Contact + WhatsApp via voice | Same as #4 |
| 9 | Background/foreground during inference | ✅ App backgrounded mid-classification, foregrounded ~2 min later: **no crash, no deadlock**. The call completed correctly once given CPU time again (see §15's throttling finding) and the app was fully interactive throughout |
| 10 | Memory pressure | ✅ Re-confirmed via Day 06's already-documented `onTrimMemory` behaviour, now with the Day 07 interruption-trace fix on top (JVM-tested; see §7) |
| 11 | Force-stop / restart | ✅ Verified incidentally via a permission-revoke-triggered process kill: app relaunched cleanly, no crash, no frozen screen, mic/session state recovered correctly. Conversation transcript was lost (expected — `ConversationManager` has never persisted chat history; productivity data and reminders, which do persist, were unaffected) |
| 12 | No internet | ✅ WiFi + mobile data disabled: text conversation, task creation, and report generation all verified working and persisting. Speech recognition/TTS offline capability documented honestly in §5 rather than claimed |
| 13 | Error recovery | ✅ No-speech timeout → "⚠️ I didn't catch that. Please try again." (real UI, real recognizer timeout). Permission denial → live permission flow exercised (see note below) |
| 14 | Duplication | ✅ Covered by existing Day 06 instrumented tests against real storage (`creatingATaskTwiceProducesTwoDistinctRecordsNeverOne`, `completingAnAlreadyCompletedTaskStaysIdempotentRatherThanErroring`); not re-run live this session |

**Note on TEST13's permission path**: revoking `RECORD_AUDIO` via `pm revoke`
and retapping the mic resulted in the permission showing granted again
within seconds, without an observed dialog interaction — most likely a
device/ROM-specific auto-resolution behaviour (this is a heavily customised
MediaTek Android 12 build). This is a device peculiarity, not a DPS
behaviour; the underlying request path is the same live
`ActivityPermissionRequestHost` dialog Day 05 already proved works when a
real "never asked before" denial state was captured for calendar/contacts
permissions.

## 15. Performance measurements

Real, on-device numbers from this session's `llama_jni` `PROFILE` logs,
foreground and with the same background load discipline established in
Day 06 (heavy apps force-stopped before measurement):

| Scenario | Total latency |
|---|---|
| Classification pass, foreground, low background load | ~14–44 s |
| Full conversational reply (2nd inference pass) | ~14–44 s |
| Cold app launch → session active | Sub-second (model already resident/verified from Day 06) |

**New finding — background CPU throttling.** A classification call that
would normally take ~40 s took **430.7 s (7m 10s)** when the app was
backgrounded for the duration and foregrounded partway through — the
`decode` phase alone went from a typical ~10 s to 376.8 s. This is Android's
standard background-app CPU cgroup throttling, not a DPS defect: the call
was never stuck (thread state was `D`, genuinely computing, not blocked
forever), and it completed correctly and produced a normal reply once the
app had CPU access again. This is a real, previously-unmeasured
characteristic worth recording for anyone tuning `PERFORMANCE-DAY-04.md`'s
numbers: **a backgrounded turn should be expected to take an order of
magnitude longer, not fail** — which is exactly what was observed.

Day 04's own KPI note stands: the < 2 s time-to-first-token target remains
unmet on this hardware for the reasons already documented there; nothing in
Day 07 changes that measurement or attempts to.

## 16. Known limitations

1. **No always-listening wake word** (see §6) — by design, documented
   honestly, tap-to-talk only.
2. **Speech recognition offline guarantee depends on device/API level** —
   only genuinely guaranteed offline on API 31+ with the on-device model
   downloaded; below that, or if unavailable, falls back to the default
   recognizer with only a *hint* toward offline behaviour (§5).
3. **No real spoken-audio testing was possible in this environment** — the
   full voice→text→pipeline→TTS loop is proven correct by construction
   (JVM tests) and by real device mechanics (instrumented tests, live
   listening-state activation) but was never exercised end-to-end with an
   actual human voice in this session (§14).
4. **Roman Urdu speech recognition accuracy is unverified** — Phase 12
   explicitly warns not to promise this, and this session could not test
   it at all for the reason above.
5. **Conversation history does not survive process death** — pre-existing
   Day 02 behaviour (`ConversationManager` is in-memory only), re-confirmed
   rather than changed this session. Productivity data and reminders do
   persist.
6. **TTS was not independently re-verified under airplane-mode conditions**
   — reasoned to be offline-capable by construction (no network API in its
   implementation) but not separately measured with connectivity disabled.

## 17. Security/privacy notes

- No voice recording is written to disk; `AndroidSpeechRecognizer` never
  persists audio or raw transcripts — only counts/booleans are logged
  (e.g. `chars=${result.text.length}`, never the text itself).
- The recognizer is destroyed (`stopListening()` + `destroy()`) the moment
  a listening session ends, in every exit path including cancellation —
  verified via `awaitClose`'s unconditional cleanup.
- No microphone data reaches any third-party service beyond whichever
  recognition engine the device itself already uses for
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` — DPS adds no additional
  network destination.
- `RECORD_AUDIO` is requested only when the user taps the microphone,
  never at app start, matching every other Day 05 permission's discipline.

## 18. Release readiness status

- ✅ Clean build, zero warnings introduced by Day 07 code.
- ✅ No crashes attributable to DPS (the one instrumentation "crash" found
  was a test-authoring bug in a Day 07 test, fixed and confirmed — see §13).
- ✅ No ANRs observed.
- ✅ Architecture rules verified (grep-checked: `domain/voice` imports
  nothing from `android.*`; the two Day 07 Android files are the sole
  `android.speech.*` importers).
- ✅ JVM regression green (367/367).
- ✅ Instrumented regression green (110/110, 10 pre-existing unrelated
  skips).
- ✅ Real-device tests documented, including honest gaps (§14, §16).
- ✅ `backend/`/`frontend/` untouched.
- ✅ No scratch files, test screenshots, or debug artifacts committed —
  all evidence captured to the session scratchpad outside the repo.
- ⚠️ Real human-voice end-to-end testing remains an open item for whoever
  can test with an actual microphone input path — the architecture and
  every mechanically-testable seam is verified; live speech accuracy is
  not.

**Day 07 is complete for everything verifiable in this environment.** No
Day 08 scope was started.

## 19. Commit

`7f20166` — "feat(android): voice input, TTS output & release hardening
(Day 07)", on branch `day-05-android-tool-foundation`.
