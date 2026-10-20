# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / B0 PASS / B1 PASS / B2 PASS / B3 REAL-DEVICE PASS / B4 NEXT**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Room: `v14`

## Frozen project boundaries

- `ioannes78/voice-card-android` remains isolated; no copy, migration, cherry-pick, mechanical translation or rewrite;
- Stage 16A.2 annotation ownership remains `Recording`;
- canonical/realtime `sampleIndex` remains the durable primary coordinate;
- no QS668/CB08 protocol, Android Audio I/O, playback or Stage 16A.2 ownership changes;
- CI success never substitutes for real-device QA;
- production model-channel changes require explicit promotion authorization;
- Stage 16C remains `RESERVED / NOT STARTED`.

## Gate A — VAD Matrix — TECHNICAL PASS

Final Local File policy:

`FSMN VAD INT8 primary -> Silero VAD INT8 fallback`

Realtime remains Silero until Stage 16C.1. TEN remains candidate/QA only.

Key evidence:

- A2 weighted CER: FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`;
- A4 30-minute total RTF: FSMN `0.128`, Silero `0.187`;
- A4 VAD wall: FSMN `13.337 s`, Silero `131.736 s`;
- 60/120-minute extension explicitly waived;
- FSMN product version `1.0.0`.

Docs:

- `docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`;
- `docs/STAGE_16B_GATE_A_FINAL_DECISION.md`.

Model-channel promotion remains staged only in Draft `ioannes78/voica-model-channel#21`; do not merge without explicit authorization.

## Gate B candidates

1. SenseVoice INT8;
2. Fun-ASR-Nano INT8;
3. Qwen3-ASR 0.6B INT8.

Formal Gate B evidence requires exact active `fsmn-vad-int8`; Silero fallback invalidates a formal comparison.

## B0 Candidate Readiness — REAL-DEVICE PASS

All three candidates loaded and produced nonblank overall transcription without crash on the same exact FSMN-frozen PCM. FunASR had `17/17` nonblank calls; SenseVoice and Qwen3 each had one blank call among 17 but nonblank overall output.

Doc: `docs/STAGE_16B_GATE_B_B0.md`.

## B1 Same-input Base Quality — REAL-DEVICE PASS

Key signals:

- Qwen3 strongest on long Mandarin (`ZH_LONG CER=0.0000`);
- FunASR strongest tendency on mixed-language / proper-name profiles and demonstrated active ITN;
- SenseVoice exact on simple profiles but weaker on complex mixed/proper-name content;
- all three passed boundary completeness;
- no candidate crashed, whole-profile blanked or showed deterministic repeat-output failure.

Docs:

- `docs/STAGE_16B_GATE_B_B1.md`;
- `docs/STAGE_16B_GATE_B_B1_REAL_DEVICE_REPORT.md`.

## B2 Real Recording Quality / Completeness — REAL-DEVICE PASS

A material adapter/chunking compatibility issue was found at the former exact 30-second cap: FunASR blanked on multiple exact `30.00 s` chunks while SenseVoice/Qwen3 were nonblank on the same PCM.

The formal common cap was changed to **25 seconds for all three candidates**. Retest on the same recording produced `8/8` nonblank for every candidate. A third mixed-content recording produced `10/10` nonblank for all three with no crash or deterministic whole-sentence repeat/hallucination.

This is a verified current Android runtime/adapter safe comparison boundary, not a claim of an intrinsic upstream FunASR 30-second limit.

Docs:

- `docs/STAGE_16B_GATE_B_B2.md`;
- `docs/STAGE_16B_GATE_B_B2_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B2_30S_INPUT_FINDING.md`.

## B3 Punctuation / ITN / Language / Hallucination — REAL-DEVICE PASS

B3 preserves exact FSMN-once, canonical frozen `sampleIndex`, common 25-second inputs, no external punctuation, no Qwen timeline alignment and no Room benchmark persistence.

### Native punctuation

All three preserve literal content on the punctuation profile (`CER=0.0000`). Native punctuation ordering is Qwen3 strongest, FunASR partial, SenseVoice none under the no-external-punctuation contract.

### ITN

- SenseVoice `ITN_ON`: strongest compact digit formatting on the controlled profile;
- FunASR `ITN_ON`: semantic coverage `1.00`, behaviorally active but partial category normalization;
- Qwen3: current integration exposes no ITN switch.

### Language behavior

Monolingual default/AUTO paths are exact on ZH and EN. On the controlled mixed-language profile, FunASR AUTO has the best literal score (`CER=0.2703`) versus Qwen3 default `0.4324` and SenseVoice AUTO `0.4865`.

Language forcing is not a verified hard lock for FunASR or SenseVoice and can degrade mismatched/mixed speech. Normal product behavior should remain AUTO/default; any forcing UI should be advanced/experimental hint semantics only.

### Short utterance

All three produce nonblank semantically exact `可以。` output with `contentCer=0.0000`.

### Silence / noise

Both controlled no-speech profiles are rejected by exact FSMN before ASR:

- `speechSegments=0`;
- `frozenAsrInputs=0`;
- `vadFilteredNoSpeech=true`;
- `pipelineHallucinationRisk=FILTERED_BEFORE_ASR`.

This is pipeline-level hallucination-protection evidence, not intrinsic no-VAD ASR ranking.

### Hotword / context-bias A/B

Reference: `今天测试 Qwen3、OpenAI 和 GitHub 的语音识别效果。`

Configured hotwords: `Qwen3,OpenAI,GitHub`.

Same exact FSMN-frozen PCM:

| Candidate | OFF CER | ON CER | OFF exact coverage | ON exact coverage |
| --- | ---: | ---: | ---: | ---: |
| FunASR Nano | `0.1724` | `0.0345` | `2/3` | `3/3` |
| Qwen3-ASR | `0.3103` | `0.0690` | `0/3` | `2/3` |

Both supported runtimes show material same-input improvement. SenseVoice is excluded because the current integration exposes no equivalent hotword control.

Hotword QA checkpoint:

- code head `7e22e2b184c34092c9a6082cf32925600aac8e63`;
- Fast PR Gate #2012: **PASS**;
- QA APK Gate #2013: **PASS**;
- artifact `12276585259`;
- artifact digest `sha256:bcdbc66828ef138237ec8f6e3e00a8b1bc8ca77109b7801d179d948cef4b26b2`;
- extracted APK SHA-256 `1417ae43a2f091c823aa4299d595dafb322c824b4554db00047e8382200ca635`.

B3 docs:

- `docs/STAGE_16B_GATE_B_B3.md`;
- `docs/STAGE_16B_GATE_B_B3_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B3_LANGUAGE_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B3_SHORT_UTTERANCE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_SILENCE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_NOISE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_HOTWORD_REAL_DEVICE.md`.

B3 decision: **REAL-DEVICE PASS**. No production ASR promotion/removal is authorized yet.

## Current candidate role signals before B4/B5

- **Qwen3**: strongest long-Mandarin evidence and native punctuation;
- **FunASR**: strongest mixed/proper-name tendency so far, active ITN and strongest supported hotword A/B;
- **SenseVoice**: strongest compact ITN formatting and lower runtime-cost signals, but weaker complex mixed/proper-name accuracy and no equivalent hotword control in current integration.

These are provisional role signals, not the final Product Matrix. B5 owns isolated performance/memory ranking.

## Product-state boundaries

- Local File VAD: `FSMN primary -> Silero fallback`;
- formal Gate B VAD: exact active FSMN;
- realtime VAD: Silero unchanged until Stage 16C.1;
- SenseVoice/Qwen3 production ASR selection unchanged;
- Fun-ASR-Nano remains candidate-only;
- Room remains v14;
- Android PR #35 remains Draft/unmerged;
- model-channel PR #21 remains Draft/unmerged;
- no ASR production promotion/removal is authorized yet.

## Next planned work

1. **B4 — Timestamp / Timeline Compatibility**: distinguish native token timing from extra alignment paths and preserve canonical `sampleIndex` ownership.
2. Use B2/B3/B4 evidence to decide which candidates/roles proceed into isolated **B5 performance + memory** testing.
3. Run **B6 representative long-file** testing only on finalists where needed.
4. Present the complete Gate B Product Matrix before any production model promotion/removal decision.
