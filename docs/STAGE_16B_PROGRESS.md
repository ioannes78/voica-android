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

For Gate A, VAD post-processing policy is part of the benchmark configuration and must be frozen explicitly. The first reproducible FSMN comparison will use the pinned model/config end-silence semantics rather than silently varying the silence threshold with accumulated speech duration.

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

Candidate model-channel path:

- candidate branch: `ioannes78/voica-model-channel:stage16b-funasr-nano-candidate`
- prerelease tag/release: `candidate-funasr-nano-r1`
- candidate manifest URL: `https://github.com/ioannes78/voica-model-channel/releases/download/candidate-funasr-nano-r1/production.json`
- candidate manifestVersion: `8`
- candidate manifestDigest: `a05fab6a9f6464762a38e1a023c8f96f83db2fbe24585a766a14e2279aa12063`
- official upstream package size: `841730611` bytes
- official package SHA-256: `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`
- required installed files total: `1010070962` bytes
- candidate package/inner-file verification workflow `39228400994`: **SUCCESS**

The production model-channel `main` remains unchanged at `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec` / manifestVersion `7`.

QA-only candidate tooling boundary:

- pinned production model IDs continue to exclude `funasr-nano-int8`;
- Debug and `releaseQa` may use candidate release manifests;
- formal `release` keeps candidate-channel tooling disabled;
- the normal product offline-ASR matrix remains unchanged.

Automation evidence:

- Fun-ASR adapter Android PR CI `#1803` / run `39227036295`: **SUCCESS**
- candidate-channel / QA tooling Android PR CI `#1810` / run `39229577661`: **SUCCESS**
- Fast PR Gate: **SUCCESS**
- Android Production Gate `#660`: **SKIPPED by policy**

Production-like candidate QA APK:

- Android PR CI `#1811` / run `39229974350`: **SUCCESS**
- QA APK Gate: **SUCCESS**
- Build / R8 verification / release-artifact verification / security-model-channel verification / sherpa native-package verification: **SUCCESS**
- artifact ID: `12176594114`
- artifact ZIP digest: `sha256:797955d78507e17567f18fa1450e1dd14fc2afe85773d692007d722a8bbe358b`
- artifact retention: `1 day`
- verified package: `io.github.ioannes78.voica.qa`
- verified version: `1.1.0-stage16a2-export-qa (92)`
- signing identity: `qa`

Current Fun-ASR status:

- adapter compile/tests: **AUTOMATED PASS**
- candidate package integrity generation: **AUTOMATED PASS**
- production-like QA APK build/security/native packaging: **AUTOMATED PASS**
- candidate manifest selection on real device: **NOT VERIFIED**
- candidate model download/resume/integrity on real device: **NOT VERIFIED**
- isolated native runtime validation on real device: **NOT VERIFIED**
- atomic candidate activation on real device: **NOT VERIFIED**
- real-device transcription quality/punctuation/hotword/timestamp behavior: **NOT RUN / NOT VERIFIED**

### FSMN-VAD candidate

Status: **CANDIDATE ENGINE IMPLEMENTED — AUTOMATED PASS / REAL-DEVICE NOT VERIFIED**

Implemented:

- dedicated `engine:fsmn` Android module;
- existing `VadEngine` contract reused; no second VAD state machine was added to the transcription domain;
- canonical PCM contract remains 16 kHz / mono / PCM16;
- bounded sequential PCM reads with sample-index `SpeechSegment` output;
- incremental FBank / LFR / CMVN frontend;
- four FSMN cache tensors (`128 x 19`) maintained across incremental inference;
- native ONNX inputs `speech + in_cache0..3` and outputs `logits + out_cache0..3` validated against the candidate model contract;
- existing APK `libonnxruntime.so` is resolved dynamically via `dlopen` / `OrtGetApiBase`;
- no second ONNX Runtime library is packaged or linked by the FSMN candidate JNI;
- FSMN runtime identity is candidate-only: `voica-fsmn-ort / 1.0.0`;
- candidate model ID is `fsmn-vad-int8` and remains excluded from the pinned production model catalog;
- Debug / `qa` / `releaseQa` may package the FSMN candidate runtime; formal `release` does not depend on `engine:fsmn`;
- isolated `:model_validator` routes FSMN descriptors to `FsmnModelCandidateValidator` without creating a compile-time App dependency on the candidate runtime;
- validator smoke test covers native library load, shared ONNX Runtime resolution, model/CMVN initialization and one bounded inference pass;
- R8/JNI keep rules protect the reflection and native ABI used only by candidate builds.

Pinned upstream candidate assets prepared for model-channel packaging:

- upstream repository: `funasr/fsmn-vad-onnx`;
- fixed upstream revision: `eeb66f413b94a33458f7edbe7f501b10f20fcc85`;
- `model_quant.onnx`: `508876` bytes, SHA-256 `9b28837838fce9685503c63139fadbad35d6c8ed485485dafdbb32e725969660`;
- `vad.mvn`: `8040` bytes, SHA-256 `6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754`;
- upstream license: Apache-2.0.

Automated runtime-boundary verification checks:

- arm64-v8a candidate JNI is produced;
- no FSMN-owned `libonnxruntime.so` is packaged;
- no additional ABI is introduced;
- FSMN JNI has no `DT_NEEDED` dependency on `libonnxruntime.so`;
- candidate JNI remains under the configured size bound.

Automation evidence:

- Android PR CI `#1844` / run `39241110395`: **SUCCESS**
- Unit tests and debug compile gate: **SUCCESS**
- Verify FSMN candidate runtime boundary: **SUCCESS**
- QA APK Gate: **SKIPPED by policy**
- Android Production Gate `#691` / run `39241110498`: **SKIPPED by policy**
- verified candidate head: `58d7098c1b37e5c1e67d1ed67ad9da7372b324d3`

Current FSMN status:

- candidate engine implementation: **AUTOMATED PASS**
- single-ORT packaging/runtime boundary: **AUTOMATED PASS**
- QA-only isolated validator routing: **AUTOMATED PASS**
- candidate model-channel prerelease/package: **NOT RUN / NOT VERIFIED**
- model download/resume/integrity on real device: **NOT VERIFIED**
- isolated FSMN model init/inference on real device: **NOT VERIFIED**
- VAD segmentation quality / boundary accuracy: **NOT RUN / NOT VERIFIED**
- Gate A Silero-vs-FSMN comparison: **NOT RUN**

Post-processing note before Gate A:

The current candidate endpoint implementation follows FunASR's current dynamic silence schedule. For the first reproducible VAD matrix comparison, Stage 16B will explicitly freeze the FSMN end-silence policy to the pinned model/config semantics (`max_end_silence_time=800 ms`, `speech_to_silence=150 ms`, `lookahead_end=100 ms`) rather than allowing accumulated-speech duration to change the threshold. Dynamic silence can be benchmarked later as a separate tuning variant if FSMN survives Gate A.

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

1. Package and publish the pinned FSMN files through a candidate-only model-channel prerelease; production manifest remains unchanged.
2. Build a production-like `releaseQa` APK containing the candidate runtime only in the QA variant.
3. Real-device FSMN bring-up: candidate manifest selection → download/resume/integrity → isolated runtime validation → atomic activation → short VAD smoke.
4. Freeze the reproducible FSMN end-silence policy before quality benchmarking.
5. Complete the equivalent pending Fun-ASR candidate-channel real-device bring-up.
6. Only after Silero and FSMN are both technically testable may Stage 16B run the full VAD benchmark and Gate A.

This progress document records development state only. It is not a Freeze or Handoff document.
