# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / GATE B NEXT**

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

Status: **NEXT**

Candidates:

- SenseVoice INT8
- Fun-ASR-Nano INT8
- Qwen3-ASR 0.6B INT8

All final ASR comparisons use the Gate A production VAD baseline unless a model-specific exception is separately justified by strong evidence and explicitly approved.

The gate must decide the long-term product matrix; model count is not predetermined.

## Candidate runtime status relevant to Gate B

### Fun-ASR-Nano

- sherpa-onnx `v1.13.8` adapter path implemented;
- candidate package integrity workflow passed;
- production-like candidate QA APK build/security/native packaging passed;
- candidate manifest/model activation and transcription quality still require the Gate B real-device matrix.

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
- Realtime VAD: Silero unchanged until Stage 16C.1.
- SenseVoice / Qwen3 product ASR selection remains unchanged until Gate B finishes.
- Fun-ASR-Nano remains candidate-only until Gate B decision.
- Room remains `v14`.
- Stage 16C remains `RESERVED / NOT STARTED`.
- PR #35 remains Draft and must not be merged without explicit user authorization.

## Next planned work

1. Finish automated verification of the revised `FSMN → Silero` fallback policy and FSMN-only production manifest pin.
2. Start Stage 16B Gate B benchmark harness/QA flow.
3. Compare SenseVoice vs Fun-ASR-Nano vs Qwen3-ASR 0.6B on the same canonical inputs and frozen Gate A VAD policy.
4. Present Gate B evidence before any ASR production-model promotion/removal decision.
