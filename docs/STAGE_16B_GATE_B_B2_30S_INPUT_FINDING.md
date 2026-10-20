# Stage 16B Gate B — B2 30-second Input Finding

Status: **OPEN / 25-SECOND RETEST REQUIRED**

Date: 2026-10-20

## Context

During B2 real-recording completeness QA, a representative natural recording exposed a length-correlated blank-output pattern for `funasr-nano-int8` while SenseVoice and Qwen3-ASR remained nonblank on the exact same frozen PCM inputs.

This document records the evidence without prematurely declaring a root cause. The next verification step is a common 25-second B2 frozen-input cap across all three candidates.

## Frozen baseline

- VAD: exact `fsmn-vad-int8@1.0.0/1`
- FSMN runs once on canonical PCM
- frozen boundaries are canonical `sampleIndex`
- identical frozen chunks are supplied to all three ASRs
- external punctuation: off
- Qwen timeline alignment: off
- Room persistence: none

## Real-device evidence

Recording:

- recording ID: `4a76699d-e8bc-4e07-adf2-a0f63362ecb5`
- canonical SHA-256: `659567ea5d5c7d522dfcd1f41fdbe1f3307f416435e3ac2a14d3fdbdc7c84c6`
- FSMN speech segments: `5`
- frozen ASR inputs: `5`

Model summary under the original 30-second B2 cap:

| Candidate | ASR calls | Blank calls | Blank chunk indexes |
| --- | ---: | ---: | --- |
| Fun-ASR-Nano INT8 | 5 | **3** | **0, 2, 3** |
| SenseVoice INT8 | 5 | 0 | none |
| Qwen3-ASR 0.6B INT8 | 5 | 0 | none |

Frozen boundaries:

| Chunk | Time | Duration | FunASR | SenseVoice | Qwen3 |
| --- | --- | ---: | --- | --- | --- |
| #0 | 0.18–30.18 s | **30.00 s** | **blank** | nonblank | nonblank |
| #1 | 30.18–35.63 s | 5.45 s | nonblank | nonblank | nonblank |
| #2 | 35.92–65.92 s | **30.00 s** | **blank** | nonblank | nonblank |
| #3 | 65.92–95.92 s | **30.00 s** | **blank** | nonblank | nonblank |
| #4 | 95.92–118.25 s | 22.33 s | nonblank | nonblank | nonblank |

The three FunASR blanks align exactly with the three 30.00-second inputs. The shorter 5.45-second and 22.33-second inputs are nonblank. SenseVoice and Qwen3 are nonblank on all five identical inputs.

## Interpretation

This is materially stronger than a normal lexical-quality disagreement because the failure is whole-chunk blank output and is perfectly correlated with the current 30-second benchmark split boundary in this recording.

However, one recording is not sufficient to claim a proven runtime limit. The current working hypothesis is:

> `funasr-nano-int8` has an input-length compatibility problem at or near the current 30-second Android inference boundary.

The finding remains **OPEN** until the same recording is rerun with a lower common chunk cap.

## Verification revision

B2 now uses a dedicated common maximum ASR input duration of **25 seconds**:

- applies equally to FunASR, SenseVoice and Qwen3;
- preserves exact FSMN-once / frozen-sampleIndex fairness;
- does not alter the already-completed B0/B1 historical 30-second benchmark contract;
- does not change any production transcription path or model defaults.

For an FSMN speech range of 30 seconds, B2 must deterministically produce a 25-second frozen input followed by a 5-second frozen input.

## Required retest

Rerun the same recording `4a76699d-e8bc-4e07-adf2-a0f63362ecb5` with the revised B2 QA APK.

Pass signal for the length hypothesis:

- every B2 frozen chunk is `<= 25.00 s`;
- FunASR no longer shows the previous 30-second-correlated blank pattern;
- all three candidates are compared on the same revised boundaries.

If FunASR still returns candidate-specific blank chunks at 25 seconds, do not continue the wider B2 corpus yet. Reduce the experimental cap to 20 seconds or investigate model/runtime generation limits before proceeding.

## Frozen product state

This QA investigation does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote Fun-ASR-Nano;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change Local File product `FSMN -> Silero` policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- start Stage 16C.
