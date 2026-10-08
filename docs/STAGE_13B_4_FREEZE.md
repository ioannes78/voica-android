# Voica Stage 13B.4 Final Freeze

状态：**FINAL / ACCEPTED / FROZEN**

日期：2026-10-07

## 1. 最终结论

Stage 13B.4 — Durable Model Install 已完成自动化门禁与最终真机验收，现正式冻结。

用户最终明确确认：

**“测试通过”**

本 Freeze 只冻结 Stage 13B.4 子阶段；Stage 13B 整体仍继续开发，不代表 Stage 13B Final Freeze。

## 2. 最终功能基线

最终功能 / 真机验收代码基线：

`fb945496975695a99c80c38064c089466d87a9b8`

最终 QA candidate：

- versionCode：58
- base versionName：`0.13.1-stage13b-model-install-qa4`
- QA versionName：`0.13.1-stage13b-model-install-qa4-qa`
- package：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- Room schema：7
- Android PR CI：#799
- run：`37559018140`
- CI conclusion：SUCCESS
- Artifact：`Voica-qa-apk`
- Artifact ID：`11455389528`
- Artifact ZIP size：35,983,334 bytes
- Artifact digest：`sha256:9060375ab886a6ad78309b0cc54ba4563407569ae463e374592a75a785a019dc`
- Accepted APK size：56,647,536 bytes
- Accepted APK SHA-256：`e2dff19c12202de8f0d2abadbd0dde80eb09bbfdb13082fe29dd1cf0dec0e378`

CI success 不能替代真机验收；本阶段已有用户明确 PASS。

## 3. Durable Model Install 冻结契约

正式生命周期：

`DOWNLOAD → VERIFY → EXTRACT → FILE_VERIFY → RUNTIME_VALIDATE → ATOMIC_ACTIVATE → READY`

恢复/终态：

- INTERRUPTED
- FAILED_RECOVERABLE
- FAILED_INTEGRITY
- FAILED_RUNTIME
- FAILED_CONFIGURATION
- CANCELLED

冻结边界：

- filesystem / ModelStorage / ModelAvailability 是当前模型事实
- journal 是 operation truth
- WorkManager / JobScheduler 仅为 executor
- executor generation 必须隔离 stale callback
- candidate identity 固定为 modelId + version + revision + manifestDigest
- old active model 在新 candidate 完成 runtime validation + atomic activation 前始终保持可用

## 4. Executor 冻结

- Android 14+ 用户手动模型下载：User-Initiated Data Transfer Job / JobScheduler 路径
- Android 8–13：foreground WorkManager `dataSync` fallback
- AUTO_SMALL / Silero 自动更新：WorkManager
- verify/extract/file verify/runtime validate/activate：durable finalizer

不允许重新退回 Compose `rememberCoroutineScope()` 或普通 process-local scope 作为长模型安装唯一 owner。

## 5. 下载、暂停与恢复语义

冻结为：

- 下载未完成：`取消下载`，partial 可删除
- 下载完整后的校验/解压/runtime validation：`暂停安装`，完整 package/candidate 必须保留
- INTERRUPTED：`继续安装`
- destructive cleanup 必须与 pause 分离

若 complete package 已存在，resume 不得重新下载；若 static candidate 已存在，可直接恢复到 runtime validation；若 exact candidate 已 active + confirmed-good，应 reconcile 为 READY。

历史 READY 不代表模型现在仍存在。模型被删除后再次安装必须重新 inspection，并依据当前 filesystem truth 决定 DOWNLOAD / VERIFY / RUNTIME_VALIDATE / READY。

## 6. 用户终止语义冻结

### Force Stop

系统 Force Stop / user-requested process stop 使用 `ApplicationExitInfo.REASON_USER_REQUESTED` 参与恢复判断。

App 重开后不得静默恢复大模型网络下载，必须明确进入“继续安装”。

### Recent-task swipe

最终 QA4 不再依赖普通 `Service.onTaskRemoved()`。

冻结方案：

- MainActivity 注册当前 taskId
- MANUAL operation 绑定 operationId + executorGeneration + ownerTaskId
- ActivityManager.appTasks 判断 owner task 是否仍存在
- Home / app switch / lock：继续执行
- recent-task swipe：owner task 消失 → INTERRUPTED + requiresUserResume
- partial/package/candidate 保留
- startup recovery 在普通 executor reconciliation 前再次检查 persisted ownership

目标 realme Android 12 真机已验证通过。

## 7. Notification 冻结

系统通知只投影 durable journal，不形成第二状态机。

- DOWNLOAD：真实百分比 + downloaded/total bytes
- VERIFY：正在校验下载文件
- EXTRACT：正在解压模型
- FILE_VERIFY：正在校验模型文件
- RUNTIME_VALIDATE：正在验证运行库
- ATOMIC_ACTIVATE：正在启用模型
- INTERRUPTED：安装已中断 / 可继续

通知 action 与 App 页面共用同一 durable control path。

禁止再次出现：

- 通知无下载进度
- notification phase 卡在旧 VERIFY
- post-download 仍以“取消下载”删除完整 package
- App 已暂停但 notification 仍显示旧进行中状态

## 8. Error / cleanup 冻结

- 用户可见模型安装错误默认简体中文
- 原始 IOException/system exception 仅供 diagnostics/logging
- journal-owned `.part` / complete package / candidate 不得被 process-local cleanup 误删
- integrity/runtime failure 不得切换 active model
- late cancel 不得 rollback 已成功 atomic activation 的 READY candidate

## 9. Production model channel 冻结事实

Stage 13B.4 QA 期间，经用户明确确认完成受控 production promotion。

当前 production 基线：

- repository：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production commit：`6f74d89f02a23a46a489de79757f0ed07d0daea2`
- manifestVersion：7
- manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- validator：PASS

当前产品正式模型：

- Silero VAD
- CT-Transformer zh-en punctuation
- Pyannote Segmentation 3.0
- SenseVoice INT8
- Qwen3-ASR 0.6B INT8
- Small Bilingual Zipformer
- Chinese Large Zipformer CTC INT8
- CAM++

FireRedASR2、Chinese Large Transducer 不进入当前产品矩阵。

后续不得未经新 Stage 明确需求、验证与用户授权再次修改 production model channel。

## 10. QA 历史

- QA1 v55：暴露 Force Stop 自动续传、完整 package 被 cancel 删除、英文异常、stale READY
- QA2 v56：上述修复后，暴露 recent-task swipe 自动恢复及 notification state/action 不一致
- QA3 v57：Force Stop + notification chain PASS；普通 onTaskRemoved guard 在目标设备不可靠
- QA4 v58：ownerTaskId/task-presence closure；最终全部通过

最终真机确认覆盖：

- normal install
- background/Home/lock continuation
- Force Stop → explicit resume
- recent-task swipe → explicit resume
- notification determinate progress
- notification phase sync
- cancel download / pause install / resume semantics
- complete package reuse
- model delete → real reinstall
- Chinese product error messages
- accepted Stage 13B baseline regression check

## 11. Room / 生产代码边界

Room 继续为 v7；本阶段无 Room migration。

Stage 13B.4 Final Freeze 不允许借冻结动作修改 BLE、Playback、ASR、Diarization、AI Summary 算法或其它生产功能。

## 12. 下一门

Stage 13B.4 已冻结，下一子阶段解锁为：

**Stage 13B.5 — Durable AI Summary / Process Recovery**

Stage 13B.5 开始编码前仍必须重新核验 GitHub 当前：

- main
- PR #17
- current branch HEAD
- CI
- 本 Freeze/Handoff
- Room v7
- production model channel v7
- AI Summary 当前源码与 DAO/checkpoint contract

不得仅依据聊天记忆开始实现。
