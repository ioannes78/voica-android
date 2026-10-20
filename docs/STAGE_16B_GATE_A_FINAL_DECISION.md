# Stage 16B Gate A — Final VAD Decision

状态：**DECIDED / PRODUCTION WIRING IN PROGRESS**

日期：2026-10-20

## 1. 用户最终决策

Stage 16B Local File ASR 的 VAD 产品矩阵冻结为：

`FSMN VAD INT8 (primary) → TEN VAD FP32 (fallback)`

同时明确：

- Silero VAD 退出 Local File ASR VAD 路径；
- Silero VAD 继续保留为当前 Stage 16A realtime production VAD，直到 Stage 16C.1 Realtime VAD Matrix 重新选型；
- FSMN 与 TEN 进入正式模型库候选 promotion 流程；
- Gate A 专用 `A4 QA` / `VAD QA` 用户可见入口在 Gate A 收口后移除；
- Stage 16C 仍为 `RESERVED / NOT STARTED`，本决定不授权提前开始 Stage 16C。

## 2. FSMN 正式版本标识

FSMN 候选阶段使用的上游 revision：

`eeb66f413b94a33458f7edbe7f501b10f20fcc85`

不得继续作为普通用户看到的产品版本号。

正式模型描述符使用：

- displayName: `FSMN VAD INT8`
- version: `1.0.0`
- revision: `1`

完整上游 revision 继续保存在 attribution / source provenance 中。

## 3. Fallback 语义

Local File VAD 选择顺序固定为：

1. FSMN active / available → 使用 FSMN；
2. FSMN 不可用而 TEN active / available → 使用 TEN；
3. FSMN 与 TEN 均不可用 → 明确报缺少正式 Local File VAD 模型；
4. 不得静默回退到 Silero。

当前 fallback 是**模型可用性 / 激活层 fallback**。不得在已经消费部分 PCM 后静默重跑另一个 VAD 并伪装成同一次结果；若未来需要处理中途失败自动 retry，必须建立可重开 PCM、可追踪 lineage 的事务式重试契约后再实现。

## 4. Realtime 边界

本决定只改变 Local File ASR。

Stage 16A realtime 继续使用现有 Silero binding。FSMN / TEN 是否替代 realtime Silero，只能由 Stage 16C.1 的独立三模型 realtime benchmark 决定。

## 5. TEN license gate

TEN-VAD 的 production promotion / release 仍受 license applicability 门禁约束。

在该门禁明确解决前：

- 允许技术实现、CI、QA 与 draft model-channel manifest；
- 不得把 TEN promotion 视为已取得正式发布许可；
- 不得因此合并/发布包含该 production fallback 决策的最终版本。

TEN 模型保持 upstream-only 分发策略，不由 Voica 镜像模型二进制。

## 6. Gate A evidence

完整技术证据继续以：

`docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`

为准。A1–A4 benchmark 数值不因本最终决策文件而改写。

## 7. 下一门禁

完成以下事项后，Gate A 才进入 implementation-complete：

- FSMN primary / TEN fallback production wiring CI 通过；
- production-like QA APK 真机冒烟通过；
- FSMN 版本显示为 `1.0.0`；
- A4 QA / VAD QA 用户入口确认移除；
- realtime Silero 行为确认未受影响；
- TEN license applicability 在最终 merge/release 前明确解决。

之后进入 Stage 16B Gate B：

`SenseVoice INT8 vs Fun-ASR-Nano INT8 vs Qwen3-ASR 0.6B INT8`。
