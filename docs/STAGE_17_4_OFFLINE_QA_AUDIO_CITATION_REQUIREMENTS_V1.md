# Stage 17.4 — 离线问答与原始语音证据回链：需求草案 V1

**状态：DRAFT / PLANNING ONLY / NOT IMPLEMENTATION APPROVAL / NOT STARTED**
前置决策：`docs/STAGE_17_3_R17C_P2_M4B1_DEFERRAL_HANDOFF.md`
唯一实现事实源：`ioannes78/voica-android` GitHub 当前源码及当前 CI。

## 1. 目标和边界

用户针对**当前选定录音/转写版本**提出自然语言问题，在安装好本地模型后完全离线获取有证据的回答。每一条可验证的回答引用应用所提供的稳定 transcript evidence ID，点选后回到原始录音对应的 canonical sample range；LLM 只负责语义，不负责时间坐标。

本文件只建立 17.4 功能需求与设计约束；17.3 的产品真机回归与 Freeze/Handoff 仍未关闭。原 Roadmap 中「17.3 Freeze 后开始 17.4 实现」继续生效，除非用户明确另行批准调整依赖。当前只开展规划与不触发功能的源码审计。

## 2. 功能范围（首版）

### Q1. 当前录音范围的问答

- 问答默认绑定当前录音、有效的转写/人工修订版本和 canonical source identity；不得悄悄跨录音取材。
- 输入问题为空、来源未就绪、源数据已变更或模型未安装时，应给出清晰的本地错误状态，而不是返回模型猜测。
- 支持单条问题及本地会话内连续追问；会话历史如何持久化、是否另建数据库表，在实施规划前另行确认。
- 结果应包含简洁的答案、Evidence 引用、来源摘要与必要的不足证据说明；不能把模型输出视为转写事实真值。

### Q2. 证据优先、拒绝无根据回答

- 应用先建立允许引用的 candidate evidence allowlist。回答中的 evidence identity 必须属于本次 request 的 allowlist，且对应当前有效转写/source digest。
- Evidence 为零、不足以回答或 citation 验证失败时，明确显示「在本录音中未找到足够依据」，不生成伪造引用或编造时间。
- 对同一个回答中的多个事实，支持多条不同来源引用；不能把一段相关性低的 transcript 强行标记为充分证据。
- 任何生成的文字、引用和音频跳转只保存在本地；无云端 AI fallback。

### Q3. 音频回链

- 来源卡显示转写原文 excerpt、说话人、应用计算的开始时间/片段范围；点击调用既有播放器的 canonical seek 接口。
- 真实位置来自 App 的 sample range/recording timeline，不能采用 LLM 生成的 timestamp、估算值或用户可编辑的可见字符串作 seek truth。
- 引用指向已删除/变化的 source、缺失音频、无合法 sample range 时禁用跳转并解释原因。
- 人工 Revision 与模型原始转写的 anchor 映射必须可验证；不能把 presentation stable ID 当作原始时间坐标。

### Q4. 轻量本地检索（只为 17.4 Q&A 提供候选）

- 首版优先评估现有本地搜索/FTS 索引、词语/短语匹配、speaker/time-range 限制和邻近片段扩展。
- 以稳定 evidence ID、source digest、sample range、speaker 和原文 excerpt 为候选输出；根据输入长度按保守 token budget 排序和截断候选**集合**，不得静默截断源事实或混用转写版本。
- 可能需要基于 SQLite FTS5 或已有搜索基础实现最小内部接口；是否新增索引与 Room migration 在设计审计后确认，不在本需求草案中预先更改 schema。
- Stage 17.5 的跨录音 RAG、大规模 embedding、向量数据库、自动摘要检索等继续 **RESERVED / NOT STARTED**；17.4 只允许范围受控的轻量候选检索。

## 3. 运行时复用

- 复用 Stage 17.1 `AppLocalLlmRuntime` 的**单重型本地模型 owner**及模型 install/verify/activation/cancel/thermal/resource 限制，绝不引入第二个 Engine 或并行生成。
- 复用 17.2/17.3 的 `StructuredTranscriptInput`、摘要/音频 Evidence 语义；不改变 Direct/Long 已冻结 prompt/schema/budget 与 checkpoint。
- 当前结构化生成 `AUTO + responseJsonSchema -> CPU` 的保守边界继续有效，除非经单独 QA 审批重新验证 GPU。
- 不依赖 `Stage17P2M4BController`、`.m4blab`、模拟 Parent/Child Binder、授权 Grant/Journal、故障注入或生产模型通道修改。
- 不使 Q&A 成为录音、BLE、ASR、播放或总结完成的阻塞依赖；取消/退出必须遵守 single-owner release 与不确定 native ownership quarantine 原则。

## 4. 结构化结果与安全验证（拟议接口）

```text
Current recording/transcript revision
  -> canonical source snapshot + version/digest
  -> deterministic local candidate retrieval
  -> bounded evidence allowlist + context pack
  -> serial single-runtime local LLM answer
  -> strict schema / evidence ID validation
  -> local Q&A display + app-owned sample range resolver
  -> player seek
```

建议结果字段：`answerText`、`evidenceIds[]`、`insufficientEvidence`、可选 `reasonCode`。字段名仍需结合现有 `AiSummaryResult` 和 `StructuredTranscriptInput` 代码审计，不允许直接沿用总结结果作为问答数据模型。应有明确的 token budget、超限拒绝与有限重试策略，不能继承未经审查的默认大上下文。

## 5. UX/状态

- 转写阅读页面应有清晰的离线问答入口，不改变 Stage 16X 主题或逐字高亮布局。
- 问题输入、正在检索/推理、回答、来源卡、证据不足、取消、错误重试分别显示明确状态。
- 每条证据卡提供录音名称（若需要）、说话人、原文 excerpt、应用生成的时间与跳转操作。
- 失败不可隐式请求在线 Provider；不要求用户提交 API key，不上传 transcript/question/audio。
- 搜索和转写阅读不应因问答生成而明显失去可操作性。

## 6. 质量与隐私 Gate（规划，尚未测试）

1. **No-cloud**：安装完成后飞行模式完成有证据问答；静态检查不出现云端 fallback、API key、upload。
2. **Single owner**：本地生成严格串行，取消与资源回收符合已冻结 runtime gate。
3. **Correct evidence**：有效引用全部在 allowlist，且通过 canonical ID → original sample range 回链。
4. **No evidence**：无相关语句或转写变更时拒绝编造，UI 明确证据不足。
5. **Multiple citations**：多个片段、跨说话人、顺序不同的证据卡保持稳定。
6. **Edit/revision**：原始转写与人工 Revision 不串台；过期索引不得引用旧时间。
7. **Lifecycle**：退出/后台/取消后无隐式重启推理；原有录音播放不受影响。
8. **Long transcript**：上下文仅打包有证据的 Top-K 候选；不得静默丢失证据或使用错误 transcript digest。
9. **Safety**：无 M4B1 Owner/Journal/IPC Admission/FAULT_INJECTION 引用；R17C 既有 CPU/GPU containment 不回退。
10. **Device**：Android 实机 QA 仍是功能发布门槛；JVM/云端 x86_64 Lab 安装不能替代原生 LLM 真机推理。

## 7. 实施前还需决定

- 首版问答是否只针对当前单条录音（推荐），还是跨录音；后者属于 17.5，不应默认包含。
- 会话是否只保留当前页面内存，或要求本地持久化（涉及 Room migration / 版本管理）。
- 最小检索索引是否复用既有 `SearchDocumentEntity` 和 transcript search anchor，还是新建 FTS 层；必须先审计现有 DAO。
- Stage 17.3 核心功能真机 QA/Freeze 完成日期或另行批准改动路线依赖。

## 8. 下一里程碑

仅在用户审阅确认本 V1 需求及开发规划、且 Stage 17.3 Freeze/阶段依赖门槛满足后，才开始 Stage 17.4 Android 功能代码。P2-M4B1 保持 DEFERRED / NOT CLOSED；PR #37 保持 OPEN/DRAFT/UNMERGED。
