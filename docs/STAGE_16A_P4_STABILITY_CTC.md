# Stage 16A P4 — Stability Layer + Large CTC

状态：**CORE IMPLEMENTATION / CI PENDING**

## 1. 本轮目标

在 P3 `StreamingAudioEvent → VAD → Streaming ASR → PARTIAL/FINAL` 基础上补齐 P4 的稳定文本层、canonical token timing 映射与 Chinese Large CTC 生命周期覆盖。

本轮仍不实现 CT-Transformer 标点、Pause/Resume/reconnect 产品恢复、正式 realtime UI、Mini Live Bar 或后台通知；这些继续留在 P5/P6。

## 2. Hypothesis Stabilizer

新增 `RealtimeHypothesisStabilizer`，作为 ASR processor 下游的 engine-independent stability layer：

- native PARTIAL 仍保留为 mutable hypothesis；
- 默认要求连续 3 轮 native partial 共同前缀后才提升为 STABLE；
- duplicate partial 可以作为稳定性证据，但不重复向下游发布完全相同的 PARTIAL；
- STABLE 正常单调增长，不因 ordinary partial churn 回退；
- continuity 切换后清空稳定证据；
- FINAL 为权威最终结果，可修正此前 stable prefix；
- duplicate FINAL 在同一 continuity 内抑制，避免重复持久化。

STABLE 仅代表确认前缀；最新完整 native hypothesis 仍以 PARTIAL 事件提供，供后续 UI 组合 `stablePrefix + mutableTail`。

## 3. Token timing

新增 recognizer-relative token → canonical epoch sample mapping：

- 基于 `recognizerBaseSampleIndex`；
- 不使用 wall clock；
- 不 clamp 超界 token；
- timing 不单调、offset 超过已接收 canonical 样本范围等异常时整组 timing 丢弃，而不是伪造位置。

## 4. Chinese Large CTC

Large CTC 继续使用现有 `SherpaStreamingZipformerCtcEngine` / `OnlineZipformer2CtcModelConfig` true-streaming 实现，不恢复 Large Transducer。

P4 增加与 Small 同等级的核心 session lifecycle 测试：

`openSession → acceptSamples → decode PARTIAL → finishInput FINAL → reset → decode → close`

并校验 PCM16 normalization、token timing 与 native resource close。

## 5. 后续

P5 才把 stabilizer 接入正式 product coordinator，并加入：

- FINAL/STABLE punctuation policy；
- Pause/Resume；
- discontinuity / decoder recovery；
- TRANSPORT_CLOSED / reconnect；
- TYPE3/device recording end；
- durable FINAL / media segment recovery。
