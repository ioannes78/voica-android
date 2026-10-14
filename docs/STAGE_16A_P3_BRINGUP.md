# Stage 16A P3 — Small Bilingual True Streaming Bring-up

状态：**CORE IMPLEMENTATION / CI PENDING**

## 1. 本轮目标

建立 P3 的 engine-independent realtime ASR processor 与 Sherpa Silero streaming VAD adapter，使 Stage 16A 可以从 P1 `StreamingAudioEvent` 继续进入：

`StreamingChunkAssembler → Streaming VAD → Small Bilingual Streaming ASR → PARTIAL / FINAL`

本轮仍不实现 STABLE、最终标点、Large CTC 产品化、Room FINAL 写入或正式 UI；这些按 P4/P5/P6 顺序继续。

## 2. Streaming VAD

新增 `StreamingVadEngine / StreamingVadSession` contract，并基于 sherpa-onnx 1.13.8 `Vad` 提供 Silero streaming adapter。

adapter 使用 native `isSpeechDetected()` 做实时 speech-active 状态，使用已完成 native segment 作为 utterance-end 证据；segment sample offsets 保持为当前 VAD continuity-relative，不使用 wall clock。

## 3. ASR processor

`RealtimeStreamingAsrProcessor`：

- 只消费 P1 `StreamingAudioEvent`；
- chunk size 通过 config 注入，本阶段不把 40/80/100/160/200ms 中任何一个提前冻结为最终产品值；
- 用 bounded VAD pre-roll 保存 speech-detection 前的开头，避免首次 native speech-detected 延迟截掉句首；
- recognizer 只在 VAD speech-active 或已完成短 speech 需要 finalization 时启动；
- native PARTIAL 直接作为 P3 PARTIAL；
- VAD speech end / epoch boundary / discontinuity / session end 分别形成显式 finalization reason；
- `finishInput()` 每个 recognizer continuity 逻辑上只执行一次；
- token timing 保持 recognizer-relative，同时事件携带 `recognizerBaseSampleIndex`，后续只允许通过 canonical base 做映射；
- discontinuity 后创建新的 continuity，不跨 gap 拼接 token timeline；
- 不补 silence，不按 wall clock 推进媒体时间。

## 4. 当前边界

P3 core 不自动抢占 Stage 15 canonical source，也不在 App 启动时常驻运行。真正 product runtime / model-manager lease / QA probe 在 P3 下一步接入，避免与现有 Stage 15 destructive QA observer 竞争 `receive()`。

Small Bilingual 仍是 Stage 16A 默认 realtime 模型；Chinese Large CTC 留到 P4。
