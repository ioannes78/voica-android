# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — 16B.1 CANDIDATE RUNTIME INTEGRATION / GATE A EXPANDED TO THREE VADS**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Requirements baseline: **Stage 16B revised requirements V5 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment**

Development-plan baseline: **Stage 16B development plan V1 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment**

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
- Status remains explicit: `AUTOMATED PASS`, `REAL-DEVICE PASS`, `NOT RUN`, `NOT VERIFIED`, or `WAIVED` as applicable.

## Mandatory Stage 16B gates

### Gate A — VAD Matrix Gate

Candidates are now explicitly three-way:

- **Silero VAD** — current production baseline
- **FSMN-VAD** — Stage 16B challenger
- **TEN-VAD** — Stage 16B challenger added by explicit user amendment

Required before final local-file VAD selection:

- same canonical PCM inputs;
- frozen and recorded per-VAD configuration;
- speech recall / precision where labelled references exist;
- start/end boundary error and sentence-start/sentence-end loss;
- missed-speech and false-speech duration;
- segment count, short-fragment count and forced-split count;
- VAD RTF / wall time / memory impact;
- downstream ASR impact using the same ASR, with SenseVoice as the first low-confound reference path;
- representative diarization impact;
- representative long-file validation after the initial shortlist.

Decision principle:

- if results are materially equivalent, retain Silero to avoid unnecessary migration;
- FSMN or TEN replaces Silero only with clear full-pipeline evidence;
- do not bind a different VAD to each ASR unless later evidence shows a large, stable, explicitly approved model-specific benefit.

For Gate A, VAD post-processing policy is part of the benchmark configuration and must be frozen explicitly.

FSMN first reproducible baseline must use the pinned ONNX semantics:

- `max_end_silence_time = 800 ms`
- `speech_to_silence = 150 ms`
- `lookahead_end = 100 ms`

The current dynamic FSMN silence schedule may later be benchmarked only as a separate tuning variant; it must not be silently mixed into the baseline.

TEN-VAD baseline parameters will be frozen from the exact sherpa-onnx `v1.13.8` candidate model/runtime path before Gate A rather than copied from another release or project.

### Gate B — ASR Model Matrix Gate

Candidates:

- SenseVoice INT8
- Fun-ASR-Nano INT8
- Qwen3-ASR 0.6B INT8

All final ASR comparisons must use the VAD baseline frozen by Gate A unless a model-specific VAD exception is separately justified by strong evidence and explicitly approved.

The gate must decide the long-term product matrix; model count is not predetermined.

## Context Lexicon scope

Stage 16B includes a model-independent `AsrContextLexicon` product capability.

The design keeps separate:

- model-level context bias / hotwords;
- deterministic or derived text correction;
- immutable raw machine ASR evidence.

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

Status: **AUTOMATED PASS / REAL-DEVICE NOT VERIFIED**

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

Runtime compatibility was checked against sherpa-onnx `v1.13.8`, the runtime currently pinned by Voica.

Candidate model-channel path:

- candidate branch: `ioannes78/voica-model-channel:stage16b-funasr-nano-candidate`
- prerelease: `candidate-funasr-nano-r1`
- candidate manifest URL: `https://github.com/ioannes78/voica-model-channel/releases/download/candidate-funasr-nano-r1/production.json`
- candidate manifestVersion: `8`
- candidate manifestDigest: `a05fab6a9f6464762a38e1a023c8f96f83db2fbe24585a766a14e2279aa12063`
- official upstream package size: `841730611` bytes
- official package SHA-256: `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`
- required installed files total: `1010070962` bytes
- candidate package/inner-file verification workflow `39228400994`: **SUCCESS**

Production-like candidate QA APK evidence:

- Android PR CI `#1811` / run `39229974350`: **SUCCESS**
- QA APK Gate: **SUCCESS**
- artifact ID: `12176594114`
- verified package: `io.github.ioannes78.voica.qa`
- verified version: `1.1.0-stage16a2-export-qa (92)`

Current Fun-ASR status:

- adapter compile/tests: **AUTOMATED PASS**
- candidate package integrity generation: **AUTOMATED PASS**
- production-like QA APK build/security/native packaging: **AUTOMATED PASS**
- candidate manifest selection on real device: **NOT VERIFIED**
- candidate model download/resume/integrity on real device: **NOT VERIFIED**
- isolated native runtime validation on real device: **NOT VERIFIED**
- atomic candidate activation on real device: **NOT VERIFIED**
- transcription quality/punctuation/hotword/timestamp behavior: **NOT RUN / NOT VERIFIED**

### FSMN-VAD candidate

Status: **AUTOMATED PASS / CANDIDATE RUNTIME BRING-UP REAL-DEVICE PASS / QUALITY GATE NOT RUN**

Implemented:

- dedicated `engine:fsmn` Android module;
- existing `VadEngine` contract reused;
- canonical PCM remains 16 kHz / mono / PCM16;
- bounded sequential PCM reads with sample-index `SpeechSegment` output;
- incremental FBank / LFR / CMVN frontend;
- four FSMN cache tensors (`128 x 19`) across incremental inference;
- native ONNX I/O `speech + in_cache0..3` and `logits + out_cache0..3`;
- existing APK `libonnxruntime.so` resolved dynamically via `dlopen` / `OrtGetApiBase`;
- no second ONNX Runtime packaged or linked;
- candidate runtime identity `voica-fsmn-ort / 1.0.0`;
- candidate model ID `fsmn-vad-int8` excluded from pinned production catalog;
- Debug / `qa` / `releaseQa` may package the candidate runtime; formal `release` does not depend on `engine:fsmn`;
- isolated `:model_validator` routes FSMN descriptors through the candidate validator;
- validator smoke covers native load, shared ORT resolution, model/CMVN initialization and bounded inference;
- R8/JNI rules preserve the reflection/native ABI used by candidate builds.

Pinned upstream candidate assets:

- upstream repository: `funasr/fsmn-vad-onnx`
- fixed revision: `eeb66f413b94a33458f7edbe7f501b10f20fcc85`
- `model_quant.onnx`: `508876` bytes / SHA-256 `9b28837838fce9685503c63139fadbad35d6c8ed485485dafdbb32e725969660`
- `vad.mvn`: `8040` bytes / SHA-256 `6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754`
- license: Apache-2.0

FSMN candidate model channel:

- branch: `stage16b-fsmn-vad-candidate`
- prerelease: `candidate-fsmn-vad-r1`
- manifest URL: `https://github.com/ioannes78/voica-model-channel/releases/download/candidate-fsmn-vad-r1/production.json`
- candidate manifestDigest: `74789e3befd2e85eba5a1f2d61e5635b7e132817acd4307a39500b561faf9300`
- package: `478102` bytes / SHA-256 `719be2c2243b11069f6701fb6c478ffd1dc4f342d93753c438a0f13c7e4769e8`
- installed size: `516916` bytes
- candidate workflow `39241804263`: **SUCCESS**

Android automation at candidate head `5c6bd529a07cd52effc9ddccf1c96b7b240e9b65`:

- Android PR CI `#1849` / run `39242383824`: **SUCCESS**
- Unit tests and debug compile gate: **SUCCESS**
- Verify FSMN candidate runtime boundary: **SUCCESS**
- Production Gate `#695`: **SKIPPED by policy**

Production-like FSMN QA APK:

- Android PR CI `#1850` / run `39256583378`: **SUCCESS**
- QA APK Build / Verify / Upload: **SUCCESS**
- artifact ID: `12188580296`
- artifact ZIP digest: `sha256:64797f56a93b17cf037147bab1447dcc3248199631f9a8d195437736c30d59e5`
- APK size: `32015616` bytes
- APK SHA-256: `89f7f844ba172d69a745b0bf389579e1aa827ad3cc9c336215b47d1b664031c6`
- package: `io.github.ioannes78.voica.qa`
- version: `1.1.0-stage16a2-export-qa (92)`

Real-device bring-up evidence:

- candidate manifest selected successfully;
- `FSMN VAD INT8` revision `eeb66f4` downloaded, validated and activated;
- model UI showed available revision `eeb66f4` and current revision `eeb66f4`;
- candidate download → file integrity → isolated runtime validation → activation main chain: **REAL-DEVICE PASS**;
- interrupted-download resume behavior: **NOT VERIFIED**;
- segmentation quality / boundary accuracy: **NOT RUN**;
- Gate A quality comparison: **NOT RUN**.

### TEN-VAD candidate

Status: **RUNTIME API VERIFIED / CANDIDATE MODEL PACKAGE NOT YET INTEGRATED / REAL-DEVICE NOT VERIFIED**

Scope amendment:

TEN-VAD is now a formal Gate A challenger alongside Silero and FSMN.

Runtime facts verified against the exact Voica-pinned sherpa-onnx `v1.13.8` Kotlin API:

- `TenVadModelConfig` is natively available;
- TEN-VAD uses the same sherpa `Vad` API family as Silero;
- `VadConfig` can carry either `SileroVadModelConfig` or `TenVadModelConfig`;
- sample-rate baseline is 16 kHz;
- `TenVadModelConfig` exposes threshold, minimum silence duration, minimum speech duration, maximum speech duration, window size, thread count, debug and provider settings;
- v1.13.8 defaults include threshold `0.5`, min silence `0.25 s`, min speech `0.25 s`, max speech `20 s`, window size `256`, one thread and CPU provider.

Integration consequence:

- no new inference runtime is required;
- no FSMN-style second JNI/runtime bridge is required;
- implementation should remain inside the existing sherpa VAD adapter family;
- TEN-VAD remains candidate-only until Gate A and explicit later product decision;
- exact model asset, immutable source/revision, package/file hashes and redistribution license must be pinned before candidate-channel publication;
- production model channel and current Silero product baseline remain unchanged.

Current TEN-VAD status:

- exact sherpa-onnx v1.13.8 Android/Kotlin API support: **VERIFIED**
- model asset/license/digest pin: **NOT RUN / NOT VERIFIED**
- Android adapter integration: **NOT RUN**
- candidate model-channel package/prerelease: **NOT RUN**
- real-device download/validation/activation: **NOT VERIFIED**
- VAD quality benchmark: **NOT RUN**

## Gate A benchmark execution policy after TEN amendment

The first comparison will avoid a wasteful full Cartesian long-recording matrix.

Phase A1 — same-input three-VAD core benchmark:

`Canonical PCM → Silero / FSMN / TEN → independent VAD metrics`

Phase A2 — downstream reference-ASR benchmark:

`Silero / FSMN / TEN → same SenseVoice path → CER/WER + sentence-boundary loss + total time`

Phase A3 — representative diarization impact:

- VAD segment count;
- downstream speaker-segmentation / CAM++ embedding workload;
- clustering time;
- speaker fragmentation/confusion on representative multi-speaker samples.

Phase A4 — long-file validation only after initial shortlist:

- 10–15 min: all three candidates;
- 30 min: candidates still competitive after A1/A2;
- 60/120 min: final Gate A finalists as applicable.

Only if a VAD appears strongly coupled to a specific ASR will a representative `3 VAD × 3 ASR` interaction subset be run. It is not the default first benchmark.

## Product-state non-changes

At the current Stage 16B point:

- Fun-ASR-Nano is **not** in the normal product offline-ASR matrix;
- FSMN-VAD is **not** the product VAD;
- TEN-VAD is **not** the product VAD;
- Silero remains the current production file-ASR VAD baseline;
- SenseVoice / Qwen3 product selection remains unchanged;
- Room remains `v14`;
- production model channel remains unchanged;
- no production model promotion has been authorized.

## Next planned work

1. Pin TEN-VAD's exact sherpa-compatible model asset, immutable source/revision, hashes and redistribution license.
2. Integrate TEN-VAD through the existing sherpa VAD adapter family and candidate-only model catalog; do not add a new runtime.
3. Freeze FSMN's first Gate A baseline to the pinned ONNX endpoint semantics (`800/150/100 ms`).
4. Build one Gate A QA path capable of running the same canonical recording through Silero, FSMN and TEN with frozen per-candidate configs.
5. Complete automated tests and Fast PR Gate.
6. Complete TEN candidate real-device download/validation/activation smoke.
7. Run the three-way VAD Matrix Gate A benchmark and produce `Stage 16B VAD Benchmark Report`.
8. Stop for explicit user VAD selection before the final three-ASR benchmark.
9. Complete the pending Fun-ASR real-device candidate bring-up before ASR Model Matrix Gate B.

This progress document records development state only. It is not a Freeze or Handoff document.
