# Stage 13A Final Model Decisions

Status: **FROZEN / real-device acceptance passed on 2026-10-05**

This document records the frozen Stage 13A product-selection decisions. For Stage 13A model selection, benchmark-product scope, and Stage 16 streaming-model eligibility, it supersedes older provisional candidate wording that may remain in historical planning text. `docs/STAGE_13A_TEST.md` is the acceptance checklist; `docs/STAGE_13A_FREEZE.md` is the formal freeze record.

User acceptance: **“测试通过”**.

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
- CAM++ is the frozen product speaker-embedding model.
- ERes2Net — removed from the product matrix.

The Stage 13A QA5/Fix1 real-device acceptance gate, including the current Auto Speaker behavior under the accepted configuration, has been passed by the user. Any future change to the frozen embedding model or default clustering/stitching behavior requires explicit regression testing; Stage 13B stability work must not silently replace the Stage 13A speaker model matrix.

### Shared auxiliary models

- Silero VAD.
- CT-Transformer zh-en punctuation.

## Benchmark scope

The temporary Speech Benchmark / Diarization Benchmark product tools used during model selection are not product features and are not part of the frozen product surface.

Stage 13A retains model installation verification, isolated native smoke validation, runtime capability checks, config snapshots and deterministic unit tests. Further model comparisons should use isolated development tooling or explicit real-device procedures rather than reintroducing benchmark controls into Recording Detail.

## Model channel

Stage 13A real-device acceptance used the existing debug/candidate model-channel path; the App filters the candidate catalog to the frozen product model IDs.

The production model channel was **not promoted as part of this Freeze/Handoff**. At freeze time, `ioannes78/voica-model-channel` production `main` remains at `e4e64d29b8c92b97de4298ec6e292c33273f3ba4`.

Production promotion is a separate controlled operation and requires explicit authorization. Stage 13A Freeze does not silently modify the production manifest.

## Compatibility boundary

Stage 13A QA5/Fix1 acceptance was performed as a fresh installation. No QA4→QA5 SharedPreferences migration or overlay-install compatibility is frozen as a requirement.

Room schema remains v7 because Stage 13A adds no data-model requirement that justifies another schema bump. Internal historical FAST/HIGH_QUALITY and first/second-pass database/state names may remain where changing them would create unnecessary persistence/timeline risk; they are not product-facing model choices.

## Frozen QA baseline

- Functional/QA HEAD: `6543d9f45ed73bb3a815456968518f0d3641b774`
- versionCode: 46
- versionName: `0.13.0-stage13a-qa5-fix1`
- Room schema: 7
- sherpa-onnx: 1.13.8
- ABI: arm64-v8a
- Android PR CI: #693 / run `37264265999` — success
- Artifact: `Voica-qa-apk` / ID `11326236293`
- Artifact digest: `sha256:e86a9379fe5f5d08d1978cd9e1e9598898c5ce93609e0304fba00dd2274f8d66`
- Accepted APK SHA-256: `cebc706417abf881ecc3360b922e8fe98369320f16d4f0c4bdad5955ec62bc05`
