# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / GATE B B0 REAL-DEVICE PASS / B1 REAL-DEVICE PASS / B2 NEXT**

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

Gate A compared Silero, FSMN and TEN. Final production decision:

`FSMN VAD INT8 (primary) → Silero VAD INT8 (fallback)`

TEN remains candidate / QA only. Evidence and final policy are frozen in:

- `docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`
- `docs/STAGE_16B_GATE_A_FINAL_DECISION.md`

Gate A summary:

- A1: FSMN led envelope consistency and VAD RTF.
- A2 weighted CER: FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`.
- A3: FSMN materially reduced fragmentation and diarization workload versus Silero.
- A4 30-minute: FSMN total RTF `0.128` versus Silero `0.187`; VAD wall `13.337 s` versus `131.736 s`.
- 60/120-minute extension was explicitly waived by the user.
- FSMN product version is `1.0.0`; upstream revision stays provenance only.
- Gate A-only `A4 QA` / `VAD QA` product entry points remain removed.

Current model-channel promotion staging remains Draft in `ioannes78/voica-model-channel#21`; it must not be merged without explicit user authorization.

## Gate B — ASR Model Matrix Gate

Candidates:

- SenseVoice INT8
- Fun-ASR-Nano INT8
- Qwen3-ASR 0.6B INT8

All formal Gate B comparisons require exact active `fsmn-vad-int8`. A run that resolves Silero fallback is invalid Gate B evidence.

The gate decides a long-term product matrix; model count is not predetermined.

### B0 Candidate Readiness — REAL-DEVICE PASS

Documentation: `docs/STAGE_16B_GATE_B_B0.md`

Contract:

1. exact FSMN runs once on canonical PCM;
2. canonical `sampleIndex` speech boundaries are frozen;
3. exact same frozen PCM chunks are reused across all ASRs;
4. external punctuation and Qwen alignment are excluded;
5. benchmark transcript state is not persisted to Room.

Automated checkpoint:

- code checkpoint `a27ed998db56e8edd71c17ff50303285ae3453bf`
- Android PR CI #1945 Fast PR Gate: **PASS**
- Android PR CI #1946 production-like QA APK Gate: **PASS**
- QA artifact `12245362192`
- digest `sha256:07c976c4aa38a3064e6e7e6628bea5a53107c81f595c6b773bde6299f918aa34`

Real-device checkpoint supplied by the user on 2026-10-20: **REAL-DEVICE PASS**.

- `fsmn-vad-int8@1.0.0/1`
- 17 speech segments / 17 frozen ASR inputs
- Fun-ASR-Nano READY: 17 calls / 0 blank
- SenseVoice READY: 17 calls / 1 blank
- Qwen3-ASR READY: 17 calls / 1 blank
- no candidate crashed; every candidate produced nonblank overall transcription

B0 performance/PSS differences are signals only and are not a B5 conclusion.

### B1 Same-input Base Quality — REAL-DEVICE PASS

Primary documentation:

- `docs/STAGE_16B_GATE_B_B1.md`
- `docs/STAGE_16B_GATE_B_B1_REAL_DEVICE_REPORT.md`

B1 automated checkpoint:

- QA artifact head `45954e4b13ef6ab69697e53d44109a5fa0044f3f`
- Android PR CI #1959 Fast PR Gate: **PASS**
- B1 scoring/fairness tests: **PASS**
- FSMN runtime boundary: **PASS**
- Android PR CI #1961 production-like QA APK Gate: **PASS**
- QA artifact `12250801192`
- digest `sha256:e7916339c769d25e0bac4d381cb0ee6ac298975e25705c4b4c9cad100d7beca5`

Real-device eight-profile corpus completed on 2026-10-20: **REAL-DEVICE PASS**.

| Profile | Fun-ASR-Nano | SenseVoice | Qwen3-ASR |
| --- | ---: | ---: | ---: |
| `ZH_BASE` | CER `0.0000` | CER `0.0000` | CER `0.0000` |
| `ZH_LONG` | CER `0.0345` | CER `0.0862` | CER `0.0000` |
| `EN_BASE` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` |
| `ZH_EN_MIXED` | CER `0.0313` | CER `0.0938` | CER `0.0469` |
| `NUMBERS_ITN_RAW` | raw CER `0.6000`, semantic content correct with ITN | CER `0.0000` | CER `0.0000` |
| `PROPER_NOUNS` | CER `0.0313` | CER `0.1250` | CER `0.0469` |
| `BOUNDARY` | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false |
| `PAUSE` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` |

B1 interpretation:

- Qwen3 is strongest on long Mandarin.
- FunASR is strongest on code-switch/proper-noun material and demonstrated active ITN behavior.
- SenseVoice is exact on simpler/base tasks but materially weaker on long Mandarin, mixed-language and proper nouns.
- `NUMBERS_ITN_RAW` is not ranked by raw CER alone because FunASR converts spoken numbers/date/time/money/percentage to digit-form while preserving meaning; B3 owns formal ITN evaluation.
- `PAUSE` is non-discriminatory ASR evidence. It produced 3 FSMN segments / 3 frozen ASR inputs and identical `del=9` for all three candidates, corresponding to `稍微停一下` + `再停一下`; the run is retained as segmentation/continuity evidence rather than model ranking.
- no candidate crashed, produced a whole-profile blank, or showed deterministic repeat-output failure.
- all three passed the BOUNDARY start/end completeness probe.

B1 first shortlist decision: **all three proceed to B2/B3/B4**. FunASR and Qwen3 are the stronger complex-content quality contenders; SenseVoice remains because its lighter/faster runtime may support a differentiated production role and requires fair isolated B5 evaluation.

B1 PSS/RTF/open-time numbers are signals only because models ran sequentially in the same process. B5 must use isolated cold/warm measurements before any resource ranking.

### Fun-ASR-Nano

- sherpa-onnx `v1.13.8` adapter path implemented;
- candidate package integrity and production-like packaging checks passed;
- candidate activation / native transcription: **PASS in B0**;
- comparative base quality: **PASS in B1**;
- remains candidate-only until the full Gate B Product Matrix decision.

Candidate reference:

- prerelease `candidate-funasr-nano-r1`
- manifestDigest `a05fab6a9f6464762a38e1a023c8f96f83db2fbe24585a766a14e2279aa12063`
- upstream package size `841730611` bytes
- upstream SHA-256 `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`
- required installed files total `1010070962` bytes

## Product-state boundaries

- Local File VAD: `FSMN primary → Silero fallback`.
- Formal Gate B VAD: exact active FSMN; Silero fallback invalidates the benchmark run.
- Realtime VAD: Silero unchanged until Stage 16C.1.
- SenseVoice / Qwen3 production ASR selection remains unchanged until Gate B finishes.
- Fun-ASR-Nano remains candidate-only until Gate B decision.
- Room remains `v14`.
- no QS668/CB08, Android Audio I/O, playback, Stage 16A.2 annotation or canonical `sampleIndex` ownership changes.
- Stage 16C remains `RESERVED / NOT STARTED`.
- PR #35 and model-channel PR #21 remain Draft and must not be merged without explicit user authorization.

## Next planned work

1. **B2 Real Recording Quality / Completeness** — use representative natural Voica recordings; focus on sentence loss, head/tail/mid-sentence omissions, repetitions and hallucinations while preserving FSMN boundaries.
2. **B3 Punctuation / ITN / Language / Hallucination** — explicitly separate native punctuation, ITN-equivalent semantics, language forcing/detection and silence/noise hallucination behavior.
3. **B4 Timestamp / Timeline Compatibility** — preserve canonical `sampleIndex` ownership; distinguish native token timing from extra alignment paths.
4. Use B2/B3/B4 to clarify viable product roles before isolated B5 performance/memory and B6 representative long-file tests.
5. Present the complete Gate B evidence before any ASR production promotion/removal decision.
