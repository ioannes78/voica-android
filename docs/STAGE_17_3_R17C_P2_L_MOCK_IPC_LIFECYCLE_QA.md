# Stage 17.3 R17C-P2-L — QA-only mock process / IPC lifecycle gate

Status: **CODE IMPLEMENTED IN STAGE 17 DRAFT / CI PENDING / NO DEVICE RUN / NO REAL NATIVE**.
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
