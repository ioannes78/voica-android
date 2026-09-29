# Voica Stage 2 Handoff

## 当前结论

Stage 2 已完成开发、自动验证和两轮 QS668/CB08 真机验收，用户已最终确认“测试通过”。

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 3。

## 仓库与分支

Repository：

`ioannes78/voica-android`

Stage 2 开发分支：

`stage2-development`

PR：

`#2 Stage 2：BLE 连接与设备基础信息`

Stage 2 基线：

`main@1ef1e520e67ae3db6d074d2980555ebbc9247961`

真机代码候选：

`0c09609385ed1b0c07d957bd6c37f3371ceb807c`

真机 Candidate CI：

`36503703348` — success

验收文档分支 CI：

`36524473446` — success

最终 Freeze 内容提交和合并后 main SHA 以 GitHub 实际历史为准；Stage 3 开始时必须重新读取，不得猜测。

## 版本

- versionCode：3
- versionName：`0.2.1-stage2`
- APK SHA-256：`cc96ad74763b5e35fbec3498c8224c2265e12babf518644a2b6d7716bce093cc`

## 当前模块

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

### :core:protocol

纯 Kotlin/JVM。

新增 Stage 2 能力：

- DeviceTime
- Sync Time Builder
- BatteryState
- Battery 110 Charging
- Firmware decoder
- Auth decoder

### :core:ble

核心类包括：

- BleUuids
- BlePermissionPolicy
- AndroidBleScanner
- BleScanAccumulator
- GattOperation / GattOperationQueue
- AndroidGattBackend
- NotificationRouter
- DeviceCommandClient
- AndroidDeviceSession
- DeviceRepository / DefaultDeviceRepository
- ReconnectPolicy
- MtuPolicy
- BLE diagnostics models

### :app

- VoicaApplication
- AppContainer
- MainActivity
- DeviceViewModel
- Compose Device Screen
- Runtime permission flow
- 中文 BLE Diagnostics

## 关键 BLE Contract

### UUID

- AE20：`0000ae20-0000-1000-8000-00805f9b34fb`
- AE21：`0000ae21-0000-1000-8000-00805f9b34fb`
- AE22：`0000ae22-0000-1000-8000-00805f9b34fb`
- AE23：`0000ae23-0000-1000-8000-00805f9b34fb`
- CCCD：`00002902-0000-1000-8000-00805f9b34fb`

### GATT

- AE21：WRITE_NO_RESPONSE
- AE22：NOTIFY
- AE23：NOTIFY
- 所有异步 GATT 操作严格串行
- 每次 connection 有 generation
- stale callback 必须忽略

### MTU

- Android request：517
- 真机 actual：517
- MTU <39：不得 Ready
- 39..170：基础控制
- >=171：当前已知 168B 数据通道能力
- 36B File Import Request 后续必须一次完整 write，不得应用层拆分

### Notifications

AE22 / AE23 必须使用两个独立 FrameParser。

但 **不要假设 control response 一定只在 AE22**。

真机已经证明：

Battery Response 0/4 可以从 AE23 返回。

因此当前架构：

`AE22 parser / AE23 parser → ProtocolFrame → shared pending matcher`

pending matcher：

- 强匹配 TYPE/CMD
- sequence 只记录 diagnostics

不要在 Stage 3 擅自改成 sequence 强匹配，除非有新的可重复真机证据。

## Device Info

已通过：

- Battery
- Charging
- Capacity
- Firmware
- Auth
- Time Sync

Battery：

- Request：TYPE=0 CMD=3
- Response：TYPE=0 CMD=4
- 0..100：电量
- 110：Charging

## Reconnect

冻结策略：

- Remote：1s → 2s → 4s，最多 3 次
- User Disconnect：不重连
- Bluetooth Off：取消 scan/reconnect、释放 GATT
- 前台恢复时可恢复有限重连
- 无无限后台 reconnect
- Stage 2 无 Foreground Service

## 测试事实

第一轮真机主链路：PASS。

第二轮稳定性/异常链路：PASS。

包括：

- 连续连接/断开 >=10 次
- Device Off
- Out of Range
- Retry cap
- Bluetooth Off/On
- foreground/background >=5 次
- 页面切换
- 无 Crash
- 无 ANR

详见：

`docs/STAGE_2_TEST.md`

## Stage 3 目标边界

Stage 3 才开始：

- 获取录音状态
- 开始录音
- 暂停
- 恢复
- 停止保存
- 当前录音时长
- 当前录音文件名
- AE23 物理按键事件
- App 控制与设备物理按键状态统一

Stage 3 **不要提前实现**：

- 文件下载
- Opus/WAV
- Room
- 播放器
- ASR/VAD/标点
- Speaker
- AI

## Stage 3 接管流程

新 Agent 必须：

1. 检查 `main` 当前真实 HEAD、branch、PR、CI。
2. 阅读 `AGENTS.md`。
3. 阅读 `docs/STAGE_2_FREEZE.md`、`docs/STAGE_2_HANDOFF.md`、`docs/STAGE_2_TEST.md`。
4. 阅读当前 `docs/ROADMAP.md` 和 `docs/ARCHITECTURE.md`。
5. 阅读实际源码，不只依赖本 Handoff。
6. 先输出 Stage 3 修订需求并等待确认。
7. 再输出 Stage 3 修订开发规划并等待第二次确认。
8. 确认后才允许编码。
