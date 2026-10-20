# Stage 16B Gate B — B5 Isolated Cold/Warm Performance + Memory

Status: **AUTOMATED PASS / REAL-DEVICE PASS**

Date: 2026-10-21

B4 handoff: **REAL-DEVICE PASS**.

## Objective

B5 measures Android-device runtime cost without cross-model PSS contamination. B1–B4 own quality/capability evidence; B5 owns isolated cold/warm speed and process-memory evidence.

## Candidates

- **SenseVoice INT8** — efficiency/balanced-role baseline;
- **Fun-ASR-Nano INT8** — high-quality-role challenger;
- **Qwen3-ASR 0.6B INT8** — existing high-quality-role baseline.

## Final isolation contract

The initial dedicated-process implementation exposed app-bootstrap dependencies in real-device QA. The final verified harness therefore uses a **fresh lightweight process instance** for each candidate session:

- the main QA process prepares the exact common FSMN plan once;
- each candidate session starts in the lightweight validator process path where full `AppContainer` bootstrap is skipped;
- B5 candidate dependencies use a **read-only model-manager path**, so WorkManager / durable model-install runtime is not initialized inside the measured process;
- after the candidate result is captured, the candidate process is terminated before the next model starts;
- a process-local guard allows exactly one formal COLD + WARM session per candidate process instance.

This preserves cross-model memory isolation without bringing BLE, playback, recording, notification, durable model-install scheduling, or other unrelated runtime owners into the benchmark process.

## Frozen input contract

- exact active `fsmn-vad-int8@1.0.0/1` is mandatory;
- Silero fallback invalidates formal B5 evidence;
- same recording / canonical SHA for all candidates;
- common maximum ASR input **25 seconds**;
- canonical `sampleIndex` boundaries authoritative;
- no external punctuation;
- no Qwen/FunASR timeline-alignment pass inside measured ASR time;
- no Room writes;
- no diarization;
- no production model promotion/removal.

## Common formal real-device input

- recording: `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`;
- canonical SHA-256: `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `1349 ms`;
- threads: `2`;
- frozen inputs: `10`;
- frozen speech duration: `109.3420 s`.

## Formal output

| Candidate | Pass | Engine open ms | ASR wall ms | ASR RTF | Total wall ms | Total RTF | Start / peak / end PSS KB | Calls / blank |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| SenseVoice | COLD | 2707 | 9578 | **0.0876** | 12285 | **0.1124** | 50633 / 389108 / 385799 | 10 / 0 |
| SenseVoice | WARM | 2779 | 9520 | **0.0871** | 12299 | **0.1125** | 385755 / 713942 / 385193 | 10 / 0 |
| FunASR Nano | COLD | 10942 | 71587 | **0.6547** | 82529 | **0.7548** | 50970 / 1753708 / 1435369 | 10 / 0 |
| FunASR Nano | WARM | 11078 | 68889 | **0.6300** | 79967 | **0.7313** | 441507 / 1745334 / 1445370 | 10 / 0 |
| Qwen3-ASR | COLD | 9104 | 106789 | **0.9767** | 115893 | **1.0599** | 62938 / 2342673 / 2290038 | 10 / 0 |
| Qwen3-ASR | WARM | 8955 | 82473 | **0.7543** | 91428 | **0.8362** | 1411650 / 2330256 / 2308684 | 10 / 0 |

All six passes completed without crash/OOM and with `blankCalls=0`.

Full evidence: `docs/STAGE_16B_GATE_B_B5_REAL_DEVICE_REPORT.md`.

## B5 ranking

### Runtime speed

1. **SenseVoice** — ASR RTF about `0.087` cold/warm; dramatically fastest.
2. **FunASR Nano** — `0.655 -> 0.630`; comfortably below realtime.
3. **Qwen3-ASR** — `0.977 -> 0.754`; cold ASR only just below realtime and cold total RTF `1.0599` is slightly slower than realtime.

### Process memory

1. **SenseVoice** — cold peak `389108 KB`; warm transient peak `713942 KB`, end about `385 MB`.
2. **FunASR Nano** — peak about `1.75 GB`; end about `1.44 GB`.
3. **Qwen3-ASR** — peak about `2.33 GB`; end about `2.29–2.31 GB`.

## B1–B5 role reduction

B5 does not choose a universal winner by RTF alone.

- **SenseVoice** retains a materially differentiated efficiency role: lowest runtime cost by a large margin, strongest compact ITN formatting, and full native token timing. Its complex mixed/proper-name quality is weaker than the two high-quality contenders, so it is not the B6 high-quality-role finalist. It is **not eliminated** from the Product Matrix.
- **FunASR Nano** remains a high-quality-role finalist because of strong mixed/proper-name evidence, active ITN, strongest controlled hotword A/B and materially lower runtime/memory than Qwen3. Current Android integration still requires the verified common 25-second input cap and lacks native token timing.
- **Qwen3-ASR** remains a high-quality-role finalist because it owns the strongest controlled long-Mandarin result and strongest native punctuation, despite the highest memory pressure and weakest cold realtime margin.

## B6 reduction

Run expensive B6 only for the two candidates still competing for the **high-quality local-file role**:

1. **FunASR Nano INT8**;
2. **Qwen3-ASR 0.6B INT8**.

SenseVoice remains the efficiency/balanced baseline and does not require B6 to justify that already differentiated role.

B6 uses one representative ~30-minute real recording with exact FSMN and the common verified 25-second ASR-input cap. No 60/120-minute extension is pre-required; extend only if B6 remains materially ambiguous.

## Automated checkpoints

Initial B5 automated checkpoint:

- Fast PR Gate #2048: PASS;
- production-like QA APK Gate #2049: PASS.

Fresh-process/bootstrap fixes:

- Fast PR Gate #2055: PASS;
- production-like QA Gate #2056: PASS;
- read-only model-manager / WorkManager-free candidate process head `2e9638fe311db1f4bc02e8c51bc086f1d42e0775`;
- Fast PR Gate #2059: PASS;
- production-like QA Gate #2060: PASS;
- artifact ID `12284494447`;
- artifact digest `sha256:951364d79344960ce8d9b3cb265233930ef0585cfb0251e74248579381028ee9`.

## Frozen non-changes

B5 does not merge PR #35 or model-channel PR #21, modify `main`, promote FunASR, remove current production ASR models, change Room v14, change Local File `FSMN -> Silero`, change realtime Silero, change canonical `sampleIndex` ownership, or start Stage 16C.

## Next

**B6 — representative ~30-minute long-file endurance / completeness for FunASR Nano and Qwen3-ASR only.**