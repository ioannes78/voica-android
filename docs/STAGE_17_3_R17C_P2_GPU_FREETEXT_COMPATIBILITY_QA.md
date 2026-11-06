# Stage 17.3 R17C-P2 — GPU Free-Text Compatibility (QA-only)

Status: IMPLEMENTED IN PR #37 DRAFT / CI and real-device QA required.
Project: WENZHI / ioannes78/voica-android. Target production branch main unchanged.

## Evidence and scope

P1-R2 verified a full 8K/2048 CPU free-text summary using exact effective transcript
EHBZKFZH6GIZG.m4a (507611 ms, 2705 chars, 84 units), digest
931223800714b8d92ae7bfd3f821cb374058c1e8d6c29d225b6f72a3cedd4a81
and prompt SHA
e80da37fe54b0b6bd73450113814a058193b19038a022a070ddb4b7fa5822d2f.
CPU 8K/2048 baseline: generationMs=339182, ttfeMs=63463,
peakPssKb=2549114, pssClosedKb=1366409. Content still has the serious
"三到八成" -> "3%到8%" numeric scale error. GPU speed is a separate gate
from content fidelity.

Stage 17.2 structured GPU had intermittent ANRs, unresponsive native session, and
in a manual-lock repro a device reboot. The native GPU/driver root cause is not yet
proven. Official Stage 17.2 production containment remains:
AUTO + responseJsonSchema -> CPU. No release qualification is granted by this QA.

This P2-A/B experiment stays on **LiteRT-LM 0.18.0**, fixed Gemma 4 E2B revision
7fa1d78473894f7e736a21d920c3aa80f950c0db:r1.
It does NOT try 0.18.1, enable MTP, enable native JSON response format,
upgrade model weights, open production GPU, or change Room / summaries.

## QA-only manual two-step matrix

In Stage 17 P1 QA screen, use card "R17C P2 · GPU 自由文本兼容性":

- P2-A: explicit GPU 4K / max output 256, short synthetic/content-free request.
  Pass requires response marker R17C_GPU_OK, resolved GPU, zero fallback,
  nonnull after-close PSS, and runtime returned IDLE.
- P2-B: explicit GPU 8K / max output 2048, source-selected full transcript,
  **exact same prompt and sampling** as P1-R2 CPU 8K/2048. Only GPU backend
  differs. No MAP/REDUCE/FINAL, no truncation, no native JSON Schema.
  Input estimate + 2048 output + 192 template + 256 safety <= 8000.

No automated chain. User must manually check device and initiate P2-B after
P2-A passes. It cannot start without P2-A success within the same QA screen
session, risk acknowledgement, foreground+resumed+interactive+unlocked lifecycle,
runtime IDLE, no previous P2 failure/cancel, and available memory >=3 GiB
without Android lowMemory. Memory floor is QA-only and cannot guarantee safety.

A P2 attempt is run at most once per test case per QA screen. On exception or
cancellation, the QA screen blocks further GPU P2 tests within that session.
If Android process crashes/reboots before exception handling, rely on R5 Crash
Breadcrumb in the newly launched process; stop GPU attempts, don't re-arm.

## Real-device instructions / stop conditions

1. Cover-install QA APK without clearing data; ensure model READY.
2. Foreground, screen on, device cool, stable battery/charging and no heavy
   concurrent processes. Read the on-screen warning; check risk acknowledgement.
3. Tap P2-A only. Never lock screen, press Home, switch app, or manually trigger
   cancellation as part of this compatibility trial.
4. If P2-A succeeds, first inspect UI state and GPU diagnostic close PSS.
   Save P2 TXT now. If process restart, ANR, unusually high memory, thermals or
   lag occurs, STOP and do NOT run P2-B.
5. If stable, load/select EHBZKFZH6GIZG.m4a under the existing R17C P0/P1
   card, confirm digest prefix 93122380. Tap P2-B manually once.
   A busy/failed resource gate must reject without any silent CPU substitution.
6. Save WENZHI-Stage17.3-R17C-P2-GPU-Compatibility.txt using the document picker.
   It contains raw GPU output/diagnostics; when P2-B was run, it also contains
   the full source and Evidence sidecar. This report remains local unless the
   user explicitly shares it. Do not assume evidence membership proves support.
7. Record: native version/model and revision, requested/resolved backend,
   fallback attempted, prompt SHA, input estimate, context/output cap,
   initializeMs, TTFE, generationMs, peak PSS/after-close PSS, error/restart,
   output content and known numeric regression. For an unexpected process exit,
   recover R5 Crash Breadcrumb instead of attempting another GPU session.

### Stop immediately

Any UI hang/ANR, process kill/restart, severe heat, GPU release unconfirmed,
unusual driver behavior, or memory gate failure: stop further GPU QA. Never
GPU->CPU switch a running or poisoned native session. Do not use lock-screen
GPU stress QA on this device.

## Automated CI

Tests assert short GPU-only/no-schema config; full 8K GPU request is bitwise
prompt-identical to CPU request with only backend changed; output reserve gate;
foreground/keyguard/idle/ack/failure preflight; 4K marker/backend/fallback/close
requirements; long memory floor; raw report, source digest and numeric warning.
Android PR CI and Quality Diagnostic APK assembly must succeed before device QA.

## Qualification

A successful P2-A does not itself qualify P2-B. A successful single P2-B run
proves only feasibility for this device and moment, not cross-device or
lockscreen reliability, native GPU/JSON stability or production eligibility.

Requalification of GPU product behavior requires repeated stable real-device
runs, stress/thermal and lifecycle matrices with safeguards, confirmed release,
and an independently reviewed update. P2-C (separate LiteRT-LM 0.18.1 isolated
build) is conditional on P2-A/B findings, and is not part of this commit.

PR #37 remains OPEN/DRAFT; DO NOT MERGE.
