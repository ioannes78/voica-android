# Stage 16B Gate A — Final VAD Decision

状态：**TECHNICAL PASS / FINAL PRODUCT POLICY REVISED**

日期：2026-10-20

## 1. 用户最终决策

Stage 16B Local File ASR 的 VAD 产品矩阵最终冻结为：

`FSMN VAD INT8 (primary) → Silero VAD INT8 (fallback)`

同时明确：

- FSMN 成为 Local File ASR 的正式 primary VAD；
- Silero 保留为 Local File ASR 的异常/可用性 fallback；
- Silero 同时继续作为当前 Stage 16A realtime production VAD，直到 Stage 16C.1 Realtime VAD Matrix 重新选型；
- TEN-VAD 不进入 production model catalog，保留在 candidate / QA 范围；
- TEN 后续是否进入 production，取决于 deployment-license applicability 是否明确，以及 Stage 16C.1 的重新评估结果；
- Gate A 专用 `A4 QA` / `VAD QA` 用户可见入口已移除；
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
2. FSMN 不可用而 Silero available → 使用现有 Silero；
3. FSMN 与 Silero 均不可用 → 明确报缺少 Local File VAD；
4. TEN 不参与 production Local File fallback。

当前 fallback 是**模型可用性 / 激活层 fallback**。不得在已经消费部分 PCM 后静默重跑另一个 VAD 并伪装成同一次结果；若未来需要处理中途失败自动 retry，必须建立可重开 PCM、可追踪 lineage 的事务式重试契约后再实现。

## 4. Realtime 边界

本决定只改变 Local File ASR primary VAD。

Stage 16A realtime 继续使用现有 Silero binding。FSMN / TEN 是否替代 realtime Silero，只能由 Stage 16C.1 的独立三模型 realtime benchmark 决定。

## 5. TEN 状态

TEN-VAD 的 Gate A benchmark 历史证据保留，但 production promotion 已撤回。

当前策略：

- TEN 保持 candidate / QA 可见；
- TEN 不进入 production manifest；
- TEN 不作为 Local File production fallback；
- 不再让 TEN license applicability 阻塞 Stage 16B Gate B；
- 若未来要进入 production，必须重新通过明确的 license / product gate。

## 6. Gate A evidence

完整技术证据继续以：

`docs/STAGE_16B_GATE_A_VAD_BENCHMARK_REPORT.md`

为准。A1–A4 benchmark 数值不因最终 fallback 决策调整而改写。

## 7. Gate A implementation-complete 条件

当前技术状态：

- FSMN primary production wiring：完成；
- Silero fallback policy：已实现并由自动测试锁定；
- production model catalog：FSMN 正式进入，TEN 保持 candidate-only；
- FSMN product version：`1.0.0`；
- A4 QA / VAD QA 用户入口：已移除；
- realtime Silero ownership：保持不变；
- 前一版 `FSMN → TEN` production-like QA：真机 PASS；
- 修订后的 `FSMN → Silero` policy 需由 CI / 必要时 production-like smoke 最终确认。

完成修订 CI 后进入 Stage 16B Gate B：

`SenseVoice INT8 vs Fun-ASR-Nano INT8 vs Qwen3-ASR 0.6B INT8`。
