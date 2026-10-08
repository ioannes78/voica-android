# Stage 13C C2 — Exact-range Anchor Embedding Reuse QA

Status: C2 QA candidate only. This is not a Stage 13C Freeze/final candidate.

## Build

- Branch: `stage13c-diarization-performance`
- Version code: `67`
- Version name: `0.13.2-stage13c-c2`
- QA version: `0.13.2-stage13c-c2-qa`
- Room: v11, unchanged
- Production model channel: unchanged

## C1 accepted baseline

C1 lineage-safe VAD reuse is accepted for continuation because all three same-source runs retained identical VAD/workload/result structure while actual `VAD_ENGINE` time collapsed to cached-path overhead.

| Sample | C1 VAD | VAD segments | Speech samples | Anchor calls | Anchor samples | Overlap processed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| rapid turn-taking | 0.480 ms | 130 | 4,645,792 | 23 | 4,378,908 | 960,000 |
| normal conversation | 1.264 ms | 39 | 5,078,016 | 22 | 4,963,208 | 160,000 |
| single-speaker Safe | 0.368 ms | 50 | 5,445,632 | 24 | 5,479,120 | 0 |

Do not attribute the much larger C0→C1 total-wall reduction to VAD reuse. `NATIVE_DIARIZATION` itself varied substantially between runs. C1's directly attributable gain is the removed native VAD work (~9–13 seconds in the C0 samples).

## C2 implementation under test

C2 adds a bounded run-scoped LRU cache for the app's extra cross-window anchor CAM++ embeddings.

A cache hit requires the same:

- embedding-engine/run scope (never shared across a later recording/run)
- absolute canonical sample range
- embedding model id/version/revision
- embedding model file SHA-256 lineage
- sample rate
- anchor preprocessing version

The cache retains at most 128 entries and also enforces a 512 KiB embedding-memory ceiling. It is in-memory only. There is no Room migration and no persistent/cross-run reuse.

Because the cache is scoped to the per-run embedding engine, entries cannot cross canonical-source lineages. If a future implementation makes this cache persistent/shared across runs, explicit canonical SHA-256 must be part of the persisted lookup key before such reuse is allowed.

On a hit, the app skips both `copyOfRange` and the native speaker-embedding call. The C0/C1 profiler therefore records only actual embedding misses/native calls in `ANCHOR_EMBEDDING`.

## Intentionally unchanged

C2 does not change:

- Silero VAD behavior or C1 lineage gate
- Pyannote Segmentation 3.0 INT8
- CAM++ model bytes
- Sherpa native diarization implementation/defaults
- 60 s chunk / 10 s overlap defaults
- clustering thresholds
- stitching algorithm/thresholds
- transcript alignment
- Stage 13B durable lifecycle
- Room v11
- production model channel

There is no synthetic batch API and no invented Sherpa internal timing split.

## Real-device test

Use the same three recordings used for C0/C1 and preserve the same AUTO / 2-thread / default-VAD/default-diarization settings.

For each recording:

1. Keep the existing completed transcription so C1 VAD reuse remains eligible.
2. Run speaker diarization and alignment normally.
3. Verify the visible speaker/transcript result is still usable and has not regressed.
4. Collect the new JSON from `Downloads/Voica/Diagnostics/`.
5. Submit all three C2 JSON files for C1→C2 comparison.

Do not change speaker-count preset, model, performance profile, thread count, window size, overlap, clustering threshold, or VAD settings between C1 and C2.

## Primary acceptance checks

For every same-source pair:

- `VAD_ENGINE` remains cached-path overhead and VAD segment/speech-sample counts remain identical.
- native diarization workload/result structure must not regress unexpectedly.
- final speaker count/final turn structure must remain equivalent to the C1 result unless a native-runtime variance is separately demonstrated.

For C2 specifically:

- rapid turn-taking C1 reference: 23 actual anchor calls / 4,378,908 anchor samples / 10.114 s anchor time.
- normal conversation C1 reference: 22 calls / 4,963,208 samples / 12.107 s.
- single-speaker Safe C1 reference: 24 calls / 5,479,120 samples / 12.895 s.

The single-speaker Safe sample has zero duplicated window coverage and is therefore an important control: an exact-range cache is not expected to create a benefit when no anchor range repeats.

A C2 benefit is accepted only where the same native/workload structure shows fewer actual anchor embedding calls/samples. Total wall time remains secondary because native diarization variance is already proven large.

If the three real samples show zero useful exact-range hits, do not broaden cache semantics speculatively. Record the negative result and move to C3 native/CAM++ execution analysis instead.

## Gate after QA

- Do not Freeze Stage 13C.
- Do not merge PR #23.
- Do not change production model channel.
- Analyze C1→C2 JSON deltas first.
- Keep C2 only if correctness is unchanged and real reuse is demonstrated; otherwise reconsider/revert the cache before C3.
