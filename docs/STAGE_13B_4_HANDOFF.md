# Voica Stage 13B.4 Final Handoff

状态：**FINAL / ACCEPTED**

日期：2026-10-07

下一子阶段：**Stage 13B.5 — Durable AI Summary / Process Recovery**

## 1. 接管原则

GitHub 当前仓库始终是唯一事实来源。

Stage 13B.5 开始任何编码前，必须重新核验：

- `main` HEAD
- PR #17 open/merge 状态
- `stage13b-stability-background-longrecording` 当前 HEAD
- 最新 Android PR CI
- `docs/STAGE_13B_4_DURABLE_MODEL_INSTALL.md`
- `docs/STAGE_13B_4_FREEZE.md`
- `docs/STAGE_13B_4_HANDOFF.md`
- `docs/STAGE_13B_LONG_TASK_AUDIT.md`
- `docs/STAGE_13B_BACKGROUND_EXECUTION_DESIGN.md`
- `docs/STAGE_13B_B1_QA.md`
- Room schema
- production model-channel 当前 HEAD / production manifest
- AI Summary coordinator / DAO / startup recovery / provider request 当前源码

聊天记录只能作为交接线索。

## 2. Stage 13B.4 最终接受基线

最终功能 / 真机 QA 基线：

`fb945496975695a99c80c38064c089466d87a9b8`

最终 QA：

- versionCode 58
- base versionName `0.13.1-stage13b-model-install-qa4`
- QA versionName `0.13.1-stage13b-model-install-qa4-qa`
- QA package `io.github.ioannes78.voica.qa`
- Room v7
- arm64-v8a
- Android PR CI #799 / run `37559018140` — SUCCESS
- Artifact ID `11455389528`
- Artifact digest `sha256:9060375ab886a6ad78309b0cc54ba4563407569ae463e374592a75a785a019dc`
- Accepted APK size `56,647,536` bytes
- Accepted APK SHA-256 `e2dff19c12202de8f0d2abadbd0dde80eb09bbfdb13082fe29dd1cf0dec0e378`

用户最终明确：

**“测试通过”**

Stage 13B.4 因此正式冻结。

## 3. Durable Model Install 不得回归的事实

Stage 13B.5 及后续阶段不得无明确需求改变：

- durable journal 位于 app-private no-backup storage
- `DOWNLOAD → VERIFY → EXTRACT → FILE_VERIFY → RUNTIME_VALIDATE → ATOMIC_ACTIVATE → READY`
- operation truth 与 model/filesystem truth 分离
- candidate identity 冻结 exact descriptor + manifest digest
- executor generation 防 stale callback
- API 34+ manual large download 使用 UIDT/JobScheduler 路径
- API 26–33 使用 foreground WorkManager dataSync fallback
- AUTO_SMALL 使用 WorkManager
- post-download processing 使用 durable finalizer
- old active model 在新 candidate fully validated/activated 前继续可用
- complete package / static candidate 可恢复复用
- historical READY 不能冒充模型当前仍存在

## 4. 用户操作语义不得回归

固定：

- incomplete download：取消下载
- complete package 后的 verify/extract/runtime validation：暂停安装
- interrupted：继续安装

暂停不得删除完整下载包。

通知栏与 App 页面必须使用同一 durable control path；不得恢复为两套独立判断。

## 5. Force Stop / recent-task swipe 不得回归

Force Stop：

- `ApplicationExitInfo.REASON_USER_REQUESTED`
- reopen 后 explicit resume
- 不静默重新发起大模型网络下载

Recent-task swipe：

- 不依赖普通 `Service.onTaskRemoved()`
- MANUAL operation 绑定 ownerTaskId
- `ActivityManager.appTasks` 检查 task presence
- owner task 消失 → INTERRUPTED + requiresUserResume
- Home / app switch / lock 不触发误暂停
- startup recovery 在 executor reconciliation 前兜底检查 ownership

目标 Android 12 / realme 真机已通过。

## 6. Notification / error contract

模型安装 notification 是 journal projection：

- 实际 download percent + bytes
- phase 实时同步
- cancel / pause / continue 与当前 durable state 一致
- terminal state 正确清除或收口

用户可见模型安装错误默认简体中文；raw system exception 只保留诊断用途。

## 7. Production model channel

Stage 13B.4 中经用户明确授权完成 production v7 promotion。

当前冻结基线：

- repo `ioannes78/voica-model-channel`
- main `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production commit `6f74d89f02a23a46a489de79757f0ed07d0daea2`
- manifestVersion 7
- manifestDigest `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- validator PASS

Stage 13B.5 不得顺带修改 model channel。

## 8. Room / frozen product baseline

Room 仍为 v7。

继续保护 Stage 13A/13B 已冻结事实：

- Current Effective Transcription / Summary lifecycle
- immutable original ASR / speaker timeline facts
- revision lineage
- canonical absolute sample timeline
- SearchDocument/FTS 可重建派生边界
- Mini Player / MediaSession lifecycle
- BLE B1 QA2-R1 accepted behavior
- transcription / diarization / AI Summary task deep links
- Paragraph Organizer V2

## 9. Stage 13B.5 目标边界

Stage 13B.5 只收口 **AI Summary durable execution / process recovery**。

开始前重新核验现有 AI Summary contract；已知设计原则是：

- Room 继续作为 AI Summary business truth
- scheduler/executor 不取代 Room 状态
- existing provider/model/template/transcription lineage/checkpoint 必须保持
- process death 后必须区分“provider request 明确未发出”与“provider request 是否已被远端接受未知”
- ambiguous provider-call state 不得静默自动重发，避免重复 LLM 请求/计费/重复总结
- existing `resumeInterrupted(summaryId)` 语义必须重新核验后复用或扩展
- WorkManager retry 不得自动等同于重新发送非幂等 provider request
- completed/cancelled/failed terminal state 不得被 stale worker callback 复活

Stage 13B.5 不重新设计 AI Summary 产品模板、Provider 体系或 Structured Output 业务规则。

## 10. Stage 13B.5 建议验收矩阵

至少覆盖：

- 正常总结完成
- App 切后台 / 锁屏
- provider request 前 process death
- request 已发出但 response 未持久化时 process death
- response 已得到但 commit 前 process death
- user cancel
- temporary network failure
- provider 4xx / 5xx
- duplicate enqueue
- stale executor generation
- force stop / reopen
- restart 后明确手动 Resume
- completed terminal state 不重复请求 provider
- Current Effective Transcription / Revision lineage 不漂移

## 11. Remaining Stage 13B gates

Stage 13B.4 结束后剩余：

1. Stage 13B.5 — Durable AI Summary / Process Recovery
2. Stage 13B.6 — Cross-task Recovery Matrix Closure
3. Stage 13B.7 — Real 30/60/120 Minute Stress Campaign
4. Stage 13B.8 — Final Regression / Stage 13B Final Freeze / Handoff

Stage 13B 整体 Final Freeze/Handoff 之前不得直接进入 Stage 13C。

Stage 13C 开始时还必须读取 `docs/ROADMAP_STAGE_13C_PLUS.md` 当前 `main` 版本。

## 12. PR / merge 边界

PR #17 当前继续作为 Stage 13B 开发 PR。

Stage 13B.4 子阶段 Freeze/Handoff **不等于合并 PR #17**。除非用户另行明确授权，不得因本 Handoff 自动 merge、close PR 或开始 Stage 13B Final Freeze。
