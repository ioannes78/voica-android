# Stage 8 Development

状态：**COMPLETED / 用户验收通过 / 准备 Freeze**

Stage 8 起始基线：

- main HEAD：`6bcc8a425e68fff23e2b5ea58764f692517ac3e2`
- Stage 7 source tree：`807a7395f5800d3dd0b2c2fd13be8563735c77d6`

最终实现 HEAD：

`189ea18dd11a45227d541b133c4795e38ffa85ea`

最终 QA 候选：

- versionCode 23
- versionName `0.8.0-stage8-alpha4`
- QA package `io.github.ioannes78.voica.qa`
- sherpa-onnx 1.13.8
- Room schema 2
- arm64-v8a
- CI `36920166807` / #248 — success

## 已完成范围

- `:core:model`
- `:core:transcript`
- `:engine:sherpa`
- Room 1 → 2 migration
- built-in Silero VAD baseline
- Small Bilingual first-pass/streaming ASR
- CT-Transformer punctuation
- SenseVoice High Quality second pass
- model catalog/download/hash/staging/promotion/activation/rollback
- exact revision lease
- automatic production-manifest checks
- Silero-only automatic update eligibility
- candidate catalog QA override
- resumable large-model download and background verification/extraction
- Fast local transcription
- High Quality two-pass transcription
- cancellation / interrupted reconciliation
- atomic completed persistence
- independent FAST/HIGH_QUALITY history
- transcript version list / per-version viewing
- virtual 30/60/120min bounded-read tests
- stable fixed-signature QA build track

## 冻结模型

production model-channel main：

`2817873b74d87ff1d4bfa54266e082ede450ba06`

manifest digest：

`ceff5a281acd1166a75e53c9112dd88e93c9e0266d6a545651159a97ebc0154b`

模型：

- Silero VAD int8 2025-07-11
- Small Bilingual Zipformer zh-en 2023-02-16
- CT-Transformer punctuation zh-en int8 2024-04-12
- SenseVoice zh-en-ja-ko-yue int8 2024-07-17

## 关键修复

Alpha2 大模型安装末段卡死根因涉及下载进度高频 UI 更新及下载完成后的重 I/O/CPU 工作调度。

Alpha3 已冻结修复：

- progress throttle
- retained `.part`
- HTTP Range resume
- explicit cancel cleanup
- background SHA/extract/promotion/native smoke
- VERIFYING UI

Alpha3 同时引入固定 QA 签名轨道，避免 Hosted Runner 临时 debug keystore 导致 APK 无法覆盖。

Alpha4 补齐“转写版本”UI，使同一录音的 FAST/HIGH_QUALITY 独立历史可在真机直接验证。

## 真机结论

见 `docs/STAGE_8_TEST.md`。

用户最终明确：

**“Stage 8 真机测试通过”**

## 边界

Stage 9 才实现说话人分离。

Stage 10 才实现 transcript ↔ playback seek/highlight synchronization。

Stage 13 才执行真实 30min/1h/2h 长录音、RAM/CPU/温度 soak。

Stage 16 本地实时转写应复用 Stage 8 的 `StreamingAsrSession` 与 Small Bilingual contract。
