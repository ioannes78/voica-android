# Stage 17.3 R17C-P2-M — Native isolation readiness and failure-recovery safety review

Status: **SOURCE/DESIGN REVIEW COMPLETE — REAL NATIVE / GPU GATE BLOCKED — NO NATIVE IMPLEMENTATION OR DEVICE TEST**.

Repository: `ioannes78/voica-android`.
Source baseline: `dbba18c45b29f8d4fd9c25e5a92d8f6e79c94c35` on
`stage17-fully-offline-intelligence`; PR #37 OPEN/DRAFT/UNMERGED;
production `main` `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`.
This document is a design/audit deliverable, not an approval of real GPU QA.
The explicit P2-D GPU/AUTO pre-native deny and product
AUTO+JSON-schema → CPU containment stay in place.

## 1. Evidence accepted, but not overextended

- P2-L QA1 NORMAL on-device mock IPC: 13 accepted phases, `RELEASE_CONFIRMED`
  at +134 ms; `poisoned=false`.
- P2-L QA2 CALLBACK_BEFORE_RETURN on-device mock IPC: 13 accepted phases,
  `FIRST_EMISSION` at +22 ms before `SEND_RETURNED` at +23 ms;
  `RELEASE_CONFIRMED` at +52 ms; `poisoned=false`.
- The matching QA APK baseline is `544571ca4d09f25b9554e568605f50b934dfa99c`.
  CI `43459371463` and diagnostic releaseQa `43459371471` were SUCCESS.
  Later documentation-only commits received CI; they do not change that APK.
- All P2-L events are FAKE ONLY: **NO LLM / NO JNI / NO GPU**. A simulated
  MODEL_READY / SEND_RETURNED / TERMINAL_DONE is never evidence that the
  real `Engine`, native worker, or GPU released resources.
- P2-I explicit CPU short probe passed once with observed wrapper submission,
  first callback, terminal and post-close PSS; it does not qualify GPU.
- Historical R17C P2-C2-B requested/resolved GPU/GPU, ~1,988 estimated prompt
  tokens, R5 last phase GENERATING with zero emissions and Android ANR(6).
  The old R5 marker precedes `sendMessageAsync`, so GPU SEND_RETURNED remains
  unknown. The native/GPU-driver wait chain and root cause are **NOT resolved**.

## 2. Source audit matrix (the real P2-M blockers)

| ID | Finding directly verified in repository | Native-risk interpretation | Gate |
| --- | --- | --- | --- |
| M1 | `Stage17P2LMockCoordinator` restores only the persisted **text** into `_report`, but constructs a fresh `Stage17P2LMockLedger` with `poisoned=false` and `nextToken=1` on every new instance | Prior unresolved attempt or poison can disappear after Activity/main-process recreation; an old report is not an ownership lock | **BLOCKER**: durable recovery state and attempt generation |
| M2 | Parent `Stage17P2LMockCoordinator.record` queues `SharedPreferences.commit()` on an executor after each event; only the initial arm is synchronously confirmed on IO before bind | Latest delivered phase may be lost after main-process death; `RELEASE_CONFIRMED` visible in UI is not necessarily durably stored | **BLOCKER**: committed-vs-observed distinction, crash consistency |
| M3 | `ModelUseRegistry` uses synchronized in-memory counts; `LocalLlmRuntimeCoordinator` has a process-local Mutex and sticky poison | Starting a new native child would create independent ownership and could race with the main app's offline summary, model update/remove, or another native session | **BLOCKER**: parent-held model lease + process-wide admission / test-only global lock |
| M4 | Parent handles ServiceConnection disconnection/binding death/null binding and has a fake report-only 2.5 s timeout; the real native termination path is not proven when Binder dies | A lost Binder or report timeout is NOT terminal acknowledgment; Android may reconnect a binding and native/driver state may persist beyond service loss | **BLOCKER**: no auto reconnect/retry/close; hard unresolved state |
| M5 | `Stage17R5CrashBreadcrumb` retrieves historical exit using `getHistoricalProcessExitReasons(packageName, pid, 1).firstOrNull()` and a lower timestamp bound; it currently represents the original process breadcrumb, not a dedicated child attempt | PID reuse and unrelated exit could produce false attribution in a future child-process investigation | **BLOCKER**: process name + PID + package UID + attempt time window (+ optional system process summary) |
| M6 | `VoicaApplication.onCreate` bypasses `AppContainer` only for `:model_validator` or exact QA `:stage17_mock_probe` | Future `:stage17_gpu_probe` would boot the entire app container unless explicitly classified; model/database/media initialization invalidates isolation | **BLOCKER**: separate QA-only exact process allowlist reviewed before implementation |
| M7 | Real `LiteRtLocalLlmRuntime.generate` executes in `Dispatchers.IO`, suspends at `terminal.await()`, cancels and releases after terminal or quarantines if acknowledgment is unconfirmed | IO executor can change physical worker after suspension; neither same-thread JNI requirement nor its absence is established by v0.18.0 native code | **BLOCKER**: isolated owner-thread fake tests and native contract review |
| M8 | QA mocked Service is a same-app-UID `android:process` service, not `android:isolatedProcess=true`; its fake protocol allows a reported PID/name from Bundle checked against ActivityManager processes | Good for basic fault-domain separation; same UID allows app-private model access but is not a security sandbox. Process verification is not equivalent to Android-enforced caller identity | **REVIEW**: explicit Binder/UID/session-nonce contract before real native |
| M9 | Existing P2-I first-emission event is delivered through an observer activated only for explicit CPU QA; mock Messenger has a separate phase protocol | Neither can be switched on for GPU by renaming flags or copying the fake event list | **BLOCKER**: dedicated content-free GPU-native observer with durable semantics |
| M10 | No child exit / post-native-release device graphics evidence was obtained during P2-L; historic GPU Android ANR includes HWUI/render waits without a full chain | A child process can die while GPU driver/HWUI resources remain blocked; child separation does **not** prove graphics safety | **PERMANENT SAFETY LIMIT** |

All code observations above are from the exact repository source baseline; they
are **not** reverse-engineered native findings.

## 3. Proposed native-ownership state model (design only)

An event ledger and an **authorization/ownership journal** are different.
The journal MUST be written by the main process, with a monotonic attempt
generation, a random opaque attempt identity, QA APK/model identities,
explicit requested backend, the intended child process name, a parent
launch timestamp and an uncertainty disposition. It retains **no prompt,
model response, transcript, recording name, file path or raw trace**.

Proposed persisted states:

```text
DISARMED
  └─ explicit QA-only admission / durable intent → ARMED_NOT_BOUND
       └─ child identity verified → CHILD_BOUND
            └─ first native ownership event → NATIVE_ACTIVE
                 ├─ send/terminal/release all observed + owner ack
                 │   └─ parent durably commits → RELEASE_CONFIRMED
                 └─ process death / timeout / callback missing /
                    cancellation uncertain / release failure
                     → UNRESOLVED_POISONED (sticky)
```

Mandatory transitions:
1. **Persist the durable ARMED_NOT_BOUND intent BEFORE bind or touching JNI.**
   If commit fails, deny the operation. Never treat the UI display as a
   successful durable record.
2. **Bind identity and attempt generation are immutable.** A stale Binder
   message cannot revive a new attempt; reject duplicates, delayed callbacks,
   mismatched child process identities and protocol-version mismatch.
3. **TERMINAL_DONE / TERMINAL_ERROR are not RELEASE_CONFIRMED.**
   The child must report that both Conversation and Engine ownership were
   safely released, and the parent must durably commit the result. Uncertain
   cancel/close, missing owner acknowledgment, process crash or lost IPC →
   UNRESOLVED_POISONED, never IDLE.
4. **On parent restart**, recover the journal before creating any native
   runtime or presenting a start action. Any ARMED_NOT_BOUND, CHILD_BOUND,
   NATIVE_ACTIVE, interrupted commit or unknown disposition enters
   UNRESOLVED_POISONED. A confirmed closure remains historical evidence;
   it is **not** blanket permission to start another GPU attempt.
5. **On child death/reconnect**, record death and reject any auto rebound
   `onServiceConnected`; release the binding without restarting native work.
   No automatic retry, parameter escalation, native cancellation by a
   restarted process or unsafe close after JNI ownership is unknown.
6. **Poison recovery is not a 'try again' button**. A separate manual
   design-reviewed intervention may collect evidence and explicitly
   authorize reset of only a **mock** attempt. Native GPU authorization
   remains blocked, even after a device reboot, until a separately reviewed
   safety gate.
7. **Write discipline**: initial parent intent, irreversible poison and
   final release confirmation require durable acknowledgment on a non-UI
   worker. The event stream may be best-effort/coalesced; every displayed
   phase should identify OBSERVED vs DURABLY_COMMITTED. Never do PSS,
   SharedPreferences disk I/O, or Binder RPC from JNI callbacks.

This is a state-machine **proposal**, not code and not proof of physical
native teardown.

## 4. Process and model ownership contract (design only)

- Future native Service, if justified, belongs to a separate QA-only
  `:stage17_gpu_probe` process and is `exported=false`. Do not repurpose
  `:model_validator` or convert the P2-L fake Service into GPU by changing
  a boolean.
- Process launch must skip the main app's heavy `AppContainer`, Room,
  recordings, audio playback/ASR and background coordinators.
- Ordinary `android:process` uses the application's UID and can access
  its own app-private model files. It is **not** an `isolatedProcess` sandbox.
  `isolatedProcess=true` changes permissions/UID and requires a reviewed
  IPC/file-descriptor handoff if private models must be read. Neither mode
  isolates GPU kernel/driver state.
- Parent verifies managed model identity/revision/checksum, keeps a
  parent-owned `ModelUseRegistry` lease, and coordinates with normal
  summary/model-update admission. A process-local mutex alone is
  insufficient; the parent must retain authority for every child attempt
  until terminal+release confirmation OR explicit poisoned accounting.
- Model deletion, replacement and reconfiguration are prohibited while
  a child owns or may still own its model. Losing IPC must not immediately
  free the model lease if the native child or GPU state is uncertain.
  A lease is an application-level lifecycle contract, **not** an OS kernel
  file lock or evidence of driver release.
- The native child must be limited to a prevalidated read-only model handle
  or canonical model path, pinned runtime/backend config, bounded work, and
  one local owner thread for initialize/send/cancel/close **as an
  investigation hypothesis**. The v0.18.0 source does not itself prove
  same-OS-thread affinity is required. Do not retrofit this into product
  Runtime without independent source/CI evidence.
- Native callback handlers deliver only bounded scalar phases; they do
  not synchronously write to disk, wait on UI Binder, or close a still-active
  Conversation. If synchronous `nativeSendMessageAsync` never returns,
  **no coroutine timeout can force an orderly release**.

## 5. Child-process exit evidence and attribution

For Android API 30+:
- Query a **bounded collection** from
  `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, maxNum)`.
  Correlate a candidate by
  `ApplicationExitInfo.getProcessName()`, PID, package UID, plausible
  parent-arm/child-start/exit timestamp interval, and optional bounded
  `getProcessStateSummary()` attempt marker if added with explicit review.
- PID alone, a process name alone, or `firstOrNull()` is not adequate.
  Treat a missing trace, unavailable record, reused PID, ambiguous timestamp
  or multiple candidate exits as **UNKNOWN**, not a successful recovery.
- Distinguish `REASON_ANR`, `REASON_CRASH_NATIVE`,
  `REASON_LOW_MEMORY`, `REASON_USER_REQUESTED` and missing system
  history. An ANR reason does not demonstrate a GPU driver defect.
- PSS/RSS are prior system samples; do not treat them as exact exit-time
  measurements or absolute memory-leak proof.
- If a trace is available, parse bounded allowlisted classifications
  transiently in memory. Do not persist the raw trace, whole stacks,
  prompt, conversation text, file paths or device identifiers. No network
  upload. Do not claim precise RenderThread ↔ GPU worker fence ownership
  from trace-wide marker presence.

Android authoritative references:
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/reference/android/app/ActivityManager
- https://developer.android.com/reference/android/content/ServiceConnection
- https://developer.android.com/guide/topics/manifest/service-element

## 6. Mock hardening that MUST precede any native bridge

Recommended next engineering subgate: **P2-M1 — MOCK-ONLY durable recovery and
ownership protocol**, separate code implementation/CI, with all real-native
entry points statically absent/denied.

| Scenario | Required result |
| --- | --- |
| Parent crash after durable arm, before bind | Restart reads unresolved intent and denies new attempt |
| Parent crash after final phase observed but before durable release commit | Restart stays unresolved, not IDLE |
| Restart after durably committed fake release | Historical RELEASE_CONFIRMED preserved; no implicit GPU authorization |
| Child death after SEND_ENTERED / after TERMINAL_DONE but before release | UNRESOLVED_POISONED; no reconnect/retry |
| Auto-rebind from ServiceConnection after death | Ignored and unbound; never resends a native command |
| PID reuse, mismatched process/UID, stale generation, late Binder event | Rejected, no release forgery |
| `ApplicationExitInfo` wrong process / stale timestamp / absent trace | UNKNOWN attribution, not a false ANR match |
| Duplicate terminal, callback before submit return, missing terminal | Logged in genuine observed order; unknown release remains blocked |
| Native-owner release failure represented by fake adapter | Poison sticky across process restart |
| Main and child admission conflict, model deletion under mock lease | Admission/deletion denied while leased; no concurrent native owner |
| Durable write failure or malformed/corrupt journal | Fail closed; do not bootstrap clean native ownership |
| 32-event overflow, repeated unsolicited callback | Bounded evidence, no sensitive content, no UI-thread disk I/O |

Mock tests and static guards must be re-run on the exact HEAD; build/verify
releaseQa only at QA gates, per repository storage policy. Any mock device QA
must be explicitly labeled FAKE and report only IPC/lifecycle behavior.
No GPU, AUTO or long CPU stress requested.

## 7. Decision: strict entry and exit gates

**P2-M review result: DESIGN COMPLETE; REAL-NATIVE ISOLATION NOT READY.
NO-GO for P2-N GPU test.**

To reconsider a real-native QA build later, require **all** of:

1. P2-M1 mock-recovery implementation + JVM/IPC race/static tests PASS and
   mock on-device lifecycle evidence for the additional failure modes.
2. Independent pinned LiteRT-LM 0.18.0 native owner-thread and close semantics
   review; no blind version upgrade or low-priority GPU-context patch.
3. Cross-process exclusive admission + parent-held model lease, no model
   deletion during uncertain ownership.
4. Durable parent journal, exact child exit correlation and no unsafe
   restart/rebind after ANR/crash/uncertain cancel.
5. Explicit dev-build QA-only opt-in, one-attempt limits, no content,
   retained P2-D production/QA safeguards, exact SHA/APK verification.
6. Separate **fresh user authorization** for any first native GPU hardware
   attempt on an identified device/configuration, with stop criteria and
   the explicit acknowledgment that separate processes do not isolate a
   GPU driver or system HWUI.

A single short GPU success would still not qualify the historical
1,988-token medium workload, background/lockscreen safety, repeated
runs, or production GPU enablement.

**Current authorized trajectory**: retain CPU as the proven local LLM path,
retain GPU/AUTO QA suspension, keep PR #37 draft and `main` unchanged.
P2-M1 is the recommended next mock-only engineering task. Real-native
bridge and P2-N are **NOT AUTHORIZED** by this design document.
