# Stage 17.3 R17C P2-M4 — Cross-process Model Ownership and Parent-Death Safety

**Status: DESIGN REVIEW COMPLETE / NATIVE BRIDGE NO-GO / NO JNI OR GPU / NO SOURCE IMPLEMENTATION.**

Repository: ioannes78/voica-android.
Source-reviewed HEAD: 36c612f2de34875c27fc01d043c2ba5bcec4926e.
Development branch: stage17-fully-offline-intelligence, PR #37 OPEN / DRAFT / UNMERGED.
Production main: 28ec9a837c5b0debac8e6d32ab99e0cf8d80be12, unchanged.

P2-M3 QA5 established that a QA-only fake child process can die after
SEND_ENTERED, while the live parent observes BINDER_DIED, durably commits
UNRESOLVED_POISONED, and on reopening restores STICKY_MOCK_POISON and
ApplicationExitInfo correlation. Exact APK source:
0b093f7a99d336a493addbfa51921a7d533a8d53; Android PR CI #43813956689
and diagnostic APK #43813956597 both SUCCESS.
This is **child death / parent survives** evidence, not parent death,
native Engine release or GPU safety.

## 1. Verified source audit

| ID | Exact current source observation | Risk / disposition |
| --- | --- | --- |
| M4-01 | core/model/.../ModelUseRegistry.kt uses synchronized in-memory counts with a closeable ModelLease. | Model-use counts are not cross-process and disappear on parent death: BLOCKER. |
| M4-02 | engine/local-llm/.../LocalLlmRuntimeCoordinator.kt has an instance-local coroutine Mutex and AtomicBoolean poison. | Cannot prevent a second owner in another process or after process restart: BLOCKER. |
| M4-03 | app/.../VoicaApplication.kt creates one ModelUseRegistry for its AppContainer and passes it to ModelManager, ASR and diarization. | Correct within the same container; a future child uses a different process: BLOCKER. |
| M4-04 | app/.../AppLocalLlmRuntime.kt resolves activeModel before acquiring a lease and unconditionally closes that lease in finally. | Native quarantine may outlive the model-file use counter. A deletion after uncertain close is possible under existing checks: HIGH-PRIORITY BLOCKER. |
| M4-05 | engine/local-llm/.../LiteRtLocalLlmRuntime.kt retains QuarantinedNativeSession after unacknowledged cancel or close and reports RELEASE_UNCONFIRMED. | Native ownership remains uncertain even when the application finally scope runs: BLOCKER. |
| M4-06 | core/model/.../DefaultModelManager.kt removeDownloadedVersion checks isInUse under its per-manager model Mutex, then changes activation/removes disk files. The generation lease does not acquire this mutex. | Check-to-delete and model-resolution-to-lease have an unclosed concurrent admission window, particularly across processes: BLOCKER. |
| M4-07 | DefaultModelManager.confirmInstalledVersion / rollback and ModelStorage.removeVersion / cleanup alter installed identities. | All relevant activation, rollback, replacement, deletion and cleanup paths need a shared model-version mutation contract. |
| M4-08 | app/.../OfflineSummaryCoordinator.kt owns a summary-level Mutex; reconcileOnStartup interrupts active tasks but never automatically starts inference. | Preserve this useful behavior; Room task state is not native ownership or a global model lease. |
| M4-09 | app/.../Stage17P2LMockCoordinator.kt durably arms a fake journal before bind, receives Binder death and restores poison on UI reentry. | Parent-main-process death while child may survive is still untested; mock preferences are not a native authorization ledger. |
| M4-10 | VoicaApplication lightweight process allowlist covers model_validator and QA stage17_mock_probe only. | Future native QA child requires a new exact guarded process classification, not changing the P2-L fake service into a GPU service. |
| M4-11 | Stage17R17CP2DAnrClosure.GPU_QA_SUSPENDED=true and product AUTO+responseJsonSchema is separately contained to CPU in AppLocalLlmRuntime. | Both boundaries must remain unchanged. No P2-N GPU authorization. |

The concurrency risks are inferred from exact source sequencing; this
review does **not** claim to have reproduced model deletion races on device.

## 2. Two distinct process-failure directions

**P2-M3 proven:** the fake child died and the parent remained alive to
receive BINDER_DIED and commit durable poison. On reopening the Activity,
the journal returned STICKY_MOCK_POISON and preserved exit metadata.

**P2-M4 unproven:** the parent/main process dies before or during child
execution. Its memory registry, mutex and ServiceConnection disappear;
the child may still be scheduled, blocked in JNI, or torn down by
Android. Android is not required to deliver a Binder callback to a dead
client. A new parent creates a new AppContainer; without a global
journal and version gate, it might admit another native owner. It
must not assume unbinding, OS process exit or PID lookup equals native
Engine/Conversation release.

References:
- Android ServiceConnection may reconnect a binding after disconnect:
  https://developer.android.com/reference/android/content/ServiceConnection
- Bound Service lifecycle does not establish synchronous JNI teardown:
  https://developer.android.com/develop/background-work/services/bound-services
- Java FileLock is advisory and automatically ceases after holder exit,
  which is NOT proof that the previous GPU/native state was safely released:
  https://developer.android.com/reference/java/nio/channels/FileLock

## 3. Decision: three independent, cooperating gates

**A. Short-lived interprocess admission transaction.** All native LLM
entry paths, model identity pinning, shared lease claims and mutating
model operations must check and update one durably versioned owner
journal under a cooperative cross-process transaction gate. It
serializes check-and-claim; a process-local Mutex cannot substitute.

**B. Runtime lifetime exclusion held by the actual native child.**
The child, not merely its parent, must hold a native-lifetime gate
through Engine/Conversation ownership. An OS-visible lock can
contribute to exclusion while its holder exists, but automatic lock
release on a crash must NOT reset poison or authorize a second attempt.

**C. Immutable version and deletion barrier.** Pin exact modelId,
version, revision, artifact SHA-256 and canonical managed file as part
of admission. All relevant model activation, rollback, deletion,
replacement and cleanup must honor the claim. Native quarantine/
RELEASE_UNCONFIRMED cannot silently free the effective deletion
barrier, even if the existing local ModelLease.close() runs. This
requires an independent product integration review; do not apply a
blind cancellation/close patch.

Reject: FileLock-only, SharedPreferences-only, Binder-only, or
ModelUseRegistry-only designs. Their failure modes are independent.
The exact implementation of transaction storage, locks, IPC and
mutation coverage is a later gate, not part of this design-only step.

## 4. Future native owner journal (DESIGN ONLY)

    VERIFIED_NEVER_ARMED
      -> INTENT_DURABLE (generation, nonce, backend, pinned model identity)
      -> CHILD_ADMITTED (identity validated + lifetime gate held)
      -> NATIVE_ACTIVE
      -> OWNER_RELEASE_REPORTED (terminal and Engine/Conversation close)
      -> RELEASE_CONFIRMED_DURABLE (historical only)

Any parent or child death, interrupted journal commit, Binder loss,
unacknowledged cancellation, missing native close, inconsistent
identity, stale generation or uncertain ownership:
    -> UNRESOLVED_POISONED (persistent, no automatic reset/retry).

This native journal must **not reuse or clear** the existing P2-L
mock-only owner journal; the P2-M3 installation's Poison must remain.
The missing-journal state is considered clean only if separately
proven never armed. Neither a terminal event nor a free FileLock
establishes RELEASE_CONFIRMED_DURABLE.

The child protocol must validate package/process/UID, one-time
generation/nonce, immutable model version/hash and explicit backend.
There is no auto-rebind, auto-adoption, auto-resume or retry. Parent
death must lead to deny-new-requests even if the child survives.
Do not block a Binder or UI thread on JNI, and never declare a
coroutine timeout to be safe native release. Only content-free
bounded scalar diagnostic evidence may be persisted.

## 5. Model-file mutation contract

All execution paths, including the existing product CPU summary and
future QA child, must participate in global arbitration for the same
model resource. It is not enough to guard a new child while leaving
AppLocalLlmRuntime outside the gate.

Admission must atomically resolve and claim an installed immutable
model identity; the claimed version cannot be removed while a real
owner may be alive or quarantined. Storage operations to block under
the model-version gate include removeDownloadedVersion, relevant
activation/confirm/rollback, replacement and cleanup of referenced
files. Unrelated download into independent immutable staging can
proceed if no pinned bytes or activation metadata are mutated.

If native release is uncertain, the deletion barrier remains. A
process crash does not silently remove the lease. Failure is a
visible BUSY/UNRESOLVED gate outcome, not an automatic cleanup job.
The existing CPU summary, ASR and diarization feature paths require
regression tests before changing shared model-manager semantics.

## 6. Next implementation/validation subgates

**P2-M4A — Pure JVM mock-only arbitration policy (next recommended task).**
Implement a deterministic policy and test adapter with NO Android
Binder, FileLock, JNI, GPU or model file I/O. Acceptance matrix:

1. Parent dies before arm journal commit: no admission.
2. Parent dies after durable arm, before child bind: restart denies.
3. Parent dies after child admission, before terminal: restart denies.
4. Parent dies after fake terminal, before durable release commit: denied.
5. Child dies, OS lock would be free, journal remains dirty: denied.
6. Two fake clients and fake product CPU vs fake child race: at most one owner.
7. Model delete/activate/rollback while active or poisoned: denied.
8. Fake native RELEASE_UNCONFIRMED then local lease close: deletion still denied.
9. Model revision/hash changes during binding: reject handoff.
10. Stale generation/nonce/replayed Binder event: no state mutation.
11. Missing/corrupt journal and ambiguous exit metadata: fail closed.
12. Full fake release plus durable commit: historical confirmation only, never GPU unlock.

**P2-M4B — Separately designed Android parent-death MOCK gate.**
Only after M4A PASS. Must explicitly review test mechanism for
main-process death and child survivorship, and request fresh approval
before any on-device process kill. Never disturb the currently
poisoned P2-M3 QA installation, reset its journal or kill the
user's real main App during this design review.

**P2-M4C — Production/model-manager integration gate.**
Separately review and authorize model admission/deletion/activation
sealing in AppLocalLlmRuntime, DefaultModelManager, ModelUpdateController
and future native child. Must prove no regression to existing CPU
summary/ASR/diarization and model-install workflows.

**P2-N real GPU remains NOT AUTHORIZED.** The prior ~1,988 estimated
token GPU, zero-emission, ANR case is not fixed by process isolation.
Future native GPU testing requires an independent 0.18.0 native
owner-thread/close review, explicit authorization, exact QA APK and
strict device safety criteria.

## 7. P2-M4 review decision

**DESIGN COMPLETE, NATIVE BRIDGE NO-GO.** Proceed next with P2-M4A
mock-only policy and JVM tests, not native code, physical GPU tests,
or further phone operations. Keep PR #37 Draft, main unchanged, QA
GPU/AUTO pre-native suspension intact.

Primary GitHub source files reviewed: ModelUseRegistry.kt,
DefaultModelManager.kt, ModelStorage.kt, AppLocalLlmRuntime.kt,
VoicaApplication.kt, OfflineSummaryCoordinator.kt,
LocalLlmRuntimeCoordinator.kt, LiteRtLocalLlmRuntime.kt,
Stage17P2LMockCoordinator.kt, Stage17P2M1MockRecovery.kt,
Stage17R17CP2DAnrClosure.kt, and the P2-K/P2-M design docs.
