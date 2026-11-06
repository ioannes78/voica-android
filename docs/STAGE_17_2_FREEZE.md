# Stage 17.2 — Offline Summary V1 Freeze

Status: **FROZEN / STRUCTURED PRODUCTION PATH CPU-CONTAINED / STRUCTURED GPU DEFERRED**

Repository: `ioannes78/voica-android`

Development branch: `stage17-fully-offline-intelligence`

Pull request: `#37 — Stage 17 — Fully Offline Intelligence`

Product / QA evidence freeze HEAD:

`fa075301adf22ae22803715673cafe9cb26587aa`

The closure/freeze/handoff commits are documentation-only and do not alter the product behavior frozen at the HEAD above.

---

## 1. Frozen Stage 17.2 product boundary

Stage 17.2 is frozen as a direct, fully offline transcript-summary path.

```text
Current Effective Transcription
  -> StructuredTranscriptInput
  -> OfflineSummaryPrompt
  -> direct budget gate
  -> OfflineSummaryCoordinator
  -> AppLocalLlmRuntime.generate(AUTO)
  -> structured production CPU containment
  -> strict validation / optional one repair
  -> evidence mapping
  -> existing AiSummary version lifecycle
  -> existing summary UI/search/export/evidence playback
```

Stage 17.2 does not include long-transcript map/reduce, hierarchical summarization or checkpointed reduction. Those belong to Stage 17.3.

---

## 2. Frozen runtime/model identity

Do not change these Stage 17.2 production assumptions without a separately reviewed revision:

- LiteRT-LM Android: `0.18.0`
- model: `litert-community/gemma-4-E2B-it-litert-lm`
- revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- artifact: `gemma-4-E2B-it.litertlm`
- size: `2,583,085,056` bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- R8/JNI boundary: `-keep,allowoptimization class com.google.ai.edge.litertlm.** { *; }`

No production model-channel promotion is implied by this freeze.

---

## 3. Frozen prompt/output contract

- prompt version: `170204`
- Simplified Chinese default output
- JSON-only structured response
- bounded request-specific `responseJsonSchema`
- strict evidence allowlist semantics
- no model-authored authoritative timestamps/sample indices
- strict result codec/validation
- at most one bounded local repair attempt
- invalid/unknown evidence must not be persisted as successful completed evidence

Generation preset:

- `maxNumTokens=4000`
- `maxOutputTokens=1024`
- `topK=64`
- `topP=0.95`
- `temperature=1.0`
- `speculativeDecoding=false`

---

## 4. Frozen direct-input budget

The direct-summary budget is frozen as:

- context budget: `4000`
- output reserve: `1024`
- safety reserve: `192`
- estimator margin: `256`
- maximum estimated prompt tokens: `2528`

Over-budget behavior is fail-closed:

`INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY`

The rejection occurs before native generation. Stage 17.2 must not silently truncate input or introduce map/reduce as an implicit fallback.

---

## 5. Frozen backend policy

Formal product requests continue to request `AUTO` through `AppLocalLlmRuntime`.

For Stage 17.2 structured output, the production containment is frozen as:

`AUTO + responseJsonSchema -> CPU`

This policy exists because current-device structured GPU is not reliability-qualified.

The following remain valid:

- CPU structured offline summary;
- unconstrained GPU capability where already qualified;
- explicit GPU diagnostics in QA-only surfaces.

The following remain prohibited for production Stage 17.2 until requalification:

- explicit structured GPU as the product default;
- restoring structured `AUTO` to GPU-first from a single successful run;
- automatic CPU fallback after ANR/unknown GPU ownership in the same session;
- silently retrying an unknown/poisoned GPU session.

GPU is deferred, not removed.

---

## 6. Structured-GPU requalification gate

Another device may reopen structured-GPU qualification while preserving the frozen model/runtime contract first.

Minimum evidence required before any production restoration:

1. fresh-process formal prompt + GPU + no schema;
2. fresh-process small prompt + GPU + formal schema;
3. fresh-process formal prompt + GPU + formal schema;
4. repeated formal structured GPU inference in the same process;
5. every successful inference reaches confirmed `ENGINE_CLOSED`;
6. no ANR, restart, `RELEASE_UNCONFIRMED`, `POISONED` or unknown ownership;
7. only after foreground repeat stability, repeat lifecycle/background/lock qualification.

A new device passing does not automatically authorize global GPU restoration. If device behavior differs, the next design decision must be evidence-driven: device/GPU capability gating or continued global CPU containment.

---

## 7. Frozen persistence and lifecycle rules

- Room remains version 16.
- No Stage 17.2-specific schema migration is authorized by this freeze.
- Each regeneration creates a distinct summary version/candidate.
- Earlier completed summaries are not overwritten.
- Current/candidate behavior reuses the existing summary lifecycle.
- only validated results may become `COMPLETED`.
- cancellation and interruption remain explicit terminal outcomes.
- process death does not silently auto-resume generation.
- historical online/provider summaries remain historical and must not be relabeled as local.

---

## 8. Privacy and isolation rules

- no cloud fallback;
- no transcript/audio upload for Stage 17.2 inference;
- no API key requirement for local inference;
- Local LLM work must remain isolated from recording, BLE, playback and ASR critical paths;
- evidence playback continues through the existing canonical playback owner;
- QA crash/ANR diagnostics remain content-free and must not persist prompt, transcript or generated model text.

---

## 9. Change-control boundary

The following require a separate requirements/revision decision rather than an incidental Stage 17.2 edit:

- LiteRT-LM version change;
- model/revision/artifact change;
- prompt-version change;
- response-schema contract change;
- direct-budget change;
- structured GPU production restoration;
- Room schema migration;
- production model-channel promotion;
- long-transcript hierarchy.

Bug fixes that preserve the frozen contracts may be evaluated normally, but they must not silently widen the production capability claim.

---

## 10. Freeze result

Stage 17.2 is frozen as:

**OFFLINE SUMMARY V1 / DIRECT SUMMARY / CPU-CONTAINED STRUCTURED PRODUCTION PATH / NO CLOUD FALLBACK**

Detailed closure evidence:

- `docs/STAGE_17_2_CLOSURE.md`
- `docs/STAGE_17_2_R5_CLOSURE.md`

PR #37 remains:

**DRAFT / OPEN / DO NOT MERGE**
