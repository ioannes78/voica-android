# Stage 17.3 R17C-P2-M1 — Mock-only durable ownership / restart recovery

Status: **P2-M1 IMPLEMENTED / SOURCE-BASELINE CI + APK PASS / QA3 MOCK THREE STEPS PASS WITH DISPLAY-SNAPSHOT LIMIT / REAL NATIVE AND GPU NOT TESTED**.

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


## P2-M1 QA3 real-device reopen — step 2 (user-submitted)

The user left and reopened Stage 17 P1 **without starting another mock
attempt**. Observed copied report preserved the previous 13 ordered mock
events and included the following fields:

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
poisoned=false
p2m1OwnerDurability=ARMED_DURABLE
p2m1OwnerBlocked=false
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=NOT_CHECKED
eventCount=13
... previous P2-M1 QA3 step 1 mock events ...
p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE
p2m1OwnerBlocked=false
```

**QA3 step 2 = PASS WITH EXPLICIT DISPLAY-SNAPSHOT LIMIT.**
The recovered journal verdict `LAST_MOCK_RELEASE_DURABLE` and the recovered
`p2m1OwnerBlocked=false` directly meet the restart-read criterion. No
intervening mock execution occurred. The independent step 1 output had
already shown `RELEASE_CONFIRMED_DURABLE` after the fake release.

**Interpretation of the apparent discrepancy:** Exact source audit of
`Stage17P2LMockCoordinator.kt` at `d377677a95df112aa54b24f75a603ebca7014b59`
shows the initial `_report` constructed as
`prefs.getString("last", ...).orEmpty() + "\\np2m1OwnerRecovery=" +
priorRecovery.reason + "\\np2m1OwnerBlocked=" + priorRecovery.blocked`.
The preexisting `p2m1OwnerDurability=ARMED_DURABLE` and eventCount=13 are
from the previous **best-effort cached phase text**, not fields freshly
computed from the restored owner journal. On reentry, those cached phase
fields are therefore historical even when they appear above the newly
appended recovery fields. They must NOT be read as a current ARMED
ownership state. The recovery verdict is derived from a separate persisted
owner journal and is the relevant restart gate.

**Known non-blocking QA diagnostic UI defect:** Cached phase-report text
does not label itself as historical and presents the stale `ARMED_DURABLE`
alongside the current `LAST_MOCK_RELEASE_DURABLE`. A follow-up
QA report-format improvement should segregate previous report from
restored owner status, ideally showing the owner journal state as current
and the cached events under an explicit historical heading. This is a
reporting ambiguity, **not evidence of failed recovery**. Do not declare
all future race/crash recovery gates PASS from this test.

P2-M1 mock real-device QA3 summary to date:
- Step 1: fake NORMAL + durable release confirmation: **PASS**.
- Step 2: reopen and recover durable journal: **PASS**, display snapshot
  ambiguity documented.
- Callback-before-SEND_RETURNED recheck on P2-M1 QA build: **PASS**; see QA3 step 3 below.
- Actual Binder child death, native JNI release, device driver/HWUI
  recovery and real GPU qualification: **NOT TESTED / NOT AUTHORIZED**.

No runtime code change, new APK, GPU/AUTO action or model inference is
introduced by this evidence-only record.

## P2-M1 QA3 real-device callback-before-return — step 3 (user-submitted)

The user triggered **模拟回调抢先** once in the P2-M1 QA build, then copied the
P2-L MOCK report. No real LLM, JNI or GPU was used.

Reported header:

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

Full simulated lifecycle observations:

| Phase | Elapsed |
| --- | ---: |
| PARENT_ARM_PERSISTED | +0 ms |
| BIND_REQUESTED | +5 ms |
| CHILD_BOUND | +11 ms |
| MODEL_INITIALIZING | +12 ms |
| MODEL_READY | +12 ms |
| CONVERSATION_ENTERED | +13 ms |
| CONVERSATION_READY | +13 ms |
| SEND_ENTERED | +14 ms |
| FIRST_EMISSION | +14 ms |
| SEND_RETURNED | +15 ms |
| TERMINAL_AWAIT_ENTERED | +15 ms |
| TERMINAL_DONE | +54 ms |
| RELEASE_CONFIRMED | +55 ms |

**QA3 step 3 = PASS (one fake on-device observation).**
The recorded event ordering correctly preserves
`FIRST_EMISSION` **before** `SEND_RETURNED`, despite its matching
millisecond-resolution timestamp with `SEND_ENTERED`. The fake terminal
and release were observed, and the parent separately reported
`RELEASE_CONFIRMED_DURABLE` with `poisoned=false`,
`p2m1OwnerBlocked=false`, and no release commit pending.
`NOT_CHECKED` is expected for child-exit correlation in a normal completion.

### P2-M1 QA3 closure

**QA3 MOCK REAL-DEVICE CHECKLIST: COMPLETE / PASS WITH LIMITED COVERAGE.**

- Step 1: normal mock IPC plus durable owner release: PASS.
- Step 2: reopen screen, recover prior durably released owner: PASS;
  cached prior phase text can still display stale `ARMED_DURABLE`.
- Step 3: callback before send return plus durable owner release: PASS.
- Baseline source commit for the installed test APK:
  `3cd94de26e80580825bb4e4074fca50252bbc605`;
  Android PR CI `43605843146` and diagnostic APK build
  `43605843115` both SUCCESS.
- This closure documents supplied device observations; it does not claim
  real Binder child death, actual model resource release or GPU/HWUI stability.

Known follow-up: update the report view to clearly separate **historical
cached phase text** from **current recovered owner journal**. This is a
diagnostic clarity item and not a runtime safety proof.

**P2-M2 should remain mock/design-only until cross-process real model
ownership, Binder death and restart state machine, exact native close
semantics, and the previously documented native isolation blockers have been
independently reviewed.** P2-N real GPU remains NOT AUTHORIZED; the existing
GPU/AUTO quarantine stays enabled, production `main` is unchanged, and PR
#37 remains DRAFT.
