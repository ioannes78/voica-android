# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / GATE B B0 REAL-DEVICE PASS / B1 AUTOMATED PASS / B1 REAL-DEVICE NEXT**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Requirements baseline: **Stage 16B revised requirements V5 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment and final fallback revision**

Development-plan baseline: **Stage 16B development plan V1 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment and final fallback revision**

## Frozen starting identity

- versionCode: `92`
- versionName: `1.1.0-stage16a2`
- Room: `v14`
- starting production model channel: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- starting production manifestVersion: `7`
- Stage 16A.2 Annotation owner remains `Recording`
- Stage 16A.2 durable primary annotation coordinate remains canonical/realtime `sampleIndex`

## Hard boundaries

- `ioannes78/voice-card-android` remains fully isolated; no copy, migration, cherry-pick, mechanical translation, or rewrite from that project.
- Stage 16B does not modify QS668/CB08 BLE protocol, Android Audio I/O ownership, playback ownership, or Stage 16A.2 annotation ownership.
- Production model-channel changes require a separate explicit promotion gate and user authorization.
- CI success never substitutes for real-device QA.
- Status remains explicit: `AUTOMATED PASS`, `REAL-DEVICE PASS`, `NOT RUN`, `NOT VERIFIED`, or `WAIVED` as applicable.

## Gate A — VAD Matrix Gate

Gate A compared:

- Silero VAD — production baseline
- FSMN-VAD — challenger
- TEN-VAD — challenger added by explicit user amendment

Benchmark evidence is frozen in:

`docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`

Final product policy is frozen in:

`docs/STAGE_16B_GATE_A_FINAL_DECISION.md`

Final production decision:

`FSMN VAD INT8 (primary) → Silero VAD INT8 (fallback)`

TEN remains candidate / QA only because its production deployment-license applicability is not sufficiently clear. This no longer blocks Stage 16B Gate B.

Gate A technical evidence summary:

- A1: FSMN led envelope consistency and VAD RTF.
- A2 weighted CER: FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`.
- A3: FSMN materially reduced fragmentation and diarization workload versus Silero.
- A4 30-minute: FSMN total RTF `0.128` versus Silero `0.187`; VAD wall `13.337 s` versus `131.736 s`.
- 60/120-minute extension was explicitly waived by the user.
- production-like QA for the Gate A wiring checkpoint passed on device.

Current production model-channel staging:

- manifestVersion: `8`
- production model-channel commit: `8f2f94ef7a376f1b0b652072ef67df5e07dd31de`
- production manifestDigest: `05dcd9f205a3383c2bda0704996b85e741384daa9c2e8a63ff132b74b5424d37`
- FSMN product version: `1.0.0`
- FSMN upstream revision remains provenance only: `eeb66f413b94a33458f7edbe7f501b10f20fcc85`
- TEN is excluded from the production manifest and stays candidate-only.

Gate A-only `A4 QA` / `VAD QA` product entry points have been removed.

## Gate B — ASR Model Matrix Gate

Status: **B0 REAL-DEVICE PASS / B1 AUTOMATED PASS / B1 REAL-DEVICE NEXT**

Candidates:

- SenseVoice INT8
- Fun-ASR-Nano INT8
- Qwen3-ASR 0.6B INT8

All formal Gate B comparisons use exact active `fsmn-vad-int8`. A benchmark run that resolves the Local File Silero availability fallback is invalid for Gate B evidence.

The gate must decide the long-term product matrix; model count is not predetermined.

### B0 Candidate Readiness harness

The QA-only B0 harness is implemented and documented in:

`docs/STAGE_16B_GATE_B_B0.md`

B0 benchmark contract:

1. Resolve exact active FSMN directly; do not accept Silero fallback.
2. Run FSMN exactly once on canonical PCM.
3. Freeze canonical `sampleIndex` speech boundaries.
4. Deterministically split only oversized ranges and reuse the exact same PCM chunks for every ASR candidate.
5. Run Fun-ASR-Nano → SenseVoice → Qwen3-ASR against the same frozen inputs.
6. Keep external punctuation and Qwen timeline alignment out of B0 so the checkpoint measures raw candidate readiness rather than downstream enrichment.
7. Do not persist benchmark transcript state to Room.

The harness captures model identity, canonical lineage SHA, shared FSMN identity/timing, engine-open time, ASR call/blank counts, raw text, detected language when available, ASR/total wall time, RTF and PSS.

Automated checkpoint:

- implementation commit: `118e283d67d3d1cc8acfe83d925157dd84c57aab`
- compile fix commit: `a27ed998db56e8edd71c17ff50303285ae3453bf`
- Android PR CI #1945 / Fast PR Gate: **AUTOMATED PASS**
- unit tests + debug compile: **PASS**
- FSMN runtime boundary: **PASS**
- Android PR CI #1946 / production-like QA APK Gate: **AUTOMATED PASS**
- release QA APK verification/security/model-channel checks: **PASS**
- QA artifact ID: `12245362192`
- QA artifact digest: `sha256:07c976c4aa38a3064e6e7e6628bea5a53107c81f595c6b773bde6299f918aa34`
- QA artifact head: `a27ed998db56e8edd71c17ff50303285ae3453bf`

Real-device checkpoint supplied by the user on 2026-10-20: **REAL-DEVICE PASS**.

- formal VAD: `fsmn-vad-int8@1.0.0/1`
- VAD wall: `1026 ms`
- speech segments: `17`
- frozen ASR inputs: `17`
- Fun-ASR-Nano: READY, `17` calls / `0` blank, total RTF `0.5176`, peak PSS `1778348 KB`
- SenseVoice: READY, `17` calls / `1` blank, total RTF `0.0876`, peak PSS `583185 KB`
- Qwen3-ASR: READY, `17` calls / `1` blank, total RTF `0.5099`, peak PSS `1681547 KB`
- no candidate crashed; every candidate produced nonblank overall transcription from the exact same frozen PCM inputs
- B0 performance/PSS differences are signals only and are not a B5 conclusion
- B0 raw transcript differences are not an accuracy ranking because the run did not use a frozen reference transcript

B0 decision: **PASS.** All three candidates proceed to B1; no production ASR promotion/removal is authorized by B0.

### B1 Same-input Base Quality

The frozen first-pass corpus, scoring rules and human recording procedure are documented in:

`docs/STAGE_16B_GATE_B_B1.md`

B1 QA support is implemented for eight discriminatory profiles:

- `ZH_BASE`
- `ZH_LONG`
- `EN_BASE`
- `ZH_EN_MIXED`
- `NUMBERS_ITN_RAW`
- `PROPER_NOUNS`
- `BOUNDARY`
- `PAUSE`

Each B1 run keeps the B0 fairness contract: FSMN runs once, the resulting canonical `sampleIndex` boundaries are frozen, and the exact same PCM chunks are delivered to all three candidates. B1 records CER, EN WER where applicable, edit counts, blank calls, whole-profile blank state, head/tail deletion evidence, duplicate-line evidence and raw hypothesis text.

SenseVoice real-device B0 output exposed language metadata tags such as `<zh>`, `<yue>` and `<ja>`. B1 therefore removes only known SenseVoice language metadata (`<zh>`, `<en>`, `<ja>`, `<ko>`, `<yue>`) from the **scoring view** while retaining `rawText` unchanged. Regression tests lock that those tags do not inflate CER/WER and that metadata-only output remains blank.

B1 automated checkpoint:

- score/profile implementation: `ebe80d64a1a16851ebe7c78a198e19fa6a66eb5b`
- QA profile selection: `f0560ee7e503e1d3a475748278c5b1346c3a5793`
- initial scoring tests: `32f471f3799c8000c4310a729a5b3f74a04d00fc`
- visibility fix: `2ccda951e194b0f9eca9bdeb7cd4ee2c6eabf6cc`
- SenseVoice scoring normalization: `88dc69bcd038c2b846ab87c312c473c85c199188`
- regression-test checkpoint / QA artifact head: `45954e4b13ef6ab69697e53d44109a5fa0044f3f`
- Android PR CI #1959 / Fast PR Gate: **AUTOMATED PASS**
- unit tests + debug compile: **PASS**
- B1 scoring/fairness regression tests: **PASS**
- FSMN runtime boundary: **PASS**
- Android PR CI #1961 / production-like QA APK Gate: **AUTOMATED PASS**
- release QA APK verification/security/model-channel checks: **PASS**
- QA artifact ID: `12250801192`
- QA artifact digest: `sha256:e7916339c769d25e0bac4d381cb0ee6ac298975e25705c4b4c9cad100d7beca5`

B1 real-device reference corpus: **NOT RUN / NEXT**. No B1 shortlist or model ranking is authorized before the reference recordings are run and reviewed.

### Fun-ASR-Nano

- sherpa-onnx `v1.13.8` adapter path implemented;
- candidate package integrity workflow passed;
- production-like candidate QA APK build/security/native packaging passed;
- candidate manifest/model activation and real-device native transcription: **PASS in Gate B B0**;
- comparative transcription quality remains to be measured in B1/B2/B3.

Candidate model-channel reference:

- prerelease: `candidate-funasr-nano-r1`
- candidate manifestDigest: `a05fab6a9f6464762a38e1a023c8f96f83db2fbe24585a766a14e2279aa12063`
- official upstream package size: `841730611` bytes
- official package SHA-256: `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`
- required installed files total: `1010070962` bytes

### FSMN-VAD

- dedicated `engine:fsmn` runtime integrated;
- candidate download / integrity / isolated runtime validation / activation passed on real device;
- Gate A A1–A4 completed;
- promoted to production model catalog as `FSMN VAD INT8 1.0.0`.

### TEN-VAD

- sherpa-onnx TEN adapter and Gate A benchmark evidence retained;
- candidate / QA only;
- excluded from production model catalog;
- may be reevaluated in Stage 16C.1 after license/product gating.

## Product-state boundaries

- Local File VAD: `FSMN primary → Silero fallback`.
- Formal Gate B benchmark VAD: exact active `FSMN`; Silero fallback invalidates that benchmark run.
- Realtime VAD: Silero unchanged until Stage 16C.1.
- SenseVoice / Qwen3 product ASR selection remains unchanged until Gate B finishes.
- Fun-ASR-Nano remains candidate-only until Gate B decision.
- Room remains `v14`.
- Stage 16C remains `RESERVED / NOT STARTED`.
- PR #35 remains Draft and must not be merged without explicit user authorization.

## Next planned work

1. Run **B1 Same-input Base Quality** on real device using the eight frozen reference profiles.
2. Keep exact active FSMN and reuse identical frozen PCM chunks for all three candidates.
3. Capture full QA result evidence for CER / English WER where applicable, insertion/deletion/substitution, blank output, head/tail deletion, repetition/hallucination review and ASR call count.
4. Build the first discriminatory shortlist only after the B1 reference evidence is complete; do not make the final Product Matrix decision in B1.
5. Continue with B2 real-recording completeness, B3 punctuation/ITN/language/hallucination and B4 timeline compatibility for viable candidates.
6. Reserve B5/B6 performance/long-file work for viable finalists to avoid a meaningless Cartesian benchmark.
7. Present the complete Gate B evidence before any ASR production-model promotion/removal decision.
