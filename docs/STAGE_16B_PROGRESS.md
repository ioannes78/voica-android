# Stage 16B — Local File ASR V2 Progress

Status: **IN PROGRESS — GATE A TECHNICAL PASS / B0 PASS / B1 PASS / B2 PASS / B3 REAL-DEVICE PASS / B4 REAL-DEVICE PASS / B5 REAL-DEVICE PASS / B6 AUTOMATED PASS / B6 REAL-DEVICE NEXT**

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

## B6 Finalist Long-file Endurance — AUTOMATED PASS / REAL-DEVICE NEXT

Finalists only:

1. **FunASR Nano INT8**;
2. **Qwen3-ASR 0.6B INT8**.

B6 freezes exact FSMN once and reuses the same canonical 25-second inputs across both finalist processes. Each finalist runs exactly once in a fresh lightweight `:model_validator` process using the read-only model-manager path. The runner streams PCM boundary-by-boundary and records engine-open time, ASR/total RTF, first-vs-last-quarter RTF, thermal slowdown ratio, start/peak/end PSS, Android thermal status, calls/blanks and normalized output size.

Preferred formal input is one representative **~30-minute** real recording; the QA harness accepts **9–45 minutes** so an existing ~10-minute mixed recording may be used as an initial endurance pass. If a shorter accepted run leaves the finalists materially ambiguous, repeat formally on ~30 minutes before the Product Matrix decision. No 60/120-minute extension is pre-required.

Automated checkpoint:

- implementation head `d5dac92390544202788b8c8a2c420a3674079a6b`;
- Android PR CI #2072 Fast PR Gate: **PASS**;
- B6 unit tests / Debug compile: PASS;
- FSMN runtime boundary: PASS;
- Production Gate: skipped as expected.

QA launcher cleanup is also implemented: B0/B3/B4/B5 phase activities no longer expose separate desktop launcher icons in QA/releaseQa. One **`Voica Stage 16B QA`** hub is the only Stage 16B QA launcher; production release manifests remain unaffected.

Doc: `docs/STAGE_16B_GATE_B_B6.md`.

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

1. Generate the production-like B6 `releaseQa` APK.
2. Run the same long-file B6 plan once on FunASR Nano and once on Qwen3-ASR.
3. Present the complete Gate B Product Matrix and a production-role recommendation.
4. Do not merge/promote/remove models or start Stage 16C until explicitly authorized.
