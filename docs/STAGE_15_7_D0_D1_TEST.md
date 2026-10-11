# Stage 15.7 — TYPE3 / TYPE1 真机 Fact Gate

状态：**D0 / D1 / D1A / D1B / D1C 已确认；D2 待真机验证**

## 1. 范围与原则

本 Gate 只验证 QS668 / CB08 当前真机上的两类控制行为：

- TYPE=3：设备 recording / file lifecycle；
- TYPE=1：BLE realtime audio lifecycle。

不得扩展为 Stage 16 streaming ASR 产品逻辑。

所有 QA Probe 必须调用现有生产 `DeviceRepository` / `RealtimeAudioRuntime` 入口；不得为了得到期望结果绕过 production guard 直接发送 raw TYPE3 命令。

真机重复事实优先于先验协议假设。

## 2. D0 — TYPE3-only Passive TYPE1 Gate（已确认）

固定流程：

`TYPE3 START → 录音 8–10 秒 → TYPE3 SAVE`

真机事实：

```text
D0 sourceDelta started=0 ended=0 fileCountDelta=1
D0 invariant noRealtimeControl=true passiveType1Observed=false
```

冻结结论：

- TYPE3 普通录音可以独立创建 1 个设备录音文件；
- TYPE3 START 不会隐式启动 TYPE1 realtime audio；
- TYPE1 realtime 必须由 App 显式 START。

```text
D0 factStatus=RESOLVED
passiveType1DuringType3=false
conclusion=TYPE1_REQUIRES_EXPLICIT_REALTIME_START
```

## 3. 首轮 D1 — TYPE3 + TYPE1 共存事实（已确认）

原固定顺序：

`TYPE3 START → realtime START → realtime STOP → production saveRecording()`

冻结结论：

- TYPE3 已处于 Recording 时，显式 realtime START 成功；
- realtime START 后设备仍为 Recording；
- realtime media pipeline 健康；
- realtime STOP 正常闭合 canonical source；
- realtime STOP 后设备 recording 从 Recording 变为 Idle；
- 因此 TYPE1 STOP 与 TYPE3 recording lifecycle 并非独立。

```text
D1 factStatus=RESOLVED
TYPE3_PLUS_REALTIME_START_COEXISTENCE=true
REALTIME_STOP_PRESERVES_TYPE3=false
REALTIME_PIPELINE_HEALTHY=true
```

## 4. D1A — Realtime STOP Finalize Gate（已确认）

固定流程：

`刷新文件基线 → TYPE3 START → realtime START → 讲话 5–8 秒 → realtime STOP → sync recording → refresh files`

2026-10-12 真机：

```text
D1A TYPE3 status afterStart=Recording afterRealtimeStart=Recording afterRealtimeStop=Idle
D1A stopFiles count=21 deltaFromBaseline=2
D1A newFilesAfterRealtimeStop=[note20261012-021237.opus, note20261012-021250.opus]
D1A mediaDelta acceptedBlocks=168 packets=672 canonicalFrames=672 canonicalSamples=215040
D1A anomalyDelta ingressOverflow=0 malformed=0 dropped=0 decodeFailures=0 sourceOverflow=0
D1A source afterStop active=false lastEnd=STOP drainTimedOut=false
D1A factStatus=RESOLVED
```

冻结结论：

- TYPE3 Recording 与 TYPE1 realtime START 可以共存；
- TYPE1 STOP 会把设备 recording 置为 Idle；
- 混合会话最终新增 2 个不同时间戳的设备 `.opus` 文件；
- realtime 媒体、解码、canonical STOP lifecycle 均健康。

## 5. D1B — Production SAVE After STOP（已确认）

生产 `DeviceRepository.saveRecording()` 只在状态为 `Recording` / `Paused` 时发送 SAVE；状态已经为 Idle 时直接返回。

真机：

```text
D1B save attempted=true eligibleAtRequest=false statusBefore=Idle statusAfter=Idle
D1B files before=21 after=21 delta=0
D1B newFilesAfterSaveCall=[]
D1B facts productionSaveExpectedNoOp=true postStopSaveCreatedFiles=false postStopSaveNoOp=true
D1B factStatus=RESOLVED
```

冻结结论：

- D1A 两个新增文件全部形成于 D1B 调用之前；
- STOP 后 production SAVE 是 NO-OP；
- 不得绕过 guard 发送 raw SAVE 来改变这个生产事实。

## 6. D1C — TYPE1-only File Side-Effect Gate（已确认）

固定流程：

`Idle + 文件基线 → realtime START → 讲话 5–8 秒 → realtime STOP → sync recording → refresh files`

整个流程不调用 TYPE3 START / SAVE / PAUSE / RESUME。

2026-10-12 真机：

```text
D1C baseline recordingStatus=Idle
D1C afterRealtimeStart status=Recording sizeBytes=240 filename=note20261012-022603.opus
D1C afterRealtimeStop status=Idle sizeBytes=0 filename=note20261012-022603.opus
D1C mediaDelta acceptedBlocks=137 packets=548 canonicalFrames=548 canonicalSamples=175360
D1C anomalyDelta ingressOverflow=0 malformed=0 dropped=0 decodeFailures=0 sourceOverflow=0
D1C source afterStop active=false lastEnd=STOP drainTimedOut=false
D1C stopFiles count=24 deltaFromBaseline=1
D1C newFilesAfterRealtimeStop=[note20261012-022603.opus]
D1C facts baselineIdle=true type1ChangedRecordingState=true type1StopReturnedIdle=true
D1C facts type1OnlyCreatedFiles=true exactlyOneFileCreated=true
D1C executionValid=true
D1C factStatus=RESOLVED
```

### 6.1 D1C 冻结事实

TYPE1 realtime START **不是纯 BLE audio subscribe**。它同时：

- 把设备 recording state 从 Idle 改为 Recording；
- 分配 recording filename；
- 产生 recording size；
- 开启 TYPE1 realtime media；
- 在 TYPE1 STOP 后 finalize 1 个设备 `.opus` 文件并回到 Idle。

结合 D0 / D1A：

```text
D0   TYPE3-only  -> +1 file
D1C  TYPE1-only  -> +1 file
D1A  TYPE3+TYPE1 -> +2 files
```

因此 D1A 两个不同时间戳文件分别来源于两套 START lifecycle 的解释已经得到交叉验证支持。

## 7. 当前设备生命周期模型

截至 D1C，可冻结为：

```text
TYPE3 START
  -> 启动设备 recording/file lifecycle
  -> 不开启 TYPE1 realtime stream

TYPE1 START
  -> 启动设备 recording/file lifecycle
  -> 开启 TYPE1 realtime stream
  -> recording state = Recording

TYPE1 STOP
  -> 停止 TYPE1 realtime
  -> recording state = Idle
  -> finalize 当前相关 recording files
```

因此“TYPE1 / TYPE3 是完全独立控制面”的假设已经否定。

## 8. D2 — Production Reverse-Control Fact Gate

### 8.1 为什么原 D2 顺序需要修正

原先设想：

`realtime START → TYPE3 START → TYPE3 SAVE → realtime STOP`

但 D1C 已证明 realtime START 后 production recording state 已为 `Recording`。

当前生产 `DeviceRepository.startRecording()` guard 为：

- transport 必须 Ready；
- recording state 必须是 `Idle`；
- 不得存在活动 file operation。

所以在 realtime ACTIVE 时再次调用 production `startRecording()`，正常预期是 **NO-OP**，不会真实发送第二个 TYPE3 START。

相反，production `saveRecording()` 在 `Recording` / `Paused` 状态是 eligible，因此它可能真实发送 TYPE3 SAVE。

D2 不绕过这个 guard，而直接验证实际产品冲突：

> TYPE1 realtime ACTIVE 时，正常录音控制路径发出 production TYPE3 SAVE，会不会反向终止或静默切断 TYPE1 realtime media？

### 8.2 固定流程

```text
同步 recording + 刷新文件基线
→ realtime START
→ 讲话约 3 秒
→ production TYPE3 START 尝试
→ 继续讲话 5–8 秒
→ production TYPE3 SAVE
→ 继续讲话 3 秒并自动观察 realtime media
→ 显式 realtime STOP 清理
→ refresh final files
```

### 8.3 QA Surface

Launcher：`Voica Stage15 D2 Probe`

Activity：`Stage157D2ReverseControlProbeActivity`

D2 只调用：

- `RealtimeAudioRuntime.start()`；
- `DeviceRepository.startRecording()`；
- `DeviceRepository.saveRecording()`；
- `RealtimeAudioRuntime.stop()`；
- recording sync / file refresh。

不得直接调用 raw TYPE3 protocol。

### 8.4 D2 核心事实字段

生产 START guard：

```text
D2 TYPE3 start attempted=...
eligibleAtRequest=...
productionStartExpectedNoOp=...
```

TYPE3 SAVE 前后：

```text
D2 TYPE3 save attempted=...
eligibleAtRequest=...
statusBefore=...
statusAfter=...
```

保存后 3 秒媒体窗口：

```text
D2 runtime immediateAfterSave phase=... gateOpen=... sourceActive=...
D2 runtime postSaveWindow phase=... gateOpen=... sourceActive=...
D2 media postSaveWindow acceptedBlocks=...
canonicalFrames=...
canonicalSamples=...
```

判定字段：

```text
D2 facts productionType3StartNoOp=...
type3SaveEligible=...
type3SaveReturnedIdle=...
postSaveMediaContinued=...
runtimeStayedActiveAfterSave=...
type3SaveReverseStoppedMedia=...
D2 reverseEffect=...
D2 executionValid=...
D2 factStatus=...
```

### 8.5 `reverseEffect` 解释

#### `STREAM_CONTINUES`

TYPE3 SAVE 后 realtime runtime 仍 ACTIVE / gate open / source active，且保存后媒体计数继续增长。

说明：TYPE3 SAVE 可以结束设备 recording state，但 TYPE1 realtime media 继续出流。

#### `DEVICE_MEDIA_STOPS_RUNTIME_STAYS_ACTIVE`

TYPE3 SAVE 后 runtime 仍认为 ACTIVE，但保存后 3 秒 `acceptedBlocks/canonicalSamples` 不再增长。

说明：固件已停止 TYPE1 media，但 App runtime 没有收到对应 realtime STOP 状态转换。这是 Stage 15 lifecycle contract 必须处理的重要设备事实。

#### `RUNTIME_NOT_ACTIVE_AFTER_SAVE`

TYPE3 SAVE 后 runtime 本身不再 ACTIVE / gate closed / canonical source inactive。

需要根据 `lastEnd` / transport generation / command 状态判断是固件导致 transport lifecycle 改变，还是其他已定义关闭路径。

### 8.6 D2 真机步骤

1. 安装最新 Stage 15 QA APK。
2. 正常 Voica 连接 QS668 / CB08，等待 Ready。
3. 确认当前没有普通设备录音进行中。
4. 打开 `Voica Stage15 D2 Probe`，确认 Ready=true。
5. 点击 `重置D2`。
6. 点击 `D2-1 realtime START`；等待成功，讲话约 3 秒。
7. 点击 `D2-2 TYPE3开始尝试`；正常预期 production guard NO-OP。
8. 继续讲话 5–8 秒。
9. 点击 `D2-3 TYPE3保存`。
10. 点击后**持续讲话至少 3 秒**；Probe 会自动观察保存后的媒体增长。
11. 页面提示 D2-3 观察完成后，点击 `D2-4 realtime STOP` 做显式清理。
12. 等待最终文件刷新完成。
13. 点击 `复制D2 Gate`，提交完整文本。

测试期间不要按录音卡物理按键，不要打开其他 Stage15 Probe。

## 9. Stage 15.7 后续门禁

D2 真机事实完成前：

- 不修改 production BLE / Realtime lifecycle contract；
- 不创建 Stage 15 Final Freeze/Handoff；
- 不把 Stage 16 streaming ASR 行为建立在“TYPE1 是无副作用纯流”这一错误假设上。

D2 完成后，再依据 D0–D2 全部真机证据决定 Stage 15.7 是否需要生产 lifecycle 修订，或可直接进入 Stage 15 Final 收口。
