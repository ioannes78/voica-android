# Voica Stage 3 修订开发规划

状态：已确认（2026-09-29）

## 分支与基线

- 开发分支：`stage3-development`
- 基线：`main@e8e5625efdadc4d43c856a02443f7304b9c8fedd`
- Stage 3 版本：versionCode 4，versionName `0.3.0-stage3`

编码前已重新验证 main HEAD 与 Stage 2 Freeze 一致。

## 模块

不新增 Gradle module，继续：

`app → core:ble → core:protocol`

职责：

- `:core:protocol`：TYPE=3 frame builder、model、严格 decoder。
- `:core:ble`：请求/响应、AE22/AE23 routing、Recording state reducer、Session/Repository、polling/reconciliation。
- `:app`：ViewModel、Compose RecordingCard、简体中文字符串和 Diagnostics 展示。

## 实施阶段

1. **M3.1 Protocol Increment**
   - Start/Save/Pause/Resume/GetState/GetTime/GetFilename/GetGain/SetGain builder。
   - RecordingStatus / RecordingGain / RecordingTimeInfo。
   - strict decoder 和 golden tests。
2. **M3.2 BLE Routing**
   - 保留 AE22/AE23 response source。
   - TYPE/CMD matcher 不增加 source/sequence 强条件。
   - RecordingFrameRouter 区分 response、hardware event、malformed。
3. **M3.3 Unified Recording State**
   - RecordingDeviceState / Freshness / CommandState / HardwareEvent。
   - 纯 Kotlin RecordingStateReducer。
4. **M3.4 Session Commands**
   - 所有 TYPE=3 request 复用现有 DeviceCommandClient。
   - 不创建第二套 pending/request 框架。
5. **M3.5 Repository Reconciliation**
   - Ready 初始同步。
   - App command 后 GET_STATE 收敛。
   - physical event 后 debounce + resync。
   - reconnect / foreground return resync。
6. **M3.6 Duration Poller**
   - Ready + foreground + Recording 时约 1 秒 GET_TIME。
   - 单一 poll job，无重叠 request。
7. **M3.7 UI**
   - DeviceViewModel 暴露 recordingState。
   - Device 页新增 RecordingCard。
   - Idle / Recording / Paused / Unknown 按状态显示控制。
   - Gain 低/中/高，设置后回读。
8. **M3.8 Diagnostics / Regression**
   - source、raw values、sequence、latency、hardware event、sync reason、polling。
   - AE23 response regression、malformed payload、state reducer、polling policy。
9. **M3.9 Automated Validation**
   - `:core:protocol:test`
   - `:core:ble:testDebugUnitTest`
   - `:app:assembleDebug`
   - GitHub Actions。
10. **M3.10 APK + Real Device**
    - 生成 Debug APK。
    - QS668/CB08 依序验证 App 控制、物理按钮、断线重连、Bluetooth Off/On、前后台、竞争场景。
11. **M3.11 Freeze/Handoff**
    - 仅在用户明确“测试通过”后执行。

## Request / Response 路由

固定路径：

```
AE22 ─独立 parser─┐
                  ├→ RoutedNotification
AE23 ─独立 parser─┘
                         ├→ DeviceCommandClient pending matcher
                         └→ RecordingFrameRouter
                                     ↓
                            RecordingStateReducer
                                     ↓
                         StateFlow<RecordingDeviceState>
                                     ↓
                                  Compose
```

TYPE=3 response 可以来自 AE22 或 AE23。Pending 只要求 expected TYPE/CMD；sequence 和 source 均作为诊断证据，不作为强匹配条件。

## Command 流程

App Start/Pause/Resume/Save：

```
UI action
→ Repository commandState
→ AndroidDeviceSession
→ DeviceCommandClient
→ AE21
→ response / timeout
→ GET_STATE reconciliation
→ device truth
```

副作用命令 timeout 不自动重发。

## 初始同步

Ready 后录音同步顺序：

```
GET_STATE
GET_TIME
GET_FILENAME
GET_GAIN
```

GET_STATE 是 Mandatory；其余为辅助字段，失败要记录但不得 Crash。

## Polling

`RecordingPollingPolicy` 只有在 Ready + foreground + Recording 时返回 true。

Poller 每约 1000 ms 只执行 GET_TIME，不每秒查询状态/文件名/增益。

## 物理按键

候选 unsolicited CMD：

- 1 Start
- 3 Save
- 5 Pause
- 7 Resume

收到后：

```
record hardware event
→ mark state stale
→ short debounce
→ full recording resync
```

不直接把 event 当作最终状态。

## Unit Test

重点覆盖：

- TYPE=3 golden frame。
- Gain 1/2/3 与非法值。
- known / unknown status。
- LE duration/size 和 truncated payload。
- UTF-8 filename / NUL / empty。
- known / unknown gain。
- raw command result retention。
- AE23 response 完成 pending。
- response sequence 与 request sequence 不一致仍可匹配。
- unsolicited 3/1 不完成 pending 3/2。
- hardware-event routing。
- reducer Fresh/Stale/Failed 状态转换。
- disconnect 保留最后值但标记 Stale。
- polling policy。
- 增益 readback 后恢复 Fresh。
- Stage 1/2 既有 regression 全部继续通过。

## CI

长期保持单 Android PR CI：

- JDK 17
- Android platform 37.1
- Gradle 9.6
- concurrency + cancel-in-progress
- Unit Test + assembleDebug

默认 PR 不上传 APK。为当前工具环境增加显式 opt-in：只有手动 workflow_dispatch，或 PR 标题临时包含 `[APK]` 时才上传 Debug APK，artifact retention 3 天。正常开发提交仍不产生 APK artifact。

## 真机候选门禁

候选 APK 生成前必须：

1. Protocol tests 通过。
2. BLE unit tests 通过。
3. Debug assemble 通过。
4. PR CI 通过。

真机必须重点确认：

- 3/2、3/4、3/6、3/8、3/28 实际 success code。
- 3/20 status mapping。
- 3/22 endian、duration/size unit。
- 3/24 filename 编码、终止符和生命周期。
- 3/26 gain mapping。
- 物理按键 TYPE/CMD/body/sequence/source。
- TYPE=3 response 是否实际从 AE23 出现。
- App command response 与 physical event 的实际先后顺序。
- Recording / Paused 断线重连后的恢复。
- 前后台与页面切换无重复 GATT / poller。
- App 操作与物理按键竞争最终收敛。

## Freeze 门禁

在用户回复 **“测试通过”** 前：

- PR 保持 Draft。
- 不创建 `STAGE_3_TEST.md`。
- 不创建 `STAGE_3_FREEZE.md`。
- 不创建 `STAGE_3_HANDOFF.md`。
- 不 merge main。
- 不标记 Stage 3 完成。
