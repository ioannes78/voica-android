# Stage 17 — Fully Offline Intelligence

状态：**IN PROGRESS / 17.1–17.3 IMPLEMENTED BASELINE / 17.3 REAL-DEVICE QA PENDING**

修订日期：2026-10-28

Stage 17 位于 Stage 16 Final Freeze/Handoff 之后。

目标：在不依赖在线 AI Provider 的前提下，让闻知 WENZHI 在 Android 设备本地完成转写内容的总结、长文本理解、问答、检索和原始语音证据回链。

本文件是 Stage 17 的路线与当前事实摘要。实现状态必须以 GitHub 当前源码、CI、Gate/Freeze 文档为准；聊天记录中的临时版本号不是生产事实源。

---

## 17.0 核心原则

1. 核心 AI 工作流必须离线；
2. 不提供 cloud AI fallback；
3. 不因本地模型失败自动上传文本或音频；
4. Local LLM 默认消费结构化 transcript，而不是再次直接理解整段音频；
5. canonical transcript/audio timeline 继续是唯一可跳转时间真值；
6. LLM 不负责生成真实 timestamp；
7. 长录音通过 deterministic hierarchical processing / retrieval 解决，不依赖一次性塞满 context；
8. 单一重型本地 AI runtime owner，避免并行模型抢占内存；
9. transcript/source/model/prompt/schema lineage 必须可追溯；
10. 用户编辑与 Evidence 坐标属于应用事实，不由 LLM 重建。

产品原则：

> **LLM 负责语义，闻知负责坐标。**

---

# Stage 17.1 — Offline LLM Runtime Foundation

状态：**IMPLEMENTED / FROZEN FOUNDATION FOR 17.2+**

当前 Stage 17 已冻结并继续复用以下本地 LLM 基线：

- model id：`gemma-4-e2b-it-litertlm`
- model revision：`7fa1d78473894f7e736a21d920c3aa80f950c0db:r1`
- artifact：`gemma-4-E2B-it.litertlm`
- artifact SHA-256：`ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- runtime：`litert-lm 0.18.0`
- application-scoped runtime owner：`AppLocalLlmRuntime`
- backend policy：`AUTO`
- `maxNumTokens = 4000`
- `maxOutputTokens = 1024`
- speculative decoding：关闭

Stage 17.2/17.3 不创建第二个 Engine/Conversation owner，不建立多 Provider fallback，也不把 Gemma 候选静默提升进通用 production model matrix。

运行时基础继续承担：

- model install/download/verify/delete；
- revision identity；
- single active heavy inference owner；
- cancellation；
- CPU/GPU capability/fallback；
- memory/thermal protection；
- foreground execution；
- diagnostics；
- missing/corrupt model recovery。

---

# Stage 17.2 — Offline Summary V1

状态：**IMPLEMENTED / DIRECT PATH FROZEN / AUTOMATED REGRESSION PASS**

目标：完全本地生成结构化总结，不依赖旧 Online AI Provider。

输入使用 `StructuredTranscriptInput`，包含：

- transcript text；
- canonical evidence/sample range；
- speaker identity/display name；
- transcription/revision/alignment/canonical asset lineage；
- stable evidence IDs。

Direct V1 当前冻结 contract：

- prompt version：`170204`
- conservative direct prompt budget：`2528` estimated tokens
- runtime max tokens：`4000`
- output reserve：`1024`
- response schema 本地 constrained decoding；
- strict local decode + Evidence allowlist；
- 首次结构失败最多一次 bounded repair；
- 无 cloud fallback。

输出继续使用统一 `AiSummaryResult`，并进入既有 Current/Candidate/History、人工 Revision、复制/分享/TXT/Markdown、Evidence playback 与搜索体系。

---

# Stage 17.3 — Long Transcript Intelligence

状态：**IMPLEMENTED / AUTOMATED REGRESSION PASS / QA APK + REAL-DEVICE QA PENDING**

详细自动化 Gate 与真机清单：

- `docs/STAGE_17_3_LONG_TRANSCRIPT_INTELLIGENCE_AUTOMATED_GATE.md`

长录音不依赖一次性全文 prompt。当前正式链：

`StructuredTranscriptInput`
→ deterministic Direct/LONG budget routing
→ deterministic MAP chunks
→ serial MAP
→ serial hierarchical REDUCE
→ FINAL
→ existing `AiSummaryResult` product surface

冻结 contract：

- pipeline version：`173001`
- planner version：`173001`
- MAP prompt version：`173101`
- REDUCE prompt version：`173201`
- FINAL prompt version：`173301`
- intermediate schema version：`1`
- planner target：`2048` estimated prompt tokens
- hard ceiling：`2528` estimated prompt tokens

实现边界：

- Direct 与 Long 共用同一个 `OfflineSummaryCoordinator` 与 `AppLocalLlmRuntime`；
- MAP/REDUCE/FINAL 严格串行，不允许 parallel MAP；
- deterministic chunk/source identity + SHA-256 source digest；
- MAP/REDUCE 只有通过本地 schema/ref 校验后才成为 durable checkpoint；
- Room v17 的 `offline_summary_tasks` / `offline_summary_nodes` 是长任务业务状态真相；
- process death 后 startup reconciliation 不自动调用 LLM；
- Resume 只能由用户显式触发，并重新校验输入、模型、pipeline/planner/prompt/schema 与 checkpoint graph；
- VALIDATED checkpoint 原样保留，只重试未完成/失败/中断节点；
- Cancel/Completed 执行确定性 checkpoint cleanup；
- 产品 UI 显示结构化 `规划 / 分段分析 X/N / 层级合并 / 最终校验` 进度；
- Long FINAL 复用 Direct 的 summary completion / Evidence / Current-Candidate / Revision / Export / Search 产品链，不建立 Long-only 结果体系。

自动化关键证据：

- P3：Android PR CI `#2790` — SUCCESS
- P4：Android PR CI `#2792` — SUCCESS
- Resume product wiring：Android PR CI `#2799` — SUCCESS
- P5：Android PR CI `#2801` attempt 2 — SUCCESS
- P6：Android PR CI `#2802`、`#2803` — SUCCESS
- P7 runtime boundary：Android PR CI `#2804` — SUCCESS

下一步：QA APK Gate → 单项真机 QA → Stage 17.3 Freeze/Handoff。真机通过前不得标记 CLOSED。

---

# Stage 17.4 — Offline Q&A + Audio Citation

状态：**RESERVED / NOT STARTED**

目标：用户可以针对当前录音/转写提问，并获得可点击回到原始语音位置的证据。

正式链路：

`Question`
→ `Local Retrieval`
→ `candidate transcript ranges`
→ `Gemma answer/rerank`
→ `answer + evidence IDs`
→ `Voica DB canonical coordinates`
→ `player seek`

LLM 输出只引用应用提供的稳定 evidence identity，不直接提供可信 timestamp。

不得：

- 接受模型凭空生成的时间作为 seek truth；
- 把无 evidence 的回答伪装成已证实事实；
- 因问答失败访问云端；
- 为问答重新上传/重新解码整段音频。

UI 应明确显示回答、来源片段、speaker、时间、原文 excerpt 与点击播放入口。

---

# Stage 17.5 — Local Retrieval / RAG

状态：**RESERVED / NOT STARTED**

首版优先采用轻量本地 retrieval，不立即引入独立向量数据库。

优先评估：

- SQLite FTS5；
- normalized keyword/phrase matching；
- speaker filter；
- time-range filter；
- neighboring segment expansion；
- lexical score；
- Gemma rerank；
- Top-K evidence packing。

目标链：

`Question`
→ `FTS / lexical candidates`
→ `neighbor expansion`
→ `Gemma rerank`
→ `Top-K evidence`
→ `answer`

只有在真实测试证明关键词召回对语义改写/同义表达不足时，才进入小型本地 embedding model 选型。

如果未来引入 embedding：

- 必须完全本地；
- vector identity 必须绑定 source transcript digest；
- 不将向量作为时间真值；
- 不自动引入云向量数据库。

---

## 数据与 schema 策略

当前 Stage 17.3 checkpoint 基线为 Room **v17**。

已落地的新增持久化概念包括：

- local summary run/version；
- Long task / node checkpoint；
- source/input digest；
- prompt/pipeline/planner/schema versions；
- validated intermediate payload；
- stable evidence reference。

Stage 17.4/17.5 若需要 Q&A thread、retrieval index metadata 或 embedding identity，必须在对应阶段重新设计与迁移，不得提前污染 17.3 schema。

---

## 隐私边界

Stage 17 核心链不得把 transcript、summary、prompt、question、audio 或 speaker data 自动发送给第三方。

模型首次下载可以联网；模型安装完成后的 summary/Q&A/retrieval 目标是可在飞行模式完成。

17.2/17.3 正式 inference/checkpoint 路径由 CI 静态阻止 HTTP/cloud/API-key/upload/WorkManager fallback 依赖。

---

## Stage 17 验收方向

已进入/完成的 17.1–17.3 验收：

- local model/runtime identity；
- Direct offline summary；
- Long hierarchical summary；
- cancellation/resume；
- durable checkpoint；
- process/lifecycle reconciliation；
- Current/Candidate/History compatibility；
- Revision/export/evidence compatibility；
- no cloud fallback；
- single runtime owner。

17.3 仍待 QA APK + 真机验证：

- 飞行模式 Direct/Long；
- 多 chunk / 多层 reduce；
- process kill + explicit resume；
- cancel cleanup；
- evidence click → correct audio seek；
- memory/thermal/stall observation。

17.4/17.5 后续再验收：

- Q&A single/multiple evidence；
- insufficient evidence；
- local retrieval quality；
- embedding（仅在需要时）。

Stage 17.3 Freeze 后才继续 17.4；Stage 17 全部目标完成后再进入后续本地智能阶段。
