# Stage 17.3 R17C-P2-M1 — Mock-only durable ownership / restart recovery

Status: **IMPLEMENTED / EXACT-HEAD CI + APK PASS / REAL-DEVICE MOCK QA3 STEP 1 PASS / REOPEN RECOVERY PENDING / NO JNI OR GPU**.

Repository: \`ioannes78/voica-android\`. Source baseline:
\`b20343f5628ede4fd11eda5db52eb097e9d00276\`.
Stage 17 branch: \`stage17-fully-offline-intelligence\`; PR #37 OPEN/DRAFT
and production main unchanged. This stage adds no production Android
Service, no model catalog or Room migration, no real JNI owner thread,
no update of \`litertlm-android:0.18.0\`, and no GPU/AUTO QA unlock.

## Why M1 is required

P2-L QA1 (normal IPC) and QA2 (callback-before-send-return) on-device
fake runs passed, but the process-local \`Stage17P2LMockLedger\` was recreated
on every QA screen reconstruction. Previously a textual report could survive
while the associated active/poisoned state disappeared. Treating that
report as a valid lease would be unsound for native execution.

P2-M reviews explicitly required **fail-closed restart recovery** before
considering any future real-native design. M1 remains entirely mock-only.

## Implemented source changes

1. \`Stage17P2M1MockRecovery.kt\`: a pure Kotlin durable-state codec with
   \`ARMED\`, \`RELEASE_CONFIRMED\` and \`UNRESOLVED_POISONED\`.
   On recreation: missing new journal means a legacy P2-L mock record only
   (never contained native work), a durable confirmed release permits another
   mock, and any ARMED, poisoned, malformed or mismatched-process journal
   **denies** another request. No automatic reset/try again.
2. Parent \`Stage17P2LMockCoordinator\`: generates a random nonce and token
   with a persisted monotonic attempt generation. It must commit the
   ARMED journal on a non-UI worker **before binding the Service**.
   A commit error denies bind. A callback's fake RELEASE_CONFIRMED is not
   accepted as ownership release until a valid terminal + submission-order
   sequence has been observed AND the parent explicitly commits
   RELEASE_CONFIRMED durably. During the commit the next attempt remains
   blocked. Any binder failure, timeout, unknown identity or interrupted
   activity writes/retains UNRESOLVED_POISONED; the already committed ARMED
   state alone also causes restart denial if the poison write is lost.
3. Parent process mock admission has an AtomicBoolean across local
   coordinator instances; this is **only a mock in-main-process gate**.
   It does not serialize a separate real LLM runtime process or replace
   the model registry. No model is actually leased or touched in M1.
4. Fake Service round-trips the random attempt nonce in every scalar
   Messenger event. The parent checks nonce, exact token, child PID,
   app-owned process name and the actual running-process UID. Missing
   identity causes fail-closed refusal. Neither IPC side passes a prompt,
   audio transcript or model output.
5. Child death lookup, if a previously observed PID exists, uses a bounded
   history \`ApplicationExitInfo\` query (API30+) with exact process name,
   child PID, UID, and attempt timestamp window. Zero or duplicate matches
   are UNKNOWN/AMBIGUOUS, never a falsely attributed Android ANR. No raw
   ANR traces, stacks or device IDs are saved.
6. \`Stage17P2M1MockCompletionPolicy\` separately validates fake
   SEND_ENTERED, SEND_RETURNED, TERMINAL_AWAIT_ENTERED, observed terminal
   and final RELEASE_CONFIRMED before the parent can commit release.
   FIRST_EMISSION before SEND_RETURNED is explicitly accepted; a premature
   or missing terminal/send-return is not.
7. \`Stage17P2M1MockRecoveryTest.kt\` adds pure JVM tests covering
   interrupted-arm recovery, durable release, sticky poison, corrupt
   journal, UID/PID/process/timestamp exit correlation, duplicate exit
   candidates, competing mock owner claims, callback ordering and
   premature release rejection. CI static P2-E guard is extended to fail
   on missing journal/nonce/UID/boundary contracts.

The existing P2-L \`NO LLM / NO JNI / NO GPU\` panel remains the QA surface
and reports \`p2m1OwnerDurability\`, \`p2m1OwnerBlocked\`,
\`p2m1ReleaseCommitPending\`, and \`p2m1ChildExitCorrelation\`.

## Durability/observability rules

- An event displayed as RELEASE_CONFIRMED is **FAKE** and can precede
  durable journal confirmation. The additional durability field is
  authoritative for whether a subsequent **mock** start is permitted.
- Initial ARMED journal commit and final owner release commit run off the
  main thread. Intermediate phase/log snapshots are still best-effort.
- A process crash while journal state is ARMED fails closed on restart,
  including one after the fake terminal callback but before the durable
  release commit.
- If the parent is killed before a child PID is observed, historical exit
  correlation reports UNKNOWN; it does not infer child identity from a
  stack marker.
- The active recorder/ASR, Room state, production local LLM coordinator,
  product summary backend and model-update flow are unchanged.

## Gate checklist

- Source static contracts and app JVM tests pass on the *exact new HEAD*.
- A releaseQa APK compiles and verifies with QA signing; test only
  mock-only IPC, never the suspended GPU/AUTO actions.
- Optional P2-M1 QA3 on-device (mock only): cover-install the exact QA APK
  without wiping app data, trigger NORMAL once, copy the full report and
  verify RELEASE_CONFIRMED_DURABLE; leave/reopen the QA screen, verify
  LAST_MOCK_RELEASE_DURABLE recovery; then trigger CALLBACK_BEFORE_RETURN once
  and copy its report. Stop on any unexpected poisoned/timeout/identity failure.
- No device kill/reboot/force-stop or native GPU test is requested in M1.

## Explicit unresolved blockers

**P2-M1 is a mock architecture increment, not a native isolation clearance.**
The following remain for separate P2-M2/P2-N review:
- Parent-held real \`ModelUseRegistry\` lease and cross-process exclusive
  admission with the genuine local LLM runtime;
- durability under actual child crash / Binder reconnect and system-wide ANR;
- exact 0.18.0 native owner-thread / cancel / close behavior;
- driver/HWUI fence correlation, device memory safety and process-independent
  graphics resource ownership;
- full Android instrumentation of durable journal write failures and
  restoration before any actual native Service work.

GPU / AUTO QA remains **FAIL / SUSPENDED**, model inference remains
unmodified, and there is **no P2-N real GPU authorization**.


## P2-M1 QA3 real-device NORMAL — step 1 (user-submitted)

Observed from the installed QA APK built at source commit
`3cd94de26e80580825bb4e4074fca50252bbc605` (source unchanged by this
documentation-only update). Precise-source Android PR CI run
`43605843146`: SUCCESS; diagnostic releaseQa APK run `43605843115`: SUCCESS.

Header from the device:

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
poisoned=false
p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE
p2m1OwnerBlocked=false
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=NOT_CHECKED
eventCount=13
```

| Event | Observed relative ms |
| --- | ---: |
| PARENT_ARM_PERSISTED | 0 |
| BIND_REQUESTED | 5 |
| CHILD_BOUND | 79 |
| MODEL_INITIALIZING | 93 |
| MODEL_READY | 94 |
| CONVERSATION_ENTERED | 96 |
| CONVERSATION_READY | 97 |
| SEND_ENTERED | 98 |
| SEND_RETURNED | 99 |
| TERMINAL_AWAIT_ENTERED | 99 |
| FIRST_EMISSION | 126 |
| TERMINAL_DONE | 127 |
| RELEASE_CONFIRMED | 129 |

**QA3 step 1 = PASS WITH LIMITED COVERAGE.** The parent received a complete
mock normal ordering and reported a separately committed fake ownership release
(`RELEASE_CONFIRMED_DURABLE`) with no pending commit, no poison, and owner
unblocked. These milliseconds represent mock IPC phases rather than real model
latency. `p2m1ChildExitCorrelation=NOT_CHECKED` is expected without child death.

**QA3 step 2 remains PENDING.** The user must leave the Stage 17 P1 QA screen,
reopen it without force-stop/clearing data, and copy the P2-L panel **without
launching another mock run first**. Required recovered scalar:
`p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE`,
`p2m1OwnerBlocked=false`. Do not call QA3 complete until this is observed.
The optional callback-before-send-return repeat is not yet verified for P2-M1.

No real JNI, GPU or AUTO execution was performed or approved. Real child death,
binder reconnect and native resource ownership remain unresolved.
