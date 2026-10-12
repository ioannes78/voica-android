# Stage 15.7 — TYPE3 / TYPE1 真机 Fact Gate

状态：**COMPLETE — D0 / D1 / D1A / D1B / D1C / D2 全部真机 RESOLVED**

## 1. 范围与原则

本 Gate 只验证 QS668 / CB08 当前真机上的两类控制行为：

- TYPE=3：设备 recording / file lifecycle；
- TYPE=1：BLE realtime audio lifecycle。

不得扩展为 Stage 16 streaming ASR 产品逻辑。

所有 QA Probe 均调用生产 `DeviceRepository` / `RealtimeAudioRuntime` 入口；不得为了得到期望结果绕过 production guard 直接发送 raw TYPE3 命令。

真机可重复事实优先于先验协议假设。

## 2. D0 — TYPE3-only Passive TYPE1 Gate

固定流程：

`TYPE3 START → 录音 8–10 秒 → TYPE3 SAVE`

2026-10-12 真机：

```text
D0 sourceDelta started=0 ended=0 fileCountDelta=1
D0 invariant noRealtimeControl=true passiveType1Observed=false
D0 factStatus=RESOLVED
```

冻结事实：

- TYPE3 普通录音独立创建 1 个设备录音文件；
- TYPE3 START 不会隐式启动 TYPE1 realtime audio；
- TYPE1 realtime 必须由 App 显式 START。

```text
passiveType1DuringType3=false
conclusion=TYPE1_REQUIRES_EXPLICIT_REALTIME_START
```

## 3. D1 / D1A — TYPE3 + TYPE1 共存与 TYPE1 STOP

D1A 固定流程：

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

冻结事实：

- TYPE3 已处于 Recording 时，显式 TYPE1 realtime START 可以成功；
- 两者可以在开始阶段共存；
- TYPE1 STOP 会把设备 recording state 从 Recording 变为 Idle；
- 混合会话最终新增 2 个不同时间戳的设备 `.opus` 文件；
- realtime 媒体 / decode / canonical STOP lifecycle 正常，无异常计数。

```text
TYPE3_PLUS_REALTIME_START_COEXISTENCE=true
REALTIME_STOP_PRESERVES_TYPE3=false
REALTIME_PIPELINE_HEALTHY=true
```

因此“TYPE1 / TYPE3 是完全独立控制面”的假设已被否定。

## 4. D1B — Production SAVE After TYPE1 STOP

生产 `DeviceRepository.saveRecording()` 只在 recording status 为 `Recording` / `Paused` 时发送 SAVE；状态已经为 Idle 时直接返回。

2026-10-12 真机：

```text
D1B save attempted=true eligibleAtRequest=false statusBefore=Idle statusAfter=Idle
D1B files before=21 after=21 delta=0
D1B newFilesAfterSaveCall=[]
D1B facts productionSaveExpectedNoOp=true postStopSaveCreatedFiles=false postStopSaveNoOp=true
D1B factStatus=RESOLVED
```

冻结事实：

- TYPE1 STOP 后 production recording state 已为 Idle；
- 后续 `saveRecording()` 是 production NO-OP；
- D1A 的两个新增文件都形成于后续 SAVE 调用之前；
- 不得绕过 guard 发送 raw SAVE 来改变这个事实。

## 5. D1C — TYPE1-only File Side-Effect Gate

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

冻结事实：

TYPE1 realtime START **不是纯 BLE audio subscribe**。它同时：

- 把设备 recording state 从 Idle 改为 Recording；
- 分配 recording filename；
- 产生 recording size；
- 开启 TYPE1 realtime media；
- TYPE1 STOP 后 finalize 1 个设备 `.opus` 文件并回到 Idle。

交叉验证：

```text
D0   TYPE3-only  -> +1 file
D1C  TYPE1-only  -> +1 file
D1A  TYPE3+TYPE1 -> +2 files
```

因此 D1A 两个不同时间戳文件由两套 START lifecycle 形成的解释得到隔离验证支持。

## 6. D2 — Production Reverse-Control Fact Gate

### 6.1 目的

D1C 已证明 TYPE1 START 后 production recording state 已为 `Recording`，所以此时 production `startRecording()` 会被 Idle guard 拒绝；production `saveRecording()` 反而满足 `Recording/Paused` guard。

D2 不绕过 guard，只验证实际产品冲突：

> TYPE1 realtime ACTIVE 时，production TYPE3 SAVE 是否反向停止设备 TYPE1 realtime media，以及 App runtime 是否同步收敛。

固定流程：

```text
Idle + 文件基线
→ realtime START
→ 讲话约 3 秒
→ production TYPE3 START 尝试
→ 继续讲话 5–8 秒
→ production TYPE3 SAVE
→ 保存后继续讲话 3 秒
→ 观察 media/runtime
→ explicit realtime STOP 仅作测试清理
→ refresh files
```

### 6.2 2026-10-12 真机结果

```text
Stage 15.7-D2 Production Reverse-Control Fact Gate
connectionReady=true
recording currentStatus=Idle sizeBytes=0 filename=note20261012-024107.opus
recording freshness=FRESH commandState=IDLE error=--
D2 baseline generation=1 recordingStatus=Idle sizeBytes=0 filename=--
D2 baselineFiles count=24 freshness=FRESH error=--
D2 realtime start=true/-- afterStartStatus=Recording filename=note20261012-024107.opus sizeBytes=360
D2 TYPE3 start attempted=true eligibleAtRequest=false statusBefore=Recording statusAfter=Recording
D2 TYPE3 start after filename=note20261012-024107.opus sizeBytes=8600 productionStartExpectedNoOp=true
D2 TYPE3 save attempted=true eligibleAtRequest=true statusBefore=Recording statusAfter=Idle
D2 afterSave recording filename=note20261012-024107.opus sizeBytes=0 postWindowStatus=Idle
D2 runtime immediateAfterSave phase=ACTIVE gateOpen=true sourceActive=true
D2 runtime postSaveWindow phase=ACTIVE gateOpen=true sourceActive=true
D2 media preSave acceptedBlocks=184 canonicalSamples=235200
D2 media postSaveWindow acceptedBlocks=0 packets=0 canonicalFrames=0 canonicalSamples=0
D2 anomaly postSaveWindow ingressOverflow=0 malformed=0 dropped=0 decodeFailures=0 sourceOverflow=0
D2 cleanup realtimeStop attempted=true outcome=true/-- recordingStatus=Idle
D2 commandDelta start=1 stop=1 finalRuntimePhase=IDLE gateOpen=false
D2 files finalCount=25 deltaFromBaseline=1 freshness=FRESH error=--
D2 newFiles=[note20261012-024107.opus]
D2 facts baselineIdle=true realtimeStartChangedRecordingState=true productionType3StartNoOp=true type3SaveEligible=true type3SaveReturnedIdle=true
D2 facts preSaveMediaObserved=true postSaveMediaContinued=false runtimeStayedActiveAfterSave=true cleanPostSaveWindow=true type3SaveReverseStoppedMedia=true finalLifecycleClosed=true
D2 reverseEffect=DEVICE_MEDIA_STOPS_RUNTIME_STAYS_ACTIVE
D2 executionValid=true
D2 factStatus=RESOLVED
```

### 6.3 D2 冻结事实

- TYPE1 realtime START 后 recording state = Recording；
- 此时 production TYPE3 START 因 Idle guard 不满足而 NO-OP；
- production TYPE3 SAVE 是 eligible，并真实执行；
- TYPE3 SAVE 后设备 recording state 立即回到 Idle；
- TYPE3 SAVE 后设备停止发送 TYPE1 realtime media；
- 保存后 3 秒窗口 `acceptedBlocks / packets / canonicalFrames / canonicalSamples` 全部增量为 0；
- 但当前 App `RealtimeAudioRuntime` 仍错误保持：
  - `phase=ACTIVE`
  - `gateOpen=true`
  - `sourceActive=true`
- D2 最后显式 realtime STOP 成功，说明已有 STOP 路径能够把悬空 runtime 正常闭合；
- D2 只形成 1 个设备文件 `note20261012-024107.opus`。

正式结论：

```text
D2 reverseEffect=DEVICE_MEDIA_STOPS_RUNTIME_STAYS_ACTIVE
```

即：**设备 recording/file lifecycle 与 TYPE1 media lifecycle 在固件侧双向耦合，但 TYPE3 SAVE 导致的 realtime media 终止没有被 App runtime 自动感知。**

## 7. Stage 15.7 最终设备生命周期模型

```text
TYPE3 START
  -> device Recording/file lifecycle starts
  -> TYPE1 realtime media does NOT start

TYPE3 SAVE while TYPE1 inactive
  -> device Recording -> Idle
  -> one TYPE3 recording file finalized

TYPE1 START
  -> device Recording/file lifecycle starts
  -> TYPE1 realtime media starts
  -> App runtime ACTIVE

TYPE1 STOP
  -> TYPE1 realtime media stops
  -> device Recording -> Idle
  -> App runtime drains canonical source -> IDLE

TYPE3 START while TYPE1 ACTIVE
  -> production guard NO-OP because device already Recording

TYPE3 SAVE while TYPE1 ACTIVE
  -> device Recording -> Idle
  -> device TYPE1 media stops
  -> pre-15.8 App runtime incorrectly remains ACTIVE / gateOpen / sourceActive
```

## 8. Stage 15.7 Final Status

```text
D0  RESOLVED
D1  RESOLVED
D1A RESOLVED
D1B RESOLVED
D1C RESOLVED
D2  RESOLVED
Stage15.7=COMPLETE
```

Stage 15.7 不再增加协议事实 Probe。

D2 暴露的生产缺口进入独立收口项：

**Stage 15.8 — Realtime / Recording Lifecycle Reconciliation**。

Stage 15.8 的目标是：当可信 `FRESH Recording/Paused -> FRESH Idle` 已证明设备 recording lifecycle 结束，而 realtime runtime 仍处于 ACTIVE/PAUSED 时，自动把 realtime runtime / gate / canonical lifecycle 收敛到终态；同时避免 explicit realtime STOP、TYPE3-only 和 stale recording state 触发误收敛。

Stage 15.8 真机验收通过前：

- 不创建 Stage 15 Final Freeze/Handoff；
- 不进入 Stage 16 streaming ASR 产品实现；
- Room schema / production model channel / app version identity保持不变。
