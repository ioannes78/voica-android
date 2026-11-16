# Stage 17.3 R17C P2-M3 — QA mock child-process death and fail-closed recovery

Status: IMPLEMENTED ON DRAFT BRANCH / EXACT-HEAD CI AND APK PENDING /
REAL-DEVICE QA5 NOT STARTED / NO JNI OR GPU.

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
