# Stage 17.2 — Offline Summary V1 Closure

Status: **CLOSED / PASS WITH PRODUCT CONTAINMENT / STRUCTURED GPU REQUALIFICATION DEFERRED**

Repository: `ioannes78/voica-android`

Production branch: `main`

Development branch: `stage17-fully-offline-intelligence`

Pull request: `#37 — Stage 17 — Fully Offline Intelligence`

PR policy: **DRAFT / OPEN / DO NOT MERGE WITHOUT SEPARATE EXPLICIT USER AUTHORIZATION**

Product / QA evidence freeze HEAD before these docs-only closure artifacts:

`fa075301adf22ae22803715673cafe9cb26587aa`

Stage 17.2 implementation baseline:

`462dd8868996992653c0f231fd0be5e2d6c8db0a`

Canonical implementation history is the ordered Git range:

`462dd8868996992653c0f231fd0be5e2d6c8db0a..fa075301adf22ae22803715673cafe9cb26587aa`

This range contains the Stage 17.2 implementation, QA revisions, containment work, R5 diagnostics and final evidence. The closure/freeze/handoff commit that adds this document is docs-only and does not change the product freeze above.

---

## 1. Final decision

Stage 17.2 — Offline Summary V1 is closed as:

**PASS WITH PRODUCT CONTAINMENT / STRUCTURED GPU REQUALIFICATION DEFERRED**

The production-facing direct offline summary path is accepted with the current structured-summary backend containment:

`AUTO + responseJsonSchema -> CPU`

This is not a global GPU deprecation.

- unconstrained GPU capability remains part of the Stage 17 Local LLM runtime;
- explicit GPU remains available only for QA diagnostics where applicable;
- structured Stage 17.2 GPU is not production-qualified on the current real device;
- structured-GPU requalification is deferred until another device/runtime combination is available;
- no cloud fallback is permitted.

No further structured-GPU stress testing is required on the current device for Stage 17.2 closure.

---

## 2. Frozen runtime and model contract

Stage 17.2 closes with the following validated contract:

- LiteRT-LM Android: `0.18.0`
- model repository: `litert-community/gemma-4-E2B-it-litert-lm`
- revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- artifact: `gemma-4-E2B-it.litertlm`
- artifact size: `2,583,085,056` bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- prompt version: `170204`
- `maxNumTokens=4000`
- `maxOutputTokens=1024`
- `topK=64`
- `topP=0.95`
- `temperature=1.0`
- `speculativeDecoding=false`
- request-specific bounded `responseJsonSchema`
- LiteRT-LM R8/JNI keep boundary: `-keep,allowoptimization class com.google.ai.edge.litertlm.** { *; }`

The historical structured-GPU Golden baseline retained for comparison is:

`3babe307ff204c825b81b46a66443ea390303adf`

No model, runtime, prompt, schema or decoding-budget replacement was introduced to hide the R5 device failure.

---

## 3. Final direct-summary budget

Stage 17.2 remains a direct-summary-only stage.

The current deterministic production budget is:

- context window budget: `4000`
- output reserve: `1024`
- safety reserve: `192`
- estimator margin: `256`
- maximum estimated prompt budget: `2528`

Derived as:

`4000 - 1024 - 192 - 256 = 2528`

The estimator is deterministic and conservative. Over-budget input is rejected as `INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY` before native model generation. Stage 17.2 does not silently tail-truncate, summarize only a prefix or start map/reduce.

The formal R5 real-device fixture produced an estimated prompt of approximately `2008` tokens and remained within the accepted direct-summary budget.

Long-transcript hierarchy is explicitly deferred to Stage 17.3.

---

## 4. Accepted product architecture

The accepted Stage 17.2 path is:

```text
Current Effective Transcription
  -> StructuredTranscriptInput
  -> OfflineSummaryPrompt
  -> direct input budget gate
  -> OfflineSummaryCoordinator
  -> AppLocalLlmRuntime.generate(AUTO)
  -> production structured-summary CPU containment
  -> strict SummaryResultCodec validation
  -> optional bounded one-time repair
  -> evidence mapping against frozen source refs
  -> AiSummary persistence/version lifecycle
  -> existing summary UI/search/export/evidence playback
```

Hard boundaries:

- Stage 17.2 reuses the Stage 17 P1 application-scoped runtime/coordinator/model lifecycle;
- no parallel LiteRT-LM Engine owner is introduced;
- no cloud-provider fallback is introduced;
- inference does not require an API key or transcript/audio upload;
- Room remains schema version 16;
- existing canonical playback remains the evidence playback owner;
- every regeneration creates a distinct summary version/candidate rather than overwriting an earlier completed result;
- only strictly validated structured output may become `COMPLETED`;
- unknown evidence refs are rejected rather than persisted as successful evidence;
- process-death recovery reconciles non-terminal work to interrupted state rather than silently auto-resuming.

---

## 5. Automated verification

### Current product/QA evidence freeze HEAD

`fa075301adf22ae22803715673cafe9cb26587aa`

Android PR CI:

- run number: `#2771`
- run ID: `40522440173`
- result: **SUCCESS**
- Fast PR Gate: **SUCCESS**
- QA APK Gate: skipped as expected for the final docs/evidence-only head
- Android Production Gate: skipped as expected

The Fast PR Gate includes repository contracts, unit tests/debug compile and the existing native/runtime boundary verification.

### Production-like QA artifact used for final R5 diagnostics

- source HEAD: `08da6197b8688fa1fcdff97793f038a83acf86c2`
- Android PR CI run number: `#2768`
- run ID: `40502230964`
- QA APK Gate: **SUCCESS**
- artifact ID: `12732879071`
- artifact name: `WENZHI-1.1.0-stage16a2-export-qa-apk`
- artifact digest: `sha256:26296d4f7e273a1111117cf54f541a54c9735c11d2c4dcc92df0883cf23d11a1`

The artifact name is a retained historical workflow naming convention; closure identity is the exact run ID, source HEAD, artifact ID and digest above.

The production-like gate completed releaseQa assembly, R8/minification/security verification and artifact upload successfully.

---

## 6. Real-device QA evidence accepted for closure

Stage 17.2 accumulated product-path QA plus targeted QA revisions rather than one final monolithic rerun.

Accepted recorded evidence includes:

1. Before QA Revision-1, the Stage 17.2 summary product path had passed real-device QA except for task visibility after Home/background.
2. QA Revision-1 moved task visibility/background ownership to the application-scoped execution path and closed that product gap without introducing a parallel LLM runtime.
3. QA Revision-2 targeted foreground, Home/background and manual lock-screen-safe local LLM execution while preserving the P1 ownership contract.
4. QA Revision-3 established the summary current/candidate/version lifecycle contract in the product implementation and automated coverage. No separate real-device claim is added here beyond the evidence explicitly recorded by the revision history.
5. R5 repeatedly exercised the formal approximately 1:57 recording fixture (`AB010-02 [cut_118sec].mp3`) with an approximately 2008-token formal prompt and request-specific structured schema.
6. Product structured-summary CPU containment remained the accepted safe production path.
7. Explicit structured GPU could both succeed and later ANR under an equivalent formal workload, proving an intermittent reliability failure rather than a blanket functional impossibility.

The latest repeated structured-GPU evidence included:

Successful run:

- GPU resolved
- schema enabled
- schema size approximately `2112` chars
- prompt estimate approximately `2008` tokens
- `ENGINE_CLOSED`
- `emissionCount=524`

Subsequent failed run:

- GPU resolved
- same formal schema/prompt class
- `GENERATING`
- `emissionCount=0`
- foreground / interactive / unlocked
- `exitReason=ANR(6)`

ANR trace classification placed the observed failure in Android native graphics/HWUI/RenderThread synchronization paths. R5 does not claim a proven vendor-specific GPU driver defect.

The authoritative detailed forensic record is:

`docs/STAGE_17_2_R5_CLOSURE.md`

---

## 7. Production backend behavior at closure

Production Stage 17.2 behavior is frozen as:

- request backend from the product path: `AUTO`;
- request with `responseJsonSchema`: resolve to CPU containment;
- no same-session automatic CPU fallback after an ANR, unknown GPU ownership, `RELEASE_UNCONFIRMED` or poisoned GPU session;
- unconstrained GPU remains available where previously qualified;
- explicit structured GPU is QA-only and not production-qualified;
- no cloud fallback.

A single successful structured-GPU run is not sufficient to restore GPU production use. Requalification requires repeated cross-device stability and confirmed release.

---

## 8. Explicit NOT COVERED / deferred items

The following are not claimed as Stage 17.2 production-qualified real-device paths:

- structured Stage 17.2 GPU on the current device;
- cross-device structured-GPU compatibility;
- restoration of structured `AUTO` to GPU-first behavior;
- SEVERE/CRITICAL thermal branches that could not be safely forced;
- unsafe native crash injection;
- model corruption fault injection without a safe supported injection path;
- Stage 17.3 long-transcript map/reduce/checkpoint hierarchy;
- production model-channel promotion.

Production model promotion or wider feature gating remains a separate release decision requiring explicit authorization.

---

## 9. Known limitations

1. Structured summary on the current device is intentionally CPU-contained and can be materially slower than a stable GPU path would be.
2. The direct-summary prompt budget is finite; inputs above the accepted budget are rejected rather than partially summarized.
3. Long transcripts require Stage 17.3 architecture rather than relaxing the Stage 17.2 budget.
4. Structured GPU is technically capable of completing on the current device but is not reliable enough for production because equivalent runs can ANR before first emission.
5. Cross-device GPU behavior is not inferred from the current device.
6. Stage 17.2 does not authorize a model/runtime/schema/prompt upgrade merely to pursue GPU performance.

---

## 10. Closure result

Stage 17.2 — Offline Summary V1:

**CLOSED / PASS WITH PRODUCT CONTAINMENT / STRUCTURED GPU REQUALIFICATION DEFERRED**

The direct offline summary product path may be carried forward as a frozen dependency for Stage 17.3 planning.

PR #37 remains:

**DRAFT / OPEN / DO NOT MERGE**

This closure does not authorize merge, production model-channel promotion, structured-GPU restoration or Stage 17.3 implementation without its own confirmed requirements/development plan.
