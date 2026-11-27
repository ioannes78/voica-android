# Stage 17.3 — R17C-P2-E GPU/HWUI native thread and synchronization audit

**Status: SOURCE AUDIT COMPLETE; REAL-DEVICE GPU = ANR FAIL / SUSPENDED.**
**Scope:** static analysis only; no GPU inference, version upgrade, requalification,
new native executor, native release behavior change or data model migration.
**Source of truth:** \`ioannes78/voica-android\`, PR #37 OPEN/DRAFT, baseline
\`5ed11c98ee116c1b4860e44f9062a88f141fd655\` before this audit commit.
Keep production \`main\` unchanged and do not merge the draft PR.

## 1. Incident evidence and confidence

The user supplied R5 historical process exit metadata for R17C P2-C2-B:

| Field | Observed |
| --- | --- |
| process | previous process, pid 2704, attempt 0e9fa481-0b52-48c4-80d0-3e585cf8a188 |
| requested / resolved | explicit GPU / GPU |
| native JSON schema | off |
| conservative prompt estimate | 1,988 tokens, 4K context, 1,024 output cap |
| last persisted runtime phase | GENERATING, emissionCount=0 |
| historical exit | Android ApplicationExitInfo ANR(6), status=0 |
| last persisted PSS | 2,032,921 KB |
| exit PSS / RSS | 2,014,444 / 2,108,684 KB |
| display | interactive=true, keyguard=false, deviceLocked=false |
| main thread | Native, libc.so pthread_cond_wait, libhwui.so frames |
| RenderThread | Native, libsync.so sync_wait, libui/libgui frames |
| additional trace-wide markers | PTHREAD_COND_WAIT_PRESENT, GRAPHICS_FENCE_WAIT_CANDIDATE, ANDROID_HWUI_PRESENT, GPU_DRIVER_GRAPHICS_SYMBOL_PRESENT, ML_RUNTIME_SYMBOL_PRESENT |

**Confirmed:** Android categorized the prior process exit as ANR during a GPU
generation attempt. The last persisted phase precedes any observed output.
No evidence indicates this was an ordinary Kotlin exception or a direct
low-memory exit classification.

**Not established:** GPU driver defect, an exact wait-cycle/deadlock, a
specific LiteRT-LM worker as the blocker, synchronous JNI send blocking the
UI thread, OOM, native process root cause, or a reliable token threshold.
Trace-wide marker presence is NOT a call chain. Last breadcrumb timestamp
to exit timestamp is NOT request duration. The truncated R5 frames do not
identify the lock owner or provide a full GPU scheduler timeline.

## 2. Real repository execution chain

\`\`\`
Stage17P1LocalLlmActivity (Compose, rememberCoroutineScope / main)
  -> startRequest -> scope.launch
  -> AppLocalLlmRuntime.generate
       -> applyStage172R4StructuredSummarySafety
       -> managed model identity + lease, resource gate
       -> LocalLlmExecutionController.generate
       -> LocalLlmBackendResolver.resolve
       -> LocalLlmRuntimeCoordinator.generate (ownerMutex.tryLock)
       -> LiteRtLocalLlmRuntime.generate (withContext(Dispatchers.IO))
            -> Engine(EngineConfig)
            -> Engine.initialize()
            -> Engine.createConversation()
            -> Conversation.sendMessageAsync(callback)
            -> callback.onMessage/onDone/onError
            -> terminal.await()
            -> Conversation.cancelProcess() on coroutine cancellation
            -> native terminal acknowledgment (10s bounded wait)
            -> Conversation.close() then Engine.close()
            -> PSS snapshot / Engine release status
\`\`\`

The above is based on current source order, NOT a native-thread trace.
The callback may be invoked by the underlying LiteRT-LM native worker;
its exact thread identity is not recorded by current QA.

### Findings / evidence / disposition

| ID | Fact or gap | Source finding | Risk / interpretation | P2-E decision |
| --- | --- | --- | --- | --- |
| E1 | Foreground UI does not directly call Engine.initialize | Compose click starts a coroutine; native adapter wraps work in Dispatchers.IO | Main-thread direct JNI initiation not observed | RETAIN |
| E2 | IO dispatcher is a pool, not a guaranteed dedicated OS thread | \`LiteRtLocalLlmRuntime.generate\` wraps entire attempt in \`withContext(Dispatchers.IO)\`, and suspends at \`terminal.await()\` | initialize/send/cancel/close may run on different worker threads after suspension; **thread affinity is unproven** | INVESTIGATE IN ISOLATION; NO PRODUCTION CHANGE |
| E3 | Callback instrumentation is synchronous | onMessage calls \`onPhase(GENERATION_PROGRESS)\`; Coordinator emits observer; R5 observer reads Debug.getPss and commits SharedPreferences | May add work to native callback path; C2-B emissionCount=0, so an onMessage-induced stall is NOT supported by this incident | RECORD CANDIDATE; DO NOT CLAIM ROOT |
| E4 | Independent PSS sampler runs on Dispatchers.Default | Debug.getPss every 250 ms during generation | Adds diagnostic load; not proven to cause a GPU/graphics fence stall | RETAIN UNTIL ISOLATED QA DESIGN |
| E5 | Cancellation has explicit terminal acknowledgment | cancelProcess -> withContext(NonCancellable) / withTimeoutOrNull(10s) -> quarantine if unknown | Protects JNI ownership; native cancelProcess might run on a resumed IO thread; 10s is cancel wait, not a generation watchdog | RETAIN; DO NOT SYNTHESIZE GPU TIMEOUT |
| E6 | Conversation and Engine close order is explicit | close conversation, then engine; skip if native ownership quarantined; POISONED sticky in Coordinator | Guard against overlapping native sessions; resource failures prior to initialization need future separate audit | RETAIN |
| E7 | Single inference owner | Coordinator Mutex, AppContainer registry | Avoids simultaneous Engines on one coordinator; does not serialize GPU with Android HWUI/RenderThread or other processes | RETAIN |
| E8 | Native library visibility declared | local-llm AndroidManifest includes optional libOpenCL.so and libvndksupport.so; pinned dependency LiteRT-LM 0.18.0 | Manifest source declarations exist, but runtime driver availability or merged package state is not proven by static reading | NO CHANGE |
| E9 | Main and RenderThread native waits | R5 ANR main shows pthread_cond_wait and libhwui; RenderThread sync_wait | Graphics sync dependency is a serious candidate; exact owner/wait chain unavailable | NEED NATIVE TIMELINE; NO DRIVER FAULT CLAIM |
| E10 | QA-only GPU containment already active | P2-D explicitly blocks GPU and AUTO before native QA call; stale UI entrances also guarded | Prevents additional unapproved QA GPU tests; not a platform-wide disable | RETAIN AND CI FENCE |
| E11 | Product summary still CPU-contained | production structured-summary config uses AUTO+JSON schema; \`applyStage172R4StructuredSummarySafety\` coerces that combination to CPU | Product path does **not** establish all possible unconstrained AUTO calls are CPU; the preserved rule specifically targets structured summaries | RETAIN, DO NOT BROADEN CLAIM |

### Version and upstream guidance boundary

The app pins \`com.google.ai.edge.litertlm:litertlm-android:0.18.0\`.
Current official Android integration guidance describes a dedicated single-thread
background executor for initialize/send/cancel/close, whereas the repository's
\`Dispatchers.IO\` coroutine may resume on another worker thread. This is a
**potential compatibility investigation**, not proof that pinned 0.18.0 JNI
requires same-thread affinity or that it caused the observed GPU ANR.
Do not port arbitrary current upstream code or change dependency without
checking the specific pinned AAR native behavior.

Upstream references (external, not repository source):
- https://developer.android.com/agents/skills/device-ai/litert/litert-lm/skill
- https://developers.google.com/edge/litert-lm/android
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt

## 3. CI-only fail-closed static guard

\`ci/verify-stage17-p2e-anr-static-boundary.py\` is invoked in Android PR CI
and Stage 17.3 diagnostic QA build *before native toolchain setup*. It checks:
- compile-time P2-D suspended flag, CPU-only QA backend allowlist;
- central P2-D dispatch rejection precedes R5 arm and runtime.generate;
- stale P2/P2-C1/P2-C2 GPU clicks cannot set KEEP_SCREEN_ON;
- production AUTO+schema CPU isolation;
- pinned 0.18.0, asynchronous callback + cancel/wait/release order;
- Mutex and sticky poisoned state;
- no CPU fallback from GENERATING failures;
- optional native OpenCL/VNDK manifest declarations.

A source-string checker is a **regression tripwire**, not a formal proof.
CI PASS does not qualify GPU; negative results are treated as review blockers.
This change does not touch runtime execution, model files, Room, provider
configuration, production routing, or UI rendering.

## 4. Prioritized next work (no risky hardware test in P2-E)

**P2-E output:**
- Retain P2-D GPU QA suspension and user-provided ANR evidence.
- Record thread-affinity gap E2 as highest-priority engineering hypothesis.
- Preserve callback instrumentation gap E3 and PSS sampling gap E4 as secondary.
- Avoid simplifying cancellation; current quarantine is safer than closing a
  possibly running native Engine.
- Do not assume a new thread/executor or a coroutine timeout would fix a
  native GPU driver or HWUI fence stall.

**Proposed future P2-F (separate explicitly approved design):**
1. Analyze the pinned 0.18.0 Kotlin/JNI wrapper and the exact device GPU
   backend capabilities, including threading assumptions and native
   cancellation/close semantics.
2. Prototype a single-thread owned JNI executor in a fully isolated QA
   implementation **without enabling GPU in the existing app**. Evaluate
   callback/terminal event dispatch and thread shutdown behavior using fake
   native backends/unit tests before hardware.
3. Consider content-free thread IDs, phase timestamps and callback time
   measurements without synchronous per-token disk writes.
4. Only after explicit safety authorization and an isolated build, plan
   limited device validation. No automatic retry, unlock, lock-screen or
   long GPU stress. Do not move GPU to production on a short success.

## Gate

R17C P2-E = SOURCE-ANALYSIS COMPLETE only after the new source guard passes
PR CI. GPU real-device gate remains FAIL / SUSPENDED. PR #37 remains DRAFT.
No new QA APK or GPU real-device re-test is required for this static audit.
