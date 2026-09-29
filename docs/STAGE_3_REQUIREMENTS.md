# Voica Stage 3 修订需求 V1

状态：已确认（2026-09-29）

## 目标

Stage 3 只实现 **QS668/CB08 设备录音控制 + 设备实时录音状态同步**。

用户可在设备页查看并控制：

- 未录音 / 录音中 / 已暂停 / 未知状态
- 开始录音
- 暂停
- 继续录音
- 停止并保存
- 当前录音时长
- 当前文件大小
- 当前文件名
- 录音增益（低 / 中 / 高）

App 操作和设备物理按键必须汇聚到同一份设备录音状态；最终状态以设备查询响应为准，不能以按钮点击结果或通知来源猜测。

## 项目边界

Voica 是独立项目，不复制、迁移、cherry-pick 或机械翻译 `ioannes78/voice-card-android` 的代码、Gradle、测试、UI、BLE Queue、Room 或其他实现。

协议事实优先级：

1. QS668/CB08 真机可重复验证行为。
2. `nextproto1024/ai-recorder-card-open-protocol@e741ea72207f1a2aae3df4debc5c135728e0170e`。
3. `laidely/kardo@bcec3c5fdbcb34810a6f235e8b5873683f2ab951`，仅用于产品行为交叉验证。
4. Android 官方 BLE API 行为。

## Stage 2 基础保持不变

- `:app → :core:ble → :core:protocol`
- AE21 WRITE_NO_RESPONSE。
- AE22 / AE23 使用独立 FrameParser。
- 两路完整 ProtocolFrame 均进入统一 pending matcher。
- Request/Response 按 TYPE/CMD 强匹配；sequence 仅作为 Diagnostics。
- GATT 操作严格串行。
- 每次连接使用独立 session generation，旧 callback 不得污染新 session。
- Ready 必须经过 GATT shape、AE22/AE23 notification 和 MTU。
- Android 请求 MTU 517；QS668/CB08 Stage 2 真机已确认实际 MTU 517。
- Remote reconnect：1s → 2s → 4s，最多 3 次。
- 用户主动断开不自动重连。

## TYPE=3 录音协议

| 功能 | 请求 | 响应 |
| --- | --- | --- |
| 开始录音 | TYPE=3/CMD=1 | TYPE=3/CMD=2 |
| 停止并保存 | TYPE=3/CMD=3 | TYPE=3/CMD=4 |
| 暂停 | TYPE=3/CMD=5 | TYPE=3/CMD=6 |
| 继续 | TYPE=3/CMD=7 | TYPE=3/CMD=8 |
| 查询状态 | TYPE=3/CMD=19 | TYPE=3/CMD=20 |
| 查询时长/大小 | TYPE=3/CMD=21 | TYPE=3/CMD=22 |
| 查询当前文件名 | TYPE=3/CMD=23 | TYPE=3/CMD=24 |
| 查询增益 | TYPE=3/CMD=25 | TYPE=3/CMD=26 |
| 设置增益 | TYPE=3/CMD=27 + 1B gain | TYPE=3/CMD=28 |

公开协议当前候选语义：

- Start / Save / Pause / Resume：response body[0] == 1 表示成功。
- Set Gain：response body[0] == 0 表示成功。
- 状态：1=Recording，2=Idle，3=Paused。
- 增益：1=Low，2=Medium，3=High。
- TIME_RESPONSE：至少 6B，前 2B 为 LE u16 duration，后 4B 为 LE u32 size。

上述 success code、状态值、单位和物理事件行为必须由 Stage 3 QS668/CB08 真机测试最终冻结。

## AE22 / AE23 路由

禁止因为 notification 来源是 AE23 就提前把 TYPE=3 帧认定为物理按键事件。

每个完整帧都必须：

1. 保留 AE22 / AE23 source 供诊断。
2. 尝试按 expected TYPE/CMD 完成 pending request。
3. 将已知 TYPE=3 response 解码为录音状态事件。
4. 将 CMD 1/3/5/7 作为 unsolicited hardware-event candidate。
5. 未知或 malformed 帧进入诊断，不得 Crash。

例如 GET_STATE 3/19 的 3/20 response 即使来自 AE23、sequence 与请求不同，也必须可以完成 pending request。

## 统一录音状态

Repository 暴露单一 `StateFlow<RecordingDeviceState>`，至少包含：

- status
- durationSeconds
- currentSizeBytes
- filename
- gain
- freshness
- commandState
- lastHardwareEvent
- lastUpdatedTime
- lastError

Freshness 至少区分：

- NotSynced
- Syncing
- Fresh
- Stale
- Failed

连接断开时允许保留最后已知数据显示，但必须标记 Stale；重新 Ready 后必须重新向设备读取，不得把缓存状态重新标为 Fresh。

## 状态真值顺序

1. GET_STATE / GET_TIME / GET_FILENAME / GET_GAIN 查询响应是当前设备真值。
2. 物理按键事件只触发 reconciliation，不作为长期真值。
3. App command response 只说明命令响应结果，命令后仍必须 GET_STATE 收敛。
4. 不允许点击按钮后永久 optimistic 更新录音状态。

## 命令与超时

Start / Pause / Resume / Save / SetGain 均属于有副作用命令。

这些命令如果 response timeout：

- 不允许自动重发原命令；
- 记录 timeout；
- 使用只读查询确认最终设备状态。

这样避免“设备已执行，但响应丢失”时重复执行副作用命令。

## Ready / Reconnect / Foreground

进入 Ready 后：

1. GET_STATE
2. GET_TIME
3. GET_FILENAME
4. GET_GAIN

GET_STATE 失败时：

- BLE 可继续保持 Ready；
- Recording State 进入 Failed/Unknown；
- 录音控制禁用；
- 用户可以重新同步。

Remote reconnect 或 App 回到前台后，如果 GATT 仍 Ready，必须重新读取设备录音状态。

Stage 3 不新增 Foreground Service。

## 时长轮询

只有满足以下全部条件才进行约 1 秒一次 GET_TIME：

- Device Ready
- App foreground
- RecordingStatus == Recording

任何时刻最多一个 poll job；Pause、Save、Disconnect、Bluetooth Off、App background、session change 时必须停止。

## 增益

Stage 3 包含 GET_GAIN / SET_GAIN：

- UI：低 / 中 / 高。
- 设置后必须重新 GET_GAIN，以设备回读值为最终显示。
- 未知 gain raw 值必须保留。
- 增益失败不能破坏录音主控制链路。

## Malformed Payload

禁止使用伪造默认值掩盖畸形协议响应。

特别是 TIME_RESPONSE 少于 6B 时必须明确 decode failure，不能返回合法的 0 秒 / 0 bytes。

## UI

Ready 后显示“设备录音”区域：

- 录音状态
- 同步状态
- 时长
- 当前大小
- 当前文件名
- 录音增益

按钮矩阵：

- Idle：开始录音
- Recording：暂停 / 停止并保存
- Paused：继续录音 / 停止并保存
- Unknown / Syncing / Failed：禁用录音控制，允许重新同步

命令执行中禁止重复点击。

## Diagnostics

至少记录：

- raw / decoded recording state
- freshness
- duration / size / filename
- raw / decoded gain
- last TYPE=3 response source/cmd
- request / response sequence
- response latency
- last hardware event
- last sync reason
- raw command result code
- decode error / operation error
- polling active

## Stage 3 不实现

- 设备文件列表 UI
- 文件下载 / 断点续传 / 删除
- Opus/WAV 解码
- Room / 本地录音库
- 播放器 / 时间轴
- TYPE=1 实时音频业务
- 手机麦克风录音
- VAD / ASR / 标点 / 说话人
- AI 会议
- 云端转写
- Foreground BLE Service

## 验收门禁

必须通过 Stage 1/2 回归、Stage 3 Unit Test、Debug Build、GitHub Actions CI，并生成 APK 做 QS668/CB08 真机验收。

只有用户明确回复 **“测试通过”** 后，才能创建 Stage 3 Test/Freeze/Handoff、更新冻结文档并 merge main。
