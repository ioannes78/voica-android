# Stage 17.3 R17C-P2-L — QA-only mock process / IPC lifecycle gate

Status: **MOCK QA1 NORMAL + QA2 CALLBACK-BEFORE-RETURN REAL-DEVICE PASS / SOURCE HEAD CI PREVIOUSLY PASS / GPU FAIL-SUSPENDED / NO REAL NATIVE**.
Repository: `ioannes78/voica-android`, PR #37 remains OPEN/DRAFT/UNMERGED.
Starting baseline: `022cf7b5330c771e0fb73992dd35b5f3039bcd6c`.
Production `main` has not been modified. The runtime dependency remains
`com.google.ai.edge.litertlm:litertlm-android:0.18.0`.

## Purpose, strictly bounded scope

This stage follows the P2-K design, **not** the GPU requalification. The historic
P2-C2-B explicit GPU test exited ANR(6) with last R5 `GENERATING / 0 emissions`.
P2-I CPU short probe successfully observed SEND_ENTERED/SEND_RETURNED/
FIRST_EMISSION/TERMINAL_DONE. Neither explains the GPU native graphics
synchronization, nor authorizes another GPU call.

P2-L creates a **separate QA app process solely for deterministic fake events**.
It does NOT load a Gemma model, create LiteRT-LM Engine/Conversation,
touch a native JNI entrypoint, allocate GPU resources, alter model catalog,
run an ASR/diarization workload, use an actual GPU driver, or change
production online/offline summary behavior. There is no test payload text.

## Concrete implementation

- `Stage17P2LMockProtocol.kt`: pure Kotlin `NATIVE_EXECUTION_ENABLED=false`,
  enums and bounded **32 scalar-event** ledger. Owner attempts cannot overlap.
  Missing terminal / Binder loss / uncertainty poison mock ownership until
  explicit new QA session; unknown JNI results are never reinterpreted as success.
- `Stage17P2LMockProbeService.kt`: FAKE-only `Service` using Messenger IPC.
  It runs from a dedicated background HandlerThread to send simulated
  lifecycle events. `SUBMISSION_NOT_RETURNED` and `TERMINAL_MISSING`
  merely stop emitting events, not block a worker. `CANCEL_ACKNOWLEDGED`
  and `CANCEL_UNACKNOWLEDGED` simulate fake terminal acknowledgment.
- `Stage17P2LMockCoordinator.kt`: main-process-only parent owner:
  persists a content-free PARENT_ARM_PERSISTED record on IO worker **before
  bind**, maintains bounded phase ledger, coalesces UI StateFlow snapshot,
  persists subsequent snapshots via single background writer, checks child
  PID plus exact `:stage17_mock_probe` process-name match, rejects stale
  attempt IDs and handles bind failure, death, null binding, report timeout.
  The 2.5 s timer is fake-only/report-only and NEVER kills a native call.
- `app/src/qa/AndroidManifest.xml` and
  `app/src/releaseQa/AndroidManifest.xml`: one private
  `android:exported="false"` Service, `android:process=":stage17_mock_probe"`,
  **no launcher**. Production `app/src/main/AndroidManifest.xml` has no
  registration. This is a distinct process from the existing
  `:model_validator`; neither is Android `isolatedProcess=true`.
- `VoicaApplication.kt`: exact process allowlist bypasses heavy AppContainer
  initialization only for `:model_validator` (existing behavior) and the
  explicit `.qa:stage17_mock_probe` process. A production mock process
  suffix or `:stage17_gpu_probe` never triggers the new exception.
- `Stage17P1LocalLlmActivity.kt`: diagnostic panel for **NORMAL** and
  **CALLBACK_BEFORE_RETURN** fake-only scenarios, copied parent report.
  Existing P2-D global GPU/AUTO pre-native guard and P2-I CPU path stay intact.
- `Stage17P2LMockProtocolTest.kt`: JVM-only tests covering QA admission,
  exact process attestation, lightweight boot allowlist, callback-before-return
  ordering, submission-no-return, missing terminal, cancellation with/without
  acknowledgment, Binder death/bind failure, stale attempt, duplicate terminal,
  mock poison/uncertain release and 32-event maximum.
- `ci/verify-stage17-p2e-anr-static-boundary.py`: static checks for QA-only
  manifests, no real Engine/JNI calls reachable from these mock components,
  parent durable arm before binding, correct boot allowlist and unit tests.

## Explicit evidence semantics

All P2-L data is `MOCK ONLY`. Its fake
`MODEL_INITIALIZING`, `SEND_RETURNED`, `TERMINAL_DONE` or
`RELEASE_CONFIRMED` events mean **only that the fake Service sent those
messages and the parent accepted them**. They are NOT model timings,
native stack traces, kernel GPU fence evidence, or a future
LiteRT-LM status observation.

The child's reported process name/PID are matched against the main
process's ActivityManager view. If the OS does not expose a matching
process, that is `CHILD_IDENTITY_UNCONFIRMED` (fail-closed), not a
success and not permission to skip verification. Parent can die too:
durability is best-effort after the initial arm commit; later queued
snapshots may not complete before a system-wide ANR.

The event ledger only includes enum phases and elapsed milliseconds.
No user speech, prompt, recording name, model response, device identifier,
raw ANR trace or file path is recorded or transferred. The app does not
perform network upload.

## Acceptance and restrictions

1. Source must still pin 0.18.0 and keep P2-D compile-time
   `GPU_QA_SUSPENDED=true`, central pre-native GPU/AUTO deny, and
   formal AUTO+JSON-schema→CPU containment.
2. Android PR CI exact HEAD success: JVM tests + static contract + Kotlin
   debug compilation.
3. Diagnostic releaseQa APK build/verification success; compilation is not
   hardware qualification.
4. Static and manifest inspection must confirm the mock Service is absent
   from production registration and no real JNI path is reachable.
5. Optional real-device **mock-only** QA is separately gated and, if done,
   only confirms parent-child IPC/lifecycle on the test phone. Failure or
   missing events never justifies GPU execution.
6. No merge, production release, real GPU, real CPU stress, JNI
   thread-affinity replacement, LiteRT-LM dependency upgrade, user
   transcript processing, or driver context-priority modification.

## Known limitations and next decision

This stage implements bounded parent-side evidence and message transport,
not a complete system-wide ANR forensic capture. The existing QA mock panel
offers normal ordering and callback-before-return; fault cases are covered
by deterministic JVM tests. Android may deny live child process inspection,
causing intentionally conservative diagnostic failure.

The mock-state machine is process-local. A process restart preserves the
last persisted snapshot, but a later manual launch currently creates a new
mock-only state machine; do not assume this persistence is sufficient for
a real native GPU gate. P2-M must strengthen restart poisoning, explicit
reset semantics, ownership coordination with genuine local LLM operations,
and historical `ApplicationExitInfo` process/PID/timestamp correlation
before considering any real-native path.

**P2-L does not authorize GPU hardware testing.** P2-M is a separate
real-native design review requiring deliberate approval and fresh evidence.


## P2-L mock real-device QA1 — NORMAL scenario (user-submitted)

Evidence source: user copied the P2-L MOCK panel from a device after the
NORMAL scenario. Corresponding QA APK source commit:
`544571ca4d09f25b9554e568605f50b934dfa99c`.
Build validation:
- Android PR CI `43459371463`: **SUCCESS** on exactly that commit;
  static source contracts, JVM unit tests and Debug compile passed.
- Stage 17.3 Diagnostic APK `43459371471`: **SUCCESS** on that commit;
  releaseQa compilation, verification and artifact upload passed.

Full content-free observation (relative elapsed milliseconds):

| Event | Observed ms |
| --- | ---: |
| PARENT_ARM_PERSISTED | 0 |
| BIND_REQUESTED | 4 |
| CHILD_BOUND | 85 |
| MODEL_INITIALIZING | 90 |
| MODEL_READY | 91 |
| CONVERSATION_ENTERED | 92 |
| CONVERSATION_READY | 93 |
| SEND_ENTERED | 93 |
| SEND_RETURNED | 94 |
| TERMINAL_AWAIT_ENTERED | 95 |
| FIRST_EMISSION | 132 |
| TERMINAL_DONE | 133 |
| RELEASE_CONFIRMED | 134 |

Additional reported scalars: `P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU`,
`nativeExecution=false`, `poisoned=false`, `eventCount=13`.

**QA1 NORMAL = PASS (one mock-on-device observation).**
The child process bound, the parent accepted all 13 ordered fake phases,
and the fake release concluded without sticky mock poisoning. The report does
not prove real `Engine.initialize()`, real synchronous JNI submission, GPU
worker liveness, native release, or robustness to process death. These
millisecond durations refer to fake IPC events, **not model speed**.

Not yet demonstrated on real hardware: lost Binder process,
watchdog/terminal-missing and cancellation recovery; these fault cases have
JVM fake-test coverage only. QA2 callback-before-return is separately
verified below. No real GPU requalification has been authorized.

**QA2 CALLBACK_BEFORE_RETURN completed — see the result below.** No additional
real-GPU test is authorized; separate process death and restart-poisoning
requirements remain as P2-M work.


## P2-L mock real-device QA2 — CALLBACK_BEFORE_RETURN scenario (user-submitted)

Evidence source: user copied the P2-L MOCK panel after triggering **模拟回调抢先** in
the installed P2-L QA build. The verified test APK source baseline remains
`544571ca4d09f25b9554e568605f50b934dfa99c`; the subsequent docs-only
commit does not change the binary under test.

Reported header:
```
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
poisoned=false
eventCount=13
```

Full phase ordering and elapsed milliseconds:

| Event | Observed ms |
| --- | ---: |
| PARENT_ARM_PERSISTED | 0 |
| BIND_REQUESTED | 4 |
| CHILD_BOUND | 8 |
| MODEL_INITIALIZING | 12 |
| MODEL_READY | 20 |
| CONVERSATION_ENTERED | 21 |
| CONVERSATION_READY | 21 |
| SEND_ENTERED | 22 |
| FIRST_EMISSION | 22 |
| SEND_RETURNED | 23 |
| TERMINAL_AWAIT_ENTERED | 23 |
| TERMINAL_DONE | 51 |
| RELEASE_CONFIRMED | 52 |

**QA2 CALLBACK_BEFORE_RETURN = PASS (one mock-on-device observation).**

The event list preserves **FIRST_EMISSION before SEND_RETURNED**, including
same-millisecond `SEND_ENTERED` and `FIRST_EMISSION` entries in their recorded
arrival order. The difference between logged events is millisecond-resolution
quantization, not a measurement of the underlying JNI call (none occurred).
The observed fake terminal and release completed and no mock poison flag was
reported.

Combined real-device P2-L outcome:
- **QA1 NORMAL = PASS**: 13 fake phases, final RELEASE_CONFIRMED at +134 ms,
  `poisoned=false`.
- **QA2 CALLBACK_BEFORE_RETURN = PASS**: 13 fake phases, preserved early callback,
  final RELEASE_CONFIRMED at +52 ms, `poisoned=false`.
- **P2-L mock IPC happy-path + callback race evidence = PASS WITH LIMITED COVERAGE.**

Do **not** claim that Binder child-death, failure recovery, persisted poisoning
after main-process restart, or actual Android-native resource ownership have
real-device PASS. The simulated failure cases were JVM-tested only. If the
remaining failure-path features are needed, they require a separate QA design
and explicit mocks, not a GPU retest.

**Handoff:** P2-M should audit the parent/child ownership boundary, restart
poisoning, PID/process + ApplicationExitInfo correlation, and model lease safety
as design and mock-only work first. Any real native or GPU execution requires
separate authorization. Existing P2-D GPU/AUTO quarantine remains enabled and
`main` remains unchanged.
