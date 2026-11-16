# Stage 17.3 R17C P2-M4B — Disposable Parent / Android Binder Lifecycle QA Design

**STATUS: DESIGN REVIEW COMPLETE / NON-DESTRUCTIVE FIXTURE SELECTED / ANDROID PROCESS IMPLEMENTATION NOT STARTED / ON-DEVICE FAULT INJECTION NOT AUTHORIZED.**

Repository: ioannes78/voica-android.
Source baseline: 4be791cf965680ca88bd924791f50a255be44c90.
Branch: stage17-fully-offline-intelligence; PR #37 OPEN/DRAFT/UNMERGED.
Production main: 28ec9a837c5b0debac8e6d32ab99e0cf8d80be12.

## 1. Facts already proven; strict limit

- P2-M3 QA5 installed source 0b093f7a99d336a493addbfa51921a7d533a8d53 showed **mock child death while the real main App survives**: BINDER_DIED, persisted UNRESOLVED_POISON_DURABLE, screen reopen STICKY_MOCK_POISON, and correlated Android exit metadata MATCHED.
- P2-M4A implements a pure Kotlin **synthetic in-memory shared-journal arbitration fixture**, with 14 JVM tests and exact-source CI SUCCESS on the development branch. It is NOT a real Android cross-process lock and has not killed any parent process.
- **Never reuse or reset** the P2-M1/P2-M3 journal at stage17_p2m1_mock_owner_journal. The user's QA device deliberately remains STICKY_MOCK_POISON. This is a separate immutable evidence stream.
- The historical GPU zero-emission ANR remains unresolved. QA GPU/AUTO is suspended by Stage17R17CP2DAnrClosure; no LiteRT-LM Engine, JNI, native model, or GPU in P2-M4B.

## 2. Platform-source constraints verified

Android SharedPreferences **does not support use across multiple processes**. MODE_MULTI_PROCESS is deprecated and cannot provide atomic concurrent updates. An implementation with separate parent and child processes reading/writing the same SharedPreferences is NOT acceptable for owner authorization.
https://developer.android.com/reference/android/content/SharedPreferences
https://developer.android.com/reference/android/content/Context

The ServiceConnection.onServiceDisconnected event means a service connection was lost; Android may reconnect an existing binding. It does NOT prove the child cleaned up all native resources, and a later onServiceConnected is not permission to replay a command.
https://developer.android.com/reference/android/content/ServiceConnection

A purely bound Android Service is normally destroyed when its last client unbinds or disappears. Therefore, a parent-process death can cause the bound child to disappear, *not* guarantee a surviving child. Any test that adds a second binder client or starts a child to keep it alive CHANGES the lifecycle and must be reported as a deliberately retained-survivor fixture, not natural parent-death behavior.
https://developer.android.com/develop/background-work/services/bound-services

A remote IBinder.linkToDeath callback can detect the death of the process hosting that remote binder, but notification has timing and state-of-observation limits; it is not a native teardown acknowledgment.
https://developer.android.com/reference/android/os/IBinder

## 3. Critical decision: do NOT kill the installed App's main process

The already installed P2-M3 QA application contains important historical evidence and sticky poison. Killing its real main process could interrupt unrelated app tasks and would make it difficult to separate test fixture behavior from product behavior. There is no approval for that high-impact action.

Selected implementation direction for the next code-only subgate:

- A dedicated **disposable QA surrogate parent Service** runs in process :stage17_m4b_parent.
- A dedicated **fake QA child Service** runs in process :stage17_m4b_child.
- The normal QA app Activity/main process is a **controller/observer**, not the process deliberately killed. It remains alive so the user can copy the report.
- Only an explicit, guarded command can make the **surrogate parent kill itself**. No code path may call killProcess with the main process PID. Never kill the P2-L child, model validator, or GPU-related processes.
- This tests real Android Binder/process lifecycle behavior for **a parent-like disposable process**, and is a narrower proof than killing the actual application main process. Do not label it MAIN_PROCESS_DEATH_PASS.

P2-M4B has two individually labeled scenarios:

A. **PURE_BOUND_PARENT_LOSS**: surrogate parent binds fake child, reports child-admitted, then self-exits. No observer binds to child. OS may clean up the child after its last binding disappears. Record observed outcome as CHILD_EXITED / CHILD_STILL_OBSERVED / UNKNOWN; no assumed survival.

B. **INTENTIONALLY_RETAINED_CHILD**: before parent exit, a second separately identified QA observer binds the fake child *only to deliberately hold it alive*. After the parent dies, verify that the second client still sees the child, that any new command from a resurrected surrogate parent is rejected by the durable owner gate, and that stale nonce/generation messages are rejected. This tests a retained survivor under extra-binding conditions, not natural pure-bound Service behavior.

A third test may later kill the **actual** main process, but only in a separate disposable test installation/application ID, with an independent approval and explicit evidence-retention strategy. It is OUT OF SCOPE in P2-M4B.

## 4. Three process responsibilities and ownership of persistence

**Controller (QA app main process; NO native LLM):**
- Owns exactly one separate P2-M4B journal namespace, e.g. stage17_p2m4b_disposable_parent_journal, and serializes all journal operations on a single non-UI worker; parent and child must NOT open this preference file independently.
- Durably writes INTENT_DURABLE and a monotonic generation plus random nonce BEFORE binding the disposable parent; if write is not confirmed, do not launch either process.
- Is the **only writer**; handles ACKed state transitions via strict generation/nonce/UID/process identity, not arbitrary phase text. Never treat a connected binder or returned message as release confirmation.
- Observes surrogate parent Binder death and writes UNRESOLVED_POISONED (or retains a prior durable non-release state on write failure); no automatic reconnect or duplicate request.
- Reopening the QA page displays current recovered journal state and separate historical events; NEVER reconstruct ownership from cached text, live process lists, or ApplicationExitInfo.

**Disposable QA parent process:**
- Exact QA-only process name and non-exported Service. Lightweight onCreate bypasses normal AppContainer/Room/ASR/BLE boot.
- Accepts at most one run with exact generation/nonce/model FAKE identity and an independent controller callback binder.
- Binds fake child once, sends child-bound fake phase and waits for an explicit controller-triggered self-exit. No background timer may spontaneously kill any process.
- Before self-termination it reports intent on IPC without claiming an accomplished durable release; the controller must already be durably ARMED.
- Only killProcess(myPid()) after checking the current actual process name is *exactly* applicationId:stage17_m4b_parent AND the package ends .qa AND the run is a one-shot explicitly confirmed QA death scenario. Never use PID from a Bundle.

**Fake QA child process:**
- Exact QA-only process name, non-exported Service, no Engine/Conversation/LLM/JNI/GPU.
- Captures original generation and nonce for every delayed callback. Never accept an automatic second run, replayed token or new controller without fresh durable authorization.
- On parent Binder death, if it remains alive, it may report scalar PARENT_LINK_LOST to the separate observer, but cannot issue a new model request or claim ownership released.
- Rebind after parent death is NOT a resume permit.
- If a test holds a second binding to preserve child survival, tag the run INTENTIONALLY_RETAINED_CHILD.
- Never block synchronous callback threads waiting on artificial native operations.

## 5. Strict state-machine requirements

    NEVER_ARMED_PROVEN_BY_SEPARATE_TEST_FIXTURE
      -> INTENT_DURABLE
      -> PARENT_BOUND
      -> CHILD_ADMITTED
      -> FAKE_OWNER_ACTIVE
      -> [one-shot surrogate parent death]
      -> UNRESOLVED_POISONED (sticky; no new fake owner)

Simulated clean release case (separate fixture, no death):

    FAKE_OWNER_ACTIVE
      -> TERMINAL_FAKE_OBSERVED
      -> CHILD_RELEASE_ACK_FAKE_OBSERVED
      -> PARENT_RELEASE_ACK_FAKE_OBSERVED
      -> RELEASE_CONFIRMED_DURABLE

A delivered fake terminal alone is NEVER the final release. Death before a commit, timeout, history ambiguity, stale token, journal corruption, callback lost, or controller restart while ARMED => deny/recover as unresolved. Never auto-adopt child or synthesize RELEASE_CONFIRMED_DURABLE from an available FileLock or Binder death.

**No cross-process SharedPreferences.** A controller-owned broker (or dedicated non-exported ContentProvider with atomic transaction and a single writer) is required. P2-M4B may demonstrate controlled journal recovery via the controller but is NOT the final production-wide resource arbitration. Model-manager integration is P2-M4C only.

## 6. Safe UI/QA conditions (future, not yet authorized)

- Existing Stage 17 P1 QA diagnostics stay visible; a new P2-M4B section must clearly say **DISPOSABLE PARENT ONLY — MAIN APP SURVIVES — MOCK / NO JNI / NO GPU**.
- A read-only **dry-run** button reports the state but never binds or kills a process.
- Separate two-stage confirmation for the one-shot test, with full notice: process to terminate, how recovered poison is expected to behave, and that subsequent attempts remain blocked.
- If the M4B journal is already ARMED, malformed or poisoned, disable all death-test and admission buttons. No reset button, no clearing app data, no touching P2-M3 preferences.
- Start with a verified clean *new M4B namespace*. Never equate missing state in a previously used namespace with verified never armed.
- Hard stop on wrong package, process name, UID, nonce, model identity, failed durable commit, lost Binder, timeout or any unexpected callback order.
- After one deliberate surrogate parent death, copy the live report, reopen and copy the recovered report. Do not repeat the fault test on that installation.
- No actual device fault operation until code reviewed, exact source CI and APK built and the user explicitly approves this specific test and its scope.

## 7. Acceptance matrix / CI contract for later implementation

| Case | Required verdict |
| --- | --- |
| Missing/corrupt controller journal before bind | DENY; no Service launch |
| Parent dies after durable INTENT but before child bind | Reopen DENY; no implicit replay |
| Parent dies after child admission, pure-bound variant | Durable sticky poison; child fate reported as observed/unknown |
| Parent dies with independent observer holding child | Surviving child observed without second fake ownership |
| Parent exit between fake terminal and durable final commit | Poison/unresolved, not released |
| Callback/rebind after parent death | No second message or generation |
| Two competing simulated owner requests | Exactly one durable ownership generation |
| Parent sends stale nonce/attempt or spoofed process | DENY without mutation |
| Controller process dies after arm | On return, durable journal forces deny; do NOT claim this is actual main-process kill QA |
| OS exit info absent, ambiguous, wrong PID/UID or timestamp | UNKNOWN attribution; no authorization |
| Native/mock lease locally closes while global fake owner unresolved | Fake model mutation remains denied |
| Android executes no JNI/GPU | Source/manifest/static and APK contract proof |

JVM policy tests from M4A are retained. Add pure JVM process authorization tests and source static checks for the two exact QA service processes, non-exported manifests, one-shot death guard, no real native imports, and strict journal isolation. Add bounded Android device QA only if and when explicitly approved. Android process liveness and Binder behavior cannot be fully proven by JVM unit tests.

## 8. Gate split and decision

**P2-M4B0 (this change): DESIGN / CONSTRAINTS / SAFETY REVIEW COMPLETE.**

**P2-M4B1 (next, code-only):** implement isolated disposable-parent and fake-child QA service skeleton, controller-owned journal/IPC and static/JVM gates. Keep all fault-injection actions gated behind a separate explicit confirmation, and do not run a device test or distribute a new APK without a QA handoff. No product LLM/model mutation integration.

**P2-M4B2 (later):** exact-source QA APK and optional single on-device test, *only after user explicitly authorizes* disposable-parent death. Nothing may kill the actual App main process.

**P2-M4C (later):** independently approved model-manager and real cross-process model ownership integration; verify CPU summary, model updates, ASR and diarization before any real native child is considered.

**P2-N GPU: NOT AUTHORIZED.** Historical GPU/HWUI ANR remains unresolved. P2-D QA GPU/AUTO suspension MUST stay enabled. PR #37 remains DRAFT/UNMERGED; production main remains unchanged.
