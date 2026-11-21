# Stage 17.3 R17C P2-M3 — QA mock child-process death and fail-closed recovery

Status: IMPLEMENTED / SOURCE CI + APK SUCCESS / QA5 TWO-STEP REAL-DEVICE MOCK BINDER DEATH + STICKY POISON RECOVERY PASS / REAL JNI AND GPU NOT TESTED.

Repository: ioannes78/voica-android.
Implementation baseline: c690f316868ac05ea1cc2554f6c0f1caac13e2f2.
Branch: stage17-fully-offline-intelligence. PR #37 stays OPEN/DRAFT.
Production main is unchanged at 28ec9a837c5b0debac8e6d32ab99e0cf8d80be12.

## Objective and scope

P2-M1 normal IPC, callback-before-return and reopen evidence passed on a
real QA device. P2-M2 report separation also passed QA4. Real child death,
post-death poison restoration and Android exit-history correlation have
not yet been device-tested.

P2-M3 adds a QA-only process-lifecycle fault: a dedicated
:stage17_mock_probe Service reports fake phases through SEND_ENTERED and,
after a short delay, intentionally terminates its own child process.
It never kills the host App, creates LiteRT-LM Engine/Conversation,
runs JNI or GPU, or changes the production manifest, model runtime,
Room schema or production summary.

This is controlled fake child-process death, not an ANR or a GPU-driver test.

## Implementation and safeguards

- New fake scenario CHILD_PROCESS_SELF_EXIT_AFTER_SEND is appended to
  Stage17P2LMockScenario, preserving older enum ordinal values.
- Stage17P2M3MockDeathGuard permits Process.killProcess(Process.myPid())
  only when all of the following are true: applicationId ends in .qa,
  process is exactly applicationId:stage17_mock_probe,
  nativeExecution is compile-time false, the one-shot fake death scenario
  was explicitly selected, and its original token/nonce remain active.
- The QA screen requires a two-step opt-in; it warns that the mock owner
  will remain poisoned, blocking all subsequent P2-L fake requests on
  that installation until a separate future mock-only reset design.
  The parent process is not force-stopped.
- Child sends SEND_ENTERED and never sends a simulated terminal or
  RELEASE_CONFIRMED in this scenario. ServiceDisconnected/BindingDied
  records BINDER_DIED; if a Binder callback never arrives in time the
  existing 2500ms watchdog records REPORT_TIMEOUT. Either event must
  poison the durable fake owner and forbid retries.
- Parent unbinds a failed connection. A per-ServiceConnection commandIssued
  flag prevents a repeated onServiceConnected from ever resending a
  command, even if callbacks arrive out of order.
- The already durable ARMED journal fails closed after interrupted
  release, even if persisting UNRESOLVED_POISONED subsequently fails.
- After Binder death, the parent queries bounded Android
  ApplicationExitInfo metadata (API 30+) up to four times, at most
  450 ms apart, for exact PID, processName, UID and attempt time window.
  OS history may be absent or ambiguous; UNKNOWN never means success.
- A normalized scalar verdict tied to the journal generation is persisted
  separately, without prompts, transcripts, paths, PIDs or raw traces.
  Reopen displays p2m3RecoveredExitEvidence; it is NEVER an ownership
  release or native-safety decision.
- The P2-D GPU/AUTO pre-native quarantine remains hard enabled; P2-N
  remains NOT AUTHORIZED.

## Automated tests

Stage17P2M3MockDeathTest and Stage17P2M3MockExitEvidenceTest are pure JVM:
1. Only exact QA child and explicitly selected scenario can terminate.
2. BINDER_DIED after fake SEND_ENTERED poisons, no RELEASE_CONFIRMED.
3. REPORT_TIMEOUT fallback also poisons and forbids retry.
4. Durable UNRESOLVED_POISONED reentry yields STICKY_MOCK_POISON.
5. Stale Binder connection cannot resend.
6. PID/UID/process/timestamp mismatch or reuse cannot be misattributed.
7. Persisted exit verdict requires matching generation and allowlisted scalar.
8. CI static source checks reject a real model/JNI call in fake service.

No Android actual death is proven by JVM tests.

## QA5: one-shot MOCK-only real-device acceptance

Only after both exact-head CI and releaseQa build pass, cover-install the
QA APK without uninstalling or clearing data. The initial mock owner must
display LAST_MOCK_RELEASE_DURABLE and p2m1OwnerBlocked=false.

1. Open Stage 17 P1 -> P2-L diagnostic, tap
   准备 P2-M3 子进程死亡演练. Read the warning, then tap
   确认一次性模拟子进程死亡 exactly once.
2. Keep the main App and QA screen foreground; do not force-stop,
   background-kill, reboot, clear app data, or run GPU.
3. After the fake child exits, copy the complete report. Expected:
   poisoned=true, p2m1OwnerBlocked=true,
   p2m1OwnerDurability=UNRESOLVED_POISON_DURABLE,
   plus BINDER_DIED (or REPORT_TIMEOUT if Android Binder event is
   missing). Any reported childExitCorrelation MATCHED/UNKNOWN/
   AMBIGUOUS is diagnostic only; do NOT infer a GPU ANR.
4. Leave and reopen Stage 17 P1 without starting another mock.
   Copy the recovered report: expect
   p2m1OwnerRecovery=STICKY_MOCK_POISON, p2m1OwnerBlocked=true,
   poisoned=true, eventCount=0, and only historicalEvent= rows.
   The p2m3RecoveredExitEvidence may be MATCHED, UNKNOWN,
   AMBIGUOUS or NOT_RECORDED depending on OS history/timing.
5. Do not press other mock buttons after poisoning and DO NOT
   clear application data. The permanent mock-only block is
   intended for this controlled QA exercise.

QA5 PASS for Binder-loss coverage requires BINDER_DIED and durable
Poison restored on reopen. If only REPORT_TIMEOUT is observed, the
fail-closed watchdog passes but the Binder death callback remains
UNVERIFIED. An automatic retry or lost poison is FAIL/BLOCKED.

Caution: one-shot poison prevents other P2-L mock runs with this
installation, by design. A separate test-fixture mock-only reset
must be reviewed if further tests are required.

## Strict limits

This work does NOT prove true ModelUseRegistry cross-process leasing,
native JNI thread-affinity, LiteRT-LM cancellation/close safety,
GPU driver/HWUI resource release, or system-wide ANR isolation.
GPU/AUTO remains FAIL/SUSPENDED and PR #37 remains DRAFT/UNMERGED.


## QA5 closure — on-device child death + reopen (user-provided evidence)

**VERDICT: QA5 TWO STEPS PASS WITH LIMITED MOCK-ONLY COVERAGE.**
Installed diagnostic APK source:
`0b093f7a99d336a493addbfa51921a7d533a8d53`.
Android PR CI `43813956689` = SUCCESS;
Stage 17.3 QA APK `43813956597` = SUCCESS.
This is the EXACT code source used for the QA5 device run; the present
documentation-only update does not change runtime/APK semantics.

### Step 1 — LIVE_MOCK_ATTEMPT: actual child exit/Binder disconnect

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
p2m2ReportScope=LIVE_MOCK_ATTEMPT
historicalSnapshotOnly=false
poisoned=true
p2m1OwnerDurability=UNRESOLVED_POISON_DURABLE
p2m1OwnerBlocked=true
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=MATCHED
p2m3ExitEvidenceDurability=COMMITTED
eventCount=9
+0ms PARENT_ARM_PERSISTED
+11ms BIND_REQUESTED
+87ms CHILD_BOUND
+93ms MODEL_INITIALIZING
+94ms MODEL_READY
+94ms CONVERSATION_ENTERED
+95ms CONVERSATION_READY
+95ms SEND_ENTERED
+295ms BINDER_DIED
```

**Step 1 PASS**: observed a genuine Android ServiceConnection
`BINDER_DIED` callback following the intentionally killed mock child
after fake `SEND_ENTERED`. The parent explicitly denied further mock
admission and committed its unresolved poison state. There was no
`TERMINAL_DONE` or `RELEASE_CONFIRMED`. A bounded
`ApplicationExitInfo` candidate matched the child process/PID/UID/time
window and the **scalar metadata verdict** was persisted. This
does **not** attribute a GPU ANR or demonstrate native release.

### Step 2 — RECOVERED_OWNER_JOURNAL: reopen after child death

The user exited/reopened Stage 17 P1 without launching another mock task
and supplied this full report:

```text
P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU
nativeExecution=false
p2m2ReportScope=RECOVERED_OWNER_JOURNAL
poisoned=true
p2m1OwnerDurability=UNRESOLVED_POISON_DURABLE
p2m1OwnerRecovery=STICKY_MOCK_POISON
p2m1OwnerBlocked=true
p2m1ReleaseCommitPending=false
p2m1ChildExitCorrelation=NOT_CHECKED
eventCount=0
historicalSnapshotOnly=true
historicalEventCount=9
historicalEvent=+0ms PARENT_ARM_PERSISTED
historicalEvent=+11ms BIND_REQUESTED
historicalEvent=+87ms CHILD_BOUND
historicalEvent=+93ms MODEL_INITIALIZING
historicalEvent=+94ms MODEL_READY
historicalEvent=+94ms CONVERSATION_ENTERED
historicalEvent=+95ms CONVERSATION_READY
historicalEvent=+95ms SEND_ENTERED
historicalEvent=+295ms BINDER_DIED
p2m3RecoveredExitEvidence=MATCHED
```

**Step 2 PASS**: the recovered journal retained
`STICKY_MOCK_POISON`, `UNRESOLVED_POISON_DURABLE`, and blocked further
fake admission. Current event count is zero; all nine previous events
are clearly labeled historical. `p2m3RecoveredExitEvidence=MATCHED`
correctly recovers the prior metadata correlation, while
`p2m1ChildExitCorrelation=NOT_CHECKED` correctly means the reopened
screen has not performed a *new live* exit-history query. These fields
are not contradictory.

### Completion decision / remaining gates

P2-M3 QA5 **COMPLETE/PASS** within its approved QA-only scope:
- child death -> real Binder disconnection callback: PASS;
- durable unresolved poisoned ownership: PASS;
- page reentry -> sticky fail-closed block: PASS;
- history-correlation metadata persistence/recovery: PASS.

Still **NOT VERIFIED**: parent-main-process death and actual process-restart
crash consistency, repeated Binder reconnects under system stress,
true shared model ownership across processes, JNI thread-affinity or
teardown, HWUI/GPU-driver stability, recovery of the historical GPU ANR,
and production authorization. Do not auto-reset the sticky mock
state and do not clear QA app data. A future test-fixture reset must
be designed separately for mock-only code, with explicit authorization.
P2-N real GPU is **NOT AUTHORIZED**; P2-D GPU/AUTO hard-suspension stays
in place; PR #37 stays OPEN/DRAFT, and production main stays unchanged.
