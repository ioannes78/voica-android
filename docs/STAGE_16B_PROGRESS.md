# Stage 16B — Local File ASR V2 Progress

Status: **GATE A TECHNICAL PASS / GATE B REAL-DEVICE PASS / FINAL PRODUCT MATRIX AUTHORIZED / STAGED, UNMERGED**

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

Final Local File policy: `FSMN VAD INT8 primary -> Silero VAD INT8 fallback`.
Realtime remains Silero until Stage 16C.1. TEN remains candidate/QA only.

Key evidence: A2 weighted CER FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`; A4 30-minute total RTF FSMN `0.128`, Silero `0.187`; A4 VAD wall FSMN `13.337 s`, Silero `131.736 s`. The 60/120-minute extension was explicitly waived. FSMN product version is `1.0.0`.

Model-channel promotion remains staged only in Draft `ioannes78/voica-model-channel#21`; do not merge without explicit authorization.

## Gate B candidates

1. SenseVoice INT8;
2. Fun-ASR-Nano INT8;
3. Qwen3-ASR 0.6B INT8.

Formal Gate B evidence requires exact active `fsmn-vad-int8`; Silero fallback invalidates a formal comparison.

## B0 Candidate Readiness — REAL-DEVICE PASS

All three candidates loaded and produced nonblank overall transcription without crash on the same exact FSMN-frozen PCM.

## B1 Same-input Base Quality — REAL-DEVICE PASS

Qwen3 owns the strongest controlled long-Mandarin result; FunASR owns the strongest mixed/proper-name tendency and active ITN signal; SenseVoice is exact on simple profiles but weaker on complex mixed/proper-name content. All three passed boundary completeness without crash or deterministic repeat failure.

## B2 Real Recording Quality / Completeness — REAL-DEVICE PASS

The former exact 30-second cap exposed FunASR whole-chunk blanks. The formal common cap was changed to **25 seconds for all candidates**. Same-recording retest removed the FunASR blanks, and the third mixed-content recording produced all-nonblank output for all three. This is a verified current Android runtime/adapter safe boundary, not a claim of an intrinsic upstream model limit.

## B3 Punctuation / ITN / Language / Hallucination — REAL-DEVICE PASS

- native punctuation: Qwen3 strongest, FunASR partial, SenseVoice none under the no-external-punctuation contract;
- ITN: SenseVoice strongest compact digit formatting, FunASR behaviorally active, Qwen3 current integration exposes no ITN switch;
- language: monolingual AUTO/default paths exact; FunASR AUTO strongest controlled mixed-language result;
- short utterance: all three exact/nonblank;
- silence/noise: exact FSMN filters both before ASR;
- hotword A/B: FunASR `CER 0.1724 -> 0.0345`, coverage `2/3 -> 3/3`; Qwen3 `0.3103 -> 0.0690`, `0/3 -> 2/3`.

Language forcing is not a verified hard lock for FunASR or SenseVoice and should not be exposed as a guaranteed product behavior.

## B4 Timestamp / Timeline Compatibility — REAL-DEVICE PASS

B4 separates canonical segment timing from token-level timing precision.

| Candidate / route | Token timing | Timed-segment coverage | Decision |
| --- | --- | ---: | --- |
| FunASR native | none | `0/5` | PASS / expected no native timing |
| FunASR alignment probe | aligned partial | `3/5` | PASS / `PARTIAL_SAFE_REFUSAL` |
| SenseVoice native | native full | `4/4` nonblank | PASS / FULL |
| Qwen3 native | none | `0/5` | PASS / expected no native timing |
| Qwen3 current product alignment | aligned partial | `3/5` | PASS / `PARTIAL_SAFE_REFUSAL` |

All emitted timed tokens were monotonic and in bounds. Production `TimelineAlignment` behavior was not weakened or changed.

## B5 Isolated Cold/Warm Performance + Memory — REAL-DEVICE PASS

Formal common input: recording `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`, canonical SHA `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`, exact FSMN `1.0.0/1`, `threads=2`, `frozenInputs=10`, frozen speech `109.3420 s`, common 25-second ASR-input cap.

| Candidate | Cold ASR RTF | Warm ASR RTF | Cold total RTF | Warm total RTF | Cold peak PSS KB | Warm peak PSS KB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| SenseVoice | **0.0876** | **0.0871** | **0.1124** | **0.1125** | 389108 | 713942 |
| FunASR Nano | **0.6547** | **0.6300** | **0.7548** | **0.7313** | 1753708 | 1745334 |
| Qwen3-ASR | **0.9767** | **0.7543** | **1.0599** | **0.8362** | 2342673 | 2330256 |

All six passes completed with `blankCalls=0` and no crash/OOM. Runtime/memory ordering is unambiguous: **SenseVoice << FunASR < Qwen3**. SenseVoice remains the efficiency/balanced baseline; FunASR and Qwen3 remain the two high-quality-role finalists.

## B6 Finalist Long-file Endurance — REAL-DEVICE PASS

Both finalists completed the same formal B6 input with exact FSMN `1.0.0/1`, `threads=2`, `54` frozen inputs, `597.7900 s` frozen speech and `blankCalls=0`.

| Candidate | ASR RTF | Total RTF | First-quarter | Last-quarter | Thermal ratio | Peak PSS KB | End PSS KB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **FunASR Nano** | **0.6323** | **0.6533** | **0.5836** | **0.6304** | 1.0801 | **1728929** | **1543305** |
| Qwen3-ASR | 0.7120 | 0.7256 | 0.7407 | 0.6546 | **0.8838** | 2396068 | 2390460 |

Both reported thermal `NONE/NONE/NONE`, no crash/OOM and 54/54 nonblank calls. The accepted ~10.5-minute run leaves no material endurance ambiguity, so the preferred ~30-minute extension is waived.

Final authorized product roles:

- **SenseVoice INT8 = balanced / efficiency**;
- **FunASR Nano INT8 = high quality**;
- **Qwen3-ASR 0.6B INT8 = removed from the production product matrix and replaced by FunASR Nano**.

Historical Qwen runtime/evidence may remain for prior-transcript compatibility and QA traceability; it is not a production Local File ASR choice.

Doc: `docs/STAGE_16B_GATE_B_B6.md`.

## Product-state boundaries

- Local File VAD: `FSMN primary -> Silero fallback`;
- formal Gate B VAD: exact active FSMN;
- realtime VAD: Silero unchanged until Stage 16C.1;
- Local File balanced/efficiency ASR: **SenseVoice INT8**;
- Local File high-quality ASR: **FunASR Nano INT8**;
- Qwen3-ASR removed from the production product matrix;
- model-channel staged manifest: v9, digest `a49da9fdf2dabf2408ba2bb14cf6b5c25c35563052cc6307db8f8095caac0b45`, commit `eeb59214244f392f3e2eecdb90016efbe3a95401`;
- Room remains v14;
- Android PR #35 remains Draft/unmerged;
- model-channel PR #21 remains Draft/unmerged;
- Stage 16C remains `RESERVED / NOT STARTED`.

## Next planned work

1. Run final Android CI against the pinned v9 model-channel matrix.
2. Verify the production-like Local File UI exposes SenseVoice + FunASR Nano and no Qwen3 production choice.
3. Freeze Stage 16B handoff after CI/QA evidence is green.
4. Do not merge PR #35 or model-channel PR #21, and do not start Stage 16C, without explicit user authorization.
