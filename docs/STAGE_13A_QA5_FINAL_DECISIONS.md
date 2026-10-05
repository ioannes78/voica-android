# Stage 13A QA5 Final Model Decisions

Status: **QA5 candidate / pending real-device acceptance / not frozen**

This document records the final Stage 13A QA5 product-selection decisions. For Stage 13A model selection and benchmark-product scope, it supersedes older provisional candidate wording that may remain in historical roadmap/planning text. `docs/STAGE_13A_TEST.md` remains the QA acceptance checklist.

## Product model matrix

### Recording-file offline transcription

- SenseVoice INT8 — fast/default.
- Qwen3-ASR 0.6B INT8 — high quality.
- FireRedASR2 — removed from the product matrix.

Recording-file transcription has one product action: `开始离线转写`. The selected offline model comes from `设置 → 本地语音识别 → 离线转写`.

Qwen may use Small Bilingual only as an optional post-ASR timing/alignment helper. That helper must never replace Qwen final text and must never be a hard dependency for successful Qwen transcription.

### Future realtime transcription

- Small Bilingual Zipformer INT8 — light/default.
- Chinese Large CTC INT8 — high-quality Chinese option.
- Chinese Large Transducer — removed from the product matrix.
- No AUTO product model routing.

Realtime selection is independent from recording-file offline transcription.

### Speaker diarization

- Pyannote Segmentation 3.0 INT8 + CAM++.
- CAM++ is the only product speaker-embedding model.
- ERes2Net — removed from the product matrix.

Auto speaker-count tuning remains a QA5 real-device gate. The default clustering/stitching values are not considered frozen until single-speaker, two-speaker and 3–4-speaker recordings are accepted.

### Shared auxiliary models

- Silero VAD.
- CT-Transformer zh-en punctuation.

## Benchmark scope

The temporary Speech Benchmark / Diarization Benchmark product tools used during model selection are no longer product features. QA5 retains model installation verification, isolated native smoke validation, runtime capability checks, config snapshots and deterministic unit tests.

Further model comparisons should use isolated development tooling or explicit real-device acceptance procedures rather than reintroducing benchmark controls into Recording Detail.

## Model channel

QA5 may use a debug/candidate model-channel manifest for real-device acceptance. The App filters the candidate catalog to the final product model IDs.

The production model channel must remain unchanged until the user explicitly confirms QA5 real-device acceptance with `测试通过` and a later promotion step is authorized.

## Compatibility boundary

QA5 is tested as a fresh installation. No QA4→QA5 SharedPreferences migration or overlay-install compatibility is required.

Room schema remains v7 because QA5 adds no data-model requirement that justifies a schema bump. Internal historical FAST/HIGH_QUALITY and first/second-pass database/state names may remain where changing them would create unnecessary persistence/timeline risk; they are not product-facing model choices.
