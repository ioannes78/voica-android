# Stage 16B Gate B — B6 Finalist Long-File Endurance

Status: **AUTOMATED PASS / REAL-DEVICE NEXT**

Date: 2026-10-21

## Objective

B6 is the final expensive Gate B run. It does not repeat the full three-model matrix. B1–B5 already established distinct product roles and isolated runtime cost. B6 only checks long-file endurance for the two candidates still competing for the **high-quality local-file role**:

1. **Fun-ASR-Nano INT8**;
2. **Qwen3-ASR 0.6B INT8**.

SenseVoice remains the efficiency/balanced baseline and is not eliminated from the Product Matrix.

## Frozen contract

- exact active `fsmn-vad-int8@1.0.0/1` is mandatory;
- Silero fallback invalidates formal B6 evidence;
- common ASR input cap remains **25 seconds**;
- same recording / canonical SHA / FSMN frozen boundaries are reused for both finalists;
- canonical `sampleIndex` boundaries remain authoritative;
- each finalist runs in a fresh lightweight `:model_validator` process instance;
- B6 candidate dependencies use the read-only model-manager path, excluding WorkManager/durable model-install runtime;
- no external punctuation, timeline alignment, Room transcript writes, diarization, or production model promotion/removal is included in measured ASR time;
- one formal run per candidate process; leaving the candidate screen terminates that process before the other finalist starts.

## Long-file input

Preferred formal input is one representative **~30-minute** real recording. The QA harness accepts **9–45 minutes** so an existing ~10-minute mixed recording can be used as an initial endurance run without rebuilding the harness. If the two finalists remain materially ambiguous after a shorter accepted run, repeat B6 formally on a ~30-minute file before the final Product Matrix decision.

No 60/120-minute extension is pre-required.

## Measurements

For each finalist B6 reports:

- engine-open wall time;
- ASR-only wall time and ASR RTF;
- engine-open + ASR total wall time and total RTF;
- ASR call count / blank-call count;
- normalized output character count;
- first-quarter ASR RTF;
- last-quarter ASR RTF;
- thermal slowdown ratio = last-quarter RTF / first-quarter RTF;
- start / peak / end process PSS;
- Android thermal status at start / maximum observed / end;
- crash/OOM outcome.

PCM is streamed boundary-by-boundary instead of loading the whole long recording into candidate-process memory, so the PSS measurement primarily reflects the model/runtime rather than an avoidable full-file PCM buffer.

## Launcher cleanup

QA/releaseQa no longer exposes a separate desktop launcher icon for every Stage 16B benchmark. The phase-specific activities remain internal. A single **`Voica Stage 16B QA`** hub is the only Stage 16B QA launcher and routes to B6 plus earlier evidence-recovery tools.

Production release manifests are unchanged by this QA-only cleanup.

## Automated checkpoint

Implementation head: `d5dac92390544202788b8c8a2c420a3674079a6b`.

Android PR CI #2072 Fast PR Gate: **PASS**.

- B6 duration-window / plan-codec / first-vs-last-quarter RTF / blank-call regressions: PASS;
- Debug compile: PASS;
- FSMN candidate runtime boundary: PASS;
- Production Gate: skipped as expected.

Production-like B6 QA APK is the next checkpoint.

## Decision rule after B6

B6 does not select by speed alone. Final high-quality-role selection combines:

- B1/B2 recognition quality and completeness;
- B3 punctuation / ITN / language / hotword capability;
- B4 native/aligned timing behavior;
- B5 isolated speed/memory cost;
- B6 long-run crash/blank/thermal/memory stability.

After B6, present the complete Gate B Product Matrix. Do not promote/remove production ASR models, merge PR #35 or model-channel PR #21, or start Stage 16C without explicit user authorization.
