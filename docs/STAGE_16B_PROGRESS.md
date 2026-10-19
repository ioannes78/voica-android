# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — 16B.0 BASELINE / BENCHMARK HARNESS**

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

## 16B.0 — current work

Current scope is intentionally limited to baseline audit and benchmark infrastructure.

Goals:

1. Reuse the existing bounded local-file pipeline rather than create a second transcription pipeline.
2. Establish deterministic CER/WER edit-distance scoring helpers.
3. Establish a model/VAD-neutral benchmark observation contract for later VAD and ASR candidate runs.
4. Keep Room v14 and existing product defaults unchanged.
5. Add automated tests for benchmark scoring before integrating FSMN-VAD or Fun-ASR-Nano.

### Current audit conclusion

The existing high-quality local-file transcription path already performs bounded sequential PCM reads, VAD segmentation, bounded offline-ASR chunks, optional timing-reference alignment, capability-aware punctuation finalization, and deterministic engine/source closing. Stage 16B should extend this architecture rather than replace it.

## 16B.0 completion gate

Before moving to 16B.1 candidate runtime integration:

- benchmark scoring helpers compile and unit-test successfully;
- benchmark observation contract is model/VAD neutral;
- no production ASR/VAD selection changes are present;
- Room remains v14;
- production model channel remains unchanged;
- Fast PR CI is green on the 16B.0 branch/PR.

## Next planned step after 16B.0

`16B.1 — FSMN-VAD candidate + Fun-ASR-Nano candidate runtime integration`

This progress document records development state only. It is not a Freeze or Handoff document.
