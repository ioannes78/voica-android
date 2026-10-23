# Stage 16B Gate B — B2 30-second Input Finding

Status: **25-SECOND RETEST PASS / LENGTH-CORRELATED COMPATIBILITY FINDING CONFIRMED FOR CURRENT QA RUNTIME**

Date: 2026-10-20

## Context

During B2 real-recording completeness QA, a representative natural recording exposed a length-correlated blank-output pattern for `funasr-nano-int8` while SenseVoice and Qwen3-ASR remained nonblank on the exact same frozen PCM inputs.

The same recording has now been rerun with a common 25-second B2 frozen-input cap across all three candidates. The previous FunASR blank pattern disappeared completely.

This confirms a practical compatibility problem at the previous 30-second B2 input boundary for the current Android FunASR runtime/adapter configuration. It does not by itself prove the lower-level root cause inside sherpa-onnx/model generation.

## Frozen baseline

- VAD: exact `fsmn-vad-int8@1.0.0/1`
- FSMN runs once on canonical PCM
- frozen boundaries are canonical `sampleIndex`
- identical frozen chunks are supplied to all three ASRs
- external punctuation: off
- Qwen timeline alignment: off
- Room persistence: none

## Original 30-second evidence

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

The three FunASR blanks aligned exactly with three `30.00 s` frozen inputs. The shorter `5.45 s` and `22.33 s` inputs were nonblank. SenseVoice and Qwen3 were nonblank on all five identical inputs.

## 25-second verification revision

B2 was revised to use a dedicated common maximum ASR input duration of **25 seconds**:

- applies equally to FunASR, SenseVoice and Qwen3;
- preserves exact FSMN-once / frozen-sampleIndex fairness;
- does not alter the already-completed B0/B1 historical 30-second benchmark contract;
- does not change any production transcription path or model defaults.

For a 30-second FSMN speech range, B2 deterministically produces `25 s + 5 s` frozen inputs.

Automated verification:

- code/doc checkpoint: `d00a3093adb3791b9a77866aa4b9148e9d9ea88c`
- Android PR CI #1980 Fast PR Gate: **PASS**
- 30s -> 25s + 5s split regression: **PASS**
- all-blank candidate cannot report READY: **PASS**
- Android PR CI #1981 production-like QA APK Gate: **PASS**
- artifact ID: `12260190295`
- artifact digest: `sha256:6a21093443f0939fcc925558d74f300a08c7cd2c6de8c5cc28599aaba09ab639`
- extracted APK SHA-256: `b4f2229f60279ebb611170cbe857a5fb1fabc24589069db93bce77fae486e9e2`

## Real-device 25-second retest

Same recording / same canonical PCM:

- recording ID: `4a76699d-e8bc-4e07-adf2-a0f63362ecb5`
- VAD: `fsmn-vad-int8@1.0.0/1`
- FSMN speech segments: `5`
- revised frozen ASR inputs: `8`
- every frozen chunk is `<= 25.00 s`

Model summary:

| Candidate | ASR calls | Blank calls | Blank chunk indexes | Nonblank chunks |
| --- | ---: | ---: | --- | ---: |
| Fun-ASR-Nano INT8 | 8 | **0** | none | 8 |
| SenseVoice INT8 | 8 | 0 | none | 8 |
| Qwen3-ASR 0.6B INT8 | 8 | 0 | none | 8 |

The exact same content that previously produced three candidate-specific FunASR blanks at 30 seconds is nonblank across all revised 25-second-or-shorter frozen inputs.

## Interpretation

The evidence now supports the following Stage 16B conclusion:

> Under the current Android FunASR runtime/adapter, using the previous 30-second B2 ASR input boundary can cause whole-chunk blank output. A common 25-second frozen-input cap removes the observed failure on the same recording.

This is sufficient to use `25 s` as the **Gate B B2 safe comparison cap** and continue the real-recording matrix. The lower-level cause remains an implementation/runtime compatibility detail and is not claimed to be an intrinsic upstream model limit.

If FunASR is later selected for production, the production chunking policy must explicitly account for this verified safe boundary before promotion. No production policy change is authorized by this finding alone.

## Frozen product state

This QA finding does not:

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
