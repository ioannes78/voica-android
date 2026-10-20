# Stage 16B Gate B — B2 Real Recording Quality / Completeness

Status: **AUTOMATED PASS / REAL-DEVICE NEXT**

## Objective

B2 checks whether the three Gate B ASR candidates preserve real spoken content under natural recording conditions. Unlike B1, B2 does not use a frozen synthetic reference transcript and therefore does not manufacture CER/WER for uncontrolled real recordings.

Candidates:

1. `funasr-nano-int8`
2. `sensevoice-2024-int8`
3. `qwen3-asr-0.6b-int8`

## Fairness contract

B2 keeps the same formal Gate B baseline:

1. exact active `fsmn-vad-int8` is mandatory;
2. Silero fallback invalidates the formal B2 run;
3. FSMN runs exactly once per canonical recording;
4. canonical `sampleIndex` speech boundaries are frozen once;
5. oversized speech ranges are split deterministically with the existing 30-second ASR input cap;
6. the exact same frozen PCM chunks are sent to every ASR candidate in the same order;
7. external punctuation and Qwen timeline alignment remain disabled;
8. no B2 transcript/evidence is persisted to Room.

First-pass B2 runs are limited to 120 seconds. B6 owns representative long-file testing.

## QA evidence model

The QA-only `Stage16BGateB2CompletenessRunner` records, for every frozen input chunk and every candidate:

- zero-based frozen chunk index;
- canonical `startSampleIndex`;
- canonical `endSampleIndexExclusive`;
- raw hypothesis text;
- detected language when available;
- whether the chunk is blank after removing only known SenseVoice language metadata;
- model identity;
- engine-open time;
- ASR wall time;
- total wall time / RTF signal;
- start / peak PSS signal;
- model/runtime error if present.

The QA page also prints a `B2 FROZEN-CHUNK MATRIX` so the same chunk can be reviewed across FunASR, SenseVoice and Qwen3 without losing alignment when one model returns blank text.

B2 PSS/RTF remain observational signals only. Formal isolated performance and memory ranking belongs to B5.

## Automated checkpoint

Verified code / QA APK head: `e8e5477c4a99b98e6f6e149c9027647652b46324`.

- Android PR CI #1973 / Fast PR Gate: **AUTOMATED PASS**
- unit tests + debug compile: **PASS**
- B2 chunk-evidence regression tests: **PASS**
- FSMN runtime boundary: **PASS**
- Android PR CI #1974 / production-like QA APK Gate: **AUTOMATED PASS**
- `assembleReleaseQa`: **PASS**
- QA APK verification/security/model-channel checks: **PASS**
- QA artifact ID: `12255162649`
- QA artifact digest: `sha256:70de5ca27ac8fcaa70157b38366e043847447e12fd4113679050fb6b5ad25792`
- extracted APK SHA-256: `608973e32313c2d3c8e1a586ca1c9260d63df0c63596f2c392fac2c1aea3551d`

CI success does not substitute for B2 real-device completeness review.

## What B2 reviews

B2 manual review focuses on real content preservation:

- whole sentence loss;
- head-word or tail-word loss;
- middle-of-sentence omission;
- loss immediately after a natural pause;
- repeated phrase or repeated chunk output;
- hallucinated content not present in the recording;
- obvious semantic replacement;
- blank output from one model while the other two recover the same frozen chunk;
- degradation on natural code-switching or numbers when present in the recording.

If all three candidates omit identical words from the same frozen chunk, do not automatically attribute the failure to all three ASRs. Review the frozen FSMN boundary and source audio first, as demonstrated by the B1 `PAUSE` evidence.

## First-pass real recordings

Use three short, representative recordings rather than a large matrix:

1. **Single-speaker natural speech** — about 60–120 seconds, normal distance and speaking rate.
2. **Two/multiple-speaker natural conversation** — about 60–120 seconds. B2 evaluates content completeness only; diarization accuracy is not scored here.
3. **Mixed-content natural speech** — about 60–120 seconds containing some combination of English terms, numbers, names and natural pauses.

Existing Voica recordings may be reused if they satisfy these conditions. Do not rerecord a synthetic script solely to make B2 easier to score; B1 already owns controlled reference quality.

## Human QA procedure

1. Install the production-like QA APK containing B2.
2. Keep exact FSMN, Fun-ASR-Nano, SenseVoice and Qwen3-ASR installed/active.
3. Open `Voica Gate B QA`.
4. Select `Use B2 real-recording completeness mode`.
5. Tap one representative local recording.
6. Require exact `fsmn-vad-int8` in the result.
7. Confirm the same `frozenAsrInputs` count is used for all three candidates.
8. Review model summaries for blank chunk indexes and runtime errors.
9. Review the `B2 FROZEN-CHUNK MATRIX` chunk by chunk.
10. Capture the full result evidence for the Stage 16B report.

## B2 decision rule

B2 is not a final product-model decision. A candidate shows a material B2 completeness weakness only when the same real recordings repeatedly show candidate-specific omissions, blank chunks, repetitions, hallucinations or semantic loss under identical frozen inputs.

One isolated lexical disagreement does not eliminate a model. Repeated candidate-specific sentence loss or hallucination is materially stronger evidence.

All viable candidates continue to B3 punctuation/ITN/language/hallucination and B4 timestamp/timeline compatibility. B5/B6 remain reserved for viable finalists.

## Frozen product state

B2 does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote Fun-ASR-Nano;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change Local File `FSMN -> Silero` product policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- modify QS668/CB08, Android Audio I/O, playback or Stage 16A.2 ownership;
- start Stage 16C.
