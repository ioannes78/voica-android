# Stage 17.3 R17C P2-M4A — JVM-Only Fake Cross-Process Ownership Matrix

Status: **SOURCE IMPLEMENTED / EXACT-HEAD CI PENDING / MOCK-ONLY / NO ANDROID DEVICE QA / NO REAL PROCESS OR GPU**.

Repository: ioannes78/voica-android.
Implementation baseline: d13d33e5985705bc091abd008eb988648f2e1c60.
Development branch: stage17-fully-offline-intelligence; PR #37 OPEN / DRAFT / UNMERGED.
Production main: 28ec9a837c5b0debac8e6d32ab99e0cf8d80be12.

## Why P2-M4A exists

The reviewed P2-M4 architecture identified that ModelUseRegistry counts
and LocalLlmRuntimeCoordinator's Mutex/Poison are local to the Android
process. AppLocalLlmRuntime finally closes the model version lease even
when a runtime has quarantined uncertain native ownership.
DefaultModelManager.removeDownloadedVersion checks an in-memory
ModelUseRegistry and then deletes bytes without an atomic shared
execution/mutation transaction. These are **source-identified safety
gaps**, not reproduced model deletion or a GPU fix.

P2-M3 QA5 did prove MOCK child death / parent alive / BINDER_DIED /
durable poison / reenter blocked. P2-M4A covers **parent-death
logical policies** in synthetic JVM state tests, without killing the
user's phone or launching Android Binder processes.

## Implemented source

- Stage17P2M4AFakeOwnershipPolicy.kt: independent pure Kotlin classes,
  FakeJournal, strict content-free FakeCodec, FakeArbiter and fake owner
  states. All agents share one *in-memory* fixture and synchronize
  on that fixture, simulating serialization of a hypothetical global
  transaction. **This is not an interprocess lock implementation.**
- Explicit verifiedNeverArmedTestFixture is the ONLY clean seed; a
  null, truncated, invalid, untrusted or failed fake journal never
  becomes clean by default.
- Fake status path:
    VERIFIED_NEVER_ARMED
      -> INTENT_DURABLE
      -> CHILD_ADMITTED (QA_CHILD) / NATIVE_ACTIVE (fake PRODUCT_CPU)
      -> NATIVE_ACTIVE
      -> TERMINAL_OBSERVED
      -> OWNER_RELEASE_REPORTED
      -> RELEASE_CONFIRMED_DURABLE
  All intermediate states deny a second fake native owner, including
  after a logical parent restart or a freed conceptual OS lock.
  RELEASE_UNCONFIRMED moves to durable UNRESOLVED_POISONED if the
  synthetic commit succeeds. A simulated commit failure leaves the
  durable previous state (still blocking) and marks the fake fixture
  uncertain; no automatic clean or retry.
- Exact pinned fake model identity: modelId, version, revision, 64-char
  hex SHA-256. The child must present the identical pin; generation
  and UUID-shaped nonce must match, and replayed state transitions
  cannot mutate the journal.
- Fake PRODUCT_CPU owner and QA_CHILD owner compete for a shared
  conceptual single slot. A second fake owner is rejected. The
  mocked "model mutation" gate blocks removal, rollback, activation,
  replacement or referenced cleanup while a model owner is active or
  poisoned. Independent immutable staging is separately allowed.
- Legacy local lease close does not alter fake journal model-ownership
  state; a quarantine means deletion stays denied.
- No actual model IDs, paths, contents, prompts, generated responses or
  PIDs are written to Android preferences or files. Fake test model
  identity/nonce and phase are held in JVM memory only.
- The policy has NO call path from product AppLocalLlmRuntime,
  DefaultModelManager, real native Engine, P2-L Service or QA UI.
  No Android manifest, Room schema, model channel or dependencies
  are modified.

## Automated pure JVM acceptance matrix

Stage17P2M4AFakeOwnershipPolicyTest:
1. Parent death / failed initial durable arm denies admission.
2. Parent death after arm before child binding stays blocked.
3. Parent death after fake child admitted, even if a fictional OS
   lifetime lock becomes free, stays blocked.
4. Parent death after fake terminal or release ack but before
   durable release commit stays blocked.
5. Simultaneous fake product CPU and child clients (40 contenders)
   yield exactly one admitted owner through a shared fixture.
6. CPU owner and child cannot both own the fake model.
7. Active and poisoned state block model delete/rollback/activation/
   replacement/referenced cleanup; unrelated staging can continue.
8. RELEASE_UNCONFIRMED followed by legacy ModelLease.close cannot
   clear the global fake model-deletion barrier.
9. Changed pinned revision/hash during child handoff is rejected.
10. Generation/nonce mismatch, stale/replayed child/terminal/release
    messages cannot mutate the journal.
11. Missing, malformed, truncated and wrong-version fake journals
    fail closed.
12. A failed final durable release commit never converts release
    acknowledgment into admission.
13. A complete fake terminal+release+durable commit gives historical
    RELEASE_CONFIRMED and can re-admit a NEW FAKE attempt only.
14. Persisted content remains scalar-only; unrelated/invalid process
    exit metadata cannot clear the journal.

Exact source CI must run JVM tests and static checks. Unit-test
success proves policy logic, NOT actual synchronized Android
interprocess admission, FileLock behavior or process death handling.

## Remaining safety blocks

- P2-M4B: design/mock-only Android parent-process death fixture,
  lifecycle isolation and actual cross-process admission gate.
  Requires independent review and explicit test approval. Do not
  touch or reset the P2-M3 Poison on the user's phone.
- P2-M4C: product-wide real shared gate, immutable model-version
  lease, mutation protocol and CPU/ASR/diarization regression.
  No implementation is authorized by this JVM matrix.
- JNI/Engine/Conversation close thread affinity remains unverified.
- GPU/AUTO remains hard QA-suspended. P2-N GPU is NOT AUTHORIZED.
- PR #37 stays Draft; production main remains unchanged.

This subgate has **NO required APK or real-device test**.
If the repository automatically executes a diagnostic APK workflow
for a commit, that build is not P2-M4A device authorization and
should not be distributed as a new QA test instruction.
