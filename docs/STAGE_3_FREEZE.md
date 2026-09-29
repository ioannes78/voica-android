# Voica Stage 3 Freeze

状态：**FROZEN / ACCEPTED**

用户最终验收日期：2026-09-29

## 1. 冻结范围

Stage 3 冻结以下能力：

- App 卡录音开始 / 暂停 / 继续 / 停止并保存
- RecordingStatus / RecordingGain / RecordingTimeInfo
- 当前录音时长、大小、文件名、增益
- TYPE=3 设备物理按键事件
- 物理按键 acknowledgement
- RecordingFrameRouter
- RecordingStateReducer
- RecordingDeviceState / Freshness / CommandState
- App 操作与设备物理操作状态统一
- 前台 Recording GET_TIME Poller
- Pause semantic latch
- Full sync 与 Poller 互斥
- 辅助查询错误降级 Diagnostics
- 记忆最后成功设备与 App 自动连接
- Stage 3 Compose 录音卡
- Stage 3 Diagnostics
- Stage 3 Unit Tests

Stage 3 不冻结、不宣称实现：

- 设备文件列表业务
- 文件下载/删除
- Opus/WAV
- Room
- 播放器
- TYPE=1 实时音频业务
- VAD / ASR / 标点
- Speaker
- AI
- Cloud ASR
- Foreground BLE Service

## 2. 技术基线

- versionCode：7
- versionName：`0.3.3-stage3`
- Kotlin：2.4.20
- AGP：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Application ID：`io.github.ioannes78.voica`
- 模块：`:app`、`:core:protocol`、`:core:ble`

依赖方向：

`app → core:ble → core:protocol`

## 3. 真机代码与自动验证

最终真机候选：

`de03a707dfaea22cc6932a6e0211ac69f9691835`

核心修复：

`70b416ba2c20bdd4bd1b9201f337251a756af818`

自动验证：

- Core CI Run：`36567838517` — success
- Candidate APK CI Run：`36568209124` — success
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过

APK SHA-256：

`426addc3a0da5ae68a60bf13bcff9ff7e2a43cca7417d22b68168384c5511ded`

Freeze/Handoff 内容提交：

`575912ce85338bf35feebac818c066c875c8c78f`

Freeze 内容 CI：

- Workflow：Android PR CI
- Run ID：`36570496710`
- 结论：**success**
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过

本 seal 提交仅记录最终验证证据，不改变 Stage 3 生产代码。

## 4. 冻结 TYPE=3 卡录音事实

当前 QS668/CB08 真机与官方 Android 3.0.9-u 交叉验证：

| 方向 | 行为 | TYPE/CMD | Body |
| --- | --- | --- | --- |
| Device → App | 物理开始 | 3/1 | 无 |
| App → Device | 开始 / 开始确认 | 3/2 | `01` |
| Device → App | 物理停止保存 | 3/3 | 无 |
| App → Device | 停止保存 / 停止确认 | 3/4 | `01` |
| Device → App | 物理暂停 | 3/5 | 无 |
| App → Device | 暂停 / 暂停确认 | 3/6 | `01` |
| Device → App | 物理继续 | 3/7 | 无 |
| App → Device | 继续 / 继续确认 | 3/8 | `01` |
| App → Device | 查询状态 | 3/19 | 无 |
| Device → App | 状态响应 | 3/20 | 1B |
| App → Device | 查询时长/大小 | 3/21 | 无 |
| Device → App | 时长/大小 | 3/22 | 6B |
| App → Device | 查询文件名 | 3/23 | 无 |
| Device → App | 文件名 | 3/24 | UTF-8 |
| App → Device | 查询增益 | 3/25 | 无 |
| Device → App | 增益 | 3/26 | 1B |
| App → Device | 设置增益 | 3/27 | 1B |
| Device → App | 设置增益结果 | 3/28 | 1B |

重要：NextProto/Kardo 的奇数 request → 偶数 response 模型不能直接用于当前固件的 App 卡录音控制方向。真机事实优先。

## 5. 录音状态冻结事实

- GET_STATE=2：可确认 Idle
- GET_STATE=1：表示当前录音 session 非 Idle，但当前固件无法据此区分 Recording 与 Paused
- parser 保留 raw=3 → Paused 能力，但当前验收不依赖 raw=3
- App Pause 写成功：Pausing → Paused 强语义证据
- 物理 Pause CMD=5：Paused 强语义证据
- App/物理 Resume：解除 Pause 锁存并进入 Recording
- Start / Save / Idle：解除 Pause 锁存
- Pause 锁存期间 GET_STATE=1 不覆盖 Paused

因此后续 Stage 不得重新退化为“只用 GET_STATE=1/2/3 决定所有录音 UI 状态”，除非新的可重复真机证据证明固件行为已改变。

## 6. 时长、大小、文件名、增益

- TIME_RESPONSE 至少 6B
- duration：前 2B little-endian
- size：后 4B little-endian
- 短于 6B 必须明确 decode failure，不得伪装 0/0
- Recording/Paused 可读取当前时长与大小
- Idle 不查询当前 GET_TIME
- 当前文件名为 UTF-8，设备实际示例：`noteYYYYMMDD-HHMMSS.opus`
- Idle 不强制 GET_FILENAME，保留最后已知文件名
- Gain：1 Low / 2 Medium / 3 High，未知值保留 raw
- SET_GAIN 后重新 GET_GAIN，回读值为最终显示

## 7. Polling / Reconciliation

只有以下条件同时成立才启动约 1 秒 GET_TIME：

- Ready
- foreground
- Recording

任何 full recording sync 开始前必须停止 Poller。

收到任一物理录音事件时也必须先停止 Poller，再：

`hardware event → even CMD+01 acknowledgement → reconciliation`

防止旧 Poller GET_TIME 与 Stop/Pause full sync 竞争。

## 8. 错误分级

关键错误：

- GET_STATE 失败
- 卡录音 control write 失败
- disconnect / reconnect 导致关键操作不确定

可影响 RecordingState.lastError / freshness。

辅助错误：

- 周期 GET_TIME
- full sync GET_TIME
- GET_FILENAME
- GET_GAIN

辅助错误进入 BLE Diagnostics，但不得伪装成“最近录音操作失败”。

## 9. 自动连接

- Ready 后持久化最后成功设备地址
- 后续 App 启动/前台且蓝牙权限可用时自动连接
- Bluetooth Off→On 后前台可自动连接
- 权限恢复后可自动连接
- 用户主动断开：本 App 进程内抑制自动连接
- App 重启后恢复自动连接资格
- Remote disconnect：继续 Stage 2 1s → 2s → 4s、最多 3 次

## 10. 用户验收

用户最终明确回复：

**“真机测试通过”**

因此 Stage 3 满足 Freeze/Handoff 条件。

## 11. 下一阶段

Stage 4：**设备文件列表 + 文件名解析**

Stage 4 必须从合并后的 `main` 重新检查当前真实状态，再执行：

`项目接管 → 阅读 Stage 3 Freeze/Handoff → 检查 main → 修订 Stage 4 需求 → 用户确认 → 修订开发规划 → 用户确认 → 编码`
