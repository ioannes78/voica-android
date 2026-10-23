# Stage 17 — Fully Offline Intelligence

状态：**RESERVED / APPROVED DIRECTION / NOT STARTED**

修订日期：2026-10-23

Stage 17 位于 Stage 16 Final Freeze/Handoff 之后。

目标：在不依赖在线 AI Provider 的前提下，让 Voica 在 Android 设备本地完成转写内容的总结、长文本理解、问答、检索和原始语音证据回链。

本文件只冻结路线，不授权创建 Stage 17 implementation branch，也不授权在 Stage 16 完成前提前编码。

---

## 17.0 核心原则

1. 核心 AI 工作流必须离线；
2. 不提供 cloud AI fallback；
3. 不因本地模型失败自动上传文本或音频；
4. Local LLM 默认消费结构化 transcript，而不是再次直接理解整段音频；
5. canonical transcript/audio timeline 继续是唯一可跳转时间真值；
6. LLM 不负责生成真实 timestamp；
7. 长录音通过 retrieval / hierarchical processing 解决，不依赖一次性塞满 context；
8. 当前 pre-release 阶段优先干净架构，不为旧开发数据维护 migration compatibility。

产品原则：

> **LLM 负责语义，Voica 负责坐标。**

---

# Stage 17.1 — Offline LLM Runtime Foundation

状态：**RESERVED / NOT STARTED**

首选模型方向：**Gemma 4 E2B instruction-tuned**。

当前 Google AI Edge Gallery 已列出 Android 可部署的 `Gemma-4-E2B-it` LiteRT-LM 模型。Stage 17 开始时仍必须重新核验并冻结当时实际可用的 artifact/runtime/revision，不把当前聊天或路线文档中的临时版本号直接当作 production contract。

Stage 17 开始时必须重新核验并冻结：

- 官方可用 Android artifact；
- LiteRT-LM/runtime 版本；
- model revision/commit；
- quantization；
- model package size；
- context/token limits；
- CPU/GPU accelerator support；
- Android min/recommended RAM；
- license/redistribution；
- 真机 RAM/PSS、TTFT、tokens/s、thermal。

不得把聊天记录中的临时版本号直接写成 production contract。

运行时至少设计：

- model install/download/verify/delete；
- atomic promotion / revision identity；
- single active heavy inference owner；
- cancellation；
- session lifecycle；
- CPU/GPU capability/fallback；
- memory pressure protection；
- thermal protection；
- foreground/background behavior；
- diagnostic metrics；
- corrupted/missing model recovery。

首版不建立多 LLM Provider/多模型矩阵。优先把单一 Gemma 4 E2B 路径做稳。

---

# Stage 17.2 — Offline Summary V1

状态：**RESERVED / NOT STARTED**

目标：完全本地替代旧 Online AI Summary。

输入优先使用结构化 transcript：

- text；
- canonical start/end；
- speaker identity/display name；
- paragraph/sentence boundaries；
- transcription/model lineage 中对总结必要的最小信息。

首版输出至少包括：

- 标题；
- 摘要；
- 关键点；
- 决策；
- 待办事项；
- 会议纪要/结构化内容。

输出 contract 应结构化、可验证，避免只依赖自由文本解析。

要求：

- inference failure 不破坏 transcript；
- 可以取消；
- summary version 可独立持久化；
- 重新生成不覆盖人工保留版本；
- 本地模型/revision/config lineage 可追溯；
- 不存在在线 fallback。

现有 summary UI 若仍适合可以复用；仅为在线 Provider 存在的 UI/setting 不恢复。

---

# Stage 17.3 — Long Transcript Intelligence

状态：**RESERVED / NOT STARTED**

长录音不得依赖一次性全文 prompt。

目标链：

`Structured Transcript`
→ `semantic/time blocks`
→ `local block summaries`
→ `intermediate summaries`
→ `final reduce`

需要设计：

- deterministic block identity；
- token/context budgeting；
- speaker/timestamp boundary-aware chunking；
- completed block cache；
- cancellation/resume；
- retry only failed/pending block；
- final reduce lineage；
- transcript change 后的精确 cache invalidation；
- bounded memory。

对于数小时录音，已完成 block 不应因 App 短暂退出而全部从零重算，前提是 source digest 和 model/config identity 没有变化。

---

# Stage 17.4 — Offline Q&A + Audio Citation

状态：**RESERVED / NOT STARTED**

这是 Stage 17 的核心产品能力之一。

目标：用户可以针对当前录音/转写提问，并获得可点击回到原始语音位置的证据。

示例：

用户：`测试版什么时候发布？`

结果：

- answer：`暂定周四发布，前提是服务器迁移完成。`
- evidence：稳定 segment/range IDs
- UI：`▶ 12:08`、`▶ 12:25`

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

UI 应明确显示：

- 回答；
- 一个或多个来源片段；
- speaker；
- 时间；
- 原文 excerpt；
- 点击播放。

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

- 仍必须完全本地；
- vector identity 必须绑定 source transcript digest；
- 不将向量作为时间真值；
- 不自动引入云向量数据库。

---

## 数据与 schema 策略

Stage 17 可以为了干净架构直接重构 pre-release Room schema。

不要求兼容 Stage 16 之前设备里的开发测试数据。

可能需要的新持久化概念包括：

- local LLM model/revision state；
- local summary run/version；
- block summary cache；
- source digest；
- Q&A thread/message；
- evidence reference；
- retrieval index metadata。

正式 entity/table 设计必须在 Stage 17 requirements/development-plan 阶段按当时真实 schema 决定。

---

## 隐私边界

Stage 17 核心链不得把：

- transcript；
- summary；
- prompt；
- question；
- audio；
- speaker data

自动发送给第三方。

模型首次下载可以联网；下载完成后的 summary/Q&A/retrieval 应可在飞行模式完成。

---

## Stage 17 验收方向

至少验证：

- clean install + model install；
- offline summary；
- 飞行模式 summary；
- 长录音 hierarchical summary；
- cancellation/resume；
- transcript edit 后 cache invalidation；
- Q&A single evidence；
- Q&A multiple evidence ranges；
- evidence click → correct audio seek；
- answer with insufficient evidence；
- no-network behavior；
- OOM/memory pressure；
- thermal；
- CPU/GPU fallback；
- process/lifecycle recovery；
- model missing/corrupt；
- no cloud request during core AI workflow。

Stage 17 完成后再进入 Stage 18 本地语音质量/延迟收敛。
