# Stage 16B Gate B — B6 Finalist Long-File Endurance

Status: **REAL-DEVICE PASS / PRODUCT DECISION AUTHORIZED**

Date: 2026-10-21

## Frozen contract

- exact active `fsmn-vad-int8@1.0.0/1`;
- common ASR input cap: **25 seconds**;
- same recording / canonical SHA / FSMN-frozen boundaries for both finalists;
- `threads=2`, fresh lightweight `:model_validator` process per finalist;
- one formal run per finalist; no external punctuation, diarization, Room writes or model promotion included in measured ASR time.

## Formal real-device input

- recording: `badf8d81-4ede-4fb1-b1f7-966d85e2ac78`;
- canonical SHA-256: `2f611589ba1c48eb0d7f4634340c9e5e4bc81f0c40c635d508464ad797138e878`;
- recording: `628.3468 s`;
- frozen speech: `597.7900 s`;
- frozen inputs: `54`;
- VAD wall: `5003 ms`.

The accepted B6 window is 9–45 minutes. The ~10.5-minute run did not leave the finalists materially ambiguous, so the preferred ~30-minute extension is not required.

## Results

| Metric | FunASR Nano INT8 | Qwen3-ASR 0.6B INT8 |
| --- | ---: | ---: |
| engine open | 12.522 s | **8.175 s** |
| ASR wall | **377.987 s** | 425.601 s |
| ASR RTF | **0.6323** | 0.7120 |
| total RTF | **0.6533** | 0.7256 |
| first-quarter ASR RTF | **0.5836** | 0.7407 |
| last-quarter ASR RTF | **0.6304** | 0.6546 |
| thermal slowdown ratio | 1.0801 | **0.8838** |
| PSS start / peak / end KB | **46540 / 1728929 / 1543305** | 50059 / 2396068 / 2390460 |
| thermal start / max / end | NONE / NONE / NONE | NONE / NONE / NONE |
| ASR calls | 54 | 54 |
| blank calls | **0** | **0** |
| normalized chars | 1965 | 1920 |
| crash / OOM | none | none |

Both finalists pass endurance. FunASR keeps materially lower long-run ASR time and memory pressure; Qwen3 opens faster and also shows no thermal instability.

## Final Gate B product decision

User authorization after B6 fixes the Local File ASR V2 production roles as:

1. **SenseVoice INT8 — balanced / efficiency role**;
2. **FunASR Nano INT8 — high-quality role**;
3. **Qwen3-ASR 0.6B INT8 — removed from the production product matrix and replaced by FunASR Nano**.

The decision combines B1–B6 rather than speed alone: FunASR showed the stronger mixed/proper-name tendency, active ITN, strongest controlled hotword A/B, no B6 blanks/crash/OOM, lower B5/B6 runtime cost than Qwen3, and substantially lower B6 peak PSS. Qwen3 historical QA/runtime support may remain in source so prior evidence/transcripts remain interpretable; it is no longer a selectable production Local File ASR role.

## Promotion boundary

The authorized product-matrix change is staged through Draft model-channel PR #21. Android PR #35 and model-channel PR #21 remain unmerged until explicit merge authorization. Stage 16C remains RESERVED / NOT STARTED.
