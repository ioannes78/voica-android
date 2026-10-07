# Stage 13B.4 — Durable Model Install

Status: **FINAL / ACCEPTED / FROZEN**

日期：2026-10-07

## Purpose

Stage 13B.4 将模型下载、校验、解压、运行库验证与激活从 UI/process-owned coroutine 提升为 durable、可恢复、可审计的安装管线，同时保持既有 ModelStorage / runtime validation 安全边界。

本子阶段最终真机验收已通过。Stage 13B 整体仍未结束；下一门为 Stage 13B.5。

## Frozen functional baseline

最终功能 / 真机 QA 基线：

`fb945496975695a99c80c38064c089466d87a9b8`

最终候选：

- versionCode：58
- base versionName：`0.13.1-stage13b-model-install-qa4`
- QA versionName：`0.13.1-stage13b-model-install-qa4-qa`
- QA package：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- Room schema：7
- Android PR CI：#799 / run `37559018140` — success
- Artifact：`Voica-qa-apk`
- Artifact ID：`11455389528`
- Artifact ZIP size：35,983,334 bytes
- Artifact digest：`sha256:9060375ab886a6ad78309b0cc54ba4563407569ae463e374592a75a785a019dc`
- Accepted APK size：56,647,536 bytes
- Accepted APK SHA-256：`e2dff19c12202de8f0d2abadbd0dde80eb09bbfdb13082fe29dd1cf0dec0e378`

用户于 2026-10-07 明确确认：

**“测试通过”**

CI success 仅作为自动化证据，不替代本次真机验收。

## Frozen baseline retained

Stage 13B.4 不得回归已经接受的 Stage 13B B1-QA2-R1 行为：

- BLE scan/connect R3
- reconnect/foreground ownership R5
- BLE protocol/GATT core
- compact playback page
- MediaSession / Mini Player
- deterministic recording navigation
- transcription / diarization / AI Summary task visibility and deep links
- Paragraph Organizer V2

## Durable state machine

正式安装生命周期冻结为：

`DOWNLOAD -> VERIFY -> EXTRACT -> FILE_VERIFY -> RUNTIME_VALIDATE -> ATOMIC_ACTIVATE -> READY`

Durable terminal/recovery states：

- `INTERRUPTED`
- `FAILED_RECOVERABLE`
- `FAILED_INTEGRITY`
- `FAILED_RUNTIME`
- `FAILED_CONFIGURATION`
- `CANCELLED`

边界固定：

- `ModelAvailability` / ModelStorage / filesystem = 当前模型事实
- `ModelInstallJournalRecord` = 安装 operation 事实
- WorkManager / JobScheduler = executor，不是 business truth

历史 `READY` 只证明某次 operation 曾成功；模型被删除后必须重新 inspection，不能以旧 READY 冒充当前模型仍存在。

## Candidate identity

每个 operation 冻结精确 `ModelDescriptorSnapshot` 和 manifest digest。

候选 identity：

`modelId + version + revision + manifestDigest`

恢复过程中不得因为远端 manifest 后续变化而静默切换 revision。

## Persistent journal

Journal：

`noBackupFilesDir/model-install-journal/`

使用 atomic file replacement，保存：

- operationId
- descriptor snapshot
- manifest digest
- origin (`MANUAL` / `AUTO_SMALL`)
- phase
- downloaded/total bytes
- executor kind / generation
- retry/attempt
- cancel flag
- requiresUserResume
- failure code / diagnostic message
- creation/update timestamps

Stage 13B.4 不升级 Room；Room 保持 v7。

## Execution boundary

### Android 14+ manual download

用户明确发起的大模型下载使用 User-Initiated Data Transfer Job / JobScheduler 路径。

### Android 8–13 manual download

使用 foreground WorkManager fallback，并声明 `dataSync` foreground-service type。

### Automatic small-model update

自动 Silero 更新使用 WorkManager；不得伪装为 user-initiated transfer。

### Post-download processing

VERIFY / EXTRACT / FILE_VERIFY / RUNTIME_VALIDATE / ATOMIC_ACTIVATE 由 durable finalizer 执行。

## Existing model safety retained

继续冻结：

- HTTPS-only download
- HTTP Range continuation
- server 不接受 Range 时安全从 0 重启
- package size / SHA-256 verification
- safe archive extraction
- manifest-declared files only
- extracted file size / SHA verification
- staging directory
- atomic staging promotion
- isolated runtime validator
- confirmed-good marker
- atomic active/previous state
- rollback protection

旧 active model 必须一直可用，直到精确 candidate 完成 runtime validation + atomic activation。

## Recovery rules

### Recoverable download interruption

系统/进程中断不得等同于用户取消。可恢复 `.part` 保留，用于 HTTP Range 续传。

### Incomplete extraction

不完整 staging 不可信；清理后重新 extraction。不得把半解压内容识别为已安装。

### Existing complete package / candidate

- complete package 存在：可从 VERIFY 恢复，不得重复下载
- static candidate 已存在：可从 RUNTIME_VALIDATE 恢复
- candidate 已 active + confirmed-good：reconcile 收敛 READY

### User action semantics

冻结为三种不同语义：

- 下载未完成：`取消下载`，允许删除 partial
- 下载完成后的 VERIFY / EXTRACT / FILE_VERIFY / RUNTIME_VALIDATE：`暂停安装`，必须保留完整 package/candidate
- `INTERRUPTED`：`继续安装`

破坏性“放弃并删除下载”不得与“暂停安装”混淆。

如果 atomic activation 已经成功，则 READY 胜出，late cancel/pause 不得自动 rollback。

## Force Stop / recent-task removal

最终 QA4 冻结两条独立用户终止识别链。

### System Force Stop / user-requested process stop

Android 11+ 使用 `ApplicationExitInfo.REASON_USER_REQUESTED` 作为系统 Force Stop 等用户主动终止的恢复证据。

重新打开 App 后不得静默恢复大模型网络下载，必须进入：

`INTERRUPTED + requiresUserResume=true`

### Recent-task swipe

QA3 的普通 `Service.onTaskRemoved()` 方案在 realme Android 12 真机不可靠，已移除。

QA4 最终方案：

- `MainActivity` 持久记录当前 Android `taskId`
- MANUAL 非终态安装绑定 `operationId + executorGeneration + ownerTaskId`
- 通过 `ActivityManager.appTasks` 检查 owner task 是否仍存在
- Home / app switch / lock：owner task 仍存在，下载可继续
- recent-task swipe：owner task 消失，精确 generation 转 `INTERRUPTED + requiresUserResume`
- scheduler 被取消，但 partial/package/candidate 保留
- startup recovery 在普通 executor reconciliation 前检查 persisted ownerTaskId
- stale generation / AUTO_SMALL / terminal / already-interrupted operation 不得被误判

最终真机已确认：

- 应用详情 Force Stop → 重新打开不自动下载，显示“继续安装”
- recent-task swipe → 重新打开不自动下载，显示“继续安装”
- Home / app switch / lock 不被误判为主动结束

## Notification contract

模型安装系统通知是 durable journal 的 projection，不建立第二套状态机。

冻结行为：

- DOWNLOAD 显示真实百分比与 downloaded/total bytes
- VERIFY：正在校验下载文件
- EXTRACT：正在解压模型
- FILE_VERIFY：正在校验模型文件
- RUNTIME_VALIDATE：正在验证运行库
- ATOMIC_ACTIVATE：正在启用模型
- INTERRUPTED：安装已中断，可继续

通知 action 与 App 页面共用同一 durable user-control path：

- incomplete download → 取消下载
- complete package / post-download processing → 暂停安装
- interrupted → 继续安装

点击 action 时必须重新读取最新 durable/filesystem truth，防止“通知生成时仍是下载、点击时 package 已完整”的 race 导致完整包被误删。

终态 progress notification 必须正确移除/收口，不得卡在旧 VERIFY 等状态。

## Error presentation

用户可见错误信息默认简体中文。

底层 `IOException.message` 等英文诊断不得直接暴露为产品提示；journal 保存稳定 failure code，原始异常只保留用于 diagnostics/logging。

## Transient cleanup

Transient cleanup 必须 journal-aware。

非终态 durable operation 所拥有的 `.part`、完整 package 或必要 staging/candidate 不得仅因 process-local coroutine 消失而被 startup cleanup 删除。

## Production model channel

Stage 13B.4 QA 期间，经用户明确确认，将 Stage 13A 最终产品模型矩阵正式提升到 production model channel v7。

model-channel 最终基线：

- repository：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production commit：`6f74d89f02a23a46a489de79757f0ed07d0daea2`
- manifestVersion：7
- manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- production validator：PASS

当前 Voica 产品矩阵包括：

- Silero VAD
- CT-Transformer zh-en punctuation
- Pyannote Segmentation 3.0
- SenseVoice INT8
- Qwen3-ASR 0.6B INT8
- Small Bilingual Zipformer
- Chinese Large Zipformer CTC INT8
- CAM++

FireRedASR2 与 Chinese Large Transducer 不进入当前 production 产品矩阵。

旧兼容 descriptor 可继续存在于 channel，但当前 Voica product allowlist 不得因此重新暴露已移出的产品模型。

## Real-device QA history

- QA1 v55：发现 Force Stop 自动续传、post-download cancel 误删 package、英文底层错误、历史 READY 导致删除后假安装
- QA2 v56：上述问题修复；发现 recent-task swipe 仍自动恢复及 notification progress/phase/action 不一致
- QA3 v57：Force Stop + notification chain 真机通过；recent-task swipe 的 `onTaskRemoved()` guard 在目标设备仍不可靠
- QA4 v58：改用 ownerTaskId/task-presence；最终真机全部通过

最终用户确认：**“测试通过”**。

## Acceptance

Stage 13B.4 最终验收成立：

- Compose/UI 不再是安装任务唯一生命周期 owner
- manual large download 使用 durable platform executor
- automatic small-model update durable
- journal/process recovery 成立
- system interruption 与 user cancellation 区分
- complete package pause 不重复下载
- stale READY 不冒充当前模型事实
- runtime failure 不破坏旧 active model
- atomic activation / generation fencing 保持
- Force Stop / recent-task swipe 需要明确人工 resume
- Home / app switch / lock 不误暂停
- notification progress / phase / actions 与 durable truth 同步
- 用户可见错误中文化
- Room 保持 v7
- production model channel v7 已受控发布并验证
- Android PR CI PASS
- real-device QA PASS

## Explicit non-scope

Stage 13B.4 不修改：

- BLE protocol/GATT
- R3 scan/connect
- R5 reconnect ownership
- playback/Mini Player
- transcription/diarization execution policy
- AI Summary durable execution policy（留给 Stage 13B.5）
- Paragraph Organizer V2
- Room schema
- Stage 13C diarization performance optimization
- formal 30/60/120-minute stress campaign

## Next gate

Stage 13B.4 Freeze/Handoff 后，下一子阶段为：

**Stage 13B.5 — Durable AI Summary / Process Recovery**

Stage 13B 整体仍未 Final Freeze；后续仍需完成：

- 13B.5 Durable AI Summary / Process Recovery
- 13B.6 Cross-task Recovery Matrix Closure
- 13B.7 Real 30/60/120 Minute Stress Campaign
- 13B.8 Final Regression / Stage 13B Final Freeze / Handoff
