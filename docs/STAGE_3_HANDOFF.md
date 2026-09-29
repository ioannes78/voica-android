# Voica Stage 3 Handoff

## 当前结论

Stage 3 已完成开发、自动验证和 QS668/CB08 真机验收，用户已明确确认：

**“真机测试通过”**

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 4。

## 仓库与分支

Repository：

`ioannes78/voica-android`

Stage 3 开发分支：

`stage3-development`

PR：

`#3 Stage 3：录音控制与设备实时状态`

Stage 3 起始基线：

`main@e8e5625efdadc4d43c856a02443f7304b9c8fedd`

最终真机候选：

`de03a707dfaea22cc6932a6e0211ac69f9691835`

核心最终修复：

`70b416ba2c20bdd4bd1b9201f337251a756af818`

Core CI：

`36567838517` — success

Candidate APK CI：

`36568209124` — success

Freeze/Handoff 内容提交：

`575912ce85338bf35feebac818c066c875c8c78f`

Freeze 内容 CI：

`36570496710` — **success**

该 Run 再次通过：

- `:core:protocol:test`
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

本 Handoff 的 seal 提交仅记录最终验证证据，不改变 Stage 3 生产代码。

PR #3 合并后的最终 `main` SHA，Stage 4 接管时必须从 GitHub 重新读取，不得从聊天或本文件猜测。

## 版本

- versionCode：7
- versionName：`0.3.3-stage3`
- APK SHA-256：`426addc3a0da5ae68a60bf13bcff9ff7e2a43cca7417d22b68168384c5511ded`

## 当前模块

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

Stage 3 没有引入空模块。

## Stage 3 新增核心

### :core:protocol

- RecordingStatus
- RecordingGain
- RecordingTimeInfo
- ProtocolDecodeResult
- TYPE=3 卡录音 control builders
- RecordingDecoders
- malformed TIME_RESPONSE 严格失败

### :core:ble

- RecordingRequestOutcome
- RecordingFrameRouter
- RecordingStateReducer
- RecordingDeviceState
- RecordingFreshness
- RecordingCommandState
- RecordingHardwareEvent
- RecordingDiagnostics
- RecordingPollingPolicy
- RecordingStateConvergencePolicy
- RecordingStateEvidencePolicy
- RecordingSupplementaryReadPolicy
- RememberedDeviceStore
- RememberedDeviceAutoConnectPolicy

### :app

- DeviceViewModel recording actions/state
- RecordingCard
- 简体中文录音控制
- Stage 3 BLE/Recording Diagnostics

## 当前固件最重要的协议事实

不要把 NextProto/Kardo 的奇数 request → 偶数 response 模型直接套回当前 QS668/CB08 卡录音控制。

真机冻结方向：

- Device → App：3/1 开始、3/3 停止、3/5 暂停、3/7 继续
- App → Device：3/2+01 开始、3/4+01 停止、3/6+01 暂停、3/8+01 继续

物理按键事件收到后：

`stop poller → even CMD+01 ack → reconciliation`

App 主动控制：

`send even CMD+01 once → reconcile`

不得在 timeout 后自动重发副作用命令。

## Pause 特殊事实

当前固件：

- 实际 Pause 后 GET_STATE 仍可能连续返回 1
- 因此 GET_STATE=1 不能区分 Recording / Paused

Voica 当前规则：

- App Pause 写成功 → latch Paused
- physical CMD=5 → latch Paused
- GET_STATE=1 在 latch 时不得覆盖 Paused
- Resume → clear latch → Recording
- Idle / Save / Start 同样清理 latch

这个规则是 Stage 3 最重要的真机兼容点之一。

## GET_STATE / TIME / FILENAME / GAIN

- GET_STATE：3/19 → 3/20，关键读取
- GET_TIME：3/21 → 3/22，6B LE duration + size
- GET_FILENAME：3/23 → 3/24，UTF-8
- GET_GAIN：3/25 → 3/26
- SET_GAIN：3/27 → 3/28

Idle 时：

- duration / current size 归零
- 不查询当前 GET_TIME
- 不强制 GET_FILENAME
- 保留最后文件名

辅助读取失败仅 Diagnostics。

## Poller

只有 Ready + foreground + Recording 时，约每秒 GET_TIME。

Full sync、物理录音事件、Pause/Save/Disconnect/Background 时停止。

严禁重新引入“旧 Poller 与 full sync 同时 GET_TIME”的竞争。

## BLE 基线仍继承 Stage 2

Stage 2 Freeze 继续有效：

- AE20 Service
- AE21 WRITE_NO_RESPONSE
- AE22 Notify
- AE23 Notify
- AE22/AE23 独立 FrameParser
- 两路完整 ProtocolFrame 均可进入 shared pending matcher
- TYPE/CMD 强匹配
- sequence 仅 diagnostics
- Actual MTU 517
- GATT operation 严格串行
- stale session callback 防护
- Remote reconnect 1s → 2s → 4s
- User disconnect 不执行 remote reconnect

## 自动连接

Stage 3 新增 remembered-device auto-connect：

- Ready 后保存最后成功设备地址
- 下次 App 前台自动直连
- Bluetooth ON / 权限恢复可自动尝试
- 用户主动断开后，本进程不自动连回
- App 重启恢复自动连接

## Stage 4 目标边界

Stage 4：设备文件列表 + 文件名解析。

重点：

- 设备文件列表请求与解析
- 文件名称/大小/时长
- 排序与刷新
- 短/截断文件名处理
- 2-12 分段能力用于完整文件名探测
- 已下载状态映射的契约准备

Stage 4 不要提前实现：

- 文件下载业务
- 删除业务
- Opus/WAV
- Room
- 播放器
- ASR/VAD
- Speaker
- AI

## Stage 4 接管流程

新 Agent 必须：

1. 从 GitHub 检查 `main` 当前 HEAD、branch、PR、CI。
2. 阅读 `AGENTS.md`。
3. 阅读 `docs/STAGE_3_TEST.md`、`docs/STAGE_3_FREEZE.md`、`docs/STAGE_3_HANDOFF.md`。
4. 阅读 `docs/STAGE_3_REAL_DEVICE_FINDINGS.md`。
5. 阅读 `docs/ROADMAP.md` 和 `docs/ARCHITECTURE.md`。
6. 阅读实际源码，不只依赖本 Handoff。
7. 先输出 Stage 4 修订需求并等待确认。
8. 再输出 Stage 4 修订开发规划并等待第二次确认。
9. 确认后才允许编码。
