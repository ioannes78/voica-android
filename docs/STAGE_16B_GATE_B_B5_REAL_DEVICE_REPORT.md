# Stage 16B Gate B — B5 Real-device Performance Report

Status: **REAL-DEVICE PASS**

Date: 2026-10-21

## Common formal input

All three candidates used the same prepared B5 plan:

- recording: `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`;
- canonical SHA-256: `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `1349 ms`;
- threads: `2`;
- frozen inputs: `10`;
- frozen speech duration: `109.3420 s`;
- common max ASR input: `25 s`;
- no external punctuation, timeline alignment, Room writes or diarization inside measured ASR time.

Each candidate ran in a fresh lightweight process instance. Candidate runtime uses a read-only model-manager path so WorkManager / durable model-install runtime is not initialized inside the performance process. The candidate process is terminated before the next model starts.

## Formal results

| Candidate | Pass | Engine open ms | ASR wall ms | ASR RTF | Total wall ms | Total RTF | PSS start/peak/end KB | Calls / blank |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| SenseVoice | COLD | 2707 | 9578 | **0.0876** | 12285 | **0.1124** | 50633 / 389108 / 385799 | 10 / 0 |
| SenseVoice | WARM | 2779 | 9520 | **0.0871** | 12299 | **0.1125** | 385755 / 713942 / 385193 | 10 / 0 |
| FunASR Nano | COLD | 10942 | 71587 | **0.6547** | 82529 | **0.7548** | 50970 / 1753708 / 1435369 | 10 / 0 |
| FunASR Nano | WARM | 11078 | 68889 | **0.6300** | 79967 | **0.7313** | 441507 / 1745334 / 1445370 | 10 / 0 |
| Qwen3-ASR | COLD | 9104 | 106789 | **0.9767** | 115893 | **1.0599** | 62938 / 2342673 / 2290038 | 10 / 0 |
| Qwen3-ASR | WARM | 8955 | 82473 | **0.7543** | 91428 | **0.8362** | 1411650 / 2330256 / 2308684 | 10 / 0 |

All six measured passes completed with `blankCalls=0` and no crash/OOM.

## Runtime ranking

### Speed

1. **SenseVoice** — by a very large margin; ASR RTF stays around `0.087` cold/warm.
2. **FunASR Nano** — `0.655 -> 0.630`; clearly faster than Qwen3 and comfortably below realtime on this device.
3. **Qwen3-ASR** — `0.977 -> 0.754`; cold ASR is only just below realtime and cold total RTF is `1.0599`, so first-use total processing is slightly slower than realtime.

### Memory

1. **SenseVoice** — cold peak about `389 MB` PSS; warm transient peak about `714 MB`, returning to about `385 MB` end PSS.
2. **FunASR Nano** — peak about `1.75 GB`; end PSS remains about `1.44 GB`.
3. **Qwen3-ASR** — peak about `2.33 GB`; end PSS remains about `2.29–2.31 GB`.

B5 confirms that Qwen3 has the highest Android memory pressure and the least cold-start realtime margin of the three. FunASR is materially lighter/faster than Qwen3, but still much heavier than SenseVoice.

## B1–B5 role reduction before B6

B5 is not a universal-winner decision. Combining B1–B5:

- **SenseVoice** retains a clearly differentiated efficiency role: dramatically lowest runtime cost, strongest compact ITN formatting, and full native token timing. Its weaker complex mixed/proper-name quality means it is not the high-quality-role finalist, but B5 does not remove it from the Product Matrix.
- **FunASR Nano** remains a high-quality-role finalist because it has strong mixed/proper-name behavior, active ITN, the strongest controlled hotword A/B, and materially lower runtime/memory cost than Qwen3. Current Android integration still lacks native token timing and requires the verified 25-second input cap.
- **Qwen3-ASR** remains a high-quality-role finalist because it owns the strongest controlled long-Mandarin evidence and strongest native punctuation, despite being the heaviest/slower candidate.

### B6 reduction

Run B6 only for the two candidates that still compete for the **high-quality** role:

1. **FunASR Nano INT8**;
2. **Qwen3-ASR 0.6B INT8**.

SenseVoice is **not eliminated**. It is retained as the efficiency/balanced baseline and does not need an expensive B6 run to justify that already differentiated role.

B6 should use one representative ~30-minute real recording on both finalists with exact FSMN and the common verified 25-second ASR-input cap. No 60/120-minute extension is pre-required; extend only if B6 remains materially ambiguous.

## Non-decisions

This report does not merge PR #35 or model-channel PR #21, change `main`, promote FunASR, remove SenseVoice/Qwen3 production models, change Room v14, change Local File `FSMN -> Silero`, change realtime Silero, change canonical `sampleIndex` ownership, or start Stage 16C.
