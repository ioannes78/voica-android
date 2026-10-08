# Stage 13C C2 — Exact-range Anchor Embedding Reuse Result

Status: **REJECTED after real-device QA**. The C2 cache has been removed from the production path. This document is retained as the experiment/result record and is not a Stage 13C Freeze/final candidate.

## Tested build

- Branch: `stage13c-diarization-performance`
- Version code: `67`
- Version name: `0.13.2-stage13c-c2`
- QA version: `0.13.2-stage13c-c2-qa`
- Room: v11, unchanged
- Production model channel: unchanged

## C1 accepted baseline

C1 lineage-safe VAD reuse remains accepted. Its directly attributable gain is removal of the repeated native Silero VAD pass (~9–13 seconds in the C0 samples). Larger wall-time differences are not attributed to C1 because native diarization variance is substantial.

| Sample | C1 VAD | VAD segments | Speech samples | Anchor calls | Anchor samples | C1 anchor time | Overlap processed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| rapid turn-taking | 0.480 ms | 130 | 4,645,792 | 23 | 4,378,908 | ~10.114 s | 960,000 |
| normal conversation | 1.264 ms | 39 | 5,078,016 | 22 | 4,963,208 | ~12.107 s | 160,000 |
| single-speaker Safe | 0.368 ms | 50 | 5,445,632 | 24 | 5,479,120 | ~12.895 s | 0 |

## C2 experiment

C2 tested a bounded run-scoped exact-range LRU cache for the app's extra cross-window CAM++ anchor embeddings. A candidate hit required the same per-run embedding-engine scope, absolute canonical sample range, embedding-model lineage, sample rate, and preprocessing version. The cache was in-memory only, capped at 128 entries and 512 KiB, and did not alter Room or the model channel.

The acceptance contract was intentionally strict: the cache could remain only if same-source real-device runs preserved correctness/workload while reducing the number or sample volume of actual `ANCHOR_EMBEDDING` calls.

## Real-device result

All three C2 runs completed successfully with the same AUTO / 2-thread / default configuration used by C1.

| Sample | C1 anchor calls / samples | C2 anchor calls / samples | C1 anchor time | C2 anchor time | Exact-range reuse |
| --- | --- | --- | ---: | ---: | --- |
| rapid turn-taking | 23 / 4,378,908 | 23 / 4,378,908 | ~10.114 s | 11.205 s | none observed |
| normal conversation | 22 / 4,963,208 | 22 / 4,963,208 | ~12.107 s | 11.617 s | none observed |
| single-speaker Safe | 24 / 5,479,120 | 24 / 5,479,120 | ~12.895 s | 13.679 s | none observed |

The actual native anchor invocation count and processed sample count did not fall in any sample. Therefore the exact-range cache produced no observable real-device hit. The anchor-time differences are treated as runtime variance rather than cache benefit.

C1 VAD reuse remained healthy in C2: detailed `VAD_ENGINE` times were 0.782 ms, 0.556 ms, and 0.386 ms respectively, with the same VAD workload/result structure.

The rapid-turn sample is particularly informative: it had 960,000 duplicated window samples, yet the selected anchor ranges were still not exact duplicates. A generic exact-range anchor cache therefore does not address the measured overlap cost.

## Decision

C2 is rejected and the exact-range cache is removed from the production path.

Do **not** broaden the cache to fuzzy/approximate range matching. Such matching would change speaker-stitching semantics and would no longer be a mechanically safe reuse optimization.

No persistent cache, Room migration, model-channel change, window change, clustering change, or model change is introduced.

## Next gate

Proceed to C3 native/CAM++ execution-path audit. Verify the actual sherpa-onnx 1.13.8 public API before considering batching, stream reuse, internal-embedding reuse, or concurrency. If the runtime exposes no safe high-value mechanism, close C3 without speculative optimization and move to C4 window/overlap/native duplicate-work analysis.

- Do not Freeze Stage 13C.
- Do not merge PR #23.
- Keep C1 VAD reuse.
