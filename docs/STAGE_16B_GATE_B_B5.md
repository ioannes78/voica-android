# Stage 16B Gate B — B5 Isolated Cold/Warm Performance + Memory

Status: **AUTOMATED PASS / REAL-DEVICE NEXT**

Date: 2026-10-20

B4 handoff: **REAL-DEVICE PASS**. Corrected B4 QA interpretation checkpoint: `9e62acbfb179eb09fa5227d640ef0261765ce4c0`; Fast PR Gate #2031 PASS. B4 production alignment behavior was not changed.

## Objective

B5 measures Android-device runtime cost without the cross-model PSS contamination observed in earlier same-process exploratory runs. B5 is not another accuracy matrix: B1–B4 already own quality/capability evidence.

## Candidates retained for B5

All three candidates remain because each still owns a materially different product-role signal:

- **SenseVoice INT8** — low runtime-cost signal, strongest compact ITN formatting, full native token timing;
- **Fun-ASR-Nano INT8** — strongest mixed/proper-name tendency, active ITN, strongest supported hotword A/B, alignment-compatible candidate path;
- **Qwen3-ASR 0.6B INT8** — strongest long-Mandarin evidence and native punctuation, existing high-quality product role.

No additional model is added. B5 is needed before deciding whether any candidate is dominated enough to skip B6.

## Isolation contract

The three candidates never run sequentially in the same Android process for formal B5 evidence.

The QA build declares three private app processes:

- SenseVoice: `:stage16b_b5_sense`;
- FunASR: `:stage16b_b5_funasr`;
- Qwen3: `:stage16b_b5_qwen3`.

The B5 launcher prepares the common exact-FSMN frozen input once in the main QA process. Each candidate process may consume exactly one formal session containing:

1. a cold engine-open + transcription pass;
2. engine close;
3. a warm engine-open + transcription pass on the exact same frozen PCM inputs.

A process-local guard refuses a second formal session without process restart.

## Frozen input contract

- exact active `fsmn-vad-int8@1.0.0/1` is mandatory;
- Silero fallback invalidates formal B5 evidence;
- same recording / canonical SHA is used for all candidates;
- common maximum ASR input is **25 seconds**;
- canonical `sampleIndex` boundaries remain authoritative;
- no external punctuation;
- no Qwen/FunASR timeline-alignment pass inside measured ASR time;
- no Room writes;
- no diarization;
- no product model promotion/removal.

Use one representative 1–3 minute real-speech recording with several frozen ASR inputs. B6 owns long-file endurance.

## Measurements

For each cold and warm pass B5 reports:

- engine-open wall time;
- ASR-only wall time;
- engine-open + ASR total wall time;
- ASR / total RTF using exact frozen **speech duration** as denominator;
- start / peak / end process PSS via `Debug.getPss()`;
- ASR call / blank-call counts;
- process/session identity and PID;
- crash/OOM outcome.

VAD preparation time is reported separately and excluded from candidate ASR RTF. B4 alignment overhead is also excluded from B5 measured ASR time and carried separately into the final Product Matrix.

## Automated checkpoint

B5 implementation checkpoint: `a49ea1ef2fd7cff0ee385b5509262e9208853684`.

- Android PR CI #2048 Fast PR Gate: **PASS**;
- B5 plan codec / 25-second split / RTF / blank-call regressions: **PASS**;
- Debug compile: **PASS**;
- FSMN runtime boundary: **PASS**;
- Android PR CI #2049 production-like QA APK Gate: **PASS**;
- `assembleReleaseQa`: **PASS**;
- APK verification / security / model-channel checks: **PASS**;
- artifact ID: `12280288773`;
- artifact digest: `sha256:36a1e20e05baf38a9370a2be71c60ffb8e16647cf2d91938780ab071bc529421`;
- extracted APK SHA-256: `47596c051546b2b3260a1cb35ed2c60f4dd0a34b0909c7b83c2a07a0b13e8339`.

CI success does not substitute for B5 real-device measurements.

## Real-device procedure

1. Install/cover-install the B5 `releaseQa` APK.
2. Confirm FSMN, SenseVoice, FunASR Nano and Qwen3 are installed/active.
3. Open **Voica B5 Performance QA**.
4. Choose one representative **1–3 minute** real-speech recording. The launcher runs FSMN once and displays `B5 PLAN READY`.
5. Do not choose another recording after the plan is ready. The same recording/SHA/frozen inputs must be used for all three candidates.
6. Run **SenseVoice — isolated process** and press `RUN COLD + WARM` once. Capture the full result.
7. Return to the B5 launcher and run **FunASR Nano — isolated process** once. Capture the full result.
8. Return and run **Qwen3-ASR — isolated process** once. Capture the full result.
9. Do not rerun a candidate in the same candidate process. If a formal rerun is needed, restart the app process and prepare a fresh common plan.

Order does not contaminate candidate PSS because each candidate uses a different process. Use the same prepared plan throughout.

## Formal output

| Candidate | Pass | Engine open | ASR wall | ASR RTF | Total wall | Total RTF | Start PSS | Peak PSS | End PSS | Calls / blank | Result |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |

## Interpretation / B6 reduction

B5 does not select a universal winner by lowest RTF alone. The final decision combines B1/B2 quality, B3 capabilities, B4 timing behavior and B5 runtime cost.

After B5:

- keep candidates that remain materially differentiated by quality/capability/runtime role;
- remove a candidate from expensive B6 if another dominates it on its relevant role without a compensating feature;
- carry close candidates to B6;
- do not automatically run B6 for all three.

## Frozen non-changes

B5 does not merge PR #35 or model-channel PR #21, modify `main`, promote FunASR, remove current production ASR models, change Room v14, change Local File `FSMN -> Silero`, change realtime Silero, change canonical `sampleIndex` ownership, or start Stage 16C.

## Next

Run the three isolated B5 real-device sessions and compare their cold/warm latency + process PSS. Then reduce the candidate/role matrix before B6.