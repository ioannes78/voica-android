# Stage 17.3 R17C P2-D — GPU ANR Safety Closure

Status: REAL-DEVICE GPU ANR CONFIRMED / QA CONTAINMENT CODE PREPARED / CI PENDING.
Repository: ioannes78/voica-android.
Branch: stage17-fully-offline-intelligence, PR #37 OPEN/DRAFT.
main is untouched. Do not merge, remove native containment, or call this GPU-safe.

## User-reported R5 evidence

The user ran P2-C2-B (GPU 4K / 1024, authentic medium transcript prefix).
The application closed during this explicit GPU attempt. After relaunch,
the user copied the original R5 Crash Breadcrumb before any new QA run.

Relevant scalar values supplied:
- source=PREVIOUS_PROCESS, pid=2704
- requestedBackend=GPU; resolvedBackend=GPU
- schemaEnabled=false; schemaSizeChars=0
- estimatedPromptTokens=1988
- runtimePhase=GENERATING; emissionCount=0
- pssKb=2032921
- interactive=true; keyguard=false; deviceLocked=false
- timestampEpochMs=1794232267086
- exitReason=ANR(6); exitStatus=0; exitImportance=100
- exitPssKb=2014444; exitRssKb=2108684
- exitTimestampEpochMs=1794232278149
- anrTraceAvailable=true
- anrMainState=Native; main frames include libc.so syscall /
  pthread_cond_wait and libhwui.so
- anrRenderThreadState=Native; render frames include libc.so __ppoll/poll,
  libsync.so sync_wait and libgui.so

**What is proven:** Android classified the previous process exit as ANR;
the most recent persisted runtime phase was GPU GENERATING, zero emissions,
and the device remained interactive and unlocked.

**What is NOT proven:** origin of the wait, LiteRT-LM or GPU driver defect,
specific sync edge/deadlock, memory leak, kernel timeout, or OOM. The 11,063ms
difference between last breadcrumb and process-exit timestamps is NOT the
whole request wall-clock duration. Exit classification is ANR, not LOW_MEMORY;
this alone does not exhaust all memory hypotheses.

This does not contradict the earlier P2-A/C1 short-pass or the full P2-B
generation pass: native GPU safety is not guaranteed across requests.
A single 1,988-token ANR is not a deterministic input-length threshold.

## Engineering containment decision

**Fail closed at QA request dispatch.** On this QA build, reject all requests
whose requested policy is GPU **or AUTO** before crash-breadcrumb arm, launch
of the coroutine, or invocation of the native Runtime. AUTO might otherwise
resolve to GPU for an unconstrained no-schema QA request. Only explicit CPU
requests are eligible for existing runtime checks.

Also disable the screen's P2-A/P2-B/P2-C1 GPU/P2-C2 GPU buttons, legacy generic
GPU, generic AUTO, GPU long-generation, cancellation stress and simulated AUTO.
This UI policy alone is not trusted: the central pre-native gate is the
safety boundary. CPU-side QA and diagnostic exports are retained.
Additionally, every P2/P2-C1/P2-C2 GPU entry checks quarantine before
setting FLAG_KEEP_SCREEN_ON, so even a stale UI tap does not change screen
policy when inference is denied. The independent central request guard remains
in place as defense in depth.

This stage does **not** patch the native engine, adjust performance parameters,
upgrade LiteRT-LM 0.18.0, republish the model, or modify the production summary
route. Product-facing AUTO+JSON-schema CPU containment remains unchanged.
Requalification requires new code review and explicit real-device safety gates.

## ANR diagnostics without raw trace retention

The R5 persisted breadcrumb format stays backward-compatible. Historical
Android ApplicationExitInfo is still matched by PID and timestamp, read
on relaunch, and displayed in scalar form. The already available main and
RenderThread sanitized top-frame summaries remain.

P2-D extends the derived ANR summary with a short allowlisted set of
*content-free* trace-wide markers:
- PTHREAD_COND_WAIT_PRESENT
- GRAPHICS_FENCE_WAIT_CANDIDATE
- ANDROID_HWUI_PRESENT
- GPU_DRIVER_GRAPHICS_SYMBOL_PRESENT
- ML_RUNTIME_SYMBOL_PRESENT

These marker names are simply presence indicators; they do not establish a
causal wait chain, are not specific to one thread, and can be absent if the
Android trace was truncated. No raw ANR trace, full stack, transcript,
prompt, generated text, recording title, or private user content is persisted
or uploaded by this feature.

The QR/QA R5 panel shows a conservative exit-evidence classification:
CONFIRMED_ANDROID_ANR_DURING_GPU_GENERATING, with emissions count and
"causal_driver_root_unknown". If historical exit information is unavailable,
do not guess the cause. The existing "copy breadcrumb" and "clear" actions
remain; users should copy the old breadcrumb before starting any CPU QA test.

## User safety instructions

1. DO NOT repeat P2-C2-B GPU, P2-B 8K, P2-C1 GPU or GPU long-stress.
2. Cover-install the P2-D QA APK without wiping app data, if needed to use
   the safeguarded QA interface.
3. Open Stage 17 P1 > R5 Crash Breadcrumb. Copy the breadcrumb before other
   inference. The prior-process metadata may eventually be unavailable from
   Android system history; user-supplied evidence above is preserved here.
4. Use only explicit CPU tests, if an additional diagnostic is needed.
5. If even CPU execution fails, stop and preserve the separate evidence.
6. Do not treat the patch as a fix for GPU instability. No GPU real-device
   re-test is requested in this phase.

## Automated gate and exit status

Unit tests assert:
- hard-disabled QA GPU and AUTO, CPU still permitted;
- previous-process GPU GENERATING ANR classification with no causal assertion;
- content-free allowlist signal extraction / no raw trace exposure;
- no production backend/model/Room modifications.

Android PR CI and Stage 17.3 diagnostic releaseQa build must pass.
P2-D has no GPU hardware "PASS" target: GPU remains ANR FAIL / SUSPENDED.
PR #37 remains OPEN/DRAFT, main unchanged. Follow-up work is **offline**
native stack / Android graphics-driver synchronization analysis and, only if
justified, a later opt-in isolated runtime version A/B with explicit approval.
