# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — 16B.1 CANDIDATE RUNTIME INTEGRATION**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Requirements baseline: **Stage 16B revised requirements V5 — confirmed by user**

Development-plan baseline: **Stage 16B development plan V1 — confirmed by user**

## Frozen starting identity

- versionCode: `92`
- versionName: `1.1.0-stage16a2`
- Room: `v14`
- production model channel: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion: `7`
- Stage 16A.2 Annotation owner remains `Recording`
- Stage 16A.2 durable primary annotation coordinate remains canonical/realtime `sampleIndex`

## Hard boundaries

- `ioannes78/voice-card-android` remains fully isolated; no copy, migration, cherry-pick, mechanical translation, or rewrite from that project.
- Stage 16B does not modify QS668/CB08 BLE protocol, Android Audio I/O ownership, playback ownership, or Stage 16A.2 annotation ownership.
- Production model-channel changes require a separate explicit promotion gate and user authorization.
- CI success never substitutes for real-device QA.
- Status must remain explicit: `AUTOMATED PASS`, `REAL-DEVICE PASS`, `NOT RUN`, `NOT VERIFIED`, or `WAIVED` as applicable.

## Mandatory Stage 16B gates

### Gate A — VAD Matrix Gate

Candidates:

- Silero VAD — current production baseline
- FSMN-VAD — Stage 16B challenger

Required before final local-file VAD selection:

- boundary-quality benchmark
- downstream ASR impact
- diarization impact
- resource/performance comparison
- representative long-file validation

If results are materially equivalent, retain Silero. FSMN replaces Silero only with clear full-pipeline evidence.

### Gate B — ASR Model Matrix Gate

Candidates:

- SenseVoice INT8
- Fun-ASR-Nano INT8
- Qwen3-ASR 0.6B INT8

All final ASR comparisons must use the VAD baseline frozen by Gate A unless a model-specific VAD exception is separately justified by strong evidence and explicitly approved.

The gate must decide the long-term product matrix; model count is not predetermined.

## Context Lexicon scope

Stage 16B includes a model-independent `AsrContextLexicon` product capability.

The design must keep separate:

- model-level context bias / hotwords
- deterministic or derived text correction
- immutable raw machine ASR evidence

Each transcription run must retain an immutable effective-context snapshot or equivalent reproducible lineage.

## 16B.0 — Baseline / Benchmark Harness

Status: **AUTOMATED PASS**

Implemented:

- model/VAD-neutral local-file benchmark observation contract;
- deterministic CER/WER edit-distance scoring;
- substitution / insertion / deletion accounting;
- deterministic CER normalization;
- explicit-token WER scoring without inventing Chinese word segmentation;
- RTF calculation;
- automated unit coverage.

Audit conclusion:

The existing high-quality local-file transcription path already performs bounded sequential PCM reads, VAD segmentation, bounded offline-ASR chunks, optional timing-reference alignment, capability-aware punctuation finalization, and deterministic engine/source closing. Stage 16B extends this architecture rather than replacing it.

Automation evidence:

- branch candidate: `837d2d44e544e073179cf57be75fa4f850bde80a`
- Android PR CI `#1801` / run `39223677715`: **SUCCESS**
- Fast PR Gate: **SUCCESS**
- QA APK Gate: **SKIPPED by policy**
- Android Production Gate `#651` / run `39223677810`: **SKIPPED by policy**

No real-device benchmark was required for 16B.0 infrastructure itself.

## 16B.1 — Candidate Runtime Integration

Status: **IN PROGRESS**

### Fun-ASR-Nano candidate

Implemented in the existing sherpa large-offline-ASR adapter:

- runtime model type `funasr-nano`;
- sherpa-onnx `OfflineFunAsrNanoModelConfig` wiring;
- expected INT8 layout:
  - `encoder_adaptor.int8.onnx`
  - `llm.int8.onnx`
  - `embedding.int8.onnx`
  - non-empty `Qwen3-0.6B/` tokenizer directory;
- candidate settings for system/user prompt, generation parameters, language, ITN and hotwords;
- native punctuation path remains capability-driven;
- token timing is **not** pre-declared as production-supported; Stage 16B requires actual candidate/runtime verification;
- layout validation tests reject missing/empty tokenizer state before native load.

Runtime compatibility was checked against sherpa-onnx `v1.13.8`, the runtime currently pinned by Voica. In that runtime, Fun-ASR-Nano and Qwen3-ASR intentionally use tokenizer directories and do not require a `tokens.txt` path.

Automation evidence:

- Fun-ASR candidate code head: `2fb25d344fb689c1b704efef1b282e798ccc54a4`
- Android PR CI `#1803` / run `39227036295`: **SUCCESS**
- Fast PR Gate: **SUCCESS**
- Android Production Gate `#653` / run `39227036363`: **SKIPPED by policy**

Still required before Fun-ASR candidate can be exercised on-device:

- exact official package metadata and hashes;
- candidate descriptor / manifest outside the immutable production manifest;
- isolated runtime validation;
- candidate download/install/activation QA;
- real-device transcription QA including punctuation, hotwords and timestamp inspection.

### FSMN-VAD candidate

Status: **BLOCKED ON CLEAN ANDROID RUNTIME PATH — NOT IMPLEMENTED / NOT VERIFIED**

Current finding:

- Voica's pinned sherpa-onnx Android/Kotlin VAD surface does not expose FSMN-VAD;
- existing Voica has no separate ONNX Runtime dependency;
- directly adding a second ORT runtime only for FSMN could add native-library/APK-size/ABI/conflict risk and is not authorized as an implicit shortcut.

Therefore Stage 16B does **not** pretend FSMN is integrated. The next FSMN step is to establish a clean Android runtime path and evaluate its binary/runtime cost before candidate implementation.

This blocker does not authorize silently dropping FSMN from Gate A; if a valid Android runtime cannot be established, the VAD Benchmark Report must state the affected FSMN items as `NOT RUN / NOT VERIFIED` with the exact reason.

## Product-state non-changes

At the current 16B.1 point:

- Fun-ASR-Nano is **not** in the normal product offline-ASR matrix;
- FSMN-VAD is **not** the product VAD;
- Silero remains the current production file-ASR VAD baseline;
- SenseVoice / Qwen3 product selection remains unchanged;
- Room remains `v14`;
- production model channel remains unchanged;
- no production model promotion has been authorized.

## Next planned work

1. Obtain exact official Fun-ASR-Nano package metadata/hashes and prepare a candidate-only manifest/descriptor path.
2. Run isolated candidate runtime validation before normal app activation.
3. Establish or reject a clean FSMN Android runtime approach without silently introducing a duplicate inference runtime.
4. Only after both VAD candidates are technically testable may Stage 16B proceed to the full 16B.2 VAD benchmark and Gate A.

This progress document records development state only. It is not a Freeze or Handoff document.
