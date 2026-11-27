# Stage 17.3 R17C-P2-K — GPU isolated forensic diagnostic design V1

Status: **DESIGN REVIEW COMPLETE / NO IMPLEMENTATION / NO GPU RUN / GPU ANR FAIL–SUSPENDED**.
Repository: `ioannes78/voica-android`. Baseline before this design:
`955ea9515bc19ade2adb3be177bf0dfee8ec9742`.
PR #37: **OPEN / DRAFT / UNMERGED**; production `main`:
`28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`.
This commit adds **documentation only**. No enabled GPU entry point, new service,
model dependency, schema, Gradle or Android manifest change is authorized.

## 1. Why this separate design gate is needed

Prior R17C P2-C2-B failed in an explicit GPU/GPU attempt with Android
`ApplicationExitInfo.REASON_ANR (6)`. The last persistable R5 breadcrumb was
`GENERATING / emissionCount=0`; estimated prompt was 1,988 tokens, JSON schema
was off, screen interactive/unlocked. Main-thread trace summary included
`pthread_cond_wait` / `libhwui.so`; RenderThread included
`libsync.so:sync_wait`. Those truncated frames are **not** a causally resolved
graphics-fence wait chain. Android exit PSS ≈ 2,014,444 KiB does not establish
OOM or exonerate memory pressure.

P2-I CPU QA on LiteRT-LM 0.18.0 succeeded once: wrapper
`SEND_ENTERED=6866 ms`, `SEND_RETURNED=6870 ms`, `FIRST_EMISSION=10453 ms`,
`TERMINAL_DONE=11952 ms`, with a Chinese response and post-close PSS sample.
This **does not** establish whether GPU's synchronous `sendMessageAsync`
returned in the earlier failed attempt, nor prove stable JNI thread affinity,
GPU callback delivery or native release safety.

P2-J verified that the corresponding QA APK actually packaged the arm64
`liblitertlm_jni.so` and its JNI entrypoints, EGL/GLES dependencies and OpenCL
strings. Static symbols cannot establish which path the failed GPU attempt took.

**P2-K is a design and threat-model gate, NOT a GPU repair or a new GPU test.**
Keep `Stage17R17CP2DAnrClosure.GPU_QA_SUSPENDED=true` and the central
pre-native GPU/AUTO denial intact in the current QA build; retain formal
summary AUTO+JSON-schema→CPU containment.

## 2. Three distinct goals (never substitute one for another)

| Objective | Evidence required | NOT guaranteed by |
| --- | --- | --- |
| A. Contain process-local native crash | Main process stays alive when the **child process** dies, correct Binder death detection | Child process launch, a CPU smoke PASS, GPU driver state being healthy |
| B. Diagnose GPU failure phase | Child SEND_ENTERED/SEND_RETURNED, callback/terminal and post-exit parent-held metadata, per-process exit reasons | GENERATING breadcrumb or a list of native symbols |
| C. Prove device GPU workload safety | Separately authorized real-device GPU stability gate across selected representative loads | A single short prompt or the absence of an ANR in a mock |

The Android GPU driver/HWUI and kernel resources are device-wide. A second
Android application process can isolate process-local faults but **cannot
guarantee graphics-driver isolation or prevent UI ANR/system freezes**.

## 3. Options reviewed and selected engineering direction

| Option | Benefit | Tradeoff / recommendation |
| --- | --- | --- |
| Existing in-process QA Activity with extra markers | Cheapest implementation | **Reject for next GPU experiment**: a native process crash can take the UI and its last unwritten evidence |
| New, QA-only `android:process=":stage17_gpu_probe"` bound Service, `android:exported="false"` | Dedicated child PID, shared-app-UID **read-only model file access**, separate JNI owner and Binder death detection | **Preferred future prototype**. Shared UID is NOT a full security sandbox and GPU driver remains shared |
| Android `android:isolatedProcess="true"` Service | Separate isolated UID with fewer permissions | **Defer**: model stored under app-private `noBackupFilesDir` is not directly readable; needs explicit ParcelFileDescriptor/IPC capability transfer, authorization and extra ownership review |
| A different application/package | Stronger app-level isolation | Requires separate installation/signature/model access and new test surface; not necessary for the mock-only first gate |

Android's service contract: an `isolatedProcess` Service runs under a special
process with **no permissions of its own**, communicating via Service binding/
starting. It is not interchangeable with an ordinary colon-named private
process. Source: https://developer.android.com/guide/topics/manifest/service-element

### Existing project integration finding

`app/src/main/AndroidManifest.xml` already declares
`IsolatedModelValidationService` in `:model_validator`. Despite its class name,
this is an ordinary **app-UID separate process** (`android:process`), not a
manifest `android:isolatedProcess="true"` sandbox. Its validation implementation
lives in `IsolatedModelValidation.kt` and uses process-local Service +
result-file polling.

**Do not reuse `:model_validator` as the GPU QA process:** that would mix
separate model-validation/native workloads and corrupt failure attribution.
Do not copy the validation file-polling timeout as proof native GPU resources
were released.

**Critical boot constraint:** `VoicaApplication.onCreate()` skips heavy
`AppContainer` only when the current process equals
`packageName + ":model_validator"`. A future
`:stage17_gpu_probe` process MUST get its own explicit, fail-closed lightweight
process classification **before** AppContainer/Room/recording/ASR/notification
initialization. The classification is scoped to the exact QA process name;
do not suppress normal initialization for production application processes.

This is a **design-required source change in a later gate**; P2-K does not make it.

## 4. Proposed mock-first architecture (not implemented)

```text
QA screen / main process (no GPU JNI)
  ├─ hard P2-D deny remains on existing startRequest path
  ├─ future dedicated UI consent + preflight (NOT enabled in P2-K)
  ├─ process-wide QA attempt admission + model use lease
  ├─ parent-owned, content-free bounded evidence ledger + writer
  └─ bind to exported=false :stage17_gpu_probe Service
         ↕ Binder async commands and lifecycle/death notifications
:stage17_gpu_probe process (only in a separate future QA implementation)
  ├─ lightweight Application startup, no AppContainer
  ├─ guarded single request, pinned model, no AUTO/fallback
  ├─ single native owner; unknown release -> poison/quarantine
  ├─ OFF callback-thread phase relay; no sync disk/PSS per token
  └─ Engine/Conversation/sendMessageAsync/terminal/close
```

The initial prototype shall be **100% fake-native, CPU-free and GPU-free**;
it must not instantiate a LiteRT-LM `Engine` or load
`liblitertlm_jni.so`. Only a later separately authorized build may connect
a real native adapter.

**Two-layer opt-in is mandatory for any later GPU build:**

1. A source-reviewed QA-only **compile-time disabled-by-default** GPU-native
   entry gate, separately enforceable **inside the probe Service before Engine
   construction**, and a central request denial in the parent. The current
   `GPU_QA_SUSPENDED=true` is NOT to be silently bypassed or switched off.
2. A subsequent single-attempt user action after an explicit new approval of
   the exact version/build/device/test parameters. No automatic execution,
   retry on error, resume after restart, background/lockscreen run or AUTO.
   A successful CPU/mock test never auto-arms GPU.

Model access: main process validates the managed model identity, source
revision/hash and safety resource gate and retains the **parent-side model lease**
while the child is alive. Child must independently verify a canonical,
app-private model path and read-only usage. Never transfer raw recording/
transcript or an external writable arbitrary path. Check that changing model
versions cannot remove a leased artifact while the child owns it.

Ownership: separate child and main-process gates are needed because
`LocalLlmRuntimeCoordinator` mutex and poisoning are process-local. The UI
must not schedule a competing local LLM job until the child outcome is known.
An uncertain native release or Binder death results in **no immediate retry**
and a visibly unresolved QA gate.

## 5. Minimal observable phase protocol

Proposed **scalar metadata only**, max 32 phases per attempt; no user content,
prompt, model output, file paths, stacks, raw traces, device serials, internet
upload or unbounded event log.

| Phase | Meaning |
| --- | --- |
| PARENT_ARM_PERSISTED | Main process successfully persisted admission metadata **before** child can touch JNI |
| BIND_REQUESTED / CHILD_BOUND | Bind requested / onServiceConnected observed |
| MODEL_INITIALIZING / MODEL_READY | Child entered/returned from model init |
| CONVERSATION_ENTERED / CONVERSATION_READY | Wrapper entered/returned conversation creation |
| SEND_ENTERED | Wrapper about to call synchronous `sendMessageAsync` |
| SEND_RETURNED | Wrapper observed Java/JNI submission return |
| TERMINAL_AWAIT_ENTERED | Wrapper entered deferred terminal wait |
| FIRST_EMISSION | First text callback, **content not retained** |
| TERMINAL_DONE / TERMINAL_ERROR | Actual terminal callback observed |
| CANCEL_REQUESTED / CANCEL_SIGNAL_SENT / CANCEL_ACK | Explicit cancellation and acknowledgment boundaries |
| RELEASE_ENTERED / RELEASE_CONFIRMED / RELEASE_UNCERTAIN | Closing attempt and observable result |
| BINDER_DIED / CHILD_EXIT_OBSERVED / ANR_EXIT_MATCHED | Parent observed child death and optional Android process history match |

Do not invent missing phases after crash. `SEND_RETURNED` may occur after a
callback; preserving arrival order matters. Events need monotonic
`SystemClock.elapsedRealtimeNanos` timestamps; parent can assign a separate
wall-clock anchor only at admission. Timestamps identify wrapper observation,
**not** GPU decode start, native thread activity or graphics-fence ownership.

JNI callback thread: only bounded atomic/in-memory append and scheduling.
IPC delivery and SharedPreferences/file commits run off the callback thread.
Binder death callback must not do filesystem work synchronously. Evidence
delivery/persistence is best-effort: the parent records whether a snapshot
was **observed** versus **durably committed**. Missing SEND_RETURNED remains
**UNKNOWN**, not proof of synchronous JNI deadlock. If driver-wide ANR freezes
the main process too, parent ledger availability is NOT guaranteed.

The P2-I global `LocalLlmCpuBoundaryObserverRegistry` is a CPU QA convenience,
not a safe cross-process logger or a GPU tracing mechanism. Do not simply
enable it for GPU and claim an evidence gate.

### Child-exit correlation

Android `ApplicationExitInfo` (API 30+) provides PID, process name, reason,
timestamp, PSS/RSS and optionally a trace input stream. For an isolated child,
the caller must match **process name, PID and plausible attempt timestamp
window**; do not accept `getHistoricalProcessExitReasons(...).firstOrNull()`
or PID alone. A system exit trace may be absent/overwritten. If present,
parse bounded sanitized thread/driver markers **in memory only**, do not persist
or upload raw ANR trace, arbitrary stack frame contents or user text.

Reference:
https://developer.android.com/reference/android/app/ApplicationExitInfo

## 6. Safety preflight / response and NO-GO gates

These are **requirements for a later explicitly approved native experiment**,
not instructions to use the current APK:

- Verify QA package and exact build SHA/runtime/model hash/device/OS/driver
  identity locally; do not assume prior device-specific evidence transfers to
  another phone. No change to `main`, PR #37 remains draft until formal closure.
- Test only when foreground/resumed, interactive/unlocked, not in
  `ActivityManager.MemoryInfo.lowMemory`; require headroom decided against
  the actual model memory curve. No inference on lock/home/background.
- Use one predefined short, content-free static prompt, constrained input
  estimate and **small** output cap (design target at most 128 generated
  tokens; exact limits subject to later review), explicit GPU only,
  `responseJsonSchema=null`, no speculative decoding, no CPU fallback and
  no real transcript, no 1,988-token reproduction for the first gate.
- Before any native work, main persists `PARENT_ARM_PERSISTED` and checks
  child launch prerequisites. Child must independently reject unauthorized
  requests. A device-health failure, app restart, missing model or policy
  mismatch is a NO-GO (do not opportunistically weaken preflight).
- Detect a lack of progress **as a report-only watchdog**. A Java coroutine
  timeout cannot interrupt a blocked synchronous C++/JNI call. Do not free
  Engine/Conversation, launch a second request, force-kill the service or
  claim release after an unacknowledged native terminal.
- If child crash/Binder death/ANR/driver/UI jank/device reboot occurs,
  **STOP the entire GPU gate**. Preserve the last noncontent evidence and
  Android exit classification; no retry, parameter escalation, fallback
  masking, lock-state tests or automatic app startup experiment.
- Separate-process failure isolation is a hypothesis; never claim system
  graphics safety from child survival alone.
- Record `IDLE` and release verification separately. Short success alone
  is not a PASS of extended real-transcript GPU quality/stability.

A GUI "cancel" tap while the child is blocked must not wait on JNI from the
main/UI thread. It marks cancellation request and uncertainty, not a proved
stopped native job. If native cancellation cannot be confirmed, the device
remains in a blocked QA state until explicit safe intervention; no automatic
restart should silently reload GPU.

## 7. Implementation plan and stage gates

### P2-L — Mock-only interprocess lifecycle, explicitly no JNI/GPU

**Proposed implementation after this design review, not done in P2-K.**

- QA-only private bound Service in a dedicated process, **frozen in fake-only
  mode**; no prod manifest or new launcher; verify that `VoicaApplication`
  excludes only the new child from `AppContainer` initialization.
- Parent-owned bounded metadata ledger; fake callback and Binder death
  exercises; accurate PID/process-name match; no cross-process
  SharedPreferences-based communication.
- Simulated tests: preflight denial, unbound/failed bind, service death,
  callback before SEND_RETURNED, submit never returns, missing terminal,
  cancellation with/without acknowledgment, child lingering, stale process
  event, duplicate/concurrent admission, poisoning, stream truncation,
  parent kill/restore and no user content.
- Unit + instrumentation/static source checks must prove
  `GPU_QA_SUSPENDED=true` and no LiteRT-LM Engine/Conversation calls
  reachable from that mock Service.
- CI exact commit, QA APK build, and optional **mock-only** device launch
  if needed. A mock PASS can only validate IPC/lifecycle/evidence behavior.

### P2-M — Real-native isolated QA design review (not authorized)

After P2-L source/CI/mock evidence, independently review native owner-thread
lifecycle, APK native provenance, evidence plumbing, model lease, process
death and device safety. Produce a dedicated build design and require
explicit approval. **No runtime upgrade or GPU execution in P2-K/P2-L.**

### P2-N — One explicit GPU short probe (not authorized)

Only if a later user approval covers a specific SHA/APK, device,
configuration, abort criteria and data-handling plan. One short GPU run cannot
close the prior medium-text ANR, quality or sustained stability questions.
No test may start because a document was merged or a CI check passed.

## 8. Stage decision

**P2-K design gate: COMPLETE (documentation only); GPU native implementation:
NOT STARTED; GPU requalification: NOT AUTHORIZED.**

Approved decision for the next engineering proposal is **P2-L mock-only**
with a dedicated QA process and parent-owned evidence store, not a change to
the current LiteRT-LM 0.18.0 GPU scheduling. Preserve production summary CPU
containment, P2-D GPU/AUTO quarantine and the separate device QA authorization
requirement.
