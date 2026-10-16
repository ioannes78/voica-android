# Stage 16A.1.5 Progress

状态：**QA2 IMPLEMENTATION IN PROGRESS / DEVICE QA PENDING**

日期：2026-10-16

## 当前基线

- repository：`ioannes78/voica-android`
- base main：`cd984fc7910092777190547b0e584dc74a260f32`
- development branch：`stage16a1_5-home-notification-ux`
- PR：`#30`
- QA1 real-device candidate：`114f0c5507c929da32e89a33905aa87fa4c5d33c`
- H1：未通过；根据真机截图进入 QA2 presentation/resource 修订
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13，未新增 migration
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`，未修改
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off` = `NOT RUN / WAIVED BY USER`

## QA2 用户确认范围

只允许 Compose / presentation / image resource 调整，不允许修改业务 lifecycle。

1. 扫描结果移出主页 feed，改为 modal bottom sheet 设备选择器；继续调用现有 BLE scan/connect API。
2. 删除 QA1 新增的固定强调色，恢复 `MaterialTheme.colorScheme`。
3. 不缩字体，只压缩设备状态卡无效垂直留白。
4. 不缩字体，压缩录音来源/模式 segmented selector 视觉高度，并保留合理点击热区。
5. 波形恢复主题强调色；手机录音/实时转写读取现有真实 audio activity levels；普通录音卡录音无 realtime PCM truth 时不伪造动态波形。
6. Active 卡左上副标题移除重复时间；中央只保留一份时长；进行中恢复文件名/当前大小的 presentation。
7. 使用用户本轮重新上传的原始品牌资源：launcher icon、透明彩色 logo、notification icon、splash、QS668/CB08 产品图；不改变启动/通知业务 lifecycle。
8. 删除 Idle 主卡无功能的 `•••`。

## 硬边界

QA2 不得修改：

- BLE protocol / scan/connect semantics
- recorder-card recording lifecycle
- phone microphone recording lifecycle
- realtime ASR lifecycle
- AudioRecord ownership
- FGS ownership / WakeLock
- transcription / diarization / AI Summary execution
- Room schema（保持 v13）
- WorkManager / UIDT ownership
- production model channel

所有新状态必须来自现有业务 truth；presentation 不得成为业务 truth。

## QA1 已完成能力（继续保留）

- Unified activity projection / Primary + Secondary
- 单一 GlobalTaskStatusBar + durable attention
- notification channel registry
- notification swipe presentation store
- exact-page acknowledge semantics
- playback exact recording deep link
- canonical / model install read-only projection adapters
- notification small-icon consolidation seam

## QA2 完成后的 Gate

1. repository contracts
2. unit tests
3. Debug compile
4. releaseQa / R8 / security / artifact checks
5. 固定新的 QA2 commit SHA
6. 从 H1 重新开始真机测试，一次一项

Stage 16A.1 S15 保持 `NOT RUN / WAIVED BY USER`；只有 QA2 意外触及 microphone/background execution ownership 时才重新评估。
