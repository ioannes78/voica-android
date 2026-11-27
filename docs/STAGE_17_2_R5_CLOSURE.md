# Stage 17.2 R5 — AUTO/GPU Summary Regression Fix Closure

Status: **CLOSED / PRODUCT CONTAINMENT RETAINED / STRUCTURED GPU REQUALIFICATION DEFERRED**

PR: **#37 — Stage 17 — Fully Offline Intelligence**

Branch: `stage17-fully-offline-intelligence`

## 1. Closure decision

Stage 17.2 R5 is closed without restoring production structured-summary GPU on the current test device.

The production safety policy remains:

- `AUTO + responseJsonSchema -> CPU`
- explicit GPU diagnostics remain available only in the QA diagnostic path
- unconstrained GPU capability is retained
- no cloud fallback
- no model/runtime/schema/prompt replacement was introduced as a workaround

This is **not** a global GPU deprecation.

GPU remains part of the Local LLM runtime. Only the current Stage 17.2 structured-summary path is contained to CPU until structured GPU is independently re-qualified on another device/runtime combination.

## 2. Frozen model/runtime contract

The R5 investigation did not change the Stage 17.2 frozen contract:

- LiteRT-LM Android: `0.18.0`
- model: `litert-community/gemma-4-E2B-it-litert-lm`
- revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- artifact: `gemma-4-E2B-it.litertlm`
- artifact size: `2,583,085,056`
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- prompt version: `170204`
- maxNumTokens: `4000`
- maxOutputTokens: `1024`
- topK: `64`
- topP: `0.95`
- temperature: `1.0`
- speculativeDecoding: `false`
- request-specific bounded `responseJsonSchema`

Golden baseline retained for historical comparison:

`3babe307ff204c825b81b46a66443ea390303adf`

## 3. R5 forensics delivered

R5 added QA-only, content-free persistent crash breadcrumbs capable of surviving process death.

Recorded metadata includes:

- PID
- process/session ID
- attempt/session ID
- requested backend
- resolved backend
- schema enabled state
- schema size
- estimated prompt tokens
- runtime phase
- emission count
- PSS
- interactive/keyguard/deviceLocked state
- timestamp
- Android `ApplicationExitInfo` classification
- privacy-trimmed ANR thread classification for main / RenderThread

The diagnostic path does **not** persist or expose:

- prompt text
- transcript text
- generated model text
- raw ANR trace contents

## 4. Foreground isolation evidence

Test source:

- recording: `AB010-02 [cut_118sec].mp3`
- duration: approximately `1:57`
- formal prompt estimate: approximately `2008` tokens
- request-specific schema size: approximately `2112` characters

### B1 — formal prompt + GPU + no schema

Observed both success and later intermittent process termination.

Successful example:

- GPU resolved
- schema disabled
- formal prompt approximately 2008 tokens
- generation completed
- `ENGINE_CLOSED`
- approximately 612 emissions

Later a fresh-process B1 also terminated before first emission.

Therefore schema/constrained decoding is **not required** for the underlying device-level failure mode.

### B2 — small prompt + GPU + formal schema

PASS.

Observed:

- GPU resolved
- schema enabled
- small prompt approximately 270 tokens
- formal Stage 17.2 schema enabled
- generation completed
- `ENGINE_CLOSED`

Therefore the formal bounded JSON schema alone is **not sufficient** to reproduce the failure.

### B3 — formal prompt + GPU + formal schema

Intermittent FAIL.

Repeated failures were captured at:

- `runtimePhase=GENERATING`
- `emissionCount=0`
- foreground
- interactive screen
- no keyguard
- device unlocked

A fresh-process B3 could sometimes succeed, while a later B3 in the same process could fail. A fresh-process B1 was also later observed to fail, so the final boundary is broader than constrained decoding alone.

### Post-closure repeat evidence

After the initial closure decision, the same B3 configuration was re-run on the current device without changing the model/runtime/schema/prompt contract.

One repeat completed successfully:

- `source=CURRENT_PROCESS`
- GPU resolved
- schema enabled
- schema size `2112` chars
- prompt estimate `2008` tokens
- `runtimePhase=ENGINE_CLOSED`
- `emissionCount=524`
- no process restart / no ANR

A subsequent repeat on the same device failed again:

- `source=PREVIOUS_PROCESS`
- GPU resolved
- schema enabled
- schema size `2112` chars
- prompt estimate `2008` tokens
- `runtimePhase=GENERATING`
- `emissionCount=0`
- foreground / interactive / unlocked
- `exitReason=ANR(6)`

This PASS-then-FAIL sequence confirms that the current-device failure is **intermittent under an otherwise equivalent structured-GPU workload**. A successful single run must not be treated as production qualification.

## 5. Exit classification

Android `ApplicationExitInfo` classified the captured process termination as:

- `exitReason=ANR(6)`
- foreground process importance
- not `LOW_MEMORY`
- not classified as native crash

A subsequent QA-only ANR trace classifier confirmed two observed graphics-side ANR shapes.

### Captured ANR shape A

Main thread:

- state `Native`
- `pthread_cond_wait`
- `libhwui.so`

RenderThread:

- state `Native`
- `poll / __ppoll`
- `libsync.so:sync_wait`
- `libui.so`
- `libgui.so`
- `IGraphicBufferProducer::QueueBuffer...`

### Captured ANR shape B

Main thread:

- state `Native`
- `__epoll_pwait`
- `libutils.so`
- `libandroid_runtime.so`
- `art_quick_generic_jni_trampoline`

RenderThread:

- state `Native`
- `__epoll_pwait`
- `libutils.so`
- `libhwui.so`

The evidence is consistent with an Android graphics/GPU synchronization or rendering stall that propagates into HWUI / RenderThread and ultimately causes a foreground ANR.

R5 does **not** claim a specific vendor GPU driver defect because the privacy-trimmed evidence does not identify a definitive vendor-driver fault site.

## 6. Root-cause boundary accepted for product release

The evidence is sufficient to reject the following earlier hypotheses as complete explanations:

- Flow vs callback uses a different native generation API
- bounded schema is inherently unsupported on GPU
- Prompt 170204 inherently crashes GPU
- maxOutputTokens=1024 inherently crashes GPU
- response format has never worked on GPU
- normal lifecycle/background/lock handling is generally broken
- the observed restart is a low-memory kill
- the observed restart is a Kotlin main-thread business-code deadlock

The accepted current boundary is:

> On the current real device, sufficiently heavy LiteRT-LM GPU inference can intermittently stall the Android graphics/render synchronization path during generation startup/prefill, before the first model emission. Formal structured summary increases the relevant workload surface but is not the only configuration capable of reproducing the ANR.

The same formal structured-GPU request can complete normally in one run and ANR in a later equivalent run. Therefore reliability, not basic functional capability, is the blocking qualification criterion.

## 7. Product policy after R5

Production Stage 17.2 remains safe by retaining the existing containment:

`AUTO + responseJsonSchema -> CPU`

The following remain allowed:

- CPU structured offline summary
- ordinary/unconstrained GPU capability where already qualified
- explicit QA-only GPU diagnostics

The following are **not production-qualified**:

- structured Stage 17.2 summary on explicit GPU
- automatic restoration of GPU after an unknown/ANR native session

No same-session automatic CPU fallback is permitted after an ANR/unknown GPU ownership event.

A single successful structured-GPU run does not override this containment; production qualification requires repeat stability.

## 8. Cross-device GPU requalification policy

Structured GPU is deferred, not removed.

When another Android device is available, requalification may resume without changing the frozen model/runtime contract first.

Minimum requalification sequence:

1. fresh-process formal prompt + GPU + no schema
2. fresh-process small prompt + GPU + formal schema
3. fresh-process formal prompt + GPU + formal schema
4. repeated formal structured GPU inference in the same process
5. each successful inference must reach confirmed `ENGINE_CLOSED`
6. second/subsequent GPU inference must remain stable
7. no ANR / process restart / `RELEASE_UNCONFIRMED` / `POISONED`
8. only after foreground stability, repeat lifecycle qualification

Production GPU-first structured summary may be restored only after the new device passes the complete qualification gate.

If another device passes while the current device continues to fail, the next decision must be evidence-driven: device/GPU capability gating or continued global CPU containment. Do not globally enable structured GPU from a single-device success.

## 9. R5 final state

R5 outcome:

**PASS WITH PRODUCT CONTAINMENT / GPU STRUCTURED REQUALIFICATION DEFERRED**

The project may continue with Stage 17.2 offline summary using CPU for the structured production path. GPU work is explicitly deferred until cross-device real-device evidence is available.

PR #37 remains:

**DRAFT / OPEN / DO NOT MERGE**
