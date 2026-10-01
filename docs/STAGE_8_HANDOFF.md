# Voica Stage 8 Handoff

状态：**Stage 8 已完成 / 已真机验收 / 已冻结**

下一阶段：**Stage 9 — 说话人分离**

## 1. 接管原则

GitHub 当前 `main` 是唯一事实来源。

新 Agent 不得只依赖本文件或聊天记录，必须重新读取：

- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_8_TEST.md`
- `docs/STAGE_8_FREEZE.md`
- 本文件
- 当前 main HEAD / open PR / Actions
- 关键源码与 Room schema

## 2. Stage 8 最终实现

Freeze 前实现 HEAD：

`189ea18dd11a45227d541b133c4795e38ffa85ea`

最终 QA：

- versionCode 23
- `0.8.0-stage8-alpha4`
- QA package `io.github.ioannes78.voica.qa`
- sherpa-onnx 1.13.8
- Room 2
- arm64-v8a

Candidate CI：

`36920166807` / #248 — success

用户明确：

**“Stage 8 真机测试通过”**

## 3. 当前模块

```
:app
├─ :core:ble → :core:protocol
├─ :core:database
├─ :core:audio
├─ :core:model
├─ :core:transcript
├─ :engine:opus
├─ :engine:playback
└─ :engine:sherpa
```

## 4. Stage 8 冻结处理链

Fast：

`PcmSource → Silero → Small Bilingual → CT-Transformer → persisted transcript`

High Quality：

`PcmSource → Silero → Small Bilingual → SenseVoice → punctuation finalization → persisted transcript`

所有时间继续使用 absolute canonical PCM sample index。

## 5. Model channel

权威模型分发仓库：

`ioannes78/voica-model-channel`

production manifest：

`https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json`

Stage 8 完成时 model-channel main：

`2817873b74d87ff1d4bfa54266e082ede450ba06`

manifest digest：

`ceff5a281acd1166a75e53c9112dd88e93c9e0266d6a545651159a97ebc0154b`

模型：

- Silero VAD 2025-07-11
- Small Bilingual 2023-02-16
- CT-Transformer 2024-04-12
- SenseVoice 2024-07-17

Candidate release URL 不是正式运行依赖；只在 QA 未合并候选验收期间使用。

## 6. 转写版本

Room v2 保留全部转写历史。

同一 recording 可以存在多个：

- FAST
- HIGH_QUALITY
- interrupted/cancelled/failed 历史任务

完成版本通过“转写版本”UI列出；选择具体 transcriptionId 加载对应 segment。

Stage 9 不得改成“只留最新结果”而破坏该语义。

## 7. Stage 9 必须保留的边界

Stage 9 增加 diarization 时：

- 不改 Stage 8 canonical PCM 时间真值
- 不让 speaker engine 直接读 BLE/raw OPUS
- 继续通过 PcmSource
- speaker segment 必须可映射 absolute sample range
- 不覆盖现有 ASR Transcription 版本
- 不把 Stage 10 的播放高亮/点击 seek 提前塞进 Stage 9
- 不把 voiceprint / 跨会议 speaker identity 提前塞进 Stage 9

## 8. 已知测试债务

真实 30min/1h/2h 长录音压力、RAM/CPU/温度 soak 仍属于 Stage 13。

不要在 Stage 9 Handoff 中误写成 Stage 8 已执行。

## 9. Stage 9 接管顺序

1. 读取合并后的最新 main、HEAD、Actions。
2. 阅读 AGENTS / ROADMAP / ARCHITECTURE / Stage 8 Freeze/Handoff/Test。
3. 检查 Room v2、Transcription/Segment/Token、PcmSource 与 model contracts。
4. 执行 baseline validation。
5. 输出《Stage 9 修订需求》。
6. 等用户确认。
7. 输出《Stage 9 修订开发规划》。
8. 再次等用户确认。
9. 确认后才创建 Stage 9 development branch 和编码。

## 10. Seal

Stage 8 Freeze/Handoff 内容提交与 Seal CI 以本次最终收口过程为准。

Stage 9 开始时必须重新核对最终合并后的 main HEAD，不得把 Freeze 前实现 HEAD 当作最终 main SHA。
