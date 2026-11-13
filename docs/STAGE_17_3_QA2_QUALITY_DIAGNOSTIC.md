# Stage 17.3 — QA2 Long Summary Quality Diagnostic

状态：**QA1 PASS / QA2 ROUTE PASS + QUALITY FAIL / BLOCKED / DIAGNOSTIC APK REQUIRED**

修订日期：2026-10-30

## 1. 真实真机结果

### QA1 — Direct sanity

- 结果：**PASS**
- 短转写可继续使用 Stage 17.2 Direct 路径并正常生成离线总结。

### QA2 — Long route

- Long 路由：**PASS**
- Long pipeline 可完成并生成最终总结：**PASS**
- 长文本语义覆盖：**FAIL**
- 语义分类准确性：**FAIL**
- Overall：**BLOCKED**

真机黄金样本为一段关于 AI 趋势的多人对话。原文覆盖 AI 产业爆发、算力、模型能力、企业采用、摩根大通、医疗 AI、数据中心与能源、AI Agent、多模态、Physical AI、中美竞争以及 AI 收益/风险平衡等多个主题。

实际 Gemma 4 E2B Long FINAL 几乎只保留全文开头“讨论 AI 趋势、变化很快、梳理报告和图表”的内容，并把主持人的节目目标误分类成“待办事项”。因此该结果不是轻微质量波动，而是严重的长文本语义覆盖失败。

## 2. 当前判断

现有代码会在 MAP 完成后验证 MAP 节点数与确定性分块数一致，因此“只有 MAP1 被执行”并非首要假设；仍需真机证据区分以下情况：

1. 后续 MAP 输入错误/重复/缺失；
2. MAP 输入正确，但 MAP 输出已经大量丢失信息；
3. MAP 输出完整，但 REDUCE 过度压缩；
4. REDUCE 仍完整，但 FINAL 偏向前部节点或错误分类。

当前中间 schema 的容量约束（最多 6 items、synopsis 96 chars、item text 72 chars）以及 MAP/REDUCE 都要求“压缩/去重”，是高风险点，但在获得中间节点真机输出前不得直接把它当作唯一根因。

## 3. Diagnostic APK 目的

诊断 APK **不得改变 Stage 17.3 的路由、模型、参数、结构化 schema、Room checkpoint、串行执行、resume、Evidence、History 或 FINAL 产品契约**。

仅增加旁路观测：

- PLAN：分块数量、source unit 范围、source refs、估算 prompt tokens；
- 每个 MAP：完整 prompt 输入 + 通过结构校验后的输出；
- 每层 REDUCE：完整 prompt 输入 + 通过结构校验后的输出；
- FINAL：完整 prompt 输入 + 最终 JSON 输出。

Long FINAL 成功后，诊断文本自动导出到：

`Download/WENZHI/WENZHI-Stage17.3-LongSummary-Diagnostic-<timestamp>.txt`

诊断文件包含用户转写文本与本地模型输出，只用于本轮 QA 根因分析。

## 4. 下一真机动作

1. 安装 Stage 17.3 Quality Diagnostic APK；
2. 对同一黄金样本重新执行一次离线 Long Summary；
3. 等待最终总结完成；
4. 在系统“下载 / WENZHI”目录找到最新 `WENZHI-Stage17.3-LongSummary-Diagnostic-*.txt`；
5. 将该 txt 文件提供给 ChatGPT；
6. 按 MAP → REDUCE → FINAL 层级定位首次发生信息覆盖坍塌的位置；
7. 根因确认后再进入 Stage 17.3 Quality Revision，不继续 QA3。

## 5. Gate

在 QA2 长文本语义覆盖修复并重新真机 PASS 前：

- QA3 及后续真机项暂停；
- Stage 17.3 不得 Freeze/Handoff；
- PR #37 不得合并；
- 不得把“能生成结果”记为 QA2 PASS。
