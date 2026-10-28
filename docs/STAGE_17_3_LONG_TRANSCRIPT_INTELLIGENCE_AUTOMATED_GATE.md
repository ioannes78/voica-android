# Stage 17.3 — Long Transcript Intelligence Automated Gate

状态：**IMPLEMENTED / AUTOMATED REGRESSION PASS / REAL-DEVICE QA PENDING**

修订日期：2026-10-28

本文件记录 Stage 17.3 的自动化关闭边界。它不是 Freeze/Handoff；在 QA APK 与真机回归通过前，Stage 17.3 仍不得标记 CLOSED。

---

## 1. 目标

Stage 17.3 在 Stage 17.2 Direct Offline Summary 基线上增加确定性的长转写分层总结，并保持单一产品/运行时/持久化体系：

`StructuredTranscriptInput`
→ Direct 预算判定
→ `LONG` 路由
→ deterministic MAP chunks
→ serial MAP
→ serial hierarchical REDUCE
→ FINAL
→ 既有 `AiSummaryResult` / Evidence / History / Revision / Export / Search 产品面。

短文本仍走 Stage 17.2 Direct 路径，不因 Stage 17.3 改写。

---

## 2. 冻结运行时与模型身份

Stage 17.3 不引入新的 LLM runtime owner 或 Provider 矩阵。

- model id：`gemma-4-e2b-it-litertlm`
- model revision：`7fa1d78473894f7e736a21d920c3aa80f950c0db:r1`
- artifact：`gemma-4-E2B-it.litertlm`
- artifact SHA-256：`ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- runtime：`litert-lm 0.18.0`
- application runtime owner：`AppLocalLlmRuntime`
- backend policy：`AUTO`
- `maxNumTokens = 4000`
- `maxOutputTokens = 1024`
- `topK = 64`
- `topP = 0.95`
- `temperature = 1.0`
- speculative decoding：关闭

禁止：第二个 Engine/Conversation owner、Long 专用 runtime、云端 fallback、HTTP/API key、WorkManager 或第二任务系统。

---

## 3. 冻结 Direct / Long Contract

### 3.1 Direct 保持不变

- Direct prompt version：`170204`
- Direct conservative prompt budget：`2528` estimated tokens
- 最多一次结构 repair
- response schema / evidence allowlist / strict decoder 保持 Stage 17.2 contract

### 3.2 Long

- pipeline version：`173001`
- planner version：`173001`
- MAP prompt version：`173101`
- REDUCE prompt version：`173201`
- FINAL prompt version：`173301`
- intermediate schema version：`1`
- planner soft target：`2048` estimated prompt tokens
- hard prompt ceiling：`2528` estimated prompt tokens

Long hard ceiling 必须与 Direct prompt budget 相同；任何 MAP/REDUCE/FINAL 请求都不得绕过该边界。

---

## 4. 执行与恢复语义

1. Direct/Long 共用一个 application-scoped `OfflineSummaryCoordinator`。
2. Long MAP、REDUCE、FINAL 严格串行；禁止 coroutine fan-out / parallel MAP。
3. 每个 structured node 首次输出失败时最多一次 repair。
4. Room v17 的 `offline_summary_tasks` / `offline_summary_nodes` 是长任务业务状态真相。
5. 只有通过本地校验的 MAP/REDUCE payload 才可成为 durable checkpoint。
6. process death / startup reconciliation 只把活动任务收敛为可恢复状态，不启动模型、不 prepare inference。
7. Resume 必须由用户显式触发，并重新校验 transcript/source digest、model identity、pipeline/planner/prompt/schema versions 与 checkpoint graph。
8. Resume 保留 VALIDATED checkpoint，只把未完成/失败/中断节点恢复为可执行状态。
9. Cancel 清理长任务节点；Completed 清理中间节点并保留终态任务审计行。

---

## 5. 产品兼容边界

Long FINAL 不创建第二套结果模型。

最终结果必须复用：

- `OfflineSummaryPersistenceRepository.complete()`；
- `AiSummaryResult` / `AiSummaryEvidenceEntity`；
- `Stage12CContentRepository.onAiSummaryCompleted()`；
- Current / Candidate / History；
- 人工 Revision；
- Copy / Share / TXT / Markdown 的统一 `TextContentDocument`；
- Evidence Ref → canonical sample range → player seek；
- 既有搜索/索引重建路径。

Stage 17.3 P6 已增加 Long FINAL 兼容回归，验证长路径不会分叉成 Long-only UI 或 Long-only export/evidence 体系。

---

## 6. 自动化 Gate 证据

已通过的关键 Fast PR Gate：

- P3 durable checkpoint/resume：Android PR CI `#2790` — SUCCESS
- P4 Direct/Long serial coordinator integration：Android PR CI `#2792` — SUCCESS
- explicit Resume product wiring：Android PR CI `#2799` — SUCCESS
- P5 structured progress + Resume surface：Android PR CI `#2801` attempt 2 — SUCCESS
- P6 Long completion compatibility：Android PR CI `#2802` — SUCCESS
- P6 Revision/export compatibility：Android PR CI `#2803` — SUCCESS
- P7 long-summary runtime boundary：Android PR CI `#2804` — SUCCESS
- P7 final unified product/evidence boundary：Android PR CI `#2809` — SUCCESS

Fast PR Gate continues to run the full repository unit/debug compile matrix plus the Stage 17 boundary script and FSMN candidate runtime boundary。

---

## 7. QA APK / 真机回归清单

P7 自动化通过后才进入 QA APK Gate。真机按以下顺序执行，一项失败即停止并回到开发分支修订：

1. **Direct sanity**：短转写仍走 Direct，结果/证据/历史与 Stage 17.2 一致。
2. **Long route**：超 Direct 预算输入进入 Long；UI 显示“规划 → 分段分析 X/N → 层级合并 → 最终总结”。
3. **Serial checkpoint**：至少两个 MAP chunk；已完成 chunk 在后续失败后保持 VALIDATED。
4. **Explicit Resume**：失败/中断后显示“继续”；恢复时跳过 VALIDATED chunk，仅重试未完成节点。
5. **Process restart**：Long 执行中杀进程/重新打开 App；启动后不得自动推理，必须显式继续。
6. **Cancel**：Long 执行中取消；FGS 释放，节点清理符合 contract，可重新生成新总结。
7. **Current/Candidate/History**：首次结果成为 Current；重新生成成为 Candidate；“使用”后切换 Current。
8. **Revision/export**：人工编辑、修订历史、复制、分享、TXT、Markdown 均可使用 Long FINAL。
9. **Evidence playback**：Long FINAL 的 evidence 点击回到正确 canonical audio 位置。
10. **Offline**：模型已安装后在飞行模式完成 Direct 与 Long，不出现云端请求或 provider fallback。
11. **资源观察**：长任务连续 MAP/REDUCE 时无第二 runtime、无并行节点；记录明显 OOM/thermal/stall 异常。

---

## 8. Freeze 条件

只有同时满足以下条件才允许创建 Stage 17.3 Freeze/Handoff：

- P7 final Fast PR Gate SUCCESS；
- QA APK Gate SUCCESS；
- 上述真机回归全部 PASS；
- PR #37 仍保持单一事实链且无未解释红灯；
- Freeze/Handoff 文档记录最终 HEAD、CI run、QA APK 与真机结果。

在此之前不得合并为 Stage 17.3 CLOSED。
