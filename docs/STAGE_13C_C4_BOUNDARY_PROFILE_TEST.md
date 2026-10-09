# Stage 13C C4 — Boundary / Overlap Profiling QA

Status: C4 measurement-only QA candidate. This is not a Stage 13C Freeze/final candidate.

## Build

- Branch: `stage13c-diarization-performance`
- Version code: `69`
- Version name: `0.13.2-stage13c-c4-profile`
- QA version: `0.13.2-stage13c-c4-profile-qa`
- Room: v11, unchanged
- Production model channel: unchanged

## Purpose

C0/C1/C2 data established that native diarization remains the dominant cost. C2 exact-range anchor caching produced no real-device hit and was removed. C3 confirmed that sherpa-onnx 1.13.8 does not expose a verified true batch speaker-embedding API or internal diarization embeddings that Voica can safely reuse.

C4 therefore begins with **measurement only**. This build does not change the current 60 s window / 10 s overlap policy. It measures what is actually present inside every overlap boundary so that a later adaptive-overlap or larger-window experiment can be selected from real data rather than guesswork.

## What C4 records

For every pair of adjacent diarization windows that actually overlap, the QA build records:

- overlap start/end and total samples
- VAD speech samples inside the overlap
- silence samples inside the overlap
- speech ratio
- number of merged speech runs
- number of silence gaps
- longest silence gap length and absolute sample range

The report also contains an aggregate summary across all boundaries.

Speech/silence is derived from the exact VAD segments already used by the diarization run. C1 lineage-safe VAD reuse remains active where eligible; C4 does not run VAD a second time.

## Explicitly unchanged

C4 profiling does **not** change:

- Silero VAD behavior or C1 reuse gate
- Pyannote Segmentation 3.0 INT8
- CAM++ model bytes
- sherpa native diarization implementation/defaults
- 60 s chunk size
- 10 s overlap
- clustering thresholds
- anchor selection or stitching behavior
- transcript alignment
- Stage 13B durable lifecycle
- Room v11
- production model channel

The C4 wrapper observes inputs/results and forwards them unchanged.

## Export

QA/debug builds export an additional JSON file after each diarization run to:

`Downloads/Voica/Diagnostics/`

File name prefix:

`diarization-boundary-`

The normal Stage 13C benchmark JSON continues to be exported separately. C4 boundary analysis is delayed until after the native diarization engine closes so it does not execute inside measured native calls.

## Real-device test

Use the same three recordings used for C0/C1/C2, with the same AUTO / 2-thread / default settings:

1. rapid turn-taking / short utterances
2. normal two-person conversation
3. single-speaker Safe control

For each recording:

1. Keep the existing completed transcription so C1 VAD reuse remains eligible.
2. Run speaker diarization and alignment normally.
3. Verify the visible speaker/transcript result is still usable and unchanged in behavior.
4. Wait a few seconds after diarization completes.
5. Collect the newest `diarization-boundary-*.json` from `Downloads/Voica/Diagnostics/`.
6. Submit all three C4 boundary JSON files.

Do not change speaker-count preset, model, performance profile, thread count, window size, overlap, clustering threshold, or VAD settings between the three samples.

## Decision metrics

For each sample calculate:

- total overlap samples
- speech samples in overlap
- silence samples in overlap
- speech ratio in overlap
- distribution of longest-silence-gap samples per boundary
- count/share of boundaries with no silence at all

No arbitrary "safe cut" threshold is encoded in this build. The longest silence gaps are observations only.

## Gate after QA

Use the measured boundary distribution to choose the next C4 experiment:

- If most duplicated overlap contains meaningful silence gaps, design a conservative silence-aware adaptive-overlap candidate and confirm its exact policy before changing production window semantics.
- If overlap is predominantly continuous speech, do not force adaptive cuts; instead prepare a controlled 60/90/120 s window-size A/B with unchanged overlap semantics and accuracy guardrails.

Do not Freeze Stage 13C, merge PR #23, change Room, or promote the production model channel at this gate.
