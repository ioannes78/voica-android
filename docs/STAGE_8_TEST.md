# Voica Stage 8 Real-Device Test Checklist

状态：**PASSED / 用户明确验收通过**

日期：2026-10-02

最终 QA 候选：

- versionCode：23
- base versionName：`0.8.0-stage8-alpha4`
- QA versionName：`0.8.0-stage8-alpha4-qa`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：2
- implementation HEAD：`189ea18dd11a45227d541b133c4795e38ffa85ea`
- candidate CI：`36920166807` / #248 — **success**

## A. Runtime / regression

真机确认：

- sherpa-onnx native load 成功。
- Stage 1–7 BLE、设备文件、本地库、播放、seek/倍速、设备录音互锁未发现 Stage 8 回归。
- 连续多条短录音转写后 App 保持可用。
- 转写取消、重试、进程中断恢复、模型删除保护按预期工作。

## B. 模型通道与模型验收

production URL：

`https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json`

最终 production model-channel：

- main commit：`2817873b74d87ff1d4bfa54266e082ede450ba06`
- manifest validation CI：#13 — **success**
- manifest digest：`ceff5a281acd1166a75e53c9112dd88e93c9e0266d6a545651159a97ebc0154b`

已按顺序完成 candidate → 真机 → merge：

1. Small Bilingual r1
2. CT-Transformer punctuation r1
3. SenseVoice 2024 int8 r1

真机确认当前 production 可识别并使用：

- Silero VAD int8 — 2025-07-11
- Small Bilingual Zipformer zh-en — 2023-02-16
- CT-Transformer zh-en punctuation int8 — 2024-04-12
- SenseVoice zh-en-ja-ko-yue int8 — 2024-07-17

候选验收结束后已恢复 production；最终 Stage 8 不依赖临时 candidate manifest。

## C. 大模型下载与修复验收

Alpha2 暴露问题：

- CT-Transformer 下载曾在约 94% / 98% / 99% / 94% 附近出现 UI 无响应并退出。
- 旧实现异常后重新下载会从 0 开始。

Alpha3 修复并真机通过：

- 下载进度节流。
- recoverable failure 保留 `.part`。
- HTTP Range 断点续传。
- 用户主动取消确定性删除 `.part`。
- package SHA、TAR.BZ2/ZIP 解包、installed-file SHA、promotion、native smoke 后台化。
- VERIFYING UI 与可继续下载状态。
- CT-Transformer 与更大的 SenseVoice 均完成真实下载、解包、校验、激活，未复现原卡死。

## D. Fast 转写

真机通过：

`Silero VAD → Small Bilingual → CT-Transformer → persistence`

观察到：

- 中文转写正常。
- 时间段正常显示。
- 最终文本存在合理中文标点。
- 结果可重新打开。
- mixed zh/en/数字功能检查通过。

## E. High Quality 转写

真机通过：

`Silero VAD → Small Bilingual first pass → SenseVoice second pass → CT-Transformer final punctuation → persistence`

观察到：

- SenseVoice 2024-07-17 native smoke 与实际 HQ 转写通过。
- 高质量任务完成并显示 timestamped final segments。
- `TranscriptionRunState.Completed` 只在 `persistCompleted()` 成功后发布，因此完成 UI 同时证明最终数据库持久化完成。

## F. 独立转写版本

Alpha4 补齐最终 UI 验收入口：

- 同一录音可同时保留 FAST 与 HIGH_QUALITY COMPLETED 版本。
- “转写版本”显示模式、完成时间、分段数、最新/当前查看。
- 点击任一版本加载其自己的 segment 文本。
- 数据库回归测试确认两条记录 UUID 不同、segment 独立、不互相覆盖。

用户最终确认：

**“Stage 8 真机测试通过”**

## G. 自动化与长录音债务

已自动化覆盖：

- virtual 30 / 60 / 120 minute source
- bounded 4,096-sample reads
- Long absolute sample indices
- cancellation
- no whole-file PCM allocation
- Room 1 → 2 migration
- model package/file integrity
- Fast/HQ pipeline contracts
- FAST/HIGH_QUALITY independent-version regression

仍明确延期到 Stage 13：

- 真实 30 min 长录音压力
- 真实 1 h 长录音压力
- 真实 2 h 长录音压力
- 长转写 RAM/CPU/温度/ADB meminfo soak

## Acceptance

Stage 8 所有本阶段要求的真机门禁已完成，用户已明确验收通过，可以 Freeze/Handoff 并合并 main。
