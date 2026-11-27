# Stage 17 P1 — Offline LLM Runtime Foundation Closure

Status: **CLOSED / AUTOMATED GATES PASS / REAL-DEVICE QA PASS WITH EXPLICIT COVERAGE LIMITS**

Branch: `stage17-fully-offline-intelligence`

Production baseline: `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`

Validated implementation HEAD before closure doc: `2af4db369286fbf16f28142f871271f412a31003`

PR: `#37` — remains **DRAFT / OPEN / DO NOT MERGE WITHOUT EXPLICIT USER AUTHORIZATION**

## Scope

P1 establishes the production Local LLM runtime foundation for WENZHI Stage 17. It does not implement Offline Summary, long-transcript hierarchy, Q&A/RAG, embeddings, Audio LLM, or any cloud fallback.

The P1 runtime foundation includes:

1. formal runtime/backend/model contracts;
2. production LiteRT-LM runtime extraction from the P0 probe;
3. app-level runtime lifecycle and a single heavy inference owner;
4. AUTO/GPU/CPU backend resolution;
5. GPU capability/resource/thermal preflight gating;
6. safe pre-generation GPU -> CPU fallback only where classified as recoverable;
7. model lifecycle integration through ModelManager;
8. cancellation and foreground/background recovery;
9. production-like R8/JNI verification for LiteRT-LM;
10. QA-only runtime diagnostics surface.

No Room migration was introduced for P1.

## Frozen runtime/model contract

- LiteRT-LM Android: `0.18.0`
- Model repository: `litert-community/gemma-4-E2B-it-litert-lm`
- Validated revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- File: `gemma-4-E2B-it.litertlm`
- Size: `2,583,085,056` bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- Runtime preset: P0 validated sampler/context/speculative settings retained as a named regression preset.
- Production backend policy: `AUTO`.
- AUTO policy: GPU preferred when capability/resource/thermal gates permit; otherwise select CPU before generation.
- Explicit GPU/CPU diagnostic requests remain explicit and are not silently rewritten.
- No cloud fallback is permitted.

The newer MTP-capable Gemma artifact remains outside the validated P1 production contract.

## R8 / JNI release contract

The P0 fix remains a hard release boundary:

```proguard
-keep,allowoptimization class com.google.ai.edge.litertlm.** {
    *;
}
```

P1 added production-like APK verification through `ci/verify-litertlm-r8-boundary.sh` and `ci/verify-r8-outputs.sh`.

The QA APK Gate verified the retained original LiteRT-LM class descriptors, including Engine, Conversation, ConversationConfig, Content, Contents, ExperimentalFlags and LiteRtLmJni.

Observed QA Gate evidence:

- QA APK Gate run: `#2597` / workflow run `40154091115`
- build variant: `releaseQa`
- minification/resource shrink: enabled
- LiteRT-LM retained package descriptors: `107`
- R8/JNI boundary verification: **PASS**
- QA artifact: `WENZHI-1.1.0-stage16a2-export-qa-apk`

## Automated implementation gates

P1 implementation was built incrementally and the relevant Fast PR Gate runs passed, including:

- P1 contracts/tests;
- production LiteRT-LM runtime extraction;
- coordinator/single-owner behavior;
- backend/resource policy and model lifecycle integration;
- release APK R8/JNI verifier.

The final implementation HEAD before closure documentation is `2af4db369286fbf16f28142f871271f412a31003`.

## Real-device QA evidence

### QA1 — clean install / model state

**PASS**

Gemma candidate is visible after clean install and initially reports not installed without corrupt/incompatible state.

### QA2 — download / SHA verification / activation

**PASS**

The full model download, verification, install and activation path completed successfully and reached READY.

### QA3 — restart persistence

**PASS**

The installed model remained READY after process/task restart without re-download.

### QA4 — AUTO / GPU preferred

**PASS**

Observed:

- requested: `AUTO`
- resolved: `GPU`
- reason: `GPU_AVAILABLE`
- fallback: `false`
- peak PSS: `2,221,539 KB`
- successful local Chinese response

Cold initialization in this run was ~76 s; subsequent GPU runs were materially faster.

### QA5 — explicit CPU

**PASS**

Observed:

- requested/resolved: `CPU` / `CPU`
- reason: `EXPLICIT_CPU`
- fallback: `false`
- peak PSS: `1,370,715 KB`
- successful local response

### QA6 — explicit GPU

**PASS**

Observed:

- requested/resolved: `GPU` / `GPU`
- reason: `EXPLICIT_GPU`
- fallback: `false`
- peak PSS: `2,111,587 KB`
- successful local response

### QA7 — AUTO preflight GPU block -> CPU

**PASS**

Observed:

- requested: `AUTO`
- resolved: `CPU`
- reason: `GPU_PREFLIGHT_BLOCKED_MEMORY_HEADROOM_LOW`
- fallback: `true`
- peak PSS: `1,374,812 KB`
- successful CPU response

This validates pre-generation resource gating instead of GPU generation failure followed by unsafe retry.

### QA8 — single heavy inference owner

**PASS**

While GPU inference was running, CPU could not be started. No second Engine/generation task was launched.

### QA9 — cancellation

**PASS**

Cancellation returned the runtime/UI to an operable idle state and subsequent backend controls were usable.

### QA10 — foreground/background transition

**PASS**

Inference survived background/foreground transition without duplicate task execution, crash, or unusable state.

### QA11 — in-use model delete protection

**PASS**

The managed model could not be destructively removed while held by an active runtime task.

### QA12 — delete / reinstall

**PASS**

Model deletion returned the model to not-installed state and a full reinstall returned it to READY.

### QA13 — missing model recovery

**PASS**

After model deletion, the QA runtime surface reported the model as missing/not activated and did not continue using a stale model or native handle.

### QA14 — corrupt / bad-SHA negative path

**MANUAL N/A FOR THIS QA BUILD**

The current QA surface has no safe fault-injection control for intentionally corrupting the 2.58 GB model or supplying a bad checksum. This negative path was therefore **not** claimed as a real-device PASS.

The positive download -> checksum verification -> activation path is covered by QA2. A dedicated QA fault-injection mechanism or automated ModelManager negative test should be used if explicit end-to-end bad-SHA device proof is required later.

### QA15 — airplane-mode local inference

**PASS**

With network disabled, AUTO inference completed locally:

- requested: `AUTO`
- resolved: `GPU`
- reason: `GPU_AVAILABLE`
- fallback: `false`
- peak PSS: `2,253,896 KB`
- successful Chinese response

No provider/API-key/network/cloud-fallback error occurred.

### QA16 — repeated AUTO load/generate/release

**PASS**

Five consecutive AUTO runs completed without crash, stuck state, Engine initialization failure, generation failure, or progressive inability to start the next run.

Representative final run:

- resolved: `GPU`
- peak PSS: `2,141,703 KB`
- successful response

### QA17 — memory/resource gate observation

**PASS — NORMAL RESOURCE PATH**

Observed:

- requested: `AUTO`
- resolved: `GPU`
- reason: `GPU_AVAILABLE`
- fallback: `false`
- peak PSS: `2,086,696 KB`

No OOM/native crash or unsafe backend retry was observed.

### QA18 — thermal gate observation

**PASS — NORMAL THERMAL PATH ONLY**

Observed:

- requested: `AUTO`
- resolved: `GPU`
- reason: `GPU_AVAILABLE`
- fallback: `false`
- peak PSS: `2,160,052 KB`

The device did not enter SEVERE/CRITICAL thermal state during this test. Therefore SEVERE/CRITICAL real-device branches are explicitly **not claimed as covered**.

## Coverage limits retained after closure

P1 closes with the following explicit coverage limits, which are not P1 blockers:

1. Bad-SHA/corrupt-model end-to-end real-device fault injection was not available in the current QA build.
2. SEVERE/CRITICAL thermal branches were not naturally reached on the test device.
3. GPU post-close PSS remains visibly higher than CPU immediately after close; prior and current repeated-run evidence is consistent with delayed runtime/driver reclamation rather than monotonic per-run growth.
4. The validated model contract is the pre-MTP Gemma artifact only.
5. P1 proves runtime foundation behavior, not production summary quality or long-recording semantic quality.

## Gate decision

**Stage 17 P1 = CLOSED / PASS WITH EXPLICIT COVERAGE LIMITS**

The following are accepted as the frozen P1 foundation for subsequent Stage 17 work:

- app-level LiteRT-LM runtime lifecycle;
- managed Gemma candidate lifecycle;
- AUTO GPU-first policy with preflight resource/thermal gating;
- safe CPU selection/fallback before generation where permitted;
- explicit CPU/GPU diagnostic paths;
- single heavy inference owner;
- cancellation and lifecycle recovery;
- model in-use protection and missing-model recovery;
- offline inference with network disabled;
- production-like R8/JNI boundary verification;
- no cloud fallback.

## Stage transition

P1 is closed and frozen as the runtime foundation.

The next planned product step is **Stage 17.2 — Offline Summary V1**. It must consume the P1 runtime contract rather than re-implement Engine ownership, backend arbitration, model lifecycle, or cloud fallback.

PR #37 remains Draft/Open. P1 closure does **not** authorize merge.