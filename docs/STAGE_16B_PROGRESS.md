# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / B0 PASS / B1 PASS / B2 PASS / B3 REAL-DEVICE PASS / B4 REAL-DEVICE PASS / B5 REAL-DEVICE PASS / B6 NEXT**

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

All three candidates loaded and produced nonblank overall transcription without crash on the same exact FSMN-frozen PCM.

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

The formal common cap was changed to **25 seconds for all three candidates**. Retest on the same recording produced complete nonblank output. The third mixed-content recording also produced all-nonblank output for all three.

This is a verified current Android runtime/adapter safe comparison boundary, not a claim of an intrinsic upstream FunASR 30-second limit.

Docs:

- `docs/STAGE_16B_GATE_B_B2.md`;
- `docs/STAGE_16B_GATE_B_B2_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B2_30S_INPUT_FINDING.md`.

## B3 Punctuation / ITN / Language / Hallucination — REAL-DEVICE PASS

- native punctuation: Qwen3 strongest; FunASR partial; SenseVoice none under the no-external-punctuation contract;
- ITN: SenseVoice strongest compact digit formatting; FunASR behaviorally active; Qwen3 current integration exposes no ITN switch;
- language: monolingual AUTO/default paths exact; FunASR AUTO strongest controlled mixed-language result;
- short utterance: all three exact/nonblank;
- silence/noise: exact FSMN filters both before ASR;
- hotword A/B: FunASR `CER 0.1724 -> 0.0345`, coverage `2/3 -> 3/3`; Qwen3 `0.3103 -> 0.0690`, `0/3 -> 2/3`.

Language forcing is not a verified hard lock for FunASR or SenseVoice and should not be exposed as a guaranteed product behavior.

Docs:

- `docs/STAGE_16B_GATE_B_B3.md`;
- `docs/STAGE_16B_GATE_B_B3_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B3_LANGUAGE_REAL_DEVICE_REPORT.md`;
- `docs/STAGE_16B_GATE_B_B3_SHORT_UTTERANCE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_SILENCE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_NOISE_REAL_DEVICE.md`;
- `docs/STAGE_16B_GATE_B_B3_HOTWORD_REAL_DEVICE.md`.

## B4 Timestamp / Timeline Compatibility — REAL-DEVICE PASS

B4 separates canonical segment timing from token-level timing precision.

| Candidate / route | Token timing | Timed-segment coverage | Coordinate safety | Decision |
| --- | --- | ---: | --- | --- |
| FunASR native | none | `0/5` | canonical segment timeline retained | PASS / expected no native timing |
| FunASR alignment probe | aligned partial | `3/5` | `38/38` timed, `0` monotonic violations, `0` out-of-bounds | PASS / `PARTIAL_SAFE_REFUSAL` |
| SenseVoice native | native full | `4/4` nonblank | `34/34` timed, `0` violations, `0` out-of-bounds | PASS / FULL |
| Qwen3 native | none | `0/5` | canonical segment timeline retained | PASS / expected no native timing |
| Qwen3 current product alignment | aligned partial | `3/5` | `33/33` timed, `0` violations, `0` out-of-bounds | PASS / `PARTIAL_SAFE_REFUSAL` |

The initial QA interpretation was corrected after source audit; production `TimelineAlignment` behavior was not weakened or changed.

Docs:

- `docs/STAGE_16B_GATE_B_B4.md`;
- `docs/STAGE_16B_GATE_B_B4_REAL_DEVICE_REPORT.md`.

## B5 Isolated Cold/Warm Performance + Memory — REAL-DEVICE PASS

Formal common input:

- recording `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`;
- canonical SHA `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`;
- exact FSMN `1.0.0/1`;
- `threads=2`;
- `frozenInputs=10`;
- frozen speech `109.3420 s`;
- common 25-second input cap.

Final B5 harness uses a fresh lightweight candidate process instance and a read-only model-manager path; WorkManager / durable model-install runtime is excluded from the measured process. Each candidate process is terminated before the next model starts.

| Candidate | Cold ASR RTF | Warm ASR RTF | Cold total RTF | Warm total RTF | Cold peak PSS KB | Warm peak PSS KB | End-PSS signal |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| SenseVoice | **0.0876** | **0.0871** | **0.1124** | **0.1125** | 389108 | 713942 | ~385 MB |
| FunASR Nano | **0.6547** | **0.6300** | **0.7548** | **0.7313** | 1753708 | 1745334 | ~1.44 GB |
| Qwen3-ASR | **0.9767** | **0.7543** | **1.0599** | **0.8362** | 2342673 | 2330256 | ~2.29–2.31 GB |

All six passes completed with `blankCalls=0` and no crash/OOM.

B5 runtime ordering is unambiguous: **SenseVoice << FunASR < Qwen3** for both speed cost and memory pressure. Qwen3 cold total RTF is slightly above realtime (`1.0599`).

Docs:

- `docs/STAGE_16B_GATE_B_B5.md`;
- `docs/STAGE_16B_GATE_B_B5_REAL_DEVICE_REPORT.md`.

### B1–B5 role reduction

- **SenseVoice** remains the differentiated efficiency/balanced baseline: lowest runtime cost by a large margin, strongest compact ITN formatting, full native token timing. It is not eliminated, but it is not the high-quality-role finalist.
- **FunASR Nano** remains a high-quality-role finalist: strong mixed/proper-name signal, active ITN, strongest controlled hotword A/B, and materially lower cost than Qwen3.
- **Qwen3-ASR** remains a high-quality-role finalist because it owns the strongest controlled long-Mandarin result and strongest native punctuation despite the highest runtime/memory cost.

## B6 finalists

Run B6 only for the two models still competing for the **high-quality local-file role**:

1. **FunASR Nano INT8**;
2. **Qwen3-ASR 0.6B INT8**.

SenseVoice is retained in the Product Matrix as the efficiency/balanced baseline and does not require an expensive B6 run to justify that distinct role.

B6 contract: one representative ~30-minute real recording, exact FSMN, common verified 25-second ASR-input cap. No 60/120-minute extension is pre-required; extend only if the B6 result remains materially ambiguous.

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

1. **B6 — representative ~30-minute long-file endurance / completeness** for FunASR Nano and Qwen3-ASR only.
2. Compare long-file stability, blank-rate, end-to-end wall/RTF, memory behavior and output completeness on the same exact-FSMN frozen input.
3. Present the complete Gate B Product Matrix before any production model promotion/removal decision.
4. Do not start Stage 16C until Stage 16B is explicitly closed.