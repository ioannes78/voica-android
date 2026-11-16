# Stage 17.3 R17C-P2-M2 — Recovered-owner report separation and mock Binder rebind denial

Status: **IMPLEMENTED / EXACT-SOURCE CI + QA APK PASS / QA4 REAL-DEVICE MOCK REPORT PASS / ACTUAL BINDER DEATH AND GPU NOT TESTED**.

Repository: `ioannes78/voica-android`; baseline before implementation:
`90e0e0d8066128d606caebac64e255870936bbb4`.
PR #37 remains OPEN/DRAFT/UNMERGED. Production `main` remains
`28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`.
This is a **QA-only mock-report/lifecycle enhancement**, not the
P2-N real GPU stage.

## Evidence prompting the change

P2-M1 real-device QA3 recorded PASS across three prescribed MOCK checks:
- normal fake IPC + parent-durable fake release;
- reentering the QA screen with recovered journal verdict
  `p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE` and `p2m1OwnerBlocked=false`;
- fake callback-before-submit-return ordering with
  `p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE`.

On screen reentry the old implementation **concatenated** cached
best-effort phase report text containing
`p2m1OwnerDurability=ARMED_DURABLE` with the new journal recovery fields.
That is an ambiguous presentation, **not evidence that the authoritative
durable journal is ARMED**. P2-M2 fixes the display scope.

## Changes

1. New pure-Kotlin `Stage17P2M2MockReport.recovered` derives current fields
   ONLY from `Stage17P2M1MockJournal.restore`. Distinct
   `p2m2ReportScope=RECOVERED_OWNER_JOURNAL`,
   `p2m1OwnerDurability`, `p2m1OwnerRecovery`,
   `p2m1OwnerBlocked` and current `poisoned` are displayed.
   Current session `eventCount=0` after screen reentry.
2. Previous cached report text is **historical, not current**. Only bounded
   allowlisted `+Nms PHASE` lines are carried into
   `historicalEvent=...` entries (max 32), with an explicit
   `historicalSnapshotOnly` flag and `historicalEventCount`.
   The old stale owner/durability lines and non-phase text are never
   reinserted as current metadata.
3. On a live mock run, `p2m2ReportScope=LIVE_MOCK_ATTEMPT` and
   `historicalSnapshotOnly=false` distinguish the running report.
4. New pure-Kotlin `Stage17P2M2MockConnectionPolicy.canSend` denies
   sending any request on a stale or disconnected ServiceConnection,
   inactive token, poisoned owner, pending release commit, wrong Service
   component or null Binder. The Android `onServiceConnected` path uses
   this actual guard, not just a unit-test stub.
5. The **FAKE ONLY** Service clears its previous token/nonce/reply messenger
   on `onUnbind`. This removes a stale mock attempt after normal unbinding
   if Android reuses the child process. It does NOT show that a native
   Engine/Conversation can safely be released.
6. Pure JVM tests verify current vs historical field isolation, durable
   poison/ARMED fail-closed recovery, malformed journal blocking,
   bounded historical events without user data, and no automatic
   resubmission on late Binder reconnect.
7. Static CI contract is extended to fail if these boundaries disappear.
   P2-D explicit GPU/AUTO deny, LiteRT-LM pin at 0.18.0,
   formal AUTO+JSON-schema→CPU containment and production local LLM code
   remain untouched.

## Example expected QA output on reopening a previously completed mock

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
p2m2ReportScope=RECOVERED_OWNER_JOURNAL
poisoned=false
p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE
p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE
p2m1OwnerBlocked=false
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=NOT_CHECKED
eventCount=0
historicalSnapshotOnly=true
historicalEventCount=13
historicalEvent=+0ms PARENT_ARM_PERSISTED
... up to 32 historical events
```

These historical rows come from a **best-effort report cache**. They are
neither fresh runtime events nor durable proof of native release.

## Acceptance and gaps

- Exact new HEAD: Android PR CI static/JVM/Debug PASS, releaseQa APK PASS.
- Optional QA4 on a real phone may verify **only** report presentation by
  opening P2-L without launching any new fake request. Preserve existing
  installed data and test APK signing; do not run GPU/AUTO.
- A fake late Binder callback is exercised in JVM policy tests. **Real
  child process kill/restart, actual Binder reconnection, process UID
  spoofing, an unstable GPU driver and native close behavior have NOT been
  tested**. Do not equate source guard tests with Android lifecycle proof.
- `Stage17P2M1MockAdmission` remains a fake process-local exclusivity
  demonstration. True cross-process lock, real ModelUseRegistry lease,
  corrupted-record recovery policy and `ApplicationExitInfo` provenance
  for genuine native GPU work require separate design/gates.
- Do NOT add a GPU execution Service, remove P2-D quarantine, or merge
  PR #37 based on this source change.

**P2-M2 scope closure is conditional on exact-head CI and APK evidence.**
Real native/GPU qualification remains NOT AUTHORIZED.


## P2-M2 implementation correction — deferred fake callbacks

Follow-up source review revealed an additional hazard in the mock-only
Service: an already-posted fake callback used to read the live mutable
activeToken and attemptNonce fields when it eventually executed.
After unbind followed by a new bind, it could be mislabeled as the
new attempt even though it was posted by the previous attempt.

This is now guarded by Stage17P2M2MockCallbackGuard: every fake
delayed event captures the original token + nonce; it must still match
the current active attempt before any phase is emitted. The fake
onUnbind reset and the parent's ServiceConnection identity admission
remain intact. Pure JVM tests cover old-token, old-nonce, closed and
rebound scenarios. This is a MOCK callback protocol invariant, not
a LiteRT-LM callback or GPU/native release proof.

The exact post-change CI must also validate the new static source
invariant before P2-M2 is considered code-gated. Real Binder death,
actual OS rebind timing and all native/GPU behavior remain untested.

## P2-M2 QA4 real-device recovered report — user-submitted

Source baseline of installed P2-M2 QA APK:
`6725912052a122861b875e7bea08d17c5d0b2ada`.
Exact-source verification: Android PR CI run `43680700134` **SUCCESS**;
Stage 17.3 Diagnostic releaseQa APK run `43680700122` **SUCCESS**.

User opened Stage 17 P1 / P2-L after cover-installing the P2-M2 mock-only APK,
**without launching a new mock request**, and copied the report:

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
p2m2ReportScope=RECOVERED_OWNER_JOURNAL
poisoned=false
p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE
p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE
p2m1OwnerBlocked=false
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=NOT_CHECKED
eventCount=0
historicalSnapshotOnly=true
historicalEventCount=13
historicalEvent=+0ms PARENT_ARM_PERSISTED
historicalEvent=+5ms BIND_REQUESTED
historicalEvent=+11ms CHILD_BOUND
historicalEvent=+12ms MODEL_INITIALIZING
historicalEvent=+12ms MODEL_READY
historicalEvent=+13ms CONVERSATION_ENTERED
historicalEvent=+13ms CONVERSATION_READY
historicalEvent=+14ms SEND_ENTERED
historicalEvent=+14ms FIRST_EMISSION
historicalEvent=+15ms SEND_RETURNED
historicalEvent=+15ms TERMINAL_AWAIT_ENTERED
historicalEvent=+54ms TERMINAL_DONE
historicalEvent=+55ms RELEASE_CONFIRMED
```

**QA4 MOCK REPORT PRESENTATION = PASS (one on-device observation).**
All current owner fields come from recovered owner journal:
`p2m2ReportScope=RECOVERED_OWNER_JOURNAL`,
`p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE`,
`p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE`,
`p2m1OwnerBlocked=false`, `poisoned=false`, and
`p2m1ReleaseCommitPending=false`.
No current events were generated (`eventCount=0`); the 13 prior phases
are explicitly marked historical and preserve callback-before-return order.
The old cached `ARMED_DURABLE` value is no longer presented as current.
`p2m1ChildExitCorrelation=NOT_CHECKED` is expected: no child crash occurred.

**P2-M2 scope result: SOURCE/JVM/CI/APK + QA4 REPORT-PRESENTATION PASS.**
This is not a pass for actual Binder child death/rebind, forced app restart,
unacknowledged native cancellation, real model resource exclusivity, or GPU
driver/HWUI recovery. Those remain separate gates. P2-D GPU/AUTO suspension
stays **HARD BLOCKED**, and P2-N real GPU is **NOT AUTHORIZED**.
No production `main` merge, model change, or JNI call is made in this
evidence-only archival commit.
