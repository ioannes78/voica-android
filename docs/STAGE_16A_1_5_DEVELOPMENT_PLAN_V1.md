# Stage 16A.1.5 — Home & Notification UX Consolidation

状态：**APPROVED DEVELOPMENT PLAN V1**

日期：2026-10-16

## 1. 开发目标

统一现有业务 truth 到 Homepage / App Global UI / Android Notification 的 projection。不得重做 recording、realtime、transcription、diarization、AI Summary、canonical、model install 或 playback lifecycle。

## 2. 开发分支

`stage16a1_5-home-notification-ux`

起点：`main@cd984fc7910092777190547b0e584dc74a260f32`

## 3. 开发顺序

`P0 文档/基线 → P1 Unified State Projection → P2 最小化 existing destination/deep-link adapter → P3 Homepage IA → P4 App Global Status consolidation → P5 Notification registry/projection → P6 lifecycle/dedup/dismiss presentation → P7 canonical/model/playback adapter 收口 → P8 自动化/CI → P9 单项真机 QA → P10 Freeze/Handoff`

开发顺序不得从视觉卡片开始；必须先完成可测试 projection。

## 4. P0 — Documentation / Baseline

- 写入 Stage 16A.1.5 requirements / development plan；
- 更新 16A 产品路线，将 16A.1.5 插入 16A.1 与 16A.2；
- 更新 AGENTS 当前接管状态；
- 历史 Stage 16A/16A.1 Freeze/Handoff 不改写；
- Room v13 / model pin / version / CI baseline 写入阶段进度。

## 5. P1 — Unified State Projection

新增纯 presentation/domain projection。建议模型：

- `AppActivityProjection`
- `AppActivityKind`
- `AppActivityLifecycleClass`
- `AppActivitySource`
- `AppActivityAction`
- `AppActivityProjectionResolver`

核心字段至少覆盖：logical key、kind、source、lifecycle class、title/subtitle、progress、recordingId/taskId、destination、available actions、priority。

### P1 约束

- 不持有业务 lifecycle；
- 不写 Room；
- 不启动/停止 Service；
- 不调用 AudioRecord/BLE/ASR；
- projection 应可由现有 truth 随时重建；
- 先 unit test，再接 UI。

## 6. P2 — Minimal Existing Destination Adapter

只做 notification/global UI 到已有页面的最小 typed adapter。优先复用 `RecordingOpenTarget` 和现有 process-local navigation bridge。

目标：

- phone recording live；
- phone realtime live；
- device realtime live；
- recording detail / transcription / diarization / AI summary；
- playback exact recording；
- model manager exact model（若现有页面支持）；
- error/attention exact actionable page。

不得以通知需求为由大规模重写 Navigation。

## 7. P3 — Homepage IA

Idle：只展示 `录音` / `实时转写` 高频动作，source 作为 `录音卡 | 手机` 属性。

Active：一个 Primary Activity 大卡；其它并发 active 用 compact secondary row。

- 手机录音可直接 Pause/Resume/Stop，但仅调用现有 controller；
- 设备状态 compact；
- Mini Player 仅 playback；
- background/attention 用紧凑摘要；
- 不修改业务功能。

## 8. P4 — App Global Status Consolidation

逐步替换/整合现有：

- `GlobalRecordingStatusBar`
- `GlobalTaskStatusBar`
- QA4 durable attention UI

目标只保留紧凑的 active strip + task/attention strip，并保留 exact visible-page suppression。

旧/新 projection 开发期间必须互斥，不能同时正式显示。

## 9. P5 — Android Notification Registry / Projection

建立中央 channel registry，登记现有 physical channel ID，不删除用户已有 channel。

建立 notification projection/event model：logical task key、generation/result identity、state、title/text、progress、destination、actions。

FGS execution owner 继续保持现状；不同 FGS type 不合并。

## 10. P6 — Notification Lifecycle / Dedup / Dismiss

- RUNNING 原位更新；
- terminal 后 running presentation 退出；
- exact page visible 时 suppression；
- candidate/completion 同一 logical result 去重；
- swipe 使用 deleteIntent/轻量 presentation state 防止同 generation 立即重发；
- swipe != acknowledge；
- exact result viewed 才 acknowledge；
- failure/interrupted 分开；
- task cancel 默认无额外 completion。

若 presentation persistence 需要持久化，优先 SharedPreferences/DataStore；默认不升级 Room。

## 11. P7 — Canonical / Model Install / Playback

只新增 adapter/presentation：

- canonical：映射已有 progress/failure，不改 coordinator；
- model install：映射 durable journal，不改 WorkManager/UIDT ownership；
- playback：Mini Player 与 system notification 读取同一现有 playback truth；只修正 presentation/deep link，不改 playback lifecycle。

三类分别独立提交，方便回滚。

## 12. 通知子阶段硬边界

通知部分只允许改：Android notification、App global state/attention、Global/Attention bars 及其 presentation glue。

不得借机修改：录音、实时转写、离线转写、重新转写、说话人分离、重新说话人分离、AI 总结/重新总结、canonical、playback、model install、WorkManager、FGS ownership、WakeLock、AudioRecord、BLE、ASR、Room business schema、结果版本机制。

若纯通知层无法实现某项文案/行为，默认降级通知表现，而不是修改业务功能。

## 13. P8 — Automated Regression

### Unit

- projection normalization/resolver；
- active priority/concurrent source；
- lifecycle mapping；
- logical notification identity；
- dedup；
- acknowledge/dismiss/re-notify；
- destination adapter。

### Android/Robolectric

- channel registry；
- notification ID stability；
- ongoing/autoCancel/deleteIntent；
- PendingIntent target；
- POST_NOTIFICATIONS denied 不改变业务 truth；
- service notification presentation。

### Compose

- idle/connected/disconnected；
- phone/device active；
- simultaneous active；
- paused/error；
- background summary；
- attention summary；
- Mini Player。

同时继续跑现有 Stage 16A/16A.1/long-task/model-install/Room/security regression。

## 14. P9 — 真机 QA

严格一次一项：

H1 Idle Homepage；H2 录音卡录音；H3 手机录音；H4 手机 realtime；H5 录音卡 realtime；H6 双 active source；H7 Mini Player；H8 offline transcription；H9 diarization；H10 AI Summary；H11 canonical；H12 model install；H13 completion attention；H14 failure/interruption；H15 notification deep link；H16 POST_NOTIFICATIONS denied；H17 background/screen-off；H18 process kill/restart；H19 notification swipe；H20 multi-task aggregation。

## 15. FGS Gate

若实现过程中发现必须修改 microphone/device/media-processing FGS acquire/release、WakeLock、capture ownership 或 execution lease：立即停止该项编码，说明原因和受影响 QA Gate，等待用户再次明确确认。

## 16. Rollback Strategy

建议独立提交：

- C1 P0 docs/stage registration
- C2 P1 projection
- C3 P2 destination adapter
- C4 P3 homepage
- C5 P4 global status
- C6 P5 registry/projection
- C7 P6 lifecycle/dedup
- C8 canonical adapter
- C9 model adapter
- C10 playback adapter
- C11 tests
- C12 QA fixes

## 17. 数据/模型/版本

- Room 默认保持 v13；
- production model channel 不修改；
- 当前 versionCode/versionName 在进入 release candidate 前按仓库 release policy 再决定；
- CI PASS 只允许进入真机 QA，不等于 H1–H20 PASS。
