# Stage 17.3 R17C-P2-I — CPU-only JNI submission/callback evidence

Status: **P2-I CPU SHORT-PROBE QA PASS WITH EXPLICIT COVERAGE LIMITS / GPU ANR FAIL-SUSPENDED / PR #37 DRAFT-UNMERGED**.
Repository: `ioannes78/voica-android` / PR #37 OPEN/DRAFT; `main` unchanged.
Baseline: P2-H mock CI PASS at `c721f74c60b072073f40a3047ec142489b65b277`.
LiteRT-LM pinned to **0.18.0**. GPU/AUTO QA quarantine remains active.

## Purpose and exact evidence limit

The P2-C2-B GPU failure was Android ANR(6) with last R5 phase GENERATING and zero
emissions. GENERATING is emitted *before* synchronous
`Conversation.sendMessageAsync` enters the Java/JNI API. That R5 event alone
does not establish whether submission returned, callbacks were lost, native
decode began, the graphics driver deadlocked or CPU/GPU memory was exhausted.

P2-I adds a smaller, content-free boundary protocol for **user-triggered,
explicit CPU QA** only. It is diagnostic instrumentation, **not a native fix**.

| Boundary | Observed fact; no stronger conclusion |
| --- | --- |
| SEND_ENTERED | Adapter is about to invoke synchronous sendMessageAsync |
| SEND_RETURNED | Adapter observed that invocation return |
| TERMINAL_AWAIT_ENTERED | Caller is entering deferred terminal wait |
| FIRST_EMISSION | First onMessage callback crossed into Kotlin |
| TERMINAL_DONE | onDone callback observed |
| TERMINAL_ERROR | onError callback observed |
| CANCEL_REQUESTED / CANCEL_SIGNAL_SENT | Wrapper requested/called cancellation |
| CANCEL_ACK_TIMEOUT / RELEASE_BLOCKED | Terminal not acknowledged or release quarantined; not evidence resources were safely freed |

Callbacks can race **before SEND_RETURNED**, which is deliberately preserved.
No raw prompt, transcript, generated text, file path, PID or thread identity
enters the P2-I ledger.

## Implementation boundaries

- `engine/local-llm/.../LocalLlmCpuBoundaryDiagnostic.kt`: bounded 32-event,
  monotonic-timed JVM ledger and optional QA listener.
- `engine/local-llm/.../LiteRtLocalLlmRuntime.kt`: emit only when
  `request.config.backendPolicy == CPU` **and** resolved backend is CPU.
  Preserve original `sendMessageAsync`, callback, cancellation, terminal wait,
  native close, quarantine and dispatcher behavior.
- `app/.../Stage17P2ICpuBoundaryStore.kt`: per-screen QA observer; in-memory
  bounded append on callback, coalesced snapshot persisted by single writer
  **off the native callback thread**. The last persisted snapshot is shown on
  screen again after a process death. Writes are best-effort; an abort can occur
  before queued persistence is durable.
- `app/.../Stage17P1LocalLlmActivity.kt`: only an explicit CPU request arms the
  P2-I observer. The existing R5 diagnostic observer suppresses per-token
  GENERATION_PROGRESS on this CPU QA path so the JNI callback does not execute
  R5 `Debug.getPss()` or synchronous SharedPreferences commits. Other R5
  lifecycle phases remain unchanged; P2-I FIRST_EMISSION replaces per-token
  persistence for this diagnostic path.
- `ci/verify-stage17-p2e-anr-static-boundary.py`: static checks for CPU-only
  instrumentation, chronological source anchors, bounded ledger and the
  pre-native GPU/AUTO blockade.
- `engine/local-llm/src/test/.../Stage17P2ICpuBoundaryLedgerTest.kt`: fake
  synchronous submission-not-returned, synchronous terminal callback race,
  concurrent first-emission dedup, zero-emission error, missing terminal,
  cancellation quarantine, capacity/arm/disarm and listener exception tests.
  These use **no Android hardware or real JNI**.

P2-I does not alter Room, assets, model channel, BLE/ASR/recording, sample
parameters, production summary routing, native resource ownership or model
version. The QA screen's P2-D pre-native deny of GPU/AUTO and the production
AUTO+JSON-schema -> CPU containment remain intact.

## Acceptance

1. Android PR CI passes on **exact current implementation HEAD**, including
   `:engine:local-llm:testDebugUnitTest` and static source checks.
2. Diagnostic QA `releaseQa` APK build and verification pass independently.
3. Confirm on source that callback path records no content, PSS or disk I/O.
4. Confirm PR #37 remains OPEN/DRAFT and main unchanged.
5. Only then **one user-triggered CPU smoke test** may be requested. Copy the
   historical R5 breadcrumb **before** any new CPU run; run the short default
   CPU QA action once while unlocked/foreground, then copy the new P2-I panel.
6. Do not run GPU, AUTO or long CPU stress. Any CPU crash/ANR aborts the gate.
   A CPU smoke PASS would validate only that CPU configuration/diagnostics run
   on that phone, not GPU stability or a driver root cause.

P2-I output is an observed wrapper phase timeline, not a native stack trace
or a guarantee that native execution is stoppable. Do not use naive
`withTimeout` to declare a blocked JNI call cancelled. GPU remains
**ANR FAIL / SUSPENDED**, with a separate explicit requalification gate.


## Observed exact-commit CI / device acceptance (2026-11-13 record)

The QA APK and Android PR CI were built from exactly
`d5633c513f493d30c15ccef02d799b1e73c1e01e`:

- Android PR CI run `43238302640`: **SUCCESS**, including static source contracts,
  unit tests, and Debug compile.
- Stage 17.3 Quality Diagnostic APK run `43238303040`: **SUCCESS**,
  including releaseQa compile, APK verification and upload.
- No LiteRT-LM upgrade; runtime remains `0.18.0`. Model:
  `gemma-4-e2b-it-litertlm`, revision
  `7fa1d78473894f7e736a21d920c3aa80f950c0db:r1`.
- User's on-device test: explicit CPU / resolved CPU /
  reason EXPLICIT_CPU / fallback=false.
- QA diagnostic response: `我正在本地设备上运行。`

### P2-I wrapper phase record (milliseconds since arm)

| Entry | Elapsed ms |
| --- | ---: |
| SEND_ENTERED | 6866 |
| SEND_RETURNED | 6870 |
| TERMINAL_AWAIT_ENTERED | 6870 |
| FIRST_EMISSION | 10453 |
| TERMINAL_DONE | 11952 |

The synchronous submission wrapper returned in ~4 ms. Its first observed
emission was ~3583 ms after return, and the terminal callback arrived ~1499 ms
later. These are observed wrapper/diagnostic boundaries, not proof of native
worker scheduling, GPU driver behavior or exact token processing durations.
The separate runtime diagnostic `ttfeMs=3348`, `generationMs=4095` use
different boundaries and must not be forcibly reconciled with the P2-I arm
timestamps.

### Runtime diagnostic / resource observation

| Metric | Observed |
| --- | ---: |
| initializeMs | 934 |
| conversationCreateMs | 7 |
| ttfeMs | 3348 |
| generationMs | 4095 |
| pssBeforeKb | 78050 |
| pssInitializedKb | 477592 |
| pssGeneratedKb | 1363141 |
| pssClosedKb | 380884 |
| peakPssKb | 1364550 |

The normal return included a post-close PSS sample: memory declined materially
from generation to close, but post-close PSS remained above pre-inference PSS.
One sample does not establish memory-leak absence or repeat-load safety.
No direct `LocalLlmRuntimeState.IDLE` reading was supplied in this QA2 report;
do not label a separately observed coordinator-IDLE gate as passed.

### P2-I gate decision

**CPU short-probe JNI wrapper / callback / post-close diagnostic:
PASS WITH EXPLICIT COVERAGE LIMITS.**

Confirmed for this one explicit CPU attempt: native submission returned,
asynchronous first callback arrived, terminal-done callback arrived, a
meaningful Chinese response was returned, and post-close PSS was sampled.
Not confirmed: sustained repeated CPU loads, every cancellation path,
native resource internals, callback-thread identity, independent coordinator
IDLE observation, GPU generation, or GPU ANR root cause.

Historical GPU P2-C2-B still exited with Android ANR(6), requested/resolved
GPU/GPU, estimated 1988 prompt tokens, last breadcrumb GENERATING/0 emissions.
That GENERATING event preceded synchronous JNI submission; the old GPU run
has no P2-I SEND_RETURNED marker. It is **not** valid to infer the GPU
submission returned from the CPU result. The GPU/AUTO QA quarantine remains
hard-enabled. PR #37 stays OPEN/DRAFT; production main stays unchanged.

Next engineering gate is a separate evidence-led GPU failure investigation
without automatically re-enabling GPU: first preserve the P2-I CPU acceptance
and review device/driver/runtime evidence, only then propose a fresh isolated
GPU requalification plan with separate explicit authorization.
