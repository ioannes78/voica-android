# Stage 13C C1 — Lineage-safe VAD Reuse QA

Status: QA candidate

Branch: `stage13c-diarization-performance`

Target app build:

- versionCode: `66`
- versionName: `0.13.2-stage13c-c1`
- QA versionName: `0.13.2-stage13c-c1-qa`
- Room schema: `11` (unchanged)
- production model channel: unchanged
- Sherpa runtime: `1.13.8`

## Scope

C1 changes only the VAD execution path used before Stage 9 diarization.

If a completed transcription for the same recording has VAD boundaries whose durable lineage exactly matches the current diarization request, diarization reuses those persisted boundaries instead of running Silero VAD a second time.

Reuse requires matching:

- recording ID
- canonical SHA-256
- canonical profile
- total sample count
- transcription pipeline version
- Sherpa runtime ID/version/provider
- VAD model ID/version/revision/manifest digest
- effective CPU thread count
- VAD threshold
- VAD min silence
- VAD min speech
- VAD max speech
- VAD window size
- valid, ordered, non-overlapping persisted segment ranges

Any uncertainty or mismatch is a cache miss and must execute the existing Silero VAD path unchanged.

C1 does **not** change:

- Pyannote Segmentation 3.0 INT8
- CAM++
- native sherpa diarization configuration
- chunk/window defaults
- overlap defaults
- clustering
- stitching
- transcript-speaker alignment
- Room schema
- production model channel

The C0 profiler remains outside the reuse provider, so a reuse hit still reports one `VAD_ENGINE` invocation and the original VAD workload counters. Its elapsed time should collapse to the cached-path overhead rather than disappear from the benchmark.

## C0 baseline used for comparison

Use the same three recordings and the same default speech settings / AUTO performance / effective 2 threads.

### Sample A — single speaker safe file

- file: `01_single_speaker_6min_safe.wav`
- C0 total: ~393.24 s
- C0 RTF: ~1.046
- C0 VAD_ENGINE: ~12.76 s
- C0 VAD segments: 50
- C0 speech samples: 5,445,632

### Sample B — normal two-speaker dialogue

- file: `02_two_speaker_dialogue_6min.wav`
- C0 total: ~217.83 s
- C0 RTF: ~0.630
- C0 VAD_ENGINE: ~9.09 s
- C0 VAD segments: 39
- C0 speech samples: 5,078,016

### Sample C — rapid multi-speaker turns

- file: `03_multi_speaker_rapid_turns_6min.wav`
- C0 total: ~218.16 s
- C0 RTF: ~0.632
- C0 VAD_ENGINE: ~9.67 s
- C0 VAD segments: 130
- C0 speech samples: 4,645,792

Native diarization dominates total latency and is intentionally unchanged in C1. Therefore total wall-time is a secondary measure for C1; the primary acceptance signal is a lineage-safe VAD hit with unchanged VAD boundaries/workload.

## Main real-device test

For each sample:

1. Keep the same models, VAD settings, AUTO performance profile and automatic thread selection used for C0.
2. Ensure the recording has a completed offline transcription created with those settings.
3. Run/allow automatic speaker diarization and alignment.
4. Confirm the user-visible transcript and speaker result completes normally.
5. Retrieve the new JSON from `Downloads/Voica/Diagnostics/`.
6. Submit all three C1 JSON files for comparison against C0.

## Expected C1 hit

For each matching sample:

- `outcome = COMPLETED`
- `VAD_ENGINE.invocationCount = 1`
- `VAD_ENGINE.elapsedMs` should be near cached-path overhead instead of the C0 9–13 s native VAD cost
- `vadSegmentCount` should match the C0 recording
- `speechSampleCount` should match the C0 recording
- canonical/model/runtime/config lineage should remain valid
- native diarization, anchor embedding, stitching and alignment remain functionally unchanged

Do not reject C1 only because total elapsed time varies. C0 already showed large native-runtime variance, especially for the single-speaker sample. Compare the detailed engine metrics before attributing any total-time delta to C1.

## Safe-miss regression

Automated tests cover canonical, VAD-setting, thread, manifest, segment-order and overlap mismatches. A mismatch must return `null` from the reuse gate and preserve the original Silero VAD path.

A separate manual safe-miss smoke test can be run after the three primary samples if needed; do not alter the three primary baseline settings before their JSONs are collected.

## Known unrelated QA issue

The previously observed offline-ASR error `speech segment exceeds offline ASR safety bound` is recorded separately as a VAD → offline-ASR 30-second boundary defect. It is **not** modified in C1 and must not be conflated with diarization VAD reuse.

## C1 acceptance gate

C1 can proceed to C2 analysis only after:

- CI is green;
- the three primary real-device runs complete;
- C1 JSON confirms reuse on matching lineage;
- VAD segment/speech workload is unchanged for each same-source comparison;
- no Stage 13B durable lifecycle regression is observed.

Do not Freeze or merge Stage 13C at C1. Final Stage 13C Freeze remains a later-stage gate after the full performance/regression sequence and explicit user acceptance.
