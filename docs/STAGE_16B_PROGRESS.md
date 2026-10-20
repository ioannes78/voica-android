# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / GATE B B0 REAL-DEVICE PASS / B1 REAL-DEVICE PASS / B2 REAL-DEVICE PASS / B3 AUTOMATED PASS / B3 REAL-DEVICE NEXT**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Requirements baseline: **Stage 16B revised requirements V5 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment and final fallback revision**

Development-plan baseline: **Stage 16B development plan V1 — confirmed by user, with subsequent explicit TEN-VAD Gate A amendment and final fallback revision**

## Frozen project identity

- versionCode: `92`
- versionName: `1.1.0-stage16a2`
- Room: `v14`
- Stage 16A.2 Annotation owner remains `Recording`
- durable primary annotation coordinate remains canonical/realtime `sampleIndex`
- `ioannes78/voice-card-android` remains fully isolated; no copy, migration, cherry-pick, mechanical translation or rewrite
- Stage 16B does not modify QS668/CB08 BLE protocol, Android Audio I/O ownership, playback ownership or Stage 16A.2 annotation ownership
- CI success never substitutes for real-device QA
- production model-channel changes require a separate explicit promotion gate and user authorization

## Gate A — VAD Matrix Gate — TECHNICAL PASS

Final Local File product policy:

`FSMN VAD INT8 primary -> Silero VAD INT8 fallback`

Realtime remains Silero until Stage 16C.1. TEN remains candidate / QA only.

Evidence:

- `docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`
- `docs/STAGE_16B_GATE_A_FINAL_DECISION.md`

Key frozen evidence:

- A1: FSMN led envelope consistency and VAD RTF
- A2 weighted CER: FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`
- A3: FSMN materially reduced fragmentation / diarization workload
- A4 30-minute: FSMN total RTF `0.128` vs Silero `0.187`; VAD wall `13.337 s` vs `131.736 s`
- 60/120-minute extension was explicitly waived by the user
- FSMN product version `1.0.0`; upstream revision remains provenance only
- Gate A-only QA product entry points remain removed

Model-channel promotion remains staged only in Draft `ioannes78/voica-model-channel#21`; do not merge without explicit authorization.

## Gate B — ASR Model Matrix

Candidates:

1. SenseVoice INT8
2. Fun-ASR-Nano INT8
3. Qwen3-ASR 0.6B INT8

Formal Gate B evidence requires exact active `fsmn-vad-int8`; Silero fallback invalidates a formal comparison. Model count is not predetermined.

### B0 Candidate Readiness — REAL-DEVICE PASS

Documentation: `docs/STAGE_16B_GATE_B_B0.md`

Contract:

- exact FSMN once on canonical PCM
- canonical `sampleIndex` boundaries frozen once
- identical frozen PCM reused by all ASRs
- no external punctuation or Qwen alignment
- no benchmark persistence to Room

Automated checkpoint:

- code `a27ed998db56e8edd71c17ff50303285ae3453bf`
- Fast PR Gate #1945: **PASS**
- QA APK Gate #1946: **PASS**
- artifact `12245362192`
- digest `sha256:07c976c4aa38a3064e6e7e6628bea5a53107c81f595c6b773bde6299f918aa34`

Real device:

- exact `fsmn-vad-int8@1.0.0/1`
- 17 speech segments / 17 frozen inputs
- FunASR 17 calls / 0 blank
- SenseVoice 17 calls / 1 blank
- Qwen3 17 calls / 1 blank
- all candidates loaded and produced nonblank overall transcription without crash

### B1 Same-input Base Quality — REAL-DEVICE PASS

Documentation:

- `docs/STAGE_16B_GATE_B_B1.md`
- `docs/STAGE_16B_GATE_B_B1_REAL_DEVICE_REPORT.md`

Automated checkpoint:

- code/artifact head `45954e4b13ef6ab69697e53d44109a5fa0044f3f`
- Fast PR Gate #1959: **PASS**
- QA APK Gate #1961: **PASS**
- artifact `12250801192`
- digest `sha256:e7916339c769d25e0bac4d381cb0ee6ac298975e25705c4b4c9cad100d7beca5`

Real-device summary:

| Profile | Fun-ASR-Nano | SenseVoice | Qwen3-ASR |
| --- | ---: | ---: | ---: |
| `ZH_BASE` | CER `0.0000` | CER `0.0000` | CER `0.0000` |
| `ZH_LONG` | CER `0.0345` | CER `0.0862` | CER `0.0000` |
| `EN_BASE` | CER/WER `0/0` | CER/WER `0/0` | CER/WER `0/0` |
| `ZH_EN_MIXED` | CER `0.0313` | CER `0.0938` | CER `0.0469` |
| `NUMBERS_ITN_RAW` | semantic correct + active digit ITN | CER `0.0000` | CER `0.0000` |
| `PROPER_NOUNS` | CER `0.0313` | CER `0.1250` | CER `0.0469` |
| `BOUNDARY` | CER `0`, no head/tail deletion | same | same |
| `PAUSE` | shared segmentation deletion; non-discriminatory | same | same |

Interpretation:

- Qwen3 strongest on long Mandarin
- FunASR strongest on mixed-language/proper-noun material and showed active ITN
- SenseVoice weaker on complex content but remains a lightweight contender pending isolated B5
- no candidate crashed, whole-profile blanked or showed deterministic repeat-output failure
- all three proceed to B2/B3/B4

### B2 Real Recording Quality / Completeness — REAL-DEVICE PASS

Documentation:

- `docs/STAGE_16B_GATE_B_B2.md`
- `docs/STAGE_16B_GATE_B_B2_REAL_DEVICE_REPORT.md`
- `docs/STAGE_16B_GATE_B_B2_30S_INPUT_FINDING.md`

Initial automation:

- code/artifact head `e8e5477c4a99b98e6f6e149c9027647652b46324`
- Fast PR Gate #1973: **PASS**
- QA APK Gate #1974: **PASS**
- artifact `12255162649`

Material compatibility finding:

- recording `4a76699d-e8bc-4e07-adf2-a0f63362ecb5`
- original 30-second B2 cap: FunASR blank on chunks `0,2,3`; all three failed FunASR chunks were exactly `30.00 s`; SenseVoice/Qwen3 nonblank on identical PCM
- B2 changed to a **common 25-second frozen-input cap for all three candidates**
- checkpoint `d00a3093adb3791b9a77866aa4b9148e9d9ea88c`
- Fast PR Gate #1980: **PASS**
- QA APK Gate #1981: **PASS**
- `30 s -> 25 s + 5 s` regression: **PASS**
- artifact `12260190295`, digest `sha256:6a21093443f0939fcc925558d74f300a08c7cd2c6de8c5cc28599aaba09ab639`
- same-recording 25-second retest: 5 FSMN segments -> 8 inputs; FunASR/SenseVoice/Qwen3 all `8/8` nonblank

Third mixed-content recording:

- recording `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`
- canonical `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`
- exact FSMN, 10 speech segments / 10 frozen inputs
- all three `10/10` nonblank, no crash or deterministic whole-sentence repeat/hallucination

B2 decision: **all three remain viable**. Current Android FunASR runtime/adapter must account for the verified 25-second safe boundary before any production promotion; this is not claimed as an intrinsic upstream model limit.

### B3 Punctuation / ITN / Language / Hallucination — AUTOMATED PASS / REAL-DEVICE NEXT

Documentation: `docs/STAGE_16B_GATE_B_B3.md`

B3 is a separate QA-only runner / launcher and preserves:

- exact FSMN once
- common 25-second frozen-input cap
- canonical `sampleIndex` ownership
- no external punctuation
- no Qwen timeline alignment
- no Room benchmark persistence
- maximum 60-second controlled challenge recordings

Profiles:

- `PUNCTUATION_ZH`
- `ITN_COMPLEX`
- `LANG_ZH`
- `LANG_EN`
- `LANG_MIXED`
- `SHORT_UTTERANCE`
- `SILENCE`
- `NOISE`

Capability-specific matrix:

- native punctuation: one default-native pass per model
- ITN: FunASR ON/OFF; SenseVoice ON/OFF; Qwen3 explicitly default/no toggle
- language: FunASR auto / `中文` / `英文`; SenseVoice auto / `zh` / `en`; Qwen3 explicitly default/no forcing control
- silence/noise: if FSMN filters all speech, report pipeline-level no-speech evidence; if FSMN emits a chunk, run identical input through all default ASRs and inspect nonblank hallucination
- unsupported controls are labeled unsupported/default rather than emulated

Automated checkpoints:

- B3 code checkpoint `39df8817586225d5f30e162b8d342ac1af74bec1`
- Fast PR Gate #1995: **PASS**
- B3 unit tests + Debug compile: **PASS**
- runtime-native language-value regression: **PASS**
- ITN semantic-equivalence regression: **PASS**
- punctuation evidence regression: **PASS**
- FSMN runtime boundary: **PASS**
- QA artifact head `382179fc92c619dac218e053f93e4e6770b1862a`
- QA APK Gate #1997: **PASS**
- APK verification/security/model-channel checks: **PASS**
- artifact `12262492806`
- artifact digest `sha256:76f5a4e9518222af675315d0dea23dc2aec0ba84cdc038627f9787c8a261d7c9`
- extracted APK SHA-256 `ec46550bf3c2a8945de87ec6d2fe4ee5675eef0e69197a93651af18412637a6c`

Real-device B3 is **NEXT / NOT YET PASSED**. Start with `PUNCTUATION_ZH`, review evidence, then expand the controlled matrix. A small hotword/context-bias A/B follow-up remains required for FunASR/Qwen3 if they remain viable; it is not enabled in base quality runs.

### Fun-ASR-Nano candidate state

- sherpa-onnx `v1.13.8` adapter implemented
- candidate activation/native transcription: PASS in B0
- base quality: PASS in B1
- real-recording completeness: PASS in B2 with verified common 25-second safe comparison cap
- remains candidate-only until full Product Matrix decision
- prerelease `candidate-funasr-nano-r1`
- manifestDigest `a05fab6a9f6464762a38e1a023c8f96f83db2fbe24585a766a14e2279aa12063`
- upstream package size `841730611` bytes
- upstream SHA-256 `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`
- required installed files total `1010070962` bytes

## Product-state boundaries

- Local File product VAD: `FSMN primary -> Silero fallback`
- Formal Gate B VAD: exact active FSMN; Silero fallback invalidates benchmark evidence
- Realtime VAD: Silero unchanged until Stage 16C.1
- SenseVoice/Qwen3 production ASR selection unchanged until Gate B finishes
- Fun-ASR-Nano remains candidate-only
- Room remains v14
- no QS668/CB08, Android Audio I/O, playback, Stage 16A.2 annotation or canonical `sampleIndex` ownership changes
- Stage 16C remains `RESERVED / NOT STARTED`
- Android PR #35 and model-channel PR #21 remain Draft and must not be merged without explicit user authorization

## Next planned work

1. Run **B3 real-device controlled challenges**, starting with `PUNCTUATION_ZH`.
2. Finish B3 ITN/language/short/no-speech checks and the small hotword/context A/B follow-up where supported.
3. Run **B4 Timestamp / Timeline Compatibility**, preserving canonical `sampleIndex` ownership and distinguishing native token timing from extra alignment paths.
4. Use B2/B3/B4 to clarify viable roles before isolated **B5 performance/memory** and **B6 representative long-file** tests.
5. Present the complete Gate B Product Matrix before any ASR production promotion/removal decision.
